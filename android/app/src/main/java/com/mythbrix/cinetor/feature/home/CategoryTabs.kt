package com.mythbrix.cinetor.feature.home

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mythbrix.cinetor.core.perf.PerformanceTier
import com.mythbrix.cinetor.design.Cinetor
import com.mythbrix.cinetor.design.CinetorTokens

/**
 * Movira / Liquid Glass pill category tabs (`Movies` | `Series`, `K-Drama` | `C-Drama` | `J-Drama`, etc.):
 * translucent glass outline when idle, solid Cinema Red accent when selected.
 * Keeps `contentDescription = "Tab ${tab.title}"` intact for CI emulator smoke tests.
 */
@Composable
fun CategoryTabs(
    tabs: List<TabSpec>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = Cinetor.tokens
    val lowTier = Cinetor.tier == PerformanceTier.LOW
    val shape = RoundedCornerShape(CinetorTokens.Shape.pill)
    LazyRow(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        itemsIndexed(tabs, key = { _, t -> t.id }, contentType = { _, _ -> "tab" }) { i, tab ->
            val isSelected = i == selected
            val interaction = remember { MutableInteractionSource() }
            val focused by interaction.collectIsFocusedAsState()
            val pressed by interaction.collectIsPressedAsState()
            val scale by animateFloatAsState(
                targetValue = when {
                    pressed -> 0.96f
                    focused -> 1.05f
                    else -> 1f
                },
                label = "tabScale",
            )
            val bg by animateColorAsState(
                targetValue = if (isSelected) tokens.accent else tokens.textPrimary.copy(alpha = 0.07f),
                animationSpec = tween(if (lowTier) 0 else CinetorTokens.Motion.shortMs),
                label = "tabBg",
            )
            val borderColor = when {
                focused -> tokens.focusRing
                isSelected -> Color.Transparent
                else -> tokens.textPrimary.copy(alpha = 0.22f)
            }
            Text(
                text = tab.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                color = if (isSelected) tokens.onAccent else tokens.textPrimary.copy(alpha = 0.88f),
                maxLines = 1,
                modifier = Modifier
                    .graphicsLayer { scaleX = scale; scaleY = scale }
                    .clip(shape)
                    .background(bg)
                    .border(if (focused) 2.dp else 1.dp, borderColor, shape)
                    .clickable(interactionSource = interaction, indication = null, role = Role.Tab) {
                        onSelect(i)
                    }
                    .semantics {
                        this.selected = isSelected
                        contentDescription = "Tab ${tab.title}"
                    }
                    .padding(horizontal = 18.dp, vertical = 9.dp),
            )
        }
    }
}
