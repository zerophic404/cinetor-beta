package com.mythbrix.cinetor.core.data.gateway

import com.mythbrix.cinetor.core.data.CatalogRepository
import com.mythbrix.cinetor.core.data.Category
import com.mythbrix.cinetor.core.data.Episode
import com.mythbrix.cinetor.core.data.Item
import com.mythbrix.cinetor.core.data.ItemType
import com.mythbrix.cinetor.core.data.NotFoundException
import com.mythbrix.cinetor.core.data.RemoteSearch
import com.mythbrix.cinetor.core.data.Row
import com.mythbrix.cinetor.core.data.RowStyle
import com.mythbrix.cinetor.core.data.Season
import com.mythbrix.cinetor.core.data.Space
import com.mythbrix.cinetor.core.data.tmdb.TmdbMapper
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/** One page of a row ("load more"). */
data class RowPage(val items: List<Item>, val page: Int, val hasMore: Boolean)

/** Catalogs whose rows can page (gateway lists). */
interface PagedCatalog {
    suspend fun rowPage(rowId: String, page: Int): RowPage
}

/** Catalog source that owns a set of item / row ids (routing in HybridCatalog). */
interface RoutedCatalog {
    fun ownsItem(id: String): Boolean
    fun ownsRow(rowId: String): Boolean
}

/** Search-as-you-type suggestions (gateway `/search/suggest`). */
fun interface SearchSuggestions {
    suspend fun suggest(query: String): List<String>
}

/** Persists gateway id → slug (detail_path) so history / downloads reopen after a restart. */
interface SlugStore {
    fun get(id: String): String?
    fun put(id: String, slug: String)
}

class InMemorySlugStore : SlugStore {
    private val map = ConcurrentHashMap<String, String>()
    override fun get(id: String): String? = map[id]
    override fun put(id: String, slug: String) {
        map[id] = slug
    }
}

/** Poster → dominant color (#RRGGBB) cache; Android computes it with Palette in the background. */
interface DominantColors {
    /** Cached color, or null (and schedules a computation). */
    fun colorFor(posterUrl: String): String?
}

object NoDominantColors : DominantColors {
    override fun colorFor(posterUrl: String): String? = null
}

/** Pure gateway JSON → [Item] mapping. */
object GatewayMapper {
    const val ID_PREFIX = "gw:"
    /** Adult items keep the `ad-` prefix the adult gate routes on. */
    const val ADULT_PREFIX = "ad-gw:"
    const val ACCENT = "#E50914"
    private val ADULT_GENRES = setOf("adult", "erotic", "erotica", "porn")
    private val DRAMA_COUNTRIES = setOf("korea", "south korea", "china", "japan", "taiwan", "hong kong")

    fun isGatewayId(id: String): Boolean = id.startsWith(ID_PREFIX) || id.startsWith(ADULT_PREFIX)

    fun idOf(subjectId: String, space: Space): String = (if (space == Space.ADULT) ADULT_PREFIX else ID_PREFIX) + subjectId

    fun subjectIdOf(id: String): String? = when {
        id.startsWith(ADULT_PREFIX) -> id.removePrefix(ADULT_PREFIX)
        id.startsWith(ID_PREFIX) -> id.removePrefix(ID_PREFIX)
        else -> null
    }?.takeIf { it.isNotBlank() }

    fun genresOf(raw: String?): List<String> =
        raw?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()

    fun isAdult(genres: List<String>): Boolean = genres.any { it.lowercase() in ADULT_GENRES }

    /** Where a search / detail result belongs when no row told us. */
    fun inferSpace(genres: List<String>, type: String?, country: String?): Space {
        if (isAdult(genres)) return Space.ADULT
        if (genres.any { it.equals("Anime", true) || it.equals("Animation", true) }) return Space.ANIME
        if (type == "tv" && country?.lowercase() in DRAMA_COUNTRIES) return Space.DRAMA
        return Space.HOME
    }

    private fun year(y: String?, date: String?): Int = y?.take(4)?.toIntOrNull() ?: date?.take(4)?.toIntOrNull() ?: 0

    fun toItem(g: GwItem, space: Space?, categoryId: String, colors: DominantColors = NoDominantColors): Item? {
        // Every visible title must be playable: the gateway says up front which ones have no stream.
        if (!g.hasResource) return null
        val sid = g.subjectId?.takeIf { it.isNotBlank() } ?: return null
        val title = g.name?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val genres = genresOf(g.genre)
        val sp = space ?: inferSpace(genres, g.type, g.country)
        val poster = g.posterUrl.orEmpty()
        val id = idOf(sid, sp)
        return Item(
            id = id,
            space = sp,
            categoryId = categoryId,
            type = if (g.type == "tv") ItemType.SERIES else ItemType.MOVIE,
            title = title,
            year = year(g.year, g.releaseDate),
            rating = g.rating?.toDoubleOrNull() ?: 0.0,
            maturity = if (sp == Space.ADULT) "18+" else "NR",
            genres = genres,
            synopsis = g.description.orEmpty(),
            poster = poster,
            backdrop = poster,
            dominantColor = (if (poster.isNotEmpty()) colors.colorFor(poster) else null) ?: TmdbMapper.fallbackColor(id),
            accentColor = ACCENT,
            durationSec = g.duration?.takeIf { it > 0 },
            slug = g.slug,
        )
    }

    fun episodeId(se: Int, ep: Int): String = "s${se}e$ep"

    /** `s2e5` → (2, 5); null for anything else (movie = 0/0 by the caller). */
    fun parseEpisodeId(id: String?): Pair<Int, Int>? {
        val m = Regex("^s(\\d+)e(\\d+)$").find(id ?: return null) ?: return null
        return m.groupValues[1].toInt() to m.groupValues[2].toInt()
    }

    /** `/detail/{slug}` → full item (seasons/episodes for tv; sources are resolved at play time). */
    fun detailToItem(d: GwDetailData, slug: String, summary: Item?, colors: DominantColors = NoDominantColors): Item? {
        val s = d.subject ?: return null
        val sid = s.subjectId.takeIf { it.isNotBlank() } ?: return null
        val genres = genresOf(s.genre).ifEmpty { summary?.genres.orEmpty() }
        val isTv = s.subjectType == 2 || summary?.type == ItemType.SERIES
        val space = summary?.space ?: inferSpace(genres, if (isTv) "tv" else "movie", s.countryName)
        val id = idOf(sid, space)
        val poster = s.cover?.url?.takeIf { it.isNotBlank() } ?: summary?.poster.orEmpty()
        val epDuration = s.duration?.takeIf { it > 0 } ?: 0
        val seasons = if (isTv) {
            d.resource?.seasons.orEmpty().filter { it.maxEp > 0 }.sortedBy { it.se }.map { r ->
                Season(
                    n = r.se,
                    title = "Season ${r.se}",
                    episodes = (1..r.maxEp).map { n ->
                        Episode(id = episodeId(r.se, n), n = n, title = "Episode $n", durationSec = epDuration, still = poster, sources = emptyList())
                    },
                )
            }
        } else {
            emptyList()
        }
        val color = s.cover?.avgHueDark?.takeIf { it.matches(Regex("^#[0-9a-fA-F]{6}$")) }
            ?: (if (poster.isNotEmpty()) colors.colorFor(poster) else null)
            ?: summary?.dominantColor ?: TmdbMapper.fallbackColor(id)
        return Item(
            id = id,
            space = space,
            categoryId = summary?.categoryId ?: "gw:detail",
            type = if (isTv) ItemType.SERIES else ItemType.MOVIE,
            title = s.title.ifBlank { summary?.title.orEmpty() },
            year = year(null, s.releaseDate).takeIf { it > 0 } ?: summary?.year ?: 0,
            rating = s.imdbRatingValue?.toDoubleOrNull() ?: summary?.rating ?: 0.0,
            maturity = if (space == Space.ADULT) "18+" else "NR",
            genres = genres,
            synopsis = s.description.orEmpty().ifBlank { summary?.synopsis.orEmpty() },
            poster = poster,
            backdrop = poster,
            dominantColor = color,
            accentColor = ACCENT,
            durationSec = if (isTv) null else epDuration.takeIf { it > 0 },
            seasons = seasons,
            cast = d.stars.filter { it.staffType == 1 }.map { it.name }.filter { it.isNotBlank() }.take(12),
            studio = d.stars.firstOrNull { it.staffType == 2 }?.name?.let { "Dir. $it" },
            slug = s.detailPath?.takeIf { it.isNotBlank() } ?: slug,
        )
    }
}

/** Static gateway layout per space. Row ids encode the query: `gwrow:<SPACE>:<path>?<query>`. */
object GatewayLayout {
    const val ROW_PREFIX = "gwrow:"
    const val CAT_PREFIX = "gw:"
    const val HOME_SECTION = "home"

    data class RowSpec(val space: Space, val path: String, val params: Map<String, String>, val title: String, val style: RowStyle) {
        val id: String
            get() = ROW_PREFIX + space.name + ":" + path + "?" +
                params.entries.joinToString("&") { (k, v) -> k + "=" + URLEncoder.encode(v, "UTF-8") }
    }

    data class CatSpec(val id: String, val title: String, val rows: List<RowSpec>)

    fun parseRow(rowId: String): RowSpec? {
        if (!rowId.startsWith(ROW_PREFIX)) return null
        val rest = rowId.removePrefix(ROW_PREFIX)
        val space = runCatching { Space.valueOf(rest.substringBefore(':')) }.getOrNull() ?: return null
        val pathQ = rest.substringAfter(':')
        val path = pathQ.substringBefore('?')
        val q = pathQ.substringAfter('?', "")
        val params = q.split('&').filter { it.contains('=') }
            .associate { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), "UTF-8") }
        return RowSpec(space, path, params, "", RowStyle.POSTER)
    }

    private fun row(space: Space, path: String, title: String, style: RowStyle = RowStyle.POSTER, vararg p: Pair<String, String>) =
        RowSpec(space, path, mapOf(*p), title, style)

    val HOME_SERIES_GENRES = listOf("Action", "Comedy", "Crime", "Drama", "Romance", "Sci-Fi", "Horror")
    val ANIME_GENRES = listOf("Action", "Adventure", "Comedy", "Fantasy", "Sci-Fi", "Family")
    val DRAMA_GENRES = listOf("Romance", "Comedy", "Crime", "Thriller", "Fantasy")
    val DRAMA_COUNTRIES = listOf("gw:drama-k" to ("K-Drama" to "Korea"), "gw:drama-c" to ("C-Drama" to "China"), "gw:drama-j" to ("J-Drama" to "Japan"))

    fun genres(space: Space): List<String> = when (space) {
        Space.HOME -> listOf("Action", "Comedy", "Drama", "Thriller", "Horror", "Romance", "Sci-Fi", "Adventure", "Crime", "Fantasy", "Animation")
        Space.ANIME -> ANIME_GENRES
        Space.DRAMA -> listOf("Romance", "Drama", "Comedy", "Crime", "Thriller", "Fantasy", "Action")
        else -> emptyList()
    }

    /** Home Movies tab: `/home` sections (minus Banner = hero) with the paged `/movies` list as row 3. */
    fun homeMovies(sections: List<GwSection>): CatSpec {
        val s = Space.HOME
        val rows = sections.filter { !it.section.equals("Banner", true) && it.items.isNotEmpty() }.mapIndexed { i, sec ->
            val style = when {
                sec.section.contains("Coming Soon", true) -> RowStyle.LANDSCAPE
                i == 1 -> RowStyle.TOP10
                else -> RowStyle.POSTER
            }
            row(s, HOME_SECTION, sec.section, style, "section" to sec.section)
        }.toMutableList()
        rows.add(minOf(2, rows.size), row(s, "movies", "New Movies", RowStyle.LANDSCAPE))
        return CatSpec("gw:home-movies", "Movies", rows)
    }

    fun homeSeries(): CatSpec {
        val s = Space.HOME
        return CatSpec(
            "gw:home-series", "Series",
            listOf(row(s, "tv-series", "Latest Series")) +
                HOME_SERIES_GENRES.map { g -> row(s, "tv-series", "$g Series", RowStyle.POSTER, "genre" to g) },
        )
    }

    fun anime(): List<CatSpec> {
        val s = Space.ANIME
        return listOf(
            CatSpec(
                "gw:anime", "Anime",
                listOf(row(s, "animation", "Trending Now"), row(s, "tv-series", "Japanese Anime", RowStyle.LANDSCAPE, "genre" to "Anime")) +
                    ANIME_GENRES.map { g -> row(s, "animation", g, RowStyle.POSTER, "genre" to g) },
            ),
        )
    }

    fun drama(): List<CatSpec> = DRAMA_COUNTRIES.map { (id, tc) ->
        val (title, country) = tc
        val s = Space.DRAMA
        CatSpec(
            id, title,
            listOf(row(s, "tv-series", "Latest $title", RowStyle.POSTER, "country" to country)) +
                DRAMA_GENRES.map { g -> row(s, "tv-series", g, RowStyle.POSTER, "country" to country, "genre" to g) },
        )
    }

    fun adult(): List<CatSpec> {
        val s = Space.ADULT
        fun cat(id: String, title: String, kind: String) = CatSpec(
            id, title,
            listOf(
                row(s, "adult", "Latest", RowStyle.POSTER, "kind" to kind, "sort" to "latest"),
                row(s, "adult", "Hottest", RowStyle.LANDSCAPE, "kind" to kind, "sort" to "hottest"),
                row(s, "adult", "Top Rated", RowStyle.POSTER, "kind" to kind, "sort" to "rating"),
            ),
        )
        return listOf(cat("gw:adult-movies", "Movies", "movies"), cat("gw:adult-series", "Series", "tv-series"))
    }
}

/**
 * [CatalogRepository] + [RemoteSearch] over the user's gateway for Home / Anime / Drama (+ Adult, only
 * when [adultAllowed]). Metadata only: playable sources are resolved at play time (GatewaySourceResolver).
 * Rows carry no itemIds; [row]/[rowPage] fetch them. Network failures throw → HybridCatalog falls back.
 */
class GatewayCatalogSource(
    private val api: GatewayApi,
    private val slugs: SlugStore = InMemorySlugStore(),
    private val colors: DominantColors = NoDominantColors,
    private val adultAllowed: () -> Boolean = { false },
) : CatalogRepository, RemoteSearch, PagedCatalog, RoutedCatalog, SearchSuggestions {

    private val summaries = ConcurrentHashMap<String, Item>()
    private val details = ConcurrentHashMap<String, Item>()

    /** Titles whose stream resolve found nothing: hidden from every later row / search result. */
    private val unplayable = ConcurrentHashMap.newKeySet<String>()

    fun markUnplayable(id: String) {
        unplayable += id
        summaries.remove(id)
    }

    fun isUnplayable(id: String): Boolean = id in unplayable

    override fun ownsItem(id: String): Boolean = GatewayMapper.isGatewayId(id)
    override fun ownsRow(rowId: String): Boolean = rowId.startsWith(GatewayLayout.ROW_PREFIX)

    fun supports(space: Space): Boolean = space.isVideo || (space == Space.ADULT && adultAllowed())

    private fun check(space: Space) {
        require(supports(space)) { "Gateway does not serve $space" }
    }

    private fun remember(items: List<Item>): List<Item> {
        items.forEach { item ->
            summaries.putIfAbsent(item.id, item)
            item.slug?.let { slugs.put(item.id, it) }
        }
        return items
    }

    /** Last summary seen for [id] (cards → Details instant header, resolver slug lookup). */
    fun summary(id: String): Item? = details[id] ?: summaries[id]

    fun slugOf(id: String): String? = details[id]?.slug ?: summaries[id]?.slug ?: slugs.get(id)

    private fun map(list: List<GwItem>, space: Space?, cat: String): List<Item> =
        remember(
            list.mapNotNull { GatewayMapper.toItem(it, space, cat, colors) }
                .filter { space == Space.ADULT || (it.space != Space.ADULT && !GatewayMapper.isAdult(it.genres)) }
                .filter { it.id !in unplayable }
                .distinctBy { it.id },
        )

    private suspend fun specs(space: Space): Pair<List<GatewayLayout.CatSpec>, List<Item>> {
        check(space)
        return when (space) {
            Space.HOME -> {
                val home = api.home()
                val banner = home.sections.firstOrNull { it.section.equals("Banner", true) }?.items.orEmpty()
                listOf(GatewayLayout.homeMovies(home.sections), GatewayLayout.homeSeries()) to map(banner, Space.HOME, "gw:home-movies")
            }
            Space.ANIME -> GatewayLayout.anime() to emptyList()
            Space.DRAMA -> GatewayLayout.drama() to emptyList()
            Space.ADULT -> GatewayLayout.adult() to emptyList()
            else -> throw IllegalArgumentException("Gateway does not serve $space")
        }
    }

    override suspend fun categories(space: Space): List<Category> {
        val (cats, banner) = specs(space)
        return cats.mapIndexed { i, c ->
            // Probe the first row of the first tab: a dead gateway throws here → HybridCatalog falls back.
            val heroes = when {
                i == 0 && banner.isNotEmpty() -> banner.map { it.id }
                i == 0 -> row(c.rows.first().id).take(6).map { it.id }
                else -> emptyList()
            }
            Category(c.id, space, c.title, heroes, c.rows.map { Row(it.id, it.title, it.style, emptyList()) })
        }
    }

    override suspend fun item(id: String): Item {
        details[id]?.let { return it }
        val slug = slugOf(id) ?: throw NotFoundException(id)
        val summary = summaries[id]
        if (summary?.space == Space.ADULT || id.startsWith(GatewayMapper.ADULT_PREFIX)) check(Space.ADULT)
        val d = api.detail(slug).data ?: throw NotFoundException(id)
        val item = GatewayMapper.detailToItem(d, slug, summary, colors)?.let {
            // Keep the id the card used (adult prefix etc.) even if the detail infers another space.
            if (it.id != id) it.copy(id = id, space = summary?.space ?: if (id.startsWith(GatewayMapper.ADULT_PREFIX)) Space.ADULT else it.space) else it
        } ?: throw NotFoundException(id)
        details[id] = item
        item.slug?.let { slugs.put(id, it) }
        return item
    }

    override suspend fun row(rowId: String): List<Item> = rowPage(rowId, 1).items

    override suspend fun rowPage(rowId: String, page: Int): RowPage {
        val spec = GatewayLayout.parseRow(rowId) ?: throw NotFoundException(rowId)
        check(spec.space)
        if (spec.path == GatewayLayout.HOME_SECTION) {
            if (page > 1) return RowPage(emptyList(), page, false)
            val name = spec.params["section"].orEmpty()
            val sec = api.home().sections.firstOrNull { it.section == name } ?: throw NotFoundException(rowId)
            return RowPage(map(sec.items, spec.space, "gw:home-movies"), 1, false)
        }
        val p = api.list(spec.path, spec.params, page)
        return RowPage(map(p.items, spec.space, rowId), p.page, p.hasMore && p.items.isNotEmpty())
    }

    override suspend fun byGenre(space: Space, genre: String): List<Item> {
        check(space)
        return when (space) {
            Space.HOME -> map(api.list("movies", mapOf("genre" to genre)).items, space, "gw:genre")
            Space.ANIME -> map(api.list("animation", mapOf("genre" to genre)).items, space, "gw:genre")
            Space.DRAMA -> map(api.list("tv-series", mapOf("country" to "Korea", "genre" to genre)).items, space, "gw:genre")
            else -> summaries.values.filter { it.space == space && it.genres.any { g -> g.equals(genre, true) } }
        }
    }

    override suspend fun genres(space: Space): List<String> {
        check(space)
        return GatewayLayout.genres(space)
    }

    override suspend fun allItems(space: Space): List<Item> {
        check(space)
        val seen = summaries.values.filter { it.space == space }
        if (seen.isNotEmpty()) return seen
        val first = specs(space).first.first().rows.first()
        row(first.id)
        return summaries.values.filter { it.space == space }
    }

    /** Global search: video spaces only — adult results are dropped (never in global search). */
    override suspend fun search(query: String): List<Item> {
        if (query.isBlank()) return emptyList()
        return map(api.search(query.trim()).items, null, "gw:search").filter { it.space.isVideo }
    }

    override suspend fun suggest(query: String): List<String> =
        if (query.isBlank()) emptyList() else api.suggest(query.trim())
}
