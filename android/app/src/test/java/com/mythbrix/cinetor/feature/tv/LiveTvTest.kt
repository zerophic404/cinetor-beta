package com.mythbrix.cinetor.feature.tv

import com.mythbrix.cinetor.TestFiles
import com.mythbrix.cinetor.core.data.EventStatus
import com.mythbrix.cinetor.core.data.InMemoryHistoryStorage
import com.mythbrix.cinetor.core.data.ItemType
import com.mythbrix.cinetor.core.data.Program
import com.mythbrix.cinetor.core.data.SearchRepository
import com.mythbrix.cinetor.core.data.Space
import com.mythbrix.cinetor.core.player.DEFAULT_PROFILE
import com.mythbrix.cinetor.core.player.PlaybackPhase
import com.mythbrix.cinetor.core.player.PlayerUiState
import com.mythbrix.cinetor.core.player.ResumeRepository
import com.mythbrix.cinetor.feature.player.HostMode
import com.mythbrix.cinetor.feature.player.PlayerStateHolderTest
import com.mythbrix.cinetor.feature.player.atLiveEdge
import com.mythbrix.cinetor.feature.player.isLiveItem
import com.mythbrix.cinetor.feature.player.testMovie
import com.mythbrix.cinetor.feature.player.zapDeltaForSwipe
import com.mythbrix.cinetor.feature.player.zapTarget
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LiveTvTest {
    private val catalog = TestFiles.catalog()

    private suspend fun channels() = catalog.allItems(Space.TV).filter { it.type == ItemType.CHANNEL }.sortedBy { it.channelNumber }
    private suspend fun events() = catalog.allItems(Space.TV).filter { it.type == ItemType.EVENT }

    @Test
    fun epg_nowLinePosition() {
        val anchor = 1_794_614_400L // 2026-11-14 00:00 UTC
        val now = anchor + 10 * 3600 + 47 * 60 // 10:47
        val start = epgWindowStart(now)
        assertEquals(anchor + 10 * 3600, start) // 10:00 = half-hour slot (10:30) − 30 min look-back
        assertEquals(47 * 4f, epgNowLinePosition(now, start, 4f), 0.001f) // 47 min × 4 dp
        assertEquals(0f, epgNowLinePosition(start - 600, start, 4f), 0.001f) // clamped before window
        // Programme blocks share the same scale: a block starting at now begins exactly at the now-line.
        val ch = testMovie("c").copy(
            type = ItemType.CHANNEL,
            programs = listOf(Program("p1", "News", startOffsetSec = now - anchor, durationSec = 1800)),
        )
        val b = epgBlocks(ch, anchor, start, start + 6 * 3600, 4f).single()
        assertEquals(epgNowLinePosition(now, start, 4f), b.startDp, 0.001f)
        assertEquals(30 * 4f, b.widthDp, 0.001f)
        // Mock clock folds into the 12 h guide window after the anchor.
        assertEquals(anchor + 3600, EpgClock.nowSec(anchor + 13 * 3600, anchor))
        assertEquals(anchor + 3600, EpgClock.nowSec(anchor - 11 * 3600, anchor))
    }

    @Test
    fun nowNext_findsAiringProgramme() = runTest {
        val ch = channels().first()
        val anchor = catalog.file(Space.TV).epgAnchor!!
        val first = ch.programs.minBy { it.startOffsetSec }
        val nn = nowNext(ch, anchor + first.startOffsetSec + first.durationSec / 2, anchor)
        assertEquals(first.id, nn.now?.id)
        assertEquals(0.5f, nn.progress, 0.01f)
        assertTrue(nn.next != null && nn.next!!.startOffsetSec > first.startOffsetSec)
    }

    @Test
    fun zap_nextChannelReusesPlayer() = runTest {
        val lineup = channels()
        val e = PlayerStateHolderTest.Env(this)
        e.holder.playChannel(lineup[0], lineup)
        runCurrent()
        val player = e.holder.player.value
        assertTrue(e.holder.state.value.live)
        assertEquals(lineup.size, e.holder.state.value.channels.size)

        e.holder.zap(1)
        runCurrent()
        assertEquals(listOf(lineup[0].id, lineup[1].id), e.fake.loads.map { it.first })
        assertEquals(1, e.providerCalls) // ONE player instance for every channel
        assertSame(player, e.holder.player.value)
        assertEquals(lineup[1].id, e.holder.state.value.item?.id)
        assertEquals(HostMode.FULL, e.holder.state.value.mode)
        assertTrue(e.fake.lastPlayWhenReady)

        // Down from the first channel wraps to the last; number entry jumps; lineup survives zapping.
        e.holder.zap(-1)
        e.holder.zap(-1)
        runCurrent()
        assertEquals(lineup.last().id, e.fake.currentKey)
        e.holder.channelDigit(1)
        e.holder.channelDigit(0)
        e.holder.channelDigit(3)
        assertEquals("103", e.holder.state.value.channelInput)
        advanceTimeBy(1_600)
        runCurrent()
        assertEquals(lineup.first { it.channelNumber == 103 }.id, e.fake.currentKey)
        assertNull(e.holder.state.value.channelInput)
        assertEquals(1, e.providerCalls)
        assertEquals(lineup.size, e.holder.state.value.channels.size)
    }

    @Test
    fun zapMath_wrapsAndSwipeDirection() = runTest {
        val l = channels().take(3)
        assertEquals(l[1].id, zapTarget(l, l[0].id, 1)?.id)
        assertEquals(l[2].id, zapTarget(l, l[0].id, -1)?.id)
        assertNull(zapTarget(l.take(1), l[0].id, 1))
        assertEquals(1, zapDeltaForSwipe(-200f, 100f))
        assertEquals(-1, zapDeltaForSwipe(200f, 100f))
        assertEquals(0, zapDeltaForSwipe(40f, 100f))
        assertTrue(atLiveEdge(PlayerUiState(isLive = true, seekable = false)))
        assertFalse(atLiveEdge(PlayerUiState(isLive = true, seekable = true, durationMs = 600_000, positionMs = 100_000)))
    }

    @Test
    fun liveItems_notInGlobalSearch() = runTest {
        val search = SearchRepository(catalog)
        val tvItems = catalog.allItems(Space.TV)
        assertTrue(tvItems.size >= 40)
        for (item in tvItems) {
            assertTrue(search.searchInSpace(Space.TV, item.title).any { it.id == item.id })
            assertTrue("${item.title} leaked", search.search(item.title).none { it.space == Space.TV })
        }
        // Live playback never feeds resume / continue watching.
        val store = ResumeRepository(InMemoryHistoryStorage())
        val e = PlayerStateHolderTest.Env(this, store)
        val lineup = channels()
        e.holder.playChannel(lineup[0], lineup)
        runCurrent()
        e.fake.mutableState.update { it.copy(positionMs = 90_000, durationMs = 600_000, isPlaying = true, phase = PlaybackPhase.READY) }
        advanceTimeBy(6_000)
        runCurrent()
        e.holder.saveNow()
        e.holder.pause()
        runCurrent()
        assertTrue(store.observe(DEFAULT_PROFILE).first().isEmpty())
        // Live events count as live only while on air.
        val ev = events()
        assertTrue(ev.filter { it.status == EventStatus.LIVE }.all(::isLiveItem))
        assertTrue(ev.filter { it.status != EventStatus.LIVE }.none(::isLiveItem))
    }

    @Test
    fun sports_tabsFilterByStatus() = runTest {
        val ev = events()
        val live = eventsFor(SportsTab.LIVE, ev)
        val upcoming = eventsFor(SportsTab.UPCOMING, ev)
        val results = eventsFor(SportsTab.RESULTS, ev)
        assertEquals(3, live.size)
        assertEquals(4, upcoming.size)
        assertEquals(3, results.size)
        assertTrue(live.all { it.status == EventStatus.LIVE })
        assertTrue(upcoming.all { it.status == EventStatus.UPCOMING })
        assertTrue(results.all { it.status == EventStatus.FINISHED })
        assertEquals(ev.size, (live + upcoming + results).map { it.id }.toSet().size)
        // Upcoming soonest first, results most recent first.
        assertEquals(upcoming.map { it.startOffsetSec }, upcoming.map { it.startOffsetSec }.sortedBy { it })
        assertEquals(results.map { it.startOffsetSec }, results.map { it.startOffsetSec }.sortedByDescending { it })
        // Reminder fires 5 min before kick-off in wall-clock time.
        val anchor = 1_000_000L
        val nowGuide = anchor
        val at = reminderAtMs(upcoming.first(), nowGuide, anchor, realNowMs = 0L)
        assertEquals((upcoming.first().startOffsetSec!! - 300) * 1000L, at)
        assertEquals("2h 00m", formatCountdown(7200))
    }

    @Test
    fun liveGrid_filtersByGroupGenreFavorites() = runTest {
        val ch = channels()
        assertEquals(listOf("News", "Entertainment", "Kids", "Movies", "Music", "Sports"), channelGroups(ch))
        assertEquals(5, filterChannels(ch, "News", null, false, emptySet()).size)
        val fav = setOf(ch[0].id, ch[7].id)
        assertEquals(fav, filterChannels(ch, null, null, true, fav).map { it.id }.toSet())
        assertTrue(filterChannels(ch, null, "Kids", false, emptySet()).all { "Kids" in it.genres })
    }
}
