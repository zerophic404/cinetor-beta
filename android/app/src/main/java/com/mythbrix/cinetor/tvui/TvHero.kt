package com.mythbrix.cinetor.tvui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil3.compose.AsyncImage
import com.mythbrix.cinetor.core.data.Item
import com.mythbrix.cinetor.core.ui.rememberSizedImage
import com.mythbrix.cinetor.design.Cinetor
import com.mythbrix.cinetor.design.hexColor
import com.mythbrix.cinetor.feature.home.HeroAutoAdvance
import com.mythbrix.cinetor.feature.home.HeroPause

/**
 * 10-foot hero: full-width backdrop with left scrim, title/meta/synopsis + Play · More info · My List.
 * Auto-advances (5 s) but pauses while focus is inside it (Review Focus #5: a moving banner never
 * steals or shifts focus), when off-screen, on lifecycle pause or with banner autoplay off.
 */
@Composable
fun TvHero(
    heroes: List<Item>,
    myList: Set<String>,
    visible: Boolean,
    height: Dp,
    playRequester: FocusRequester,
    onPlay: (Item) -> Unit,
    onOpen: (Item) -> Unit,
    onToggleMyList: (Item) -> Unit,
    onColor: (Color) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (heroes.isEmpty()) return
    val count = heroes.size
    var index by remember(heroes) { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val auto = remember(heroes) { HeroAutoAdvance(scope) { index = (index + 1) % count } }
    DisposableEffect(auto) {
        auto.start()
        onDispose { auto.stop() }
    }
    val prefs = Cinetor.prefs
    LaunchedEffect(auto, count) { auto.setPaused(HeroPause.SINGLE_PAGE, count <= 1) }
    LaunchedEffect(auto, prefs.bannerAutoplay, prefs.reduceMotion) {
        auto.setPaused(HeroPause.SETTING, !prefs.bannerAutoplay || prefs.reduceMotion)
    }
    LaunchedEffect(auto, visible) { auto.setPaused(HeroPause.OFFSCREEN, !visible) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, auto) {
        val lifecycle = lifecycleOwner.lifecycle
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) auto.pause(HeroPause.LIFECYCLE)
        val observer = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_PAUSE -> auto.pause(HeroPause.LIFECYCLE)
                Lifecycle.Event.ON_RESUME -> auto.resume(HeroPause.LIFECYCLE)
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val item = heroes[index.coerceIn(0, count - 1)]
    val colorCb by rememberUpdatedState(onColor)
    LaunchedEffect(item.id, visible) { if (visible) colorCb(hexColor(item.dominantColor)) }
    val tokens = Cinetor.tokens
    val fadeMs = Cinetor.tier.scaled(600)

    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(24.dp))
            .semantics { contentDescription = "Featured" },
    ) {
        AnimatedContent(
            targetState = item,
            transitionSpec = { fadeIn(tween(fadeMs)) togetherWith fadeOut(tween(fadeMs)) },
            contentKey = { it.id },
            label = "tvHero",
        ) { hero ->
            Box(Modifier.fillMaxSize()) {
                AsyncImage(
                    model = rememberSizedImage(hero.backdrop, 960.dp, height, large = true),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.horizontalGradient(0f to tokens.bg.copy(alpha = 0.92f), 0.55f to tokens.bg.copy(alpha = 0.35f), 1f to Color.Transparent),
                    ),
                )
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(0.6f to Color.Transparent, 1f to tokens.bg.copy(alpha = 0.85f)),
                    ),
                )
            }
        }
        Column(
            Modifier
                .align(Alignment.CenterStart)
                .width(520.dp)
                .padding(start = 40.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                item.title, style = MaterialTheme.typography.displaySmall, color = tokens.textPrimary,
                fontWeight = FontWeight.Black, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            val meta = listOfNotNull(
                item.year.takeIf { it > 0 }?.toString(),
                item.maturity.takeIf { it.isNotBlank() },
                item.rating.takeIf { it > 0 }?.let { "★ %.1f".format(it) },
                item.genres.take(3).joinToString(" · ").ifBlank { null },
            ).joinToString("  •  ")
            if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.labelLarge, color = tokens.textSecondary)
            if (item.synopsis.isNotBlank()) {
                Text(
                    item.synopsis, style = MaterialTheme.typography.bodyMedium, color = tokens.textSecondary,
                    maxLines = 3, overflow = TextOverflow.Ellipsis,
                )
            }
            Row(
                Modifier
                    .padding(top = 8.dp)
                    .onFocusChanged { auto.setPaused(HeroPause.FOCUS, it.hasFocus) }
                    .focusGroup(),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                TvButton("Play", Icons.Rounded.PlayArrow, { onPlay(item) }, Modifier.focusRequester(playRequester), primary = true)
                TvButton("More info", Icons.Rounded.Info, { onOpen(item) })
                val inList = item.id in myList
                TvButton(if (inList) "In My List" else "My List", if (inList) Icons.Rounded.Check else Icons.Rounded.Add, { onToggleMyList(item) })
            }
        }
        if (count > 1) {
            Row(
                Modifier.align(Alignment.BottomEnd).padding(24.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                repeat(count) { i ->
                    Box(
                        Modifier
                            .size(if (i == index) 22.dp else 8.dp, 8.dp)
                            .clip(CircleShape)
                            .background(if (i == index) tokens.accent else Color.White.copy(alpha = 0.35f)),
                    )
                }
            }
        }
    }
}
