package com.mythbrix.cinetor.feature.home

import com.mythbrix.cinetor.core.data.Item
import com.mythbrix.cinetor.core.data.ItemType
import com.mythbrix.cinetor.core.data.Source
import com.mythbrix.cinetor.core.data.SourceKind
import com.mythbrix.cinetor.core.data.Space
import com.mythbrix.cinetor.core.perf.PerformanceTier
import com.mythbrix.cinetor.core.ui.ArtPalettes
import com.mythbrix.cinetor.core.ui.artPaletteIndex
import com.mythbrix.cinetor.core.ui.decodeScale
import com.mythbrix.cinetor.core.ui.ossBucket
import com.mythbrix.cinetor.core.ui.sizedImageUrl
import com.mythbrix.cinetor.core.ui.tmdbBucket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HubPhoneUiTest {

    @Test
    fun sizedImageUrl_appendsOssResizeBucket_andCapsLowTier() {
        val raw = "https://pbcdn.aoneroom.com/image/2026/09/15/8adc684ee89af8fbb9bf626d209d9c0c.jpg"
        assertEquals(
            "$raw?x-oss-process=image/resize,w_342",
            sizedImageUrl(raw, targetWidthPx = 300, tier = PerformanceTier.MID),
        )
        assertEquals(
            "$raw?x-oss-process=image/resize,w_1280",
            sizedImageUrl(raw, targetWidthPx = 1080, tier = PerformanceTier.HIGH, large = true),
        )
        // LOW tier caps posters at w_342 and heroes/backdrops at w_780
        assertEquals(
            "$raw?x-oss-process=image/resize,w_342",
            sizedImageUrl(raw, targetWidthPx = 600, tier = PerformanceTier.LOW, large = false),
        )
        assertEquals(
            "$raw?x-oss-process=image/resize,w_780",
            sizedImageUrl(raw, targetWidthPx = 1200, tier = PerformanceTier.LOW, large = true),
        )
    }

    @Test
    fun sizedImageUrl_preservesExistingQueryAndReplacesDuplicateOssParam() {
        val withQuery = "https://img.aliyuncs.com/poster.jpg?token=abc"
        assertEquals(
            "https://img.aliyuncs.com/poster.jpg?token=abc&x-oss-process=image/resize,w_500",
            sizedImageUrl(withQuery, targetWidthPx = 420, tier = PerformanceTier.HIGH),
        )
        val alreadyResized = "https://pbcdn.aoneroom.com/p.jpg?x-oss-process=image/resize,w_200"
        assertEquals(
            "https://pbcdn.aoneroom.com/p.jpg?x-oss-process=image/resize,w_780",
            sizedImageUrl(alreadyResized, targetWidthPx = 700, tier = PerformanceTier.MID, large = true),
        )
    }

    @Test
    fun sizedImageUrl_rewritesTmdbBuckets_forPrefixAndFullUrl() {
        assertEquals(
            "https://image.tmdb.org/t/p/w342/p550.jpg",
            sizedImageUrl("tmdb:/p550.jpg", targetWidthPx = 310, tier = PerformanceTier.MID),
        )
        assertEquals(
            "https://image.tmdb.org/t/p/w780/b550.jpg",
            sizedImageUrl(
                "https://image.tmdb.org/t/p/original/b550.jpg",
                targetWidthPx = 720,
                tier = PerformanceTier.HIGH,
                large = true,
            ),
        )
        assertEquals(
            "https://image.tmdb.org/t/p/w342/p550.jpg",
            sizedImageUrl(
                "https://image.tmdb.org/t/p/w1280/p550.jpg",
                targetWidthPx = 640,
                tier = PerformanceTier.LOW,
                large = false,
            ),
        )
    }

    @Test
    fun sizedImageUrl_handlesBlankMockAndGenericRemoteUrls() {
        assertEquals("", sizedImageUrl("", targetWidthPx = 300, tier = PerformanceTier.MID))
        assertEquals("   ".let { sizedImageUrl(it, targetWidthPx = 300, tier = PerformanceTier.MID) }, "")
        assertEquals(
            "file:///android_asset/mock/images/posters/1.webp",
            sizedImageUrl("images/posters/1.webp", targetWidthPx = 300, tier = PerformanceTier.MID),
        )
        assertEquals(
            "https://uploads.mangadex.org/covers/abc/1.jpg",
            sizedImageUrl("https://uploads.mangadex.org/covers/abc/1.jpg", targetWidthPx = 300, tier = PerformanceTier.MID),
        )
    }

    @Test
    fun decodeScale_andBuckets_shrinkOnLowTier() {
        assertTrue(decodeScale(PerformanceTier.LOW, large = true) < 1f)
        assertTrue(decodeScale(PerformanceTier.LOW, large = false) < 1f)
        assertEquals(1f, decodeScale(PerformanceTier.MID, large = true), 0.0001f)
        assertEquals(1f, decodeScale(PerformanceTier.HIGH, large = false), 0.0001f)
        assertEquals("w185", tmdbBucket(160, PerformanceTier.MID))
        assertEquals(200, ossBucket(160, PerformanceTier.MID))
    }

    @Test
    fun artPaletteIndex_isDeterministicAndSafeForMinIntHash() {
        // "polygenelubricants".hashCode() == Int.MIN_VALUE in JVM
        assertEquals(Int.MIN_VALUE, "polygenelubricants".hashCode())
        val minIdx = artPaletteIndex("polygenelubricants")
        assertTrue(minIdx in ArtPalettes.indices)
        assertEquals(minIdx, artPaletteIndex("polygenelubricants"))
        for (id in listOf("", "gw:1", "gw:999", "home-movie-42")) {
            val idx = artPaletteIndex(id)
            assertTrue(idx in ArtPalettes.indices)
        }
    }

    @Test
    fun formatPosterRating_hidesZeroInvalid_andFormatsOneDecimal() {
        assertNull(formatPosterRating(0.0))
        assertNull(formatPosterRating(-1.2))
        assertNull(formatPosterRating(Double.NaN))
        assertEquals("7.8", formatPosterRating(7.84))
        assertEquals("8.0", formatPosterRating(8.0))
    }

    @Test
    fun detectDubLanguage_matchesTitleAltTitleAndGenres_withoutFalsePositives() {
        assertEquals("Hindi", detectDubLanguage("Pushpa 2 [Hindi]", listOf("Action", "Drama")))
        assertEquals("Bengali", detectDubLanguage("Parineeta", listOf("Romance", "Bangla")))
        assertEquals("Bengali", detectDubLanguage("Feluda [Bengali]", listOf("Mystery")))
        assertEquals("Tamil", detectDubLanguage("Vikram (Tamil)", listOf("Action")))
        assertEquals("Telugu", detectDubLanguage("RRR", listOf("Action", "[Telugu]")))
        assertEquals("English", detectDubLanguage("Squid Game [English]", listOf("Thriller")))
        assertEquals("English", detectDubLanguage("Dark", listOf("Sci-Fi", "English")))
        // "Johnny English" without an English dub tag must not falsely badge as English dub
        assertNull(detectDubLanguage("Johnny English", listOf("Comedy", "Adventure")))
        assertNull(detectDubLanguage("Inception", listOf("Action", "Sci-Fi")))
    }

    @Test
    fun visibleWatchProgress_enforces2To95PercentWindow() {
        assertEquals(0f, visibleWatchProgress(null), 0.0001f)
        assertEquals(0f, visibleWatchProgress(Float.NaN), 0.0001f)
        assertEquals(0f, visibleWatchProgress(0.019f), 0.0001f)
        assertEquals(0.02f, visibleWatchProgress(0.02f), 0.0001f)
        assertEquals(0.48f, visibleWatchProgress(0.48f), 0.0001f)
        assertEquals(0.95f, visibleWatchProgress(0.95f), 0.0001f)
        assertEquals(0f, visibleWatchProgress(0.951f), 0.0001f)
    }

    @Test
    fun is4kItem_andCleanHeroGenres_extractMetadataCleanly() {
        val base = sampleItem()
        assertFalse(is4kItem(base))
        assertTrue(is4kItem(base.copy(sources = listOf(Source("https://x/v.mp4", SourceKind.MP4, height = 2160)))))
        assertTrue(is4kItem(base.copy(sources = listOf(Source("https://x/v.mp4", SourceKind.MP4, label = "4K HDR")))))
        assertTrue(is4kItem(base.copy(genres = listOf("Action", "4K UHD"))))
        assertEquals(
            listOf("Action", "Sci-Fi", "Thriller"),
            cleanHeroGenres(listOf("[Hindi]", "4K UHD", "Action", "Sci-Fi", "Thriller", "Drama"), maxCount = 3),
        )
    }

    @Test
    fun heroDeckMetrics_andCardWidth_implementMoviraPeekingDeck() {
        assertEquals(312f, heroDeckCardWidthDp(maxWidthDp = 400f, wide = false), 0.01f)
        assertEquals(752f, heroDeckCardWidthDp(maxWidthDp = 800f, wide = true), 0.01f)

        val front = heroDeckMetrics(0f)
        assertEquals(1f, front.scale, 0.0001f)
        assertEquals(0f, front.translationXFraction, 0.0001f)
        assertEquals(0f, front.dimAlpha, 0.0001f)
        assertEquals(0f, front.zIndex, 0.0001f)
        assertTrue(front.visible)

        val rightNeighbor = heroDeckMetrics(-1f)
        assertEquals(0.88f, rightNeighbor.scale, 0.001f)
        assertTrue(rightNeighbor.translationXFraction < 0f)
        assertTrue(rightNeighbor.dimAlpha in 0.35f..0.60f)
        assertTrue(rightNeighbor.zIndex < front.zIndex)
        assertTrue(rightNeighbor.visible)

        val offDeck = heroDeckMetrics(2.5f)
        assertFalse(offDeck.visible)
    }

    @Test
    fun canSeeAllRow_andShouldLoadMoreRow_coverPosterAndShortFirstPages() {
        val fourItems = (1..4).map { sampleItem(id = "gw:$it") }
        assertTrue(canSeeAllRow(RowUi("r1", "Latest Movies", com.mythbrix.cinetor.core.data.RowStyle.POSTER, fourItems)))
        assertTrue(canSeeAllRow(RowUi("r2", "New Releases", com.mythbrix.cinetor.core.data.RowStyle.LANDSCAPE, fourItems)))
        assertFalse(canSeeAllRow(RowUi("r3", "Top 10", com.mythbrix.cinetor.core.data.RowStyle.TOP10, fourItems)))
        assertFalse(canSeeAllRow(RowUi("r4", "Continue", com.mythbrix.cinetor.core.data.RowStyle.CONTINUE, fourItems)))
        assertFalse(canSeeAllRow(RowUi("r5", "Tiny", com.mythbrix.cinetor.core.data.RowStyle.POSTER, fourItems.take(2))))

        assertFalse(shouldLoadMoreRow(lastVisibleIndex = 0, totalItems = 0))
        assertFalse(shouldLoadMoreRow(lastVisibleIndex = 1, totalItems = 8))
        assertTrue(shouldLoadMoreRow(lastVisibleIndex = 4, totalItems = 8))
        assertTrue(shouldLoadMoreRow(lastVisibleIndex = 2, totalItems = 5))
    }

    private fun sampleItem(
        id: String = "gw:1",
        type: ItemType = ItemType.MOVIE,
        title: String = "Sample Movie",
        genres: List<String> = listOf("Action", "Thriller"),
    ) = Item(
        id = id,
        space = Space.HOME,
        categoryId = "gw:home-featured",
        type = type,
        title = title,
        year = 2026,
        rating = 7.8,
        maturity = "PG-13",
        genres = genres,
        synopsis = "Synopsis",
        poster = "https://pbcdn.aoneroom.com/p.jpg",
        backdrop = "https://pbcdn.aoneroom.com/b.jpg",
        dominantColor = "#221115",
        accentColor = "#E11D2E",
    )
}
