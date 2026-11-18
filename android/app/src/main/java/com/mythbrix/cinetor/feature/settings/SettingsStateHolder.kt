package com.mythbrix.cinetor.feature.settings

import com.mythbrix.cinetor.core.prefs.AppSettings
import com.mythbrix.cinetor.core.prefs.SettingsSource
import com.mythbrix.cinetor.design.AppTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * App-scoped settings state (Android-free). The root composable collects [theme] / [state], so a change
 * recomposes the whole tree in place — no activity restart.
 */
class SettingsStateHolder(
    private val source: SettingsSource,
    private val scope: CoroutineScope,
    profileTheme: Flow<String?> = flowOf(null),
    private val onProfileTheme: suspend (AppTheme) -> Unit = {},
) {
    companion object {
        /** Transparent subtitle background by default (`bgOpacity = 0`). */
        const val DEFAULT_SUBTITLE_BG_OPACITY: Float = 0f
    }

    val state: StateFlow<AppSettings> = source.all.stateIn(scope, SharingStarted.Eagerly, AppSettings())

    val theme: StateFlow<AppTheme> = combine(state, profileTheme) { s, override ->
        override?.let { n -> AppTheme.entries.firstOrNull { it.name == n } } ?: s.theme
    }.stateIn(scope, SharingStarted.Eagerly, AppSettings().theme)

    fun update(transform: (AppSettings) -> AppSettings) {
        scope.launch { source.update(transform) }
    }

    fun setTheme(theme: AppTheme) {
        scope.launch {
            onProfileTheme(theme)
            source.update { it.copy(theme = theme) }
        }
    }

    /** Restores player, gesture, and appearance settings to Cinetor defaults. */
    fun resetToDefaults() {
        val def = AppSettings()
        scope.launch {
            onProfileTheme(def.theme)
            source.update { current ->
                def.copy(gatewayUrl = current.gatewayUrl)
            }
        }
    }
}
