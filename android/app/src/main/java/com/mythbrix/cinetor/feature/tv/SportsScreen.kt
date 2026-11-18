package com.mythbrix.cinetor.feature.tv

import android.Manifest
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.NotificationsNone
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mythbrix.cinetor.core.data.AppContainer
import com.mythbrix.cinetor.core.data.EventStatus
import com.mythbrix.cinetor.core.data.Item
import com.mythbrix.cinetor.core.data.Team
import com.mythbrix.cinetor.design.Cinetor
import com.mythbrix.cinetor.design.CinetorTokens
import com.mythbrix.cinetor.design.hexColor

private val CarbonCardTop = Color(0xFF151821)
private val CarbonCardBottom = Color(0xFF0D0F14)

/**
 * PlayzTV-styled Broadcast Sports hub:
 * - Sport category chips (`All · Cricket · Football · Basketball · Combat · Tennis · Motorsport`).
 * - Status tabs (`Live Now` / `Upcoming` / `Results`, preserving `Tab Upcoming` for CI smoke).
 * - Dark carbon match cards with League header + pulsing `● LIVE` pill, circular Team A vs Team B faceoff
 *   (with tournament banner fallback for non-VS events), channel badge, and `Watch Live` / `Remind Me` CTA.
 */
@Composable
fun SportsScreen(d: TvData, nowSec: Long, container: AppContainer, padding: PaddingValues) {
    var sportName by rememberSaveable { mutableStateOf(SportFilter.ALL.name) }
    var tabIndex by rememberSaveable { mutableIntStateOf(0) }

    val selectedSport = runCatching { SportFilter.valueOf(sportName) }.getOrDefault(SportFilter.ALL)
    val tab = SportsTab.entries[tabIndex.coerceIn(0, SportsTab.entries.lastIndex)]
    val reminders by container.tvPrefs.reminders.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val events = remember(d, tab, selectedSport) { filterSportsEvents(d.events, tab, selectedSport) }
    val liveCount = remember(d, selectedSport) { filterSportsEvents(d.events, SportsTab.LIVE, selectedSport).size }

    val toggle: (Item) -> Unit = { ev ->
        val on = ev.id !in reminders
        container.tvPrefs.setReminder(ev.id, on)
        if (on) {
            SportsReminders.schedule(context, ev, reminderAtMs(ev, nowSec, d.anchorSec, System.currentTimeMillis()))
            Toast.makeText(context, "Reminder set · 5 min before kick-off", Toast.LENGTH_SHORT).show()
        } else {
            SportsReminders.cancel(context, ev)
        }
    }
    var pending by remember { mutableStateOf<Item?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
        pending?.let(toggle)
        pending = null
    }
    val onRemind: (Item) -> Unit = { ev ->
        if (ev.id !in reminders && Build.VERSION.SDK_INT >= 33 && !SportsReminders.canNotify(context)) {
            pending = ev
            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            toggle(ev)
        }
    }

    LazyColumn(
        contentPadding = padding,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(key = "sport-chips", contentType = "chips") {
            SportFilterChipsRow(
                selected = selectedSport,
                onSelect = { sportName = it.name },
            )
        }
        item(key = "status-tabs", contentType = "tabs") {
            SportsStatusTabsRow(
                selectedIndex = tabIndex,
                liveCount = liveCount,
                onSelect = { tabIndex = it },
            )
        }
        if (events.isEmpty()) {
            item(key = "empty", contentType = "empty") {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 20.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Cinetor.tokens.surfaceElevated)
                        .border(1.dp, Cinetor.tokens.glassStroke, RoundedCornerShape(16.dp))
                        .padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (selectedSport == SportFilter.ALL) {
                            "No ${tab.title.lowercase()} matches scheduled right now."
                        } else {
                            "No ${tab.title.lowercase()} ${selectedSport.label} matches right now."
                        },
                        color = Cinetor.tokens.textSecondary,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
        items(events, key = { it.id }, contentType = { "event" }) { ev ->
            PlayzMatchCard(
                event = ev,
                nowSec = nowSec,
                anchorSec = d.anchorSec,
                reminded = ev.id in reminders,
                onWatch = { container.playback.playChannel(ev, d.liveEvents.ifEmpty { listOf(ev) }) },
                onRemind = { onRemind(ev) },
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
    }
}

@Composable
private fun SportFilterChipsRow(
    selected: SportFilter,
    onSelect: (SportFilter) -> Unit,
) {
    val tokens = Cinetor.tokens
    val shape = RoundedCornerShape(CinetorTokens.Shape.pill)
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(SportFilter.entries, key = { it.name }) { filter ->
            val active = filter == selected
            Text(
                filter.label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                color = if (active) tokens.onAccent else tokens.textSecondary,
                modifier = Modifier
                    .clip(shape)
                    .background(if (active) tokens.accent else tokens.glassFill)
                    .border(1.dp, if (active) Color.Transparent else tokens.glassStroke, shape)
                    .clickable(role = Role.Tab) { onSelect(filter) }
                    .semantics {
                        this.selected = active
                        contentDescription = "Sport ${filter.label}"
                    }
                    .padding(horizontal = 15.dp, vertical = 7.dp),
            )
        }
    }
}

@Composable
private fun SportsStatusTabsRow(
    selectedIndex: Int,
    liveCount: Int,
    onSelect: (Int) -> Unit,
) {
    val tokens = Cinetor.tokens
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SportsTab.entries.forEachIndexed { index, tab ->
            val active = index == selectedIndex
            val displayLabel = when (tab) {
                SportsTab.LIVE -> if (liveCount > 0) "Live Now ($liveCount)" else "Live Now"
                SportsTab.UPCOMING -> "Upcoming"
                SportsTab.RESULTS -> "Results"
            }
            // Preserve exact `"Tab ${tab.title}"` (`"Tab Live now"`, `"Tab Upcoming"`, `"Tab Results"`) for CI smoke!
            Row(
                Modifier
                    .clip(CircleShape)
                    .background(if (active) tokens.accent.copy(alpha = 0.2f) else tokens.surfaceElevated)
                    .border(1.dp, if (active) tokens.accent else tokens.glassStroke, CircleShape)
                    .clickable(role = Role.Tab) { onSelect(index) }
                    .semantics {
                        selected = active
                        contentDescription = "Tab ${tab.title}"
                    }
                    .padding(horizontal = 14.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (tab == SportsTab.LIVE) {
                    Box(
                        Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(if (liveCount > 0) tokens.liveBadge else tokens.textTertiary),
                    )
                }
                Text(
                    displayLabel,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                    color = if (active) tokens.textPrimary else tokens.textSecondary,
                )
            }
        }
    }
}

/**
 * Minimal match card (~110 dp, three or more per phone screen): league + status chip + one action
 * icon on top, then crest · name · score/VS · name · crest on a single line. Live cards play on tap.
 */
@Composable
private fun PlayzMatchCard(
    event: Item,
    nowSec: Long,
    anchorSec: Long,
    reminded: Boolean,
    onWatch: () -> Unit,
    onRemind: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = Cinetor.tokens
    val shape = RoundedCornerShape(16.dp)
    val live = event.status == EventStatus.LIVE
    val matchup = remember(event.id, event.title, event.homeTeam, event.awayTeam) { parseMatchup(event) }
    val tz = remember { localOffsetSec() }
    val clockText = formatClock(anchorSec + (event.startOffsetSec ?: 0), tz)

    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Brush.verticalGradient(listOf(CarbonCardTop, CarbonCardBottom)))
            .border(1.dp, if (live) tokens.liveBadge.copy(alpha = 0.55f) else Color.White.copy(alpha = 0.08f), shape)
            .then(if (live) Modifier.clickable(onClick = onWatch) else Modifier)
            .semantics { contentDescription = "Event ${event.title}" }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                (event.league ?: event.genres.firstOrNull() ?: "Live Sports").ifBlank { "Live Sports" },
                color = Color.White.copy(alpha = 0.66f),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            when (event.status) {
                EventStatus.LIVE -> LiveBadgePill(text = "LIVE ${elapsedMinutes(event, nowSec, anchorSec)}'", pulse = true)
                EventStatus.UPCOMING -> StatusPill(
                    secondsUntil(event, nowSec, anchorSec).let { if (it in 1..3_600) "in ${formatCountdown(it)}" else clockText },
                )
                else -> StatusPill("FT")
            }
            when (event.status) {
                EventStatus.LIVE -> CardIcon(Icons.Rounded.PlayArrow, "Watch ${event.title}", tokens.accent, onWatch)
                EventStatus.UPCOMING -> CardIcon(
                    if (reminded) Icons.Rounded.NotificationsActive else Icons.Rounded.NotificationsNone,
                    if (reminded) "Cancel reminder" else "Remind me",
                    if (reminded) tokens.accent else Color.White.copy(alpha = 0.10f),
                    onRemind,
                )
                else -> Unit
            }
        }
        if (matchup != null) {
            CompactFaceoff(matchup, event.status)
        } else {
            Text(
                event.title,
                color = Color.White,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun CardIcon(icon: ImageVector, desc: String, bg: Color, onClick: () -> Unit) {
    Box(
        Modifier
            .padding(start = 8.dp)
            .size(30.dp)
            .clip(CircleShape)
            .background(bg)
            .clickable(onClick = onClick)
            .semantics { contentDescription = desc },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(17.dp))
    }
}

@Composable
private fun CompactFaceoff(matchup: ParsedMatchup, status: EventStatus?) {
    val home = matchup.home
    val away = matchup.away
    val hasScores = status != EventStatus.UPCOMING && home.score != null && away.score != null
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TeamCrest(team = home, size = 32.dp)
        Text(
            home.name,
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
        )
        Text(
            if (hasScores) "${home.score} – ${away.score}" else "VS",
            color = if (status == EventStatus.LIVE && hasScores) Cinetor.tokens.liveBadge else Color.White,
            fontWeight = FontWeight.Black,
            fontSize = if (hasScores) 18.sp else 13.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(min = 48.dp),
        )
        Text(
            away.name,
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
        )
        TeamCrest(team = away, size = 32.dp)
    }
}

/** PlayzTV circular team crest/monogram with metallic team-color sheen. */
@Composable
fun TeamCrest(team: Team?, size: Dp) {
    val color = remember(team?.color) {
        team?.color?.let { runCatching { hexColor(it) }.getOrNull() } ?: Color(0xFF2B303C)
    }
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(
                Brush.linearGradient(
                    listOf(
                        color,
                        color.copy(alpha = 0.62f),
                        Color(0xFF0A0C10),
                    ),
                ),
            )
            .border(1.5.dp, Color.White.copy(alpha = 0.45f), CircleShape)
            .semantics { contentDescription = "Crest ${team?.short ?: ""}" },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            team?.short ?: "?",
            color = Color.White,
            fontWeight = FontWeight.Black,
            fontSize = (size.value * 0.27f).sp,
        )
    }
}

@Composable
private fun StatusPill(text: String) {
    Text(
        text,
        color = Color.White.copy(alpha = 0.9f),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.12f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

