package com.mythbrix.cinetor.navigation

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mythbrix.cinetor.core.data.Space
import com.mythbrix.cinetor.design.Cinetor
import com.mythbrix.cinetor.design.GlassLevel
import com.mythbrix.cinetor.design.glass

val BottomBarHeight = 64.dp
private val BarShape = RoundedCornerShape(24.dp)

fun iconFor(key: TabKey, space: Space?): ImageVector = when (key) {
    TabKey.HOME -> Icons.Rounded.Home
    TabKey.ANIME -> Icons.Rounded.AutoAwesome
    TabKey.DRAMA -> Icons.Rounded.TheaterComedy
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

/** Picks bottom glass bar (phone) or glass rail (tablet landscape / TV placeholder). */
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
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .glass(BarShape, GlassLevel.BAR),
    ) {
        Row(
            Modifier.fillMaxWidth().height(BottomBarHeight).selectableGroup(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEach { tab ->
                NavItem(
                    tab = tab,
                    icon = iconFor(tab.key, activeSpace),
                    selected = tab.key == selected,
                    onClick = { onSelect(tab.key) },
                    modifier = Modifier.weight(1f),
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
                modifier = Modifier.fillMaxWidth(),
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
    val color by animateColorAsState(if (selected) tokens.accent else tokens.textSecondary, label = "navItem")
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Box(
            Modifier
                .clip(RoundedCornerShape(50))
                .background(if (selected) tokens.accent.copy(alpha = 0.18f) else Color.Transparent)
                .padding(horizontal = 14.dp, vertical = 4.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(24.dp))
        }
        Text(
            tab.label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
