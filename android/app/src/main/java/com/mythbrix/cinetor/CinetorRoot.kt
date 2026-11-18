package com.mythbrix.cinetor

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mythbrix.cinetor.core.data.AppContainer
import com.mythbrix.cinetor.core.data.Space
import com.mythbrix.cinetor.core.perf.PerformanceTier
import com.mythbrix.cinetor.core.perf.PrewarmPlan
import com.mythbrix.cinetor.core.perf.StartupPrewarmer
import com.mythbrix.cinetor.core.perf.detectTier
import com.mythbrix.cinetor.core.player.ResumeEntry
import com.mythbrix.cinetor.core.ui.LocalResumeEntries
import com.mythbrix.cinetor.design.CinetorTheme
import com.mythbrix.cinetor.design.UiPrefs
import com.mythbrix.cinetor.feature.music.MusicHost
import com.mythbrix.cinetor.feature.player.PlayerHost
import com.mythbrix.cinetor.feature.player.gestures.GesturePrefs
import com.mythbrix.cinetor.feature.splash.AnimatedSplash
import com.mythbrix.cinetor.navigation.CinetorNavHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Longest the splash waits for the pre-warm before showing the app anyway. */
private const val SPLASH_PREWARM_CAP_MS = 3_500L

/** Theme + tier from the settings state (applied live, no restart) -> shell with Aperture intro & catalog pre-warmer. */
@OptIn(ExperimentalCoroutinesApi::class)
@Composable
fun CinetorRoot(container: AppContainer) {
    val settings by container.settingsState.state.collectAsStateWithLifecycle()
    val theme by container.settingsState.theme.collectAsStateWithLifecycle()
    val tierOverride = if (settings.reduceMotion) PerformanceTier.LOW else settings.effects.toTierOrNull()
    val tier = remember(tierOverride) { detectTier(container.deviceProfile, tierOverride) }
    val prefs = remember(settings.reduceMotion, settings.bannerAutoplay, settings.tvFocusSounds) {
        UiPrefs(settings.reduceMotion, settings.bannerAutoplay, settings.tvFocusSounds)
    }
    val appContext = LocalContext.current.applicationContext

    CinetorTheme(theme = theme, tier = tier, dynamicColor = settings.dynamicColor, prefs = prefs) {
        var showIntro by rememberSaveable { mutableStateOf(true) }
        var catalogReady by rememberSaveable { mutableStateOf(false) }

        val density = LocalDensity.current
        val screenWidthDp = LocalConfiguration.current.screenWidthDp.toFloat()
        LaunchedEffect(container) {
            val prewarmer = StartupPrewarmer(appContext, container, tier, density, screenWidthDp)
            val spaces = listOf(Space.HOME, Space.ANIME, Space.DRAMA)
            if (showIntro) {
                // Under the splash: hero + first rows of every space, so Home and every section
                // switch open with thumbnails already decoded. The splash never waits past the cap.
                withContext(Dispatchers.IO) {
                    withTimeoutOrNull(SPLASH_PREWARM_CAP_MS) {
                        supervisorScope {
                            spaces.forEach { sp -> launch { runCatching { prewarmer.warm(sp, PrewarmPlan.Splash) } } }
                        }
                    }
                }
            }
            catalogReady = true
            if (container.dataSaver) return@LaunchedEffect
            // After the splash: the rest of each first tab and the top of every other tab.
            withContext(Dispatchers.IO) {
                spaces.forEach { sp -> runCatching { prewarmer.warm(sp, PrewarmPlan.Background) } }
            }
        }

        Box(Modifier.fillMaxSize()) {
            // Shell composes underneath so catalog loading/skeletons start during the intro.
            val resumeFlow = remember(container) {
                container.profiles.activeId.flatMapLatest { id -> container.resume.observe(id) }
            }
            val resumeEntries by resumeFlow.collectAsStateWithLifecycle(initialValue = emptyList<ResumeEntry>())
            CompositionLocalProvider(LocalResumeEntries provides resumeEntries) {
                CinetorNavHost(container)
            }
            // Music: glass mini bar above the nav / Now Playing; audio continues across navigation.
            MusicHost(container)
            // Comics reader overlay: immersive, above the shell, below the player.
            com.mythbrix.cinetor.feature.comics.ComicsReaderHost(container.comics)
            PlayerHost(
                container.playback, container.subtitles, container.tv.data,
                gesturePrefs = GesturePrefs(settings.doubleTapSec, settings.holdSpeed, settings.gestures),
                autoSkipIntro = settings.skipIntroAuto,
                isTv = container.deviceProfile.isTv,
                related = { item ->
                    val genre = item.genres.firstOrNull()
                    if (genre == null) emptyList()
                    else container.catalog.byGenre(item.space, genre).filter { it.id != item.id }.take(12)
                },
            )
            if (showIntro) {
                AnimatedSplash(
                    ready = catalogReady,
                    onFinished = { showIntro = false },
                )
            }
        }
    }
}
