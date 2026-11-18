package com.mythbrix.cinetor.navigation

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.TheaterComedy
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mythbrix.cinetor.core.data.Space
import com.mythbrix.cinetor.core.perf.PerformanceTier
import com.mythbrix.cinetor.design.Cinetor
import com.mythbrix.cinetor.design.CinetorTokens
import com.mythbrix.cinetor.design.GlassLevel
import com.mythbrix.cinetor.design.glass
import com.mythbrix.cinetor.design.liquidGlassDock

val BottomBarHeight = 64.dp
private val CapsuleHeight = 60.dp
private val CapsuleVerticalGap = 10.dp
private val DockShape = RoundedCornerShape(percent = 50)

fun iconFor(key: TabKey, space: Space?): ImageVector = when (key) {
    TabKey.HOME -> Icons.Rounded.Home
    TabKey.ANIME -> Icons.Rounded.AutoAwesome
    TabKey.DRAMA -> Icons.Rounded.TheaterComedy
    TabKey.TV -> Icons.Rounded.LiveTv
    TabKey.SEARCH, TabKey.SPACE_SEARCH -> Icons.Rounded.Search
    TabKey.ME -> Icons.Rounded.Person
    TabKey.SPACES -> Icons.Rounded.Apps
    TabKey.EXIT_SPACE -> Icons.Rounded.Movie
    TabKey.SPACE_BROWSE -> spaceIcon(space)
}

fun spaceIcon(space: Space?): ImageVector = when (space) {
    Space.TV -> Icons.Rounded.LiveTv
    Space.MUSIC -> Icons.Rounded.LibraryMusic
    Space.COMICS -> Icons.AutoMirrored.Rounded.MenuBook
    Space.ADULT -> Icons.Rounded.Lock
    Space.ANIME -> Icons.Rounded.AutoAwesome
    Space.DRAMA -> Icons.Rounded.TheaterComedy
    else -> Icons.Rounded.Home
}

/** Picks the floating Liquid Glass capsule dock (phone) or the Liquid Glass rail (tablet / TV). */
@Composable
fun AdaptiveNavBar(
    layout: NavLayout,
    tabs: List<TabSpec>,
    selected: TabKey?,
    activeSpace: Space?,
    onSelect: (TabKey) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (layout) {
        NavLayout.BOTTOM_BAR -> GlassBottomBar(tabs, selected, activeSpace, onSelect, modifier)
        NavLayout.RAIL -> GlassRail(tabs, selected, activeSpace, onSelect, width = 88, modifier = modifier)
        NavLayout.TV_RAIL -> GlassRail(tabs, selected, activeSpace, onSelect, width = 128, modifier = modifier)
    }
}

/**
 * Floating Liquid Glass capsule dock (every theme): see-through glass with a lens highlight and rim
 * ([liquidGlassDock]), the selected tab in an accent pill. Its vertical centre stays near `89% H`
 * with full 5-slot width so CI `emulator-smoke.sh` coordinate and semantic taps hit dead-centre.
 */
@Composable
private fun GlassBottomBar(
    tabs: List<TabSpec>,
    selected: TabKey?,
    activeSpace: Space?,
    onSelect: (TabKey) -> Unit,
    modifier: Modifier,
) {
    Box(
        modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = CapsuleVerticalGap)
            .liquidGlassDock(DockShape),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(CapsuleHeight)
                .padding(horizontal = 6.dp)
                .selectableGroup(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEach { tab ->
                NavItem(
                    tab = tab,
                    icon = iconFor(tab.key, activeSpace),
                    selected = tab.key == selected,
                    onClick = { onSelect(tab.key) },
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
            }
        }
    }
}

@Composable
private fun GlassRail(
    tabs: List<TabSpec>,
    selected: TabKey?,
    activeSpace: Space?,
    onSelect: (TabKey) -> Unit,
    width: Int,
    modifier: Modifier,
) {
    Column(
        modifier
            .fillMaxHeight()
            .width(width.dp)
            .glass(RectangleShape, GlassLevel.BAR)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(vertical = 72.dp, horizontal = 8.dp)
            .selectableGroup(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        tabs.forEach { tab ->
            NavItem(
                tab = tab,
                icon = iconFor(tab.key, activeSpace),
                selected = tab.key == selected,
                onClick = { onSelect(tab.key) },
                modifier = Modifier.fillMaxWidth().height(60.dp),
            )
        }
        Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun NavItem(
    tab: TabSpec,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    val tokens = Cinetor.tokens
    val tier = Cinetor.tier
    val specular = Cinetor.specularShine
    val reduceMotion = Cinetor.prefs.reduceMotion

    val targetIconColor = if (selected) tokens.accent else tokens.textSecondary
    val targetLabelColor = if (selected) tokens.textPrimary else tokens.textSecondary
    val animSpec = if (reduceMotion || tier == PerformanceTier.LOW) {
        snap<Color>()
    } else {
        tween<Color>(durationMillis = CinetorTokens.Motion.shortMs)
    }
    val iconColor by animateColorAsState(targetIconColor, animationSpec = animSpec, label = "navIcon")
    val labelColor by animateColorAsState(targetLabelColor, animationSpec = animSpec, label = "navLabel")

    val spotlightColor = tokens.accent
    val pillShape = RoundedCornerShape(percent = 50)
    val pillFill = if (selected) tokens.accent.copy(alpha = if (tokens.isLight) 0.14f else 0.20f) else Color.Transparent
    val pillStroke = if (selected && specular) tokens.accent.copy(alpha = 0.34f) else Color.Transparent

    Column(
        modifier
            .clip(pillShape)
            .navSpotlight(
                active = selected,
                color = spotlightColor,
                castGlow = specular && tier != PerformanceTier.LOW,
            )
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .semantics { contentDescription = tab.label }
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier
                .clip(pillShape)
                .background(pillFill)
                .then(
                    if (pillStroke != Color.Transparent) {
                        Modifier.border(CinetorTokens.Shape.strokeHairline, pillStroke, pillShape)
                    } else {
                        Modifier
                    },
                )
                .padding(horizontal = 13.dp, vertical = 3.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconColor,
                modifier = Modifier.size(22.dp),
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(
            text = tab.label,
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            ),
            color = labelColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Cinetor Beta signature top spotlight bar with optional soft light cone falling onto the active tab. */
private fun Modifier.navSpotlight(
    active: Boolean,
    color: Color,
    castGlow: Boolean,
): Modifier = if (!active) {
    this
} else {
    drawWithCache {
        val barW = 20.dp.toPx()
        val barH = 2.dp.toPx()
        val glowH = size.height * 0.68f
        val glowBrush = if (castGlow) {
            Brush.radialGradient(
                colors = listOf(color.copy(alpha = 0.18f), Color.Transparent),
                center = Offset(size.width / 2f, 0f),
                radius = (size.width * 0.55f).coerceAtLeast(1f),
            )
        } else {
            null
        }
        onDrawBehind {
            if (glowBrush != null) {
                drawRect(brush = glowBrush, size = Size(size.width, glowH))
            }
            drawRoundRect(
                color = color,
                topLeft = Offset((size.width - barW) / 2f, 0f),
                size = Size(barW, barH),
                cornerRadius = CornerRadius(barH),
            )
        }
    }
}
