package com.mythbrix.cinetor.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.mythbrix.cinetor.design.Cinetor
import com.mythbrix.cinetor.design.CinetorTokens

/** Genre filter chips ("All" + space genres). Selected chip = accent outline + tinted fill. */
@Composable
fun GenreChips(genres: List<String>, selected: String?, onSelect: (String?) -> Unit, modifier: Modifier = Modifier) {
    val tokens = Cinetor.tokens
    val shape = RoundedCornerShape(CinetorTokens.Shape.pill)
    val all = listOf<String?>(null) + genres
    LazyRow(
        modifier,
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(all, key = { it ?: "__all" }, contentType = { "chip" }) { g ->
            val isSelected = g == selected
            Text(
                g ?: "All",
                style = MaterialTheme.typography.labelLarge,
                color = if (isSelected) tokens.textPrimary else tokens.textSecondary,
                modifier = Modifier
                    .clip(shape)
                    .background(if (isSelected) tokens.accent.copy(alpha = 0.22f) else Color.Transparent)
                    .border(1.dp, if (isSelected) tokens.accent else tokens.glassStroke, shape)
                    .clickable(role = Role.Checkbox) { onSelect(g) }
                    .padding(horizontal = 14.dp, vertical = 7.dp),
            )
        }
    }
}
