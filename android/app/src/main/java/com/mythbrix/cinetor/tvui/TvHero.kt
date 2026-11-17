package com.mythbrix.cinetor.tvui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
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
import com.mythbrix.cinetor.core.ui.rememberSizedImage
import com.mythbrix.cinetor.design.Cinetor
import com.mythbrix.cinetor.design.CinetorTokens
import com.mythbrix.cinetor.design.blendTint
import com.mythbrix.cinetor.design.hexColor
import com.mythbrix.cinetor.feature.home.HeroAutoAdvance
import com.mythbrix.cinetor.feature.home.HeroPause
import java.util.Locale
import kotlin.math.max

enum class TvPillKind { ACCENT_4K, RATING, DUB, NEUTRAL }

@Immutable
data class TvHeroPill(val text: String, val kind: TvPillKind)

private val HERO_NON_GENRES = setOf(
    "hindi", "[hindi]", "bengali", "[bengali]", "bangla", "[bangla]",
    "tamil", "[tamil]", "telugu", "[telugu]", "english", "[english]",
    "4k", "4k uhd", "uhd",
)

/**
 * Computes widescreen 16:9 / 21:9 TV hero height (in dp) so BOTH the framed hero banner AND the
 * first content shelf below peek comfortably on 1080p TV screens without clipping.
 */
fun tvHeroHeightDp(viewportHeightDp: Float, showTabs: Boolean): Float =
    if (showTabs) {
        (viewportHeightDp * 0.48f).coerceIn(208f, 268f)
    } else {
        (viewportHeightDp * 0.52f).coerceIn(220f, 288f)
    }

fun tvHeroHeight(viewportHeight: Dp, showTabs: Boolean): Dp =
    tvHeroHeightDp(viewportHeight.value, showTabs).dp

/** Ordered metadata pills (`4K UHD`, `★` rating, dub language, year, maturity) for the TV hero. */
fun tvHeroMetaPills(item: Item): List<TvHeroPill> = buildList {
    if (tvIs4k(item)) add(TvHeroPill("4K UHD", TvPillKind.ACCENT_4K))
    tvRatingBadge(item.rating)?.let { add(TvHeroPill("★ $it", TvPillKind.RATING)) }
    tvDubBadge(item)?.let { add(TvHeroPill(it, TvPillKind.DUB)) }
    if (item.year > 0) add(TvHeroPill(item.year.toString(), TvPillKind.NEUTRAL))
    val mat = item.maturity.trim()
    if (mat.isNotEmpty() && !mat.equals("NR", ignoreCase = true)) {
        add(TvHeroPill(mat, TvPillKind.NEUTRAL))
    }
}

/** Up to 3 clean display genres separated by bullets, excluding dub/resolution tags. */
fun tvHeroGenreLine(item: Item): String =
    item.genres
        .map { it.trim() }
        .filter { it.isNotEmpty() && it.lowercase(Locale.US) !in HERO_NON_GENRES }
        .take(3)
        .joinToString(" • ")

/**
 * Cinetor Beta 10-foot widescreen Liquid Glass framed hero banner:
 * - Sized proportionally so hero + first content row fit comfortably on 1080p TVs.
 * - Horizontal + vertical cinema scrims for high-contrast legibility across all 8 themes.
 * - `4K UHD` / `★` rating / dub language / year pills, crisp D-pad spotlight focus rings on
 *   `Play`, `Details`, and `My List`, plus ambient `ArtTint` spill glow.
 */
@OptIn(ExperimentalComposeUiApi::class)
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
    val tier = Cinetor.tier
    LaunchedEffect(auto, count) { auto.setPaused(HeroPause.SINGLE_PAGE, count <= 1) }
    LaunchedEffect(auto, prefs.bannerAutoplay, prefs.reduceMotion, tier) {
        auto.setPaused(
            HeroPause.SETTING,
            !prefs.bannerAutoplay || prefs.reduceMotion || tier == PerformanceTier.LOW,
        )
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
    val dominant = remember(item.dominantColor) { hexColor(item.dominantColor) }
    val colorCb by rememberUpdatedState(onColor)
    LaunchedEffect(item.id, visible) { if (visible) colorCb(dominant) }

    val tokens = Cinetor.tokens
    val fadeMs = if (prefs.reduceMotion || tier == PerformanceTier.LOW) 0 else tier.scaled(520)
    val targetGlow = blendTint(dominant, tokens.accent, 0.55f)
    val artGlow by animateColorAsState(
        targetValue = targetGlow,
        animationSpec = tween(
            if (prefs.reduceMotion) 0 else tier.scaled(CinetorTokens.Motion.tintAnimMs),
            easing = CinetorTokens.Motion.standard,
        ),
        label = "tvHeroArtGlow",
    )

    val heroShape = RoundedCornerShape(20.dp)
    val specularFrame = remember(tokens.glassStroke, artGlow) {
        Brush.linearGradient(
            colors = listOf(
                Color.White.copy(alpha = 0.44f),
                tokens.glassStroke.copy(alpha = 0.28f),
                artGlow.copy(alpha = 0.42f),
            ),
            start = Offset.Zero,
            end = Offset.Infinite,
        )
    }
    val pills = remember(item) { tvHeroMetaPills(item) }
    val genreLine = remember(item.genres) { tvHeroGenreLine(item) }

    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .drawBehind {
                // Ambient ArtTint spill glow radiating softly behind and below the framed cinema screen.
                val spillAlpha = if (tier == PerformanceTier.LOW) 0.18f else 0.32f
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(artGlow.copy(alpha = spillAlpha), Color.Transparent),
                        center = Offset(size.width * 0.36f, size.height * 0.72f),
                        radius = max(size.width, size.height) * 0.72f,
                    ),
                )
            }
            .shadow(
                elevation = if (tier == PerformanceTier.LOW) 0.dp else 16.dp,
                shape = heroShape,
                clip = false,
                ambientColor = artGlow,
                spotColor = artGlow,
            )
            .clip(heroShape)
            .background(Color(0xFF09090B))
            .border(1.dp, specularFrame, heroShape)
            .semantics { contentDescription = "Featured" },
    ) {
        AnimatedContent(
            targetState = item,
            transitionSpec = { fadeIn(tween(fadeMs)) togetherWith fadeOut(tween(fadeMs)) },
            contentKey = { it.id },
            label = "tvHero",
        ) { hero ->
            val artPath = hero.backdrop.ifBlank { hero.poster }
            Box(Modifier.fillMaxSize()) {
                if (artPath.isBlank()) {
                    TvGeneratedArt(hero.id, Modifier.fillMaxSize())
                } else {
                    AsyncImage(
                        model = rememberSizedImage(artPath, 960.dp, height, large = true),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                // Horizontal left-to-right cinema scrim so title, pills, and CTAs stay razor-sharp.
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.horizontalGradient(
                            0f to Color(0xF209090B),
                            0.50f to Color(0xBD09090B),
                            0.78f to Color(0x4509090B),
                            1f to Color(0x1A09090B),
                        ),
                    ),
                )
                // Vertical bottom-to-top cinema scrim + subtle top specular sheen.
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(
                            0f to Color.White.copy(alpha = 0.06f),
                            0.12f to Color.Transparent,
                            0.54f to Color.Transparent,
                            1f to Color(0xE609090B),
                        ),
                    ),
                )
            }
        }

        Column(
            Modifier
                .fillMaxHeight()
                .widthIn(max = 540.dp)
                .padding(horizontal = 28.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.Bottom,
            horizontalAlignment = Alignment.Start,
        ) {
            if (pills.isNotEmpty() || genreLine.isNotEmpty()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    pills.forEach { pill -> TvHeroMetaTag(pill) }
                    if (genreLine.isNotEmpty()) {
                        Text(
                            text = genreLine,
                            style = MaterialTheme.typography.labelMedium,
                            color = Color(0xCCFFFFFF),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
            }

            Text(
                text = item.title,
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontSize = 28.sp,
                    lineHeight = 32.sp,
                    fontWeight = FontWeight.Black,
                ),
                color = Color.White,
                maxLines = if (height < 232.dp) 1 else 2,
                overflow = TextOverflow.Ellipsis,
            )

            if (item.synopsis.isNotBlank() && height >= 216.dp) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = item.synopsis,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xCCFFFFFF),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(Modifier.height(12.dp))

            Row(
                Modifier
                    .onFocusChanged { auto.setPaused(HeroPause.FOCUS, it.hasFocus) }
                    .focusProperties { enter = { playRequester } }
                    .focusGroup(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TvButton(
                    label = "Play",
                    icon = Icons.Rounded.PlayArrow,
                    onClick = { onPlay(item) },
                    modifier = Modifier.focusRequester(playRequester),
                    primary = true,
                    onDarkScrim = true,
                )
                TvButton(
                    label = "Details",
                    icon = Icons.Rounded.Info,
                    onClick = { onOpen(item) },
                    onDarkScrim = true,
                )
                val inList = item.id in myList
                TvButton(
                    label = if (inList) "In My List" else "My List",
                    icon = if (inList) Icons.Rounded.Check else Icons.Rounded.Add,
                    onClick = { onToggleMyList(item) },
                    onDarkScrim = true,
                )
            }
        }

        if (count > 1) {
            Row(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(horizontal = 22.dp, vertical = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                repeat(count) { i ->
                    val active = i == index
                    Box(
                        Modifier
                            .animateContentSize()
                            .size(width = if (active) 22.dp else 7.dp, height = 7.dp)
                            .clip(CircleShape)
                            .background(if (active) tokens.accent else Color.White.copy(alpha = 0.35f)),
                    )
                }
            }
        }
    }
}

@Composable
private fun TvHeroMetaTag(pill: TvHeroPill) {
    val tokens = Cinetor.tokens
    val shape = RoundedCornerShape(7.dp)
    when (pill.kind) {
        TvPillKind.ACCENT_4K -> Box(
            Modifier
                .clip(shape)
                .background(tokens.accent)
                .padding(horizontal = 7.dp, vertical = 2.5.dp),
        ) {
            Text(
                pill.text,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold),
                color = tokens.onAccent,
                maxLines = 1,
            )
        }
        TvPillKind.RATING -> Row(
            Modifier
                .clip(shape)
                .background(Color(0xD9101014))
                .border(1.dp, Color.White.copy(alpha = 0.22f), shape)
                .padding(horizontal = 7.dp, vertical = 2.5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                "★",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold),
                color = Color(0xFFFFB800),
            )
            Text(
                pill.text.removePrefix("★").trim(),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold),
                color = Color.White,
            )
        }
        TvPillKind.DUB -> Box(
            Modifier
                .clip(shape)
                .background(Color(0xF0FFFFFF))
                .padding(horizontal = 7.dp, vertical = 2.5.dp),
        ) {
            Text(
                pill.text,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold),
                color = Color(0xFF09090B),
                maxLines = 1,
            )
        }
        TvPillKind.NEUTRAL -> Box(
            Modifier
                .clip(shape)
                .background(Color(0xAD141418))
                .border(1.dp, Color.White.copy(alpha = 0.20f), shape)
                .padding(horizontal = 7.dp, vertical = 2.5.dp),
        ) {
            Text(
                pill.text,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
                color = Color(0xEBFFFFFF),
                maxLines = 1,
            )
        }
    }
}
