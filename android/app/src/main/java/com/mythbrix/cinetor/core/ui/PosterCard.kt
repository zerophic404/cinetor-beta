package com.mythbrix.cinetor.core.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.mythbrix.cinetor.core.data.AssetUris
import com.mythbrix.cinetor.core.data.Item
import com.mythbrix.cinetor.design.Cinetor
import com.mythbrix.cinetor.design.CinetorTokens
import com.mythbrix.cinetor.design.hexColor

private val CardShape = RoundedCornerShape(CinetorTokens.Shape.card)

/** 2:3 poster; scales to 1.08 with an accent glow border on press/focus and feeds the app tint. */
@Composable
fun PosterCard(item: Item, onClick: () -> Unit, modifier: Modifier = Modifier, width: Dp = 112.dp) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    val active = pressed || focused
    val scale by animateFloatAsState(if (active) CinetorTokens.Motion.cardFocusScale else 1f, label = "cardScale")
    val dominant = remember(item.id) { hexColor(item.dominantColor) }
    val accent = remember(item.id) { hexColor(item.accentColor) }
    val tint = Cinetor.tint
    LaunchedEffect(active) { if (active) tint.setSource(dominant) }
    Box(
        modifier
            .width(width)
            .aspectRatio(2f / 3f)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(CardShape)
            .background(dominant)
            .border(1.dp, if (active) accent.copy(alpha = 0.9f) else Color.White.copy(alpha = 0.06f), CardShape)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
    ) {
        AsyncImage(
            model = AssetUris.of(item.poster),
            contentDescription = item.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    }
}
