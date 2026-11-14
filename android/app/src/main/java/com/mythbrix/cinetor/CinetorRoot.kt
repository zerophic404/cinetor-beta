package com.mythbrix.cinetor

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mythbrix.cinetor.core.data.AppContainer
import com.mythbrix.cinetor.core.perf.PerformanceTier
import com.mythbrix.cinetor.core.perf.detectTier
import com.mythbrix.cinetor.core.player.ResumeEntry
import com.mythbrix.cinetor.core.ui.LocalResumeEntries
import com.mythbrix.cinetor.design.CinetorTheme
import com.mythbrix.cinetor.design.UiPrefs
import com.mythbrix.cinetor.feature.music.MusicHost
import com.mythbrix.cinetor.feature.player.PlayerHost
import com.mythbrix.cinetor.feature.player.gestures.GesturePrefs
import com.mythbrix.cinetor.feature.profile.StartupProfilePicker
import com.mythbrix.cinetor.feature.splash.AnimatedSplash
import com.mythbrix.cinetor.navigation.CinetorNavHost
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flatMapLatest

/** Theme + tier from the settings state (applied live, no restart) → shell with the intro on top. */
@OptIn(ExperimentalCoroutinesApi::class)
@Composable
fun CinetorRoot(container: AppContainer) {
    val settings by container.settingsState.state.collectAsStateWithLifecycle()
    val theme by container.settingsState.theme.collectAsStateWithLifecycle()
    // Reduce motion → LOW-tier motion (shorter / fewer animations, static blur).
    val tierOverride = if (settings.reduceMotion) PerformanceTier.LOW else settings.effects.toTierOrNull()
    val tier = remember(tierOverride) { detectTier(container.deviceProfile, tierOverride) }
    val prefs = remember(settings.reduceMotion, settings.bannerAutoplay, settings.tvFocusSounds) {
        UiPrefs(settings.reduceMotion, settings.bannerAutoplay, settings.tvFocusSounds)
    }
    CinetorTheme(theme = theme, tier = tier, dynamicColor = settings.dynamicColor, prefs = prefs) {
        var showIntro by rememberSaveable { mutableStateOf(true) }
        Box(Modifier.fillMaxSize()) {
            // Shell composes underneath so catalog loading/skeletons start during the intro.
            val resumeFlow = remember(container) {
                container.profiles.activeId.flatMapLatest { id -> container.resume.observe(id) }
            }
            val resumeEntries by resumeFlow.collectAsStateWithLifecycle(initialValue = emptyList<ResumeEntry>())
            CompositionLocalProvider(LocalResumeEntries provides resumeEntries) {
                CinetorNavHost(container)
            }
            // Player lives above the NavHost: playback survives navigation (mini player in 3.6).
            // Music: glass mini bar above the nav / Now Playing (Task 4.2); audio continues across navigation.
            MusicHost(container)
            // Comics reader overlay (Task 4.3): immersive, above the shell, below the player.
            com.mythbrix.cinetor.feature.comics.ComicsReaderHost(container.comics)
            PlayerHost(
                container.playback, container.subtitles, container.tv.data,
                gesturePrefs = GesturePrefs(settings.doubleTapSec, settings.holdSpeed, settings.gestures),
                autoSkipIntro = settings.skipIntroAuto,
            )
            // "Who's watching?" once per cold start when more than one profile exists (Task 5.1).
            if (!showIntro) StartupProfilePicker(container.profiles)
            if (showIntro) AnimatedSplash(onFinished = { showIntro = false })
        }
    }
}
