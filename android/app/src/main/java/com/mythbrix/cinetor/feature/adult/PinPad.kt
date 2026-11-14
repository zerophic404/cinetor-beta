package com.mythbrix.cinetor.feature.adult

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.keyframes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.mythbrix.cinetor.design.Cinetor
import com.mythbrix.cinetor.design.GlassLevel
import com.mythbrix.cinetor.design.glass
import kotlin.math.roundToInt

/** 4 PIN dots; shakes horizontally whenever [shakeKey] changes (wrong PIN). */
@Composable
fun PinDots(entered: Int, shakeKey: Int, error: Boolean, modifier: Modifier = Modifier) {
    val tokens = Cinetor.tokens
    val shake = remember { Animatable(0f) }
    LaunchedEffect(shakeKey) {
        if (shakeKey > 0) {
            shake.animateTo(
                0f,
                keyframes {
                    durationMillis = 420
                    -24f at 50
                    24f at 110
                    -18f at 170
                    18f at 230
                    -9f at 290
                    9f at 350
                },
            )
        }
    }
    Row(
        modifier
            .offset { IntOffset(shake.value.roundToInt(), 0) }
            .semantics { contentDescription = "PIN $entered of ${PinHasher.PIN_LENGTH}" },
        horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        repeat(PinHasher.PIN_LENGTH) { i ->
            val filled = i < entered
            val color = if (error) tokens.error else tokens.accent
            Box(
                Modifier
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(if (filled) color else tokens.glassFill)
                    .border(1.5.dp, if (filled) color else tokens.glassStroke, CircleShape),
            )
        }
    }
}

/** Glass number pad: 1-9, (cancel), 0, backspace. Keys are labelled "PIN key N" for accessibility/smoke tests. */
@Composable
fun PinPad(
    onDigit: (Char) -> Unit,
    onBackspace: () -> Unit,
    onCancel: (() -> Unit)?,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.alpha(if (enabled) 1f else 0.4f),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        listOf("123", "456", "789").forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                row.forEach { d -> PinKey("PIN key $d", enabled, { onDigit(d) }) { KeyText(d.toString()) } }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
            if (onCancel != null) {
                PinKey("Cancel PIN", true, onCancel, plain = true) {
                    Icon(Icons.Rounded.Close, contentDescription = null, tint = Cinetor.tokens.textSecondary)
                }
            } else {
                Spacer(Modifier.size(KEY))
            }
            PinKey("PIN key 0", enabled, { onDigit('0') }) { KeyText("0") }
            PinKey("PIN backspace", enabled, onBackspace, plain = true) {
                Icon(Icons.AutoMirrored.Rounded.Backspace, contentDescription = null, tint = Cinetor.tokens.textSecondary)
            }
        }
    }
}

private val KEY = 72.dp

@Composable
private fun KeyText(s: String) {
    Text(s, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, color = Cinetor.tokens.textPrimary)
}

@Composable
private fun PinKey(
    desc: String,
    enabled: Boolean,
    onClick: () -> Unit,
    plain: Boolean = false,
    content: @Composable () -> Unit,
) {
    Box(
        Modifier
            .size(KEY)
            .then(if (plain) Modifier.clip(CircleShape) else Modifier.glass(CircleShape, GlassLevel.SURFACE))
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = desc },
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** Title + dots + message + pad in one column (used by the gate and the settings dialogs). */
@Composable
fun PinPanel(
    title: String,
    subtitle: String?,
    entered: Int,
    shakeKey: Int,
    error: Boolean,
    enabled: Boolean,
    onDigit: (Char) -> Unit,
    onBackspace: () -> Unit,
    onCancel: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val tokens = Cinetor.tokens
    Column(
        modifier.padding(horizontal = 24.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge, color = tokens.textPrimary)
        Text(
            subtitle ?: " ",
            style = MaterialTheme.typography.bodyMedium,
            color = if (error) tokens.error else tokens.textSecondary,
            modifier = Modifier.width(280.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        PinDots(entered, shakeKey, error)
        Spacer(Modifier.size(4.dp))
        PinPad(onDigit, onBackspace, onCancel, enabled)
    }
}
