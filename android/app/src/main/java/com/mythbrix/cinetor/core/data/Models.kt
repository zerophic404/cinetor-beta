package com.mythbrix.cinetor.core.data

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

// Fragment 2/5 of the ownership notice (see core/legal/Ownership.kt). Never shown in the UI.
internal const val OWNERSHIP_F2 = "2e237a353c7a17232e3238283322747a1e357a34352e7a2e352f3932767a28"

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
data class DubOption(
    val subjectId: String,
    val slug: String,
    val languageName: String,
    val languageCode: String = "",
)

@Immutable
@Serializable
data class Source(
    val url: String,
    val kind: SourceKind,
    val label: String? = null,
    val headers: Map<String, String> = emptyMap(),
    /** Vertical resolution when the provider states it (gateway `1080p` → 1080). */
    val height: Int? = null,
    /** Real file size in bytes when known (gateway `size`). */
    val sizeBytes: Long? = null,
    val codec: String? = null,
    /** Normalized full audio/dub language name (e.g. `"Original"`, `"Hindi"`, `"Bangla"`, `"English"`). */
    val audioLang: String? = null,
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
    /** Cast with photo + character when the source provides them (same order as [cast]). */
    val castMembers: List<CastMember> = emptyList(),
    val studio: String? = null,
    /** Gateway `detail_path` (needed for /detail + /stream); null for mock/TMDB items. */
    val slug: String? = null,
    /** Sibling dubbed editions resolved from `/detail` + `/search` (`Hindi`, `Bangla`, `English`, etc.). */
    val dubs: List<DubOption> = emptyList(),
)

/** One actor: display name, optional character name, optional round photo URL. */
@Immutable
@Serializable
data class CastMember(val name: String, val role: String? = null, val photo: String? = null)

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

/** Image path → Coil-loadable URI: mock paths map to bundled assets, absolute URLs (TMDB) pass through. */
object AssetUris {
    const val MOCK_ROOT = "file:///android_asset/mock/"
    fun of(relativePath: String): String =
        if (isRemote(relativePath)) relativePath else MOCK_ROOT + relativePath

    fun isRemote(path: String): Boolean = path.startsWith("https://") || path.startsWith("http://")
}

private val LANGUAGE_CODE_MAP: Map<String, String> = mapOf(
    "" to "Original",
    "und" to "Original",
    "unknown" to "Original",
    "original" to "Original",
    "orig" to "Original",
    "default" to "Original",
    "mul" to "Original",
    "zxx" to "Original",
    "none" to "Original",
    "bn" to "Bangla",
    "ben" to "Bangla",
    "bengali" to "Bangla",
    "bangla" to "Bangla",
    "en" to "English",
    "eng" to "English",
    "english" to "English",
    "hi" to "Hindi",
    "hin" to "Hindi",
    "hindi" to "Hindi",
    "ur" to "Urdu",
    "urd" to "Urdu",
    "urdu" to "Urdu",
    "zh" to "Chinese",
    "chi" to "Chinese",
    "zho" to "Chinese",
    "zh-cn" to "Chinese",
    "zh-tw" to "Chinese",
    "zh-hk" to "Chinese",
    "zh-sg" to "Chinese",
    "zh-hans" to "Chinese",
    "zh-hant" to "Chinese",
    "cmn" to "Chinese",
    "yue" to "Chinese",
    "chinese" to "Chinese",
    "mandarin" to "Chinese",
    "cantonese" to "Chinese",
    "ar" to "Arabic",
    "ara" to "Arabic",
    "arabic" to "Arabic",
    "ko" to "Korean",
    "kor" to "Korean",
    "korean" to "Korean",
    "ja" to "Japanese",
    "jpn" to "Japanese",
    "japanese" to "Japanese",
    "es" to "Spanish",
    "spa" to "Spanish",
    "spanish" to "Spanish",
    "espanol" to "Spanish",
    "español" to "Spanish",
    "fr" to "French",
    "fre" to "French",
    "fra" to "French",
    "french" to "French",
    "francais" to "French",
    "français" to "French",
    "id" to "Indonesian",
    "ind" to "Indonesian",
    "in" to "Indonesian",
    "indonesian" to "Indonesian",
    "bahasa indonesia" to "Indonesian",
    "tr" to "Turkish",
    "tur" to "Turkish",
    "turkish" to "Turkish",
    "turkce" to "Turkish",
    "türkçe" to "Turkish",
    "ta" to "Tamil",
    "tam" to "Tamil",
    "tamil" to "Tamil",
    "te" to "Telugu",
    "tel" to "Telugu",
    "telugu" to "Telugu",
    "ml" to "Malayalam",
    "mal" to "Malayalam",
    "malayalam" to "Malayalam",
    "pa" to "Punjabi",
    "pan" to "Punjabi",
    "punjabi" to "Punjabi",
    "ru" to "Russian",
    "rus" to "Russian",
    "russian" to "Russian",
    "pt" to "Portuguese",
    "por" to "Portuguese",
    "pt-br" to "Portuguese",
    "pt-pt" to "Portuguese",
    "portuguese" to "Portuguese",
    "portugues" to "Portuguese",
    "português" to "Portuguese",
    "ms" to "Malay",
    "may" to "Malay",
    "msa" to "Malay",
    "malay" to "Malay",
    "bahasa melayu" to "Malay",
    "th" to "Thai",
    "tha" to "Thai",
    "thai" to "Thai",
    "vi" to "Vietnamese",
    "vie" to "Vietnamese",
    "vietnamese" to "Vietnamese",
    "tiếng việt" to "Vietnamese",
    "tl" to "Filipino",
    "fil" to "Filipino",
    "tgl" to "Filipino",
    "filipino" to "Filipino",
    "tagalog" to "Filipino",
    "de" to "German",
    "ger" to "German",
    "deu" to "German",
    "german" to "German",
    "deutsch" to "German",
    "it" to "Italian",
    "ita" to "Italian",
    "italian" to "Italian",
    "italiano" to "Italian",
    "nl" to "Dutch",
    "dut" to "Dutch",
    "nld" to "Dutch",
    "dutch" to "Dutch",
    "pl" to "Polish",
    "pol" to "Polish",
    "polish" to "Polish",
    "uk" to "Ukrainian",
    "ukr" to "Ukrainian",
    "ukrainian" to "Ukrainian",
    "el" to "Greek",
    "gre" to "Greek",
    "ell" to "Greek",
    "greek" to "Greek",
    "he" to "Hebrew",
    "iw" to "Hebrew",
    "heb" to "Hebrew",
    "hebrew" to "Hebrew",
    "fa" to "Persian",
    "per" to "Persian",
    "fas" to "Persian",
    "persian" to "Persian",
    "sv" to "Swedish",
    "swe" to "Swedish",
    "swedish" to "Swedish",
    "no" to "Norwegian",
    "nor" to "Norwegian",
    "nb" to "Norwegian",
    "nn" to "Norwegian",
    "norwegian" to "Norwegian",
    "da" to "Danish",
    "dan" to "Danish",
    "danish" to "Danish",
    "fi" to "Finnish",
    "fin" to "Finnish",
    "finnish" to "Finnish",
    "cs" to "Czech",
    "cze" to "Czech",
    "ces" to "Czech",
    "czech" to "Czech",
    "ro" to "Romanian",
    "rum" to "Romanian",
    "ron" to "Romanian",
    "romanian" to "Romanian",
    "hu" to "Hungarian",
    "hun" to "Hungarian",
    "hungarian" to "Hungarian",
    "mr" to "Marathi",
    "mar" to "Marathi",
    "marathi" to "Marathi",
    "kn" to "Kannada",
    "kan" to "Kannada",
    "kannada" to "Kannada",
    "gu" to "Gujarati",
    "guj" to "Gujarati",
    "gujarati" to "Gujarati",
    "ne" to "Nepali",
    "nep" to "Nepali",
    "nepali" to "Nepali",
    "si" to "Sinhala",
    "sin" to "Sinhala",
    "sinhala" to "Sinhala",
    "my" to "Burmese",
    "bur" to "Burmese",
    "mya" to "Burmese",
    "burmese" to "Burmese",
    "km" to "Khmer",
    "khm" to "Khmer",
    "khmer" to "Khmer",
)

private val LANGUAGE_NOISE_SUFFIX = Regex(
    """(?i)\b(dubbed|dub|subtitles?|subs?|audio|track|official|sdh|cc|forced)\b""",
)

/**
 * Pure human-readable language mapper across ISO-639-1/2/3 codes, BCP-47 tags, native scripts,
 * and dub/subtitle labels (`"bn"`/`"bengali"` -> `"Bangla"`, `"hi"` -> `"Hindi"`, `"und"`/`""` -> `"Original"`).
 * Never emits 2- or 3-letter codes.
 */
fun fullLanguageName(raw: String): String {
    val trimmed = raw.trim().trim('[', ']', '(', ')', '-', '_', '·', '|', '/').trim()
    if (trimmed.isEmpty()) return "Original"
    when {
        trimmed.contains("বাংলা") -> return "Bangla"
        trimmed.contains("हिन्दी") || trimmed.contains("हिंदी") -> return "Hindi"
        trimmed.contains("اردو") -> return "Urdu"
        trimmed.contains("العربية") -> return "Arabic"
        trimmed.contains("한국어") -> return "Korean"
        trimmed.contains("日本語") -> return "Japanese"
        trimmed.contains("தமிழ்") -> return "Tamil"
        trimmed.contains("తెలుగు") -> return "Telugu"
        trimmed.contains("മലയാളം") -> return "Malayalam"
        trimmed.contains("ਪੰਜਾਬੀ") -> return "Punjabi"
        trimmed.contains("русский", ignoreCase = true) -> return "Russian"
        trimmed.contains("ไทย") -> return "Thai"
        trimmed.any { it in '\u4E00'..'\u9FFF' } -> return "Chinese"
    }
    val lower = trimmed.lowercase(java.util.Locale.US).replace('_', '-')
    LANGUAGE_CODE_MAP[lower]?.let { return it }

    val stripped = LANGUAGE_NOISE_SUFFIX.replace(lower, " ")
        .replace(Regex("""\s+"""), " ")
        .trim()
        .trim('-', '·', '|', '/')
        .trim()
    LANGUAGE_CODE_MAP[stripped]?.let { return it }

    val baseTag = stripped.substringBefore('-').trim()
    if (baseTag.isNotEmpty()) {
        LANGUAGE_CODE_MAP[baseTag]?.let { return it }
    }
    val firstWord = stripped.substringBefore(' ').substringBefore('-').trim()
    if (firstWord.isNotEmpty()) {
        LANGUAGE_CODE_MAP[firstWord]?.let { return it }
    }

    if (stripped.length <= 3) {
        val localeName = runCatching {
            java.util.Locale(baseTag.ifEmpty { stripped }).getDisplayLanguage(java.util.Locale.ENGLISH)
        }.getOrDefault("")
        return if (localeName.length > 3 && !localeName.equals(baseTag, ignoreCase = true) && !localeName.equals(stripped, ignoreCase = true)) {
            localeName
        } else {
            "Original"
        }
    }

    return stripped.split(' ')
        .filter { it.isNotEmpty() }
        .joinToString(" ") { word -> word.replaceFirstChar { c -> c.titlecase(java.util.Locale.US) } }
        .takeIf { it.length > 3 }
        ?: "Original"
}
