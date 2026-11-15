package com.mythbrix.cinetor.feature.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mythbrix.cinetor.core.prefs.SettingsOptions
import com.mythbrix.cinetor.design.Cinetor
import com.mythbrix.cinetor.design.GlassLevel
import com.mythbrix.cinetor.design.glass
import kotlinx.coroutines.launch

/**
 * Create ([profileId] empty) or edit a profile: name, generated avatar (shape + colour), Kids flag,
 * optional PIN, subtitle / audio language defaults. Delete removes the profile with all its data.
 */
@Composable
fun EditProfile(manager: ProfileManager, profileId: String, contentPadding: PaddingValues, onDone: () -> Unit) {
    val tokens = Cinetor.tokens
    val scope = rememberCoroutineScope()
    val profiles by manager.profiles.collectAsStateWithLifecycle()
    val existing = profiles.firstOrNull { it.id == profileId }
    val isNew = profileId.isEmpty()

    var loaded by rememberSaveable { mutableStateOf(isNew) }
    var name by rememberSaveable { mutableStateOf("") }
    var avatar by rememberSaveable { mutableIntStateOf(profiles.size) }
    var color by rememberSaveable { mutableLongStateOf(Avatars.colors[profiles.size % Avatars.colors.size]) }
    var kids by rememberSaveable { mutableStateOf(false) }
    var subLang by rememberSaveable { mutableStateOf<String?>(null) }
    var audioLang by rememberSaveable { mutableStateOf<String?>(null) }
    var newPin by rememberSaveable { mutableStateOf<String?>(null) }
    var clearPin by rememberSaveable { mutableStateOf(false) }
    var pinStep by rememberSaveable { mutableIntStateOf(0) } // 0 none, 1 enter, 2 confirm
    var firstPin by rememberSaveable { mutableStateOf("") }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var message by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(existing?.id) {
        if (!loaded && existing != null) {
            name = existing.name; avatar = existing.avatar; color = existing.color; kids = existing.kids
            subLang = existing.subtitleLang; audioLang = existing.audioLang
            loaded = true
        }
    }
    val hasPin = (existing?.hasPin() == true && !clearPin) || newPin != null
    val save: () -> Unit = {
                scope.launch {
                    if (isNew) {
                        when (val r = manager.create(name, avatar, color, kids, newPin)) {
                            is CreateResult.Ok -> {
                                manager.update(r.profile.copy(subtitleLang = subLang, audioLang = audioLang))
                                onDone()
                            }
                            CreateResult.LimitReached -> message = "Maximum ${ProfileManager.MAX_PROFILES} profiles"
                            CreateResult.InvalidName -> message = "Enter a name"
                            CreateResult.InvalidPin -> message = "PIN must be 4 digits"
                        }
                    } else if (existing != null) {
                        val ok = manager.update(
                            existing.copy(name = name, avatar = avatar, color = color, kids = kids, subtitleLang = subLang, audioLang = audioLang),
                        )
                        if (!ok) { message = "Enter a name"; return@launch }
                        if (newPin != null) manager.setPin(existing.id, newPin) else if (clearPin) manager.setPin(existing.id, null)
                        onDone()
                    }
                }
            }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(contentPadding).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (isNew) "Add profile" else "Edit profile",
                style = MaterialTheme.typography.headlineMedium,
                color = tokens.textPrimary,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = save, modifier = Modifier.semantics { contentDescription = "Save profile" }) {
                Text("Save", color = tokens.accent, style = MaterialTheme.typography.titleMedium)
            }
        }
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            ProfileAvatar(name.ifBlank { "?" }, avatar, color, 96.dp)
        }
        OutlinedTextField(
            value = name,
            onValueChange = { name = it.take(ProfileManager.MAX_NAME) },
            label = { Text("Name") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = tokens.accent, focusedTextColor = tokens.textPrimary, unfocusedTextColor = tokens.textPrimary,
            ),
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Profile name" },
        )
        Section("Avatar")
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Avatars.shapes.forEachIndexed { i, _ ->
                ProfileAvatar(
                    name.ifBlank { "?" }, i, color, 52.dp, selected = i == avatar,
                    modifier = Modifier.clickable { avatar = i }.semantics { contentDescription = "Avatar ${i + 1}" },
                )
            }
        }
        Section("Colour")
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Avatars.colors.forEachIndexed { i, c ->
                Box(
                    Modifier.size(36.dp).clip(CircleShape).background(Color(c))
                        .then(if (c == color) Modifier.border(3.dp, tokens.textPrimary, CircleShape) else Modifier)
                        .clickable { color = c }
                        .semantics { contentDescription = "Colour ${i + 1}" },
                )
            }
        }
        ToggleLine("Kids profile", "Kids-safe: the 18+ section can never be turned on", kids) { kids = it }
        ToggleLine("Profile PIN", if (hasPin) "PIN required to switch to this profile" else "Anyone can open this profile", hasPin) { on ->
            if (on) { pinStep = 1; firstPin = "" } else { newPin = null; clearPin = true }
        }
        Section("Subtitle language")
        LangChips(subLang) { subLang = it }
        Section("Audio language")
        LangChips(audioLang) { audioLang = it }
        message?.let { Text(it, color = tokens.error) }
        Button(
            onClick = save,
            colors = ButtonDefaults.buttonColors(containerColor = tokens.accent, contentColor = tokens.onAccent),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Save") }
        if (!isNew && profiles.size > 1) {
            OutlinedButton(
                onClick = { confirmDelete = true },
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Delete profile" },
            ) { Text("Delete profile", color = tokens.error) }
        }
    }

    if (pinStep > 0) {
        ProfilePinDialog(
            title = if (pinStep == 1) "Set a 4-digit PIN" else "Confirm PIN",
            onDismiss = { pinStep = 0 },
            onPin = { pin ->
                if (pinStep == 1) { firstPin = pin; pinStep = 2; true }
                else if (pin == firstPin) { newPin = pin; clearPin = false; pinStep = 0; true }
                else { pinStep = 1; false }
            },
        )
    }
    if (confirmDelete && existing != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${existing.name}?") },
            text = { Text("Watch history, My List and recent searches of this profile are removed.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch { if (manager.delete(existing.id)) onDone() }
                }) { Text("Delete", color = tokens.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
            containerColor = tokens.surfaceElevated,
        )
    }
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleSmall, color = Cinetor.tokens.textSecondary)
}

@Composable
private fun LangChips(selected: String?, onPick: (String?) -> Unit) {
    val tokens = Cinetor.tokens
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = selected == null, onClick = { onPick(null) }, label = { Text("App default") },
            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = tokens.accent, selectedLabelColor = tokens.onAccent),
        )
        SettingsOptions.LANGUAGES.forEach { (code, label) ->
            FilterChip(
                selected = selected == code, onClick = { onPick(code) }, label = { Text(label) },
                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = tokens.accent, selectedLabelColor = tokens.onAccent),
            )
        }
    }
}

@Composable
private fun ToggleLine(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val tokens = Cinetor.tokens
    Row(
        Modifier.fillMaxWidth().glass(RoundedCornerShape(16.dp), GlassLevel.SURFACE).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = tokens.textPrimary)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = tokens.textSecondary)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = tokens.accent),
            modifier = Modifier.semantics { contentDescription = title },
        )
    }
}
