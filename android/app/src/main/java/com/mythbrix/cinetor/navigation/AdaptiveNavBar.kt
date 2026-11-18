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
import com.mythbrix.cinetor.design.AppTheme
import com.mythbrix.cinetor.design.Cinetor
import com.mythbrix.cinetor.design.CinetorTokens
import com.mythbrix.cinetor.design.GlassLevel
import com.mythbrix.cinetor.design.glass

val BottomBarHeight = 64.dp
private val CapsuleHeight = 60.dp
private val CapsuleVerticalGap = 10.dp
private val StandardDockShape = RoundedCornerShape(24.dp)
private val MaterialDockShape = RoundedCornerShape(28.dp)
private val AuraCapsuleShape = RoundedCornerShape(percent = 50)

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

/** Picks floating Liquid Glass dock / Aura capsule (phone) or Liquid Glass rail (tablet / TV). */
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
 * Floating Liquid Glass phone dock, morphing into Cinetor Beta's sleek floating pill capsule
 * when `Cinetor.theme == AppTheme.AURA`. Keeps vertical center aligned with `y ≈ 89% H` and full
 * 5-slot width coverage so CI `emulator-smoke.sh` coordinate and semantic taps hit dead-center.
 */
@Composable
private fun GlassBottomBar(
    tabs: List<TabSpec>,
    selected: TabKey?,
    activeSpace: Space?,
    onSelect: (TabKey) -> Unit,
    modifier: Modifier,
) {
    val theme = Cinetor.theme
    val isAura = theme == AppTheme.AURA
    val dockShape = when (theme) {
        AppTheme.AURA -> AuraCapsuleShape
        AppTheme.MATERIAL -> MaterialDockShape
        else -> StandardDockShape
    }
    val horizontalInset = if (isAura) 18.dp else 12.dp
    val verticalInset = if (isAura) CapsuleVerticalGap else 8.dp
    val barHeight = if (isAura) CapsuleHeight else BottomBarHeight

    Box(
        modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = horizontalInset, vertical = verticalInset)
            .glass(dockShape, GlassLevel.BAR),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(barHeight)
                .padding(horizontal = if (isAura) 6.dp else 2.dp)
                .selectableGroup(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEach { tab ->
                NavItem(
                    tab = tab,
                    icon = iconFor(tab.key, activeSpace),
                    selected = tab.key == selected,
                    capsuleMode = isAura,
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
    val isAura = Cinetor.theme == AppTheme.AURA
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
                capsuleMode = isAura,
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
    capsuleMode: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    val tokens = Cinetor.tokens
    val tier = Cinetor.tier
    val specular = Cinetor.specularShine
    val reduceMotion = Cinetor.prefs.reduceMotion

    val targetIconColor = when {
        !selected -> tokens.textSecondary
        capsuleMode -> tokens.textPrimary
        else -> tokens.accent
    }
    val targetLabelColor = if (selected) tokens.textPrimary else tokens.textSecondary
    val animSpec = if (reduceMotion || tier == PerformanceTier.LOW) {
        snap<Color>()
    } else {
        tween<Color>(durationMillis = CinetorTokens.Motion.shortMs)
    }
    val iconColor by animateColorAsState(targetIconColor, animationSpec = animSpec, label = "navIcon")
    val labelColor by animateColorAsState(targetLabelColor, animationSpec = animSpec, label = "navLabel")

    val spotlightColor = if (capsuleMode || !tokens.isLight) tokens.focusRing else tokens.accent
    val pillShape = RoundedCornerShape(percent = 50)
    val pillFill = when {
        !selected -> Color.Transparent
        capsuleMode -> tokens.textPrimary.copy(alpha = 0.14f)
        tokens.isLight -> tokens.accent.copy(alpha = 0.14f)
        else -> tokens.accent.copy(alpha = 0.18f)
    }
    val pillStroke = when {
        !selected || !specular -> Color.Transparent
        capsuleMode -> tokens.glassStroke
        else -> tokens.accent.copy(alpha = 0.28f)
    }

    Column(
        modifier
            .clip(if (capsuleMode) pillShape else RoundedCornerShape(16.dp))
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
                .padding(horizontal = if (capsuleMode) 12.dp else 14.dp, vertical = 3.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconColor,
                modifier = Modifier.size(if (capsuleMode) 22.dp else 23.dp),
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
