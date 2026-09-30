package io.github.rmdodhia.gesturelauncher.books

import android.app.Activity
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import io.github.rmdodhia.gesturelauncher.core.Book
import io.github.rmdodhia.gesturelauncher.core.BookApp
import io.github.rmdodhia.gesturelauncher.core.Links
import io.github.rmdodhia.gesturelauncher.data.ErrorLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Lists Play Books "Reading now" and "My books" through the official Google Books API. */
class PlayBooksSource(private val context: Context) : BookSource {
    override val app = BookApp.PLAY_BOOKS

    // Main-thread only. The share sheet can start a second MainActivity, so the launcher follows whichever
    // activity is in front and is dropped when that activity is destroyed.
    private var launcher: ActivityResultLauncher<IntentSenderRequest>? = null
    private var pending: CompletableDeferred<ActivityResult>? = null
    private var pendingLauncher: ActivityResultLauncher<IntentSenderRequest>? = null

    override fun attach(activity: ComponentActivity) {
        val l = activity.registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
            pending?.complete(r)
            pending = null
        }
        launcher = l
        activity.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onResume(owner: LifecycleOwner) {
                launcher = l
            }

            override fun onDestroy(owner: LifecycleOwner) {
                if (launcher === l) launcher = null
                if (pendingLauncher === l) {
                    pending?.complete(ActivityResult(Activity.RESULT_CANCELED, null))
                    pending = null
                }
            }
        })
    }

    override suspend fun fetch(interactive: Boolean): FetchResult = try {
        when (val t = token(interactive)) {
            is Token.Ok -> fetchWith(t.value, retryOnAuthError = true)
            Token.NeedsConsent -> FetchResult.NeedsConsent
            is Token.Failed -> FetchResult.Failed(t.message)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        ErrorLog.record("play books fetch", e)
        FetchResult.Failed("Couldn't reach Google Books: ${e.message ?: e::class.simpleName}")
    }

    private suspend fun fetchWith(token: String, retryOnAuthError: Boolean): FetchResult {
        val reading = when (val r = shelf(token, PlayBooks.SHELF_READING_NOW)) {
            is ShelfResult.Ok -> r.items
            is ShelfResult.Http -> return httpFailure(r, token, retryOnAuthError)
        }
        val owned = when (val r = shelf(token, PlayBooks.SHELF_MY_EBOOKS)) {
            is ShelfResult.Ok -> r.items
            is ShelfResult.Http -> return httpFailure(r, token, retryOnAuthError)
        }
        return FetchResult.Books(PlayBooks.merge(reading, owned))
    }

    private suspend fun httpFailure(r: ShelfResult.Http, token: String, retry: Boolean): FetchResult {
        if (r.code == 401 && retry) {
            // Cached token expired or was revoked: drop it and get a fresh one.
            Identity.getAuthorizationClient(context).clearToken(ClearTokenRequest.builder().setToken(token).build()).await()
            return when (val t = token(interactive = false)) {
                is Token.Ok -> fetchWith(t.value, retryOnAuthError = false)
                Token.NeedsConsent -> FetchResult.NeedsConsent
                is Token.Failed -> FetchResult.Failed(t.message)
            }
        }
        return FetchResult.Failed(PlayBooks.errorMessage(r.code, r.body))
    }

    private sealed interface Token {
        data class Ok(val value: String) : Token
        data object NeedsConsent : Token
        data class Failed(val message: String) : Token
    }

    private suspend fun token(interactive: Boolean): Token {
        val client = Identity.getAuthorizationClient(context)
        val request = AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(PlayBooks.SCOPE))).build()
        val result = client.authorize(request).await()
        result.accessToken?.let { return Token.Ok(it) }
        val intent = result.pendingIntent
        if (!result.hasResolution() || intent == null) return Token.Failed("Google didn't grant access to Play Books.")
        if (!interactive) return Token.NeedsConsent
        val done = CompletableDeferred<ActivityResult>()
        val launched = withContext(Dispatchers.Main) {
            val l = launcher ?: return@withContext false
            pending?.cancel()
            pending = done
            pendingLauncher = l
            l.launch(IntentSenderRequest.Builder(intent.intentSender).build())
            true
        }
        if (!launched) return Token.Failed("Open Gesture Launcher and try again.")
        val r = done.await()
        if (r.resultCode != Activity.RESULT_OK) return Token.Failed("Play Books access was not granted.")
        val granted = client.getAuthorizationResultFromIntent(r.data).accessToken
        return granted?.let { Token.Ok(it) } ?: Token.Failed("Google didn't return an access token.")
    }

    private sealed interface ShelfResult {
        data class Ok(val items: List<Book>) : ShelfResult
        data class Http(val code: Int, val body: String) : ShelfResult
    }

    private suspend fun shelf(token: String, shelfId: Int): ShelfResult = withContext(Dispatchers.IO) {
        val all = mutableListOf<Book>()
        var start = 0
        while (start < PlayBooks.MAX_BOOKS) {
            val url = URL("${PlayBooks.API}/mylibrary/bookshelves/$shelfId/volumes?maxResults=${PlayBooks.PAGE}&startIndex=$start")
            val conn = url.openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 15_000
                conn.readTimeout = 20_000
                conn.setRequestProperty("Authorization", "Bearer $token")
                conn.setRequestProperty("Accept", "application/json")
                val code = conn.responseCode
                if (code != 200) {
                    val body = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                    return@withContext ShelfResult.Http(code, body)
                }
                val page = PlayBooks.parseShelf(conn.inputStream.bufferedReader().use { it.readText() })
                all += page.books
                start += PlayBooks.PAGE
                if (page.books.isEmpty() || start >= page.totalItems) break
            } finally {
                conn.disconnect()
            }
        }
        ShelfResult.Ok(all)
    }
}

/** Pure parts of the Play Books sync, kept separate so they can be unit-tested. */
object PlayBooks {
    const val SCOPE = "https://www.googleapis.com/auth/books"
    const val API = "https://www.googleapis.com/books/v1"
    const val SHELF_READING_NOW = 3
    const val SHELF_MY_EBOOKS = 7
    const val PAGE = 40
    const val MAX_BOOKS = 1000
    const val ENABLE_API_URL = "https://console.cloud.google.com/apis/library/books.googleapis.com"

    private val json = Json { ignoreUnknownKeys = true }

    data class Page(val books: List<Book>, val totalItems: Int)

    fun parseShelf(body: String): Page {
        val root = json.parseToJsonElement(body).jsonObject
        val items = (root["items"] as? JsonArray).orEmpty()
        val books = items.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val id = o["id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val info = o["volumeInfo"] as? JsonObject
            val title = info?.get("title")?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() } ?: id
            val author = (info?.get("authors") as? JsonArray)
                ?.mapNotNull { it.jsonPrimitive.contentOrNull?.trim()?.takeIf(String::isNotEmpty) }
                ?.takeIf { it.isNotEmpty() }
                ?.joinToString(", ")
            Book(BookApp.PLAY_BOOKS, id, title, author, Links.playBooksUri(id), synced = true)
        }
        val total = root["totalItems"]?.jsonPrimitive?.intOrNull ?: books.size
        return Page(books, total)
    }

    /** Reading-now books first (flagged), then the rest of the library; each book once. */
    fun merge(reading: List<Book>, owned: List<Book>): List<Book> =
        (reading.map { it.copy(reading = true) } + owned).distinctBy { it.key }

    fun errorMessage(code: Int, body: String): String {
        val message = runCatching {
            json.parseToJsonElement(body).jsonObject["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
        }.getOrNull()
        return when {
            code == 403 && message != null && (message.contains("has not been used") || message.contains("disabled")) ->
                "Turn on the Books API for your Google Cloud project, then tap Refresh: $ENABLE_API_URL"
            code == 401 -> "Google sign-in expired. Tap Connect Play Books again."
            code == 429 -> "Google Books is rate-limiting requests. Try again in a minute."
            message != null -> "Google Books error $code: $message"
            else -> "Google Books error $code."
        }
    }
}

private fun JsonArray?.orEmpty(): List<kotlinx.serialization.json.JsonElement> = this ?: emptyList()

internal suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnCompleteListener { t ->
        val e = t.exception
        when {
            e != null -> cont.resumeWithException(e)
            t.isCanceled -> cont.cancel()
            else -> cont.resume(t.result)
        }
    }
}
