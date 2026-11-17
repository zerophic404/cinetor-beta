package com.mythbrix.cinetor.feature.downloads

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mythbrix.cinetor.core.download.DownloadInfo
import com.mythbrix.cinetor.core.download.DownloadStatus
import com.mythbrix.cinetor.core.download.progress
import com.mythbrix.cinetor.design.Cinetor
import com.mythbrix.cinetor.design.CinetorTokens
import com.mythbrix.cinetor.design.GlassLevel
import com.mythbrix.cinetor.design.glass

/** Visual state of a Download button. */
enum class DownloadButtonState { NONE, QUEUED, WAITING, DOWNLOADING, PAUSED, COMPLETED, FAILED }

fun buttonStateOf(info: DownloadInfo?): DownloadButtonState = when (info?.record?.status) {
    null -> DownloadButtonState.NONE
    DownloadStatus.QUEUED -> DownloadButtonState.QUEUED
    DownloadStatus.WAITING -> DownloadButtonState.WAITING
    DownloadStatus.DOWNLOADING -> DownloadButtonState.DOWNLOADING
    DownloadStatus.PAUSED -> DownloadButtonState.PAUSED
    DownloadStatus.COMPLETED -> DownloadButtonState.COMPLETED
    DownloadStatus.FAILED -> DownloadButtonState.FAILED
}

private data class Look(val icon: ImageVector?, val label: String, val tint: Color)

@Composable
private fun lookOf(state: DownloadButtonState, info: DownloadInfo?): Look {
    val t = Cinetor.tokens
    val pct = ((info?.record?.progress ?: 0f) * 100).toInt()
    return when (state) {
        DownloadButtonState.NONE -> Look(Icons.Rounded.Download, "Download", t.textPrimary)
        DownloadButtonState.QUEUED -> Look(Icons.Rounded.Schedule, "Queued", t.textSecondary)
        DownloadButtonState.WAITING -> Look(Icons.Rounded.Schedule, info?.record?.waitingReason ?: "Waiting", t.warning)
        DownloadButtonState.DOWNLOADING -> Look(null, "$pct%", t.textPrimary)
        DownloadButtonState.PAUSED -> Look(Icons.Rounded.Pause, "Paused $pct%", t.textSecondary)
        DownloadButtonState.COMPLETED -> Look(Icons.Rounded.CheckCircle, "Downloaded", t.success)
        DownloadButtonState.FAILED -> Look(Icons.Rounded.ErrorOutline, "Retry", t.error)
    }
}

/** Full-size button next to Play on Details. NONE → options/batch sheet; anything else → details sheet. */
@Composable
fun DownloadButton(
    info: DownloadInfo?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    overrideLabel: String? = null,
) {
    val tokens = Cinetor.tokens
    val state = buttonStateOf(info)
    val look = lookOf(state, info)
    val shape = RoundedCornerShape(CinetorTokens.Shape.pill)
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Row(
        modifier
            .height(50.dp)
            .glass(shape, GlassLevel.SURFACE)
            .then(if (focused) Modifier.border(2.dp, tokens.focusRing, shape) else Modifier)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .semantics { contentDescription = "Download" }
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StateIcon(state, info, look, 20)
        Spacer(Modifier.width(6.dp))
        Text(
            overrideLabel ?: look.label,
            color = look.tint,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

/** Compact icon button for episode rows. */
@Composable
fun EpisodeDownloadButton(info: DownloadInfo?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val tokens = Cinetor.tokens
    val state = buttonStateOf(info)
    val look = lookOf(state, info)
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Box(
        modifier
            .size(40.dp)
            .clip(CircleShape)
            .then(if (focused) Modifier.border(2.dp, tokens.focusRing, CircleShape) else Modifier)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .semantics { contentDescription = "Download episode: ${look.label}" },
        contentAlignment = Alignment.Center,
    ) {
        StateIcon(state, info, look, 22)
    }
}

@Composable
private fun StateIcon(state: DownloadButtonState, info: DownloadInfo?, look: Look, sizeDp: Int) {
    val t = Cinetor.tokens
    if (state == DownloadButtonState.DOWNLOADING) {
        val p = info?.record?.progress ?: 0f
        Box(contentAlignment = Alignment.Center) {
            if (p <= 0f) {
                CircularProgressIndicator(Modifier.size(sizeDp.dp), color = t.accent, strokeWidth = 2.5.dp)
            } else {
                CircularProgressIndicator(
                    progress = { p },
                    modifier = Modifier.size(sizeDp.dp),
                    color = t.accent,
                    trackColor = t.divider,
                    strokeWidth = 2.5.dp,
                )
            }
        }
    } else if (look.icon != null) {
        Icon(look.icon, contentDescription = null, tint = look.tint, modifier = Modifier.size(sizeDp.dp))
    }
}

/** Small "Downloaded" pill for cards / rows. */
@Composable
fun DownloadedBadge(modifier: Modifier = Modifier) {
    val t = Cinetor.tokens
    Row(
        modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color.Black.copy(alpha = 0.6f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = t.success, modifier = Modifier.size(12.dp))
        Spacer(Modifier.width(3.dp))
        Text("Downloaded", color = t.textPrimary, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
    }
}
