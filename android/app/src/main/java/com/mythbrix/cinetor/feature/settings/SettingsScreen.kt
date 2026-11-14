package com.mythbrix.cinetor.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.mythbrix.cinetor.core.prefs.SettingsStore
import com.mythbrix.cinetor.feature.adult.AdultGate
import com.mythbrix.cinetor.feature.adult.AdultSettingsBlock
import com.mythbrix.cinetor.design.Cinetor
import kotlinx.coroutines.launch

/** Settings — playback toggles now; full app & profile settings in Task 5.2. */
@Composable
fun SettingsScreen(contentPadding: PaddingValues, settings: SettingsStore, adult: AdultGate? = null) {
    val scope = rememberCoroutineScope()
    val backgroundPlay by settings.backgroundPlay.collectAsStateWithLifecycle(initialValue = false)
    val autoPip by settings.autoPip.collectAsStateWithLifecycle(initialValue = true)
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(contentPadding).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium, color = Cinetor.tokens.textPrimary)
        Text("Playback", style = MaterialTheme.typography.titleMedium, color = Cinetor.tokens.textPrimary)
        ToggleRow("Background play", "Keep video audio playing with the screen off / app in background", backgroundPlay) {
            scope.launch { settings.setBackgroundPlay(it) }
        }
        ToggleRow("Picture-in-picture", "Float the video when you leave the app while it plays", autoPip) {
            scope.launch { settings.setAutoPip(it) }
        }
        // Content (Task 4.4): 18+ toggle → age confirm → PIN. Full settings in 5.2.
        AdultSettingsBlock(adult)
        Text("More app & profile settings arrive in Phase 5.", color = Cinetor.tokens.textSecondary)
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val tokens = Cinetor.tokens
    Row(Modifier.fillMaxWidth().semantics { contentDescription = title }, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = tokens.textPrimary)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = tokens.textSecondary)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = tokens.accent),
        )
    }
}
