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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** App-wide preferences persisted in DataStore. */
class SettingsStore(context: Context) : SettingsSource {
    private val store = context.applicationContext.settingsDataStore

    val theme: Flow<AppTheme> = store.data
        .map { p -> enumOrDefault(p[THEME], AppTheme.CINEMA) }
        .distinctUntilChanged()

    val effects: Flow<EffectsQuality> = store.data
        .map { p -> enumOrDefault(p[EFFECTS], EffectsQuality.AUTO) }
        .distinctUntilChanged()

    /** Adult space flag (off by default). Written only by the 4.4 adult gate after 18+ confirm + PIN setup. */
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

    /** Full settings model (Task 5.2): JSON blob + legacy keys overlaid (they stay the source of truth for them). */
    override val all: Flow<AppSettings> = store.data
        .map { p -> current(p) }
        .distinctUntilChanged()

    override suspend fun update(transform: (AppSettings) -> AppSettings) {
        store.edit { p ->
            val next = transform(current(p)).normalized()
            p[BLOB] = SettingsCodec.encode(next)
            p[THEME] = next.theme.name
            p[EFFECTS] = next.effects.name
            p[BACKGROUND_PLAY] = next.backgroundPlay
            p[AUTO_PIP] = next.autoPip
        }
    }

    private fun current(p: Preferences): AppSettings = SettingsCodec.decode(p[BLOB]).copy(
        theme = enumOrDefault(p[THEME], AppTheme.CINEMA),
        effects = enumOrDefault(p[EFFECTS], EffectsQuality.AUTO),
        backgroundPlay = p[BACKGROUND_PLAY] ?: false,
        autoPip = p[AUTO_PIP] ?: true,
    )

    /** Last used profile (Task 5.1). */
    suspend fun activeProfile(): String? = store.data.first()[ACTIVE_PROFILE]

    suspend fun setActiveProfile(id: String) {
        store.edit { it[ACTIVE_PROFILE] = id }
    }

    suspend fun setAdultEnabled(on: Boolean) {
        store.edit { it[ADULT] = on }
    }

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
        val BLOB = stringPreferencesKey("app_settings_v1")
        val ACTIVE_PROFILE = stringPreferencesKey("active_profile")

        inline fun <reified E : Enum<E>> enumOrDefault(name: String?, default: E): E =
            name?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: default
    }
}
