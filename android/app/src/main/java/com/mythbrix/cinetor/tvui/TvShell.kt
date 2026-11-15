package com.mythbrix.cinetor.tvui

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** Incremented when the TV player closes; TV screens re-run their focus restore on change. */
val LocalTvRefocus = androidx.compose.runtime.compositionLocalOf { 0 }

/** Overscan-safe margin: 5% of each screen edge (TV panels crop the outer area). */
fun overscanMargin(sizeDp: Dp): Dp = sizeDp * 0.05f

/**
 * 10-foot shell: 5% overscan margins, glass [TvSideNav] on the left (collapsed icons; expands over
 * the content while focused), content on the right. Menu key → [onMenu] (settings).
 * D-pad with nothing focused (fresh screen) → focus enters the content.
 */
@Composable
fun TvShell(
    entries: List<TvNavEntry>,
    selected: TvDest?,
    routeKey: Any?,
    /** Bumped when the full-screen player closes → focus returns to the content. */
    refocusKey: Any? = null,
    onPick: (TvDest) -> Unit,
    onMenu: () -> Unit,
    content: @Composable () -> Unit,
) {
    val contentFocus = remember { FocusRequester() }
    var contentHasFocus by remember { mutableStateOf(false) }
    var navHasFocus by remember { mutableStateOf(false) }
    // New destination: if nothing claimed focus (screens without their own initial focus), enter content.
    // (Screens with their own focus restore — TV home — claim focus first; this is the fallback.)
    // Retried: during the nav transition the outgoing screen is still composed (its cards can grab
    // focus and then vanish, which drops focus into the side nav).
    LaunchedEffect(routeKey, refocusKey) {
        repeat(4) {
            delay(400)
            if (!contentHasFocus) runCatching { contentFocus.requestFocus() }
        }
    }
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (tvKeyOf(e.nativeKeyEvent.keyCode)) {
                    TvKey.MENU -> { onMenu(); true }
                    TvKey.UP, TvKey.DOWN, TvKey.LEFT, TvKey.RIGHT, TvKey.CENTER -> {
                        if (!contentHasFocus && !navHasFocus) {
                            runCatching { contentFocus.requestFocus() }
                            true
                        } else {
                            false
                        }
                    }
                    else -> false
                }
            },
    ) {
        val h = overscanMargin(maxWidth)
        val v = overscanMargin(maxHeight)
        Box(Modifier.fillMaxSize().padding(horizontal = h, vertical = v)) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(start = TvNavCollapsed + 24.dp)
                    .onFocusChanged { contentHasFocus = it.hasFocus }
                    .focusRequester(contentFocus)
                    .focusGroup(),
            ) { content() }
            TvSideNav(
                entries = entries,
                selected = selected,
                onPick = onPick,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .onFocusChanged { navHasFocus = it.hasFocus },
            )
        }
    }
}
