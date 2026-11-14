package com.mythbrix.cinetor.feature.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import com.mythbrix.cinetor.design.Cinetor
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Funnels tint requests from card press / row scroll into the app tint, debounced [DEBOUNCE_MS]
 * so flinging through a row doesn't thrash the background colour. Hero page changes call
 * [immediate] (one change per 5 s anyway).
 */
@Stable
class TintFeeder(private val apply: (Color) -> Unit) {
    private val requests = MutableSharedFlow<Color>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    fun offer(color: Color) {
        requests.tryEmit(color)
    }

    fun immediate(color: Color) = apply(color)

    @OptIn(FlowPreview::class)
    suspend fun run() {
        requests.debounce(DEBOUNCE_MS).distinctUntilChanged().collect { apply(it) }
    }

    companion object {
        const val DEBOUNCE_MS = 150L
    }
}

@Composable
fun rememberTintFeeder(): TintFeeder {
    val tint = Cinetor.tint
    val feeder = remember(tint) { TintFeeder { tint.setSource(it) } }
    LaunchedEffect(feeder) { feeder.run() }
    return feeder
}
