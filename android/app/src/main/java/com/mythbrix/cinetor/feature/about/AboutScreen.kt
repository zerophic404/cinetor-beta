package com.mythbrix.cinetor.feature.about

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import com.mythbrix.cinetor.BuildConfig
import com.mythbrix.cinetor.core.perf.PerformanceTier
import com.mythbrix.cinetor.design.Cinetor
import com.mythbrix.cinetor.design.CinetorMark
import com.mythbrix.cinetor.design.CinetorTokens
import com.mythbrix.cinetor.design.GlassLevel
import com.mythbrix.cinetor.design.glass

/**
 * About screen: Liquid Glass studio card with brand mark, version, Mythbrix studio credit,
 * "All rights reserved Mythbrix", official community links, and TMDB attribution.
 */
@Composable
fun AboutScreen(contentPadding: PaddingValues) {
    val tokens = Cinetor.tokens
    val uri = LocalUriHandler.current
    val actions = remember(uri) { AboutActions { url -> runCatching { uri.openUri(url) } } }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(contentPadding)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .glass(RoundedCornerShape(26.dp), GlassLevel.SURFACE)
                .padding(horizontal = 24.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            AnimatedLogo()
            Text(
                AboutCopy.APP_NAME,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.ExtraBold,
                color = tokens.textPrimary,
            )
            Text(
                AboutCopy.versionLine(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE, BuildConfig.BUILD_NUMBER),
                style = MaterialTheme.typography.bodySmall,
                color = tokens.textSecondary,
            )

            Spacer(Modifier.height(4.dp))
            HorizontalDivider(color = tokens.divider)
            Spacer(Modifier.height(4.dp))

            Text(
                AboutCopy.DEVELOPED_BY,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = tokens.textPrimary,
                textAlign = TextAlign.Center,
            )
            Text(
                AboutCopy.RIGHTS_RESERVED,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = tokens.accent,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .clip(RoundedCornerShape(CinetorTokens.Shape.pill))
                    .background(tokens.accent.copy(alpha = 0.12f))
                    .border(1.dp, tokens.accent.copy(alpha = 0.32f), RoundedCornerShape(CinetorTokens.Shape.pill))
                    .padding(horizontal = 16.dp, vertical = 6.dp),
            )
            ThanksLine()
        }

        Column(
            Modifier
                .fillMaxWidth()
                .glass(RoundedCornerShape(22.dp), GlassLevel.SURFACE),
        ) {
            LinkRow(Icons.AutoMirrored.Rounded.Send, AboutLink.TELEGRAM_CHANNEL) { actions.open(it) }
            HorizontalDivider(color = tokens.divider, modifier = Modifier.padding(horizontal = 16.dp))
            LinkRow(Icons.Rounded.Groups, AboutLink.TELEGRAM_GROUP) { actions.open(it) }
        }

        Text(
            AboutCopy.TMDB_ATTRIBUTION,
            style = MaterialTheme.typography.bodySmall,
            color = tokens.textTertiary,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

/** Logo breathes gently; static on LOW tier or when Reduce motion is enabled. */
@Composable
private fun AnimatedLogo() {
    val tier = Cinetor.tier
    val still = Cinetor.prefs.reduceMotion || tier == PerformanceTier.LOW
    if (still) {
        CinetorMark(Modifier.size(96.dp))
        return
    }
    val t = rememberInfiniteTransition(label = "aboutLogo")
    val pulse by t.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1_800, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pulse",
    )
    CinetorMark(
        Modifier.size(96.dp).graphicsLayer {
            val s = 0.96f + 0.06f * pulse
            scaleX = s
            scaleY = s
        },
    )
}

@Composable
private fun LinkRow(icon: ImageVector, link: AboutLink, onOpen: (AboutLink) -> Unit) {
    val tokens = Cinetor.tokens
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(18.dp)

    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .then(
                if (focused) {
                    Modifier.background(tokens.surfaceElevated).border(2.dp, tokens.focusRing, shape)
                } else {
                    Modifier
                },
            )
            .clickable(interactionSource = interaction, indication = null) { onOpen(link) }
            .semantics { contentDescription = link.label }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(tokens.accent.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = tokens.accent, modifier = Modifier.size(20.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(link.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = tokens.textPrimary)
            Text(link.url.removePrefix("https://"), style = MaterialTheme.typography.bodySmall, color = tokens.textSecondary)
        }
        Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null, tint = tokens.textSecondary, modifier = Modifier.size(18.dp))
    }
}

/** "Thanks to Ahmed Ishtiaq Zarir" — only the name is a link (LinkAnnotation.Url). */
@Composable
private fun ThanksLine() {
    val tokens = Cinetor.tokens
    val t = thanksText()
    val text = remember(tokens) {
        buildAnnotatedString {
            append(t.text.substring(0, t.start))
            withLink(
                LinkAnnotation.Url(
                    t.url,
                    TextLinkStyles(SpanStyle(color = tokens.accent, fontWeight = FontWeight.SemiBold, textDecoration = TextDecoration.Underline)),
                ),
            ) { append(t.linkedPart) }
            append(t.text.substring(t.end))
        }
    }
    Text(text, style = MaterialTheme.typography.bodyMedium, color = tokens.textSecondary, textAlign = TextAlign.Center)
}
