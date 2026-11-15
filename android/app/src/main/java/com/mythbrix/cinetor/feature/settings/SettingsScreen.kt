package com.mythbrix.cinetor.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mythbrix.cinetor.core.data.AppContainer
import com.mythbrix.cinetor.core.perf.EffectsQuality
import com.mythbrix.cinetor.core.prefs.AppSettings
import com.mythbrix.cinetor.core.prefs.ImageQuality
import com.mythbrix.cinetor.core.prefs.MaturityLimit
import com.mythbrix.cinetor.core.prefs.SettingsOptions
import com.mythbrix.cinetor.core.prefs.VideoQuality
import com.mythbrix.cinetor.design.AppTheme
import com.mythbrix.cinetor.design.Cinetor
import com.mythbrix.cinetor.design.CinetorTokens
import com.mythbrix.cinetor.design.GlassLevel
import com.mythbrix.cinetor.design.glass
import com.mythbrix.cinetor.feature.adult.AdultGate
import com.mythbrix.cinetor.feature.adult.AdultSettingsBlock
import com.mythbrix.cinetor.feature.adult.EnableResult
import com.mythbrix.cinetor.feature.adult.UnlockResult
import com.mythbrix.cinetor.feature.profile.ProfileAvatar
import com.mythbrix.cinetor.feature.profile.ProfilePinDialog
import kotlinx.coroutines.launch

/**
 * Settings (Task 5.2): Appearance · Playback · Subtitles & audio · Data & storage · Content · Profiles · TV ·
 * Developer · About. Every change goes through [SettingsStateHolder] → the root recomposes in place (no restart).
 */
@Composable
fun SettingsScreen(
    container: AppContainer,
    contentPadding: PaddingValues,
    onProfiles: () -> Unit,
    onAbout: () -> Unit,
) {
    val holder = container.settingsState
    val s by holder.state.collectAsStateWithLifecycle()
    val theme by holder.theme.collectAsStateWithLifecycle()
    val profile by container.profiles.active.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val set: ((AppSettings) -> AppSettings) -> Unit = { f -> holder.update(f) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(contentPadding).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium, color = Cinetor.tokens.textPrimary)

        Group(null) {
            // Content first: parental controls at a glance (and the 18+ toggle stays on the first screen for smoke/TV).
            // 18+ toggle → age confirm → PIN (Task 4.4 gate). Greyed on Kids profiles.
            AdultSettingsBlock(container.adult)
            ChangeAdultPin(container.adult)
            Choice("Maturity limit", MaturityLimit.entries, s.maturityLimit, { it.label }) { v -> set { it.copy(maturityLimit = v) } }
        }

        Group("Appearance") {
            Label("Theme · ${profile.name}")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                AppTheme.entries.forEach { t ->
                    ThemePreviewCard(t, selected = t == theme, modifier = Modifier.weight(1f)) { holder.setTheme(t) }
                }
            }
            Choice("Effects quality", EffectsQuality.entries, s.effects, { it.name.lowercase().replaceFirstChar { c -> c.uppercase() } }) { v ->
                set { it.copy(effects = v) }
            }
            Toggle("Reduce motion", "Shorter animations, banner stays still", s.reduceMotion) { v -> set { it.copy(reduceMotion = v) } }
            Toggle("Dynamic color", "Tint the background from the artwork", s.dynamicColor) { v -> set { it.copy(dynamicColor = v) } }
        }

        Group("Playback") {
            Choice("Default quality · Wi-Fi", VideoQuality.entries, s.qualityWifi, { it.label }) { v -> set { it.copy(qualityWifi = v) } }
            Choice("Default quality · Mobile data", VideoQuality.entries, s.qualityMobile, { it.label }) { v -> set { it.copy(qualityMobile = v) } }
            Toggle("Autoplay next episode", "10 s countdown at the end of an episode", s.autoplayNext) { v -> set { it.copy(autoplayNext = v) } }
            Toggle("Skip intro automatically", "Jump past marked intros", s.skipIntroAuto) { v -> set { it.copy(skipIntroAuto = v) } }
            Choice("Double-tap seek", SettingsOptions.DOUBLE_TAP_SEC, s.doubleTapSec, { "$it s" }) { v -> set { it.copy(doubleTapSec = v) } }
            Choice("Hold speed", SettingsOptions.HOLD_SPEEDS, s.holdSpeed, ::speedLabel) { v -> set { it.copy(holdSpeed = v) } }
            Toggle("Player gestures", "Double-tap seek, hold for speed, swipe brightness/volume", s.gestures) { v -> set { it.copy(gestures = v) } }
            Toggle("Picture-in-picture", "Float the video when you leave the app while it plays", s.autoPip) { v -> set { it.copy(autoPip = v) } }
            Toggle("Background play", "Keep video audio playing with the screen off / app in background", s.backgroundPlay) { v ->
                set { it.copy(backgroundPlay = v) }
            }
        }

        Group("Subtitles & audio") {
            Toggle("Subtitles on by default", "Pick the preferred language when available", s.subtitlesOn) { v -> set { it.copy(subtitlesOn = v) } }
            Choice("Subtitle language", SettingsOptions.LANGUAGES.drop(1).map { it.first }, s.subtitleLang, ::langLabel) { v ->
                set { it.copy(subtitleLang = v) }
            }
            Choice("Audio language", SettingsOptions.LANGUAGES.map { it.first }, s.audioLang, ::langLabel) { v -> set { it.copy(audioLang = v) } }
            val sub by container.subtitles.state.collectAsStateWithLifecycle()
            Choice("Subtitle size · ${profile.name}", listOf(75, 100, 125, 150), sub.style.sizePercent, { "$it%" }) { v ->
                container.subtitles.setStyle(sub.style.copy(sizePercent = v))
            }
            Choice("Subtitle background", listOf(0f, 0.35f, 0.7f), sub.style.bgOpacity, { if (it == 0f) "None" else "${(it * 100).toInt()}%" }) { v ->
                container.subtitles.setStyle(sub.style.copy(bgOpacity = v))
            }
            Hint("Profiles can override languages in Edit profile.")
        }

        Group("Data & storage") {
            Toggle("Data saver", "Lower quality, no pre-buffering", s.dataSaver) { v -> set { it.copy(dataSaver = v) } }
            Choice("Image quality", ImageQuality.entries, s.imageQuality, { it.label }) { v -> set { it.copy(imageQuality = v) } }
            Toggle("Preload on details", "Warm the stream while you read details (instant play)", s.preloadDetails) { v ->
                set { it.copy(preloadDetails = v) }
            }
            Choice("Video cache size", SettingsOptions.CACHE_MB, s.cacheSizeMb, { if (it >= 1000) "${it / 1000} GB" else "$it MB" }) { v ->
                set { it.copy(cacheSizeMb = v) }
            }
            Hint("Cache size applies after restart.")
            CacheRow(container)
            var confirmClear by remember { mutableStateOf(false) }
            ActionRow("Clear watch history", "History, resume points and recent searches of ${profile.name}") { confirmClear = true }
            if (confirmClear) {
                AlertDialog(
                    onDismissRequest = { confirmClear = false },
                    title = { Text("Clear history?") },
                    text = { Text("Continue Watching, history and recent searches of ${profile.name} are removed.") },
                    confirmButton = {
                        TextButton(onClick = { confirmClear = false; scope.launch { container.clearHistory() } }) {
                            Text("Clear", color = Cinetor.tokens.accent)
                        }
                    },
                    dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
                    containerColor = Cinetor.tokens.surfaceElevated,
                )
            }
        }

        Group("Profiles") {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onProfiles)
                    .semantics { contentDescription = "Manage profiles" }.padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ProfileAvatar(profile, 40.dp)
                Column(Modifier.weight(1f)) {
                    Text(profile.name, color = Cinetor.tokens.textPrimary, style = MaterialTheme.typography.bodyLarge)
                    Text("Switch, add or edit profiles", color = Cinetor.tokens.textSecondary, style = MaterialTheme.typography.bodySmall)
                }
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = Cinetor.tokens.textSecondary)
            }
        }

        Group("TV") {
            Toggle("Focus sounds", "Click sound when moving focus with the remote", s.tvFocusSounds) { v -> set { it.copy(tvFocusSounds = v) } }
            Toggle("Banner autoplay", "Hero banner advances by itself", s.bannerAutoplay) { v -> set { it.copy(bannerAutoplay = v) } }
        }

        Group("Developer") {
            Toggle("Mock latency", "Simulated 0.3–1.2 s delay on the mock catalog (shows skeletons)", s.mockLatency) { v ->
                set { it.copy(mockLatency = v) }
            }
            var gatewayUrl by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(s.gatewayUrl) }
            // Hardening §2: the override value is masked until the user asks to see it, and the real
            // default base URL is never printed as a placeholder / supporting text.
            var reveal by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
            androidx.compose.material3.OutlinedTextField(
                value = gatewayUrl,
                onValueChange = { v -> gatewayUrl = v; set { it.copy(gatewayUrl = v.trim()) } },
                label = { Text("Gateway URL override") },
                visualTransformation = if (reveal) {
                    androidx.compose.ui.text.input.VisualTransformation.None
                } else {
                    androidx.compose.ui.text.input.PasswordVisualTransformation()
                },
                trailingIcon = {
                    androidx.compose.material3.TextButton(onClick = { reveal = !reveal }) {
                        Text(if (reveal) "Hide" else "Show", color = Cinetor.tokens.accent)
                    }
                },
                supportingText = { Text("Blank = built-in default. Catalog + streams for Home, Anime, Drama, 18+") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).semantics { contentDescription = "Gateway URL" },
            )
            Toggle(
                "Debug log",
                "Keep request detail in a local log for troubleshooting. Off by default, never sent anywhere.",
                s.debugLog,
            ) { v ->
                com.mythbrix.cinetor.core.legal.DebugLog.setEnabled(v)
                set { it.copy(debugLog = v) }
            }
        }

        Group("About") {
            ActionRow("About Cinetor", "Version, links, credits, licenses", onAbout)
        }
    }
}

private fun langLabel(code: String): String = SettingsOptions.LANGUAGES.firstOrNull { it.first == code }?.second ?: code

private fun speedLabel(speed: Float): String =
    (if (speed % 1f == 0f) speed.toInt().toString() else speed.toString()) + "×"

/** Mini mock of the theme (bg, glass card, accent button) — tap applies immediately. */
@Composable
private fun ThemePreviewCard(theme: AppTheme, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val t = CinetorTokens.of(theme)
    val current = Cinetor.tokens
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier
            .clip(shape)
            .background(t.bg)
            .border(if (selected) 2.dp else 1.dp, if (selected) current.accent else t.glassStroke, shape)
            .clickable(onClick = onClick)
            .semantics { contentDescription = "Theme ${t.name}" }
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.fillMaxWidth().height(34.dp).clip(RoundedCornerShape(8.dp)).background(t.surfaceElevated)) {
            Box(Modifier.padding(6.dp).size(14.dp).clip(CircleShape).background(t.accent))
        }
        Box(Modifier.width(48.dp).height(6.dp).clip(CircleShape).background(t.textPrimary.copy(alpha = 0.8f)))
        Box(Modifier.width(32.dp).height(6.dp).clip(CircleShape).background(t.textSecondary.copy(alpha = 0.6f)))
        Box(Modifier.fillMaxWidth().height(14.dp).clip(CircleShape).background(t.accent))
        Text(t.name, color = t.textPrimary, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun CacheRow(container: AppContainer) {
    val scope = rememberCoroutineScope()
    var bytes by remember { mutableLongStateOf(-1L) }
    var refresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(refresh) { bytes = runCatching { container.cacheBytes() }.getOrDefault(0L) }
    val label = if (bytes < 0) "Calculating…" else "%.1f MB used".format(bytes / 1_048_576.0)
    ActionRow("Clear cache", "Images & temporary files · $label") {
        scope.launch {
            runCatching { container.clearImageCache() }
            refresh++
        }
    }
}

/** Change the 18+ PIN: current → new → confirm. Hidden while the section is off. */
@Composable
private fun ChangeAdultPin(gate: AdultGate) {
    val enabled by gate.enabled.collectAsStateWithLifecycle()
    if (!enabled) return
    var step by remember { mutableIntStateOf(0) }
    var first by remember { mutableStateOf("") }
    var done by remember { mutableStateOf<String?>(null) }
    ActionRow("Change 18+ PIN", done ?: "Current PIN required") { step = 1; done = null }
    if (step > 0) {
        ProfilePinDialog(
            title = when (step) {
                1 -> "Current PIN"
                2 -> "New 4-digit PIN"
                else -> "Confirm new PIN"
            },
            onDismiss = { step = 0 },
            onPin = { pin -> changePinStep(gate, step, pin, first, { first = it }, { step = it }, { done = it }) },
        )
    }
}

private suspend fun changePinStep(
    gate: AdultGate,
    step: Int,
    pin: String,
    first: String,
    setFirst: (String) -> Unit,
    setStep: (Int) -> Unit,
    setDone: (String) -> Unit,
): Boolean = when (step) {
    1 -> {
        val ok = gate.verify(pin) == UnlockResult.Ok
        if (ok) setStep(2)
        ok
    }
    2 -> {
        setFirst(pin)
        setStep(3)
        true
    }
    else -> {
        val ok = pin == first && gate.enable(ageConfirmed = true, pin = first, confirmPin = pin) == EnableResult.Ok
        if (ok) {
            setStep(0)
            setDone("PIN changed")
        } else {
            setStep(2)
        }
        ok
    }
}

@Composable
private fun Group(title: String?, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().glass(RoundedCornerShape(20.dp), GlassLevel.SURFACE).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (title != null) Text(title, style = MaterialTheme.typography.titleMedium, color = Cinetor.tokens.textPrimary)
        content()
    }
}

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = Cinetor.tokens.textSecondary)
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = Cinetor.tokens.textTertiary)
}

@Composable
private fun Toggle(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val tokens = Cinetor.tokens
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onChange(!checked) }
            .semantics { contentDescription = title },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = tokens.textPrimary)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = tokens.textSecondary)
        }
        Switch(checked = checked, onCheckedChange = onChange, colors = SwitchDefaults.colors(checkedTrackColor = tokens.accent))
    }
}

@Composable
private fun <T> Choice(title: String, options: List<T>, selected: T, label: (T) -> String, onPick: (T) -> Unit) {
    val tokens = Cinetor.tokens
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge, color = tokens.textPrimary)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { o ->
                val l = label(o)
                FilterChip(
                    selected = o == selected,
                    onClick = { onPick(o) },
                    label = { Text(l) },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = tokens.accent, selectedLabelColor = tokens.onAccent),
                    modifier = Modifier.semantics { contentDescription = "$title $l" },
                )
            }
        }
    }
}

@Composable
private fun ActionRow(title: String, subtitle: String, onClick: () -> Unit) {
    val tokens = Cinetor.tokens
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick)
            .semantics { contentDescription = title }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = tokens.textPrimary)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = tokens.textSecondary)
        }
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = tokens.textSecondary)
    }
}
