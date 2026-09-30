package io.github.rmdodhia.gesturelauncher.books

import androidx.activity.ComponentActivity
import io.github.rmdodhia.gesturelauncher.core.Book
import io.github.rmdodhia.gesturelauncher.core.BookApp
import io.github.rmdodhia.gesturelauncher.data.Store
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PlayBooksTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun parsesShelfJson() {
        val body = """
            {"kind":"books#volumes","totalItems":3,"items":[
              {"id":"vol1","volumeInfo":{"title":"Dune","authors":["Frank Herbert"],"imageLinks":{"thumbnail":"x"}}},
              {"id":"vol2","volumeInfo":{"title":"  ","authors":["A","B"]}},
              {"volumeInfo":{"title":"no id"}},
              {"id":"vol3"}
            ]}
        """.trimIndent()
        val page = PlayBooks.parseShelf(body)
        assertEquals(3, page.totalItems)
        assertEquals(
            listOf(
                Book(BookApp.PLAY_BOOKS, "vol1", "Dune", "Frank Herbert", "https://play.google.com/books/reader?id=vol1", synced = true),
                Book(BookApp.PLAY_BOOKS, "vol2", "vol2", "A, B", "https://play.google.com/books/reader?id=vol2", synced = true),
                Book(BookApp.PLAY_BOOKS, "vol3", "vol3", null, "https://play.google.com/books/reader?id=vol3", synced = true),
            ),
            page.books,
        )
        // Empty shelves have no "items" key at all.
        assertEquals(PlayBooks.Page(emptyList(), 0), PlayBooks.parseShelf("""{"kind":"books#volumes","totalItems":0}"""))
    }

    @Test
    fun mergeFlagsReadingAndDeduplicates() {
        fun b(id: String) = Book(BookApp.PLAY_BOOKS, id, id, uri = id, synced = true)
        val merged = PlayBooks.merge(listOf(b("a")), listOf(b("b"), b("a")))
        assertEquals(listOf("a" to true, "b" to false), merged.map { it.id to it.reading })
    }

    @Test
    fun errorMessages() {
        val disabled = """{"error":{"code":403,"message":"Books API has not been used in project 1 before or it is disabled."}}"""
        assertTrue(PlayBooks.errorMessage(403, disabled).contains(PlayBooks.ENABLE_API_URL))
        assertEquals("Google Books error 500: boom", PlayBooks.errorMessage(500, """{"error":{"message":"boom"}}"""))
        assertEquals("Google Books error 502.", PlayBooks.errorMessage(502, "<html>"))
    }

    private class FakeSource(var result: FetchResult) : BookSource {
        override val app = BookApp.PLAY_BOOKS
        var calls = 0
        override fun attach(activity: ComponentActivity) = Unit
        override suspend fun fetch(interactive: Boolean): FetchResult {
            calls++
            return result
        }
    }

    @Test
    fun librarySyncStoresBooksAndThrottlesSilentRefresh() = runTest {
        val store = Store(File(tmp.root, "g.json"))
        val book = Book(BookApp.PLAY_BOOKS, "v", "V", uri = "u")
        val source = FakeSource(FetchResult.NeedsConsent)
        val lib = Library(store, listOf(source))

        assertEquals(SyncState.NeedsConsent, lib.sync(source, interactive = false, now = 0))
        source.result = FetchResult.Books(listOf(book))
        assertEquals(SyncState.Done(1), lib.sync(source, interactive = true, now = 1))
        assertEquals(listOf(book.copy(synced = true)), store.data.value.books)

        // Opening the picker again soon after doesn't hit the network; an explicit refresh does.
        assertNull(lib.sync(source, interactive = false, now = 60_000))
        assertEquals(2, source.calls)
        source.result = FetchResult.Failed("nope")
        assertEquals(SyncState.Failed("nope"), lib.sync(source, interactive = true, now = 60_000))
        assertEquals(1, store.data.value.books.size)
    }
}
