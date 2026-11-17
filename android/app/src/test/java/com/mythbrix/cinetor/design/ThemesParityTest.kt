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
    fun appTheme_hasExactEightThemesInOrderAndGlassDefault() {
        val expected = listOf(
            AppTheme.NOIR,
            AppTheme.OBSIDIAN,
            AppTheme.EMBER,
            AppTheme.GLASS,
            AppTheme.MATTE,
            AppTheme.MATERIAL,
            AppTheme.AURA,
            AppTheme.PEARL,
        )
        assertEquals(expected, AppTheme.entries)
        assertEquals(AppTheme.GLASS, CinetorTokens.Default)
    }

    @Test
    fun pearlIsLight_andAllSevenDarkThemesAreNotLight() {
        assertTrue(CinetorTokens.of(AppTheme.PEARL).isLight)
        assertTrue(CinetorTokens.Pearl.isLight)

        val darkThemes = listOf(
            AppTheme.NOIR,
            AppTheme.OBSIDIAN,
            AppTheme.EMBER,
            AppTheme.GLASS,
            AppTheme.MATTE,
            AppTheme.MATERIAL,
            AppTheme.AURA,
        )
        for (theme in darkThemes) {
            assertFalse("$theme must have isLight == false", CinetorTokens.of(theme).isLight)
        }
    }

    @Test
    fun wcagContrast_textPrimaryMeetsAaOnBgAndSurfaceAcrossAllEightThemes() {
        for (theme in AppTheme.entries) {
            val tokens = CinetorTokens.of(theme)
            val bgRatio = contrastRatio(tokens.textPrimary, tokens.bg)
            val surfaceRatio = contrastRatio(tokens.textPrimary, tokens.surface)
            val elevatedRatio = contrastRatio(tokens.textPrimary, tokens.surfaceElevated)
            val secondaryBgRatio = contrastRatio(tokens.textSecondary, tokens.bg)
            val secondarySurfaceRatio = contrastRatio(tokens.textSecondary, tokens.surface)

            assertTrue("$theme textPrimary vs bg contrast $bgRatio < 4.5", bgRatio >= 4.5)
            assertTrue("$theme textPrimary vs surface contrast $surfaceRatio < 4.5", surfaceRatio >= 4.5)
            assertTrue("$theme textPrimary vs surfaceElevated contrast $elevatedRatio < 4.5", elevatedRatio >= 4.5)
            assertTrue("$theme textSecondary vs bg contrast $secondaryBgRatio < 4.5", secondaryBgRatio >= 4.5)
            assertTrue("$theme textSecondary vs surface contrast $secondarySurfaceRatio < 4.5", secondarySurfaceRatio >= 4.5)
        }

        val pearl = CinetorTokens.of(AppTheme.PEARL)
        assertTrue(contrastRatio(pearl.textTertiary, pearl.bg) >= 4.5)
        assertTrue(contrastRatio(pearl.textTertiary, pearl.surface) >= 4.5)
    }

    @Test
    fun sharedOutputs_matchIsLightAndColorSchemeContract() {
        val root = Json.parseToJsonElement(File(TestFiles.sharedDir, "tokens/themes.json").readText()).jsonObject
        assertEquals("glass", root.getValue("default").jsonPrimitive.content)
        val themes = root.getValue("themes").jsonObject
        assertEquals(8, themes.size)

        val css = File(TestFiles.sharedDir, "generated/tokens.css").readText()
        val ts = File(TestFiles.sharedDir, "generated/tokens.ts").readText()
        assertTrue(ts.contains("isLight: boolean;"))
        assertTrue(ts.contains("export const defaultTheme: ThemeName = 'glass';"))

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
