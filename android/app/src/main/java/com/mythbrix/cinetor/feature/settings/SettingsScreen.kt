package com.mythbrix.cinetor.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mythbrix.cinetor.design.Cinetor

/** Placeholder — Task 5.2. */
@Composable
fun SettingsScreen(contentPadding: PaddingValues) {
    Column(Modifier.fillMaxSize().padding(contentPadding).padding(16.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium, color = Cinetor.tokens.textPrimary)
        Text("App & profile settings arrive in Phase 5.", color = Cinetor.tokens.textSecondary)
    }
}
