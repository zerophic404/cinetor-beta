package com.mythbrix.cinetor.feature.downloads

import com.mythbrix.cinetor.core.data.Episode
import com.mythbrix.cinetor.core.data.Item
import com.mythbrix.cinetor.core.data.ItemType
import com.mythbrix.cinetor.core.data.Season
import com.mythbrix.cinetor.core.data.Source
import com.mythbrix.cinetor.core.data.SourceKind
import com.mythbrix.cinetor.core.data.Space
import com.mythbrix.cinetor.core.download.DownloadChoice
import com.mythbrix.cinetor.core.download.DownloadKind
import com.mythbrix.cinetor.core.download.DownloadStatus
import com.mythbrix.cinetor.core.download.OfflineResolver
import com.mythbrix.cinetor.core.download.buildRecord
import com.mythbrix.cinetor.core.download.formatBatchEpisodeLabel
import com.mythbrix.cinetor.core.download.formatEpisodeCode
import com.mythbrix.cinetor.core.perf.PerformanceTier
import com.mythbrix.cinetor.feature.details.canAutoplayInlinePreview
import com.mythbrix.cinetor.feature.details.displayGenres
import com.mythbrix.cinetor.feature.details.extractDubBadges
import com.mythbrix.cinetor.feature.details.extractPreviewYouTubeId
import com.mythbrix.cinetor.feature.details.hasUltraHdSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BatchSelectionTest {

    private fun ep(season: Int, num: Int, sources: List<Source> = emptyList()) = Episode(
        id = "s${season}e$num",
        n = num,
        title = "Episode $num",
        synopsis = "Synopsis S${season}E$num",
        durationSec = 2400,
        still = "https://img.example/s${season}e$num.jpg",
        sources = sources,
    )

    private fun season(num: Int, epCount: Int) = Season(
        n = num,
        title = "Season $num",
        episodes = (1..epCount).map { ep(num, it) },
    )

    @Test
    fun switchingSeasons_preservesCheckedEpisodesAndFormatsSeasonBadges() {
        val seasons = listOf(season(1, 8), season(2, 10), season(3, 6))
        val holder = BatchSelection(seasons = seasons, initialSeasonNumber = 1, startEmpty = true)

        assertEquals(1, holder.state.value.activeSeasonNumber)
        assertEquals(0, holder.state.value.totalSelectedCount)
        assertEquals("S1 (0/8)", holder.state.value.seasonBadgeLabel(1))

        // Select 4 episodes in Season 1
        listOf(1, 2, 5, 8).forEach { n -> holder.toggleEpisode(1, "s1e$n") }
        assertEquals(4, holder.state.value.selectedCountInSeason(1))
        assertEquals("S1 (4/8)", holder.state.value.seasonBadgeLabel(1))

        // Switch to Season 2 and select all 10 episodes individually
        holder.selectSeasonTab(2)
        assertEquals(2, holder.state.value.activeSeasonNumber)
        (1..10).forEach { n -> holder.toggleEpisode(2, "s2e$n") }

        // Switching back to Season 1 and then Season 3 preserves selections across ALL seasons
        holder.selectSeasonTab(1)
        assertEquals(1, holder.state.value.activeSeasonNumber)
        assertEquals("S1 (4/8)", holder.state.value.seasonBadgeLabel(1))
        assertEquals("S2 (10/10)", holder.state.value.seasonBadgeLabel(2))
        assertEquals("S3 (0/6)", holder.state.value.seasonBadgeLabel(3))
        assertEquals(14, holder.state.value.totalSelectedCount)
        assertTrue(holder.state.value.isSelected(1, "s1e5"))
        assertFalse(holder.state.value.isSelected(1, "s1e3"))
        assertTrue(holder.state.value.isSelected(2, "s2e10"))
    }

    @Test
    fun selectSeason_andUnselectSeason_onlyAffectTargetSeason() {
        val seasons = listOf(season(1, 4), season(2, 5), season(3, 3))
        val holder = BatchSelection(seasons = seasons, initialSeasonNumber = 1, startEmpty = true)

        holder.selectSeason(1)
        holder.selectSeason(3)
        assertTrue(holder.state.value.isSeasonFullySelected(1))
        assertFalse(holder.state.value.isSeasonFullySelected(2))
        assertTrue(holder.state.value.isSeasonFullySelected(3))
        assertEquals(7, holder.state.value.totalSelectedCount)

        // Unselecting Season 1 keeps Season 3 intact
        holder.unselectSeason(1)
        assertFalse(holder.state.value.isSeasonFullySelected(1))
        assertEquals(0, holder.state.value.selectedCountInSeason(1))
        assertEquals(3, holder.state.value.selectedCountInSeason(3))
        assertEquals("S1 (0/4)", holder.state.value.seasonBadgeLabel(1))
        assertEquals("S3 (3/3)", holder.state.value.seasonBadgeLabel(3))

        // toggleSeasonAll on partially-selected Season 2 selects all 5, then unselects all 5
        holder.toggleEpisode(2, "s2e2")
        assertFalse(holder.state.value.isSeasonFullySelected(2))
        holder.toggleSeasonAll(2)
        assertTrue(holder.state.value.isSeasonFullySelected(2))
        assertEquals(5, holder.state.value.selectedCountInSeason(2))
        holder.toggleSeasonAll(2)
        assertEquals(0, holder.state.value.selectedCountInSeason(2))
        assertEquals(3, holder.state.value.selectedCountInSeason(3))
    }

    @Test
    fun selectAllSeasons_andClearAll_preserveCanonicalEpisodeOrder() {
        val seasons = listOf(season(1, 3), season(2, 4), season(3, 2))
        val holder = BatchSelection(seasons = seasons, initialSeasonNumber = 2, startEmpty = true)

        // Toggle episodes out of order across seasons
        holder.toggleEpisode(3, "s3e2")
        holder.toggleEpisode(1, "s1e3")
        holder.toggleEpisode(1, "s1e1")
        holder.toggleEpisode(2, "s2e4")

        assertEquals(
            listOf(1 to 1, 1 to 3, 2 to 4, 3 to 2),
            holder.state.value.selectedEpisodes().map { it.seasonNumber to it.episode.n },
        )
        assertFalse(holder.state.value.isAllSeasonsSelected)

        // Select all seasons -> all 9 episodes selected in S1..S3 order
        holder.selectAllSeasons()
        assertTrue(holder.state.value.isAllSeasonsSelected)
        assertEquals(9, holder.state.value.totalSelectedCount)
        assertEquals(9, holder.state.value.selectedEpisodes().size)
        assertEquals(1 to 1, holder.state.value.selectedEpisodes().first().let { it.seasonNumber to it.episode.n })
        assertEquals(3 to 2, holder.state.value.selectedEpisodes().last().let { it.seasonNumber to it.episode.n })

        // Clear all -> 0 selected everywhere
        holder.clearAll()
        assertFalse(holder.state.value.isAllSeasonsSelected)
        assertEquals(0, holder.state.value.totalSelectedCount)
        assertTrue(holder.state.value.selectedEpisodes().isEmpty())
    }

    @Test
    fun initialSelection_preselectsRemainingEpisodesOfActiveSeason() {
        val seasons = listOf(season(1, 4), season(2, 5))
        val completed = setOf(
            BatchEpisodeKey(2, "s2e1"),
            BatchEpisodeKey(2, "s2e2"),
            BatchEpisodeKey(2, "s2e3"),
        )
        val holder = BatchSelection(
            seasons = seasons,
            initialSeasonNumber = 2,
            startEmpty = false,
            completedEpisodeKeys = completed,
        )
        assertEquals(2, holder.state.value.activeSeasonNumber)
        assertEquals(
            listOf("s2e4", "s2e5"),
            holder.state.value.selectedEpisodes().map { it.episode.id },
        )
    }

    @Test
    fun qualityMatching_picksClosestNonDeadDedupedStreamAcrossSeasons() {
        val s1Sources = listOf(
            Source("https://cdn.primary.test/s1e1-1080.mp4", SourceKind.MP4, "1080p · MP4", height = 1080, sizeBytes = 900_000_000),
            Source("https://cdn.mirror.test/s1e1-1080.mp4", SourceKind.MP4, "1080p · MP4", height = 1080, sizeBytes = 900_000_000),
            Source("https://cdn.primary.test/s1e1-720.mp4", SourceKind.MP4, "720p · MP4", height = 720, sizeBytes = 450_000_000),
            Source("https://cdn.primary.test/s1e1-480.mp4", SourceKind.MP4, "480p · MP4", height = 480, sizeBytes = 220_000_000),
            Source("https://cdn.primary.test/s1e1-360.mp4", SourceKind.MP4, "360p · MP4", height = 360, sizeBytes = 120_000_000),
        )
        // Deduped 1080p picks primary URL, not mirror
        val pick1080 = pickBestQualityForBatch(s1Sources, targetHeight = BatchQuality.P1080.height)
        assertNotNull(pick1080)
        assertEquals(1080, pick1080!!.height)
        assertEquals("https://cdn.primary.test/s1e1-1080.mp4", pick1080.source.url)
        assertEquals(DownloadKind.PROGRESSIVE, pick1080.kind)

        // Exact 720p and 360p matches
        assertEquals(720, pickBestQualityForBatch(s1Sources, targetHeight = BatchQuality.P720.height)?.height)
        assertEquals(360, pickBestQualityForBatch(s1Sources, targetHeight = BatchQuality.P360.height)?.height)

        // Season 2 episode missing 720p: [1080p, 480p] -> 480p is closer to 720p (240 vs 360)
        val s2Sources = listOf(
            Source("https://cdn.test/s2e1-1080.mp4", SourceKind.MP4, "1080p · MP4", height = 1080),
            Source("https://cdn.test/s2e1-480.mp4", SourceKind.MP4, "480p · MP4", height = 480),
        )
        assertEquals(480, pickBestQualityForBatch(s2Sources, targetHeight = 720)?.height)

        // Season 3 episode with dead 720p URL + YouTube embed URL + RTSP live + valid 1080p MP4
        val deadUrl = "https://cdn.test/dead-720.mp4"
        val s3Sources = listOf(
            Source(deadUrl, SourceKind.MP4, "720p · MP4", height = 720),
            Source("https://www.youtube-nocookie.com/embed/dQw4w9WgXcQ", SourceKind.MP4, "YouTube · HD", height = 720),
            Source("https://cdn.test/poster.jpg", SourceKind.MP4, "720p", height = 720),
            Source("rtsp://live.test/ch1", SourceKind.RTSP, "Live"),
            Source("https://cdn.test/s3e1-1080.mp4", SourceKind.MP4, "1080p · MP4", height = 1080),
        )
        val pickS3 = pickBestQualityForBatch(s3Sources, targetHeight = 720, deadUrls = setOf(deadUrl))
        assertNotNull(pickS3)
        assertEquals("https://cdn.test/s3e1-1080.mp4", pickS3!!.source.url)

        // Untagged HLS fallback still works (DownloadKind.HLS)
        val hlsOnly = listOf(Source("https://cdn.test/master.m3u8", SourceKind.HLS, "HLS"))
        val pickHls = pickBestQualityForBatch(hlsOnly, targetHeight = 720)
        assertNotNull(pickHls)
        assertEquals(DownloadKind.HLS, pickHls!!.kind)

        // Completely unusable sources -> null
        assertNull(pickBestQualityForBatch(listOf(Source("https://youtu.be/abc12345678", SourceKind.MP4)), targetHeight = 720))
    }

    @Test
    fun episodeFormatting_buildsCleanTitleS01E02LabelsAndPreservesOfflinePlayback() {
        assertEquals("S01E02", formatEpisodeCode(1, 2))
        assertEquals("S12E105", formatEpisodeCode(12, 105))
        assertEquals("Severance · S02E04", formatBatchEpisodeLabel("Severance", 2, 4))

        val series = Item(
            id = "gw:sev",
            space = Space.HOME,
            categoryId = "c",
            type = ItemType.SERIES,
            title = "Severance",
            year = 2022,
            rating = 8.7,
            maturity = "TV-MA",
            genres = listOf("Sci-Fi", "Thriller"),
            synopsis = "Work-life balance.",
            poster = "p",
            backdrop = "b",
            dominantColor = "#112233",
            accentColor = "#E50914",
        )
        val ep2 = ep(1, 2).copy(title = "Half Loop")
        val opt = pickBestQualityForBatch(
            listOf(Source("https://cdn.test/sev-s1e2.mp4", SourceKind.MP4, "1080p · MP4", height = 1080, sizeBytes = 500_000_000)),
            targetHeight = 1080,
        )!!
        val record = buildRecord(
            item = series,
            episode = ep2,
            seasonNumber = 1,
            choice = DownloadChoice(opt, connections = 4),
            fileFor = { id, _, ext -> "/files/$id.$ext" },
            mediaCacheDir = "/cache",
        )
        assertEquals("Severance · S01E02", record.title)
        assertEquals("S01E02 · Half Loop", record.subtitle)

        // OfflineResolver reconstructs the exact series title, season 1, episode 2
        val completed = record.copy(status = DownloadStatus.COMPLETED)
        val playable = OfflineResolver.playableItem(completed)
        assertEquals("Severance", playable.title)
        assertEquals(1, playable.seasons.single().n)
        assertEquals(2, playable.seasons.single().episodes.single().n)
        assertEquals("Half Loop", playable.seasons.single().episodes.single().title)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun sequentialBatchEnqueue_resolvesAcrossSeasonsSkipsFailuresAndFormatsSnackbar() = runTest {
        val seasons = listOf(season(1, 2), season(2, 2))
        val series = Item(
            id = "gw:dark",
            space = Space.HOME,
            categoryId = "c",
            type = ItemType.SERIES,
            title = "Dark",
            year = 2017,
            rating = 8.8,
            maturity = "16+",
            genres = listOf("Sci-Fi"),
            synopsis = "Time loops.",
            poster = "p",
            backdrop = "b",
            dominantColor = "#112233",
            accentColor = "#E50914",
            seasons = seasons,
        )
        val selection = BatchSelection(seasons = seasons, initialSeasonNumber = 1, startEmpty = true, initialQuality = BatchQuality.P720)
        selection.selectAllSeasons()

        val resolvedCalls = mutableListOf<String>()
        val queuedLabels = mutableListOf<Pair<String, Int?>>()
        val progressEvents = mutableListOf<BatchResolveProgress>()

        val result = enqueueBatchEpisodes(
            item = series,
            episodes = selection.state.value.selectedEpisodes(),
            targetHeight = selection.state.value.preferredQuality.height,
            connections = 4,
            resolveSources = { _, se, episode ->
                resolvedCalls += "S${se}E${episode.n}"
                if (se == 1 && episode.n == 2) {
                    throw java.io.IOException("Upstream timeout")
                }
                listOf(
                    Source("https://cdn.test/${episode.id}-1080.mp4", SourceKind.MP4, "1080p · MP4", height = 1080),
                    Source("https://cdn.test/${episode.id}-720.mp4", SourceKind.MP4, "720p · MP4", height = 720),
                )
            },
            enqueue = { itemArg, episodeArg, seArg, choice ->
                queuedLabels += formatBatchEpisodeLabel(itemArg.title, seArg, episodeArg.n) to choice.quality.height
            },
            onProgress = { progressEvents += it },
        )

        assertEquals(listOf("S1E1", "S1E2", "S2E1", "S2E2"), resolvedCalls)
        assertEquals(3, result.queuedCount)
        assertEquals(1, result.skippedCount)
        assertEquals(
            listOf(
                "Dark · S01E01" to 720,
                "Dark · S02E01" to 720,
                "Dark · S02E02" to 720,
            ),
            queuedLabels,
        )
        assertEquals(4, progressEvents.size)
        assertEquals("Dark · S01E01", progressEvents.first().currentEpisodeLabel)
        assertEquals("Queued 3 episodes for download", batchQueuedSnackbarMessage(result.queuedCount))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun sequentialBatchEnqueue_supportsCleanCancellationMidResolve() = runTest {
        val seasons = listOf(season(1, 4))
        val series = Item(
            id = "gw:andor",
            space = Space.HOME,
            categoryId = "c",
            type = ItemType.SERIES,
            title = "Andor",
            year = 2022,
            rating = 8.4,
            maturity = "13+",
            genres = listOf("Sci-Fi"),
            synopsis = "Rebellion.",
            poster = "p",
            backdrop = "b",
            dominantColor = "#112233",
            accentColor = "#E50914",
            seasons = seasons,
        )
        val ep2Gate = CompletableDeferred<Unit>()
        val resolvedIds = mutableListOf<String>()
        val queuedIds = mutableListOf<String>()

        val job = launch {
            enqueueBatchEpisodes(
                item = series,
                episodes = seasons.single().episodes.map { SelectedEpisodeRef(1, it) },
                targetHeight = 1080,
                connections = 4,
                resolveSources = { _, _, episode ->
                    resolvedIds += episode.id
                    if (episode.n == 2) ep2Gate.await()
                    listOf(Source("https://cdn.test/${episode.id}.mp4", SourceKind.MP4, "1080p · MP4", height = 1080))
                },
                enqueue = { _, episode, _, _ ->
                    queuedIds += episode.id
                },
            )
        }

        runCurrent()
        // Episode 1 finished and queued; Episode 2 is currently awaiting resolution
        assertEquals(listOf("s1e1", "s1e2"), resolvedIds)
        assertEquals(listOf("s1e1"), queuedIds)

        // Cancel mid-resolve! Episodes 2, 3, and 4 must never be queued
        job.cancel()
        runCurrent()
        assertTrue(job.isCancelled)
        assertEquals(listOf("s1e1", "s1e2"), resolvedIds)
        assertEquals(listOf("s1e1"), queuedIds)
    }

    @Test
    fun detailsMetadataAndInlinePreview_detect4kDubsAndRespectLowTierAndDataSaver() {
        val item = Item(
            id = "gw:rrr",
            space = Space.HOME,
            categoryId = "c",
            type = ItemType.MOVIE,
            title = "RRR [Hindi] [Bengali]",
            altTitle = "Dual Audio",
            year = 2022,
            rating = 8.0,
            maturity = "16+",
            genres = listOf("Action", "[Tamil]", "Telugu", "Drama"),
            synopsis = "Epic.",
            poster = "p",
            backdrop = "b",
            dominantColor = "#112233",
            accentColor = "#E50914",
            sources = listOf(Source("https://cdn.test/rrr-2160.mp4", SourceKind.MP4, "2160p · MP4", height = 2160)),
        )
        assertEquals(listOf("Dual Audio", "Hindi", "Bengali", "Tamil", "Telugu").take(4), extractDubBadges(item))
        assertEquals(listOf("Action", "Drama"), displayGenres(item))
        assertTrue(hasUltraHdSource(item))
        assertFalse(hasUltraHdSource(item.copy(sources = listOf(Source("https://cdn.test/rrr-1080.mp4", SourceKind.MP4, height = 1080)))))

        // Inline preview respects PerformanceTier.LOW, dataSaver, reduceMotion, bannerAutoplay, and TV
        assertTrue(canAutoplayInlinePreview(PerformanceTier.MID, dataSaver = false, bannerAutoplay = true, reduceMotion = false, isTv = false, playerActive = false))
        assertTrue(canAutoplayInlinePreview(PerformanceTier.HIGH, dataSaver = false, bannerAutoplay = true, reduceMotion = false, isTv = false, playerActive = false))
        assertFalse(canAutoplayInlinePreview(PerformanceTier.LOW, dataSaver = false, bannerAutoplay = true, reduceMotion = false, isTv = false, playerActive = false))
        assertFalse(canAutoplayInlinePreview(PerformanceTier.HIGH, dataSaver = true, bannerAutoplay = true, reduceMotion = false, isTv = false, playerActive = false))
        assertFalse(canAutoplayInlinePreview(PerformanceTier.HIGH, dataSaver = false, bannerAutoplay = false, reduceMotion = false, isTv = false, playerActive = false))
        assertFalse(canAutoplayInlinePreview(PerformanceTier.HIGH, dataSaver = false, bannerAutoplay = true, reduceMotion = true, isTv = false, playerActive = false))
        assertFalse(canAutoplayInlinePreview(PerformanceTier.HIGH, dataSaver = false, bannerAutoplay = true, reduceMotion = false, isTv = true, playerActive = false))
        assertFalse(canAutoplayInlinePreview(PerformanceTier.HIGH, dataSaver = false, bannerAutoplay = true, reduceMotion = false, isTv = false, playerActive = true))

        assertEquals("dQw4w9WgXcQ", extractPreviewYouTubeId("https://www.youtube-nocookie.com/embed/dQw4w9WgXcQ?autoplay=1"))
        assertEquals("dQw4w9WgXcQ", extractPreviewYouTubeId("https://youtu.be/dQw4w9WgXcQ"))
        assertEquals("dQw4w9WgXcQ", extractPreviewYouTubeId("yt://dQw4w9WgXcQ"))
        assertNull(extractPreviewYouTubeId("https://cdn.test/video.mp4"))
    }
}
