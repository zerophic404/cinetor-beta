package com.mythbrix.cinetor.feature.home

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mythbrix.cinetor.design.Cinetor
import com.mythbrix.cinetor.design.CinetorTokens

/** Category tabs (Movies | Series, K | C | J, ...): pill tabs, selected = accent fill. */
@Composable
fun CategoryTabs(tabs: List<TabSpec>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val tokens = Cinetor.tokens
    val shape = RoundedCornerShape(CinetorTokens.Shape.pill)
    LazyRow(
        modifier,
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        itemsIndexed(tabs, key = { _, t -> t.id }, contentType = { _, _ -> "tab" }) { i, tab ->
            val isSelected = i == selected
            val bg by animateColorAsState(if (isSelected) tokens.accent else Color.White.copy(alpha = 0.06f), label = "tabBg")
            Text(
                tab.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                color = if (isSelected) tokens.onAccent else tokens.textSecondary,
                modifier = Modifier
                    .clip(shape)
                    .background(bg)
                    .border(1.dp, if (isSelected) Color.Transparent else tokens.glassStroke, shape)
                    .clickable(role = Role.Tab) { onSelect(i) }
                    .semantics {
                        this.selected = isSelected
                        contentDescription = "Tab ${tab.title}"
                    }
                    .padding(horizontal = 18.dp, vertical = 9.dp),
            )
        }
    }
}
