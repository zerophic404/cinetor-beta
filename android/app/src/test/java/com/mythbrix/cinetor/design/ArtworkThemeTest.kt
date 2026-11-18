package com.mythbrix.cinetor.design

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtworkThemeTest {
    private val samples = listOf(
        Color(0xFF1E90FF), // bright blue poster
        Color(0xFFFFD400), // yellow
        Color(0xFF0B3D0B), // very dark green
        Color(0xFFFF2D9A), // neon pink
        Color(0xFF6A0DAD), // purple
        Color(0xFF00E5FF), // cyan
        Color(0xFF8B4513), // brown
    )
    private val fallback = Color(0xFFE50914)

    @Test
    fun accent_keepsWhiteButtonTextAndDarkPageTextReadable() {
        for (art in samples) {
            val accent = artworkAccent(art, fallback)
            assertTrue("white on $accent from $art", contrastRatio(Color.White, accent) >= 4.3f)
            assertTrue("$accent on black page from $art", contrastRatio(accent, Color(0xFF050505)) >= 4.3f)
        }
    }

    @Test
    fun accent_keepsTheArtworkHue() {
        val blue = artworkAccent(Color(0xFF1E90FF), fallback)
        assertTrue("blue stays blue", blue.blue > blue.red && blue.blue > blue.green)
        val green = artworkAccent(Color(0xFF0B3D0B), fallback)
        assertTrue("green stays green", green.green > green.red && green.green > green.blue)
    }

    @Test
    fun greyArtwork_fallsBackToTheThemeAccent() {
        assertEquals(fallback, artworkAccent(Color(0xFF808080), fallback))
        assertEquals(fallback, artworkAccent(Color(0xFF101010), fallback))
    }

    @Test
    fun tokens_followTheArtwork_butKeepThemeSurfacesAndText() {
        val base = CinetorTokens.of(AppTheme.entries.first())
        val t = ArtworkTokens(base, Color(0xFF1E90FF))
        assertTrue(t.fromArtwork)
        assertEquals(base.bg, t.bg)
        assertEquals(base.textPrimary, t.textPrimary)
        assertEquals(base.liveBadge, t.liveBadge)
        assertTrue(t.accent.blue > t.accent.red)
    }
}
