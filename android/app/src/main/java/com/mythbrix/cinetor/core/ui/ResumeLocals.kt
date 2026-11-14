package com.mythbrix.cinetor.core.ui

import androidx.compose.runtime.compositionLocalOf
import com.mythbrix.cinetor.core.player.ResumeEntry

/** Saved playback positions of the active profile, newest first (Continue row, Details Resume). */
val LocalResumeEntries = compositionLocalOf<List<ResumeEntry>> { emptyList() }
