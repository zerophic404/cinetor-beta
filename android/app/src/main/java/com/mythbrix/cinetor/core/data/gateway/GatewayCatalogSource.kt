package com.mythbrix.cinetor.core.data.gateway

import com.mythbrix.cinetor.core.data.CatalogRepository
import com.mythbrix.cinetor.core.data.Category
import com.mythbrix.cinetor.core.data.DubOption
import com.mythbrix.cinetor.core.data.Episode
import com.mythbrix.cinetor.core.data.Item
import com.mythbrix.cinetor.core.data.ItemType
import com.mythbrix.cinetor.core.data.NotFoundException
import com.mythbrix.cinetor.core.data.RemoteSearch
import com.mythbrix.cinetor.core.data.Row
import com.mythbrix.cinetor.core.data.RowStyle
import com.mythbrix.cinetor.core.data.Season
import com.mythbrix.cinetor.core.data.Space
import com.mythbrix.cinetor.core.data.fullLanguageName
import com.mythbrix.cinetor.core.data.tmdb.TmdbMapper
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonElement
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale
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
    private val DRAMA_COUNTRIES = setOf(
        "korea", "south korea", "china", "japan", "taiwan", "hong kong",
        "pakistan", "thailand", "philippines", "turkey",
    )

    /**
     * Cinetor Beta `AdultCatalog.safeName`: rejects any title hinting at minors, non-consent,
     * leaks, incest, animals, or cartoons/hentai regardless of source tags.
     */
    private val ADULT_BLOCKED = Regex(
        """(?i)\b(teen\w*|school\w*|college girl|student\w*|tuition|beti|bachi|underage|minors?|enjo|first (time|pain)|lolita|loli|shota|child\w*|kids?|young girls?|virgin|step[- ]?(daughter|son|sister|brother|mom|mother)|rape\w*|forced?|drugged|sleeping|unconscious|incest|dog|animal\w*|bestial\w*|anime|animation|hentai|cartoon|leak\w*|mms|hidden cam\w*|spy cam\w*)\b""",
    )

    private val CANONICAL_DUBS = listOf(
        "Hindi" to Regex("""(?i)(\[hindi]|\bhindi\b|हिन्दी)"""),
        "Bengali" to Regex("""(?i)(\[bengali]|\[bangla]|\bbengali\b|\bbangla\b|বাংলা|zee\s*bangla|star\s*jalsha|sony\s*aath)"""),
        "Tamil" to Regex("""(?i)(\[tamil]|\btamil\b)"""),
        "Telugu" to Regex("""(?i)(\[telugu]|\btelugu\b)"""),
        "English" to Regex("""(?i)(\[english]|\benglish(?:\s+dub(?:bed)?)?\b)"""),
    )

    private val TITLE_BRACKET_DUBS = listOf(
        "Hindi" to "[Hindi]",
        "Bengali" to "[Bengali]",
        "Bengali" to "[Bangla]",
        "Tamil" to "[Tamil]",
        "Telugu" to "[Telugu]",
        "English" to "[English]",
    )

    fun safeName(name: String): Boolean = !ADULT_BLOCKED.containsMatchIn(name)

    fun isGatewayId(id: String): Boolean = id.startsWith(ID_PREFIX) || id.startsWith(ADULT_PREFIX)

    fun idOf(subjectId: String, space: Space): String = (if (space == Space.ADULT) ADULT_PREFIX else ID_PREFIX) + subjectId

    fun subjectIdOf(id: String): String? = when {
        id.startsWith(ADULT_PREFIX) -> id.removePrefix(ADULT_PREFIX)
        id.startsWith(ID_PREFIX) -> id.removePrefix(ID_PREFIX)
        else -> null
    }?.takeIf { it.isNotBlank() }

    fun genresOf(raw: String?): List<String> =
        raw?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()

    fun isAdult(genres: List<String>, badge: String? = null): Boolean =
        genres.any { it.lowercase() in ADULT_GENRES } || badge?.contains("18+", ignoreCase = true) == true

    fun isAnime(genres: List<String>): Boolean =
        genres.any { it.equals("Anime", ignoreCase = true) || it.equals("Animation", ignoreCase = true) }

    /** Extracts canonical dub languages (`Hindi`, `Bengali`, `Tamil`, `Telugu`, `English`) from card metadata. */
    fun dubLanguagesOf(
        name: String?,
        badge: String?,
        slug: String?,
        dubs: JsonElement?,
    ): List<String> {
        val found = LinkedHashSet<String>()
        val n = name.orEmpty()
        for ((canonical, token) in TITLE_BRACKET_DUBS) {
            if (n.contains(token, ignoreCase = true)) found += canonical
        }
        val extraSignals = buildList {
            badge?.takeIf { it.isNotBlank() }?.let { add(it) }
            addAll(dubs.stringList())
            slug?.takeIf { it.isNotBlank() }?.let { s ->
                if (Regex("""(?i)(^|-)bengali(-|$)|zee-bangla""").containsMatchIn(s)) add("Bengali")
                if (Regex("""(?i)(^|-)hindi(-|$)""").containsMatchIn(s)) add("Hindi")
                if (Regex("""(?i)(^|-)tamil(-|$)""").containsMatchIn(s)) add("Tamil")
                if (Regex("""(?i)(^|-)telugu(-|$)""").containsMatchIn(s)) add("Telugu")
            }
        }
        for (signal in extraSignals) {
            for ((canonical, regex) in CANONICAL_DUBS) {
                if (regex.containsMatchIn(signal)) found += canonical
            }
        }
        return found.toList()
    }

    /** Primary dub badge for poster cards (`Hindi`, `Bengali`, `Tamil`, `Telugu`, `English`), or null. */
    fun extractDubBadge(item: Item): String? {
        for ((canonical, token) in TITLE_BRACKET_DUBS) {
            if (item.title.contains(token, ignoreCase = true)) return canonical
        }
        val canonicalNames = listOf("Hindi", "Bengali", "Tamil", "Telugu", "English")
        return canonicalNames.firstOrNull { lang -> item.genres.any { it.equals(lang, ignoreCase = true) } }
    }

    private fun mergeGenresAndDubs(genres: List<String>, dubs: List<String>): List<String> {
        if (dubs.isEmpty()) return genres
        val out = ArrayList<String>(genres.size + dubs.size)
        out.addAll(genres)
        for (dub in dubs) {
            if (out.none { it.equals(dub, ignoreCase = true) }) out += dub
        }
        return out
    }

    /** Where a search / detail result belongs when no row told us. */
    fun inferSpace(genres: List<String>, type: String?, country: String?, badge: String? = null): Space {
        if (isAdult(genres, badge)) return Space.ADULT
        if (isAnime(genres)) return Space.ANIME
        val isTv = type.equals("tv", ignoreCase = true) || type == "7" || type == "2"
        if (isTv && country?.trim()?.lowercase() in DRAMA_COUNTRIES) return Space.DRAMA
        return Space.HOME
    }

    private fun year(y: String?, date: String?): Int = y?.take(4)?.toIntOrNull() ?: date?.take(4)?.toIntOrNull() ?: 0

    private fun isSeriesItem(g: GwItem): Boolean {
        val t = g.type?.trim()?.lowercase().orEmpty()
        return t == "tv" || t == "7" || t == "2" || g.subjectType == 2 || g.subjectType == 7
    }

    fun toItem(g: GwItem, space: Space?, categoryId: String, colors: DominantColors = NoDominantColors): Item? {
        // Every visible title must be playable: the gateway says up front which ones have no stream.
        if (!g.hasResource) return null
        val sid = g.subjectId?.takeIf { it.isNotBlank() } ?: return null
        val title = g.resolvedName ?: return null
        val resolvedSlug = g.resolvedSlug
        val baseGenres = genresOf(g.genre)
        val isTv = isSeriesItem(g)
        val sp = space ?: inferSpace(baseGenres, if (isTv) "tv" else "movie", g.country, g.badge)
        if (sp == Space.ADULT && !safeName(title)) return null
        val dubs = dubLanguagesOf(title, g.badge, resolvedSlug, g.dubs)
        val genres = mergeGenresAndDubs(baseGenres, dubs)
        val poster = g.posterUrl.orEmpty()
        val id = idOf(sid, sp)
        return Item(
            id = id,
            space = sp,
            categoryId = categoryId,
            type = if (isTv) ItemType.SERIES else ItemType.MOVIE,
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
            slug = resolvedSlug ?: sid,
            dubs = g.dubs.dubOptionList(excludeSubjectId = sid),
        )
    }

    /** Strips bracketed/parenthesized dub & quality suffixes (`"Pushpa [Hindi]"` -> `"Pushpa"`). */
    fun cleanBaseTitle(raw: String): String =
        raw.replace(Regex("""\s*\[[^]]*]"""), "")
            .replace(Regex("""(?i)\s*\([^)]*\b(dub(?:bed)?|hindi|bangla|bengali|english|tamil|telugu|dual\s*audio)\b[^)]*\)"""), "")
            .replace(Regex("""(?i)\s*[-–:]\s*(hindi|bangla|bengali|english|tamil|telugu)\s+dub(?:bed)?\s*$"""), "")
            .trim()

    fun normalizeTitleKey(raw: String): String =
        cleanBaseTitle(raw).lowercase(Locale.US).replace(Regex("""[^\p{L}\p{N}]+"""), " ").trim()

    /** Extracts a normalized full dub language name when a sibling title/badge/slug explicitly names a dub. */
    fun explicitDubLanguageOf(title: String?, badge: String?, slug: String?): String? {
        val t = title.orEmpty()
        Regex("""\[\s*([^\]]+?)\s*]""").findAll(t).forEach { m ->
            val token = m.groupValues[1].trim()
            val mapped = fullLanguageName(token)
            if (mapped != "Original" && !token.matches(Regex("""(?i)^(4k|hd|cam|imax|\d+p)$"""))) {
                return mapped
            }
        }
        val combined = listOfNotNull(t, badge, slug?.replace('-', ' ')).joinToString(" ")
        for ((canonical, regex) in CANONICAL_DUBS) {
            if (regex.containsMatchIn(combined)) return fullLanguageName(canonical)
        }
        return null
    }

    /** Parses `tmdb-tv-293610` / `tmdb-movie-27205` -> `("tv", 293610)` or null. */
    fun parseTmdbSubjectId(subjectId: String?): Pair<String, Int>? {
        val m = Regex("""(?i)^tmdb-(tv|movie)-(\d+)$""").find(subjectId?.trim() ?: return null) ?: return null
        val id = m.groupValues[2].toIntOrNull()?.takeIf { it > 0 } ?: return null
        return m.groupValues[1].lowercase(Locale.US) to id
    }

    /** Maps a [TmdbCandidate] into a playable `gw:tmdb-{tv|movie}-{id}` [Item]. */
    fun tmdbCandidateToItem(c: TmdbCandidate, colors: DominantColors = NoDominantColors): Item {
        val media = if (c.mediaType.equals("tv", ignoreCase = true)) "tv" else "movie"
        val sid = "tmdb-$media-${c.tmdbId}"
        val id = idOf(sid, Space.HOME)
        val poster = c.posterUrl
        val backdrop = c.backdropUrl.ifBlank { poster }
        return Item(
            id = id,
            space = Space.HOME,
            categoryId = "gw:tmdb",
            type = if (media == "tv") ItemType.SERIES else ItemType.MOVIE,
            title = c.title,
            altTitle = c.originalTitle?.takeIf { !it.equals(c.title, ignoreCase = true) },
            year = c.year,
            rating = c.rating,
            maturity = "NR",
            genres = emptyList(),
            synopsis = c.synopsis,
            poster = poster,
            backdrop = backdrop,
            dominantColor = (if (poster.isNotEmpty()) colors.colorFor(poster) else null) ?: TmdbMapper.fallbackColor(id),
            accentColor = ACCENT,
            slug = sid,
        )
    }

    /** Guarantees a `SERIES` item always has at least Season 1 Episode 1 (`s1e1`) so Details & Player never stall. */
    fun ensurePlayableSeasons(item: Item): Item {
        if (item.type != ItemType.SERIES || item.seasons.isNotEmpty()) return item
        val still = item.backdrop.ifBlank { item.poster }
        return item.copy(
            seasons = listOf(
                Season(
                    n = 1,
                    title = "Season 1",
                    episodes = listOf(
                        Episode(
                            id = episodeId(1, 1),
                            n = 1,
                            title = "Episode 1",
                            durationSec = item.durationSec ?: 0,
                            still = still,
                            sources = emptyList(),
                        ),
                    ),
                ),
            ),
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
        val sid = s.subjectId.takeIf { it.isNotBlank() } ?: summary?.let { subjectIdOf(it.id) } ?: return null
        val baseGenres = genresOf(s.genre).ifEmpty { summary?.genres.orEmpty() }
        val rawSeasons = d.resource?.seasons.orEmpty()
        val isTv = s.subjectType == 2 || s.subjectType == 7 || summary?.type == ItemType.SERIES ||
            rawSeasons.any { (it.season ?: it.se) > 0 && (it.maxEp > 0 || it.episodes.isNotEmpty() || !it.allEp.isNullOrBlank()) }
        val space = summary?.space ?: inferSpace(baseGenres, if (isTv) "tv" else "movie", s.countryName, s.corner)
        val resolvedTitle = s.title.ifBlank { summary?.title.orEmpty() }.takeIf { it.isNotBlank() } ?: return null
        if (space == Space.ADULT && !safeName(resolvedTitle)) return null
        val detailDubs = dubLanguagesOf(resolvedTitle, s.corner, s.detailPath ?: slug, s.dubs)
        val summaryDubs = summary?.let { listOfNotNull(extractDubBadge(it)) }.orEmpty()
        val genres = mergeGenresAndDubs(baseGenres, (detailDubs + summaryDubs).distinct())
        val id = idOf(sid, space)
        val poster = s.cover?.url?.takeIf { it.isNotBlank() } ?: summary?.poster.orEmpty()
        val backdrop = s.stills?.url?.takeIf { it.isNotBlank() } ?: summary?.backdrop?.takeIf { it.isNotBlank() } ?: poster
        val epDuration = s.duration?.takeIf { it > 0 } ?: 0
        val parsedSeasons = if (isTv) {
            rawSeasons.mapNotNull { r ->
                val seasonNum = (r.season ?: r.se).takeIf { it > 0 } ?: return@mapNotNull null
                val epMap = r.episodes.mapNotNull { e -> e.episode?.let { n -> n to e } }.toMap()
                val listed = r.allEp.orEmpty().split(',').mapNotNull { it.trim().toIntOrNull() }.filter { it > 0 }
                val nums = when {
                    epMap.isNotEmpty() -> epMap.keys.sorted()
                    listed.isNotEmpty() -> listed.distinct().sorted()
                    r.maxEp > 0 -> (1..r.maxEp).toList()
                    else -> emptyList()
                }
                if (nums.isEmpty()) return@mapNotNull null
                Season(
                    n = seasonNum,
                    title = "Season $seasonNum",
                    episodes = nums.map { n ->
                        val epDetail = epMap[n]
                        Episode(
                            id = episodeId(seasonNum, n),
                            n = n,
                            title = epDetail?.name?.takeIf { it.isNotBlank() } ?: "Episode $n",
                            durationSec = epDuration,
                            still = epDetail?.resolvedStill ?: poster,
                            sources = emptyList(),
                        )
                    },
                )
            }.sortedBy { it.n }
        } else {
            emptyList()
        }
        val color = s.cover?.avgHueDark?.takeIf { it.matches(Regex("^#[0-9a-fA-F]{6}$")) }
            ?: (if (poster.isNotEmpty()) colors.colorFor(poster) else null)
            ?: summary?.dominantColor ?: TmdbMapper.fallbackColor(id)
        val structuredDubs = (s.dubs.dubOptionList(excludeSubjectId = sid) + summary?.dubs.orEmpty())
            .filter { it.subjectId != sid }
            .distinctBy { it.subjectId }
        return ensurePlayableSeasons(
            Item(
                id = id,
                space = space,
                categoryId = summary?.categoryId ?: "gw:detail",
                type = if (isTv) ItemType.SERIES else ItemType.MOVIE,
                title = resolvedTitle,
                year = year(null, s.releaseDate).takeIf { it > 0 } ?: summary?.year ?: 0,
                rating = s.imdbRatingValue?.toDoubleOrNull() ?: summary?.rating ?: 0.0,
                maturity = if (space == Space.ADULT) "18+" else "NR",
                genres = genres,
                synopsis = s.description.orEmpty().ifBlank { summary?.synopsis.orEmpty() },
                poster = poster,
                backdrop = backdrop,
                dominantColor = color,
                accentColor = ACCENT,
                durationSec = if (isTv) null else epDuration.takeIf { it > 0 },
                seasons = parsedSeasons,
                cast = d.stars.filter { it.staffType == 1 }.map { it.name }.filter { it.isNotBlank() }.take(12),
                studio = d.stars.firstOrNull { it.staffType == 2 }?.name?.let { "Dir. $it" },
                slug = s.detailPath?.takeIf { it.isNotBlank() } ?: slug,
                dubs = structuredDubs,
            ),
        )
    }
}

/** Static + dynamic Cinetor Beta gateway shelf layout per space. Row ids encode the query: `gwrow:<SPACE>:<path>?<query>`. */
object GatewayLayout {
    const val ROW_PREFIX = "gwrow:"
    const val CAT_PREFIX = "gw:"
    const val HOME_SECTION = "home"
    const val BANNER = "Banner"

    /** Excludes anime/live/sports and regional Indian/Bengali/Pakistani drama serials from `Space.HOME` (kept in `Space.DRAMA`). */
    internal val HOME_REGIONAL_DRAMA = Regex("""(?i)(indian\s*dramas?|zee\s*bangla|star\s*jalsha|star\s*jalsa|sony\s*aa?th|pakistani?)""")
    private val HOME_SKIP = Regex("""(?i)(\b(anime|live|football|sport)\b|indian\s*dramas?|zee\s*bangla|star\s*jalsha|star\s*jalsa|sony\s*aa?th|pakistani?)""")

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
        RowSpec(space, path, linkedMapOf(*p), title, style)

    val HOME_SERIES_GENRES = listOf("Action", "Comedy", "Crime", "Drama", "Romance", "Sci-Fi", "Horror", "Thriller")
    val ANIME_GENRES = listOf("Action", "Adventure", "Fantasy", "Comedy", "Romance", "Horror", "Sci-Fi", "Family")
    val DRAMA_GENRES = listOf("Romance", "Drama", "Comedy", "History", "Crime", "Thriller", "Fantasy", "Action")

    fun genres(space: Space): List<String> = when (space) {
        Space.HOME -> listOf("Action", "Comedy", "Horror", "Romance", "Thriller", "Crime", "Sci-Fi", "Drama", "Adventure", "Fantasy")
        Space.ANIME -> ANIME_GENRES
        Space.DRAMA -> DRAMA_GENRES
        Space.ADULT -> emptyList()
        else -> emptyList()
    }

    /** Pageable extra shelves on Home (`For You`) — regional Indian/Bengali/Pakistani dramas live in `Space.DRAMA`. */
    fun homeExtraShelves(): List<RowSpec> {
        val s = Space.HOME
        return listOf(
            row(s, "short-tv", "🔥 Hot Short TV", RowStyle.POSTER, "sort" to "hottest"),
            row(s, "movies", "Latest Movies", RowStyle.POSTER, "sort" to "latest"),
            row(s, "tv-series", "Latest Series", RowStyle.POSTER, "sort" to "latest"),
            row(s, "movies", "Hollywood", RowStyle.POSTER, "country" to "United States", "sort" to "hottest"),
            row(s, "movies", "Action", RowStyle.POSTER, "genre" to "Action", "sort" to "hottest"),
            row(s, "movies", "Comedy", RowStyle.POSTER, "genre" to "Comedy", "sort" to "hottest"),
            row(s, "movies", "Horror", RowStyle.POSTER, "genre" to "Horror", "sort" to "hottest"),
            row(s, "movies", "Thriller", RowStyle.POSTER, "genre" to "Thriller", "sort" to "hottest"),
            row(s, "tv-series", "Crime Series", RowStyle.POSTER, "genre" to "Crime", "sort" to "hottest"),
        )
    }

    /**
     * Closest pageable gateway list for a curated `/home` section so `rowPage(rowId, page >= 2)`
     * continues paging endlessly just like Cinetor Beta's `MovieBoxCatalog.sectionShelf`.
     */
    fun sectionFallback(heading: String): RowSpec {
        val h = heading.lowercase()
        val s = Space.HOME
        return when {
            "short tv" in h || "short drama" in h -> row(s, "short-tv", heading, RowStyle.POSTER, "sort" to "hottest")
            "zee bangla" in h -> row(s, "zee-bangla", heading, RowStyle.POSTER)
            "star jalsha" in h || "star jalsa" in h -> row(s, "tv-series", heading, RowStyle.POSTER, "genre" to "Star Jalsha", "country" to "India", "sort" to "latest")
            "sony aath" in h || "sony ath" in h -> row(s, "tv-series", heading, RowStyle.POSTER, "genre" to "Sony AATH", "country" to "India", "sort" to "latest")
            "pakistan" in h -> row(s, "tv-series", heading, RowStyle.POSTER, "country" to "Pakistan", "sort" to "hottest")
            "indian drama" in h -> row(s, "tv-series", heading, RowStyle.POSTER, "country" to "India", "sort" to "hottest")
            "bollywood" in h || ("indian" in h && "drama" !in h) || "south" in h -> row(s, "movies", heading, RowStyle.POSTER, "country" to "India", "sort" to "hottest")
            "hollywood" in h -> row(s, "movies", heading, RowStyle.POSTER, "country" to "United States", "sort" to "hottest")
            "k-drama" in h || "korean" in h -> row(s, "tv-series", heading, RowStyle.POSTER, "country" to "Korea", "sort" to "hottest")
            "c-drama" in h || "chinese" in h -> row(s, "tv-series", heading, RowStyle.POSTER, "country" to "China", "sort" to "hottest")
            "western" in h -> row(s, "tv-series", heading, RowStyle.POSTER, "country" to "United States", "sort" to "hottest")
            "action" in h && "movie" in h -> row(s, "movies", heading, RowStyle.POSTER, "genre" to "Action", "sort" to "hottest")
            "horror" in h -> row(s, "movies", heading, RowStyle.POSTER, "genre" to "Horror", "sort" to "hottest")
            "comedy" in h -> row(s, "movies", heading, RowStyle.POSTER, "genre" to "Comedy", "sort" to "hottest")
            "romance" in h -> row(s, "movies", heading, RowStyle.POSTER, "genre" to "Romance", "sort" to "hottest")
            "series" in h || "shows" in h || " tv" in h -> row(s, "tv-series", heading, RowStyle.POSTER, "sort" to "hottest")
            "coming" in h || "new" in h || "latest" in h -> row(s, "movies", heading, RowStyle.POSTER, "sort" to "latest")
            else -> row(s, "movies", heading, RowStyle.POSTER, "sort" to "hottest")
        }
    }

    private fun hasPlayableHomeCard(sec: GwSection): Boolean = sec.items.any { item ->
        if (!item.hasResource || item.resolvedName == null) return@any false
        val g = GatewayMapper.genresOf(item.genre)
        !GatewayMapper.isAdult(g, item.badge) && !GatewayMapper.isAnime(g)
    }

    /**
     * Home "For You" tab: curated `/home` sections (excluding `Banner`, anime/sport, and regional
     * Indian/Bengali/Pakistani dramas), rendered cleanly as unnumbered [RowStyle.POSTER] rows,
     * followed by pageable Home extra shelves.
     */
    fun homeFeatured(sections: List<GwSection>): CatSpec {
        val s = Space.HOME
        val curated = sections
            .filter { !it.section.equals(BANNER, ignoreCase = true) && !HOME_SKIP.containsMatchIn(it.section) && hasPlayableHomeCard(it) }
            .map { sec ->
                row(s, HOME_SECTION, sec.section, RowStyle.POSTER, "section" to sec.section)
            }
        return CatSpec("gw:home-featured", "For You", curated + homeExtraShelves())
    }

    /** Home Movies tab: paged `/movies` shelves, latest/popular/top-rated first, then per genre. */
    fun homeMovies(): CatSpec {
        val s = Space.HOME
        return CatSpec(
            "gw:home-movies",
            "Movies",
            listOf(
                row(s, "movies", "New Movies", RowStyle.POSTER),
                row(s, "movies", "Popular Movies", RowStyle.POSTER, "sort" to "hottest"),
                row(s, "movies", "Top Rated Movies", RowStyle.POSTER, "sort" to "rating"),
                row(s, "movies", "Hollywood", RowStyle.POSTER, "country" to "United States", "sort" to "hottest"),
            ) + genres(Space.HOME).map { g -> row(s, "movies", "$g Movies", RowStyle.POSTER, "genre" to g) },
        )
    }

    /** `/short-tv` micro-dramas (+ curated short sections when present). */
    fun shortTv(sections: List<GwSection>): CatSpec {
        val s = Space.HOME
        val curated = sections.filter { sec ->
            !HOME_SKIP.containsMatchIn(sec.section) &&
                hasPlayableHomeCard(sec) &&
                (sec.section.contains("Short Drama", true) || sec.section.contains("Short TV", true))
        }.map { sec -> row(s, HOME_SECTION, sec.section, RowStyle.POSTER, "section" to sec.section) }
        return CatSpec(
            "gw:short-tv",
            "Short TV",
            listOf(
                row(s, "short-tv", "Latest Short TV", RowStyle.POSTER),
                row(s, "short-tv", "🔥 Hot Short TV", RowStyle.POSTER, "sort" to "hottest"),
            ) + curated,
        )
    }

    fun homeSeries(): CatSpec {
        val s = Space.HOME
        return CatSpec(
            "gw:home-series",
            "Series",
            listOf(
                row(s, "tv-series", "Latest Series", RowStyle.POSTER),
                row(s, "tv-series", "Popular Series", RowStyle.POSTER, "sort" to "hottest"),
            ) + HOME_SERIES_GENRES.map { g -> row(s, "tv-series", "$g Series", RowStyle.POSTER, "genre" to g) },
        )
    }

    /** Anime space: Cinetor Beta's 5 core shelves (with separate `Cartoons` `/animation` shelf) + genre shelves. */
    fun anime(): List<CatSpec> {
        val s = Space.ANIME
        return listOf(
            CatSpec(
                "gw:anime",
                "Anime",
                listOf(
                    row(s, "tv-series", "Trending Anime", RowStyle.POSTER, "genre" to "Anime", "sort" to "hottest"),
                    row(s, "tv-series", "Latest Anime", RowStyle.POSTER, "genre" to "Anime", "sort" to "latest"),
                    row(s, "tv-series", "Top Rated Anime", RowStyle.POSTER, "genre" to "Anime", "sort" to "rating"),
                    row(s, "movies", "Animated Movies", RowStyle.POSTER, "genre" to "Animation", "sort" to "hottest"),
                    row(s, "animation", "Cartoons", RowStyle.POSTER, "sort" to "hottest"),
                ) + ANIME_GENRES.map { g -> row(s, "animation", "$g Anime", RowStyle.POSTER, "genre" to g) },
            ),
        )
    }

    /** Drama space: Cinetor Beta's 7 filter tabs (`All`, `K-Drama`, `C-Drama`, `J-Drama`, `Pakistani Drama`, `Indian Drama`, `Others`) and all 20 shelves. */
    fun drama(): List<CatSpec> {
        val s = Space.DRAMA
        val kDramaCore = listOf(
            row(s, "tv-series", "Trending K-Drama", RowStyle.POSTER, "country" to "Korea", "sort" to "hottest"),
            row(s, "tv-series", "Latest K-Drama", RowStyle.POSTER, "country" to "Korea", "sort" to "latest"),
            row(s, "tv-series", "Top Rated K-Drama", RowStyle.POSTER, "country" to "Korea", "sort" to "rating"),
            row(s, "tv-series", "Korean Romance", RowStyle.POSTER, "country" to "Korea", "genre" to "Romance", "sort" to "hottest"),
            row(s, "movies", "Korean Movies", RowStyle.POSTER, "country" to "Korea", "sort" to "hottest"),
        )
        val cDramaCore = listOf(
            row(s, "tv-series", "Trending C-Drama", RowStyle.POSTER, "country" to "China", "sort" to "hottest"),
            row(s, "tv-series", "Latest C-Drama", RowStyle.POSTER, "country" to "China", "sort" to "latest"),
            row(s, "tv-series", "Top Rated C-Drama", RowStyle.POSTER, "country" to "China", "sort" to "rating"),
            row(s, "tv-series", "Costume & Historical", RowStyle.POSTER, "country" to "China", "genre" to "History", "sort" to "hottest"),
            row(s, "tv-series", "Chinese Romance", RowStyle.POSTER, "country" to "China", "genre" to "Romance", "sort" to "hottest"),
            row(s, "movies", "Chinese Movies", RowStyle.POSTER, "country" to "China", "sort" to "hottest"),
        )
        val jDramaCore = listOf(
            row(s, "tv-series", "Trending J-Drama", RowStyle.POSTER, "country" to "Japan", "sort" to "hottest"),
            row(s, "tv-series", "Latest J-Drama", RowStyle.POSTER, "country" to "Japan", "sort" to "latest"),
            row(s, "movies", "Japanese Movies", RowStyle.POSTER, "country" to "Japan", "sort" to "hottest"),
        )
        val pkDramaCore = listOf(
            row(s, "tv-series", "Trending Pakistani Drama", RowStyle.POSTER, "country" to "Pakistan", "sort" to "hottest"),
            row(s, "tv-series", "All-Time Best Pakistani Drama", RowStyle.POSTER, "country" to "Pakistan", "sort" to "rating"),
        )
        val inDramaCore = listOf(
            row(s, "tv-series", "Indian Dramas", RowStyle.POSTER, "country" to "India", "sort" to "hottest"),
        )
        val otherAsianCore = listOf(
            row(s, "tv-series", "Thai Dramas", RowStyle.POSTER, "country" to "Thailand", "sort" to "hottest"),
            row(s, "tv-series", "Taiwanese Dramas", RowStyle.POSTER, "country" to "Taiwan", "sort" to "hottest"),
            row(s, "tv-series", "Other Asian Dramas", RowStyle.POSTER, "country" to "Thailand", "sort" to "latest"),
        )

        val allTwentyShelves = kDramaCore + cDramaCore + jDramaCore + pkDramaCore + inDramaCore + otherAsianCore

        return listOf(
            CatSpec("gw:drama-all", "All", allTwentyShelves),
            CatSpec(
                "gw:drama-k",
                "K-Drama",
                kDramaCore + listOf("Comedy", "Crime", "Thriller", "Fantasy").map { g ->
                    row(s, "tv-series", "Korean $g", RowStyle.POSTER, "country" to "Korea", "genre" to g)
                },
            ),
            CatSpec(
                "gw:drama-c",
                "C-Drama",
                cDramaCore + listOf("Comedy", "Fantasy", "Action").map { g ->
                    row(s, "tv-series", "Chinese $g", RowStyle.POSTER, "country" to "China", "genre" to g)
                },
            ),
            CatSpec(
                "gw:drama-j",
                "J-Drama",
                jDramaCore + listOf(
                    row(s, "tv-series", "Top Rated J-Drama", RowStyle.POSTER, "country" to "Japan", "sort" to "rating"),
                    row(s, "tv-series", "Japanese Romance", RowStyle.POSTER, "country" to "Japan", "genre" to "Romance"),
                ),
            ),
            CatSpec(
                "gw:drama-pk",
                "Pakistani Drama",
                pkDramaCore + listOf(
                    row(s, "tv-series", "Latest Pakistani Drama", RowStyle.POSTER, "country" to "Pakistan", "sort" to "latest"),
                    row(s, "tv-series", "Pakistani Romance", RowStyle.POSTER, "country" to "Pakistan", "genre" to "Romance"),
                ),
            ),
            CatSpec(
                "gw:drama-in",
                "Indian Drama",
                inDramaCore + listOf(
                    row(s, "tv-series", "Latest Indian Dramas", RowStyle.POSTER, "country" to "India", "sort" to "latest"),
                    row(s, "tv-series", "Top Rated Indian Dramas", RowStyle.POSTER, "country" to "India", "sort" to "rating"),
                    row(s, "zee-bangla", "Zee Bangla Serials", RowStyle.POSTER),
                    row(s, "tv-series", "Zee Bangla Shows", RowStyle.POSTER, "genre" to "Zee Bangla", "country" to "India", "sort" to "latest"),
                    row(s, "tv-series", "Star Jalsha Shows", RowStyle.POSTER, "genre" to "Star Jalsha", "country" to "India", "sort" to "latest"),
                    row(s, "tv-series", "Sony AATH Series", RowStyle.POSTER, "genre" to "Sony AATH", "country" to "India", "sort" to "latest"),
                ),
            ),
            CatSpec(
                "gw:drama-other",
                "Others",
                otherAsianCore + listOf(
                    row(s, "tv-series", "Filipino Dramas", RowStyle.POSTER, "country" to "Philippines", "sort" to "hottest"),
                    row(s, "tv-series", "Turkish Dramas", RowStyle.POSTER, "country" to "Turkey", "sort" to "hottest"),
                ),
            ),
        )
    }

    /** Adult space: Cinetor Beta category/sort chips (`For You`, `Hottest`, `Latest`, `Rating`, `Movies`, `Series`). */
    fun adult(): List<CatSpec> {
        val s = Space.ADULT
        return listOf(
            CatSpec(
                "gw:adult-foryou",
                "For You",
                listOf(
                    row(s, "adult", "For You", RowStyle.POSTER, "kind" to "movies", "sort" to "foryou"),
                    row(s, "adult", "Hottest", RowStyle.POSTER, "kind" to "movies", "sort" to "hottest"),
                    row(s, "adult", "Latest", RowStyle.POSTER, "kind" to "movies", "sort" to "latest"),
                    row(s, "adult", "Top Rated", RowStyle.POSTER, "kind" to "movies", "sort" to "rating"),
                    row(s, "adult", "Adult Series", RowStyle.POSTER, "kind" to "tv-series", "sort" to "foryou"),
                    row(s, "adult", "Western", RowStyle.POSTER, "kind" to "movies", "sort" to "latest", "country" to "United States"),
                    row(s, "adult", "Korean", RowStyle.POSTER, "kind" to "movies", "sort" to "latest", "country" to "Korea"),
                    row(s, "adult", "Japanese", RowStyle.POSTER, "kind" to "movies", "sort" to "latest", "country" to "Japan"),
                    row(s, "adult", "Filipino", RowStyle.POSTER, "kind" to "movies", "sort" to "latest", "country" to "Philippines"),
                ),
            ),
            CatSpec(
                "gw:adult-hottest",
                "Hottest",
                listOf(
                    row(s, "adult", "Hottest Movies", RowStyle.POSTER, "kind" to "movies", "sort" to "hottest"),
                    row(s, "adult", "Hottest Series", RowStyle.POSTER, "kind" to "tv-series", "sort" to "hottest"),
                ),
            ),
            CatSpec(
                "gw:adult-latest",
                "Latest",
                listOf(
                    row(s, "adult", "Latest Movies", RowStyle.POSTER, "kind" to "movies", "sort" to "latest"),
                    row(s, "adult", "Latest Series", RowStyle.POSTER, "kind" to "tv-series", "sort" to "latest"),
                ),
            ),
            CatSpec(
                "gw:adult-rating",
                "Rating",
                listOf(
                    row(s, "adult", "Top Rated Movies", RowStyle.POSTER, "kind" to "movies", "sort" to "rating"),
                    row(s, "adult", "Top Rated Series", RowStyle.POSTER, "kind" to "tv-series", "sort" to "rating"),
                ),
            ),
            CatSpec(
                "gw:adult-movies",
                "Movies",
                listOf(
                    row(s, "adult", "Latest", RowStyle.POSTER, "kind" to "movies", "sort" to "latest"),
                    row(s, "adult", "Hottest", RowStyle.POSTER, "kind" to "movies", "sort" to "hottest"),
                    row(s, "adult", "Top Rated", RowStyle.POSTER, "kind" to "movies", "sort" to "rating"),
                ),
            ),
            CatSpec(
                "gw:adult-series",
                "Series",
                listOf(
                    row(s, "adult", "Latest", RowStyle.POSTER, "kind" to "tv-series", "sort" to "latest"),
                    row(s, "adult", "Hottest", RowStyle.POSTER, "kind" to "tv-series", "sort" to "hottest"),
                    row(s, "adult", "Top Rated", RowStyle.POSTER, "kind" to "tv-series", "sort" to "rating"),
                ),
            ),
        )
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
    private val tmdbBridge: GatewayTmdbBridge = NoGatewayTmdbBridge,
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

    private fun keepForSpace(raw: GwItem, mapped: Item, space: Space?, fromHomeCurated: Boolean = false): Boolean {
        val rawGenres = GatewayMapper.genresOf(raw.genre)
        if (space == Space.ADULT) {
            return GatewayMapper.safeName(mapped.title)
        }
        if (mapped.space == Space.ADULT || GatewayMapper.isAdult(rawGenres, raw.badge)) {
            return false
        }
        if (fromHomeCurated && space == Space.HOME) {
            if (GatewayMapper.isAnime(rawGenres)) return false
            val regionalSignal = listOfNotNull(raw.badge, raw.genre, raw.resolvedSlug).joinToString(" ")
            if (GatewayLayout.HOME_REGIONAL_DRAMA.containsMatchIn(regionalSignal)) return false
            return true
        }
        return when (space) {
            Space.DRAMA -> !(GatewayMapper.isAnime(rawGenres) && rawGenres.none { it.equals("Drama", true) || it.equals("Romance", true) })
            else -> true
        }
    }

    private fun map(list: List<GwItem>, space: Space?, cat: String, fromHomeCurated: Boolean = false): List<Item> =
        remember(
            list.mapNotNull { raw ->
                val item = GatewayMapper.toItem(raw, space, cat, colors) ?: return@mapNotNull null
                item.takeIf { keepForSpace(raw, it, space, fromHomeCurated) }
            }
                .filter { it.id !in unplayable }
                .distinctBy { it.id },
        )

    private suspend fun specs(space: Space): Pair<List<GatewayLayout.CatSpec>, List<Item>> {
        check(space)
        return when (space) {
            Space.HOME -> {
                val home = api.home()
                val banner = home.sections.firstOrNull { it.section.equals(GatewayLayout.BANNER, true) }?.items.orEmpty()
                listOf(
                    GatewayLayout.homeFeatured(home.sections),
                    GatewayLayout.homeMovies(),
                    GatewayLayout.homeSeries(),
                    GatewayLayout.shortTv(home.sections),
                ) to map(banner, Space.HOME, "gw:home-featured", fromHomeCurated = true)
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
                i == 0 && c.rows.isNotEmpty() -> row(c.rows.first().id).take(6).map { it.id }
                else -> emptyList()
            }
            Category(c.id, space, c.title, heroes, c.rows.map { Row(it.id, it.title, it.style, emptyList()) })
        }
    }

    private fun dubPriority(lang: String): Int = when {
        lang.equals("Hindi", ignoreCase = true) -> 0
        lang.equals("Bangla", ignoreCase = true) -> 1
        lang.equals("English", ignoreCase = true) -> 2
        lang.equals("Tamil", ignoreCase = true) -> 3
        lang.equals("Telugu", ignoreCase = true) -> 4
        else -> 5
    }

    private suspend fun discoverSiblingDubs(baseItem: Item, currentSubjectId: String?): List<DubOption> {
        if (baseItem.space == Space.ADULT) return baseItem.dubs
        val baseTitle = GatewayMapper.cleanBaseTitle(baseItem.title)
        if (baseTitle.isBlank()) return baseItem.dubs
        val normTarget = GatewayMapper.normalizeTitleKey(baseTitle)
        val wantSeries = baseItem.type == ItemType.SERIES
        val hits = runCatching { api.search(baseTitle).items }.getOrDefault(emptyList())
        val merged = LinkedHashMap<String, DubOption>()
        for (d in baseItem.dubs) {
            if (d.subjectId.isNotBlank() && d.subjectId != currentSubjectId) {
                merged[d.subjectId] = d.copy(languageName = fullLanguageName(d.languageName))
            }
        }
        for (hit in hits) {
            if (!hit.hasResource) continue
            val hitName = hit.resolvedName ?: continue
            val hitNorm = GatewayMapper.normalizeTitleKey(hit.baseTitle ?: hitName)
            if (hitNorm != normTarget) continue
            val hitIsSeries = hit.type.equals("tv", true) || hit.type == "2" || hit.type == "7" || hit.subjectType == 2 || hit.subjectType == 7
            if (hitIsSeries != wantSeries) continue
            val hitYear = hit.year?.take(4)?.toIntOrNull() ?: hit.releaseDate?.take(4)?.toIntOrNull() ?: 0
            if (baseItem.year > 0 && hitYear > 0 && kotlin.math.abs(baseItem.year - hitYear) > 1) continue

            for (nested in hit.dubs.dubOptionList(excludeSubjectId = currentSubjectId)) {
                merged.putIfAbsent(nested.subjectId, nested)
            }
            val hitSid = hit.subjectId?.takeIf { it.isNotBlank() && it != currentSubjectId } ?: continue
            val explicitLang = GatewayMapper.explicitDubLanguageOf(hitName, hit.badge, hit.resolvedSlug) ?: continue
            merged.putIfAbsent(
                hitSid,
                DubOption(
                    subjectId = hitSid,
                    slug = hit.resolvedSlug ?: hitSid,
                    languageName = explicitLang,
                ),
            )
        }
        return merged.values.sortedBy { dubPriority(it.languageName) }
    }

    private fun alignItemIdAndSpace(item: Item, requestedId: String, summary: Item?): Item {
        val targetSpace = summary?.space ?: if (requestedId.startsWith(GatewayMapper.ADULT_PREFIX)) Space.ADULT else item.space
        return if (item.id != requestedId || item.space != targetSpace) {
            item.copy(id = requestedId, space = targetSpace)
        } else {
            item
        }
    }

    override suspend fun item(id: String): Item {
        val summary = summaries[id]
        if (summary?.space == Space.ADULT || id.startsWith(GatewayMapper.ADULT_PREFIX)) check(Space.ADULT)
        details[id]?.let { return it }

        val sid = GatewayMapper.subjectIdOf(id)
        val primarySlug = slugOf(id) ?: sid

        var resolved: Item? = primarySlug?.let { slug ->
            runCatching { api.detail(slug).data }.getOrNull()?.let { d ->
                GatewayMapper.detailToItem(d, slug, summary, colors)?.let { alignItemIdAndSpace(it, id, summary) }
            }
        }

        if (resolved == null && sid != null) {
            GatewayMapper.parseTmdbSubjectId(sid)?.let { (media, tmdbId) ->
                runCatching { tmdbBridge.detail(media, tmdbId) }.getOrNull()?.let { c ->
                    resolved = alignItemIdAndSpace(GatewayMapper.tmdbCandidateToItem(c, colors), id, summary)
                }
            }
        }

        if (resolved == null && summary != null) {
            resolved = summary
        }

        if (resolved == null && (!primarySlug.isNullOrBlank() || !sid.isNullOrBlank())) {
            val hint = (primarySlug ?: sid.orEmpty())
                .replace(Regex("""-[A-Za-z0-9]{8,}$"""), "")
                .replace(Regex("""[-_]+"""), " ")
                .trim()
            if (hint.isNotEmpty()) {
                val hits = runCatching { api.search(hint).items }.getOrDefault(emptyList())
                val matchedRaw = (if (sid != null) hits.firstOrNull { it.subjectId == sid } else null)
                    ?: hits.firstOrNull { it.hasResource }
                if (matchedRaw != null) {
                    val mapped = GatewayMapper.toItem(
                        matchedRaw,
                        if (id.startsWith(GatewayMapper.ADULT_PREFIX)) Space.ADULT else null,
                        "gw:detail",
                        colors,
                    )?.let { alignItemIdAndSpace(it, id, summary) }
                    val altSlug = matchedRaw.resolvedSlug?.takeIf { it != primarySlug }
                    val altDetail = altSlug?.let { s ->
                        runCatching { api.detail(s).data }.getOrNull()?.let { d ->
                            GatewayMapper.detailToItem(d, s, mapped, colors)?.let { alignItemIdAndSpace(it, id, mapped) }
                        }
                    }
                    resolved = altDetail ?: mapped
                }
            }
        }

        val base = resolved ?: throw NotFoundException(id)
        if (base.space == Space.ADULT) check(Space.ADULT)

        val enrichedDubs = discoverSiblingDubs(base, sid)
        val playable = GatewayMapper.ensurePlayableSeasons(
            if (enrichedDubs.isNotEmpty() && enrichedDubs != base.dubs) base.copy(dubs = enrichedDubs) else base,
        )
        details[id] = playable
        summaries.putIfAbsent(id, playable)
        playable.slug?.let { slugs.put(id, it) }
        return playable
    }

    override suspend fun row(rowId: String): List<Item> = rowPage(rowId, 1).items

    override suspend fun rowPage(rowId: String, page: Int): RowPage {
        val spec = GatewayLayout.parseRow(rowId) ?: throw NotFoundException(rowId)
        check(spec.space)
        if (spec.path == GatewayLayout.HOME_SECTION) {
            val name = spec.params["section"].orEmpty()
            if (page <= 1) {
                val sec = api.home().sections.firstOrNull { it.section == name } ?: throw NotFoundException(rowId)
                val items = map(sec.items, spec.space, "gw:home-featured", fromHomeCurated = true)
                return RowPage(items, 1, items.isNotEmpty())
            }
            // Endless See-all / horizontal paging for curated /home sections via closest pageable endpoint.
            val fallback = GatewayLayout.sectionFallback(name)
            val p = api.list(fallback.path, fallback.params, page - 1)
            return RowPage(map(p.items, spec.space, rowId, fromHomeCurated = true), page, p.hasMore && p.items.isNotEmpty())
        }
        val p = api.list(spec.path, spec.params, page)
        // Trust the requested page number (some gateway lists echo `page:1` for every page).
        return RowPage(map(p.items, spec.space, rowId), page, p.hasMore && p.items.isNotEmpty())
    }

    override suspend fun byGenre(space: Space, genre: String): List<Item> {
        check(space)
        return when (space) {
            Space.HOME -> when {
                genre.equals("Movies", ignoreCase = true) -> map(api.list("movies", emptyMap()).items, space, "gw:genre")
                genre.equals("Series", ignoreCase = true) -> map(api.list("tv-series", emptyMap()).items, space, "gw:genre")
                else -> map(api.list("movies", mapOf("genre" to genre)).items, space, "gw:genre")
            }
            Space.ANIME -> map(api.list("animation", mapOf("genre" to genre)).items, space, "gw:genre")
            Space.DRAMA -> when {
                genre.equals("K-Drama", ignoreCase = true) -> map(api.list("tv-series", mapOf("country" to "Korea", "sort" to "hottest")).items, space, "gw:genre")
                genre.equals("C-Drama", ignoreCase = true) -> map(api.list("tv-series", mapOf("country" to "China", "sort" to "hottest")).items, space, "gw:genre")
                genre.equals("J-Drama", ignoreCase = true) -> map(api.list("tv-series", mapOf("country" to "Japan", "sort" to "hottest")).items, space, "gw:genre")
                genre.equals("Pakistani Drama", ignoreCase = true) -> map(api.list("tv-series", mapOf("country" to "Pakistan", "sort" to "hottest")).items, space, "gw:genre")
                genre.equals("Indian Drama", ignoreCase = true) -> map(api.list("tv-series", mapOf("country" to "India", "sort" to "hottest")).items, space, "gw:genre")
                genre.equals("Others", ignoreCase = true) -> map(api.list("tv-series", mapOf("country" to "Thailand", "sort" to "hottest")).items, space, "gw:genre")
                else -> map(api.list("tv-series", mapOf("country" to "Korea", "genre" to genre)).items, space, "gw:genre")
            }
            Space.ADULT -> {
                val sort = when (genre.lowercase()) {
                    "for you", "foryou" -> "foryou"
                    "hottest", "popular" -> "hottest"
                    "rating", "top rated" -> "rating"
                    else -> "latest"
                }
                map(api.list("adult", mapOf("kind" to "movies", "sort" to sort)).items, space, "gw:genre")
            }
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

    private fun cleanQueryWords(q: String): String =
        q.replace(Regex("""[^\p{L}\p{N}\s]+"""), " ").replace(Regex("""\s+"""), " ").trim()

    /** Global search: video spaces only — combines gateway `/search`, progressive/token fallback, and TMDB `/search/multi`. */
    override suspend fun search(query: String): List<Item> = coroutineScope {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return@coroutineScope emptyList()

        val primaryDeferred = async { map(api.search(trimmed).items, null, "gw:search").filter { it.space.isVideo } }
        val tmdbDeferred = async {
            if (tmdbBridge.isConfigured) runCatching { tmdbBridge.searchMulti(trimmed) }.getOrDefault(emptyList()) else emptyList()
        }

        val triedQueries = LinkedHashSet<String>().apply { add(trimmed.lowercase(Locale.US)) }
        var gwResults = primaryDeferred.await()
        val tmdbHits = tmdbDeferred.await()

        if (gwResults.isEmpty()) {
            val fallbackQueries = ArrayList<String>(5)
            val cleaned = cleanQueryWords(trimmed)
            if (cleaned.isNotEmpty() && triedQueries.add(cleaned.lowercase(Locale.US))) {
                fallbackQueries += cleaned
            }
            runCatching { api.suggest(trimmed).firstOrNull() }.getOrNull()
                ?.replace(Regex("""\s*\[[^]]*]"""), "")
                ?.trim()
                ?.takeIf { it.isNotEmpty() && triedQueries.add(it.lowercase(Locale.US)) }
                ?.let { fallbackQueries += it }

            for (t in tmdbHits.take(2)) {
                for (candidateTitle in listOfNotNull(cleanQueryWords(t.title), t.title.trim(), t.originalTitle?.let(::cleanQueryWords))) {
                    if (candidateTitle.isNotEmpty() && triedQueries.add(candidateTitle.lowercase(Locale.US))) {
                        fallbackQueries += candidateTitle
                    }
                }
            }

            val words = cleaned.split(' ').filter { it.isNotEmpty() }
            if (words.size >= 3) {
                val twoWordPrefix = words.take(2).joinToString(" ")
                if (triedQueries.add(twoWordPrefix.lowercase(Locale.US))) fallbackQueries += twoWordPrefix
                val reordered = "${words.first()} ${words.last()}"
                if (triedQueries.add(reordered.lowercase(Locale.US))) fallbackQueries += reordered
            }

            for (altQuery in fallbackQueries.take(4)) {
                val found = runCatching { map(api.search(altQuery).items, null, "gw:search").filter { it.space.isVideo } }
                    .getOrDefault(emptyList())
                if (found.isNotEmpty()) {
                    gwResults = found
                    break
                }
            }
        } else if (tmdbHits.isNotEmpty()) {
            val topTmdb = tmdbHits.first()
            val topNorm = GatewayMapper.normalizeTitleKey(topTmdb.title)
            if (gwResults.none { GatewayMapper.normalizeTitleKey(it.title) == topNorm }) {
                val canonicalClean = cleanQueryWords(topTmdb.title)
                if (canonicalClean.isNotEmpty() && triedQueries.add(canonicalClean.lowercase(Locale.US))) {
                    val canonicalGw = runCatching {
                        map(api.search(canonicalClean).items, null, "gw:search").filter { it.space.isVideo }
                    }.getOrDefault(emptyList())
                    if (canonicalGw.isNotEmpty()) {
                        gwResults = (canonicalGw + gwResults).distinctBy { it.id }
                    }
                }
            }
        }

        if (tmdbHits.isEmpty()) return@coroutineScope gwResults

        val seenNorm = gwResults.map { "${it.type}:${GatewayMapper.normalizeTitleKey(it.title)}" }.toMutableSet()
        val tmdbExtras = remember(
            tmdbHits.mapNotNull { c ->
                val mapped = GatewayMapper.tmdbCandidateToItem(c, colors)
                val key = "${mapped.type}:${GatewayMapper.normalizeTitleKey(mapped.title)}"
                if (mapped.id in unplayable || !seenNorm.add(key)) null else mapped
            },
        )
        (gwResults + tmdbExtras).distinctBy { it.id }
    }

    override suspend fun castWorks(personName: String): List<Item> = coroutineScope {
        val q = personName.trim()
        if (q.isEmpty()) return@coroutineScope emptyList()
        val gwDeferred = async {
            runCatching { map(api.search(q).items, null, "gw:cast").filter { it.space.isVideo } }.getOrDefault(emptyList())
        }
        val tmdbDeferred = async {
            if (tmdbBridge.isConfigured) runCatching { tmdbBridge.personCredits(q) }.getOrDefault(emptyList()) else emptyList()
        }
        val cachedCastMatches = details.values.filter { item ->
            item.space.isVideo && item.id !in unplayable && item.cast.any { it.equals(q, ignoreCase = true) }
        }
        val gwItems = gwDeferred.await()
        val tmdbItems = remember(tmdbDeferred.await().map { GatewayMapper.tmdbCandidateToItem(it, colors) })

        val seenNorm = LinkedHashSet<String>()
        val seenIds = LinkedHashSet<String>()
        val combined = ArrayList<Item>()
        for (candidate in gwItems + cachedCastMatches + tmdbItems) {
            if (!candidate.space.isVideo || candidate.id in unplayable) continue
            val normKey = GatewayMapper.normalizeTitleKey(candidate.title).ifEmpty { candidate.id }
            if (seenIds.add(candidate.id) && seenNorm.add(normKey)) {
                combined += candidate
            }
        }
        combined
    }

    override suspend fun warmStartupSpaces(): List<String> = coroutineScope {
        val targetSpaces = listOf(Space.HOME, Space.ANIME, Space.DRAMA)
        val perSpaceItems = targetSpaces.map { sp ->
            async {
                runCatching {
                    val cats = categories(sp)
                    val firstCat = cats.firstOrNull() ?: return@runCatching emptyList<Item>()
                    val heroes = firstCat.heroIds.mapNotNull { summary(it) }
                    val rowItems = firstCat.rows.take(2).map { r ->
                        async { runCatching { row(r.id) }.getOrDefault(emptyList()) }
                    }.awaitAll().flatten()
                    heroes + rowItems
                }.getOrDefault(emptyList())
            }
        }.awaitAll().flatten()

        val urls = LinkedHashSet<String>()
        for (item in perSpaceItems) {
            for (u in listOf(item.backdrop, item.poster)) {
                if (u.startsWith("https://") || u.startsWith("http://")) {
                    urls += u
                    if (urls.size >= 24) return@coroutineScope urls.toList()
                }
            }
        }
        urls.toList()
    }

    override suspend fun suggest(query: String): List<String> =
        if (query.isBlank()) emptyList() else api.suggest(query.trim())
}
