package com.mythbrix.cinetor.design

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import com.mythbrix.cinetor.core.perf.PerformanceTier
import dev.chrisbanes.haze.HazeState

val LocalAppTheme = staticCompositionLocalOf { AppTheme.NOIR }
val LocalCinetorTokens = staticCompositionLocalOf<CinetorThemeTokens> { CinetorTokens.Noir }
val LocalPerformanceTier = staticCompositionLocalOf { PerformanceTier.MID }
val LocalDynamicTint = staticCompositionLocalOf { DynamicTintState() }

/** Shared blur source: background content uses `Modifier.haze(state)`, overlay glass blurs it. */
val LocalHazeState = staticCompositionLocalOf { HazeState() }

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
}

fun CinetorThemeTokens.toColorScheme(): ColorScheme = darkColorScheme(
    primary = accent,
    onPrimary = onAccent,
    primaryContainer = accentEnd,
    onPrimaryContainer = onAccent,
    secondary = accentEnd,
    onSecondary = onAccent,
    tertiary = accentGlow,
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
    scrim = scrim,
)

@Composable
fun CinetorTheme(
    theme: AppTheme = CinetorTokens.Default,
    tier: PerformanceTier = PerformanceTier.MID,
    content: @Composable () -> Unit,
) {
    val tokens = CinetorTokens.of(theme)
    val tint = remember { DynamicTintState() }
    val haze = remember { HazeState() }
    val scheme = remember(tokens) { tokens.toColorScheme() }
    CompositionLocalProvider(
        LocalAppTheme provides theme,
        LocalCinetorTokens provides tokens,
        LocalPerformanceTier provides tier,
        LocalDynamicTint provides tint,
        LocalHazeState provides haze,
    ) {
        MaterialTheme(colorScheme = scheme, typography = CinetorTypography, content = content)
    }
}
