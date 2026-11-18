package com.mythbrix.cinetor.core.data.tmdb

import com.mythbrix.cinetor.core.data.CastMember
import com.mythbrix.cinetor.core.data.Episode
import com.mythbrix.cinetor.core.data.Item
import com.mythbrix.cinetor.core.data.ItemType
import com.mythbrix.cinetor.core.data.Season
import com.mythbrix.cinetor.core.data.Space
import kotlin.math.abs
import kotlin.math.roundToInt

/** TMDB image URLs; size bucket picked from the on-screen pixel width (one step smaller on LOW tier). */
object TmdbImages {
    const val BASE = "https://image.tmdb.org/t/p/"
    private val SIZES = listOf(185 to "w185", 342 to "w342", 780 to "w780", 1280 to "w1280")
    private val SIZE_SEGMENT = Regex("/t/p/(w\\d+|original)/")

    fun of(path: String?, size: String): String? = path?.takeIf { it.isNotBlank() }?.let { BASE + size + it }

    fun bucketFor(targetPx: Int, lowTier: Boolean): String {
        val idx = SIZES.indexOfFirst { it.first >= targetPx }.let { if (it < 0) SIZES.lastIndex else it }
        val pick = if (lowTier) (idx - 1).coerceAtLeast(0) else idx
        return SIZES[pick].second
    }

    /** Rewrites the size segment of a TMDB URL; non-TMDB paths are returned unchanged. */
    fun resize(url: String, size: String): String =
        if (url.startsWith(BASE)) url.replaceFirst(SIZE_SEGMENT, "/t/p/$size/") else url

    fun sized(url: String, targetPx: Int, lowTier: Boolean): String = resize(url, bucketFor(targetPx, lowTier))
}

/** Maps TMDB DTOs to the shared [Item] contract. */
object TmdbMapper {
    const val ID_PREFIX = "tmdb-"
    const val MOVIE = "movie"
    const val TV = "tv"
    const val ANIMATION = 16

    private val DRAMA_COUNTRIES = setOf("KR", "CN", "TW", "HK", "JP")
    private val DRAMA_LANGS = setOf("ko", "zh", "cn", "ja")

    /** Unified genre names (movie + tv lists merged; "Sci-Fi & Fantasy" → Sci-Fi, Fantasy). */
    val MOVIE_GENRES: Map<Int, List<String>> = mapOf(
        28 to listOf("Action"), 12 to listOf("Adventure"), 16 to listOf("Animation"), 35 to listOf("Comedy"),
        80 to listOf("Crime"), 99 to listOf("Documentary"), 18 to listOf("Drama"), 10751 to listOf("Family"),
        14 to listOf("Fantasy"), 36 to listOf("History"), 27 to listOf("Horror"), 10402 to listOf("Music"),
        9648 to listOf("Mystery"), 10749 to listOf("Romance"), 878 to listOf("Sci-Fi"), 10770 to listOf("TV Movie"),
        53 to listOf("Thriller"), 10752 to listOf("War"), 37 to listOf("Western"),
    )
    val TV_GENRES: Map<Int, List<String>> = mapOf(
        10759 to listOf("Action", "Adventure"), 16 to listOf("Animation"), 35 to listOf("Comedy"),
        80 to listOf("Crime"), 99 to listOf("Documentary"), 18 to listOf("Drama"), 10751 to listOf("Family"),
        10762 to listOf("Kids"), 9648 to listOf("Mystery"), 10763 to listOf("News"), 10764 to listOf("Reality"),
        10765 to listOf("Sci-Fi", "Fantasy"), 10766 to listOf("Soap"), 10767 to listOf("Talk"),
        10768 to listOf("War", "Politics"), 37 to listOf("Western"),
    )

    fun genreId(media: String, name: String): Int? =
        (if (media == MOVIE) MOVIE_GENRES else TV_GENRES).entries
            .firstOrNull { e -> e.value.any { it.equals(name, ignoreCase = true) } }?.key

    fun genreNames(media: String, ids: List<Int>): List<String> {
        val table = if (media == MOVIE) MOVIE_GENRES else TV_GENRES
        return ids.flatMap { table[it].orEmpty() }.filter { it != "Animation" }.distinct()
    }

    fun itemId(media: String, id: Long): String = ID_PREFIX + (if (media == MOVIE) "m-" else "t-") + id

    /** `tmdb-m-550` → ("movie", 550); null if not a TMDB id. */
    fun parseId(id: String): Pair<String, Long>? {
        if (!id.startsWith(ID_PREFIX)) return null
        val rest = id.removePrefix(ID_PREFIX)
        val media = when {
            rest.startsWith("m-") -> MOVIE
            rest.startsWith("t-") -> TV
            else -> return null
        }
        return rest.substring(2).toLongOrNull()?.let { media to it }
    }

    /**
     * Space rules: genre 16 + Japan → ANIME; non-animated TV from KR/CN/TW/HK/JP → DRAMA; else HOME.
     * Never returns an isolated space.
     */
    fun spaceOf(media: String, genreIds: Collection<Int>, originCountry: List<String>, originalLanguage: String?): Space {
        val japanese = "JP" in originCountry || originalLanguage == "ja"
        if (ANIMATION in genreIds && japanese) return Space.ANIME
        if (media == TV && ANIMATION !in genreIds) {
            val asian = originCountry.any { it in DRAMA_COUNTRIES } ||
                (originCountry.isEmpty() && originalLanguage in DRAMA_LANGS)
            if (asian) return Space.DRAMA
        }
        return Space.HOME
    }

    fun categoryIdFor(space: Space, media: String, originCountry: List<String>, originalLanguage: String?): String =
        when (space) {
            Space.ANIME -> if (media == MOVIE) TmdbCatalog.ANIME_MOVIES else TmdbCatalog.ANIME_SERIES
            Space.DRAMA -> when {
                "KR" in originCountry || originalLanguage == "ko" -> TmdbCatalog.DRAMA_K
                originCountry.any { it == "CN" || it == "TW" || it == "HK" } || originalLanguage == "zh" -> TmdbCatalog.DRAMA_C
                else -> TmdbCatalog.DRAMA_J
            }
            else -> if (media == MOVIE) TmdbCatalog.HOME_MOVIES else TmdbCatalog.HOME_SERIES
        }

    /** Deterministic dark placeholder until [ColorCache] computes the real dominant colour. */
    fun fallbackColor(seed: String): String = FALLBACKS[abs(seed.hashCode() % FALLBACKS.size)]

    private val FALLBACKS = listOf("#3A0D12", "#1E1A2E", "#122630", "#2B1B10", "#1A2A1A", "#2E1024", "#14202E")

    private fun year(date: String?): Int = date?.take(4)?.toIntOrNull() ?: 0
    private fun rating(v: Double): Double = (v * 10).roundToInt() / 10.0

    /**
     * Search/list result → [Item]; null for people, adult titles or unknown media.
     * [mediaHint] is used when the endpoint omits `media_type`; [forcedSpace] when the row's query already
     * defines the space (e.g. drama discover rows).
     */
    fun toItem(r: TmdbResult, mediaHint: String?, forcedSpace: Space? = null): Item? {
        if (r.adult) return null
        val media = r.mediaType ?: mediaHint ?: return null
        if (media != MOVIE && media != TV) return null
        val title = (if (media == MOVIE) r.title else r.name) ?: r.title ?: r.name ?: return null
        val original = if (media == MOVIE) r.originalTitle else r.originalName
        val space = forcedSpace?.takeIf { it.isVideo }
            ?: spaceOf(media, r.genreIds, r.originCountry, r.originalLanguage)
        val id = itemId(media, r.id)
        val poster = TmdbImages.of(r.posterPath, "w342") ?: TmdbImages.of(r.backdropPath, "w342") ?: ""
        return Item(
            id = id,
            space = space,
            categoryId = categoryIdFor(space, media, r.originCountry, r.originalLanguage),
            type = if (media == MOVIE) ItemType.MOVIE else ItemType.SERIES,
            title = title,
            altTitle = original?.takeIf { it != title },
            year = year(if (media == MOVIE) r.releaseDate else r.firstAirDate),
            rating = rating(r.voteAverage),
            maturity = "NR",
            genres = genreNames(media, r.genreIds),
            synopsis = r.overview.orEmpty(),
            poster = poster,
            backdrop = TmdbImages.of(r.backdropPath, "w780") ?: poster,
            dominantColor = fallbackColor(id),
            accentColor = "#E50914",
        )
    }

    /** Full details → [Item] (episodes from [seasonDetails] when fetched, otherwise synthesised). */
    fun detailsToItem(
        d: TmdbDetails,
        media: String,
        knownSpace: Space?,
        seasonDetails: Map<Int, TmdbSeason> = emptyMap(),
    ): Item {
        val id = itemId(media, d.id)
        val genreIds = d.genres.map { it.id }
        val space = knownSpace?.takeIf { it.isVideo } ?: spaceOf(media, genreIds, d.originCountry, d.originalLanguage)
        val title = (if (media == MOVIE) d.title else d.name) ?: d.title ?: d.name ?: id
        val original = if (media == MOVIE) d.originalTitle else d.originalName
        val poster = TmdbImages.of(d.posterPath, "w342") ?: TmdbImages.of(d.backdropPath, "w342") ?: ""
        val backdrop = TmdbImages.of(d.backdropPath, "w780") ?: poster
        val epRuntime = d.episodeRunTime.firstOrNull() ?: 45
        val seasons = if (media == TV) {
            d.seasons.filter { it.seasonNumber > 0 && it.episodeCount > 0 }.map { s ->
                val real = seasonDetails[s.seasonNumber]?.episodes
                val episodes = if (!real.isNullOrEmpty()) {
                    real.map { e ->
                        Episode(
                            id = "$id-s${s.seasonNumber}e${e.episodeNumber}",
                            n = e.episodeNumber,
                            title = e.name ?: "Episode ${e.episodeNumber}",
                            synopsis = e.overview?.takeIf { it.isNotBlank() },
                            durationSec = (e.runtime ?: epRuntime) * 60,
                            still = TmdbImages.of(e.stillPath, "w342") ?: backdrop,
                            sources = emptyList(),
                        )
                    }
                } else {
                    (1..s.episodeCount).map { n ->
                        Episode(
                            id = "$id-s${s.seasonNumber}e$n",
                            n = n,
                            title = "Episode $n",
                            durationSec = epRuntime * 60,
                            still = backdrop,
                            sources = emptyList(),
                        )
                    }
                }
                Season(n = s.seasonNumber, title = s.name, episodes = episodes)
            }
        } else {
            emptyList()
        }
        return Item(
            id = id,
            space = space,
            categoryId = categoryIdFor(space, media, d.originCountry, d.originalLanguage),
            type = if (media == MOVIE) ItemType.MOVIE else ItemType.SERIES,
            title = title,
            altTitle = original?.takeIf { it != title },
            year = (if (media == MOVIE) d.releaseDate else d.firstAirDate)?.take(4)?.toIntOrNull() ?: 0,
            rating = rating(d.voteAverage),
            maturity = "NR",
            genres = d.genres.flatMap { g -> (if (media == MOVIE) MOVIE_GENRES else TV_GENRES)[g.id] ?: listOf(g.name) }
                .filter { it != "Animation" }
                .distinct(),
            synopsis = d.overview.orEmpty(),
            poster = poster,
            backdrop = backdrop,
            dominantColor = fallbackColor(id),
            accentColor = "#E50914",
            durationSec = if (media == MOVIE) d.runtime?.let { it * 60 } else null,
            seasons = seasons,
            cast = d.credits?.cast.orEmpty().sortedBy { it.order }.take(12).map { it.name },
            castMembers = d.credits?.cast.orEmpty().sortedBy { it.order }.take(12).map { c ->
                CastMember(
                    name = c.name,
                    role = c.character?.takeIf { it.isNotBlank() },
                    photo = c.profilePath?.takeIf { it.isNotBlank() }?.let { "tmdb:$it" },
                )
            },
            studio = d.productionCompanies.firstOrNull()?.name,
        )
    }
}
