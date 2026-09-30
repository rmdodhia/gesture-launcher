package io.github.rmdodhia.gesturelauncher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LinksAndBuilderTest {
    @Test
    fun asinExtraction() {
        assertEquals("B00ABCDEFG", Links.extractAsin("b00abcdefg"))
        assertEquals("B07XYZ1234", Links.extractAsin("https://www.amazon.com/Some-Book/dp/B07XYZ1234/ref=sr_1_1"))
        assertEquals("B07XYZ1234", Links.extractAsin("https://read.amazon.com/?asin=B07XYZ1234&x=1"))
        assertNull(Links.extractAsin("https://www.amazon.com/"))
        assertNull(Links.extractAsin("hello"))
    }

    @Test
    fun playBooksIdExtraction() {
        assertEquals("abcDEF_123", Links.extractPlayBooksId("https://play.google.com/store/books/details?id=abcDEF_123&hl=en"))
        assertEquals("abcDEF_123", Links.extractPlayBooksId("abcDEF_123"))
        assertNull(Links.extractPlayBooksId("https://play.google.com/store/books"))
        assertNull(Links.extractPlayBooksId("not an id!"))
    }

    @Test
    fun bookFromActions() {
        // Kindle for Samsung (Galaxy Store) shares with a different package name.
        val kindle = Links.actionFromShare("Dune https://read.amazon.com/kp/kshare?asin=B00B7NPRY8&id=x", "Dune", Links.KINDLE_SAMSUNG_PACKAGE)!!
        val kb = Links.bookFrom(kindle)!!
        assertEquals(Book(BookApp.KINDLE, "B00B7NPRY8", "Dune", uri = "kindle://book?action=open&asin=B00B7NPRY8"), kb)
        assertEquals(OpenUri(kb.uri, Links.KINDLE_PACKAGE, "Dune"), Links.actionFor(kb))

        val play = Links.bookFrom(OpenUri("https://play.google.com/store/books/details?id=XyZ123abc", Links.PLAY_BOOKS_PACKAGE, "Emma"))!!
        assertEquals(BookApp.PLAY_BOOKS, play.app)
        assertEquals("XyZ123abc", play.id)
        assertEquals("https://play.google.com/books/reader?id=XyZ123abc", play.uri)

        val libbyUrl = "https://share.libbyapp.com/title/123456"
        val libby = Links.bookFrom(Links.actionFromShare(libbyUrl, "Circe", null)!!)!!
        assertEquals(Book(BookApp.LIBBY, "123456", "Circe", uri = libbyUrl), libby)
        assertEquals(Links.LIBBY_PACKAGE, Links.actionFor(libby).packageName)

        // Share links naming the library are rewritten to the in-app title page (share.libbyapp.com opens the browser).
        val shared = Links.actionFromShare("Circe on Libby https://share.libbyapp.com/title/123456#library-nypl", null, null)!!
        assertEquals("https://libbyapp.com/library/nypl/everything/page-1/123456", shared.uri)
        assertEquals(libby.key, Links.bookFrom(shared)!!.key)

        assertNull(Links.bookFrom(OpenUri("https://example.com", null, "x")))
        // Short Amazon link: no ASIN, so it's not treated as a book.
        assertNull(Links.bookFrom(Links.actionFromShare("https://a.co/d/abc123", null, Links.KINDLE_PACKAGE)!!))
    }

    @Test
    fun equivalentPackagesCoverBothKindles() {
        assertEquals(listOf(Links.KINDLE_PACKAGE, Links.KINDLE_SAMSUNG_PACKAGE), Links.equivalentPackages(Links.KINDLE_PACKAGE))
        assertEquals(listOf(Links.KINDLE_SAMSUNG_PACKAGE, Links.KINDLE_PACKAGE), Links.equivalentPackages(Links.KINDLE_SAMSUNG_PACKAGE))
        assertEquals(listOf("com.x"), Links.equivalentPackages("com.x"))
    }

    @Test
    fun shareParsing() {
        val kindle = Links.actionFromShare("Check out Dune https://www.amazon.com/dp/B00B7NPRY8.", null, null)!!
        assertEquals("kindle://book?action=open&asin=B00B7NPRY8", kindle.uri)
        assertEquals(Links.KINDLE_PACKAGE, kindle.packageName)
        assertEquals("Check out Dune", kindle.label)

        val play = Links.actionFromShare("https://play.google.com/store/books/details?id=XyZ123abc", "Dune", null)!!
        assertEquals(Links.PLAY_BOOKS_PACKAGE, play.packageName)
        assertEquals("Dune", play.label)

        val libby = Links.actionFromShare("https://libbyapp.com/library/foo/everything/page-1/123", null, null)!!
        assertEquals(Links.LIBBY_PACKAGE, libby.packageName)

        val other = Links.actionFromShare("see https://example.com/x", null, "com.some.app")!!
        assertNull(other.packageName)
        assertEquals("https://example.com/x", other.uri)

        assertNull(Links.actionFromShare("no link here", null, null))
        // Short Amazon links have no ASIN; fall back to plain link rather than failing.
        assertEquals("https://a.co/d/abc123", Links.actionFromShare("https://a.co/d/abc123", null, Links.KINDLE_PACKAGE)!!.uri)
    }

    @Test
    fun builderTracksMultiplePointers() {
        val b = SampleBuilder(1000)
        b.down(1, 0f, 0f, 1000)
        b.down(2, 100f, 0f, 1010)
        b.move(1, 10f, 0f, 1020)
        b.move(2, 110f, 0f, 1020)
        b.move(2, 110f, 0f, 1030) // duplicate position ignored
        b.up(1, 20f, 0f, 1040)
        assertTrue(b.hasActivePointers)
        b.up(2, 120f, 0f, 1050)
        assertFalse(b.hasActivePointers)
        b.move(3, 0f, 0f, 1060) // unknown pointer ignored
        val s = b.build(2f)
        assertEquals(2, s.tracks.size)
        assertEquals(listOf(0L, 20L, 40L), s.tracks[0].points.map { it.t })
        assertEquals(3, s.tracks[1].points.size)
        assertEquals(2f, s.density)
        assertEquals(2, FeatureExtractor.analyze(s).fingerCount)
    }

    @Test
    fun builderHandlesMissedUp() {
        val b = SampleBuilder(0)
        b.down(1, 0f, 0f, 0)
        b.down(1, 5f, 5f, 100) // same id down again without up
        b.cancelActive()
        assertEquals(2, b.build(1f).tracks.size)
    }
}
