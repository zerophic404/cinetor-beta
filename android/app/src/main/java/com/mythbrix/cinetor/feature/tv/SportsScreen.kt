package com.mythbrix.cinetor.feature.tv

import android.Manifest
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.GenericShape
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.mythbrix.cinetor.core.data.AppContainer
import com.mythbrix.cinetor.core.data.EventStatus
import com.mythbrix.cinetor.core.data.Item
import com.mythbrix.cinetor.core.data.Team
import com.mythbrix.cinetor.core.ui.rememberSizedImage
import com.mythbrix.cinetor.design.Cinetor
import com.mythbrix.cinetor.design.CinetorTokens
import com.mythbrix.cinetor.design.hexColor
import com.mythbrix.cinetor.feature.home.CategoryTabs
import com.mythbrix.cinetor.feature.home.TabSpec

private val SportsTabs = SportsTab.entries.map { TabSpec("sports-${it.name}", it.title, emptyList()) }

/** Sports: Live now / Upcoming / Results, scoreboard cards with generated crests, kick-off reminders. */
@Composable
fun SportsScreen(d: TvData, nowSec: Long, container: AppContainer, padding: PaddingValues) {
    var tabIndex by rememberSaveable { mutableIntStateOf(0) }
    val tab = SportsTab.entries[tabIndex]
    val reminders by container.tvPrefs.reminders.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val events = remember(d, tab) { eventsFor(tab, d.events) }

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

    LazyColumn(contentPadding = padding, verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxSize()) {
        item(key = "tabs", contentType = "tabs") { CategoryTabs(SportsTabs, tabIndex, { tabIndex = it }) }
        if (events.isEmpty()) {
            item(key = "empty", contentType = "empty") {
                Text("No events", color = Cinetor.tokens.textSecondary, modifier = Modifier.padding(24.dp))
            }
        }
        items(events, key = { it.id }, contentType = { "event" }) { ev ->
            EventCard(
                event = ev,
                nowSec = nowSec,
                anchorSec = d.anchorSec,
                reminded = ev.id in reminders,
                onWatch = { container.playback.playChannel(ev, d.liveEvents) },
                onRemind = { onRemind(ev) },
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
    }
}

@Composable
private fun EventCard(
    event: Item,
    nowSec: Long,
    anchorSec: Long,
    reminded: Boolean,
    onWatch: () -> Unit,
    onRemind: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = Cinetor.tokens
    val shape = RoundedCornerShape(CinetorTokens.Shape.card)
    val live = event.status == EventStatus.LIVE
    val dominant = remember(event.dominantColor) { hexColor(event.dominantColor) }
    Box(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(dominant)
            .border(1.dp, if (live) tokens.liveBadge.copy(alpha = 0.7f) else tokens.glassStroke, shape)
            .then(if (live) Modifier.clickable(onClick = onWatch) else Modifier)
            .semantics { contentDescription = "Event ${event.title}" },
    ) {
        AsyncImage(
            model = rememberSizedImage(event.backdrop, 360.dp, 180.dp),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.matchParentSize(),
        )
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.55f), Color.Black.copy(alpha = 0.9f)))))
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    event.league ?: event.genres.firstOrNull().orEmpty(),
                    color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                when (event.status) {
                    EventStatus.LIVE -> LiveBadgePill(text = "LIVE ${elapsedMinutes(event, nowSec, anchorSec)}'")
                    EventStatus.UPCOMING -> StatusPill("in ${formatCountdown(secondsUntil(event, nowSec, anchorSec))}")
                    else -> StatusPill("FT")
                }
            }
            Scoreboard(event)
            Row(verticalAlignment = Alignment.CenterVertically) {
                val tz = remember { localOffsetSec() }
                Text(
                    when (event.status) {
                        EventStatus.UPCOMING -> "Kick-off ${formatClock(anchorSec + (event.startOffsetSec ?: 0), tz)}"
                        EventStatus.LIVE -> "Started ${formatClock(anchorSec + (event.startOffsetSec ?: 0), tz)}"
                        else -> "Final · ${formatClock(anchorSec + (event.startOffsetSec ?: 0), tz)}"
                    },
                    color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.weight(1f),
                )
                when (event.status) {
                    EventStatus.LIVE -> ActionPill("Watch", Icons.Rounded.PlayArrow, tokens.accent, "Watch ${event.title}", onWatch)
                    EventStatus.UPCOMING -> ActionPill(
                        if (reminded) "Reminder on" else "Remind me",
                        if (reminded) Icons.Rounded.NotificationsActive else Icons.Rounded.NotificationsNone,
                        if (reminded) tokens.accent else Color.White.copy(alpha = 0.14f),
                        if (reminded) "Cancel reminder" else "Remind me",
                        onRemind,
                    )
                    else -> Unit
                }
            }
        }
    }
}

@Composable
private fun Scoreboard(event: Item) {
    val home = event.homeTeam
    val away = event.awayTeam
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TeamColumn(home, Modifier.weight(1f))
        val hs = home?.score
        val aws = away?.score
        val score = if (event.status == EventStatus.UPCOMING || hs == null || aws == null) "vs" else "$hs – $aws"
        Text(
            score, color = Color.White, fontWeight = FontWeight.Black,
            fontSize = if (score == "vs") 18.sp else 28.sp, textAlign = TextAlign.Center,
            modifier = Modifier.width(110.dp),
        )
        TeamColumn(away, Modifier.weight(1f))
    }
}

@Composable
private fun TeamColumn(team: Team?, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        TeamCrest(team, 52.dp)
        Text(
            team?.name ?: "TBD", color = Color.White, style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Shield silhouette for generated crests. */
private val ShieldShape = GenericShape { size, _ ->
    val w = size.width
    val h = size.height
    moveTo(0f, 0f)
    lineTo(w, 0f)
    lineTo(w, h * 0.55f)
    lineTo(w * 0.82f, h * 0.8f)
    lineTo(w * 0.5f, h)
    lineTo(w * 0.18f, h * 0.8f)
    lineTo(0f, h * 0.55f)
    close()
}

/** Generated team crest: shield in the team colour (diagonal sheen) + short code. */
@Composable
fun TeamCrest(team: Team?, size: Dp) {
    val color = remember(team?.color) { team?.color?.let { runCatching { hexColor(it) }.getOrNull() } ?: Color.DarkGray }
    Box(
        Modifier
            .size(size)
            .clip(ShieldShape)
            .background(Brush.linearGradient(listOf(color, color.copy(alpha = 0.55f), Color.Black.copy(alpha = 0.6f))))
            .border(2.dp, Color.White.copy(alpha = 0.5f), ShieldShape)
            .semantics { contentDescription = "Crest ${team?.short ?: ""}" },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            team?.short ?: "?", color = Color.White, fontWeight = FontWeight.Black,
            fontSize = (size.value * 0.24f).sp, modifier = Modifier.padding(bottom = size * 0.12f),
        )
    }
}

@Composable
private fun StatusPill(text: String) {
    Text(
        text, color = Color.White, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold,
        modifier = Modifier.clip(RoundedCornerShape(5.dp)).background(Color.White.copy(alpha = 0.16f)).padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

@Composable
private fun ActionPill(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    bg: Color,
    desc: String,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .clip(RoundedCornerShape(CinetorTokens.Shape.pill))
            .background(bg)
            .clickable(onClick = onClick)
            .semantics { contentDescription = desc }
            .padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
        Text(label, color = Color.White, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
    }
}
