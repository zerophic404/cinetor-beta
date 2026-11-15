package com.mythbrix.cinetor.tvui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mythbrix.cinetor.design.Cinetor
import com.mythbrix.cinetor.design.CinetorMark
import com.mythbrix.cinetor.design.GlassLevel
import com.mythbrix.cinetor.design.glass

val TvNavCollapsed: Dp = 72.dp
val TvNavExpanded: Dp = 232.dp

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

/**
 * Glass left side nav: collapsed to icons; expands (labels + group headers) while focus is inside.
 * Entering from the content lands on the selected destination (not the top item).
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun TvSideNav(
    entries: List<TvNavEntry>,
    selected: TvDest?,
    onPick: (TvDest) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val width by animateDpAsState(if (expanded) TvNavExpanded else TvNavCollapsed, label = "tvNavWidth")
    val requesters = remember(entries) { entries.associate { it.dest to FocusRequester() } }
    val target = selected?.let { requesters[it] } ?: requesters.values.firstOrNull()
    Column(
        modifier
            .fillMaxHeight()
            .width(width)
            .glass(RoundedCornerShape(24.dp), GlassLevel.BAR)
            .onFocusChanged { expanded = it.hasFocus }
            .focusProperties { if (target != null) enter = { target } }
            .focusGroup()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 16.dp, horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(Modifier.padding(start = 6.dp, bottom = 12.dp)) { CinetorMark(Modifier.size(36.dp)) }
        var lastGroup: TvNavGroup? = null
        entries.forEach { e ->
            if (lastGroup != null && e.group != lastGroup) {
                Spacer(Modifier.height(10.dp))
                AnimatedVisibility(expanded, enter = fadeIn(), exit = fadeOut()) {
                    Text(
                        when (e.group) {
                            TvNavGroup.SPACES -> "SPACES"
                            TvNavGroup.SYSTEM -> "MORE"
                            TvNavGroup.VIDEO -> "VIDEO"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = Cinetor.tokens.textSecondary,
                        modifier = Modifier.padding(start = 10.dp, bottom = 4.dp),
                    )
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
private fun TvNavItem(entry: TvNavEntry, selected: Boolean, expanded: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val tokens = Cinetor.tokens
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val sound = rememberFocusSound()
    LaunchedEffect(focused) { if (focused) sound() }
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(
                when {
                    focused -> tokens.accent
                    selected -> tokens.accent.copy(alpha = 0.22f)
                    else -> Color.Transparent
                },
            )
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .semantics { contentDescription = entry.label }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(
            tvDestIcon(entry.dest), contentDescription = null,
            tint = if (focused || selected) Color.White else tokens.textSecondary,
            modifier = Modifier.size(24.dp),
        )
        if (expanded) {
            Text(
                entry.label,
                color = if (focused || selected) Color.White else tokens.textSecondary,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1,
            )
        }
    }
}
