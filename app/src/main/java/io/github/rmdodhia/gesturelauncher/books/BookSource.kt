package io.github.rmdodhia.gesturelauncher.books

import androidx.activity.ComponentActivity
import io.github.rmdodhia.gesturelauncher.core.Book
import io.github.rmdodhia.gesturelauncher.core.BookApp
import io.github.rmdodhia.gesturelauncher.data.ErrorLog
import io.github.rmdodhia.gesturelauncher.data.Store
import kotlinx.coroutines.CancellationException

sealed interface FetchResult {
    data class Books(val books: List<Book>) : FetchResult
    /** The user must approve access (only returned when fetching non-interactively). */
    data object NeedsConsent : FetchResult
    data class Failed(val message: String) : FetchResult
}

/** A service that can list the user's books automatically. Implementations never throw from [fetch]. */
interface BookSource {
    val app: BookApp

    /** Must be called from Activity.onCreate (registers an activity-result launcher for consent screens). */
    fun attach(activity: ComponentActivity)

    /** [interactive] = allowed to show a consent/account screen. */
    suspend fun fetch(interactive: Boolean): FetchResult
}

sealed interface SyncState {
    data object Idle : SyncState
    data object Syncing : SyncState
    data object NeedsConsent : SyncState
    data class Done(val count: Int) : SyncState
    data class Failed(val message: String) : SyncState
}

/** The unified book shelf: books synced from [sources] plus books the user shared/added by hand. */
class Library(private val store: Store, val sources: List<BookSource>) {
    private val lastSync = mutableMapOf<BookApp, Long>()

    /** Fetches from [source] and stores the result. Returns null if a silent refresh was skipped (recent sync). */
    suspend fun sync(source: BookSource, interactive: Boolean, now: Long = System.currentTimeMillis()): SyncState? {
        if (!interactive && lastSync[source.app]?.let { now - it < SILENT_REFRESH_MS } == true) return null
        return try {
            when (val r = source.fetch(interactive)) {
                is FetchResult.Books -> {
                    store.syncBooks(source.app, r.books)
                    lastSync[source.app] = now
                    SyncState.Done(r.books.size)
                }
                FetchResult.NeedsConsent -> SyncState.NeedsConsent
                is FetchResult.Failed -> SyncState.Failed(r.message)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ErrorLog.record("book sync ${source.app}", e)
            SyncState.Failed("Couldn't update ${source.app.label}: ${e.message ?: e::class.simpleName}")
        }
    }

    suspend fun add(book: Book) = store.addBook(book)
    suspend fun remove(key: String) = store.removeBook(key)
    suspend fun setReading(key: String, reading: Boolean) = store.setReading(key, reading)

    private companion object {
        const val SILENT_REFRESH_MS = 10 * 60 * 1000L
    }
}
