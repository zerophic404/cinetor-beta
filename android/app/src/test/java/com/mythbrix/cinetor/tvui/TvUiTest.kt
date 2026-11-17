package com.mythbrix.cinetor.tvui

import com.mythbrix.cinetor.core.data.Item
import com.mythbrix.cinetor.core.data.ItemType
import com.mythbrix.cinetor.core.data.RowStyle
import com.mythbrix.cinetor.core.data.Source
import com.mythbrix.cinetor.core.data.SourceKind
import com.mythbrix.cinetor.core.data.Space
import com.mythbrix.cinetor.core.player.ResumeEntry
import com.mythbrix.cinetor.feature.home.RowUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TvUiTest {

    private fun sampleItem(
        id: String = "gw:1",
        title: String = "Night Train",
        altTitle: String? = null,
        type: ItemType = ItemType.MOVIE,
        year: Int = 2025,
        rating: Double = 7.8,
        genres: List<String> = listOf("Action", "Thriller"),
        sources: List<Source> = emptyList(),
    ): Item = Item(
        id = id,
        space = Space.HOME,
        categoryId = "home",
        type = type,
        title = title,
        altTitle = altTitle,
        year = year,
        rating = rating,
        maturity = "PG-13",
        genres = genres,
        synopsis = "A midnight express across the border.",
        poster = "https://img.example/p.jpg",
        backdrop = "https://img.example/b.jpg",
        dominantColor = "#2A0A10",
        accentColor = "#E11D2E",
        sources = sources,
    )

    // ------------------------------------------------------------ Card scale + poster/hero badges

    @Test
    fun focusScale_matchesBeta106f() {
        assertEquals(1.06f, TV_FOCUS_SCALE, 0.0001f)
    }

    @Test
    fun posterBadges_extractRatingDubAnd4k() {
        assertEquals("7.8", tvRatingBadge(7.84))
        assertEquals("9.0", tvRatingBadge(9.0))
        assertNull(tvRatingBadge(0.0))
        assertNull(tvRatingBadge(-2.0))
        assertNull(tvRatingBadge(Double.NaN))

        assertEquals("Hindi", tvDubBadge(sampleItem(title = "Pushpa 2 [Hindi]")))
        assertEquals("Bengali", tvDubBadge(sampleItem(genres = listOf("Drama", "Bengali"))))
        assertEquals("Bengali", tvDubBadge(sampleItem(title = "Byomkesh [Bangla]")))
        assertEquals("Tamil", tvDubBadge(sampleItem(altTitle = "Vikram [Tamil]")))
        assertEquals("Telugu", tvDubBadge(sampleItem(genres = listOf("Action", "[Telugu]"))))
        assertEquals("English", tvDubBadge(sampleItem(title = "Dark Matter [English]")))
        assertNull(tvDubBadge(sampleItem(title = "Plain Title", genres = listOf("Action", "Sci-Fi"))))

        assertTrue(tvIs4k(sampleItem(sources = listOf(Source("https://cdn.example/v.mp4", SourceKind.MP4, height = 2160)))))
        assertTrue(tvIs4k(sampleItem(sources = listOf(Source("https://cdn.example/v.mp4", SourceKind.MP4, label = "4K UHD")))))
        assertTrue(tvIs4k(sampleItem(genres = listOf("Sci-Fi", "4K UHD"))))
        assertFalse(tvIs4k(sampleItem(sources = listOf(Source("https://cdn.example/v.mp4", SourceKind.MP4, height = 1080, label = "1080p")))))
    }

    @Test
    fun watchProgressAndSubtitle_handleEdgeBoundaries() {
        val movie = sampleItem(id = "gw:42", year = 2024, genres = listOf("Sci-Fi", "Drama"))
        assertEquals("2024 · Sci-Fi", tvCardSubtitle(movie))
        assertEquals("2024", tvCardSubtitle(movie.copy(genres = emptyList())))
        assertEquals("Sci-Fi", tvCardSubtitle(movie.copy(year = 0)))
        assertEquals("", tvCardSubtitle(movie.copy(year = 0, genres = emptyList())))

        assertNull(tvWatchProgress(movie, explicitProgress = null, resumeProgress = mapOf("gw:42" to 0.01f)))
        assertNull(tvWatchProgress(movie, explicitProgress = null, resumeProgress = mapOf("gw:42" to 0.98f)))
        assertEquals(0.45f, tvWatchProgress(movie, explicitProgress = null, resumeProgress = mapOf("gw:42" to 0.45f))!!, 0.0001f)
        assertEquals(0.72f, tvWatchProgress(movie, explicitProgress = 0.72f, resumeProgress = emptyMap())!!, 0.0001f)
    }

    // ------------------------------------------------------------ Widescreen Hero sizing + pills

    @Test
    fun heroHeight_keepsFirstContentRowPeekingOn1080pTv() {
        // 1080p TV = 540dp tall; minus 5% top & bottom overscan = 486dp viewport height.
        val viewport1080p = 486f
        val withTabs = tvHeroHeightDp(viewport1080p, showTabs = true)
        val withoutTabs = tvHeroHeightDp(viewport1080p, showTabs = false)

        // Hero stays widescreen and leaves >= 160dp below (tabs + first row header + cards peek).
        assertTrue(withTabs in 208f..268f)
        assertTrue(viewport1080p - withTabs - 56f >= 160f)
        assertTrue(withoutTabs in 220f..288f)
        assertTrue(viewport1080p - withoutTabs - 18f >= 190f)

        // Clamped safely on compact and ultra-tall viewports.
        assertEquals(208f, tvHeroHeightDp(340f, showTabs = true), 0.01f)
        assertEquals(288f, tvHeroHeightDp(800f, showTabs = false), 0.01f)
    }

    @Test
    fun heroMetaPills_include4kRatingDubAndYear() {
        val featured = sampleItem(
            title = "Kalki 2898 AD [Hindi]",
            year = 2024,
            rating = 8.4,
            genres = listOf("Sci-Fi", "Action", "Hindi"),
            sources = listOf(Source("https://cdn.example/4k.mp4", SourceKind.MP4, height = 2160)),
        )
        val pills = tvHeroMetaPills(featured)
        assertEquals(
            listOf(
                TvHeroPill("4K UHD", TvPillKind.ACCENT_4K),
                TvHeroPill("★ 8.4", TvPillKind.RATING),
                TvHeroPill("Hindi", TvPillKind.DUB),
                TvHeroPill("2024", TvPillKind.NEUTRAL),
                TvHeroPill("PG-13", TvPillKind.NEUTRAL),
            ),
            pills,
        )
        assertEquals("Sci-Fi • Action", tvHeroGenreLine(featured))
    }

    // ------------------------------------------------------------ Top-10 charts + endless row paging

    @Test
    fun top10AndContinueRows_deduplicateAndCapRankedChartAtTen() {
        val twelve = (1..12).map { sampleItem(id = "gw:$it", title = "Title $it") }
        val withDup = listOf(twelve[0]) + twelve
        val top10 = RowUi("r-top", "Top 10 Today", RowStyle.TOP10, withDup)
        val ranked = tvRowItems(top10)
        assertEquals(10, ranked.size)
        assertEquals((1..10).map { "gw:$it" }, ranked.map { it.id })

        val resume = listOf(
            ResumeEntry(
                profileId = "default",
                itemId = "gw:99",
                positionMs = 300_000,
                durationMs = 1_000_000,
                updatedAt = 10L,
                space = Space.HOME,
                title = "Resumed Film",
            ),
        )
        val contRow = RowUi("r-cont", "Continue Watching", RowStyle.CONTINUE, listOf(twelve[0]))
        assertEquals(listOf("gw:99", "gw:1"), tvRowItems(contRow, resume).map { it.id })
    }

    @Test
    fun endlessRowPaging_triggersNearRowEndOnlyForPageableStyles() {
        assertFalse(tvShouldLoadMore(index = 4, totalCount = 12, style = RowStyle.POSTER))
        assertTrue(tvShouldLoadMore(index = 8, totalCount = 12, style = RowStyle.POSTER))
        assertTrue(tvShouldLoadMore(index = 9, totalCount = 12, style = RowStyle.LANDSCAPE))
        // Top 10 charts, Continue Watching, Live channels, and tiny rows never page infinitely.
        assertFalse(tvShouldLoadMore(index = 9, totalCount = 10, style = RowStyle.TOP10))
        assertFalse(tvShouldLoadMore(index = 8, totalCount = 10, style = RowStyle.CONTINUE))
        assertFalse(tvShouldLoadMore(index = 8, totalCount = 10, style = RowStyle.CHANNEL))
        assertFalse(tvShouldLoadMore(index = 3, totalCount = 5, style = RowStyle.POSTER))
    }

    // ------------------------------------------------------------ Side-nav groups + FocusMemory restore stability

    @Test
    fun sideNavGroupHeaders_distinguishProfileSpacesAndMore() {
        assertNull(tvNavGroupLabel(TvNavGroup.VIDEO))
        assertEquals("PROFILE · SPACES", tvNavGroupLabel(TvNavGroup.PROFILE))
        assertEquals("MORE", tvNavGroupLabel(TvNavGroup.SYSTEM))
    }

    @Test
    fun focusRestoreGate_skipsScrollJumpDuringInPlaceRowPagination() {
        // Fresh screen mount or back from Details (previousRowIds == null) → restore FocusMemory.
        assertTrue(
            tvShouldRestoreFocusOnRowsChange(
                previousRowIds = null,
                currentRowIds = "r1,r2",
                hasActiveRowFocus = false,
                previousRefocus = 0,
                currentRefocus = 0,
            ),
        )
        // PagedCatalog appends page 2 to r1 while card in r1 is actively focused → keep scroll steady.
        assertFalse(
            tvShouldRestoreFocusOnRowsChange(
                previousRowIds = "r1,r2",
                currentRowIds = "r1,r2",
                hasActiveRowFocus = true,
                previousRefocus = 1,
                currentRefocus = 1,
            ),
        )
        // Category tab switch replaces row IDs → restore focus to new tab.
        assertTrue(
            tvShouldRestoreFocusOnRowsChange(
                previousRowIds = "r1,r2",
                currentRowIds = "s1,s2",
                hasActiveRowFocus = true,
                previousRefocus = 1,
                currentRefocus = 1,
            ),
        )
        // Fullscreen TV player closed (refocus tick bumped) → restore remembered FocusMemory position.
        assertTrue(
            tvShouldRestoreFocusOnRowsChange(
                previousRowIds = "r1,r2",
                currentRowIds = "r1,r2",
                hasActiveRowFocus = false,
                previousRefocus = 1,
                currentRefocus = 2,
            ),
        )
    }

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
        // Spaces moved under the Profile group (phone: Me → Spaces)
        assertEquals(listOf(TvDest.PROFILES, TvDest.LIVE_TV, TvDest.MUSIC, TvDest.COMICS), off.filter { it.group == TvNavGroup.PROFILE }.map { it.dest })
        assertEquals(TvDest.SETTINGS, off.last().dest)
        assertFalse(off.any { it.dest == TvDest.ADULT })
        assertTrue(tvNavEntries(adultEnabled = true).any { it.dest == TvDest.ADULT && it.group == TvNavGroup.PROFILE })
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
        // hero focused last (e.g. Play → player → back) → hero again, rows' columns kept
        memory.save(screen, FocusPos.HERO)
        assertEquals(FocusPos.HERO, memory.resolve(screen, rows))
        assertEquals(5, memory.columnFor(screen, 2))
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
