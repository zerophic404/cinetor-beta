package com.mythbrix.cinetor.feature.music

import com.mythbrix.cinetor.TestFiles
import com.mythbrix.cinetor.core.data.Item
import com.mythbrix.cinetor.core.data.ItemType
import com.mythbrix.cinetor.core.data.Space
import com.mythbrix.cinetor.core.player.PlaybackPhase
import com.mythbrix.cinetor.feature.player.HostMode
import com.mythbrix.cinetor.feature.player.PlayerStateHolderTest
import com.mythbrix.cinetor.feature.player.testMovie
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

@OptIn(ExperimentalCoroutinesApi::class)
class MusicTest {
    private fun track(id: String) = testMovie(id).copy(space = Space.MUSIC, type = ItemType.TRACK, durationSec = 200)
    private val tracks = (1..5).map { track("t$it") }

    /** Video holder + music controller sharing ONE preloader/player, wired like AppContainer. */
    private class Env(scope: TestScope) {
        val base = PlayerStateHolderTest.Env(scope)
        lateinit var music: MusicController
        val video = com.mythbrix.cinetor.feature.player.PlayerStateHolder(
            scope = scope.backgroundScope,
            preloader = base.pre,
            audioHandler = { music.handles(it) },
            beforePlay = { music.yieldToVideo() },
        )

        init {
            music = MusicController(
                scope = scope.backgroundScope,
                preloader = base.pre,
                takeOver = { if (video.state.value.item != null) video.close() },
            )
        }
    }

    @Test
    fun queue_reorder() {
        val q = MusicQueue(tracks, index = 2) // playing t3
        val moved = q.move(4, 0) // drag t5 to the top
        assertEquals(listOf("t5", "t1", "t2", "t3", "t4"), moved.items.map { it.id })
        assertEquals("t3", moved.current?.id) // current keeps playing
        assertEquals(3, moved.index)
        val cur = moved.move(3, 4) // drag the current track down
        assertEquals("t3", cur.current?.id)
        assertEquals(4, cur.index)
        val up = q.move(0, 4) // move before-current past it
        assertEquals("t3", up.current?.id)
        assertEquals(1, up.index)
        assertEquals(q, q.move(1, 1))
        assertEquals(q, q.move(9, 0))
        // Remove (never the current), up-next view.
        val removed = q.removeAt(0)
        assertEquals(listOf("t2", "t3", "t4", "t5"), removed.items.map { it.id })
        assertEquals("t3", removed.current?.id)
        assertEquals(q, q.removeAt(2))
        assertEquals(listOf("t4", "t5"), q.upNext.map { it.id })
    }

    @Test
    fun shuffle_repeat_logic() {
        val q = MusicQueue(tracks, index = 1)
        val sh = q.toggleShuffle(Random(7))
        assertTrue(sh.shuffle)
        assertEquals("t2", sh.current?.id)
        assertEquals(0, sh.index)
        assertEquals(tracks.map { it.id }.toSet(), sh.items.map { it.id }.toSet())
        val off = sh.next(auto = false)!!.toggleShuffle()
        assertFalse(off.shuffle)
        assertEquals(tracks.map { it.id }, off.items.map { it.id }) // original order back
        assertEquals(sh.items[1].id, off.current?.id) // current kept

        // Repeat OFF: end of queue stops; ALL wraps; ONE replays on auto-advance but skips on manual next.
        val last = MusicQueue(tracks, index = 4)
        assertNull(last.next(auto = true))
        assertEquals(RepeatMode.ALL, last.cycleRepeat().repeat)
        assertEquals(0, last.cycleRepeat().next(auto = true)?.index)
        val one = last.cycleRepeat().cycleRepeat()
        assertEquals(RepeatMode.ONE, one.repeat)
        assertEquals(4, one.next(auto = true)?.index)
        assertEquals(0, one.next(auto = false)?.index)
        assertEquals(RepeatMode.OFF, one.cycleRepeat().repeat)
        assertEquals(0, MusicQueue(tracks, 0).previous().index)
        assertEquals(4, MusicQueue(tracks, 0, repeat = RepeatMode.ALL).previous().index)
        // Shuffle while reordering keeps the unshuffled order untouched.
        assertEquals(sh.original, sh.move(1, 3).original)
    }

    @Test
    fun music_backgroundContinuesOnNavigate() = runTest {
        val e = Env(this)
        val fake = e.base.fake
        e.music.playQueue(tracks, 0)
        runCurrent()
        fake.setPhase(PlaybackPhase.READY)
        assertEquals("t1", fake.currentKey)
        assertTrue(fake.state.value.isPlaying)

        // Navigating (Details of a video warm + leave, screens disposed) never touches music.
        val movie = testMovie("m")
        e.base.pre.warm(movie)
        runCurrent()
        e.base.pre.cancel(movie.id)
        runCurrent()
        e.music.collapse()
        e.music.expand()
        assertEquals(listOf("t1"), fake.loads.map { it.first })
        assertEquals(0, fake.stops)
        assertTrue(fake.state.value.isPlaying)
        assertTrue(e.music.state.value.active)

        // App to background: video would pause (no setting), music keeps playing.
        assertFalse(shouldPauseOnBackground(videoActive = false, musicActive = true, backgroundPlay = false, inPip = false))
        assertTrue(shouldPauseOnBackground(videoActive = true, musicActive = false, backgroundPlay = false, inPip = false))
        assertFalse(shouldPauseOnBackground(videoActive = true, musicActive = false, backgroundPlay = true, inPip = false))

        // Track ends while in background → next track starts on the same player.
        fake.setPhase(PlaybackPhase.ENDED)
        runCurrent()
        assertEquals("t2", fake.currentKey)
        assertTrue(fake.lastPlayWhenReady)
        assertEquals(1, e.base.providerCalls)
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals("t2", e.music.state.value.current?.id)
    }

    @Test
    fun music_and_video_neverTwoAudios() = runTest {
        val e = Env(this)
        val fake = e.base.fake
        e.music.playQueue(tracks, 0)
        runCurrent()
        // Video Play takes the one player; music yields (queue dropped, bar hidden).
        e.video.play(testMovie("v"))
        runCurrent()
        assertEquals("v", fake.currentKey)
        assertFalse(e.music.state.value.active)
        assertEquals(HostMode.FULL, e.video.state.value.mode)
        // Music again closes the video player first.
        e.music.playQueue(tracks, 2)
        runCurrent()
        assertEquals("t3", fake.currentKey)
        assertNull(e.video.state.value.item)
        assertEquals(HostMode.HIDDEN, e.video.state.value.mode)
        // A track played through the video path (Details / hero Play) is redirected to music.
        e.video.play(tracks[4])
        runCurrent()
        assertNull(e.video.state.value.item)
        assertEquals(1, e.base.providerCalls)
    }

    @Test
    fun library_albumContextAndArtists() = runTest {
        val repo = MusicRepository(TestFiles.catalog())
        val d = repo.load()
        assertEquals(10, d.albums.size)
        assertEquals(50, d.tracks.size)
        val album = d.albums.first()
        val list = d.tracksOf(album)
        assertEquals(album.trackIds, list.map(Item::id))
        val (ctx, i) = repo.contextFor(list[2])
        assertEquals(list.map(Item::id), ctx.map(Item::id))
        assertEquals(2, i)
        assertTrue(d.artists.isNotEmpty())
        assertEquals(d.tracks.size, d.artists.sumOf { it.tracks.size })
    }
}
