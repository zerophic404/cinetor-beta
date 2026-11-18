package com.mythbrix.cinetor.feature.home

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil3.compose.AsyncImage
import com.mythbrix.cinetor.core.data.Item
import com.mythbrix.cinetor.core.perf.PerformanceTier
import com.mythbrix.cinetor.core.ui.CinetorArtwork
import com.mythbrix.cinetor.core.ui.artTint
import com.mythbrix.cinetor.core.ui.rememberSizedImage
import com.mythbrix.cinetor.design.Cinetor
import com.mythbrix.cinetor.design.CinetorTokens
import com.mythbrix.cinetor.design.GlassLevel
import com.mythbrix.cinetor.design.glass
import com.mythbrix.cinetor.design.hexColor
import kotlin.math.abs
import kotlin.math.max

private val ForegroundPosterShape = RoundedCornerShape(14.dp)
private val HeroStarGold = Color(0xFFFFB800)
private val ReadyDotGreen = Color(0xFF22C55E)

/** Infinite pager start page aligned to page 0 of the hero list. */
fun heroStartPage(count: Int): Int = if (count <= 1) 0 else (Int.MAX_VALUE / 2).let { it - it % count }

/** Width helper retained for responsive showcase geometry and unit test coverage. */
fun heroDeckCardWidthDp(maxWidthDp: Float, wide: Boolean): Float =
    if (wide) (maxWidthDp - 48f).coerceAtMost(920f) else (maxWidthDp * 0.78f).coerceAtMost(440f)

/** Page transition metrics for signed pager [distance]. */
@Immutable
data class HeroDeckMetrics(
    val scale: Float,
    val translationXFraction: Float,
    val dimAlpha: Float,
    val zIndex: Float,
    val visible: Boolean,
)

/** Pure composite-only transform metrics for smooth 60/120 fps hero page transitions. */
fun heroDeckMetrics(distance: Float): HeroDeckMetrics {
    val absD = abs(distance)
    val clamped = absD.coerceAtMost(2f)
    val scale = 1f - 0.12f * clamped
    val translationXFraction = distance * 0.38f
    val dimAlpha = if (absD <= 0.01f) 0f else (0.45f * clamped).coerceAtMost(0.78f)
    val zIndex = -((absD * 4f).toInt() / 4f)
    return HeroDeckMetrics(
        scale = scale,
        translationXFraction = translationXFraction,
        dimAlpha = dimAlpha,
        zIndex = zIndex,
        visible = absD <= 2.25f,
    )
}

/** Resolves a rich ambient tint color for [item], falling back to deterministic crimson `artTint`. */
private fun resolveHeroArtTint(item: Item): Color {
    val parsed = runCatching { hexColor(item.dominantColor) }.getOrNull() ?: return artTint(item.id)
    val maxCh = maxOf(parsed.red, parsed.green, parsed.blue)
    val minCh = minOf(parsed.red, parsed.green, parsed.blue)
    return if (maxCh < 0.08f || (maxCh - minCh) < 0.04f) artTint(item.id) else parsed
}

/**
 * Cinetor Beta Showcase Hero Banner:
 * - Full-bleed atmospheric backdrop + smooth multi-stop vignette melting into `tokens.bg`.
 * - Crisp foreground poster card (`RoundedCornerShape(14.dp)` with diagonal specular rim).
 * - Balanced bold title (zero eyebrow/kicker labels above title) + compact metadata pills
 *   (`★ rating`, `year`, `4K`/`HD`, top 2 genres).
 * - Clean `Play` primary button (`contentDescription = "Play"`, optional `7.dp` `#22C55E` dot only)
 *   + `My List` Liquid Glass pill + animated capsule pagination dots.
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
    isReady: (Item) -> Boolean = { it.sources.isNotEmpty() },
) {
    if (heroes.isEmpty()) return
    val count = heroes.size
    val pager = rememberPagerState(initialPage = heroStartPage(count)) { if (count > 1) Int.MAX_VALUE else 1 }
    val scope = rememberCoroutineScope()
    val tokens = Cinetor.tokens
    val tier = Cinetor.tier
    val uiPrefs = Cinetor.prefs
    val slideMs = tier.scaled(CinetorTokens.Motion.xlongMs + 40)
    val auto = remember(pager) {
        HeroAutoAdvance(scope) {
            pager.animateScrollToPage(
                page = pager.settledPage + 1,
                animationSpec = tween(slideMs, easing = CinetorTokens.Motion.emphasized),
            )
        }
    }
    DisposableEffect(auto) {
        auto.start()
        onDispose { auto.stop() }
    }
    LaunchedEffect(auto, count) { auto.setPaused(HeroPause.SINGLE_PAGE, count <= 1) }
    val lowTierPaused = tier == PerformanceTier.LOW
    LaunchedEffect(auto, lowTierPaused) { auto.setPaused(HeroPause.LOW_TIER, lowTierPaused) }
    val settingPaused = !uiPrefs.bannerAutoplay || uiPrefs.reduceMotion
    LaunchedEffect(auto, settingPaused) { auto.setPaused(HeroPause.SETTING, settingPaused) }
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

    val settledHero = heroes[pager.settledPage % count]
    val targetSpill = remember(settledHero.id, settledHero.dominantColor) { resolveHeroArtTint(settledHero) }
    val animatedSpill by animateColorAsState(
        targetValue = targetSpill,
        animationSpec = tween(if (tier == PerformanceTier.LOW || uiPrefs.reduceMotion) 0 else 420),
        label = "heroSpill",
    )
    val colorCallback by rememberUpdatedState(onColor)
    LaunchedEffect(pager, heroes, visible) {
        if (visible) {
            snapshotFlow { pager.settledPage }.collect { page ->
                colorCallback(resolveHeroArtTint(heroes[page % count]))
            }
        }
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val wide = maxWidth > 600.dp
            val showcaseHeight = if (wide) 320.dp else 428.dp
            val backdropPath = settledHero.backdrop.ifBlank { settledHero.poster }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(showcaseHeight),
            ) {
                // Full-bleed atmospheric backdrop + radial spill + smooth multi-stop vignette into tokens.bg
                Crossfade(
                    targetState = settledHero.id to backdropPath,
                    animationSpec = tween(if (tier == PerformanceTier.LOW || uiPrefs.reduceMotion) 0 else 360),
                    label = "heroBackdrop",
                ) { (heroId, path) ->
                    CinetorArtwork(
                        seedKey = heroId,
                        path = path,
                        width = maxWidth,
                        height = showcaseHeight,
                        large = true,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer { alpha = if (tokens.isLight) 0.34f else 0.56f }
                            .then(if (tier == PerformanceTier.LOW) Modifier else Modifier.blur(16.dp)),
                    )
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .drawBehind {
                            val spill = animatedSpill
                            drawRect(
                                brush = Brush.radialGradient(
                                    0f to spill.copy(alpha = if (tokens.isLight) 0.22f else 0.40f),
                                    0.65f to spill.copy(alpha = if (tokens.isLight) 0.08f else 0.14f),
                                    1f to Color.Transparent,
                                    center = Offset(size.width * 0.5f, size.height * 0.32f),
                                    radius = max(size.width, size.height) * 0.85f,
                                ),
                            )
                            drawRect(
                                brush = Brush.verticalGradient(
                                    0f to tokens.bg.copy(alpha = 0.62f),
                                    0.18f to tokens.bg.copy(alpha = 0.16f),
                                    0.52f to tokens.bg.copy(alpha = if (tokens.isLight) 0.72f else 0.64f),
                                    0.80f to tokens.bg.copy(alpha = 0.92f),
                                    1f to tokens.bg,
                                ),
                            )
                        },
                )

                HorizontalPager(
                    state = pager,
                    key = { it },
                    beyondViewportPageCount = 1,
                    modifier = Modifier
                        .fillMaxSize()
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
                    HeroShowcaseSlide(
                        item = item,
                        pager = pager,
                        page = page,
                        wide = wide,
                        inMyList = item.id in myList,
                        ready = isReady(item),
                        onOpen = { onOpen(item) },
                        onPlay = { onPlay(item) },
                        onToggleMyList = { onToggleMyList(item) },
                    )
                }
            }
        }

        if (count > 1) {
            Spacer(Modifier.height(6.dp))
            PageIndicator(
                count = count,
                current = { pager.settledPage % count },
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }
    }
}

@Composable
private fun HeroShowcaseSlide(
    item: Item,
    pager: PagerState,
    page: Int,
    wide: Boolean,
    inMyList: Boolean,
    ready: Boolean,
    onOpen: () -> Unit,
    onPlay: () -> Unit,
    onToggleMyList: () -> Unit,
) {
    val tokens = Cinetor.tokens
    val tier = Cinetor.tier
    val qualityTag = remember(item) { if (is4kItem(item)) "4K" else "HD" }
    val ratingText = remember(item.rating) { formatPosterRating(item.rating) }
    val topGenres = remember(item.genres) { cleanHeroGenres(item.genres, maxCount = 2) }

    val specularBorder = remember(tokens.glassStroke, tokens.accent, tokens.isLight) {
        Brush.linearGradient(
            colors = listOf(
                if (tokens.isLight) Color.Black.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.42f),
                tokens.glassStroke.copy(alpha = 0.24f),
                tokens.accent.copy(alpha = 0.34f),
            ),
            start = Offset.Zero,
            end = Offset(480f, 720f),
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onOpen,
            )
            .graphicsLayer {
                val d = (pager.currentPage - page) + pager.currentPageOffsetFraction
                val absD = abs(d).coerceAtMost(1f)
                val s = 1f - 0.06f * absD
                scaleX = s
                scaleY = s
                alpha = 1f - 0.38f * absD
                if (tier.parallax) {
                    translationX = -d * size.width * 0.08f
                }
            }
            .padding(horizontal = if (wide) 32.dp else 20.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (wide) {
            Row(
                modifier = Modifier.widthIn(max = 860.dp).fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(28.dp),
            ) {
                ForegroundPosterCard(
                    item = item,
                    posterWidth = 160.dp,
                    posterHeight = 240.dp,
                    specularBorder = specularBorder,
                    onClick = onOpen,
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.Start,
                ) {
                    HeroTitleDisplay(item = item, centered = false)
                    Spacer(Modifier.height(10.dp))
                    HeroCompactMetaRow(
                        ratingText = ratingText,
                        year = item.year,
                        qualityTag = qualityTag,
                        genres = topGenres,
                        centered = false,
                    )
                    if (item.synopsis.isNotBlank()) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = item.synopsis,
                            style = MaterialTheme.typography.bodyMedium,
                            color = tokens.textSecondary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        HeroPlayCta(ready = ready, onClick = onPlay, modifier = Modifier.widthIn(min = 148.dp))
                        HeroMyListCta(inMyList = inMyList, onClick = onToggleMyList, modifier = Modifier.widthIn(min = 140.dp))
                    }
                }
            }
        } else {
            Column(
                modifier = Modifier.widthIn(max = 380.dp).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Bottom,
            ) {
                ForegroundPosterCard(
                    item = item,
                    posterWidth = 158.dp,
                    posterHeight = 237.dp,
                    specularBorder = specularBorder,
                    onClick = onOpen,
                )
                Spacer(Modifier.height(14.dp))
                HeroTitleDisplay(item = item, centered = true)
                Spacer(Modifier.height(8.dp))
                HeroCompactMetaRow(
                    ratingText = ratingText,
                    year = item.year,
                    qualityTag = qualityTag,
                    genres = topGenres,
                    centered = true,
                )
                Spacer(Modifier.height(14.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    HeroPlayCta(ready = ready, onClick = onPlay, modifier = Modifier.weight(1f))
                    HeroMyListCta(inMyList = inMyList, onClick = onToggleMyList, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun ForegroundPosterCard(
    item: Item,
    posterWidth: Dp,
    posterHeight: Dp,
    specularBorder: Brush,
    onClick: () -> Unit,
) {
    val tier = Cinetor.tier
    val tokens = Cinetor.tokens
    val dominant = remember(item.dominantColor) { hexColor(item.dominantColor) }
    Box(
        modifier = Modifier
            .size(posterWidth, posterHeight)
            .then(
                if (tier != PerformanceTier.LOW) {
                    Modifier.shadow(20.dp, ForegroundPosterShape, ambientColor = dominant, spotColor = tokens.accentGlow)
                } else {
                    Modifier
                },
            )
            .clip(ForegroundPosterShape)
            .background(dominant)
            .border(1.25.dp, specularBorder, ForegroundPosterShape)
            .clickable(role = Role.Button, onClick = onClick),
    ) {
        CinetorArtwork(
            seedKey = item.id,
            path = item.poster.ifBlank { item.backdrop },
            width = posterWidth,
            height = posterHeight,
            large = true,
            contentDescription = item.title,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            Modifier
                .fillMaxWidth()
                .height(28.dp)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.White.copy(alpha = 0.10f), Color.Transparent),
                    ),
                ),
        )
    }
}

@Composable
private fun HeroTitleDisplay(item: Item, centered: Boolean) {
    val tokens = Cinetor.tokens
    if (item.logo != null) {
        AsyncImage(
            model = rememberSizedImage(item.logo, 210.dp, 54.dp),
            contentDescription = item.title,
            contentScale = ContentScale.Fit,
            alignment = if (centered) Alignment.Center else Alignment.CenterStart,
            modifier = Modifier.width(200.dp).heightIn(max = 54.dp),
        )
    } else {
        Text(
            text = item.title,
            style = MaterialTheme.typography.headlineSmall.copy(
                fontSize = if (centered) 23.sp else 26.sp,
                lineHeight = if (centered) 27.sp else 30.sp,
                fontWeight = FontWeight.ExtraBold,
            ),
            color = tokens.textPrimary,
            textAlign = if (centered) TextAlign.Center else TextAlign.Start,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HeroCompactMetaRow(
    ratingText: String?,
    year: Int,
    qualityTag: String,
    genres: List<String>,
    centered: Boolean,
) {
    val tokens = Cinetor.tokens
    val pillShape = RoundedCornerShape(6.dp)
    val pillFill = tokens.textPrimary.copy(alpha = 0.08f)
    val pillBorder = tokens.textPrimary.copy(alpha = 0.18f)

    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp, if (centered) Alignment.CenterHorizontally else Alignment.Start),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (ratingText != null) {
            Row(
                modifier = Modifier
                    .clip(pillShape)
                    .background(Color(0xD909090B))
                    .border(0.5.dp, Color.White.copy(alpha = 0.18f), pillShape)
                    .padding(horizontal = 6.dp, vertical = 2.5.dp)
                    .clearAndSetSemantics {},
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text("★", style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold), color = HeroStarGold)
                Text(ratingText, style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold), color = Color.White)
            }
        }
        if (year > 0) {
            Text(
                text = year.toString(),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold),
                color = tokens.textPrimary,
                modifier = Modifier
                    .clip(pillShape)
                    .background(pillFill)
                    .border(0.5.dp, pillBorder, pillShape)
                    .padding(horizontal = 6.dp, vertical = 2.5.dp)
                    .clearAndSetSemantics {},
            )
        }
        val is4k = qualityTag == "4K"
        Text(
            text = qualityTag,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.ExtraBold),
            color = if (is4k) tokens.onAccent else tokens.textPrimary,
            modifier = Modifier
                .clip(pillShape)
                .background(if (is4k) tokens.accent else pillFill)
                .then(if (is4k) Modifier else Modifier.border(0.5.dp, pillBorder, pillShape))
                .padding(horizontal = 6.dp, vertical = 2.5.dp)
                .clearAndSetSemantics {},
        )
        genres.forEach { genre ->
            val genreShape = RoundedCornerShape(CinetorTokens.Shape.pill)
            Text(
                text = genre,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp, fontWeight = FontWeight.Medium),
                color = tokens.textSecondary,
                maxLines = 1,
                modifier = Modifier
                    .clip(genreShape)
                    .background(pillFill)
                    .border(0.5.dp, pillBorder, genreShape)
                    .padding(horizontal = 8.dp, vertical = 2.5.dp)
                    .clearAndSetSemantics {},
            )
        }
    }
}

@Composable
private fun HeroPlayCta(
    ready: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = Cinetor.tokens
    val lowTier = Cinetor.tier == PerformanceTier.LOW
    val shape = RoundedCornerShape(CinetorTokens.Shape.pill)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (pressed) 0.96f else if (focused) 1.04f else 1f, label = "playCtaScale")
    val gradient = remember(tokens.accent, tokens.accentEnd) {
        Brush.horizontalGradient(listOf(tokens.accent, tokens.accentEnd))
    }
    Row(
        modifier = modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .height(44.dp)
            .then(
                if (!lowTier) Modifier.shadow(10.dp, shape, ambientColor = tokens.accentGlow, spotColor = tokens.accentGlow)
                else Modifier,
            )
            .clip(shape)
            .background(gradient)
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = if (focused) tokens.focusRing else Color.White.copy(alpha = 0.24f),
                shape = shape,
            )
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "Play" }
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Rounded.PlayArrow, contentDescription = null, tint = tokens.onAccent, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(6.dp))
        Text("Play", style = MaterialTheme.typography.labelLarge, color = tokens.onAccent, fontWeight = FontWeight.Bold, maxLines = 1)
        if (ready) {
            Spacer(Modifier.width(7.dp))
            Box(
                Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(ReadyDotGreen),
            )
        }
    }
}

@Composable
private fun HeroMyListCta(inMyList: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val tokens = Cinetor.tokens
    val shape = RoundedCornerShape(CinetorTokens.Shape.pill)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (pressed) 0.96f else if (focused) 1.04f else 1f, label = "listCtaScale")
    Row(
        modifier = modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .height(44.dp)
            .glass(shape, GlassLevel.SURFACE)
            .then(
                if (focused) Modifier.border(2.dp, tokens.focusRing, shape)
                else Modifier,
            )
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "My List" }
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = if (inMyList) Icons.Rounded.Check else Icons.Rounded.Add,
            contentDescription = null,
            tint = tokens.textPrimary,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text("My List", style = MaterialTheme.typography.labelLarge, color = tokens.textPrimary, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
private fun PageIndicator(count: Int, current: () -> Int, modifier: Modifier = Modifier) {
    val tokens = Cinetor.tokens
    val lowTier = Cinetor.tier == PerformanceTier.LOW
    val active = current()
    Row(
        modifier = modifier.semantics { contentDescription = "Item ${active + 1} of $count" },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(count) { i ->
            val selected = i == active
            val dotWidth by animateDpAsState(
                targetValue = if (selected) 20.dp else 6.dp,
                animationSpec = tween(if (lowTier) 0 else CinetorTokens.Motion.mediumMs),
                label = "heroDotWidth",
            )
            Box(
                Modifier
                    .height(6.dp)
                    .width(dotWidth)
                    .clip(RoundedCornerShape(3.dp))
                    .background(if (selected) tokens.accent else tokens.textPrimary.copy(alpha = 0.26f)),
            )
        }
    }
}
