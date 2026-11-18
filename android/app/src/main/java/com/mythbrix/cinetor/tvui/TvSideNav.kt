package com.mythbrix.cinetor.tvui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.People
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.TheaterComedy
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.mythbrix.cinetor.core.perf.PerformanceTier
import com.mythbrix.cinetor.design.Cinetor
import com.mythbrix.cinetor.design.CinetorTokens
import com.mythbrix.cinetor.design.GlassLevel
import com.mythbrix.cinetor.design.glass

val TvNavCollapsed: Dp = 72.dp
val TvNavExpanded: Dp = 252.dp

private val TV_APERTURE_BLADES = listOf(
    "M42.50,62.99L19.72,76.14A40,40 0,0 1,42.50,10.71L42.50,37.01Z",
    "M42.50,37.01L42.50,10.71A40,40 0,0 1,87.78,63.15L65.00,50.00Z",
    "M65.00,50.00L87.78,63.15A40,40 0,0 1,19.72,76.14L42.50,62.99Z",
)
private const val TV_APERTURE_PLAY = "M65.00,50.00L42.50,62.99L42.50,37.01Z"
private val TV_APERTURE_COLORS = longArrayOf(0xFFE11D2E, 0xFFC8102E, 0xFFFF2D3F)
private const val TV_APERTURE_SEAM = 3f

/** Cinetor Beta 3-blade red Aperture iris with white play opening on a 100-unit grid. */
@Composable
fun TvApertureMark(size: Dp, modifier: Modifier = Modifier) {
    val blades = remember { TV_APERTURE_BLADES.map { PathParser().parsePathString(it).toPath() } }
    val play = remember { PathParser().parsePathString(TV_APERTURE_PLAY).toPath() }
    val bladeColors = remember { TV_APERTURE_COLORS.map { Color(it) } }
    val seamColor = Cinetor.tokens.bg
    Canvas(modifier.size(size)) {
        val k = this.size.minDimension / 100f
        scale(k, k, pivot = Offset.Zero) {
            blades.forEachIndexed { i, blade ->
                drawPath(blade, bladeColors[i])
                drawPath(blade, seamColor, style = Stroke(width = TV_APERTURE_SEAM, join = StrokeJoin.Round))
            }
            drawPath(play, Color.White)
        }
    }
}

/** Cinetor Beta header wordmark: `CINE` (`FontWeight.Light`) + `TOR` (`FontWeight.Bold`) tracked `0.34.em`. */
@Composable
fun TvCinetorWordmark(modifier: Modifier = Modifier) {
    Text(
        text = buildAnnotatedString {
            withStyle(SpanStyle(fontWeight = FontWeight.Light)) { append("CINE") }
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("TOR") }
        },
        modifier = modifier,
        color = Cinetor.tokens.textPrimary,
        fontSize = 16.sp,
        letterSpacing = 0.34.em,
        maxLines = 1,
    )
}

fun tvDestIcon(dest: TvDest): ImageVector = when (dest) {
    TvDest.SEARCH -> Icons.Rounded.Search
    TvDest.HOME -> Icons.Rounded.Home
    TvDest.ANIME -> Icons.Rounded.AutoAwesome
    TvDest.DRAMA -> Icons.Rounded.TheaterComedy
    TvDest.LIVE_TV -> Icons.Rounded.LiveTv
    TvDest.MUSIC -> Icons.Rounded.LibraryMusic
    TvDest.COMICS -> Icons.AutoMirrored.Rounded.MenuBook
    TvDest.ADULT -> Icons.Rounded.Lock
    TvDest.DOWNLOADS -> Icons.Rounded.Download
    TvDest.LIBRARY -> Icons.Rounded.VideoLibrary
    TvDest.PROFILES -> Icons.Rounded.People
    TvDest.SETTINGS -> Icons.Rounded.Settings
}

/** Group header label inside the expanded TV side-nav (`null` for top primary video + TV items). */
fun tvNavGroupLabel(group: TvNavGroup): String? = when (group) {
    TvNavGroup.VIDEO -> null
    TvNavGroup.PROFILE -> "SPACES"
    TvNavGroup.SYSTEM -> "MORE"
}

/**
 * Liquid Glass left side-nav dock:
 * - Collapsed to icons (`72.dp`); smoothly expands (`252.dp`) over content while focused.
 * - Renders Cinetor Beta's 3-blade red Aperture mark + `CINE`/`TOR` (`0.34.em`) wordmark.
 * - Houses primary items (`Search`, `Home`, `Anime`, `Drama`, `TV`), `Spaces`
 *   (`Music`, `Comics`, `18+` when enabled), and `More` (`Downloads`, `Library`, `Settings`).
 * - Uses `GlassLevel.SURFACE` + opaque-backed glass tint so it is 100% immune to Android 12–15
 *   `RenderNode` self-blur crashes inside `AppBackground(Modifier.haze)`.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun TvSideNav(
    entries: List<TvNavEntry>,
    selected: TvDest?,
    onPick: (TvDest) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = Cinetor.tokens
    val tier = Cinetor.tier
    val prefs = Cinetor.prefs
    var expanded by remember { mutableStateOf(false) }
    val animMs = if (prefs.reduceMotion) 0 else tier.scaled(CinetorTokens.Motion.mediumMs)
    val width by animateDpAsState(
        targetValue = if (expanded) TvNavExpanded else TvNavCollapsed,
        animationSpec = tween(animMs, easing = CinetorTokens.Motion.emphasized),
        label = "tvNavWidth",
    )
    val requesters = remember(entries) { entries.associate { it.dest to FocusRequester() } }
    val target = selected?.let { requesters[it] } ?: requesters.values.firstOrNull()
    val railShape = RoundedCornerShape(24.dp)

    Column(
        modifier
            .fillMaxHeight()
            .width(width)
            .shadow(
                elevation = if (expanded && tier != PerformanceTier.LOW) 22.dp else 0.dp,
                shape = railShape,
                clip = false,
                ambientColor = Color.Black,
                spotColor = tokens.accent.copy(alpha = 0.35f),
            )
            .clip(railShape)
            .background(tokens.surface.copy(alpha = if (expanded) 0.94f else 0.86f))
            .glass(railShape, GlassLevel.SURFACE)
            .onFocusChanged { expanded = it.hasFocus }
            .focusProperties { if (target != null) enter = { target } }
            .focusGroup()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 14.dp, horizontal = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 9.dp, end = 8.dp, bottom = 10.dp)
                .clearAndSetSemantics { contentDescription = "Cinetor" },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            TvApertureMark(size = 32.dp)
            androidx.compose.animation.AnimatedVisibility(
                visible = expanded,
                enter = fadeIn(tween(animMs)),
                exit = fadeOut(tween(animMs / 2)),
            ) {
                TvCinetorWordmark()
            }
        }

        var lastGroup: TvNavGroup? = null
        entries.forEach { e ->
            if (lastGroup != null && e.group != lastGroup) {
                Box(
                    Modifier
                        .padding(horizontal = 8.dp, vertical = 5.dp)
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(tokens.divider),
                )
                val header = tvNavGroupLabel(e.group)
                if (header != null) {
                    androidx.compose.animation.AnimatedVisibility(
                        visible = expanded,
                        enter = fadeIn(tween(animMs)),
                        exit = fadeOut(tween(animMs / 2)),
                    ) {
                        Text(
                            text = header,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                letterSpacing = 1.1.sp,
                            ),
                            color = tokens.textTertiary,
                            maxLines = 1,
                            modifier = Modifier.padding(start = 10.dp, bottom = 3.dp),
                        )
                    }
                }
            }
            lastGroup = e.group
            TvNavItem(
                entry = e,
                selected = e.dest == selected,
                expanded = expanded,
                onClick = { onPick(e.dest) },
                modifier = Modifier.focusRequester(requesters.getValue(e.dest)),
            )
        }
    }
}

@Composable
private fun TvNavItem(
    entry: TvNavEntry,
    selected: Boolean,
    expanded: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    val tokens = Cinetor.tokens
    val prefs = Cinetor.prefs
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val scale by animateFloatAsState(
        targetValue = if (focused && !prefs.reduceMotion) 1.03f else 1f,
        label = "tvNavItemScale",
    )
    val sound = rememberFocusSound()
    LaunchedEffect(focused) { if (focused) sound() }

    val shape = RoundedCornerShape(14.dp)
    val isLight = tokens.bg.luminance() > 0.5f
    val spotlightRing = if (isLight) Color(0xFF141416) else Color.White

    val bgModifier = when {
        focused -> Modifier.background(Brush.linearGradient(listOf(tokens.accentEnd, tokens.accent)))
        selected -> Modifier.background(
            Brush.linearGradient(
                listOf(tokens.accent.copy(alpha = 0.20f), tokens.glassFill),
            ),
        )
        else -> Modifier
    }
    val borderModifier = when {
        focused -> Modifier.border(2.dp, spotlightRing.copy(alpha = 0.92f), shape)
        selected -> Modifier.border(1.dp, tokens.accent.copy(alpha = 0.50f), shape)
        else -> Modifier
    }
    val iconTint = when {
        focused -> tokens.onAccent
        selected -> tokens.accent
        else -> tokens.textSecondary
    }
    val labelColor = when {
        focused -> tokens.onAccent
        selected -> tokens.textPrimary
        else -> tokens.textSecondary
    }

    Row(
        modifier
            .fillMaxWidth()
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(shape)
            .then(bgModifier)
            .then(borderModifier)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .semantics { contentDescription = entry.label }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(
            imageVector = tvDestIcon(entry.dest),
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(24.dp),
        )
        if (expanded) {
            Text(
                text = entry.label,
                color = labelColor,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = if (selected || focused) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (entry.dest == TvDest.ADULT) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(5.dp))
                        .background(if (focused) Color.Black.copy(alpha = 0.28f) else tokens.accent.copy(alpha = 0.16f))
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                ) {
                    Text(
                        "PIN",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, fontWeight = FontWeight.Bold),
                        color = if (focused) tokens.onAccent else tokens.accent,
                    )
                }
            }
        }
    }
}
