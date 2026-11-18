package com.mythbrix.cinetor.design

import androidx.compose.ui.graphics.Color
import com.mythbrix.cinetor.TestFiles
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

class ThemesParityTest {

    private fun srgbChannelToLinear(channel: Float): Double {
        val c = channel.toDouble()
        return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }

    private fun relativeLuminance(color: Color): Double =
        0.2126 * srgbChannelToLinear(color.red) +
            0.7152 * srgbChannelToLinear(color.green) +
            0.0722 * srgbChannelToLinear(color.blue)

    private fun contrastRatio(foreground: Color, background: Color): Double {
        val l1 = relativeLuminance(foreground)
        val l2 = relativeLuminance(background)
        return (max(l1, l2) + 0.05) / (min(l1, l2) + 0.05)
    }

    @Test
    fun appTheme_hasExactSixThemesInOrderAndCinemaDefault() {
        val expected = listOf(
            AppTheme.CINEMA,
            AppTheme.MIDNIGHT,
            AppTheme.EMERALD,
            AppTheme.SUNSET,
            AppTheme.ROYAL,
            AppTheme.DAYLIGHT,
        )
        assertEquals(expected, AppTheme.entries)
        assertEquals(AppTheme.CINEMA, CinetorTokens.Default)
    }

    @Test
    fun daylightIsLight_andTheFiveOthersAreDark() {
        assertTrue(CinetorTokens.of(AppTheme.DAYLIGHT).isLight)
        for (theme in AppTheme.entries - AppTheme.DAYLIGHT) {
            assertFalse("$theme must have isLight == false", CinetorTokens.of(theme).isLight)
        }
    }

    @Test
    fun themes_areGenuinelyDifferentPalettes() {
        val accents = AppTheme.entries.map { CinetorTokens.of(it).accent }
        val grounds = AppTheme.entries.map { CinetorTokens.of(it).bg }
        assertEquals("every theme has its own accent", accents.size, accents.toSet().size)
        assertEquals("every theme has its own background", grounds.size, grounds.toSet().size)
        // Accent hues spread around the wheel (red, blue, green, coral, gold), not five shades of red.
        val hues = (AppTheme.entries - AppTheme.DAYLIGHT).map { rgbToHsv(CinetorTokens.of(it).accent)[0] }
        for (i in hues.indices) for (j in i + 1 until hues.size) {
            val d = kotlin.math.abs(hues[i] - hues[j]).let { kotlin.math.min(it, 360f - it) }
            assertTrue("accent hues $i/$j too close ($d°)", d >= 10f)
        }
    }

    @Test
    fun wcagContrast_textAndAccentMeetAaAcrossAllSixThemes() {
        for (theme in AppTheme.entries) {
            val tokens = CinetorTokens.of(theme)
            val pairs = mapOf(
                "textPrimary/bg" to contrastRatio(tokens.textPrimary, tokens.bg),
                "textPrimary/surface" to contrastRatio(tokens.textPrimary, tokens.surface),
                "textPrimary/surfaceElevated" to contrastRatio(tokens.textPrimary, tokens.surfaceElevated),
                "textSecondary/bg" to contrastRatio(tokens.textSecondary, tokens.bg),
                "textSecondary/surface" to contrastRatio(tokens.textSecondary, tokens.surface),
                "textTertiary/bg" to contrastRatio(tokens.textTertiary, tokens.bg),
                "onAccent/accent" to contrastRatio(tokens.onAccent, tokens.accent),
            )
            for ((name, ratio) in pairs) assertTrue("$theme $name contrast $ratio < 4.5", ratio >= 4.5)
        }
    }

    @Test
    fun sharedOutputs_matchIsLightAndColorSchemeContract() {
        val root = Json.parseToJsonElement(File(TestFiles.sharedDir, "tokens/themes.json").readText()).jsonObject
        assertEquals("cinema", root.getValue("default").jsonPrimitive.content)
        val themes = root.getValue("themes").jsonObject
        assertEquals(6, themes.size)

        val css = File(TestFiles.sharedDir, "generated/tokens.css").readText()
        val ts = File(TestFiles.sharedDir, "generated/tokens.ts").readText()
        assertTrue(ts.contains("isLight: boolean;"))
        assertTrue(ts.contains("export const defaultTheme: ThemeName = 'cinema';"))

        for (theme in AppTheme.entries) {
            val key = theme.name.lowercase()
            val json = themes.getValue(key).jsonObject
            val expectedLight = json.getValue("isLight").jsonPrimitive.boolean
            assertEquals("$theme.isLight", expectedLight, CinetorTokens.of(theme).isLight)

            val scheme = if (expectedLight) "color-scheme: light;" else "color-scheme: dark;"
            val blockRegex = Regex("""\[data-theme="$key"\]\s*\{([^}]*)\}""")
            val block = blockRegex.find(css)?.groupValues?.get(1) ?: error("Missing CSS block for $key")
            assertTrue("CSS block for $key missing $scheme", block.contains(scheme))
        }
    }
}
