package com.mythbrix.cinetor.core.legal

/**
 * User-facing error text (docs/requirements-hardening.md §2). Nothing here may contain a host, path,
 * query, provider name or gateway base URL: exception messages from OkHttp / Media3 routinely embed
 * the request URL, so every message shown in the UI goes through [safe] first. Detail stays in
 * [DebugLog], which is off by default and only reachable from Settings → Developer.
 */
object UserErrors {
    const val PLAYBACK = "Playback failed. Try another quality."
    const val DOWNLOAD = "Download failed. Try again."
    const val CATALOG = "Can't reach the catalog right now. Check your connection and retry."
    const val NOT_FOUND = "Title not found."
    const val GENERIC = "Something went wrong. Try again."
    const val OFFLINE = "You're offline. Check your connection and retry."

    /** Anything that looks like a locator: scheme, host, path, query, IP or port. */
    private val URLISH = Regex(
        "(?i)(" +
            "[a-z][a-z0-9+.-]*://" + // scheme://
            "|\\bwww\\." +
            "|\\b[a-z0-9-]+\\.(com|net|org|io|app|dev|co|tv|me|xyz|cc|to|vercel|cloudfront|akamaized)\\b" +
            "|/api/|\\?[a-z0-9_]+=" +
            "|\\b\\d{1,3}(\\.\\d{1,3}){3}\\b" +
            ")",
    )

    /** True when [text] leaks something locator-shaped (used by the hardening tests). */
    fun containsLocator(text: String?): Boolean = !text.isNullOrBlank() && URLISH.containsMatchIn(text)

    /**
     * Maps any failure to text that is safe to render. Technical detail is never passed through:
     * a message is only reused when it is short, human and locator-free (e.g. "No sources").
     */
    fun safe(e: Throwable?, fallback: String = GENERIC): String {
        if (e == null) return fallback
        val io = e is java.io.IOException && e !is NoRouteException
        if (io) return if (fallback == GENERIC) OFFLINE else fallback
        val m = e.message?.trim()
        if (m.isNullOrEmpty() || m.length > 80 || containsLocator(m) || m.contains('$') || m.contains("Exception")) return fallback
        return m
    }

    /** Marker for "nothing playable here", so [safe] does not downgrade it to an offline message. */
    class NoRouteException(message: String) : java.io.IOException(message)
}
