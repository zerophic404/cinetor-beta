package com.mythbrix.cinetor.feature.adult

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.mythbrix.cinetor.design.Cinetor
import com.mythbrix.cinetor.design.CinetorTokens
import com.mythbrix.cinetor.design.GlassLevel
import com.mythbrix.cinetor.design.glass
import kotlin.math.roundToInt

/** 4 Liquid Glass PIN dots; shakes horizontally whenever [shakeKey] changes (wrong PIN). */
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
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(PinHasher.PIN_LENGTH) { i ->
            val filled = i < entered
            val activeColor = if (error) tokens.error else tokens.accent
            val fill by animateColorAsState(
                targetValue = if (filled) activeColor else tokens.glassFill,
                animationSpec = tween(CinetorTokens.Motion.shortMs),
                label = "pinDotFill",
            )
            val stroke by animateColorAsState(
                targetValue = when {
                    error -> tokens.error
                    filled -> activeColor
                    else -> tokens.glassStroke
                },
                animationSpec = tween(CinetorTokens.Motion.shortMs),
                label = "pinDotStroke",
            )
            val scale by animateFloatAsState(
                targetValue = if (filled) 1.14f else 1f,
                animationSpec = tween(CinetorTokens.Motion.shortMs),
                label = "pinDotScale",
            )
            Box(
                Modifier
                    .size(22.dp)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                    },
                contentAlignment = Alignment.Center,
            ) {
                if (filled && !error) {
                    Box(
                        Modifier
                            .size(22.dp)
                            .clip(CircleShape)
                            .background(tokens.accentGlow.copy(alpha = 0.28f)),
                    )
                }
                Box(
                    Modifier
                        .size(15.dp)
                        .clip(CircleShape)
                        .background(fill)
                        .border(1.5.dp, stroke, CircleShape),
                )
            }
        }
    }
}

/** Liquid Glass number pad: 1-9, (cancel), 0, backspace. Keys keep "PIN key N" semantics for smoke tests. */
@Composable
fun PinPad(
    onDigit: (Char) -> Unit,
    onBackspace: () -> Unit,
    onCancel: (() -> Unit)?,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val tokens = Cinetor.tokens
    Column(
        modifier.alpha(if (enabled) 1f else 0.4f),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        listOf("123", "456", "789").forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                row.forEach { d ->
                    PinKey("PIN key $d", enabled, { onDigit(d) }) { KeyText(d.toString()) }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            if (onCancel != null) {
                PinKey("Cancel PIN", true, onCancel, plain = true) {
                    Icon(Icons.Rounded.Close, contentDescription = null, tint = tokens.textSecondary)
                }
            } else {
                Spacer(Modifier.size(KEY))
            }
            PinKey("PIN key 0", enabled, { onDigit('0') }) { KeyText("0") }
            PinKey("PIN backspace", enabled, onBackspace, plain = true) {
                Icon(Icons.AutoMirrored.Rounded.Backspace, contentDescription = null, tint = tokens.textSecondary)
            }
        }
    }
}

private val KEY = 72.dp

@Composable
private fun KeyText(s: String) {
    Text(
        s,
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.SemiBold,
        color = Cinetor.tokens.textPrimary,
    )
}

@Composable
private fun PinKey(
    desc: String,
    enabled: Boolean,
    onClick: () -> Unit,
    plain: Boolean = false,
    content: @Composable () -> Unit,
) {
    val tokens = Cinetor.tokens
    Box(
        Modifier
            .size(KEY)
            .then(
                if (plain) {
                    Modifier
                        .clip(CircleShape)
                        .background(Color.Transparent)
                        .border(1.dp, tokens.divider, CircleShape)
                } else {
                    Modifier.glass(CircleShape, GlassLevel.SURFACE)
                },
            )
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = desc },
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

/** Title + dots + message + pad in one balanced Liquid Glass column (used by the gate and settings dialogs). */
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
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = tokens.textPrimary,
        )
        Box(
            Modifier.width(280.dp).heightIn(min = 38.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                subtitle ?: " ",
                style = MaterialTheme.typography.bodyMedium,
                color = if (error) tokens.error else tokens.textSecondary,
                textAlign = TextAlign.Center,
                maxLines = 2,
            )
        }
        PinDots(entered, shakeKey, error)
        Spacer(Modifier.size(4.dp))
        PinPad(onDigit, onBackspace, onCancel, enabled)
    }
}
