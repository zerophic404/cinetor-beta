package com.mythbrix.cinetor.feature.home

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
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
import com.mythbrix.cinetor.design.CinetorTokens
import com.mythbrix.cinetor.design.hexColor

private val HeroShape = RoundedCornerShape(CinetorTokens.Shape.lg)

/** Infinite pager start page aligned to page 0 of the hero list. */
fun heroStartPage(count: Int): Int = if (count <= 1) 0 else (Int.MAX_VALUE / 2).let { it - it % count }

/**
 * Big auto-sliding banner: infinite pager, 5 s auto-advance (paused on touch / drag / off-screen /
 * lifecycle pause), page indicator, parallax (MID+), Ken Burns (HIGH). Settled page → [onColor].
 */
@Composable
fun HeroCarousel(
    heroes: List<Item>,
    myList: Set<String>,
    visible: Boolean,
    onOpen: (Item) -> Unit,
    onPlay: (Item) -> Unit,
    onToggleMyList: (Item) -> Unit,
    onColor: (Color) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (heroes.isEmpty()) return
    val count = heroes.size
    val pager = rememberPagerState(initialPage = heroStartPage(count)) { if (count > 1) Int.MAX_VALUE else 1 }
    val scope = rememberCoroutineScope()
    val tier = Cinetor.tier
    val slideMs = tier.scaled(CinetorTokens.Motion.xlongMs + 120)
    val auto = remember(pager) {
        HeroAutoAdvance(scope) {
            pager.animateScrollToPage(
                page = pager.currentPage + 1,
                animationSpec = tween(slideMs, easing = CinetorTokens.Motion.emphasized),
            )
        }
    }
    DisposableEffect(auto) {
        auto.start()
        onDispose { auto.stop() }
    }
    LaunchedEffect(auto, count) { auto.setPaused(HeroPause.SINGLE_PAGE, count <= 1) }
    val uiPrefs = Cinetor.prefs
    val autoplay = uiPrefs.bannerAutoplay && !uiPrefs.reduceMotion
    LaunchedEffect(auto, autoplay) { auto.setPaused(HeroPause.SETTING, !autoplay) }
    val dragged by pager.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(auto, dragged) { auto.setPaused(HeroPause.DRAG, dragged) }
    LaunchedEffect(auto, visible) { auto.setPaused(HeroPause.OFFSCREEN, !visible) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, auto) {
        val lifecycle = lifecycleOwner.lifecycle
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) auto.pause(HeroPause.LIFECYCLE)
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> auto.pause(HeroPause.LIFECYCLE)
                Lifecycle.Event.ON_RESUME -> auto.resume(HeroPause.LIFECYCLE)
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val colorCallback by rememberUpdatedState(onColor)
    LaunchedEffect(pager, heroes, visible) {
        if (visible) {
            snapshotFlow { pager.settledPage }.collect { page ->
                colorCallback(hexColor(heroes[page % count].dominantColor))
            }
        }
    }

    Column(modifier.fillMaxWidth()) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val wide = maxWidth > 600.dp
            val pageWidth = maxWidth - 32.dp
            val pageHeight = if (wide) (pageWidth * 7f / 16f).coerceAtMost(420.dp) else pageWidth * 5f / 4f
            HorizontalPager(
                state = pager,
                key = { it },
                contentPadding = PaddingValues(horizontal = 16.dp),
                pageSpacing = 12.dp,
                beyondViewportPageCount = 0,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(pageHeight)
                    .pointerInput(auto) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                            auto.pause(HeroPause.TOUCH)
                            do {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                            } while (event.changes.any { it.pressed })
                            auto.resume(HeroPause.TOUCH)
                        }
                    }
                    .semantics { contentDescription = "Featured" },
            ) { page ->
                val item = heroes[page % count]
                HeroPage(
                    item = item,
                    pager = pager,
                    page = page,
                    width = pageWidth,
                    height = pageHeight,
                    inMyList = item.id in myList,
                    onOpen = { onOpen(item) },
                    onPlay = { onPlay(item) },
                    onToggleMyList = { onToggleMyList(item) },
                )
            }
        }
        if (count > 1) {
            Spacer(Modifier.height(10.dp))
            PageIndicator(count, current = { pager.currentPage % count }, Modifier.align(Alignment.CenterHorizontally))
        }
    }
}

@Composable
private fun HeroPage(
    item: Item,
    pager: PagerState,
    page: Int,
    width: Dp,
    height: Dp,
    inMyList: Boolean,
    onOpen: () -> Unit,
    onPlay: () -> Unit,
    onToggleMyList: () -> Unit,
) {
    val tokens = Cinetor.tokens
    val tier = Cinetor.tier
    val dominant = remember(item.dominantColor) { hexColor(item.dominantColor) }
    val isCurrent = pager.settledPage == page
    val kenBurns = if (tier.kenBurns && isCurrent) {
        val t = rememberInfiniteTransition(label = "kenBurns")
        t.animateFloat(
            initialValue = 1f,
            targetValue = 1.12f,
            animationSpec = infiniteRepeatable(tween(9_000, easing = LinearEasing), RepeatMode.Reverse),
            label = "kenBurnsScale",
        )
    } else {
        null
    }
    Box(
        Modifier
            .fillMaxSize()
            .clip(HeroShape)
            .background(dominant)
            .border(1.dp, Color.White.copy(alpha = 0.08f), HeroShape)
            .clickable(onClick = onOpen),
    ) {
        AsyncImage(
            model = rememberSizedImage(item.backdrop, width, height, large = true),
            contentDescription = item.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val base = if (tier.parallax) 1.12f else 1f
                    val kb = kenBurns?.value ?: 1f
                    scaleX = base * kb
                    scaleY = base * kb
                    if (tier.parallax) {
                        val offset = (pager.currentPage - page) + pager.currentPageOffsetFraction
                        translationX = offset * size.width * 0.28f
                    }
                },
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = 0.15f),
                    0.45f to Color.Transparent,
                    1f to Color.Black.copy(alpha = 0.92f),
                ),
            ),
        )
        Column(
            Modifier.align(Alignment.BottomStart).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (item.logo != null) {
                AsyncImage(
                    model = rememberSizedImage(item.logo, 220.dp, 72.dp),
                    contentDescription = item.title,
                    contentScale = ContentScale.Fit,
                    alignment = Alignment.CenterStart,
                    modifier = Modifier.width(220.dp).heightIn(max = 72.dp),
                )
            } else {
                Text(
                    item.title,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                item.genres.take(3).joinToString("  •  "),
                style = MaterialTheme.typography.labelLarge,
                color = Color.White.copy(alpha = 0.8f),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                HeroButton(
                    label = "Play",
                    icon = { Icon(Icons.Rounded.PlayArrow, contentDescription = null, tint = tokens.onAccent) },
                    background = tokens.accent,
                    onClick = onPlay,
                )
                HeroButton(
                    label = "My List",
                    icon = {
                        Icon(
                            if (inMyList) Icons.Rounded.Check else Icons.Rounded.Add,
                            contentDescription = null,
                            tint = Color.White,
                        )
                    },
                    background = Color.White.copy(alpha = 0.16f),
                    onClick = onToggleMyList,
                )
            }
        }
    }
}

@Composable
private fun HeroButton(label: String, icon: @Composable () -> Unit, background: Color, onClick: () -> Unit) {
    val shape = RoundedCornerShape(CinetorTokens.Shape.pill)
    Row(
        Modifier
            .clip(shape)
            .background(background)
            .border(1.dp, Color.White.copy(alpha = 0.18f), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        icon()
        Text(label, style = MaterialTheme.typography.labelLarge, color = Color.White, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun PageIndicator(count: Int, current: () -> Int, modifier: Modifier = Modifier) {
    val tokens = Cinetor.tokens
    val active = current()
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(count) { i ->
            val selected = i == active
            Box(
                Modifier
                    .animateContentSize()
                    .height(6.dp)
                    .width(if (selected) 20.dp else 6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(if (selected) tokens.accent else Color.White.copy(alpha = 0.35f)),
            )
        }
    }
}
