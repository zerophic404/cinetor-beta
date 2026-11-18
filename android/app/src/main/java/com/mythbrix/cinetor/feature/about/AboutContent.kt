package com.mythbrix.cinetor.feature.about

import com.mythbrix.cinetor.core.AppLinks

/** Exact About copy — covered by AboutTest; the UI renders only these strings. */
object AboutCopy {
    const val APP_NAME = "Cinetor"
    const val DEVELOPED_BY = "Developed By Mythbrix"
    const val RIGHTS_RESERVED = "All rights reserved Mythbrix"
    const val THANKS_PREFIX = "Thanks to "
    const val THANKS_NAME = "Ahmed Ishtiaq Zarir"
    const val THANKS = THANKS_PREFIX + THANKS_NAME
    const val TMDB_ATTRIBUTION = "This product uses the TMDB API but is not endorsed or certified by TMDB."
    const val TELEGRAM_CHANNEL = "Telegram Channel"
    const val TELEGRAM_GROUP = "Telegram Group"
    const val CHECK_UPDATES = "Check for updates"
    const val TAGLINE = "Movies, series, anime, drama, music and comics in one place"

    /** Highlights shown on the About card. */
    val HIGHLIGHTS = listOf(
        "Instant play with a ready-to-play dot",
        "Six themes: Cinema, Midnight, Emerald, Sunset, Royal, Daylight",
        "Native comics reader with PDF downloads",
        "Music with lossless streaming and downloads",
        "Live TV and sports",
    )

    fun versionLine(versionName: String, versionCode: Int, build: String): String =
        "Version $versionName ($versionCode) · Build $build"
}

/** Tappable links of the About card. */
enum class AboutLink(val label: String, val url: String) {
    TELEGRAM_CHANNEL(AboutCopy.TELEGRAM_CHANNEL, AppLinks.TELEGRAM_CHANNEL_URL),
    TELEGRAM_GROUP(AboutCopy.TELEGRAM_GROUP, AppLinks.TELEGRAM_GROUP_URL),
    UPDATES(AboutCopy.CHECK_UPDATES, AppLinks.RELEASES_URL),
    ZARIR(AboutCopy.THANKS_NAME, AppLinks.ZARIR_URL),
}

/** Opens links through [opener] (UriHandler in the app, a recorder in tests). */
class AboutActions(private val opener: (String) -> Unit) {
    fun open(link: AboutLink) = opener(link.url)
}

/** "Thanks to Ahmed Ishtiaq Zarir": only [start] until [end] (the name) is hyperlinked to [url]. */
data class LinkedText(val text: String, val start: Int, val end: Int, val url: String) {
    val linkedPart: String get() = text.substring(start, end)
}

fun thanksText(): LinkedText = LinkedText(
    text = AboutCopy.THANKS,
    start = AboutCopy.THANKS_PREFIX.length,
    end = AboutCopy.THANKS.length,
    url = AppLinks.ZARIR_URL,
)
