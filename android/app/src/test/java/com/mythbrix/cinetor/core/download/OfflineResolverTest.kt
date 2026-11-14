package com.mythbrix.cinetor.core.download

import com.mythbrix.cinetor.core.data.ItemType
import com.mythbrix.cinetor.core.data.SourceKind
import com.mythbrix.cinetor.core.player.PlaybackPlan
import com.mythbrix.cinetor.core.player.SourceResolver
import com.mythbrix.cinetor.core.player.StreamType
import com.mythbrix.cinetor.core.player.SubPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineResolverTest {
    private fun rec(
        id: String,
        itemId: String,
        episodeId: String? = null,
        kind: DownloadKind = DownloadKind.PROGRESSIVE,
        status: DownloadStatus = DownloadStatus.COMPLETED,
        path: String = "/data/files/downloads/$id.mp4",
        sidecars: String = "",
    ) = DownloadRecord(
        id = id, itemId = itemId, episodeId = episodeId, title = "T", subtitle = episodeId?.let { "S2:E5 · Pilot" },
        kind = kind, url = "https://cdn.example/$id" + if (kind == DownloadKind.HLS) ".m3u8" else ".mp4",
        mimeType = "video/mp4", filePath = path, container = "mp4", status = status, sidecarSubs = sidecars,
    )

    @Test
    fun downloadedItem_resolvesToLocalSource() {
        val r = rec("m1", "m1")
        val completed = mapOf(r.id to r)
        val plan = OfflineResolver.resolve(completed, "m1", null) { it == r.filePath }
        assertEquals(OfflinePlan.LocalFile(r.filePath, "video/mp4"), plan)
    }

    @Test
    fun downloadedEpisode_hls_resolvesToDownloadCache() {
        val r = rec(downloadIdFor("s1", "e5"), "s1", "e5", kind = DownloadKind.HLS, path = "")
        val plan = OfflineResolver.resolve(mapOf(r.id to r), "s1", "e5") { false }
        assertEquals(OfflinePlan.Cached(r.id, DownloadKind.HLS), plan)
        // Other episode of the same show is not downloaded → online.
        assertNull(OfflineResolver.resolve(mapOf(r.id to r), "s1", "e6") { true })
    }

    @Test
    fun missingFileOrIncomplete_fallsBackToOnline() {
        val gone = rec("m1", "m1")
        assertNull(OfflineResolver.resolve(mapOf(gone.id to gone), "m1", null) { false })
        val partial = rec("m2", "m2", status = DownloadStatus.PAUSED)
        assertNull(OfflineResolver.resolve(mapOf(partial.id to partial), "m2", null) { true })
        assertNull(OfflineResolver.resolve(emptyMap(), "m3", null) { true })
    }

    @Test
    fun sidecarSubs_resolvedNextToFile_andOnlineSubsDropped() {
        val r = rec("m1", "m1", path = "/d/Movie.mp4", sidecars = "en|https://x/subs/en.vtt\nfr|https://x/fr.srt")
        val plan = OfflineResolver.resolve(mapOf(r.id to r), "m1", null) { it != "/d/Movie.fr.srt" } as OfflinePlan.LocalFile
        assertEquals(listOf(SidecarSub("en", "/d/Movie.en.vtt", "vtt")), plan.sidecars)
        val p = PlaybackPlan(
            url = r.url, type = StreamType.PROGRESSIVE, headers = emptyMap(),
            subtitles = listOf(
                SubPlan("asset:///mock/subs/a.vtt", "text/vtt", "en", "English"),
                SubPlan("https://remote/b.vtt", "text/vtt", "es", "Español"),
            ),
        )
        val subs = SourceResolver.offlineSubs(p, plan.sidecars)
        assertEquals(listOf("asset:///mock/subs/a.vtt", "file:///d/Movie.en.vtt"), subs.map { it.url })
    }

    @Test
    fun playableItem_fromEpisodeRecord_resolvesSameEpisode() {
        val r = rec(downloadIdFor("s1", "e5"), "s1", "e5", kind = DownloadKind.HLS)
        val item = OfflineResolver.playableItem(r)
        assertEquals(ItemType.SERIES, item.type)
        val req = SourceResolver.request(item, "e5")!!
        assertEquals("e5", req.episodeId)
        assertEquals(StreamType.HLS, req.plan.type)
        assertEquals(2, item.seasons.single().n)
        assertTrue(req.subtitle!!.startsWith("S2:E5"))
        val movie = OfflineResolver.playableItem(rec("m1", "m1"))
        assertEquals(SourceKind.MP4, movie.sources.single().kind)
        assertEquals("m1", SourceResolver.request(movie, null)!!.key)
    }
}
