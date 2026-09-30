package io.github.rmdodhia.gesturelauncher.core

import java.net.URLDecoder

object Links {
    const val KINDLE_PACKAGE = "com.amazon.kindle"
    /** Kindle from the Samsung Galaxy Store; same app, different package. */
    const val KINDLE_SAMSUNG_PACKAGE = "com.amazon.kindlefs"
    val KINDLE_PACKAGES = listOf(KINDLE_PACKAGE, KINDLE_SAMSUNG_PACKAGE)
    const val PLAY_BOOKS_PACKAGE = "com.google.android.apps.books"
    const val LIBBY_PACKAGE = "com.overdrive.mobile.android.libby"

    private val ASIN_ALONE = Regex("^[A-Z0-9]{10}$")
    private val ASIN_IN_URL = Regex("(?:[?&]asin=|/dp/|/gp/product/|/product/)([A-Z0-9]{10})(?![A-Z0-9])", RegexOption.IGNORE_CASE)
    private val URL = Regex("https?://[^\\s<>\"']+", RegexOption.IGNORE_CASE)
    private val PLAY_ID = Regex("[?&]id=([A-Za-z0-9_-]+)")
    private val PLAY_ID_ALONE = Regex("^[A-Za-z0-9_-]{6,}$")
    // Share-text boilerplate, e.g. Kindle's 'Check out this book – "Dune"'.
    private val SHARE_PREFIX = Regex(
        "^(?:check out this book|check this out|recommend(?:ed)?(?: this book)?|i'm reading|i am reading)\\s*[-–—:]\\s*",
        RegexOption.IGNORE_CASE,
    )
    private const val QUOTES = "\"'“”‘’«»"
    private val LIBBY_SHARE = Regex("^https?://share\\.libbyapp\\.com/title/(\\d+)", RegexOption.IGNORE_CASE)
    private val LIBBY_LIBRARY_HASH = Regex("#library-([\\w-]+)")
    private val LIBBY_TITLE_PATH = Regex("^https?://libbyapp\\.com/.*/(\\d+)/?(?:[?#].*)?$", RegexOption.IGNORE_CASE)

    /** Unofficial Kindle deep link; opens the book if it is in the library on this device. */
    fun kindleUri(asin: String) = "kindle://book?action=open&asin=$asin"

    /** Opens the book in the Play Books reader (the app handles play.google.com/books/reader links). */
    fun playBooksUri(volumeId: String) = "https://play.google.com/books/reader?id=$volumeId"

    /** Removes share boilerplate and surrounding quotes from a shared title. */
    fun cleanTitle(raw: String): String {
        var t = raw.trim()
        t = t.replace(SHARE_PREFIX, "").trim()
        if (t.length >= 2 && t.first() in QUOTES && t.last() in QUOTES) t = t.substring(1, t.length - 1).trim()
        return t.ifEmpty { raw.trim() }
    }

    /**
     * Libby's share links (share.libbyapp.com) open in the browser, not the app. When the link names the
     * library (#library-xyz), point it at the title page inside Libby instead.
     */
    fun libbyAppUri(url: String): String {
        val id = LIBBY_SHARE.find(url)?.groupValues?.get(1) ?: return url
        val library = LIBBY_LIBRARY_HASH.find(url)?.groupValues?.get(1) ?: return url
        return "https://libbyapp.com/library/$library/everything/page-1/$id"
    }

    /** Libby's numeric title ID from a share or libbyapp.com title link. */
    fun libbyTitleId(url: String): String? =
        LIBBY_SHARE.find(url)?.groupValues?.get(1) ?: LIBBY_TITLE_PATH.find(url)?.groupValues?.get(1)

    /** Packages that should be tried, in order, for an action targeting [pkg]. */
    fun equivalentPackages(pkg: String): List<String> =
        if (pkg in KINDLE_PACKAGES) listOf(pkg) + KINDLE_PACKAGES.filter { it != pkg } else listOf(pkg)

    /** Accepts a bare ASIN or an Amazon/Kindle URL containing one. */
    fun extractAsin(input: String): String? {
        val s = input.trim()
        if (ASIN_ALONE.matches(s.uppercase())) return s.uppercase()
        return ASIN_IN_URL.find(s)?.groupValues?.get(1)?.uppercase()
    }

    /** Accepts a bare Play Books volume ID or a Google Play / Google Books URL with ?id=. */
    fun extractPlayBooksId(input: String): String? {
        val s = input.trim()
        if (s.startsWith("http", ignoreCase = true)) return PLAY_ID.find(s)?.groupValues?.get(1)
        return s.takeIf { PLAY_ID_ALONE.matches(it) }
    }

    fun firstUrl(text: String): String? = URL.find(text)?.value?.trimEnd('.', ',', ')', ']', ';', '!')

    /**
     * Builds an action from text shared into the app (share sheet). Returns null if no link is found.
     * [referrerPackage] is the sharing app, when Android reports it.
     */
    fun actionFromShare(text: String, subject: String?, referrerPackage: String?): OpenUri? {
        val url = firstUrl(text) ?: return null
        val host = hostOf(url)
        val title = cleanTitle(
            subject?.trim()?.takeIf { it.isNotEmpty() }
                ?: text.replace(url, "").trim { it.isWhitespace() || it in "-:\"'.,;|()" }.takeIf { it.isNotEmpty() }
                ?: host ?: url,
        ).take(60)
        val decoded = runCatching { URLDecoder.decode(url, "UTF-8") }.getOrDefault(url)
        val isAmazon = host != null && (host.contains("amazon.") || host == "a.co")
        if (referrerPackage in KINDLE_PACKAGES || isAmazon) {
            extractAsin(decoded)?.let { return OpenUri(kindleUri(it), KINDLE_PACKAGE, title) }
        }
        if (referrerPackage == PLAY_BOOKS_PACKAGE || (host != null && (host == "play.google.com" || host.startsWith("books.google.")))) {
            extractPlayBooksId(url)?.let { return OpenUri(playBooksUri(it), PLAY_BOOKS_PACKAGE, title) }
        }
        if (referrerPackage == LIBBY_PACKAGE || host == "libbyapp.com" || host?.endsWith(".libbyapp.com") == true) {
            return OpenUri(libbyAppUri(url), LIBBY_PACKAGE, title)
        }
        return OpenUri(url, null, title)
    }

    /** The book an [OpenUri] points at, if it targets Kindle, Play Books or Libby; otherwise null. */
    fun bookFrom(a: OpenUri): Book? {
        val uri = a.uri.trim()
        val title = a.label.trim().ifEmpty { uri }
        return when {
            uri.startsWith("kindle://", ignoreCase = true) || a.packageName in KINDLE_PACKAGES ->
                extractAsin(uri)?.let { Book(BookApp.KINDLE, it, title, uri = kindleUri(it)) }
            a.packageName == PLAY_BOOKS_PACKAGE ->
                extractPlayBooksId(uri)?.let { Book(BookApp.PLAY_BOOKS, it, title, uri = playBooksUri(it)) }
            a.packageName == LIBBY_PACKAGE -> {
                // Libby shares "Title - Author".
                val i = title.lastIndexOf(" - ")
                val (t, author) = if (i > 0) title.substring(0, i).trim() to title.substring(i + 3).trim() else title to null
                Book(BookApp.LIBBY, libbyTitleId(uri) ?: uri, t, author?.ifEmpty { null }, uri = uri)
            }
            else -> null
        }
    }

    fun packageFor(app: BookApp): String = when (app) {
        BookApp.KINDLE -> KINDLE_PACKAGE
        BookApp.LIBBY -> LIBBY_PACKAGE
        BookApp.PLAY_BOOKS -> PLAY_BOOKS_PACKAGE
    }

    fun actionFor(book: Book): OpenUri = OpenUri(book.uri, packageFor(book.app), book.title.take(60))

    fun hostOf(url: String): String? =
        runCatching { java.net.URI(url).host?.lowercase()?.removePrefix("www.") }.getOrNull()
}
