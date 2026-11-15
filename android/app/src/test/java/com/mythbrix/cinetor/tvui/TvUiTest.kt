package com.mythbrix.cinetor.tvui

import com.mythbrix.cinetor.core.data.Space
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TvUiTest {

    // ------------------------------------------------------------ D-pad grid + side nav

    @Test
    fun dpad_navigatesRowsAndNav() {
        val rows = listOf(10, 0, 4, 6) // row 1 empty (skipped)
        val mem = FocusMemory()
        val col: (Int) -> Int? = { mem.columnFor("s", it) }
        // Right / Left inside a row
        assertEquals(GridMove.To(FocusPos(0, 3)), TvGrid.move(FocusPos(0, 2), Dpad.RIGHT, rows, col))
        assertEquals(GridMove.To(FocusPos(0, 1)), TvGrid.move(FocusPos(0, 2), Dpad.LEFT, rows, col))
        // Left at the first card → side nav; Right at the last card → stay (no jump)
        assertEquals(GridMove.ExitLeft, TvGrid.move(FocusPos(0, 0), Dpad.LEFT, rows, col))
        assertEquals(GridMove.Stay, TvGrid.move(FocusPos(0, 9), Dpad.RIGHT, rows, col))
        // Down skips the empty row and clamps the column to the shorter row
        assertEquals(GridMove.To(FocusPos(2, 3)), TvGrid.move(FocusPos(0, 7), Dpad.DOWN, rows, col))
        // Up from the first row → hero / tabs; Down from the last row → stay
        assertEquals(GridMove.ExitUp, TvGrid.move(FocusPos(0, 4), Dpad.UP, rows, col))
        assertEquals(GridMove.Stay, TvGrid.move(FocusPos(3, 1), Dpad.DOWN, rows, col))
        // Up/Down return to the column last focused in the target row
        mem.save("s", FocusPos(0, 6))
        mem.save("s", FocusPos(2, 1))
        assertEquals(GridMove.To(FocusPos(0, 6)), TvGrid.move(FocusPos(2, 1), Dpad.UP, rows, col))
        assertEquals(GridMove.To(FocusPos(3, 1)), TvGrid.move(FocusPos(2, 1), Dpad.DOWN, rows, col))
    }

    @Test
    fun sideNav_hasVideoSpacesIsolatedGroupAndSettings() {
        val off = tvNavEntries(adultEnabled = false)
        assertEquals(
            listOf(TvDest.SEARCH, TvDest.HOME, TvDest.ANIME, TvDest.DRAMA),
            off.filter { it.group == TvNavGroup.VIDEO }.map { it.dest },
        )
        assertEquals(listOf(TvDest.LIVE_TV, TvDest.MUSIC, TvDest.COMICS), off.filter { it.group == TvNavGroup.SPACES }.map { it.dest })
        assertEquals(TvDest.SETTINGS, off.last().dest)
        assertFalse(off.any { it.dest == TvDest.ADULT })
        assertTrue(tvNavEntries(adultEnabled = true).any { it.dest == TvDest.ADULT && it.group == TvNavGroup.SPACES })
        // Inside an isolated space, search is the in-space search (isolation)
        assertEquals("Search Music", tvNavEntries(false, Space.MUSIC).first().label)
        assertEquals("Search", tvNavEntries(false, Space.HOME).first().label)
        assertEquals(Space.TV, TvDest.LIVE_TV.space())
    }

    // ------------------------------------------------------------ FocusMemory (Review Focus #5)

    @Test
    fun returnFromDetails_restoresFocusedCard() {
        val memory = FocusMemory()
        val screen = tvScreenKey(Space.HOME)
        val rows = listOf(12, 8, 20)
        // user walks to row 2, card 5 then opens Details (TV home leaves composition)
        memory.save(screen, FocusPos(0, 3))
        memory.save(screen, FocusPos(2, 5))
        // back from Details → the same card, not the top of the screen
        assertEquals(FocusPos(2, 5), memory.resolve(screen, rows))
        // other screens untouched
        assertNull(memory.resolve(tvScreenKey(Space.ANIME), rows))
        // row reload with fewer items → clamped to the last card of that row
        assertEquals(FocusPos(2, 2), memory.resolve(screen, listOf(12, 8, 3)))
        // row disappeared → last non-empty row
        assertEquals(FocusPos(1, 5), memory.resolve(screen, listOf(12, 8)))
        assertEquals(FocusPos(0, 5), memory.resolve(screen, listOf(12, 0, 0)))
        // no content → caller falls back to its default focus (hero Play)
        assertNull(memory.resolve(screen, emptyList()))
        memory.clear(screen)
        assertNull(memory.resolve(screen, rows))
    }

    // ------------------------------------------------------------ TV player keys

    private class Clock(var now: Long = 1_000) { fun read() = now }

    @Test
    fun tvPlayer_rightKeySeeks10s() {
        val clock = Clock()
        val keys = TvPlayerKeys(clock::read)
        val a = keys.onKeyDown(TvKey.RIGHT, 0, TvOverlay.NONE, positionMs = 60_000, durationMs = 600_000)
        assertEquals(TvPlayerAction.Scrub(70_000, commit = true), a)
        assertEquals(TvPlayerAction.Consumed, keys.onKeyUp(TvKey.RIGHT)) // tap already committed
        assertEquals(TvPlayerAction.Scrub(50_000, commit = true), keys.onKeyDown(TvKey.LEFT, 0, TvOverlay.NONE, 60_000, 600_000))
        keys.onKeyUp(TvKey.LEFT)
        // clamped at both ends
        assertEquals(TvPlayerAction.Scrub(0, commit = true), keys.onKeyDown(TvKey.LEFT, 0, TvOverlay.NONE, 4_000, 600_000))
        keys.onKeyUp(TvKey.LEFT)
        assertEquals(TvPlayerAction.Scrub(600_000, commit = true), keys.onKeyDown(TvKey.RIGHT, 0, TvOverlay.NONE, 595_000, 600_000))
        keys.onKeyUp(TvKey.RIGHT)
        // other keys with the overlay hidden
        assertEquals(TvPlayerAction.TogglePlay, keys.onKeyDown(TvKey.CENTER, 0, TvOverlay.NONE, 0, 1))
        assertEquals(TvPlayerAction.ShowControls(focusSettings = true), keys.onKeyDown(TvKey.UP, 0, TvOverlay.NONE, 0, 1))
        assertEquals(TvPlayerAction.OpenRail, keys.onKeyDown(TvKey.DOWN, 0, TvOverlay.NONE, 0, 1))
        assertEquals(TvPlayerAction.Exit, keys.onKeyDown(TvKey.BACK, 0, TvOverlay.NONE, 0, 1))
        // overlay shown: arrows move focus, Back hides first
        assertEquals(TvPlayerAction.PassThrough, keys.onKeyDown(TvKey.RIGHT, 0, TvOverlay.CONTROLS, 0, 1))
        assertEquals(TvPlayerAction.Hide, keys.onKeyDown(TvKey.BACK, 0, TvOverlay.RAIL, 0, 1))
        // media keys always; live: up/down zap (pass through), no seeking
        assertEquals(TvPlayerAction.TogglePlay, keys.onKeyDown(TvKey.PLAY_PAUSE, 0, TvOverlay.CONTROLS, 0, 1))
        assertEquals(TvPlayerAction.OpenSettings, keys.onKeyDown(TvKey.MENU, 0, TvOverlay.NONE, 0, 1))
        assertEquals(TvPlayerAction.PassThrough, keys.onKeyDown(TvKey.UP, 0, TvOverlay.NONE, 0, 1, seekable = false, live = true))
        assertEquals(TvPlayerAction.PassThrough, keys.onKeyDown(TvKey.RIGHT, 0, TvOverlay.NONE, 0, 1, seekable = false, live = true))
    }

    @Test
    fun holdAcceleratesScrub() {
        val clock = Clock()
        val keys = TvPlayerKeys(clock::read)
        var target = (keys.onKeyDown(TvKey.RIGHT, 0, TvOverlay.NONE, 0, 3_600_000) as TvPlayerAction.Scrub).targetMs
        assertEquals(10_000, target)
        val steps = ArrayList<Long>()
        // key repeats every 50 ms for 8 s
        for (r in 1..160) {
            clock.now += 50
            val a = keys.onKeyDown(TvKey.RIGHT, r, TvOverlay.NONE, 0, 3_600_000) as TvPlayerAction.Scrub
            assertFalse(a.commit) // preview only while held
            steps += a.targetMs - target
            target = a.targetMs
        }
        // steps never shrink and end much bigger than at the start
        assertTrue(steps.zipWithNext().all { (a, b) -> b >= a })
        assertEquals(500L, steps.first()) // 10 s/s × 50 ms
        assertEquals(6_000L, steps.last()) // 120 s/s × 50 ms
        // 8 s held covers far more than 8 s × 10 s/s linear scrubbing
        assertTrue(target > 10_000 + 80_000 * 3)
        // release commits the accumulated target
        assertEquals(TvPlayerAction.SeekTo(target), keys.onKeyUp(TvKey.RIGHT))
        assertNull(keys.scrubber.target)
        assertEquals(10_000L, TvScrubber.speedPerSec(0))
        assertEquals(120_000L, TvScrubber.speedPerSec(10_000))
    }

    @Test
    fun keyCodes_mapToTvKeys() {
        assertEquals(TvKey.UP, tvKeyOf(19))
        assertEquals(TvKey.CENTER, tvKeyOf(23))
        assertEquals(TvKey.CENTER, tvKeyOf(66))
        assertEquals(TvKey.MENU, tvKeyOf(82))
        assertEquals(TvKey.PLAY_PAUSE, tvKeyOf(85))
        assertEquals(TvKey.OTHER, tvKeyOf(7)) // digit 0 → live TV channel entry (MainActivity)
    }
}
