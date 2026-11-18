package com.mythbrix.cinetor.tvui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mythbrix.cinetor.design.Cinetor
import com.mythbrix.cinetor.design.CinetorTokens
import kotlinx.coroutines.delay

/** Incremented when the TV player closes; TV screens re-run their focus restore on change. */
val LocalTvRefocus = androidx.compose.runtime.compositionLocalOf { 0 }

/** Current NavHost entry key inside [TvShell], allowing sub-screens to observe route changes. */
val LocalTvRouteKey = androidx.compose.runtime.compositionLocalOf<Any?> { null }

/** Incremented whenever the user picks an item in [TvSideNav] so sub-stacks (e.g. TvDetails) reset cleanly. */
val LocalTvNavPickTick = androidx.compose.runtime.compositionLocalOf { 0 }

/** Overscan-safe margin: 5% of each screen edge (TV panels crop the outer area). */
fun overscanMargin(sizeDp: Dp): Dp = sizeDp * 0.05f

/**
 * 10-foot shell: 5% overscan margins, Liquid Glass [TvSideNav] on the left (collapsed icons;
 * expands smoothly over a dimmed content backdrop while focused), content on the right.
 * Menu key → [onMenu] (Settings). D-pad with nothing focused → enters content cleanly.
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
    var navPickTick by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val prefs = Cinetor.prefs
    val tier = Cinetor.tier
    val scrimAlpha by animateFloatAsState(
        targetValue = if (navHasFocus) 0.48f else 0f,
        animationSpec = tween(if (prefs.reduceMotion) 0 else tier.scaled(CinetorTokens.Motion.mediumMs)),
        label = "tvNavBackdropScrim",
    )

    LaunchedEffect(routeKey, refocusKey) {
        val waits = longArrayOf(260L, 320L, 400L, 450L)
        for (waitMs in waits) {
            delay(waitMs)
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
            ) {
                CompositionLocalProvider(
                    LocalTvRouteKey provides routeKey,
                    LocalTvNavPickTick provides navPickTick,
                ) {
                    content()
                }
            }
            if (scrimAlpha > 0.01f) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .drawBehind {
                            drawRect(
                                Brush.horizontalGradient(
                                    0f to Color.Black.copy(alpha = scrimAlpha),
                                    0.45f to Color.Black.copy(alpha = scrimAlpha * 0.55f),
                                    1f to Color.Transparent,
                                ),
                            )
                        },
                )
            }
            TvSideNav(
                entries = entries,
                selected = selected,
                onPick = { dest ->
                    navPickTick++
                    onPick(dest)
                },
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .onFocusChanged { navHasFocus = it.hasFocus },
            )
        }
    }
}
