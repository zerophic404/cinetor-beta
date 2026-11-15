package com.mythbrix.cinetor.core.prefs

import com.mythbrix.cinetor.core.perf.EffectsQuality
import com.mythbrix.cinetor.design.AppTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

enum class VideoQuality(val label: String) { AUTO("Auto"), P1080("1080p"), P720("720p"), P480("480p"), P360("360p") }
enum class ImageQuality(val label: String) { LOW("Low"), MEDIUM("Medium"), HIGH("High") }
enum class MaturityLimit(val label: String) { ALL("All ages"), TEEN_13("13+"), MATURE_16("16+"), ADULT_18("18+") }

/**
 * Every app-level setting (Task 5.2) as one serializable model. Persisted as a JSON blob in DataStore;
 * theme/effects/background play/auto PiP are also mirrored to their legacy keys (older readers keep working).
 * The 18+ flag stays owned by the adult gate (not in this model).
 */
@Serializable
data class AppSettings(
    // Appearance
    val theme: AppTheme = AppTheme.NOIR,
    val effects: EffectsQuality = EffectsQuality.AUTO,
    val reduceMotion: Boolean = false,
    val dynamicColor: Boolean = true,
    // Playback
    val qualityWifi: VideoQuality = VideoQuality.AUTO,
    val qualityMobile: VideoQuality = VideoQuality.P480,
    val autoplayNext: Boolean = true,
    val skipIntroAuto: Boolean = false,
    val doubleTapSec: Int = 10,
    val holdSpeed: Float = 2f,
    val gestures: Boolean = true,
    val autoPip: Boolean = true,
    val backgroundPlay: Boolean = false,
    // Subtitles & audio (profile overrides win)
    val subtitleLang: String = "en",
    val audioLang: String = "original",
    val subtitlesOn: Boolean = true,
    // Data & storage
    val dataSaver: Boolean = false,
    val imageQuality: ImageQuality = ImageQuality.HIGH,
    val preloadDetails: Boolean = true,
    val cacheSizeMb: Int = 200,
    // Content
    val maturityLimit: MaturityLimit = MaturityLimit.ALL,
    // TV
    val tvFocusSounds: Boolean = true,
    val bannerAutoplay: Boolean = true,
    // Developer
    val mockLatency: Boolean = true,
) {
    /** Clamp to the options the UI offers (old / corrupted blobs never reach the player out of range). */
    fun normalized(): AppSettings = copy(
        doubleTapSec = SettingsOptions.DOUBLE_TAP_SEC.minByOrNull { kotlin.math.abs(it - doubleTapSec) } ?: 10,
        holdSpeed = holdSpeed.coerceIn(1.25f, 4f),
        cacheSizeMb = cacheSizeMb.coerceIn(50, 2_000),
    )
}

object SettingsOptions {
    val DOUBLE_TAP_SEC = listOf(5, 10, 15, 20, 30)
    val HOLD_SPEEDS = listOf(1.5f, 2f, 3f)
    val CACHE_MB = listOf(100, 200, 500, 1_000)
    /** code → label (subtitle / audio pickers). */
    val LANGUAGES = listOf(
        "original" to "Original", "en" to "English", "bn" to "Bangla", "hi" to "Hindi", "ja" to "Japanese",
        "ko" to "Korean", "es" to "Spanish", "ar" to "Arabic",
    )
}

/** JSON round-trip of [AppSettings] (survives process death via DataStore). */
object SettingsCodec {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; coerceInputValues = true }

    fun encode(settings: AppSettings): String = json.encodeToString(AppSettings.serializer(), settings)

    fun decode(blob: String?): AppSettings =
        blob?.let { runCatching { json.decodeFromString(AppSettings.serializer(), it) }.getOrNull() }?.normalized()
            ?: AppSettings()
}

/** Read/write access to settings (DataStore in the app, in-memory in tests). */
interface SettingsSource {
    val all: Flow<AppSettings>
    suspend fun update(transform: (AppSettings) -> AppSettings)
}

class InMemorySettingsSource(initial: AppSettings = AppSettings()) : SettingsSource {
    val flow = MutableStateFlow(initial)
    override val all: Flow<AppSettings> = flow
    override suspend fun update(transform: (AppSettings) -> AppSettings) = flow.update { transform(it).normalized() }
}
