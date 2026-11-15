package com.mythbrix.cinetor.tvui

import android.view.SoundEffectConstants
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import coil3.compose.AsyncImage
import com.mythbrix.cinetor.core.data.Item
import com.mythbrix.cinetor.core.ui.rememberSizedImage
import com.mythbrix.cinetor.design.Cinetor
import com.mythbrix.cinetor.design.hexColor

/** TV focus scale (10-foot: bigger than the phone's 1.08 press scale). */
const val TV_FOCUS_SCALE = 1.1f

/** Optional focus "tick" (Settings › TV › Focus sounds). */
@Composable
fun rememberFocusSound(): () -> Unit {
    val view = LocalView.current
    val enabled by rememberUpdatedState(Cinetor.prefs.focusSounds)
    return remember(view) { { if (enabled) view.playSoundEffect(SoundEffectConstants.CLICK) } }
}

/**
 * Focusable 10-foot card: focus = scale 1.1 + glass glow in the item accent + theme-accent border,
 * title strip below the art when focused. [onFocused] feeds [FocusMemory] + app tint.
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
) {
    val tokens = Cinetor.tokens
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (focused) TV_FOCUS_SCALE else 1f, label = "tvCardScale")
    val glow by animateDpAsState(if (focused) 24.dp else 0.dp, label = "tvCardGlow")
    val accent = remember(item.accentColor) { hexColor(item.accentColor) }
    val dominant = remember(item.dominantColor) { hexColor(item.dominantColor) }
    val sound = rememberFocusSound()
    val focusCb by rememberUpdatedState(onFocused)
    LaunchedEffect(focused) {
        if (focused) {
            sound()
            focusCb()
        }
    }
    val height = if (landscape) width * 9f / 16f else width * 1.5f
    val shape = RoundedCornerShape(14.dp)
    Column(modifier.width(width).zIndex(if (focused) 1f else 0f)) {
        Box(
            Modifier
                .size(width, height)
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .shadow(glow, shape, clip = false, ambientColor = accent, spotColor = accent)
                .clip(shape)
                .background(dominant)
                .border(
                    if (focused) 3.dp else 1.dp,
                    if (focused) tokens.accent else Color.White.copy(alpha = 0.08f),
                    shape,
                )
                .clickable(interactionSource = interaction, indication = null, onClick = onClick)
                .semantics { contentDescription = item.title },
        ) {
            AsyncImage(
                model = rememberSizedImage(if (landscape) item.backdrop else item.poster, width, height),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            if (focused) {
                // Glass sheen over the focused art.
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.14f), Color.Transparent, Color.Black.copy(alpha = 0.35f))),
                    ),
                )
            }
            if (progress != null) {
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color.Black.copy(alpha = 0.5f))) {
                    Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).height(4.dp).background(tokens.accent))
                }
            }
        }
        Text(
            item.title,
            style = MaterialTheme.typography.labelLarge,
            color = if (focused) tokens.textPrimary else tokens.textSecondary,
            fontWeight = if (focused) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 10.dp, start = 2.dp, end = 2.dp),
        )
    }
}

/** Focusable pill button (hero / player / details on TV): accent fill when focused. */
@Composable
fun TvButton(
    label: String,
    icon: ImageVector?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
) {
    val tokens = Cinetor.tokens
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (focused) TV_FOCUS_SCALE else 1f, label = "tvBtnScale")
    val sound = rememberFocusSound()
    LaunchedEffect(focused) { if (focused) sound() }
    val shape = RoundedCornerShape(50)
    val bg = when {
        focused -> tokens.accent
        primary -> Color.White.copy(alpha = 0.22f)
        else -> Color.White.copy(alpha = 0.10f)
    }
    Row(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .shadow(if (focused) 16.dp else 0.dp, shape, clip = false, ambientColor = tokens.accent, spotColor = tokens.accent)
            .clip(shape)
            .background(bg)
            .border(if (focused) 2.dp else 1.dp, if (focused) Color.White.copy(alpha = 0.9f) else Color.White.copy(alpha = 0.12f), shape)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .semantics { contentDescription = label }
            .padding(horizontal = 22.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
        Text(label, color = Color.White, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    }
}
