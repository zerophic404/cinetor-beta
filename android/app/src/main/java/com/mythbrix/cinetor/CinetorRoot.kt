package com.mythbrix.cinetor

import android.graphics.Bitmap
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
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.request.bitmapConfig
import com.mythbrix.cinetor.core.data.AppContainer
import com.mythbrix.cinetor.core.data.Item
import com.mythbrix.cinetor.core.data.Space
import com.mythbrix.cinetor.core.perf.PerformanceTier
import com.mythbrix.cinetor.core.perf.detectTier
import com.mythbrix.cinetor.core.player.ResumeEntry
import com.mythbrix.cinetor.core.ui.LocalResumeEntries
import com.mythbrix.cinetor.core.ui.sizedImageUrl
import com.mythbrix.cinetor.design.CinetorTheme
import com.mythbrix.cinetor.design.UiPrefs
import com.mythbrix.cinetor.feature.music.MusicHost
import com.mythbrix.cinetor.feature.player.PlayerHost
import com.mythbrix.cinetor.feature.player.gestures.GesturePrefs
import com.mythbrix.cinetor.feature.splash.AnimatedSplash
import com.mythbrix.cinetor.navigation.CinetorNavHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import java.util.Collections

private const val PREWARM_POSTER_W = 320
private const val PREWARM_POSTER_H = 480
private const val PREWARM_BACKDROP_W = 780
private const val PREWARM_BACKDROP_H = 439

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

        LaunchedEffect(container) {
            if (!showIntro) {
                catalogReady = true
                return@LaunchedEffect
            }
            val loader = runCatching { SingletonImageLoader.get(appContext) }.getOrNull()
            val enqueued = Collections.synchronizedSet(HashSet<String>())

            fun enqueueImage(rawPath: String, widthPx: Int, heightPx: Int, large: Boolean) {
                if (loader == null || container.dataSaver || rawPath.isBlank()) return
                val scale = if (tier == PerformanceTier.LOW) {
                    if (large) 0.75f else 0.85f
                } else {
                    1f
                }
                val targetW = (widthPx * scale).toInt().coerceAtLeast(1)
                val targetH = (heightPx * scale).toInt().coerceAtLeast(1)
                val url = sizedImageUrl(rawPath, targetW, tier, large = large)
                if (url.isEmpty() || !enqueued.add(url)) return
                val request = ImageRequest.Builder(appContext)
                    .data(url)
                    .size(targetW, targetH)
                    .apply {
                        if (tier.imageRgb565) {
                            bitmapConfig(Bitmap.Config.RGB_565)
                            allowHardware(false)
                        }
                    }
                    .build()
                runCatching { loader.enqueue(request) }
            }

            suspend fun prewarmSpace(space: Space) = coroutineScope {
                val cats = runCatching { container.catalog.categories(space) }.getOrNull().orEmpty()
                val primary = cats.firstOrNull() ?: return@coroutineScope
                val rowsItems = primary.rows.take(2).map { row ->
                    async { runCatching { container.catalog.row(row.id) }.getOrDefault(emptyList()) }
                }.awaitAll()
                val byId = HashMap<String, Item>()
                rowsItems.forEach { list -> list.forEach { byId[it.id] = it } }
                val heroes = primary.heroIds.take(6).map { id ->
                    async { byId[id] ?: runCatching { container.catalog.item(id) }.getOrNull() }
                }.awaitAll().filterNotNull().ifEmpty { rowsItems.firstOrNull().orEmpty().take(5) }

                heroes.forEach { container.peek.put(it) }
                rowsItems.forEach { list -> list.take(10).forEach { container.peek.put(it) } }

                heroes.take(5).forEach { hero ->
                    enqueueImage(hero.backdrop.ifBlank { hero.poster }, PREWARM_BACKDROP_W, PREWARM_BACKDROP_H, large = true)
                    enqueueImage(hero.poster, PREWARM_POSTER_W, PREWARM_POSTER_H, large = false)
                }
                rowsItems.forEach { list ->
                    list.take(8).forEach { item ->
                        enqueueImage(item.poster, PREWARM_POSTER_W, PREWARM_POSTER_H, large = false)
                    }
                }
            }

            withContext(Dispatchers.IO) {
                supervisorScope {
                    launch {
                        try {
                            prewarmSpace(Space.HOME)
                        } finally {
                            catalogReady = true
                        }
                    }
                    launch { runCatching { prewarmSpace(Space.ANIME) } }
                    launch { runCatching { prewarmSpace(Space.DRAMA) } }
                }
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
