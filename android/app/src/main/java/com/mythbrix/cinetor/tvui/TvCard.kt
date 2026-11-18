package com.mythbrix.cinetor.tvui

import android.view.SoundEffectConstants
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.DeviceFontFamilyName
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
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
import com.mythbrix.cinetor.core.player.Readiness
import com.mythbrix.cinetor.core.player.progressByItem
import com.mythbrix.cinetor.core.ui.LocalResumeEntries
import com.mythbrix.cinetor.core.ui.rememberSizedImage
import com.mythbrix.cinetor.design.Cinetor
import com.mythbrix.cinetor.design.hexColor
import java.util.Locale
import kotlin.math.absoluteValue
import kotlin.random.Random

/** Cinetor Beta 10-foot D-pad focus zoom. */
const val TV_FOCUS_SCALE = 1.06f

/** Green ready indicator dot color (`#22C55E`). */
val TvReadyGreen = Color(0xFF22C55E)

private val PROGRESS_SHOWN = 0.02f..0.95f

private val NON_GENRE_TAGS = setOf(
    "hindi", "[hindi]", "bengali", "[bengali]", "bangla", "[bangla]",
    "tamil", "[tamil]", "telugu", "[telugu]", "english", "[english]",
    "4k", "4k uhd", "uhd",
)

private val DUB_RULES: List<Pair<String, List<String>>> = listOf(
    "Hindi" to listOf("[hindi]", "(hindi)", "hindi dub", "hindi"),
    "Bangla" to listOf("[bengali]", "(bengali)", "[bangla]", "(bangla)", "bengali dub", "bangla dub", "bengali", "bangla"),
    "Tamil" to listOf("[tamil]", "(tamil)", "tamil dub", "tamil"),
    "Telugu" to listOf("[telugu]", "(telugu)", "telugu dub", "telugu"),
    "English" to listOf("[english]", "(english)", "english dub"),
)

/** Built-in condensed sans family for compact TV badges. */
val TvCondensedFont: FontFamily by lazy {
    FontFamily(
        Font(DeviceFontFamilyName("sans-serif-condensed"), FontWeight.Normal),
        Font(DeviceFontFamilyName("sans-serif-condensed"), FontWeight.Bold),
    )
}

private val TvArtPalettes by lazy {
    listOf(
        Color(0xFF7A0E1A) to Color(0xFF1A0609),
        Color(0xFF8F1422) to Color(0xFF200709),
        Color(0xFF4A0A12) to Color(0xFF12050A),
        Color(0xFFA51A28) to Color(0xFF2A0A0F),
        Color(0xFF3B3B40) to Color(0xFF111113),
        Color(0xFF55555C) to Color(0xFF17171A),
        Color(0xFF2A2A2F) to Color(0xFF0C0C0D),
        Color(0xFF5C0A14) to Color(0xFF16060A),
    )
}

/** Formats positive ratings as `"7.8"` in US English; hides unrated (`0.0` / negative / `NaN`). */
fun tvRatingBadge(rating: Double): String? =
    if (!rating.isNaN() && rating > 0.0) String.format(Locale.US, "%.1f", rating) else null

/** Extracts a crisp dub/language badge (`Hindi`, `Bangla`, `Tamil`, `Telugu`, `English`) when tagged. */
fun tvDubBadge(item: Item): String? {
    item.dubs.firstOrNull { d ->
        d.languageName.isNotBlank() && !d.languageName.equals("Original", ignoreCase = true)
    }?.let { return tvFullLanguageName(it.languageName) }
    item.sources.firstOrNull { s ->
        !s.audioLang.isNullOrBlank() && !s.audioLang.equals("Original", ignoreCase = true)
    }?.audioLang?.let { return tvFullLanguageName(it) }
    val textFields = listOfNotNull(item.title, item.altTitle).map { it.lowercase(Locale.US) }
    val genreTokens = item.genres.map { it.trim().lowercase(Locale.US) }
    val sourceLabels = item.sources.mapNotNull { it.label?.lowercase(Locale.US) }
    for ((badge, needles) in DUB_RULES) {
        val bracketed = needles.filter { it.startsWith("[") || it.startsWith("(") || it.endsWith("dub") }
        if (textFields.any { t -> bracketed.any { it in t } }) return badge
        if (genreTokens.any { g -> g in needles }) return badge
        if (sourceLabels.any { s -> needles.any { n -> n.length > 4 && n in s } }) return badge
    }
    return null
}

/** True when a Play button should show its `#22C55E` green ready dot (never any `"Ready"` text). */
fun tvShowReadyDot(readiness: Readiness?, hasDirectSources: Boolean): Boolean =
    readiness == Readiness.READY || (readiness == null && hasDirectSources)

/** True when a title advertises 2160p / 4K UHD sources or tags. */
fun tvIs4k(item: Item): Boolean {
    if (item.sources.any { s ->
            (s.height ?: 0) >= 2160 ||
                s.label?.let { l -> l.contains("4k", true) || l.contains("2160", true) || l.contains("uhd", true) } == true
        }
    ) return true
    if (item.seasons.any { se ->
            se.episodes.any { ep ->
                ep.sources.any { (it.height ?: 0) >= 2160 || it.label?.contains("4k", true) == true }
            }
        }
    ) return true
    if (item.genres.any { it.equals("4K", true) || it.equals("4K UHD", true) || it.equals("UHD", true) }) return true
    return item.title.contains("[4K]", true) ||
        item.title.contains("4K UHD", true) ||
        item.altTitle?.contains("4K", true) == true
}

/** Compact metadata subtitle (`"2024 · Action"`) under a TV card. */
fun tvCardSubtitle(item: Item): String {
    val yearPart = item.year.takeIf { it > 0 }?.toString()
    val genrePart = item.genres.firstOrNull { g -> g.trim().lowercase(Locale.US) !in NON_GENRE_TAGS }
    return listOfNotNull(yearPart, genrePart).joinToString(" · ")
}

/** Watch progress bar value (`0.02..0.95`), or `null` when untouched / already finished. */
fun tvWatchProgress(
    item: Item,
    explicitProgress: Float?,
    resumeProgress: Map<String, Float>,
): Float? {
    if (explicitProgress != null) {
        return explicitProgress.coerceIn(0f, 1f).takeIf { it in PROGRESS_SHOWN }
    }
    val supportsResume = item.type == ItemType.MOVIE || item.type == ItemType.SERIES || item.type == ItemType.VIDEO
    if (!supportsResume) return null
    return resumeProgress[item.id]?.takeIf { it in PROGRESS_SHOWN }
}

/** Generated crimson/graphite cinema fallback art — drawn only when remote/mock art is blank. */
@Composable
fun TvGeneratedArt(seedKey: String, modifier: Modifier = Modifier) {
    val seed = seedKey.hashCode()
    val (light, dark) = TvArtPalettes[seed.absoluteValue % TvArtPalettes.size]
    Canvas(modifier) {
        val random = Random(seed)
        drawRect(
            Brush.linearGradient(
                colors = listOf(light, dark),
                start = Offset(size.width * random.nextFloat(), 0f),
                end = Offset(size.width * random.nextFloat(), size.height),
            ),
        )
        repeat(2) {
            val center = Offset(size.width * random.nextFloat(), size.height * random.nextFloat() * 0.8f)
            val radius = size.maxDimension * (0.22f + random.nextFloat() * 0.30f)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color.White.copy(alpha = 0.12f), Color.Transparent),
                    center = center,
                    radius = radius,
                ),
                radius = radius,
                center = center,
                blendMode = BlendMode.Screen,
            )
        }
    }
}

/** Optional focus "tick" (Settings › TV › Focus sounds). */
@Composable
fun rememberFocusSound(): () -> Unit {
    val view = LocalView.current
    val enabled by rememberUpdatedState(Cinetor.prefs.focusSounds)
    return remember(view) { { if (enabled) view.playSoundEffect(SoundEffectConstants.CLICK) } }
}

/**
 * Focusable 10-foot card:
 * - ALWAYS renders the item title visibly underneath every thumbnail card (brightening to pure
 *   white with an accent focus glow when D-pad focused).
 * - D-pad focus = `1.06f` zoom + crisp spotlight focus border + item-accent glow.
 * - CinePulse `★` rating pill, dub badge (`Hindi`, `Bangla`, etc.), and watch-progress bar.
 */
@Composable
fun TvCard(
    item: Item,
    onClick: () -> Unit,
    onFocused: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = 150.dp,
    landscape: Boolean = false,
    progress: Float? = null,
    showSubtitle: Boolean = true,
) {
    val tokens = Cinetor.tokens
    val tier = Cinetor.tier
    val reduceMotion = Cinetor.prefs.reduceMotion
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val scale by animateFloatAsState(
        targetValue = when {
            !focused -> 1f
            reduceMotion -> 1.02f
            else -> TV_FOCUS_SCALE
        },
        label = "tvCardScale",
    )
    val glow by animateDpAsState(
        if (focused && tier != PerformanceTier.LOW) 20.dp else 0.dp,
        label = "tvCardGlow",
    )
    val accent = remember(item.accentColor) { hexColor(item.accentColor) }
    val dominant = remember(item.dominantColor) { hexColor(item.dominantColor) }
    val isLight = tokens.bg.luminance() > 0.5f
    val spotlight = if (isLight) Color(0xFF141416) else Color.White
    val sound = rememberFocusSound()
    val focusCb by rememberUpdatedState(onFocused)
    LaunchedEffect(focused) {
        if (focused) {
            sound()
            focusCb()
        }
    }
    val resumeEntries = LocalResumeEntries.current
    val effectiveProgress = remember(item.id, item.type, progress, resumeEntries) {
        tvWatchProgress(item, progress, progressByItem(resumeEntries))
    }
    val height = if (landscape) width * 9f / 16f else width * 1.5f
    val shape = RoundedCornerShape(14.dp)
    val imagePath = if (landscape) item.backdrop.ifBlank { item.poster } else item.poster.ifBlank { item.backdrop }
    val subtitle = remember(item.year, item.genres) { tvCardSubtitle(item) }

    val titleColor = when {
        focused && !isLight -> Color.White
        focused -> tokens.textPrimary
        else -> tokens.textPrimary.copy(alpha = 0.88f)
    }

    Column(modifier.width(width).zIndex(if (focused) 1f else 0f)) {
        TvPosterBox(
            item = item,
            imagePath = imagePath,
            width = width,
            height = height,
            landscape = landscape,
            focused = focused,
            scale = scale,
            glow = glow,
            dominant = dominant,
            accent = accent,
            spotlight = spotlight,
            progress = effectiveProgress,
            shape = shape,
            interaction = interaction,
            onClick = onClick,
        )
        Text(
            text = item.title,
            style = MaterialTheme.typography.labelLarge.copy(
                fontSize = 13.sp,
                lineHeight = 16.sp,
                shadow = if (focused && !isLight) {
                    Shadow(
                        color = tokens.accent.copy(alpha = 0.72f),
                        offset = Offset.Zero,
                        blurRadius = 10f,
                    )
                } else {
                    null
                },
            ),
            color = titleColor,
            fontWeight = if (focused) FontWeight.Bold else FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp, start = 2.dp, end = 2.dp),
        )
        if (showSubtitle && subtitle.isNotEmpty()) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                color = if (focused) tokens.textSecondary else tokens.textTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp, start = 2.dp, end = 2.dp),
            )
        }
    }
}

@Composable
private fun TvPosterBox(
    item: Item,
    imagePath: String,
    width: Dp,
    height: Dp,
    landscape: Boolean,
    focused: Boolean,
    scale: Float,
    glow: Dp,
    dominant: Color,
    accent: Color,
    spotlight: Color,
    progress: Float?,
    shape: RoundedCornerShape,
    interaction: MutableInteractionSource,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = Cinetor.tokens
    val isLive = item.live || item.status == EventStatus.LIVE
    val ratingText = remember(item.rating) { tvRatingBadge(item.rating) }
    val dubBadge = remember(item.title, item.altTitle, item.genres, item.sources) { tvDubBadge(item) }

    Box(
        modifier
            .size(width, height)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .shadow(glow, shape, clip = false, ambientColor = accent, spotColor = accent)
            .clip(shape)
            .background(dominant)
            .border(
                width = if (focused) 2.5.dp else 1.dp,
                color = if (focused) spotlight else tokens.glassStroke,
                shape = shape,
            )
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .semantics { contentDescription = item.title },
    ) {
        if (imagePath.isBlank()) {
            TvGeneratedArt(item.id, Modifier.fillMaxSize())
        } else {
            AsyncImage(
                model = rememberSizedImage(imagePath, width, height),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }

        if (isLive) {
            Box(
                Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(tokens.liveBadge)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Text(
                    "LIVE",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold),
                    color = Color.White,
                )
            }
        } else if (ratingText != null) {
            Row(
                Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xCC09090B))
                    .border(0.5.dp, Color.White.copy(alpha = 0.16f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    "★",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold),
                    color = Color(0xFFFFB800),
                )
                Text(
                    ratingText,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold),
                    color = Color.White,
                )
            }
        }

        val channelNo = item.channelNumber
        if (channelNo != null) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(Color(0xBF09090B))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Text(
                    channelNo.toString(),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.SemiBold),
                    color = Color.White,
                )
            }
        } else if (dubBadge != null) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(Color(0xF2FFFFFF))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Text(
                    dubBadge,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.5.sp, fontWeight = FontWeight.Bold),
                    color = Color(0xFF09090B),
                )
            }
        } else if (!landscape && item.type == ItemType.SERIES) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(Color(0xBF09090B))
                    .border(0.5.dp, Color.White.copy(alpha = 0.24f), RoundedCornerShape(5.dp))
                    .padding(horizontal = 5.dp, vertical = 1.5.dp),
            ) {
                Text(
                    "SERIES",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = TvCondensedFont,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp,
                    ),
                    color = Color.White,
                )
            }
        }

        if (focused) {
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        listOf(
                            Color.White.copy(alpha = 0.14f),
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.30f),
                        ),
                    ),
                ),
            )
        }

        if (progress != null) {
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .height(3.5.dp)
                    .background(Color.Black.copy(alpha = 0.55f)),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(progress.coerceIn(0f, 1f))
                        .height(3.5.dp)
                        .background(tokens.accent),
                )
            }
        }
    }
}

/**
 * Focusable cinema pill button (Hero / Details / Player / Retry):
 * - `primary = true`: Cinema Red gradient fill + crisp White Spotlight focus ring (`2.5.dp`).
 * - `readyDot = true`: renders ONLY a crisp `8.dp` `#22C55E` green circle (never `"Ready"` text).
 */
@Composable
fun TvButton(
    label: String,
    icon: ImageVector?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    onDarkScrim: Boolean = true,
    readyDot: Boolean = false,
) {
    val tokens = Cinetor.tokens
    val tier = Cinetor.tier
    val reduceMotion = Cinetor.prefs.reduceMotion
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val scale by animateFloatAsState(
        when {
            !focused -> 1f
            reduceMotion -> 1.02f
            else -> TV_FOCUS_SCALE
        },
        label = "tvBtnScale",
    )
    val sound = rememberFocusSound()
    LaunchedEffect(focused) { if (focused) sound() }

    val shape = RoundedCornerShape(50)
    val isLightSurface = !onDarkScrim && tokens.bg.luminance() > 0.5f
    val spotlightRing = if (isLightSurface) Color(0xFF141416) else Color.White
    val contentColor = when {
        primary -> tokens.onAccent
        isLightSurface -> tokens.textPrimary
        else -> Color.White
    }
    val borderWidth = if (focused) 2.5.dp else 1.dp
    val borderColor = when {
        focused -> spotlightRing
        primary -> Color.White.copy(alpha = 0.26f)
        isLightSurface -> tokens.glassStroke
        else -> Color.White.copy(alpha = 0.24f)
    }
    val glowRadius = if (focused && tier != PerformanceTier.LOW) 16.dp else 0.dp
    val glowColor = if (primary) tokens.accent else spotlightRing.copy(alpha = 0.45f)

    val fillModifier = when {
        primary -> Modifier.background(
            Brush.linearGradient(
                listOf(
                    if (focused) tokens.accent else tokens.accentEnd,
                    tokens.accent,
                ),
            ),
        )
        isLightSurface -> Modifier.background(if (focused) tokens.surfaceElevated else tokens.surface)
        else -> Modifier
            .background(Color(if (focused) 0xD916161C else 0xAD0B0B0E))
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color.White.copy(alpha = if (focused) 0.20f else 0.12f),
                        Color.White.copy(alpha = 0.03f),
                    ),
                ),
            )
    }

    Row(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .shadow(glowRadius, shape, clip = false, ambientColor = glowColor, spotColor = glowColor)
            .clip(shape)
            .then(fillModifier)
            .border(borderWidth, borderColor, shape)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .semantics { contentDescription = label }
            .padding(horizontal = 20.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(20.dp))
        }
        Text(
            text = label,
            color = contentColor,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
        if (readyDot) {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(TvReadyGreen)
                    .border(1.dp, Color.White.copy(alpha = 0.65f), CircleShape),
            )
        }
    }
}
