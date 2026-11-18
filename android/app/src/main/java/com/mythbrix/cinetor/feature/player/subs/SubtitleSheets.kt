package com.mythbrix.cinetor.feature.player.subs

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mythbrix.cinetor.design.Cinetor
import kotlin.math.roundToInt

private val SheetBg = Color(0xF0101010)

/** Subtitle style editor (size, colors, background opacity, edge, font, position). Saved per profile. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubtitleStyleSheet(style: SubtitleStyle, onChange: (SubtitleStyle) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = SheetBg,
        contentColor = Color.White,
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Subtitle style", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            // Live preview
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color(0xFF2A2A2A)).padding(vertical = 18.dp),
                contentAlignment = Alignment.Center,
            ) {
                val previewBg = if (style.bgOpacity <= 0.01f) Color.Transparent else Color(style.bgArgb.toInt())
                val previewShadow = if (style.edge == SubEdge.SHADOW || style.bgOpacity <= 0.01f) {
                    Shadow(Color(0xF0000000), Offset(0f, 2f), 6f)
                } else null
                Text(
                    "The quick brown fox",
                    color = Color(style.textColor.toInt()),
                    fontFamily = style.fontFamily(),
                    fontWeight = if (style.font == SubFont.BOLD) FontWeight.Bold else FontWeight.SemiBold,
                    fontSize = (16 * style.sizePercent / 100f).sp,
                    style = TextStyle(shadow = previewShadow),
                    modifier = Modifier.background(previewBg, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
            Label("Size ${style.sizePercent}%")
            CinetorSlider(style.sizePercent.toFloat(), 50f..200f, steps = 14) { onChange(style.copy(sizePercent = (it / 10).roundToInt() * 10)) }
            Label("Text color")
            Swatches(SubtitleStyle.TEXT_COLORS, style.textColor) { onChange(style.copy(textColor = it)) }
            Label("Background")
            Swatches(SubtitleStyle.BG_COLORS, style.bgColor) { onChange(style.copy(bgColor = it)) }
            Label("Background opacity ${(style.bgOpacity * 100).roundToInt()}%")
            CinetorSlider(style.bgOpacity, 0f..1f, steps = 9) { onChange(style.copy(bgOpacity = (it * 10).roundToInt() / 10f)) }
            Label("Edge")
            Chips(SubEdge.entries.map { it.label }, style.edge.ordinal) { onChange(style.copy(edge = SubEdge.entries[it])) }
            Label("Font")
            Chips(SubFont.entries.map { it.label }, style.font.ordinal) { onChange(style.copy(font = SubFont.entries[it])) }
            Label("Position ${style.bottomPercent}% from bottom")
            CinetorSlider(style.bottomPercent.toFloat(), 0f..40f, steps = 7) { onChange(style.copy(bottomPercent = (it / 5).roundToInt() * 5)) }
            Text(
                "Reset",
                color = Cinetor.tokens.accent,
                modifier = Modifier.clickable { onChange(SubtitleStyle()) }.padding(vertical = 8.dp),
            )
        }
    }
}

/** Sync offset: ±50 ms / ±500 ms nudges within ±10 s, reset. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubtitleSyncSheet(state: SubtitleUiState, onNudge: (Long) -> Unit, onReset: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = SheetBg,
        contentColor = Color.White,
    ) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Subtitle sync", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                SubtitleSync.label(state.offsetMs),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { contentDescription = "Subtitle offset" },
            )
            Text("+ = subtitles later · − = earlier", color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.labelSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Nudge("−0.5s") { onNudge(-500) }
                Nudge("−50ms") { onNudge(-SubtitleSync.STEP_MS) }
                Nudge("+50ms") { onNudge(SubtitleSync.STEP_MS) }
                Nudge("+0.5s") { onNudge(500) }
            }
            Text("Reset", color = Cinetor.tokens.accent, modifier = Modifier.clickable(onClick = onReset).padding(8.dp))
            state.hint?.let { Text(it, color = Cinetor.tokens.warning, style = MaterialTheme.typography.bodySmall) }
            state.sideLoaded?.let {
                Text("Using ${it.label} (${it.cues.size} cues)", color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun Label(text: String) = Text(text, style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = 0.8f))

@Composable
private fun CinetorSlider(value: Float, range: ClosedFloatingPointRange<Float>, steps: Int, onChange: (Float) -> Unit) {
    val accent = Cinetor.tokens.accent
    Slider(
        value = value,
        onValueChange = onChange,
        valueRange = range,
        steps = steps,
        colors = SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent),
    )
}

@Composable
private fun Swatches(colors: List<Long>, selected: Long, onPick: (Long) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        colors.forEach { c ->
            val isSel = (c and 0xFFFFFF) == (selected and 0xFFFFFF)
            Box(
                Modifier.size(32.dp).clip(CircleShape).background(Color(c.toInt()))
                    .border(if (isSel) 3.dp else 1.dp, if (isSel) Cinetor.tokens.accent else Color.White.copy(alpha = 0.3f), CircleShape)
                    .clickable { onPick(c) },
            )
        }
    }
}

@Composable
private fun Chips(labels: List<String>, selected: Int, onPick: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        labels.forEachIndexed { i, l ->
            val sel = i == selected
            Text(
                l,
                color = if (sel) Color.White else Color.White.copy(alpha = 0.75f),
                fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier.clip(RoundedCornerShape(50))
                    .background(if (sel) Cinetor.tokens.accent else Color.White.copy(alpha = 0.1f))
                    .clickable { onPick(i) }
                    .padding(horizontal = 14.dp, vertical = 7.dp),
            )
        }
    }
}

@Composable
private fun Nudge(label: String, onClick: () -> Unit) {
    Text(
        label,
        color = Color.White,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(Color.White.copy(alpha = 0.12f))
            .clickable(onClick = onClick).semantics { contentDescription = "Subtitle $label" }
            .padding(horizontal = 12.dp, vertical = 10.dp),
    )
}
