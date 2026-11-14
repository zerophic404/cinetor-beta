package com.mythbrix.cinetor.design

import androidx.compose.ui.graphics.Color
import com.mythbrix.cinetor.TestFiles
import com.mythbrix.cinetor.core.data.parseHexColor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class ThemeTokensTest {
    private fun colorsOf(t: CinetorThemeTokens): Map<String, Color> = mapOf(
        "bg" to t.bg, "surface" to t.surface, "surfaceElevated" to t.surfaceElevated,
        "glassFill" to t.glassFill, "glassStroke" to t.glassStroke, "accent" to t.accent,
        "accentEnd" to t.accentEnd, "accentGlow" to t.accentGlow, "onAccent" to t.onAccent,
        "textPrimary" to t.textPrimary, "textSecondary" to t.textSecondary, "textTertiary" to t.textTertiary,
        "divider" to t.divider, "focusRing" to t.focusRing, "success" to t.success, "warning" to t.warning,
        "error" to t.error, "liveBadge" to t.liveBadge, "scrim" to t.scrim,
        "skeletonBase" to t.skeletonBase, "skeletonHighlight" to t.skeletonHighlight,
    )

    @Test
    fun theme_tokensMatchJson() {
        val root = Json.parseToJsonElement(File(TestFiles.sharedDir, "tokens/themes.json").readText()).jsonObject
        val themes = root.getValue("themes").jsonObject
        assertEquals(AppTheme.entries.size, themes.size)
        for (theme in AppTheme.entries) {
            val json = themes.getValue(theme.name.lowercase()).jsonObject
            val tokens = CinetorTokens.of(theme)
            val kotlinColors = colorsOf(tokens)
            val jsonColors = json.getValue("colors").jsonObject
            for ((key, value) in jsonColors) {
                val expected = Color(parseHexColor(value.jsonPrimitive.content))
                assertEquals("$theme.$key", expected, kotlinColors.getValue(key))
            }
            // Spot-check the three identity tokens explicitly.
            for (key in listOf("bg", "accent", "textPrimary")) {
                assertEquals(Color(parseHexColor(jsonColors.getValue(key).jsonPrimitive.content)), kotlinColors.getValue(key))
            }
            assertEquals(json.getValue("blurDp").jsonPrimitive.float, tokens.blur.value, 0.001f)
            assertEquals(json.getValue("tintStrength").jsonPrimitive.float, tokens.tintStrength, 0.001f)
        }
    }
}
