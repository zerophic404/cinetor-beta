package com.mythbrix.cinetor.feature.home

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
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
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
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
import androidx.compose.ui.zIndex
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
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max

private val HeroShape = RoundedCornerShape(CinetorTokens.Shape.lg)
private val HeroStarGold = Color(0xFFFFB800)

/** Infinite pager start page aligned to page 0 of the hero list. */
fun heroStartPage(count: Int): Int = if (count <= 1) 0 else (Int.MAX_VALUE / 2).let { it - it % count }

/** Width of one hero card in dp: ~78% width peeking deck on phones, wide showcase on tablets. */
fun heroDeckCardWidthDp(maxWidthDp: Float, wide: Boolean): Float =
    if (wide) (maxWidthDp - 48f).coerceAtMost(920f) else (maxWidthDp * 0.78f).coerceAtMost(440f)

/** 3D Movira-style peeking deck transform metrics for a given signed page [distance]. */
@Immutable
data class HeroDeckMetrics(
    val scale: Float,
    val translationXFraction: Float,
    val dimAlpha: Float,
    val zIndex: Float,
    val visible: Boolean,
)

/** Pure 3D peeking deck geometry: front card full scale/lit; neighbors scaled, pulled inward, dimmed. */
fun heroDeckMetrics(distance: Float): HeroDeckMetrics {
    val absD = abs(distance)
    val clamped = absD.coerceAtMost(2f)
    val scale = 1f - 0.12f * clamped
    val translationXFraction = distance * 0.38f
    val dimAlpha = if (absD <= 0.01f) 0f else (0.45f * clamped).coerceAtMost(0.78f)
    // Quantize zIndex in quarter steps so z-order stays stable without per-frame recomposition.
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
    // Nearly pure gray/black fallback colors gain warmth from the deterministic brand palette.
    return if (maxCh < 0.08f || (maxCh - minCh) < 0.04f) artTint(item.id) else parsed
}

/**
 * Elevated Movira-style 3D Peeking Hero Deck (phones) & Widescreen Cinema Showcase (tablets):
 * - 5 s auto-advance with 6 s post-touch resume delay (`collectIsDraggedAsState` + pointer touch),
 *   paused off-screen, backgrounded, or on `PerformanceTier.LOW` / `reduceMotion`.
 * - Ambient `ArtTint` spill glow behind the deck fed directly to [onColor] (`TintFeeder`).
 * - Liquid Glass specular frame, rich metadata pills (`4K UHD`, `★` rating, year, dub, ≤3 genres),
 *   red-glow `Play` CTA + Liquid Glass `My List` CTA, and animated red capsule page-indicator dots.
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
        animationSpec = tween(if (tier == PerformanceTier.LOW || uiPrefs.reduceMotion) 0 else 450),
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
        modifier = modifier
            .fillMaxWidth()
            .drawBehind {
                val c = animatedSpill
                val alphaTop = if (tier == PerformanceTier.LOW) 0.24f else 0.42f
                drawRect(
                    brush = Brush.radialGradient(
                        0f to c.copy(alpha = alphaTop),
                        0.58f to c.copy(alpha = alphaTop * 0.35f),
                        1f to Color.Transparent,
                        center = Offset(size.width * 0.5f, size.height * 0.42f),
                        radius = max(size.width, size.height) * 0.78f,
                    ),
                )
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val wide = maxWidth > 600.dp
            val cardWidth = heroDeckCardWidthDp(maxWidth.value, wide).dp
            val cardHeight = if (wide) {
                (cardWidth * 7.2f / 16f).coerceIn(260.dp, 420.dp)
            } else {
                cardWidth / 0.75f
            }
            val horizontalPad = ((maxWidth - cardWidth) / 2f).coerceAtLeast(12.dp)
            HorizontalPager(
                state = pager,
                key = { it },
                contentPadding = PaddingValues(horizontal = horizontalPad),
                pageSize = PageSize.Fixed(cardWidth),
                pageSpacing = if (wide) 16.dp else 0.dp,
                beyondViewportPageCount = if (wide) 1 else if (tier == PerformanceTier.LOW) 1 else 2,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(cardHeight)
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
                fun pageDistance(): Float = (pager.currentPage - page) + pager.currentPageOffsetFraction
                val deckZIndex by remember(page) {
                    derivedStateOf { if (wide) 0f else heroDeckMetrics(pageDistance()).zIndex }
                }
                val sideBlur by remember(page) {
                    derivedStateOf {
                        if (wide || tier == PerformanceTier.LOW) 0.dp
                        else if (abs(pageDistance()) >= 0.5f) 2.5.dp else 0.dp
                    }
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .zIndex(deckZIndex)
                        .then(if (sideBlur > 0.dp) Modifier.blur(sideBlur) else Modifier)
                        .graphicsLayer {
                            val d = pageDistance()
                            if (wide) {
                                val s = 1f - (abs(d).coerceAtMost(1f) * 0.05f)
                                scaleX = s
                                scaleY = s
                                alpha = (1f - abs(d).coerceAtMost(1f) * 0.32f)
                            } else {
                                val m = heroDeckMetrics(d)
                                scaleX = m.scale
                                scaleY = m.scale
                                translationX = size.width * m.translationXFraction
                                alpha = if (m.visible) 1f else 0f
                            }
                        }
                        .drawWithContent {
                            drawContent()
                            if (!wide) {
                                val dim = heroDeckMetrics(pageDistance()).dimAlpha
                                if (dim > 0.01f) {
                                    drawRoundRect(
                                        color = Color.Black.copy(alpha = dim),
                                        cornerRadius = CornerRadius(CinetorTokens.Shape.lg.toPx()),
                                    )
                                }
                            }
                        },
                ) {
                    HeroCardSurface(
                        item = item,
                        pager = pager,
                        page = page,
                        wide = wide,
                        width = cardWidth,
                        height = cardHeight,
                        inMyList = item.id in myList,
                        onOpen = { onOpen(item) },
                        onPlay = { onPlay(item) },
                        onToggleMyList = { onToggleMyList(item) },
                    )
                    if (!wide && page != pager.currentPage) {
                        Box(
                            modifier = Modifier
                                .matchParentSize()
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                ) {
                                    auto.notifyUserInteraction()
                                    scope.launch {
                                        pager.animateScrollToPage(
                                            page = page,
                                            animationSpec = tween(slideMs, easing = CinetorTokens.Motion.emphasized),
                                        )
                                    }
                                },
                        )
                    }
                }
            }
        }
        if (count > 1) {
            Spacer(Modifier.height(12.dp))
            PageIndicator(
                count = count,
                current = { pager.settledPage % count },
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HeroCardSurface(
    item: Item,
    pager: PagerState,
    page: Int,
    wide: Boolean,
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
    val kenBurns = if (tier.kenBurns && isCurrent && !Cinetor.prefs.reduceMotion) {
        val t = rememberInfiniteTransition(label = "kenBurns")
        t.animateFloat(
            initialValue = 1f,
            targetValue = 1.09f,
            animationSpec = infiniteRepeatable(tween(9_000, easing = LinearEasing), RepeatMode.Reverse),
            label = "kenBurnsScale",
        )
    } else {
        null
    }
    // Refractive Liquid Glass specular border: bright top-left spotlight rim fading to warm accent gleam.
    val specularBorder = remember(tokens.glassStroke, tokens.accent, isCurrent) {
        Brush.linearGradient(
            colors = listOf(
                Color.White.copy(alpha = if (isCurrent) 0.42f else 0.16f),
                tokens.glassStroke.copy(alpha = 0.22f),
                tokens.accent.copy(alpha = if (isCurrent) 0.34f else 0.12f),
            ),
            start = Offset.Zero,
            end = Offset(1200f, 1600f),
        )
    }

    val is4k = remember(item) { is4kItem(item) }
    val ratingText = remember(item.rating) { formatPosterRating(item.rating) }
    val dubBadge = remember(item.title, item.genres, item.altTitle) {
        detectDubLanguage(item.title, item.genres, item.altTitle)
    }
    val heroGenres = remember(item.genres) { cleanHeroGenres(item.genres, maxCount = 3) }
    val artPath = if (wide) item.backdrop.ifBlank { item.poster } else item.poster.ifBlank { item.backdrop }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .then(
                if (tier != PerformanceTier.LOW && isCurrent) {
                    Modifier.shadow(18.dp, HeroShape, ambientColor = dominant, spotColor = tokens.accentGlow)
                } else {
                    Modifier
                },
            )
            .clip(HeroShape)
            .background(dominant)
            .border(if (isCurrent) 1.25.dp else 1.dp, specularBorder, HeroShape)
            .clickable(onClick = onOpen),
    ) {
        CinetorArtwork(
            seedKey = item.id,
            path = artPath,
            width = width,
            height = height,
            large = true,
            contentDescription = item.title,
            modifier = Modifier.fillMaxSize(),
            imageModifier = Modifier.graphicsLayer {
                val base = if (tier.parallax && wide) 1.08f else 1f
                val kb = kenBurns?.value ?: 1f
                scaleX = base * kb
                scaleY = base * kb
                if (tier.parallax && wide) {
                    val offset = (pager.currentPage - page) + pager.currentPageOffsetFraction
                    translationX = offset * size.width * 0.22f
                }
            },
        )

        // Cinema scrims + subtle top Liquid Glass inner sheen
        if (wide) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.horizontalGradient(
                            0f to Color(0xF008080A),
                            0.55f to Color(0xAD08080A),
                            1f to Color(0x2408080A),
                        ),
                    ),
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to Color.White.copy(alpha = 0.06f),
                            0.12f to Color.Transparent,
                            0.55f to Color.Transparent,
                            1f to Color(0xE008080A),
                        ),
                    ),
            )
        } else {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to Color.White.copy(alpha = 0.07f),
                            0.10f to Color.Transparent,
                            0.38f to Color.Transparent,
                            0.72f to Color(0xBA08080A),
                            1f to Color(0xF508080A),
                        ),
                    ),
            )
        }

        if (wide) {
            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .widthIn(max = width * 0.62f)
                    .padding(horizontal = 24.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.Bottom,
                horizontalAlignment = Alignment.Start,
            ) {
                HeroBadgeRow(is4k = is4k, ratingText = ratingText, year = item.year, dubBadge = dubBadge, centered = false)
                Spacer(Modifier.height(8.dp))
                HeroTitleOrLogo(item = item, centered = false)
                if (heroGenres.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    HeroGenreRow(genres = heroGenres, centered = false)
                }
                if (item.synopsis.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = item.synopsis,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.80f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    HeroPlayCta(onClick = onPlay)
                    HeroMyListCta(inMyList = inMyList, onClick = onToggleMyList)
                }
            }
        } else {
            // Top-row badges on portrait deck when 4K or Dub is present so bottom stack stays uncluttered
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                HeroBadgeRow(is4k = is4k, ratingText = ratingText, year = item.year, dubBadge = dubBadge, centered = true)
                Spacer(Modifier.height(8.dp))
                HeroTitleOrLogo(item = item, centered = true)
                if (heroGenres.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    HeroGenreRow(genres = heroGenres, centered = true)
                }
                Spacer(Modifier.height(14.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    HeroPlayCta(onClick = onPlay, modifier = Modifier.weight(1f))
                    HeroMyListCta(inMyList = inMyList, onClick = onToggleMyList, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HeroBadgeRow(
    is4k: Boolean,
    ratingText: String?,
    year: Int,
    dubBadge: String?,
    centered: Boolean,
) {
    val tokens = Cinetor.tokens
    if (!is4k && ratingText == null && year <= 0 && dubBadge == null) return
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp, if (centered) Alignment.CenterHorizontally else Alignment.Start),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (is4k) {
            val shape = RoundedCornerShape(6.dp)
            Text(
                text = "4K UHD",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.ExtraBold),
                color = tokens.onAccent,
                modifier = Modifier
                    .clip(shape)
                    .background(tokens.accent)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
        if (ratingText != null) {
            val shape = RoundedCornerShape(6.dp)
            Row(
                modifier = Modifier
                    .clip(shape)
                    .background(Color(0xCC09090B))
                    .border(0.5.dp, Color.White.copy(alpha = 0.20f), shape)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text("★", style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold), color = HeroStarGold)
                Text(ratingText, style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold), color = Color.White)
            }
        }
        if (year > 0) {
            val shape = RoundedCornerShape(6.dp)
            Text(
                text = year.toString(),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.SemiBold),
                color = Color.White.copy(alpha = 0.90f),
                modifier = Modifier
                    .clip(shape)
                    .background(Color.White.copy(alpha = 0.14f))
                    .border(0.5.dp, Color.White.copy(alpha = 0.20f), shape)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
        if (dubBadge != null) {
            val shape = RoundedCornerShape(6.dp)
            Text(
                text = dubBadge,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold),
                color = Color(0xFF0A0A0C),
                modifier = Modifier
                    .clip(shape)
                    .background(Color(0xF2FFFFFF))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}

@Composable
private fun HeroTitleOrLogo(item: Item, centered: Boolean) {
    if (item.logo != null) {
        AsyncImage(
            model = rememberSizedImage(item.logo, 220.dp, 68.dp),
            contentDescription = item.title,
            contentScale = ContentScale.Fit,
            alignment = if (centered) Alignment.Center else Alignment.CenterStart,
            modifier = Modifier.width(210.dp).heightIn(max = 68.dp),
        )
    } else {
        Text(
            text = item.title,
            style = MaterialTheme.typography.headlineMedium.copy(
                fontFamily = if (centered) CondensedRankFont else MaterialTheme.typography.headlineMedium.fontFamily,
                fontSize = if (centered) 30.sp else 28.sp,
                lineHeight = if (centered) 32.sp else 32.sp,
                fontWeight = FontWeight.Bold,
            ),
            color = Color.White,
            textAlign = if (centered) TextAlign.Center else TextAlign.Start,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun HeroGenreRow(genres: List<String>, centered: Boolean) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp, if (centered) Alignment.CenterHorizontally else Alignment.Start),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        genres.forEach { genre ->
            val chipShape = RoundedCornerShape(CinetorTokens.Shape.pill)
            Text(
                text = genre,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.Medium),
                color = Color.White.copy(alpha = 0.88f),
                maxLines = 1,
                modifier = Modifier
                    .clip(chipShape)
                    .background(Color.White.copy(alpha = 0.12f))
                    .border(0.5.dp, Color.White.copy(alpha = 0.22f), chipShape)
                    .padding(horizontal = 9.dp, vertical = 3.dp)
                    .clearAndSetSemantics {},
            )
        }
    }
}

@Composable
private fun HeroPlayCta(onClick: () -> Unit, modifier: Modifier = Modifier) {
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
                if (!lowTier) Modifier.shadow(12.dp, shape, ambientColor = tokens.accentGlow, spotColor = tokens.accentGlow)
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
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Rounded.PlayArrow, contentDescription = null, tint = tokens.onAccent, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(6.dp))
        Text("Play", style = MaterialTheme.typography.labelLarge, color = tokens.onAccent, fontWeight = FontWeight.Bold, maxLines = 1)
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
                targetValue = if (selected) 22.dp else 6.dp,
                animationSpec = tween(if (lowTier) 0 else CinetorTokens.Motion.mediumMs),
                label = "heroDotWidth",
            )
            Box(
                Modifier
                    .height(6.dp)
                    .width(dotWidth)
                    .clip(RoundedCornerShape(3.dp))
                    .background(if (selected) tokens.accent else tokens.textPrimary.copy(alpha = 0.28f)),
            )
        }
    }
}
