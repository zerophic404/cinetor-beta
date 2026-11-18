package com.mythbrix.cinetor.feature.home

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.DeviceFontFamilyName
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
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
import coil3.compose.AsyncImage
import com.mythbrix.cinetor.core.data.EventStatus
import com.mythbrix.cinetor.core.data.Item
import com.mythbrix.cinetor.core.data.ItemType
import com.mythbrix.cinetor.core.perf.PerformanceTier
import com.mythbrix.cinetor.core.player.progressByItem
import com.mythbrix.cinetor.core.ui.CinetorArtwork
import com.mythbrix.cinetor.core.ui.LocalResumeEntries
import com.mythbrix.cinetor.core.ui.rememberSizedImage
import com.mythbrix.cinetor.core.ui.sharedPoster
import com.mythbrix.cinetor.design.Cinetor
import com.mythbrix.cinetor.design.CinetorTokens
import com.mythbrix.cinetor.design.hexColor
import java.util.Locale

private val CardShape = RoundedCornerShape(CinetorTokens.Shape.card)
/** Rating star in Cinema Red (white / red / black palette). */
private val StarGold = Color(0xFFE50914)

internal val CondensedRankFont: FontFamily = FontFamily(
    Font(DeviceFontFamilyName("sans-serif-condensed"), FontWeight.Normal),
    Font(DeviceFontFamilyName("sans-serif-condensed"), FontWeight.Bold),
)

private val HINDI_REGEX = Regex("""\bhindi\b""", RegexOption.IGNORE_CASE)
private val BENGALI_REGEX = Regex("""\b(?:bengali|bangla)\b""", RegexOption.IGNORE_CASE)
private val TAMIL_REGEX = Regex("""\btamil\b""", RegexOption.IGNORE_CASE)
private val TELUGU_REGEX = Regex("""\btelugu\b""", RegexOption.IGNORE_CASE)
private val ENGLISH_TITLE_TAG_REGEX = Regex("""(?:\[\s*english\s*\]|\(\s*english\s*\)|\benglish\s+dub(?:bed)?\b)""", RegexOption.IGNORE_CASE)
private val ENGLISH_GENRE_REGEX = Regex("""^\[?\s*english(?:\s+dub)?\s*\]?$""", RegexOption.IGNORE_CASE)
private val NON_GENRE_TAG_REGEX = Regex(
    """^\[?\s*(?:hindi|bengali|bangla|tamil|telugu|english|dual\s*audio|4k(?:\s*uhd)?|2k|1080p|uhd)\s*\]?$""",
    RegexOption.IGNORE_CASE,
)

/** Deterministic fallback progress (0.15–0.85) for Continue Watching mock entries. */
fun mockProgress(id: String): Float = ((id.hashCode() and 0x7fffffff) % 71 + 15) / 100f

/** Formats positive ratings to one decimal place (e.g. `7.8`); hides `0` / invalid ratings. */
fun formatPosterRating(rating: Double): String? =
    if (!rating.isFinite() || rating <= 0.0) null else String.format(Locale.US, "%.1f", rating)

/** Detects dub/audio language badges (`Hindi`, `Bengali`, `Tamil`, `Telugu`, `English`) from title or genre tags. */
fun detectDubLanguage(title: String, genres: List<String>, altTitle: String? = null): String? {
    val combined = buildString {
        append(title)
        if (!altTitle.isNullOrBlank()) {
            append(' ')
            append(altTitle)
        }
        genres.forEach {
            append(' ')
            append(it)
        }
    }
    return when {
        HINDI_REGEX.containsMatchIn(combined) -> "Hindi"
        BENGALI_REGEX.containsMatchIn(combined) -> "Bengali"
        TAMIL_REGEX.containsMatchIn(combined) -> "Tamil"
        TELUGU_REGEX.containsMatchIn(combined) -> "Telugu"
        ENGLISH_TITLE_TAG_REGEX.containsMatchIn(title) ||
            (altTitle != null && ENGLISH_TITLE_TAG_REGEX.containsMatchIn(altTitle)) ||
            genres.any { ENGLISH_GENRE_REGEX.matches(it.trim()) } -> "English"
        else -> null
    }
}

/** Below 2% is a peek and above 95% is finished; neither renders a poster progress bar. */
fun visibleWatchProgress(rawProgress: Float?): Float {
    if (rawProgress == null || !rawProgress.isFinite()) return 0f
    return if (rawProgress in 0.02f..0.95f) rawProgress else 0f
}

/** True when the item carries 4K / 2160p sources or explicit 4K metadata tags. */
fun is4kItem(item: Item): Boolean =
    item.sources.any { s ->
        (s.height ?: 0) >= 2160 ||
            s.label?.contains("4K", ignoreCase = true) == true ||
            s.label?.contains("2160", ignoreCase = true) == true
    } ||
        item.title.contains("4K", ignoreCase = true) ||
        item.maturity.contains("4K", ignoreCase = true) ||
        item.genres.any { it.contains("4K", ignoreCase = true) || it.equals("UHD", ignoreCase = true) }

/** Strips language/resolution tags and returns up to [maxCount] clean genre names. */
fun cleanHeroGenres(genres: List<String>, maxCount: Int = 3): List<String> =
    genres.map { it.trim() }
        .filter { it.isNotEmpty() && !NON_GENRE_TAG_REGEX.matches(it) }
        .distinct()
        .take(maxCount)

/**
 * Shared card chrome: press/focus scales 1.0→1.08 with a glow in the item's accent colour;
 * becoming active reports the item colour (→ debounced app tint).
 */
@Composable
fun CardFrame(
    item: Item,
    onClick: () -> Unit,
    onActive: (Color) -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = CardShape,
    content: @Composable BoxScope.() -> Unit,
) {
    val tokens = Cinetor.tokens
    val lowTier = Cinetor.tier == PerformanceTier.LOW
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    val active = pressed || focused
    val scale by animateFloatAsState(if (active) CinetorTokens.Motion.cardFocusScale else 1f, label = "cardScale")
    val glow by animateDpAsState(if (active && !lowTier) 18.dp else 0.dp, label = "cardGlow")
    val dominant = remember(item.dominantColor) { hexColor(item.dominantColor) }
    val accent = remember(item.accentColor) { hexColor(item.accentColor) }
    LaunchedEffect(active) { if (active) onActive(dominant) }
    Box(
        modifier
            .zIndex(if (active) 1f else 0f)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .then(
                if (glow > 0.dp) Modifier.shadow(glow, shape, clip = false, ambientColor = accent, spotColor = accent)
                else Modifier,
            )
            .clip(shape)
            .background(dominant)
            .border(
                if (active) 2.dp else 1.dp,
                if (active) tokens.focusRing.copy(alpha = 0.92f) else tokens.glassStroke,
                shape,
            )
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .semantics { contentDescription = item.title },
        content = content,
    )
}

/** Shared high-contrast 1–2 line title label displayed cleanly underneath every thumbnail container. */
@Composable
internal fun CardTitleBelow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = Cinetor.tokens
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium.copy(
            fontSize = 12.5.sp,
            lineHeight = 16.sp,
            fontWeight = FontWeight.Medium,
        ),
        color = tokens.textPrimary,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 6.dp, start = 2.dp, end = 2.dp)
            .heightIn(min = 34.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .clearAndSetSemantics {},
    )
}

/**
 * 2:3 poster card with CinePulse/Beta badges AND a clean 1–2 line title underneath:
 * - Top-left dark translucent `★ 7.8` pill (`rating > 0`)
 * - Top-right crisp language badge (`Hindi`, `Bengali`, `Tamil`, `Telugu`, `English`) or vertical Series capsule
 * - Bottom Cinema Red watch-progress line (`0.02f..0.95f`)
 * - Visible high-contrast `12.5.sp` `FontWeight.Medium` title below the rounded poster container.
 */
@Composable
fun PosterCard(
    item: Item,
    onClick: () -> Unit,
    onActive: (Color) -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = 112.dp,
    sharedKey: String? = null,
) {
    val tokens = Cinetor.tokens
    val height = width * 1.5f
    val resumeEntries = LocalResumeEntries.current
    val progress = remember(item.id, resumeEntries) {
        visibleWatchProgress(progressByItem(resumeEntries)[item.id])
    }
    val ratingText = remember(item.rating) { formatPosterRating(item.rating) }
    val langBadge = remember(item.title, item.genres, item.altTitle) {
        detectDubLanguage(item.title, item.genres, item.altTitle)
    }
    Column(modifier = modifier.width(width)) {
        CardFrame(item, onClick, onActive, Modifier.sharedPoster(sharedKey).size(width, height)) {
            CinetorArtwork(
                seedKey = item.id,
                path = item.poster,
                width = width,
                height = height,
                fallbackTitle = null,
                modifier = Modifier.fillMaxSize(),
            )

            if (ratingText != null) {
                val pillShape = RoundedCornerShape(6.dp)
                Row(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(5.dp)
                        .clip(pillShape)
                        .background(Color(0xCC09090B))
                        .border(0.5.dp, Color.White.copy(alpha = 0.16f), pillShape)
                        .padding(horizontal = 5.dp, vertical = 2.dp)
                        .clearAndSetSemantics {},
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = "★",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold),
                        color = StarGold,
                    )
                    Text(
                        text = ratingText,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold),
                        color = Color.White,
                    )
                }
            }

            if (langBadge != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(5.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(Color(0xF2FFFFFF))
                        .padding(horizontal = 5.dp, vertical = 1.5.dp)
                        .clearAndSetSemantics {},
                ) {
                    Text(
                        text = langBadge,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, fontWeight = FontWeight.Bold),
                        color = Color(0xFF0A0A0C),
                    )
                }
            } else if (item.type == ItemType.SERIES) {
                val capsuleShape = RoundedCornerShape(3.5.dp)
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(width = 7.dp, height = 16.dp)
                        .clip(capsuleShape)
                        .background(Color(0xEBFFFFFF))
                        .border(0.5.dp, Color.Black.copy(alpha = 0.25f), capsuleShape),
                )
            }

            if (progress > 0f) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(3.dp)
                        .background(Color(0x59FFFFFF)),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(progress)
                            .fillMaxHeight()
                            .background(tokens.accent),
                    )
                }
            }
        }
        CardTitleBelow(title = item.title, onClick = onClick)
    }
}

/** 16:9 widescreen backdrop thumbnail with rating/dub badges and clean title underneath. */
@Composable
fun WideCard(item: Item, onClick: () -> Unit, onActive: (Color) -> Unit, width: Dp = 216.dp) {
    val height = width * 9f / 16f
    val ratingText = remember(item.rating) { formatPosterRating(item.rating) }
    val langBadge = remember(item.title, item.genres, item.altTitle) {
        detectDubLanguage(item.title, item.genres, item.altTitle)
    }
    Column(Modifier.width(width)) {
        CardFrame(item, onClick, onActive, Modifier.size(width, height)) {
            CinetorArtwork(
                seedKey = item.id,
                path = item.backdrop.ifBlank { item.poster },
                width = width,
                height = height,
                fallbackTitle = null,
                modifier = Modifier.fillMaxSize(),
            )
            if (ratingText != null) {
                Row(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xCC09090B))
                        .border(0.5.dp, Color.White.copy(alpha = 0.16f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 5.dp, vertical = 2.dp)
                        .clearAndSetSemantics {},
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text("★", style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold), color = StarGold)
                    Text(ratingText, style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold), color = Color.White)
                }
            }
            if (langBadge != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(Color(0xF2FFFFFF))
                        .padding(horizontal = 5.dp, vertical = 1.5.dp)
                        .clearAndSetSemantics {},
                ) {
                    Text(
                        text = langBadge,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, fontWeight = FontWeight.Bold),
                        color = Color(0xFF0A0A0C),
                    )
                }
            }
        }
        CardTitleBelow(title = item.title, onClick = onClick)
    }
}

/** 16:9 backdrop tile delegating to [WideCard] so titles always appear underneath thumbnails. */
@Composable
fun LandscapeCard(item: Item, onClick: () -> Unit, onActive: (Color) -> Unit, width: Dp = 216.dp) {
    WideCard(item = item, onClick = onClick, onActive = onActive, width = width)
}

/**
 * Ranked chart card (`1`-`10`), restored from v2.0: a giant condensed rank numeral with a soft
 * gradient fill, specular red stroke and drop shadow, anchored to the poster's bottom edge.
 * The [PosterCard] keeps its title underneath, so the numeral sits inside the poster band only.
 */
@Composable
fun Top10Card(rank: Int, item: Item, onClick: () -> Unit, onActive: (Color) -> Unit, sharedKey: String?) {
    val tokens = Cinetor.tokens
    val posterWidth = 112.dp
    val posterHeight = posterWidth * 1.5f
    val cardWidth = if (rank >= 10) 172.dp else 152.dp
    val rankLabel = rank.toString()
    val fontSize = if (rank >= 10) 94.sp else 112.sp
    val letterSpacing = if (rank >= 10) (-6).sp else (-2).sp
    val fillBrush = remember(tokens.textPrimary) {
        Brush.verticalGradient(
            listOf(
                tokens.textPrimary.copy(alpha = 0.24f),
                tokens.textPrimary.copy(alpha = 0.06f),
            ),
        )
    }
    val strokeBrush = remember(tokens.textPrimary, tokens.accent) {
        Brush.verticalGradient(
            listOf(
                tokens.textPrimary.copy(alpha = 0.92f),
                tokens.accent.copy(alpha = 0.80f),
            ),
        )
    }
    val rankStyle = TextStyle(
        fontFamily = CondensedRankFont,
        fontSize = fontSize,
        lineHeight = fontSize,
        fontWeight = FontWeight.Bold,
        letterSpacing = letterSpacing,
    )
    Box(Modifier.width(cardWidth)) {
        Box(
            modifier = Modifier
                .height(posterHeight)
                .padding(start = 2.dp)
                .clearAndSetSemantics {},
            contentAlignment = Alignment.BottomStart,
        ) {
            Text(
                text = rankLabel,
                style = rankStyle.copy(
                    brush = fillBrush,
                    shadow = Shadow(
                        color = Color.Black.copy(alpha = 0.55f),
                        offset = Offset(2f, 4f),
                        blurRadius = 10f,
                    ),
                ),
            )
            Text(
                text = rankLabel,
                style = rankStyle.copy(
                    brush = strokeBrush,
                    drawStyle = Stroke(width = 4.2f, join = StrokeJoin.Round),
                ),
            )
        }
        PosterCard(
            item = item,
            onClick = onClick,
            onActive = onActive,
            modifier = Modifier.align(Alignment.TopEnd),
            width = posterWidth,
            sharedKey = sharedKey,
        )
    }
}

/** Continue watching: 16:9 tile + progress bar + visible title & time left underneath. */
@Composable
fun ContinueCard(item: Item, onClick: () -> Unit, onActive: (Color) -> Unit, width: Dp = 200.dp) {
    val tokens = Cinetor.tokens
    val height = width * 9f / 16f
    val entries = LocalResumeEntries.current
    val progress = remember(item.id, entries) {
        progressByItem(entries)[item.id] ?: mockProgress(item.id)
    }
    Column(Modifier.width(width)) {
        CardFrame(item, onClick, onActive, Modifier.size(width, height)) {
            CinetorArtwork(
                seedKey = item.id,
                path = item.backdrop.ifBlank { item.poster },
                width = width,
                height = height,
                fallbackTitle = null,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                Modifier
                    .align(Alignment.Center)
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.58f))
                    .border(1.dp, Color.White.copy(alpha = 0.55f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.PlayArrow, contentDescription = null, tint = Color.White)
            }
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.5.dp).background(Color.White.copy(alpha = 0.28f))) {
                Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).height(3.5.dp).background(tokens.accent))
            }
        }
        Text(
            text = item.title,
            style = MaterialTheme.typography.labelMedium.copy(
                fontSize = 12.5.sp,
                lineHeight = 16.sp,
                fontWeight = FontWeight.Medium,
            ),
            color = tokens.textPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp, start = 2.dp, end = 2.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClick,
                )
                .clearAndSetSemantics {},
        )
        val total = (item.durationSec ?: 2700) / 60
        Text(
            text = "${(total * (1f - progress)).toInt().coerceAtLeast(1)} min left",
            style = MaterialTheme.typography.labelSmall,
            color = tokens.textSecondary,
            modifier = Modifier.padding(top = 2.dp, start = 2.dp),
        )
    }
}

/** Circle (artists) with visible title underneath. */
@Composable
fun CircleCard(item: Item, onClick: () -> Unit, onActive: (Color) -> Unit, size: Dp = 96.dp) {
    val tokens = Cinetor.tokens
    Column(Modifier.width(size + 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        CardFrame(item, onClick, onActive, Modifier.size(size), shape = CircleShape) {
            CinetorArtwork(
                seedKey = item.id,
                path = item.poster,
                width = size,
                height = size,
                fallbackTitle = null,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Text(
            text = item.artist ?: item.title,
            style = MaterialTheme.typography.labelMedium.copy(
                fontSize = 12.5.sp,
                lineHeight = 16.sp,
                fontWeight = FontWeight.Medium,
            ),
            color = tokens.textPrimary,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp).heightIn(min = 34.dp),
        )
    }
}

/** Channel / event tile: logo on brand colour + LIVE badge + channel number + visible title underneath. */
@Composable
fun ChannelCard(item: Item, onClick: () -> Unit, onActive: (Color) -> Unit, width: Dp = 168.dp) {
    val tokens = Cinetor.tokens
    val height = width * 9f / 16f
    val live = item.live || item.status == EventStatus.LIVE
    val dominant = remember(item.dominantColor) { hexColor(item.dominantColor) }
    Column(Modifier.width(width)) {
        CardFrame(item, onClick, onActive, Modifier.size(width, height)) {
            Box(
                Modifier.fillMaxSize().background(
                    Brush.linearGradient(listOf(dominant, Color.Black.copy(alpha = 0.9f))),
                ),
            )
            if (item.logo != null) {
                AsyncImage(
                    model = rememberSizedImage(item.logo, width, height),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().padding(18.dp),
                )
            } else {
                CinetorArtwork(
                    seedKey = item.id,
                    path = item.poster,
                    width = width,
                    height = height,
                    fallbackTitle = null,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Row(Modifier.fillMaxWidth().padding(6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                if (live) {
                    Text(
                        "LIVE",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(tokens.liveBadge)
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                } else {
                    Spacer(Modifier.size(1.dp))
                }
                item.channelNumber?.let {
                    Text(
                        it.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color.Black.copy(alpha = 0.55f))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
        }
        CardTitleBelow(title = item.title, onClick = onClick)
    }
}
