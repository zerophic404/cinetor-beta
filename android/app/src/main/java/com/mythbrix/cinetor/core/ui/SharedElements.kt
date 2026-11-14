package com.mythbrix.cinetor.core.ui

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import com.mythbrix.cinetor.core.perf.PerformanceTier
import com.mythbrix.cinetor.design.Cinetor

/** Provided by the shell around the NavHost (card poster → details poster). */
@OptIn(ExperimentalSharedTransitionApi::class)
val LocalSharedTransitionScope = compositionLocalOf<SharedTransitionScope?> { null }

/** Provided per NavHost destination (its enter/exit AnimatedVisibilityScope). */
val LocalNavAnimatedScope = compositionLocalOf<AnimatedVisibilityScope?> { null }

/** Shared-element key of a poster opened from a given row/hero. */
fun posterKey(origin: String, itemId: String): String = "poster:$origin:$itemId"

/**
 * Marks a poster as a shared element. No-op on LOW tier, without a key, or outside the shell
 * (previews/tests) → the plain NavHost fade/scale is used instead.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.sharedPoster(key: String?): Modifier {
    val shared = LocalSharedTransitionScope.current
    val anim = LocalNavAnimatedScope.current
    if (key.isNullOrEmpty() || shared == null || anim == null || Cinetor.tier == PerformanceTier.LOW) return this
    return with(shared) {
        this@sharedPoster.sharedElement(rememberSharedContentState(key), anim)
    }
}
