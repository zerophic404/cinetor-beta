package com.mythbrix.cinetor.core.data.tmdb

import com.mythbrix.cinetor.TestFiles
import com.mythbrix.cinetor.core.data.HybridCatalog
import com.mythbrix.cinetor.core.data.ItemType
import com.mythbrix.cinetor.core.data.Space
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TmdbCatalogSourceTest {

    private fun page() = TmdbApi.json.decodeFromString(TmdbPage.serializer(), TmdbFixtures.PAGE)

    @Test
    fun mapper_mapsMovieAndTv() {
        val (movieDto, tvDto) = page().results
        val movie = TmdbMapper.toItem(movieDto, null)!!
        assertEquals("tmdb-m-550", movie.id)
        assertEquals(ItemType.MOVIE, movie.type)
        assertEquals(Space.HOME, movie.space)
        assertEquals("Fight Club", movie.title)
        assertNull(movie.altTitle)
        assertEquals(1999, movie.year)
        assertEquals(8.4, movie.rating, 0.0001)
        assertEquals(listOf("Drama", "Thriller"), movie.genres)
        assertEquals("https://image.tmdb.org/t/p/w342/p550.jpg", movie.poster)
        assertEquals("https://image.tmdb.org/t/p/w780/b550.jpg", movie.backdrop)
        assertEquals(TmdbCatalog.HOME_MOVIES, movie.categoryId)

        val tv = TmdbMapper.toItem(tvDto, null)!!
        assertEquals("tmdb-t-1396", tv.id)
        assertEquals(ItemType.SERIES, tv.type)
        assertEquals("Breaking Bad", tv.title)
        assertEquals(2008, tv.year)
        assertEquals(listOf("Drama", "Crime"), tv.genres)
        assertEquals(TmdbCatalog.HOME_SERIES, tv.categoryId)
        assertEquals("movie" to 550L, TmdbMapper.parseId(movie.id))
        assertEquals("tv" to 1396L, TmdbMapper.parseId(tv.id))
    }

    @Test
    fun anime_queryUsesGenre16AndJP() = runTest {
        val http = FakeTmdbHttp()
        val source = TmdbCatalogSource(TmdbApi(http))
        val rowId = TmdbCatalog.categories(Space.ANIME).first().rows.first().id
        val items = source.row(rowId)
        val url = http.urls.single()
        assertTrue(url, url.contains("discover/tv"))
        assertTrue(url, url.contains("with_genres=16"))
        assertTrue(url, url.contains("with_origin_country=JP"))
        assertTrue(url, url.contains("include_adult=false"))
        assertTrue(items.isNotEmpty() && items.all { it.space == Space.ANIME })
        // Space rule: animated + JP → ANIME.
        assertEquals(Space.ANIME, TmdbMapper.spaceOf("tv", listOf(16), listOf("JP"), "ja"))
    }

    @Test
    fun drama_kdramaUsesKR() = runTest {
        val http = FakeTmdbHttp()
        val source = TmdbCatalogSource(TmdbApi(http))
        val kCat = TmdbCatalog.categories(Space.DRAMA).first { it.id == TmdbCatalog.DRAMA_K }
        source.row(kCat.rows.first().id)
        val url = http.urls.single()
        assertTrue(url, url.contains("with_origin_country=KR"))
        assertTrue(url, url.contains("without_genres=16"))
        assertTrue(url, url.contains("include_adult=false"))
        assertEquals(Space.DRAMA, TmdbMapper.spaceOf("tv", listOf(18), listOf("KR"), "ko"))
        assertEquals(TmdbCatalog.DRAMA_K, TmdbMapper.categoryIdFor(Space.DRAMA, "tv", listOf("KR"), "ko"))
    }

    @Test
    fun tmdbError_fallsBackToMock() = runTest {
        val http = FakeTmdbHttp(fail = true)
        val mock = TestFiles.catalog()
        val hybrid = HybridCatalog(TmdbCatalogSource(TmdbApi(http)), mock)
        for (space in Space.VIDEO) {
            assertEquals(mock.categories(space), hybrid.categories(space))
            assertEquals(mock.allItems(space).map { it.id }, hybrid.allItems(space).map { it.id })
        }
        assertTrue("TMDB was tried", http.urls.isNotEmpty())
        // Mock ids still resolve through the hybrid.
        val mockItem = mock.allItems(Space.HOME).first()
        assertEquals(mockItem, hybrid.item(mockItem.id))
    }

    @Test
    fun isolatedSpaces_neverHitTmdb() = runTest {
        val http = FakeTmdbHttp()
        val mock = TestFiles.catalog()
        val hybrid = HybridCatalog(TmdbCatalogSource(TmdbApi(http)), mock)
        for (space in Space.ISOLATED) {
            assertEquals(mock.categories(space), hybrid.categories(space))
            hybrid.allItems(space)
        }
        assertTrue(http.urls.isEmpty())
    }

    @Test
    fun noToken_usesMock() = runTest {
        val mock = TestFiles.catalog()
        val hybrid = HybridCatalog(null, mock)
        assertEquals(mock.categories(Space.HOME), hybrid.categories(Space.HOME))
    }

    @Test
    fun search_neverIncludesAdult() = runTest {
        val http = FakeTmdbHttp(body = TmdbFixtures.SEARCH)
        val source = TmdbCatalogSource(TmdbApi(http))
        val results = source.search("game")
        assertTrue(http.urls.single().contains("search/multi"))
        assertTrue(http.urls.single().contains("include_adult=false"))
        assertEquals(listOf("tmdb-m-1", "tmdb-t-4", "tmdb-t-5"), results.map { it.id })
        assertFalse(results.any { it.title.startsWith("Adult") })
        assertTrue(results.all { it.space.isVideo })
        assertEquals(Space.DRAMA, results[1].space)
        assertEquals(Space.ANIME, results[2].space)
        // URL builder forces include_adult=false even if a caller passes true.
        assertTrue(TmdbApi.url("search/multi", mapOf("include_adult" to "true")).contains("include_adult=false"))
        assertFalse(TmdbApi.url("search/multi", mapOf("include_adult" to "true")).contains("include_adult=true"))
    }

    @Test
    fun items_getMockSources() = runTest {
        val http = FakeTmdbHttp()
        val source = TmdbCatalogSource(TmdbApi(http))
        val rowId = TmdbCatalog.categories(Space.HOME).first().rows.first().id
        val movie = source.row(rowId).first()
        http.body = """{"id":550,"title":"Fight Club","runtime":139,"genres":[{"id":18,"name":"Drama"}]}"""
        val d = source.item(movie.id)
        assertTrue(d.sources.isNotEmpty())
    }
}
