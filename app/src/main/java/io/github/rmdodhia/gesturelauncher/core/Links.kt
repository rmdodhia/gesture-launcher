package io.github.rmdodhia.gesturelauncher.core

import java.net.URLDecoder

object Links {
    const val KINDLE_PACKAGE = "com.amazon.kindle"
    const val PLAY_BOOKS_PACKAGE = "com.google.android.apps.books"
    const val LIBBY_PACKAGE = "com.overdrive.mobile.android.libby"

    private val ASIN_ALONE = Regex("^[A-Z0-9]{10}$")
    private val ASIN_IN_URL = Regex("(?:[?&]asin=|/dp/|/gp/product/|/product/)([A-Z0-9]{10})(?![A-Z0-9])", RegexOption.IGNORE_CASE)
    private val URL = Regex("https?://[^\\s<>\"']+", RegexOption.IGNORE_CASE)
    private val PLAY_ID = Regex("[?&]id=([A-Za-z0-9_-]+)")
    private val PLAY_ID_ALONE = Regex("^[A-Za-z0-9_-]{6,}$")

    /** Unofficial Kindle deep link; opens the book if it is in the library on this device. */
    fun kindleUri(asin: String) = "kindle://book?action=open&asin=$asin"

    fun playBooksUri(volumeId: String) = "https://play.google.com/store/books/details?id=$volumeId"

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
        val title = (subject?.trim()?.takeIf { it.isNotEmpty() }
            ?: text.replace(url, "").trim { it.isWhitespace() || it in "-:\"'.,;|()" }.takeIf { it.isNotEmpty() }
            ?: host ?: url).take(60)
        val decoded = runCatching { URLDecoder.decode(url, "UTF-8") }.getOrDefault(url)
        val isAmazon = host != null && (host.contains("amazon.") || host == "a.co")
        if (referrerPackage == KINDLE_PACKAGE || isAmazon) {
            extractAsin(decoded)?.let { return OpenUri(kindleUri(it), KINDLE_PACKAGE, title) }
        }
        if (referrerPackage == PLAY_BOOKS_PACKAGE || (host != null && (host == "play.google.com" || host.startsWith("books.google.")))) {
            extractPlayBooksId(url)?.let { return OpenUri(playBooksUri(it), PLAY_BOOKS_PACKAGE, title) }
        }
        if (referrerPackage == LIBBY_PACKAGE || host == "libbyapp.com" || host?.endsWith(".libbyapp.com") == true) {
            return OpenUri(url, LIBBY_PACKAGE, title)
        }
        return OpenUri(url, null, title)
    }

    fun hostOf(url: String): String? =
        runCatching { java.net.URI(url).host?.lowercase()?.removePrefix("www.") }.getOrNull()
}
