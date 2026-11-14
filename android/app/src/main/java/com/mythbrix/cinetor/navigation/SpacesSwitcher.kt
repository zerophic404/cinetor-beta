package com.mythbrix.cinetor.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.mythbrix.cinetor.core.data.Space
import com.mythbrix.cinetor.design.Cinetor
import com.mythbrix.cinetor.design.GlassLevel
import com.mythbrix.cinetor.design.glass

private fun subtitleOf(space: Space): String = when (space) {
    Space.TV -> "Channels, guide & live sports"
    Space.MUSIC -> "Albums, tracks & moods"
    Space.COMICS -> "Manga · Manhwa · Manhua"
    Space.ADULT -> "18+ · PIN protected"
    else -> "Home · Anime · Drama"
}

/**
 * Glass panel opened from the logo. Picking a space swaps the nav bar to that space's tabs;
 * `null` = back to the searchable video spaces.
 */
@Composable
fun SpacesSwitcher(
    visible: Boolean,
    spaces: List<Space>,
    activeSpace: Space?,
    onPick: (Space?) -> Unit,
    onDismiss: () -> Unit,
) {
    val tokens = Cinetor.tokens
    AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(tokens.scrim)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
        )
    }
    AnimatedVisibility(
        visible,
        enter = fadeIn() + scaleIn(initialScale = 0.92f, transformOrigin = TransformOrigin(0f, 0f)),
        exit = fadeOut() + scaleOut(targetScale = 0.92f, transformOrigin = TransformOrigin(0f, 0f)),
    ) {
        Box(Modifier.fillMaxSize().statusBarsPadding().padding(12.dp)) {
            Column(
                Modifier
                    .widthIn(max = 360.dp)
                    .fillMaxWidth()
                    .glass(RoundedCornerShape(24.dp), GlassLevel.SHEET)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    "Spaces",
                    style = MaterialTheme.typography.titleLarge,
                    color = tokens.textPrimary,
                    modifier = Modifier.padding(12.dp),
                )
                SpaceRow("Video", "Home · Anime · Drama", Icons.Rounded.Movie, activeSpace == null) { onPick(null) }
                spaces.forEach { s ->
                    SpaceRow(s.displayName, subtitleOf(s), spaceIcon(s), activeSpace == s) { onPick(s) }
                }
            }
        }
    }
}

@Composable
private fun SpaceRow(title: String, subtitle: String, icon: ImageVector, active: Boolean, onClick: () -> Unit) {
    val tokens = Cinetor.tokens
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(if (active) tokens.accent.copy(alpha = 0.16f) else tokens.glassFill)
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(tokens.accent.copy(alpha = 0.22f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = tokens.accent)
        }
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium, color = tokens.textPrimary)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = tokens.textSecondary)
        }
    }
}
