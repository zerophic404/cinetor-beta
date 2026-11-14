package com.mythbrix.cinetor.feature.about

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import com.mythbrix.cinetor.design.Cinetor
import com.mythbrix.cinetor.design.CinetorMark
import com.mythbrix.cinetor.design.GlassLevel
import com.mythbrix.cinetor.design.glass

/** About (Task 5.3): glass card with animated logo, version, Telegram links, credits, TMDB line, licenses. */
@Composable
fun AboutScreen(contentPadding: PaddingValues) {
    val tokens = Cinetor.tokens
    val uri = LocalUriHandler.current
    val actions = remember(uri) { AboutActions { url -> runCatching { uri.openUri(url) } } }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(contentPadding).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(
            Modifier.fillMaxWidth().glass(RoundedCornerShape(28.dp), GlassLevel.SURFACE).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            AnimatedLogo()
            Text(AboutCopy.APP_NAME, style = MaterialTheme.typography.headlineMedium, color = tokens.textPrimary)
            Text(
                AboutCopy.versionLine(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE, BuildConfig.BUILD_NUMBER),
                style = MaterialTheme.typography.bodySmall,
                color = tokens.textSecondary,
            )
            HorizontalDivider(color = tokens.divider, modifier = Modifier.padding(vertical = 4.dp))
            LinkRow(Icons.AutoMirrored.Rounded.Send, AboutLink.TELEGRAM_CHANNEL) { actions.open(it) }
            LinkRow(Icons.Rounded.Groups, AboutLink.TELEGRAM_GROUP) { actions.open(it) }
            HorizontalDivider(color = tokens.divider, modifier = Modifier.padding(vertical = 4.dp))
            Text(AboutCopy.DEVELOPED_BY, style = MaterialTheme.typography.titleMedium, color = tokens.textPrimary)
            ThanksLine()
            Text(
                AboutCopy.TMDB_ATTRIBUTION,
                style = MaterialTheme.typography.bodySmall,
                color = tokens.textTertiary,
                textAlign = TextAlign.Center,
            )
        }
        Column(
            Modifier.fillMaxWidth().glass(RoundedCornerShape(20.dp), GlassLevel.SURFACE).padding(16.dp)
                .semantics { contentDescription = "Open-source licenses" },
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Open-source licenses", style = MaterialTheme.typography.titleMedium, color = tokens.textPrimary)
            OSS_LICENSES.forEach { l ->
                Column {
                    Text(l.name, style = MaterialTheme.typography.bodyMedium, color = tokens.textPrimary)
                    Text(l.license, style = MaterialTheme.typography.bodySmall, color = tokens.textSecondary)
                }
            }
        }
    }
}

/** Logo breathes (scale + glow alpha); still when Reduce motion is on. */
@Composable
private fun AnimatedLogo() {
    val still = Cinetor.prefs.reduceMotion
    val t = rememberInfiniteTransition(label = "aboutLogo")
    val pulse by t.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1_600, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pulse",
    )
    val p = if (still) 0.5f else pulse
    CinetorMark(
        Modifier.size(104.dp).graphicsLayer {
            val s = 0.94f + 0.08f * p
            scaleX = s
            scaleY = s
            rotationZ = -4f + 8f * p
        },
    )
}

@Composable
private fun LinkRow(icon: ImageVector, link: AboutLink, onOpen: (AboutLink) -> Unit) {
    val tokens = Cinetor.tokens
    Row(
        Modifier.fillMaxWidth().glass(RoundedCornerShape(16.dp), GlassLevel.SURFACE).clickable { onOpen(link) }
            .semantics { contentDescription = link.label }.padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, contentDescription = null, tint = tokens.accent)
        Column(Modifier.weight(1f)) {
            Text(link.label, style = MaterialTheme.typography.bodyLarge, color = tokens.textPrimary)
            Text(link.url.removePrefix("https://"), style = MaterialTheme.typography.bodySmall, color = tokens.textSecondary)
        }
        Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null, tint = tokens.textSecondary)
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
    Text(text, style = MaterialTheme.typography.bodyLarge, color = tokens.textPrimary, textAlign = TextAlign.Center)
}
