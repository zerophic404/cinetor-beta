package com.mythbrix.cinetor.design

import android.app.Activity
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.mythbrix.cinetor.core.perf.PerformanceTier
import dev.chrisbanes.haze.HazeState

val LocalAppTheme = staticCompositionLocalOf { CinetorTokens.Default }
val LocalCinetorTokens = staticCompositionLocalOf<CinetorThemeTokens> { CinetorTokens.of(CinetorTokens.Default) }
val LocalPerformanceTier = staticCompositionLocalOf { PerformanceTier.MID }
val LocalDynamicTint = staticCompositionLocalOf { DynamicTintState() }

/** UI-level settings read deep in the tree (Settings → Appearance / TV). */
data class UiPrefs(
    val reduceMotion: Boolean = false,
    val bannerAutoplay: Boolean = true,
    val focusSounds: Boolean = true,
)

val LocalUiPrefs = staticCompositionLocalOf { UiPrefs() }

/** Shared blur source: background content uses `Modifier.haze(state)`, overlay glass blurs it. */
val LocalHazeState = staticCompositionLocalOf { HazeState() }

private val StandardCardShape = RoundedCornerShape(20.dp)
private val MaterialCardShape = RoundedCornerShape(28.dp)

private val StandardPosterShape = RoundedCornerShape(CinetorTokens.Shape.card)
private val MaterialPosterShape = RoundedCornerShape(20.dp)

private val StandardButtonShape = RoundedCornerShape(14.dp)
private val MaterialButtonShape = RoundedCornerShape(percent = 50)

private val StandardShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

private val MaterialShapes = Shapes(
    extraSmall = RoundedCornerShape(12.dp),
    small = RoundedCornerShape(16.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

/** Container/panel corner shape (shared by all six themes: they differ by colour, not geometry). */
@Suppress("UNUSED_PARAMETER")
fun cardShapeFor(theme: AppTheme): RoundedCornerShape = StandardCardShape

/** Poster/media card corner shape. */
@Suppress("UNUSED_PARAMETER")
fun posterShapeFor(theme: AppTheme): RoundedCornerShape = StandardPosterShape

/** Action button corner shape. */
@Suppress("UNUSED_PARAMETER")
fun buttonShapeFor(theme: AppTheme): RoundedCornerShape = StandardButtonShape

/** Material3 shape ramp. */
@Suppress("UNUSED_PARAMETER")
fun shapesFor(theme: AppTheme): Shapes = StandardShapes

/** Every theme keeps the Liquid Glass specular rim and highlight bands. */
@Suppress("UNUSED_PARAMETER")
fun themeUsesSpecularShine(theme: AppTheme): Boolean = true

/** Accessors for the current design-system values. */
object Cinetor {
    val theme: AppTheme
        @Composable @ReadOnlyComposable get() = LocalAppTheme.current
    val tokens: CinetorThemeTokens
        @Composable @ReadOnlyComposable get() = LocalCinetorTokens.current
    val tier: PerformanceTier
        @Composable @ReadOnlyComposable get() = LocalPerformanceTier.current
    val tint: DynamicTintState
        @Composable @ReadOnlyComposable get() = LocalDynamicTint.current
    val haze: HazeState
        @Composable @ReadOnlyComposable get() = LocalHazeState.current
    val prefs: UiPrefs
        @Composable @ReadOnlyComposable get() = LocalUiPrefs.current
    val isLight: Boolean
        @Composable @ReadOnlyComposable get() = LocalCinetorTokens.current.isLight
    val cardShape: RoundedCornerShape
        @Composable @ReadOnlyComposable get() = cardShapeFor(LocalAppTheme.current)
    val posterShape: RoundedCornerShape
        @Composable @ReadOnlyComposable get() = posterShapeFor(LocalAppTheme.current)
    val buttonShape: RoundedCornerShape
        @Composable @ReadOnlyComposable get() = buttonShapeFor(LocalAppTheme.current)
    val specularShine: Boolean
        @Composable @ReadOnlyComposable get() = themeUsesSpecularShine(LocalAppTheme.current)
}

fun CinetorThemeTokens.toColorScheme(): ColorScheme = if (isLight) {
    lightColorScheme(
        primary = accent,
        onPrimary = onAccent,
        primaryContainer = blendTint(accent, surface, 0.14f),
        onPrimaryContainer = textPrimary,
        secondary = accentEnd,
        onSecondary = onAccent,
        secondaryContainer = surfaceElevated,
        onSecondaryContainer = textPrimary,
        tertiary = accentEnd,
        onTertiary = onAccent,
        background = bg,
        onBackground = textPrimary,
        surface = surface,
        onSurface = textPrimary,
        surfaceVariant = surfaceElevated,
        onSurfaceVariant = textSecondary,
        surfaceContainer = surface,
        surfaceContainerHigh = surfaceElevated,
        surfaceContainerHighest = surfaceElevated,
        surfaceContainerLow = surface,
        surfaceContainerLowest = bg,
        outline = glassStroke,
        outlineVariant = divider,
        error = error,
        onError = onAccent,
        scrim = scrim,
    )
} else {
    darkColorScheme(
        primary = accent,
        onPrimary = onAccent,
        primaryContainer = accentEnd,
        onPrimaryContainer = onAccent,
        secondary = accentEnd,
        onSecondary = onAccent,
        secondaryContainer = surfaceElevated,
        onSecondaryContainer = textPrimary,
        tertiary = accentGlow,
        onTertiary = onAccent,
        background = bg,
        onBackground = textPrimary,
        surface = surface,
        onSurface = textPrimary,
        surfaceVariant = surfaceElevated,
        onSurfaceVariant = textSecondary,
        surfaceContainer = surface,
        surfaceContainerHigh = surfaceElevated,
        surfaceContainerHighest = surfaceElevated,
        surfaceContainerLow = surface,
        surfaceContainerLowest = bg,
        outline = glassStroke,
        outlineVariant = divider,
        error = error,
        onError = onAccent,
        scrim = scrim,
    )
}

@Composable
fun CinetorTheme(
    theme: AppTheme = CinetorTokens.Default,
    tier: PerformanceTier = PerformanceTier.MID,
    dynamicColor: Boolean = true,
    prefs: UiPrefs = UiPrefs(),
    content: @Composable () -> Unit,
) {
    val tokens = CinetorTokens.of(theme)
    val tint = remember { DynamicTintState() }
    tint.enabled = dynamicColor
    val haze = remember { HazeState() }
    val scheme = remember(tokens) { tokens.toColorScheme() }
    val shapes = remember(theme) { shapesFor(theme) }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            val insetsController = WindowCompat.getInsetsController(window, view)
            insetsController.isAppearanceLightStatusBars = tokens.isLight
            insetsController.isAppearanceLightNavigationBars = tokens.isLight
        }
    }

    CompositionLocalProvider(
        LocalAppTheme provides theme,
        LocalCinetorTokens provides tokens,
        LocalPerformanceTier provides tier,
        LocalDynamicTint provides tint,
        LocalHazeState provides haze,
        LocalUiPrefs provides prefs,
    ) {
        MaterialTheme(
            colorScheme = scheme,
            typography = CinetorTypography,
            shapes = shapes,
            content = content,
        )
    }
}
