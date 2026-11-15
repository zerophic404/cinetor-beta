package com.mythbrix.cinetor.tvui

import com.mythbrix.cinetor.core.data.Space

/** Remote keys the TV layer cares about (Android key codes mapped in [tvKeyOf]). */
enum class TvKey {
    UP, DOWN, LEFT, RIGHT, CENTER, BACK, MENU,
    PLAY_PAUSE, PLAY, PAUSE, FAST_FORWARD, REWIND, NEXT, PREVIOUS, OTHER,
}

/** android.view.KeyEvent codes → [TvKey] (constants inlined so this stays JVM-testable). */
fun tvKeyOf(keyCode: Int): TvKey = when (keyCode) {
    19 -> TvKey.UP // DPAD_UP
    20 -> TvKey.DOWN
    21 -> TvKey.LEFT
    22 -> TvKey.RIGHT
    23, 66, 160 -> TvKey.CENTER // DPAD_CENTER, ENTER, NUMPAD_ENTER
    4 -> TvKey.BACK
    82 -> TvKey.MENU
    85 -> TvKey.PLAY_PAUSE // MEDIA_PLAY_PAUSE
    126 -> TvKey.PLAY
    127 -> TvKey.PAUSE
    90 -> TvKey.FAST_FORWARD
    89 -> TvKey.REWIND
    87 -> TvKey.NEXT
    88 -> TvKey.PREVIOUS
    else -> TvKey.OTHER
}

// ---------------------------------------------------------------- side nav model

enum class TvDest { SEARCH, HOME, ANIME, DRAMA, LIVE_TV, MUSIC, COMICS, ADULT, DOWNLOADS, LIBRARY, PROFILES, SETTINGS }

enum class TvNavGroup { VIDEO, SPACES, SYSTEM }

data class TvNavEntry(val dest: TvDest, val label: String, val group: TvNavGroup)

/**
 * Side nav: video spaces (+ search) · isolated spaces group · system. Adult only when the gate
 * allows it (enabled + not a Kids profile). Search is in-space while inside an isolated space.
 */
fun tvNavEntries(adultEnabled: Boolean, activeSpace: Space? = null): List<TvNavEntry> = buildList {
    val searchLabel = if (activeSpace != null && !activeSpace.isVideo) "Search ${activeSpace.displayName}" else "Search"
    add(TvNavEntry(TvDest.SEARCH, searchLabel, TvNavGroup.VIDEO))
    add(TvNavEntry(TvDest.HOME, "Home", TvNavGroup.VIDEO))
    add(TvNavEntry(TvDest.ANIME, "Anime", TvNavGroup.VIDEO))
    add(TvNavEntry(TvDest.DRAMA, "Drama", TvNavGroup.VIDEO))
    add(TvNavEntry(TvDest.LIVE_TV, "Live TV", TvNavGroup.SPACES))
    add(TvNavEntry(TvDest.MUSIC, "Music", TvNavGroup.SPACES))
    add(TvNavEntry(TvDest.COMICS, "Comics", TvNavGroup.SPACES))
    if (adultEnabled) add(TvNavEntry(TvDest.ADULT, "Adult", TvNavGroup.SPACES))
    add(TvNavEntry(TvDest.DOWNLOADS, "Downloads", TvNavGroup.SYSTEM))
    add(TvNavEntry(TvDest.LIBRARY, "Library", TvNavGroup.SYSTEM))
    add(TvNavEntry(TvDest.PROFILES, "Profiles", TvNavGroup.SYSTEM))
    add(TvNavEntry(TvDest.SETTINGS, "Settings", TvNavGroup.SYSTEM))
}

fun TvDest.space(): Space? = when (this) {
    TvDest.HOME -> Space.HOME
    TvDest.ANIME -> Space.ANIME
    TvDest.DRAMA -> Space.DRAMA
    TvDest.LIVE_TV -> Space.TV
    TvDest.MUSIC -> Space.MUSIC
    TvDest.COMICS -> Space.COMICS
    TvDest.ADULT -> Space.ADULT
    else -> null
}

// ---------------------------------------------------------------- TV player keys

/**
 * Left/Right scrubbing: a tap seeks ±10 s; holding (key repeats) accelerates by hold time
 * (rate-independent): 10 s/s for the first second, then 30, 60, 120 s/s. The target is
 * accumulated while held and committed on release. Pure, fake-clock testable.
 */
class TvScrubber(private val clock: () -> Long) {
    var target: Long? = null
        private set
    private var dir = 0
    private var pressedAt = 0L
    private var lastAt = 0L
    private var duration = 0L
    var repeats = 0
        private set

    /** Key down (repeat 0): jump ±[TAP_MS] from [positionMs]. Returns the new target. */
    fun press(direction: Int, positionMs: Long, durationMs: Long): Long {
        dir = if (direction >= 0) 1 else -1
        duration = durationMs
        val now = clock()
        pressedAt = now
        lastAt = now
        repeats = 0
        val t = clamp(positionMs + dir * TAP_MS)
        target = t
        return t
    }

    /** Key repeat while held: advance by speed(heldTime) × elapsed. */
    fun repeat(): Long {
        val base = target ?: return 0
        val now = clock()
        val dt = (now - lastAt).coerceAtLeast(0)
        lastAt = now
        repeats++
        val step = speedPerSec(now - pressedAt) * dt / 1_000
        val t = clamp(base + dir * step)
        target = t
        return t
    }

    /** Key up: final target (null if nothing pressed); resets. */
    fun release(): Long? {
        val t = target
        target = null
        dir = 0
        repeats = 0
        return t
    }

    private fun clamp(ms: Long): Long = if (duration > 0) ms.coerceIn(0, duration) else ms.coerceAtLeast(0)

    companion object {
        const val TAP_MS = 10_000L

        /** Scrub speed (ms of media per second held) for a key held [heldMs]. */
        fun speedPerSec(heldMs: Long): Long = when {
            heldMs < 1_000 -> 10_000
            heldMs < 3_000 -> 30_000
            heldMs < 6_000 -> 60_000
            else -> 120_000
        }
    }
}

/** What overlay the TV player shows (decides whether arrows seek or move focus). */
enum class TvOverlay { NONE, CONTROLS, RAIL }

sealed interface TvPlayerAction {
    /** Scrub preview to [targetMs]; [commit] = seek now (tap), else only preview while held. */
    data class Scrub(val targetMs: Long, val commit: Boolean) : TvPlayerAction
    data class SeekTo(val targetMs: Long) : TvPlayerAction
    data object TogglePlay : TvPlayerAction
    data object Play : TvPlayerAction
    data object Pause : TvPlayerAction
    /** Show controls; [focusSettings] = Up (settings row) instead of play/pause. */
    data class ShowControls(val focusSettings: Boolean = false) : TvPlayerAction
    data object OpenRail : TvPlayerAction
    data object OpenSettings : TvPlayerAction
    data object Hide : TvPlayerAction
    data object Exit : TvPlayerAction
    data object Next : TvPlayerAction
    /** Not handled here (focus navigation / live zapping in MainActivity). */
    data object PassThrough : TvPlayerAction
    /** Handled, nothing to do (e.g. a repeat while not scrubbing). */
    data object Consumed : TvPlayerAction
}

/**
 * TV player remote mapping (no gestures). Overlay hidden: Left/Right ±10 s (hold = accelerating
 * scrub), OK = play/pause (+ controls), Up = controls with the settings row, Down = episode/related
 * rail, Back = exit. Overlay shown: arrows/OK move focus, Back hides. Media keys always work.
 * Live: Up/Down pass through (channel zapping) and no scrubbing on non-seekable streams.
 */
class TvPlayerKeys(clock: () -> Long = System::currentTimeMillis) {
    val scrubber = TvScrubber(clock)
    private var scrubKey: TvKey? = null

    fun onKeyDown(
        key: TvKey,
        repeatCount: Int,
        overlay: TvOverlay,
        positionMs: Long,
        durationMs: Long,
        seekable: Boolean = true,
        live: Boolean = false,
    ): TvPlayerAction {
        when (key) {
            TvKey.PLAY_PAUSE -> return if (repeatCount == 0) TvPlayerAction.TogglePlay else TvPlayerAction.Consumed
            TvKey.PLAY -> return TvPlayerAction.Play
            TvKey.PAUSE -> return TvPlayerAction.Pause
            TvKey.NEXT -> return if (live) TvPlayerAction.PassThrough else TvPlayerAction.Next
            TvKey.MENU -> return TvPlayerAction.OpenSettings
            TvKey.FAST_FORWARD, TvKey.REWIND -> {
                if (!seekable) return TvPlayerAction.Consumed
                return scrub(key, if (key == TvKey.FAST_FORWARD) 1 else -1, repeatCount, positionMs, durationMs)
            }
            else -> Unit
        }
        if (overlay != TvOverlay.NONE) {
            return if (key == TvKey.BACK) TvPlayerAction.Hide else TvPlayerAction.PassThrough
        }
        return when (key) {
            TvKey.LEFT, TvKey.RIGHT ->
                if (!seekable) TvPlayerAction.PassThrough
                else scrub(key, if (key == TvKey.RIGHT) 1 else -1, repeatCount, positionMs, durationMs)
            TvKey.CENTER -> if (repeatCount == 0) TvPlayerAction.TogglePlay else TvPlayerAction.Consumed
            TvKey.UP -> if (live) TvPlayerAction.PassThrough else TvPlayerAction.ShowControls(focusSettings = true)
            TvKey.DOWN -> if (live) TvPlayerAction.PassThrough else TvPlayerAction.OpenRail
            TvKey.BACK -> TvPlayerAction.Exit
            else -> TvPlayerAction.PassThrough
        }
    }

    /** Key up: a held scrub commits its accumulated target. */
    fun onKeyUp(key: TvKey): TvPlayerAction {
        if (key != scrubKey) return TvPlayerAction.PassThrough
        scrubKey = null
        val held = scrubber.repeats > 0
        val t = scrubber.release() ?: return TvPlayerAction.Consumed
        return if (held) TvPlayerAction.SeekTo(t) else TvPlayerAction.Consumed
    }

    private fun scrub(key: TvKey, dir: Int, repeatCount: Int, positionMs: Long, durationMs: Long): TvPlayerAction {
        if (repeatCount == 0 || scrubKey != key) {
            scrubKey = key
            return TvPlayerAction.Scrub(scrubber.press(dir, positionMs, durationMs), commit = true)
        }
        return TvPlayerAction.Scrub(scrubber.repeat(), commit = false)
    }
}
