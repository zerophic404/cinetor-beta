package com.mythbrix.cinetor.feature.about

import com.mythbrix.cinetor.core.AppLinks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AboutTest {

    @Test
    fun about_linksOpenExactUrls() {
        assertEquals("https://t.me/mythbrix_vault", AppLinks.TELEGRAM_CHANNEL_URL)
        assertEquals("https://t.me/cinetor", AppLinks.TELEGRAM_GROUP_URL)
        assertEquals("https://ahmedishtiaquezarir.pro.bd/", AppLinks.ZARIR_URL)

        val opened = mutableListOf<String>()
        val actions = AboutActions { opened += it }
        actions.open(AboutLink.TELEGRAM_CHANNEL)
        actions.open(AboutLink.TELEGRAM_GROUP)
        actions.open(AboutLink.ZARIR)
        assertEquals(
            listOf("https://t.me/mythbrix_vault", "https://t.me/cinetor", "https://ahmedishtiaquezarir.pro.bd/"),
            opened,
        )
        assertEquals("Telegram Channel", AboutLink.TELEGRAM_CHANNEL.label)
        assertEquals("Telegram Group", AboutLink.TELEGRAM_GROUP.label)
    }

    @Test
    fun about_copyExact() {
        assertEquals("Cinetor", AboutCopy.APP_NAME)
        assertEquals("Developed By Mythbrix", AboutCopy.DEVELOPED_BY)
        assertEquals("Thanks to Ahmed Ishtiaq Zarir", AboutCopy.THANKS)
        assertEquals(
            "This product uses the TMDB API but is not endorsed or certified by TMDB.",
            AboutCopy.TMDB_ATTRIBUTION,
        )
        // Only the name is hyperlinked.
        val t = thanksText()
        assertEquals("Thanks to Ahmed Ishtiaq Zarir", t.text)
        assertEquals("Ahmed Ishtiaq Zarir", t.linkedPart)
        assertEquals("Thanks to ", t.text.substring(0, t.start))
        assertEquals(t.text.length, t.end)
        assertEquals(AppLinks.ZARIR_URL, t.url)
        assertEquals("Version 1.0 (1) · Build 42", AboutCopy.versionLine("1.0", 1, "42"))
        assertTrue(OSS_LICENSES.isNotEmpty())
    }
}
