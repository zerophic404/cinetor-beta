package com.mythbrix.cinetor.core.data

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

/** Content spaces. Only [isVideo] spaces participate in global search / recents / continue watching. */
@Serializable
enum class Space(val displayName: String) {
    HOME("Home"),
    ANIME("Anime"),
    DRAMA("Drama"),
    TV("Live TV & Sports"),
    MUSIC("Music"),
    COMICS("Comics"),
    ADULT("Adult");

    /** Searchable video space (Home/Anime/Drama). */
    val isVideo: Boolean get() = this == HOME || this == ANIME || this == DRAMA

    /** Isolated space with its own nav + in-space search. */
    val isIsolated: Boolean get() = !isVideo

    /** Asset path of the space's catalog file, e.g. `mock/home.json`. */
    val assetPath: String get() = "mock/${name.lowercase()}.json"

    companion object {
        val VIDEO: List<Space> = entries.filter { it.isVideo }
        val ISOLATED: List<Space> = entries.filter { it.isIsolated }
    }
}

@Serializable
enum class ItemType { MOVIE, SERIES, CHANNEL, EVENT, ALBUM, TRACK, COMIC, VIDEO }

@Serializable
enum class RowStyle { POSTER, LANDSCAPE, TOP10, CONTINUE, CIRCLE, CHANNEL }

@Serializable
enum class SourceKind { HLS, DASH, MP4, MKV, WEBM, RTSP, SS, MP3 }

@Serializable
enum class SubFormat { VTT, SRT, ASS }

@Serializable
enum class ComicFormat { PAGED, LONGSTRIP }

@Serializable
enum class ReadingDirection { RTL, LTR, TTB }

@Serializable
enum class EventStatus { LIVE, UPCOMING, FINISHED }

@Immutable
@Serializable
data class Row(
    val id: String,
    val title: String,
    val style: RowStyle,
    val itemIds: List<String>,
)

@Immutable
@Serializable
data class Category(
    val id: String,
    val space: Space,
    val title: String,
    val heroIds: List<String>,
    val rows: List<Row>,
)

@Immutable
@Serializable
data class Source(
    val url: String,
    val kind: SourceKind,
    val label: String? = null,
    val headers: Map<String, String> = emptyMap(),
)

@Immutable
@Serializable
data class Sub(
    val lang: String,
    val label: String,
    val url: String,
    val format: SubFormat,
)

@Immutable
@Serializable
data class Episode(
    val id: String,
    val n: Int,
    val title: String,
    val synopsis: String? = null,
    val durationSec: Int,
    val still: String,
    val sources: List<Source>,
    val subtitles: List<Sub> = emptyList(),
    val introStartSec: Int? = null,
    val introEndSec: Int? = null,
)

@Immutable
@Serializable
data class Season(
    val n: Int,
    val title: String? = null,
    val episodes: List<Episode>,
)

/** COMIC chapter; [pages] are ordered image paths. */
@Immutable
@Serializable
data class Chapter(
    val id: String,
    val n: Int,
    val title: String,
    val pages: List<String>,
)

/** TV programme; [startOffsetSec] is relative to the file-level `epgAnchor`. */
@Immutable
@Serializable
data class Program(
    val id: String,
    val title: String,
    val genre: String? = null,
    val synopsis: String? = null,
    val startOffsetSec: Long,
    val durationSec: Int,
)

@Immutable
@Serializable
data class Team(
    val name: String,
    val short: String,
    val color: String,
    val score: Int? = null,
)

@Immutable
@Serializable
data class Item(
    val id: String,
    val space: Space,
    val categoryId: String,
    val type: ItemType,
    val title: String,
    val altTitle: String? = null,
    val year: Int,
    val rating: Double,
    val maturity: String,
    val genres: List<String>,
    val synopsis: String,
    /** Relative to the mock root, e.g. `images/posters/x.webp`. */
    val poster: String,
    val backdrop: String,
    val logo: String? = null,
    /** `#RRGGBB`, precomputed — zero runtime palette cost. */
    val dominantColor: String,
    val accentColor: String,
    val durationSec: Int? = null,
    val seasons: List<Season> = emptyList(),
    val sources: List<Source> = emptyList(),
    val subtitles: List<Sub> = emptyList(),
    val introStartSec: Int? = null,
    val introEndSec: Int? = null,
    /** COMIC chapters. */
    val pages: List<Chapter> = emptyList(),
    val format: ComicFormat? = null,
    val readingDirection: ReadingDirection? = null,
    val live: Boolean = false,
    val channelNumber: Int? = null,
    val channelGroup: String? = null,
    val programs: List<Program> = emptyList(),
    val league: String? = null,
    val status: EventStatus? = null,
    val startOffsetSec: Long? = null,
    val homeTeam: Team? = null,
    val awayTeam: Team? = null,
    val artist: String? = null,
    val album: String? = null,
    val albumId: String? = null,
    val trackNumber: Int? = null,
    val trackIds: List<String> = emptyList(),
    val cast: List<String> = emptyList(),
    val studio: String? = null,
)

@Immutable
@Serializable
data class CatalogAssets(val avatars: List<String> = emptyList())

/** One mock catalog file (`home.json`, `anime.json`, ...). */
@Immutable
@Serializable
data class CatalogFile(
    val space: Space,
    val version: Int = 1,
    val genres: List<String> = emptyList(),
    /** TV only: epoch seconds that programme offsets are relative to. */
    val epgAnchor: Long? = null,
    val assets: CatalogAssets? = null,
    val categories: List<Category>,
    val items: List<Item>,
)

/** Thrown when an id (item, row) does not exist in the catalog. */
class NotFoundException(val id: String) : NoSuchElementException("Not found: $id")

/** Parses `#RRGGBB` / `#RRGGBBAA` into a 0xAARRGGBB long. */
fun parseHexColor(hex: String): Long {
    val h = hex.removePrefix("#")
    return when (h.length) {
        6 -> 0xFF000000L or h.toLong(16)
        8 -> {
            val rgb = h.substring(0, 6).toLong(16)
            val a = h.substring(6, 8).toLong(16)
            (a shl 24) or rgb
        }
        else -> throw IllegalArgumentException("Bad color: $hex")
    }
}

/** Mock image path → Coil-loadable asset URI. */
object AssetUris {
    const val MOCK_ROOT = "file:///android_asset/mock/"
    fun of(relativePath: String): String = MOCK_ROOT + relativePath
}
