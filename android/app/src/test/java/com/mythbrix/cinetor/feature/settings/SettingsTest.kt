package com.mythbrix.cinetor.feature.settings

import com.mythbrix.cinetor.core.perf.EffectsQuality
import com.mythbrix.cinetor.core.prefs.AppSettings
import com.mythbrix.cinetor.core.prefs.ImageQuality
import com.mythbrix.cinetor.core.prefs.InMemorySettingsSource
import com.mythbrix.cinetor.core.prefs.MaturityLimit
import com.mythbrix.cinetor.core.prefs.SettingsCodec
import com.mythbrix.cinetor.core.prefs.VideoQuality
import com.mythbrix.cinetor.design.AppTheme
import com.mythbrix.cinetor.feature.player.gestures.GestureController
import com.mythbrix.cinetor.feature.player.gestures.GestureTarget
import com.mythbrix.cinetor.feature.profile.InMemoryActiveProfileStore
import com.mythbrix.cinetor.feature.profile.InMemoryProfileStore
import com.mythbrix.cinetor.feature.profile.ProfileManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsTest {

    @Test
    fun themeChange_appliesWithoutRestart() = runTest {
        val profiles = ProfileManager(InMemoryProfileStore(), InMemoryActiveProfileStore(), backgroundScope)
        profiles.load()
        val source = InMemorySettingsSource()
        val holder = SettingsStateHolder(source, backgroundScope, profiles.active.map { it.theme }) { profiles.setTheme(it) }
        runCurrent()
        val themeFlow = holder.theme
        assertEquals(AppTheme.CINEMA, themeFlow.value)

        holder.setTheme(AppTheme.SUNSET)
        runCurrent()
        // Same holder + same StateFlow instance emits the new theme: the root recomposes in place, no restart.
        assertSame(themeFlow, holder.theme)
        assertEquals(AppTheme.SUNSET, themeFlow.value)
        assertEquals(AppTheme.SUNSET, holder.state.value.theme)
        assertEquals(AppTheme.SUNSET.name, profiles.active.value.theme)

        // Other profile without an override follows the app default; its own pick wins once set.
        val kid = (profiles.create("Kid") as com.mythbrix.cinetor.feature.profile.CreateResult.Ok).profile
        profiles.select(kid.id)
        runCurrent()
        assertEquals(AppTheme.SUNSET, themeFlow.value)
        holder.setTheme(AppTheme.MIDNIGHT)
        runCurrent()
        assertEquals(AppTheme.MIDNIGHT, themeFlow.value)
        profiles.select(ProfileManager.MAIN_ID)
        runCurrent()
        assertEquals(AppTheme.SUNSET, themeFlow.value)

        holder.setTheme(AppTheme.CINEMA)
        runCurrent()
        assertEquals(AppTheme.CINEMA, themeFlow.value)
    }

    @Test
    fun settings_persistAcrossProcessDeath() {
        val changed = AppSettings(
            theme = AppTheme.SUNSET, effects = EffectsQuality.LOW, reduceMotion = true, dynamicColor = false,
            qualityWifi = VideoQuality.P1080, qualityMobile = VideoQuality.P360, autoplayNext = false, skipIntroAuto = true,
            doubleTapSec = 30, holdSpeed = 3f, gestures = false, autoPip = false, backgroundPlay = true,
            subtitleLang = "bn", audioLang = "ja", subtitlesOn = false, dataSaver = true, imageQuality = ImageQuality.LOW,
            preloadDetails = false, cacheSizeMb = 500, maturityLimit = MaturityLimit.TEEN_13, tvFocusSounds = false,
            bannerAutoplay = false, mockLatency = false,
        )
        // "Process death": only the persisted string survives.
        val blob: String = SettingsCodec.encode(changed)
        val restored = SettingsCodec.decode(blob)
        assertEquals(changed, restored)

        // Fresh install / corrupt blob → defaults; unknown keys + out-of-range values are tolerated.
        assertEquals(AppSettings(), SettingsCodec.decode(null))
        assertEquals(AppSettings(), SettingsCodec.decode("{not json"))
        val odd = SettingsCodec.decode("""{"doubleTapSec":12,"future":"x","theme":"NOPE"}""")
        assertEquals(10, odd.doubleTapSec)
        assertEquals(AppTheme.CINEMA, odd.theme)
    }

    @Test
    fun doubleTapStep_followsSetting() {
        var seekedMs = 0L
        val target = object : GestureTarget {
            override val positionMs: Long = 60_000
            override val durationMs: Long = 600_000
            override val speed: Float = 1f
            override val locked: Boolean = false
            override fun seekBy(deltaMs: Long) { seekedMs += deltaMs }
            override fun seekTo(positionMs: Long) {}
            override fun setSpeed(speed: Float) {}
            override fun togglePlay() {}
            override fun toggleControls() {}
            override fun brightness(): Float = 0.5f
            override fun setBrightness(value: Float) {}
            override fun volume(): Float = 0.5f
            override fun setVolume(value: Float) {}
            override fun setFill(fill: Boolean) {}
        }
        var settings = AppSettings(doubleTapSec = 15)
        val c = GestureController(target, clock = { 0L }, stepMs = { settings.doubleTapSec * 1_000L })
        c.onDoubleTap(900f, 1000f)
        assertEquals(15_000L, seekedMs)
        settings = settings.copy(doubleTapSec = 30)
        c.onDoubleTap(100f, 1000f)
        assertEquals(15_000L - 30_000L, seekedMs)
    }
}
