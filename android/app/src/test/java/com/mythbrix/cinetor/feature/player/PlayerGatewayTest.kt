package com.mythbrix.cinetor.feature.player

import com.mythbrix.cinetor.core.data.Item
import com.mythbrix.cinetor.core.data.Source
import com.mythbrix.cinetor.core.data.SourceKind
import com.mythbrix.cinetor.core.player.FakeCinetorPlayer
import com.mythbrix.cinetor.core.player.MediaPlaybackPreloader
import com.mythbrix.cinetor.core.player.PlaybackPhase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Gateway playback in the player holder: prepare-on-play, quality = sources, 403 → re-resolve → proxy, no source. */
@OptIn(ExperimentalCoroutinesApi::class)
class PlayerGatewayTest {
    private fun gwMovie(id: String = "gw:1") = testMovie(id).copy(sources = emptyList(), slug = "slug-1")

    private fun sources(tag: String) = listOf(
        Source("https://cdn.test/$tag-1080.mp4", SourceKind.MP4, "1080p · MP4", mapOf("Referer" to "https://movieboxhd.net/"), height = 1080, sizeBytes = 10),
        Source("https://cdn.test/$tag-480.mp4", SourceKind.MP4, "480p · MP4", mapOf("Referer" to "https://movieboxhd.net/"), height = 480, sizeBytes = 5),
    )

    private class Env(scope: TestScope, val resolveTo: (Int) -> List<Source>?) {
        val fake = FakeCinetorPlayer()
        val prepares = mutableListOf<Boolean>()
        var fallbacks = 0
        val loaded = mutableListOf<String>()
        val pre = MediaPlaybackPreloader(
            scope = scope.backgroundScope,
            playerProvider = { fake },
            prepare = { item, _ -> resolveTo(0)?.let { item.copy(sources = it) } ?: item },
        )
        val holder = PlayerStateHolder(
            scope = scope.backgroundScope,
            preloader = pre,
            needsPrepare = { it.id.startsWith("gw:") },
            prepare = { item, _, fresh ->
                prepares += fresh
                resolveTo(prepares.size)?.let { item.copy(sources = it) } ?: item.copy(sources = emptyList())
            },
            fallbackFor = { item, _ ->
                fallbacks++
                item.copy(sources = listOf(Source("https://antiblock-gateway.vercel.app/api/public/v1/stream/1/play", SourceKind.MP4, "Gateway proxy")))
            },
        )
    }

    @Test
    fun play_preparesSources_qualityMenuListsResolutions_switchKeepsPosition() = runTest {
        val e = Env(this) { sources("a") }
        e.holder.play(gwMovie())
        runCurrent()
        val st = e.holder.state.value
        assertNull(st.notice)
        assertEquals(listOf(1080, 480), st.sources.map { it.height })
        assertEquals("gw:1", e.fake.currentKey)
        e.fake.mutableState.update { it.copy(positionMs = 42_000) }
        e.holder.selectSource(1)
        assertEquals(1, e.holder.state.value.sourceIndex)
        assertEquals("gw:1" to 42_000L, e.fake.loads.last())
    }

    @Test
    fun error403_reResolvesOnce_thenFallsBackToProxy() = runTest {
        val e = Env(this) { sources("t$it") }
        e.holder.play(gwMovie())
        runCurrent()
        e.fake.mutableState.update { it.copy(positionMs = 30_000) }
        val loadsBefore = e.fake.loads.size
        e.fake.setPhase(PlaybackPhase.IDLE, error = "bad http status") // 403 on the signed link
        runCurrent()
        assertEquals(listOf(false, true), e.holder.let { e.prepares }) // initial + one fresh re-resolve
        assertEquals(loadsBefore + 1, e.fake.loads.size)
        assertEquals(30_000L, e.fake.loads.last().second) // same position
        assertEquals(1, e.holder.recoveries)
        // still failing → gateway proxy (direct_play_url), then give up (no loop)
        e.fake.setPhase(PlaybackPhase.IDLE, error = "bad http status")
        runCurrent()
        assertEquals(1, e.fallbacks)
        assertEquals(2, e.holder.recoveries)
        e.fake.setPhase(PlaybackPhase.IDLE, error = "bad http status")
        runCurrent()
        assertEquals(2, e.holder.recoveries)
    }

    @Test
    fun noSource_showsFriendlyNotice_insteadOfSpinner() = runTest {
        val e = Env(this) { null }
        e.holder.play(gwMovie())
        runCurrent()
        val st = e.holder.state.value
        assertEquals("No source available", st.notice)
        assertEquals(HostMode.FULL, st.mode)
        assertNull(e.holder.player.value)
        assertTrue(e.fake.loads.isEmpty())
    }

    @Test
    fun nonGatewayItems_skipPrepare() = runTest {
        val e = Env(this) { sources("x") }
        e.holder.play(testMovie("mock-1"))
        runCurrent()
        assertTrue(e.prepares.isEmpty())
        assertEquals("mock-1", e.fake.currentKey)
    }

    @Suppress("unused")
    private fun Item.unused() = this
}
