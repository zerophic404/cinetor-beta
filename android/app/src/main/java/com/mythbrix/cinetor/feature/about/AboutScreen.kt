package com.mythbrix.cinetor.feature.about

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mythbrix.cinetor.design.Cinetor
import com.mythbrix.cinetor.design.CinetorMark

/** Placeholder — full About (links, credits) is Task 5.3. */
@Composable
fun AboutScreen(contentPadding: PaddingValues) {
    Column(
        Modifier.fillMaxSize().padding(contentPadding).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CinetorMark(Modifier.size(96.dp))
        Text("Cinetor", style = MaterialTheme.typography.headlineMedium, color = Cinetor.tokens.textPrimary)
        Text("Developed By Mythbrix", color = Cinetor.tokens.textSecondary)
    }
}
