package com.mythbrix.cinetor

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mythbrix.cinetor.core.data.AppContainer
import com.mythbrix.cinetor.core.perf.EffectsQuality
import com.mythbrix.cinetor.core.perf.detectTier
import com.mythbrix.cinetor.design.AppTheme
import com.mythbrix.cinetor.design.CinetorTheme
import com.mythbrix.cinetor.feature.splash.AnimatedSplash
import com.mythbrix.cinetor.navigation.CinetorNavHost

/** Theme + tier from DataStore (applied live, no restart) → shell with the intro on top. */
@Composable
fun CinetorRoot(container: AppContainer) {
    val theme by container.settings.theme.collectAsStateWithLifecycle(initialValue = AppTheme.NOIR)
    val effects by container.settings.effects.collectAsStateWithLifecycle(initialValue = EffectsQuality.AUTO)
    val tier = remember(effects) { detectTier(container.deviceProfile, effects.toTierOrNull()) }
    CinetorTheme(theme = theme, tier = tier) {
        var showIntro by rememberSaveable { mutableStateOf(true) }
        Box(Modifier.fillMaxSize()) {
            // Shell composes underneath so catalog loading/skeletons start during the intro.
            CinetorNavHost(container)
            if (showIntro) AnimatedSplash(onFinished = { showIntro = false })
        }
    }
}
