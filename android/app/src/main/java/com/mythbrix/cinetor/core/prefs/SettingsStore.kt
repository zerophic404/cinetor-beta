package com.mythbrix.cinetor.core.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.mythbrix.cinetor.core.perf.EffectsQuality
import com.mythbrix.cinetor.design.AppTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** App-wide preferences persisted in DataStore. */
class SettingsStore(context: Context) {
    private val store = context.applicationContext.settingsDataStore

    val theme: Flow<AppTheme> = store.data
        .map { p -> enumOrDefault(p[THEME], AppTheme.NOIR) }
        .distinctUntilChanged()

    val effects: Flow<EffectsQuality> = store.data
        .map { p -> enumOrDefault(p[EFFECTS], EffectsQuality.AUTO) }
        .distinctUntilChanged()

    /** Adult space flag — stubbed off until Phase 4 gating (18+ confirm + PIN). */
    val adultEnabled: Flow<Boolean> = store.data
        .map { p -> p[ADULT] ?: false }
        .distinctUntilChanged()

    /** Keep video audio playing in the background (MediaSession notification). Default off. */
    val backgroundPlay: Flow<Boolean> = store.data
        .map { p -> p[BACKGROUND_PLAY] ?: false }
        .distinctUntilChanged()

    /** Auto-enter system Picture-in-Picture on Home while a video plays. Default on. */
    val autoPip: Flow<Boolean> = store.data
        .map { p -> p[AUTO_PIP] ?: true }
        .distinctUntilChanged()

    suspend fun setBackgroundPlay(on: Boolean) {
        store.edit { it[BACKGROUND_PLAY] = on }
    }

    suspend fun setAutoPip(on: Boolean) {
        store.edit { it[AUTO_PIP] = on }
    }

    suspend fun setTheme(theme: AppTheme) {
        store.edit { it[THEME] = theme.name }
    }

    suspend fun setEffects(quality: EffectsQuality) {
        store.edit { it[EFFECTS] = quality.name }
    }

    private companion object {
        val THEME = stringPreferencesKey("theme")
        val EFFECTS = stringPreferencesKey("effects_quality")
        val ADULT = booleanPreferencesKey("adult_enabled")
        val BACKGROUND_PLAY = booleanPreferencesKey("background_play")
        val AUTO_PIP = booleanPreferencesKey("auto_pip")

        inline fun <reified E : Enum<E>> enumOrDefault(name: String?, default: E): E =
            name?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: default
    }
}
