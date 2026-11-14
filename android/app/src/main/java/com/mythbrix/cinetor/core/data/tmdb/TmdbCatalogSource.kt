package com.mythbrix.cinetor.core.data.tmdb

import com.mythbrix.cinetor.core.data.CatalogRepository
import com.mythbrix.cinetor.core.data.Category
import com.mythbrix.cinetor.core.data.Item
import com.mythbrix.cinetor.core.data.MockSourceProvider
import com.mythbrix.cinetor.core.data.NotFoundException
import com.mythbrix.cinetor.core.data.RemoteSearch
import com.mythbrix.cinetor.core.data.Row
import com.mythbrix.cinetor.core.data.RowStyle
import com.mythbrix.cinetor.core.data.Space
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.util.concurrent.ConcurrentHashMap

/** One TMDB-backed row: endpoint + query; [space] is forced onto mapped items. */
data class TmdbRowSpec(
    val id: String,
    val title: String,
    val style: RowStyle,
    val path: String,
    val params: Map<String, String>,
    val media: String,
    val space: Space,
    val limit: Int = 20,
)

data class TmdbCategorySpec(val id: String, val space: Space, val title: String, val rows: List<TmdbRowSpec>)

/** Static TMDB catalog layout for Home / Anime / Drama (isolated spaces never touch TMDB). */
object TmdbCatalog {
    const val ROW_PREFIX = "tmdb:"
    const val HOME_MOVIES = "tmdb:home-movies"
    const val HOME_SERIES = "tmdb:home-series"
    const val ANIME_SERIES = "tmdb:anime-series"
    const val ANIME_MOVIES = "tmdb:anime-movies"
    const val DRAMA_K = "tmdb:drama-k"
    const val DRAMA_C = "tmdb:drama-c"
    const val DRAMA_J = "tmdb:drama-j"

    private const val M = TmdbMapper.MOVIE
    private const val T = TmdbMapper.TV
    private val POP = "sort_by" to "popularity.desc"
    private val RATED = mapOf("sort_by" to "vote_average.desc", "vote_count.gte" to "200")

    val ANIME_BASE = mapOf("with_genres" to "16", "with_origin_country" to "JP")

    fun dramaBase(country: String) = mapOf("with_origin_country" to country, "without_genres" to "16")

    private fun row(cat: String, key: String, title: String, style: RowStyle, path: String, params: Map<String, String>, media: String, space: Space, limit: Int = 20) =
        TmdbRowSpec("$cat/$key", title, style, path, params, media, space, limit)

    private fun home(): List<TmdbCategorySpec> {
        val s = Space.HOME
        return listOf(
            TmdbCategorySpec(
                HOME_MOVIES, s, "Movies",
                listOf(
                    row(HOME_MOVIES, "trending", "Trending Now", RowStyle.POSTER, "trending/movie/week", emptyMap(), M, s),
                    row(HOME_MOVIES, "top10", "Top 10 Movies Today", RowStyle.TOP10, "trending/movie/day", emptyMap(), M, s, 10),
                    row(HOME_MOVIES, "now", "Now Playing", RowStyle.LANDSCAPE, "movie/now_playing", emptyMap(), M, s),
                    row(HOME_MOVIES, "popular", "Popular", RowStyle.POSTER, "movie/popular", emptyMap(), M, s),
                    row(HOME_MOVIES, "top", "Top Rated", RowStyle.POSTER, "movie/top_rated", emptyMap(), M, s),
                    row(HOME_MOVIES, "upcoming", "Coming Soon", RowStyle.LANDSCAPE, "movie/upcoming", emptyMap(), M, s),
                    row(HOME_MOVIES, "action", "Action Packed", RowStyle.POSTER, "discover/movie", mapOf("with_genres" to "28", POP), M, s),
                    row(HOME_MOVIES, "thriller", "Edge-of-Seat Thrillers", RowStyle.POSTER, "discover/movie", mapOf("with_genres" to "53", POP), M, s),
                    row(HOME_MOVIES, "scifi", "Sci-Fi Worlds", RowStyle.POSTER, "discover/movie", mapOf("with_genres" to "878", POP), M, s),
                ),
            ),
            TmdbCategorySpec(
                HOME_SERIES, s, "Series",
                listOf(
                    row(HOME_SERIES, "trending", "Trending Now", RowStyle.POSTER, "trending/tv/week", emptyMap(), T, s),
                    row(HOME_SERIES, "top10", "Top 10 Series Today", RowStyle.TOP10, "trending/tv/day", emptyMap(), T, s, 10),
                    row(HOME_SERIES, "airing", "Airing Today", RowStyle.LANDSCAPE, "tv/airing_today", emptyMap(), T, s),
                    row(HOME_SERIES, "popular", "Popular", RowStyle.POSTER, "tv/popular", emptyMap(), T, s),
                    row(HOME_SERIES, "top", "Top Rated", RowStyle.POSTER, "tv/top_rated", emptyMap(), T, s),
                    row(HOME_SERIES, "onair", "On The Air", RowStyle.LANDSCAPE, "tv/on_the_air", emptyMap(), T, s),
                    row(HOME_SERIES, "crime", "Crime & Mystery", RowStyle.POSTER, "discover/tv", mapOf("with_genres" to "80|9648", POP), T, s),
                    row(HOME_SERIES, "drama", "Binge-worthy Drama", RowStyle.POSTER, "discover/tv", mapOf("with_genres" to "18", POP), T, s),
                ),
            ),
        )
    }

    private fun anime(): List<TmdbCategorySpec> {
        val s = Space.ANIME
        val b = ANIME_BASE
        return listOf(
            TmdbCategorySpec(
                ANIME_SERIES, s, "Series",
                listOf(
                    row(ANIME_SERIES, "popular", "Trending Now", RowStyle.POSTER, "discover/tv", b + POP, T, s),
                    row(ANIME_SERIES, "top10", "Top 10 Anime", RowStyle.TOP10, "discover/tv", b + RATED, T, s, 10),
                    row(ANIME_SERIES, "new", "New Episodes", RowStyle.LANDSCAPE, "discover/tv", b + mapOf("sort_by" to "first_air_date.desc", "vote_count.gte" to "20"), T, s),
                    row(ANIME_SERIES, "action", "Big Battles", RowStyle.POSTER, "discover/tv", b + mapOf("with_genres" to "16,10759", POP), T, s),
                    row(ANIME_SERIES, "fantasy", "Fantasy Realms", RowStyle.POSTER, "discover/tv", b + mapOf("with_genres" to "16,10765", POP), T, s),
                    row(ANIME_SERIES, "comedy", "Feel-good Comedy", RowStyle.POSTER, "discover/tv", b + mapOf("with_genres" to "16,35", POP), T, s),
                ),
            ),
            TmdbCategorySpec(
                ANIME_MOVIES, s, "Movies",
                listOf(
                    row(ANIME_MOVIES, "popular", "Popular Anime Films", RowStyle.POSTER, "discover/movie", b + POP, M, s),
                    row(ANIME_MOVIES, "top10", "Top 10 Anime Films", RowStyle.TOP10, "discover/movie", b + RATED, M, s, 10),
                    row(ANIME_MOVIES, "fantasy", "Fantasy Adventures", RowStyle.LANDSCAPE, "discover/movie", b + mapOf("with_genres" to "16,14", POP), M, s),
                ),
            ),
        )
    }

    private fun drama(): List<TmdbCategorySpec> {
        val s = Space.DRAMA
        fun cat(id: String, title: String, country: String): TmdbCategorySpec {
            val b = dramaBase(country)
            return TmdbCategorySpec(
                id, s, title,
                listOf(
                    row(id, "popular", "Trending Now", RowStyle.POSTER, "discover/tv", b + POP, T, s),
                    row(id, "top10", "Top 10 $title", RowStyle.TOP10, "discover/tv", b + mapOf("sort_by" to "vote_average.desc", "vote_count.gte" to "100"), T, s, 10),
                    row(id, "new", "New Releases", RowStyle.LANDSCAPE, "discover/tv", b + mapOf("sort_by" to "first_air_date.desc", "vote_count.gte" to "10"), T, s),
                    row(id, "mystery", "Twisty Thrillers", RowStyle.POSTER, "discover/tv", b + mapOf("with_genres" to "9648|80", POP), T, s),
                    row(id, "family", "Heartfelt Stories", RowStyle.POSTER, "discover/tv", b + mapOf("with_genres" to "18|10751", POP), T, s),
                ),
            )
        }
        return listOf(cat(DRAMA_K, "K-Drama", "KR"), cat(DRAMA_C, "C-Drama", "CN"), cat(DRAMA_J, "J-Drama", "JP"))
    }

    private val bySpace: Map<Space, List<TmdbCategorySpec>> by lazy {
        mapOf(Space.HOME to home(), Space.ANIME to anime(), Space.DRAMA to drama())
    }

    private val rowsById: Map<String, TmdbRowSpec> by lazy {
        bySpace.values.flatten().flatMap { it.rows }.associateBy { it.id }
    }

    fun categories(space: Space): List<TmdbCategorySpec> =
        bySpace[space] ?: throw IllegalArgumentException("TMDB serves video spaces only, not $space")

    fun rowSpec(id: String): TmdbRowSpec? = rowsById[id]

    /** Genre chips per space (unified names, see [TmdbMapper.MOVIE_GENRES]). */
    fun genres(space: Space): List<String> = when (space) {
        Space.HOME -> listOf("Action", "Thriller", "Sci-Fi", "Drama", "Comedy", "Horror", "Romance", "Adventure", "Mystery", "Crime", "Fantasy")
        Space.ANIME -> listOf("Action", "Adventure", "Comedy", "Drama", "Fantasy", "Sci-Fi", "Mystery", "Family")
        Space.DRAMA -> listOf("Drama", "Comedy", "Crime", "Mystery", "Fantasy", "Action", "Family")
        else -> emptyList()
    }

    /** Discover query for "all of [genre] in [space]"; null if TMDB has no such genre for that media. */
    fun genreQuery(space: Space, genre: String): Triple<String, Map<String, String>, String>? = when (space) {
        Space.HOME -> TmdbMapper.genreId(M, genre)?.let { Triple("discover/movie", mapOf("with_genres" to "$it", POP), M) }
        Space.ANIME -> TmdbMapper.genreId(T, genre)?.let { Triple("discover/tv", ANIME_BASE + mapOf("with_genres" to "16,$it", POP), T) }
        Space.DRAMA -> TmdbMapper.genreId(T, genre)?.let {
            Triple("discover/tv", mapOf("with_origin_country" to "KR|CN|JP", "without_genres" to "16", "with_genres" to "$it", POP), T)
        }
        else -> null
    }
}

/**
 * [CatalogRepository] + [RemoteSearch] over TMDB for Home / Anime / Drama. Metadata only — playable
 * sources are attached by [MockSourceProvider]. Never used for isolated spaces (throws).
 * Rows carry no itemIds; resolve them with [row]. Seen items are cached in memory (local search index feed).
 */
class TmdbCatalogSource(
    private val api: TmdbApi,
    private val sources: MockSourceProvider = MockSourceProvider,
) : CatalogRepository, RemoteSearch {

    private val summaries = ConcurrentHashMap<String, Item>()
    private val details = ConcurrentHashMap<String, Item>()
    private val rows = ConcurrentHashMap<String, List<Item>>()

    private fun remember(items: List<Item>): List<Item> {
        items.forEach { summaries.putIfAbsent(it.id, it) }
        return items
    }

    override suspend fun categories(space: Space): List<Category> = coroutineScope {
        val specs = TmdbCatalog.categories(space)
        specs.map { c ->
            async {
                val heroes = row(c.rows.first().id).filter { it.backdrop.isNotEmpty() }.take(6).map { it.id }
                Category(
                    id = c.id,
                    space = space,
                    title = c.title,
                    heroIds = heroes,
                    rows = c.rows.map { Row(it.id, it.title, it.style, emptyList()) },
                )
            }
        }.awaitAll()
    }

    override suspend fun item(id: String): Item {
        details[id]?.let { return it }
        val (media, tmdbId) = TmdbMapper.parseId(id) ?: throw NotFoundException(id)
        val d = api.details(media, tmdbId)
        val seasonDetails = if (media == TmdbMapper.TV) {
            val first = d.seasons.firstOrNull { it.seasonNumber > 0 && it.episodeCount > 0 }
            first?.let { s ->
                try {
                    mapOf(s.seasonNumber to api.season(tmdbId, s.seasonNumber))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    emptyMap()
                }
            }.orEmpty()
        } else {
            emptyMap()
        }
        val item = sources.attach(TmdbMapper.detailsToItem(d, media, summaries[id]?.space, seasonDetails))
        details[id] = item
        return item
    }

    override suspend fun row(rowId: String): List<Item> {
        rows[rowId]?.let { return it }
        val spec = TmdbCatalog.rowSpec(rowId) ?: throw NotFoundException(rowId)
        val items = fetch(spec.path, spec.params, spec.media, spec.space).take(spec.limit)
        rows[rowId] = items
        return items
    }

    private suspend fun fetch(path: String, params: Map<String, String>, media: String, space: Space?): List<Item> =
        remember(api.page(path, params).results.mapNotNull { TmdbMapper.toItem(it, media, space) })

    override suspend fun byGenre(space: Space, genre: String): List<Item> {
        val q = TmdbCatalog.genreQuery(space, genre)
            ?: return summaries.values.filter { it.space == space && genre in it.genres }
        return fetch(q.first, q.second, q.third, space)
    }

    override suspend fun genres(space: Space): List<String> {
        TmdbCatalog.categories(space)
        return TmdbCatalog.genres(space)
    }

    override suspend fun allItems(space: Space): List<Item> {
        val seen = summaries.values.filter { it.space == space }
        if (seen.isNotEmpty()) return seen
        row(TmdbCatalog.categories(space).first().rows.first().id)
        return summaries.values.filter { it.space == space }
    }

    /** `/search/multi` → movie/tv only, never adult, always a video space. */
    override suspend fun search(query: String): List<Item> {
        if (query.isBlank()) return emptyList()
        return remember(
            api.searchMulti(query.trim()).results
                .filter { !it.adult && (it.mediaType == TmdbMapper.MOVIE || it.mediaType == TmdbMapper.TV) }
                .mapNotNull { TmdbMapper.toItem(it, null, null) }
                .filter { it.space.isVideo },
        )
    }
}
