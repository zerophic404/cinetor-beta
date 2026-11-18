package com.mythbrix.cinetor.core.data.comics

import com.mythbrix.cinetor.core.data.CatalogRepository
import com.mythbrix.cinetor.core.data.Category
import com.mythbrix.cinetor.core.data.Chapter
import com.mythbrix.cinetor.core.data.ComicFormat
import com.mythbrix.cinetor.core.data.Item
import com.mythbrix.cinetor.core.data.ItemType
import com.mythbrix.cinetor.core.data.NotFoundException
import com.mythbrix.cinetor.core.data.ReadingDirection
import com.mythbrix.cinetor.core.data.Row
import com.mythbrix.cinetor.core.data.RowStyle
import com.mythbrix.cinetor.core.data.Space
import com.mythbrix.cinetor.core.data.gateway.GatewayCache
import com.mythbrix.cinetor.core.data.gateway.GatewayConfig
import com.mythbrix.cinetor.core.data.gateway.GatewayHttp
import com.mythbrix.cinetor.core.data.gateway.PagedCatalog
import com.mythbrix.cinetor.core.data.gateway.RoutedCatalog
import com.mythbrix.cinetor.core.data.gateway.RowPage
import com.mythbrix.cinetor.core.data.tmdb.TmdbMapper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

// This app is the complete property of Mythbrix. Do not touch, retrieve, reverse-engineer, scrape or
// hack anything without Mythbrix's explicit permission.

/**
 * Comics kind = origin split (`Manga` JP · `Manhwa` KR · `Manhua` CN).
 */
enum class ComicKind(
    val id: String,
    val title: String,
    val subtitle: String,
    val languages: List<String>,
    val anilistCountry: String = "JP",
) {
    MANGA("cm:manga", "Manga", "Japanese · RTL Paged", listOf("ja"), "JP"),
    MANHWA("cm:manhwa", "Manhwa", "Korean · Webtoon", listOf("ko"), "KR"),
    MANHUA("cm:manhua", "Manhua", "Chinese · Longstrip", listOf("zh", "zh-hk"), "CN");

    companion object {
        fun ofId(id: String): ComicKind? = entries.firstOrNull { it.id == id }
        fun ofLanguage(lang: String?): ComicKind =
            entries.firstOrNull { k -> k.languages.any { it.equals(lang, true) } } ?: MANGA

        fun ofCountry(code: String?): ComicKind = when (code?.uppercase()) {
            "KR" -> MANHWA
            "CN", "TW", "HK" -> MANHUA
            else -> MANGA
        }
    }
}

// ----------------------------------------------------------------------------------------- DTOs

@Serializable
data class MdAttrs(
    /** Manga: localised map (`{"en": "..."}`). Chapter: a bare string. Hence [JsonElement]. */
    val title: JsonElement? = null,
    val altTitles: List<Map<String, String>> = emptyList(),
    val description: Map<String, String> = emptyMap(),
    val name: Map<String, String> = emptyMap(),
    val tags: List<MdEntity> = emptyList(),
    val links: JsonObject? = null,
    val year: Int? = null,
    val status: String? = null,
    val contentRating: String? = null,
    val originalLanguage: String? = null,
    val translatedLanguage: String? = null,
    val lastChapter: String? = null,
    val chapter: String? = null,
    val volume: String? = null,
    val pages: Int = 0,
    val externalUrl: String? = null,
    val fileName: String? = null,
    val publishAt: String? = null,
)

@Serializable
data class MdEntity(
    val id: String = "",
    val type: String = "",
    val attributes: MdAttrs? = null,
    val relationships: List<MdEntity> = emptyList(),
)

@Serializable
data class MdList(
    val result: String = "ok",
    val total: Int = 0,
    val limit: Int = 0,
    val offset: Int = 0,
    val data: List<MdEntity> = emptyList(),
)

@Serializable
data class MdSingle(val result: String = "ok", val data: MdEntity? = null)

@Serializable
data class MdChapterData(
    val hash: String = "",
    val data: List<String> = emptyList(),
    val dataSaver: List<String> = emptyList(),
)

@Serializable
data class MdAtHome(
    val result: String = "ok",
    val baseUrl: String = "",
    val chapter: MdChapterData = MdChapterData(),
    val pages: List<String> = emptyList(),
    val ref: String? = null,
)

@Serializable
data class GatewayReaderChapterDto(
    val id: String = "",
    val n: String = "",
    val t: String = "",
)

@Serializable
data class GatewayReaderChapterListDto(
    val chapters: List<GatewayReaderChapterDto> = emptyList(),
)

// ------------------------------------------------------------------------------------- mapping

/** Pure AniList / MangaDex JSON → Cinetor models (unit-testable, no Android, no network). */
object MangaDexMapper {
    const val ID_PREFIX = "cm:"
    const val ANILIST_PREFIX = "cm:al:"
    const val CHAPTER_PREFIX = "cmch:"
    const val C2_CHAPTER_PREFIX = "cmch:c2:"
    const val DEX_CHAPTER_PREFIX = "cmch:dex:"
    const val COVERS = "https://uploads.mangadex.org/covers"
    const val ACCENT = "#E50914"

    data class ChapterPageSet(val data: List<String>, val dataSaver: List<String>)

    data class ParsedChapters(
        val chapters: List<Chapter>,
        val backups: Map<String, List<String>>,
    )

    data class AnilistMappedItem(
        val item: Item,
        val altTitles: List<String>,
    )

    fun isComicId(id: String): Boolean = id.startsWith(ID_PREFIX)
    fun uuidOf(id: String): String? = id.removePrefix(ID_PREFIX).takeIf { it.isNotBlank() && it != id }
    fun anilistIdOf(id: String): Int? = id.removePrefix(ANILIST_PREFIX).takeIf { it != id }?.toIntOrNull()
    fun chapterUuidOf(id: String): String? = id.removePrefix(CHAPTER_PREFIX).takeIf { it.isNotBlank() && it != id }
    fun c2ChapterIdOf(id: String): String? = id.removePrefix(C2_CHAPTER_PREFIX).takeIf { it.isNotBlank() && it != id }
    fun dexChapterUuidOf(id: String): String? = id.removePrefix(DEX_CHAPTER_PREFIX).takeIf { it.isNotBlank() && it != id }

    /** `en` first, then romanised, then anything. */
    fun localized(map: Map<String, String>): String? =
        map["en"] ?: map.entries.firstOrNull { it.key.endsWith("-ro") }?.value ?: map.values.firstOrNull()

    /** `title` is a localised object on manga and a plain string on chapters. */
    fun titleOf(e: MdEntity): String? = when (val t = e.attributes?.title) {
        is JsonPrimitive -> t.contentOrNull?.takeIf { it.isNotBlank() }
        is JsonObject -> localized(t.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { k to it } }.toMap())
        else -> null
    }

    fun coverUrl(mangaId: String, fileName: String): String = "$COVERS/$mangaId/$fileName.512.jpg"

    fun coverFileOf(e: MdEntity): String? =
        e.relationships.firstOrNull { it.type == "cover_art" && it.attributes?.fileName != null }?.attributes?.fileName

    fun genresOf(e: MdEntity): List<String> =
        e.attributes?.tags.orEmpty().mapNotNull { localized(it.attributes?.name.orEmpty()) }
            .ifEmpty { listOfNotNull(e.attributes?.status?.replaceFirstChar { c -> c.uppercase() }) }

    /** Cleans AniList HTML / entity / markdown tags from synopses cleanly. */
    fun cleanSynopsis(raw: String): String = raw
        .replace(Regex("(?i)<br\\s*/?>"), "\n")
        .replace(Regex("<[^>]+>"), "")
        .replace(Regex("~!.*?!~", RegexOption.DOT_MATCHES_ALL), "")
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&#039;", "'")
        .replace("&#39;", "'")
        .replace("&rsquo;", "'")
        .replace("&ldquo;", "\"")
        .replace("&rdquo;", "\"")
        .replace("&mdash;", "—")
        .replace("&ndash;", "–")
        .replace("&nbsp;", " ")
        .replace(Regex("\\(Source:[^)]+\\)$", RegexOption.IGNORE_CASE), "")
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()

    /** Maps an AniList GraphQL `Media` object into a Cinetor [Item] + alternate lookup titles. */
    fun fromAnilistMedia(media: JsonObject, fallbackKind: ComicKind? = null): AnilistMappedItem? {
        val rawStatus = media.strOrNull("status").orEmpty()
        if (rawStatus.equals("NOT_YET_RELEASED", ignoreCase = true)) return null
        val alId = media.intOrNull("id")?.takeIf { it > 0 } ?: return null
        val country = media.strOrNull("countryOfOrigin")
        val kind = if (!country.isNullOrBlank()) ComicKind.ofCountry(country) else (fallbackKind ?: ComicKind.MANGA)
        val titlesObj = media.objOrNull("title")
        val english = titlesObj?.strOrNull("english")?.trim()?.takeIf { it.isNotEmpty() }
        val romaji = titlesObj?.strOrNull("romaji")?.trim()?.takeIf { it.isNotEmpty() }
        val nativeTitle = titlesObj?.strOrNull("native")?.trim()?.takeIf { it.isNotEmpty() }
        val title = english ?: romaji ?: nativeTitle ?: return null

        val coverObj = media.objOrNull("coverImage")
        val poster = (coverObj?.strOrNull("extraLarge") ?: coverObj?.strOrNull("large")).orEmpty()
        val banner = media.strOrNull("bannerImage")?.takeIf { it.isNotBlank() } ?: poster
        val rawColor = coverObj?.strOrNull("color")?.takeIf { HEX_COLOR_RE.matches(it) }
        val itemId = "$ANILIST_PREFIX$alId"
        val score = media.intOrNull("averageScore")
        val rating = if (score != null && score > 0) (score / 10.0) else 0.0
        val year = media.objOrNull("startDate")?.intOrNull("year") ?: 0
        val statusLabel = rawStatus
            .lowercase()
            .replace('_', ' ')
            .replaceFirstChar { c -> c.uppercase() }
            .takeIf { it.isNotBlank() }
        val genres = media.strList("genres").take(6).ifEmpty { listOfNotNull(statusLabel) }
        val synonyms = media.strList("synonyms").map { it.trim() }.filter { it.isNotEmpty() }.take(4)
        val altCandidates = (listOfNotNull(romaji, english, nativeTitle) + synonyms).distinct()
        val altTitle = altCandidates.firstOrNull { !it.equals(title, ignoreCase = true) }

        val item = Item(
            id = itemId,
            space = Space.COMICS,
            categoryId = kind.id,
            type = ItemType.COMIC,
            title = title,
            altTitle = altTitle,
            year = year,
            rating = rating,
            maturity = "All",
            genres = genres,
            synopsis = cleanSynopsis(media.strOrNull("description").orEmpty()),
            poster = poster,
            backdrop = banner,
            dominantColor = rawColor ?: TmdbMapper.fallbackColor(itemId),
            accentColor = ACCENT,
            artist = statusLabel,
            format = if (kind == ComicKind.MANGA) ComicFormat.PAGED else ComicFormat.LONGSTRIP,
            readingDirection = if (kind == ComicKind.MANGA) ReadingDirection.RTL else ReadingDirection.TTB,
            slug = alId.toString(),
        )
        return AnilistMappedItem(item = item, altTitles = altCandidates)
    }

    fun toItem(e: MdEntity, kind: ComicKind, categoryId: String): Item? {
        val attrs = e.attributes ?: return null
        val id = e.id.takeIf { it.isNotBlank() } ?: return null
        val title = titleOf(e) ?: attrs.altTitles.firstOrNull()?.let { localized(it) } ?: return null
        val cover = coverFileOf(e)?.let { coverUrl(id, it) }.orEmpty()
        val itemId = ID_PREFIX + id
        return Item(
            id = itemId,
            space = Space.COMICS,
            categoryId = categoryId,
            type = ItemType.COMIC,
            title = title,
            altTitle = attrs.altTitles.firstNotNullOfOrNull { m -> m["en"] }?.takeIf { it != title },
            year = attrs.year ?: 0,
            rating = 0.0,
            maturity = if (attrs.contentRating == "suggestive") "16+" else "All",
            genres = genresOf(e).take(6),
            synopsis = localized(attrs.description).orEmpty(),
            poster = cover,
            backdrop = cover,
            dominantColor = TmdbMapper.fallbackColor(itemId),
            accentColor = ACCENT,
            // Manga reads right-to-left in paged mode; manhwa / manhua are vertical long strips.
            format = if (kind == ComicKind.MANGA) ComicFormat.PAGED else ComicFormat.LONGSTRIP,
            readingDirection = if (kind == ComicKind.MANGA) ReadingDirection.RTL else ReadingDirection.TTB,
            slug = id,
        )
    }

    /**
     * Parses feed entities into sorted chapters (numeric ascending) and records duplicate scanlation
     * uploads under [ParsedChapters.backups] so the reader can retry another group if a chapter fails.
     * Licensed external-only links and 0-page placeholders are dropped.
     */
    fun toChaptersWithBackups(
        entities: List<MdEntity>,
        chapterPrefix: String = CHAPTER_PREFIX,
        backupPrefix: String = "",
    ): ParsedChapters {
        val primaryByKey = LinkedHashMap<String, Pair<Double, Chapter>>()
        val backups = LinkedHashMap<String, MutableList<String>>()
        for (e in entities) {
            val a = e.attributes ?: continue
            if (e.id.isBlank() || a.pages <= 0 || !a.externalUrl.isNullOrBlank()) continue
            val num = a.chapter?.trim().orEmpty()
            val key = if (num.isEmpty()) e.id else normalizeChapterKey(num)
            val existing = primaryByKey[key]
            if (existing != null) {
                val primaryChapterId = existing.second.id
                val candidateId = backupPrefix + e.id
                if (primaryChapterId != chapterPrefix + e.id && candidateId.isNotBlank()) {
                    backups.getOrPut(primaryChapterId) { mutableListOf() }.add(candidateId)
                }
                continue
            }
            val parsedNumber = num.toDoubleOrNull()
            val orderKey = parsedNumber ?: Double.MAX_VALUE
            val label = titleOf(e)
            val displayNum = if (num.isEmpty()) "" else cleanChapterNumberLabel(num)
            val chapter = Chapter(
                id = chapterPrefix + e.id,
                n = parsedNumber?.toInt() ?: 0,
                title = buildString {
                    append(if (displayNum.isEmpty()) "Oneshot" else "Chapter $displayNum")
                    if (!label.isNullOrBlank() && label != displayNum) append(" · ").append(label)
                },
                pages = emptyList(),
            )
            primaryByKey[key] = orderKey to chapter
        }
        val sorted = primaryByKey.values.sortedBy { it.first }.map { it.second }
        return ParsedChapters(sorted, backups)
    }

    /** `/manga/{id}/feed` → chapters, deduplicated per chapter number, in reading order. Pages resolve later. */
    fun toChapters(list: MdList): List<Chapter> = toChaptersWithBackups(list.data).chapters

    /**
     * Merges gateway reader (`c1`) chapters with gateway MangaDex (`dex`) chapters by chapter number,
     * enriching titles from MangaDex where available and linking cross-source backup IDs.
     */
    fun mergeGatewayChapters(
        c1Entries: List<GatewayReaderChapterDto>,
        dexParsed: ParsedChapters?,
    ): ParsedChapters {
        val byKey = LinkedHashMap<String, Pair<Double, Chapter>>()
        val backups = LinkedHashMap<String, MutableList<String>>()

        val dexByKey = LinkedHashMap<String, Chapter>()
        val dexOrderByKey = HashMap<String, Double>()
        dexParsed?.chapters.orEmpty().forEach { ch ->
            val rawNum = ch.title.removePrefix("Chapter ").substringBefore(" · ").trim()
            val key = if (rawNum.equals("Oneshot", ignoreCase = true) || rawNum.isEmpty()) ch.id else normalizeChapterKey(rawNum)
            dexByKey[key] = ch
            dexOrderByKey[key] = rawNum.toDoubleOrNull() ?: ch.n.toDouble().takeIf { it > 0 } ?: Double.MAX_VALUE
        }

        for (entry in c1Entries) {
            val rawId = entry.id.trim()
            val rawNum = entry.n.trim()
            if (rawId.isEmpty() || rawNum.isEmpty()) continue
            val key = normalizeChapterKey(rawNum)
            val c2Id = C2_CHAPTER_PREFIX + rawId
            val existing = byKey[key]
            if (existing != null) {
                if (existing.second.id != c2Id) {
                    backups.getOrPut(existing.second.id) { mutableListOf() }.add(c2Id)
                }
                continue
            }
            val parsedNum = rawNum.toDoubleOrNull()
            val displayNum = cleanChapterNumberLabel(rawNum)
            val dexMatch = dexByKey[key]
            val title = when {
                entry.t.isNotBlank() && entry.t.trim() != displayNum -> "Chapter $displayNum · ${entry.t.trim()}"
                dexMatch != null && " · " in dexMatch.title -> dexMatch.title
                else -> "Chapter $displayNum"
            }
            val primary = Chapter(
                id = c2Id,
                n = parsedNum?.toInt() ?: 0,
                title = title,
                pages = emptyList(),
            )
            byKey[key] = (parsedNum ?: Double.MAX_VALUE) to primary
            if (dexMatch != null) {
                val altList = backups.getOrPut(c2Id) { mutableListOf() }
                altList.add(dexMatch.id)
                dexParsed?.backups?.get(dexMatch.id)?.forEach { altList.add(it) }
            }
        }

        for ((key, dexCh) in dexByKey) {
            if (key in byKey) continue
            val order = dexOrderByKey[key] ?: Double.MAX_VALUE
            byKey[key] = order to dexCh
            dexParsed?.backups?.get(dexCh.id)?.takeIf { it.isNotEmpty() }?.let { alts ->
                backups[dexCh.id] = alts.toMutableList()
            }
        }

        val sorted = byKey.values.sortedBy { it.first }.map { it.second }
        return ParsedChapters(sorted, backups)
    }

    /** Extracts both full-res (`data`) and compressed (`data-saver`) URL lists from `at-home/server` or gateway `c2`. */
    fun pageUrlSet(at: MdAtHome): ChapterPageSet {
        val base = at.baseUrl.trimEnd('/')
        val hash = at.chapter.hash
        if (base.isEmpty() || hash.isEmpty()) {
            val direct = at.pages.map { it.trim() }.filter { it.startsWith("https://") || it.startsWith("http://") }
            return ChapterPageSet(data = direct, dataSaver = direct)
        }
        val data = at.chapter.data.filter { it.isNotBlank() }.map { "$base/data/$hash/$it" }
        val saver = at.chapter.dataSaver.filter { it.isNotBlank() }.map { "$base/data-saver/$hash/$it" }
        return ChapterPageSet(data = data, dataSaver = saver)
    }

    /** `at-home/server` response → ordered page URLs, automatically falling back to `dataSaver` when `data` is empty. */
    fun pageUrls(at: MdAtHome, dataSaver: Boolean = false): List<String> {
        val set = pageUrlSet(at)
        return if (dataSaver) set.dataSaver.ifEmpty { set.data } else set.data.ifEmpty { set.dataSaver }
    }

    /** Token-set Jaccard closeness `[0.0, 1.0]` used to match AniList titles against MangaDex entities. */
    fun titleSimilarity(a: String, b: String): Double {
        val tokensA = TOKEN_SPLIT_RE.split(a.lowercase()).filter { it.isNotBlank() }.toSet()
        val tokensB = TOKEN_SPLIT_RE.split(b.lowercase()).filter { it.isNotBlank() }.toSet()
        if (tokensA.isEmpty() || tokensB.isEmpty()) return 0.0
        val inter = tokensA.count { it in tokensB }
        val union = tokensA.size + tokensB.size - inter
        return if (union <= 0) 0.0 else inter.toDouble() / union.toDouble()
    }

    internal fun normalizeChapterKey(raw: String): String {
        val trimmed = raw.trim()
        val d = trimmed.toDoubleOrNull() ?: return trimmed
        val asLong = d.toLong()
        return if (d == asLong.toDouble()) asLong.toString() else d.toString()
    }

    private fun cleanChapterNumberLabel(raw: String): String = normalizeChapterKey(raw)

    private val HEX_COLOR_RE = Regex("^#[0-9A-Fa-f]{6}$")
    private val TOKEN_SPLIT_RE = Regex("[^a-z0-9]+")
}

/** Thrown when a chapter has no pages anywhere (gateway proxy empty + MangaDex empty). */
class ChapterUnavailableException(val chapterId: String) : Exception("Chapter unavailable")

// ----------------------------------------------------------------------------------------- API

data class AnilistBrowseResult(
    val items: List<MangaDexMapper.AnilistMappedItem>,
    val hasNextPage: Boolean,
)

data class ResolvedGatewayPages(
    val pageSet: MangaDexMapper.ChapterPageSet,
    /** Maps proxied primary URL → raw backup URL. */
    val backupsByPrimary: Map<String, String> = emptyMap(),
    /** Optional `Referer` required when fetching raw backup URLs directly. */
    val referer: String? = null,
)

/**
 * Signed-gateway Comics API (`/manga/gql` AniList metadata + HD covers, `/manga/c1` & `/manga/c2`
 * chapter/page resolvers, `/manga/img` Referer image proxy, and `/manga/dex/...` edge MangaDex proxy)
 * with direct MangaDex fallback kept intact for unit-test compatibility.
 */
class MangaDexApi(
    private val http: GatewayHttp,
    private val cache: GatewayCache? = GatewayCache(),
    private val gatewayBase: () -> String = { GatewayConfig.BASE_URL },
    private val apiBase: String = "https://api.mangadex.org",
) {
    private fun gwRoot(): String = GatewayConfig.normalize(gatewayBase()) + GatewayConfig.API

    fun proxyImageUrl(rawUrl: String, referer: String? = null): String {
        val trimmed = rawUrl.trim()
        if (!trimmed.startsWith("https://")) return trimmed
        return buildString {
            append(gwRoot())
            append("/manga/img?u=")
            append(enc(trimmed))
            if (!referer.isNullOrBlank()) {
                append("&r=")
                append(enc(referer))
            }
        }
    }

    private suspend fun cached(url: String, timeoutMs: Long = GatewayConfig.CATALOG_TIMEOUT_MS): String = withContext(Dispatchers.IO) {
        cache?.get(url)?.let { return@withContext it }
        val body = try {
            http.get(url, timeoutMs)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return@withContext cache?.getStale(url) ?: throw e
        }
        cache?.put(url, body)
        body
    }

    private fun q(pairs: List<Pair<String, String>>): String =
        pairs.joinToString("&") { (k, v) -> enc(k) + "=" + enc(v) }

    // ---- Gateway AniList GraphQL (`/api/public/v1/manga/gql`) ------------------------------

    suspend fun anilistBrowse(
        kind: ComicKind?,
        sort: String = "POPULARITY_DESC",
        page: Int = 1,
        perPage: Int = 24,
        search: String? = null,
    ): AnilistBrowseResult = withContext(Dispatchers.IO) {
        val variables = buildJsonObject {
            put("page", page.coerceAtLeast(1))
            put("perPage", perPage.coerceIn(1, 50))
            put("sort", JsonArray(listOf(JsonPrimitive(if (search.isNullOrBlank()) sort else "SEARCH_MATCH"))))
            if (kind != null) put("country", kind.anilistCountry)
            if (!search.isNullOrBlank()) put("search", search.trim())
        }
        val payload = buildJsonObject {
            put("query", ANILIST_LIST_QUERY)
            put("variables", variables)
        }.toString()
        val raw = cached("${gwRoot()}/manga/gql?body=${enc(payload)}")
        val root = json.parseToJsonElement(raw) as? JsonObject ?: throw IOException("Invalid AniList response")
        val pageObj = root.objOrNull("data")?.objOrNull("Page") ?: throw IOException("Missing AniList Page")
        val hasNext = pageObj.objOrNull("pageInfo")?.boolOrNull("hasNextPage") == true
        val mapped = pageObj.objList("media").mapNotNull { media ->
            val country = media.strOrNull("countryOfOrigin")
            if (country != null && country !in ALLOWED_COUNTRIES) return@mapNotNull null
            MangaDexMapper.fromAnilistMedia(media, fallbackKind = kind)
        }
        AnilistBrowseResult(items = mapped, hasNextPage = hasNext)
    }

    suspend fun anilistDetail(anilistId: Int): MangaDexMapper.AnilistMappedItem? = withContext(Dispatchers.IO) {
        val payload = buildJsonObject {
            put("query", ANILIST_DETAIL_QUERY)
            put("variables", buildJsonObject { put("id", anilistId) })
        }.toString()
        val raw = cached("${gwRoot()}/manga/gql?body=${enc(payload)}")
        val root = json.parseToJsonElement(raw) as? JsonObject ?: return@withContext null
        val media = root.objOrNull("data")?.objOrNull("Media") ?: return@withContext null
        MangaDexMapper.fromAnilistMedia(media)
    }

    // ---- Gateway Reader Chapters & Pages (`/manga/c1`, `/manga/c2`, `/manga/dex/...`) ------

    suspend fun readerChaptersC1(names: List<String>): List<GatewayReaderChapterDto> = withContext(Dispatchers.IO) {
        val cleanNames = names.map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(4)
        if (cleanNames.isEmpty()) return@withContext emptyList()
        val namesJson = JsonArray(cleanNames.map(::JsonPrimitive)).toString()
        val raw = cached("${gwRoot()}/manga/c1?names=${enc(namesJson)}", GatewayConfig.STREAM_TIMEOUT_MS)
        json.decodeFromString(GatewayReaderChapterListDto.serializer(), raw).chapters
    }

    suspend fun readerPagesC2(rawId: String, fresh: Boolean = false): ResolvedGatewayPages = withContext(Dispatchers.IO) {
        val url = "${gwRoot()}/manga/c2?id=${enc(rawId)}"
        val body = if (fresh) http.get(url, GatewayConfig.STREAM_TIMEOUT_MS) else cached(url, GatewayConfig.STREAM_TIMEOUT_MS)
        val at = json.decodeFromString(MdAtHome.serializer(), body)
        val rawPages = MangaDexMapper.pageUrlSet(at).data
        if (rawPages.isEmpty()) throw ChapterUnavailableException(rawId)
        val ref = at.ref?.trim()?.takeIf { it.isNotEmpty() }
        val proxied = rawPages.map { u -> proxyImageUrl(u, ref) }
        val backups = LinkedHashMap<String, String>(rawPages.size)
        for (i in proxied.indices) {
            if (proxied[i] != rawPages[i]) backups[proxied[i]] = rawPages[i]
        }
        ResolvedGatewayPages(
            pageSet = MangaDexMapper.ChapterPageSet(data = proxied, dataSaver = proxied),
            backupsByPrimary = backups,
            referer = ref,
        )
    }

    /** Resolves chapters from MangaDex through the signed gateway (`/api/public/v1/manga/dex/...`). */
    suspend fun dexChaptersViaGateway(
        names: List<String>,
        anilistId: Int? = null,
        limit: Int = 500,
        maxChapters: Int = 2_000,
    ): MangaDexMapper.ParsedChapters? = withContext(Dispatchers.IO) {
        val cleanNames = names.map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(4)
        if (cleanNames.isEmpty()) return@withContext null
        val dexId = findDexMangaIdViaGateway(cleanNames, anilistId) ?: return@withContext null
        val entities = fetchDexFeedViaGateway(dexId, limit = limit, maxChapters = maxChapters)
        if (entities.isEmpty()) return@withContext null
        MangaDexMapper.toChaptersWithBackups(
            entities = entities,
            chapterPrefix = MangaDexMapper.DEX_CHAPTER_PREFIX,
            backupPrefix = MangaDexMapper.DEX_CHAPTER_PREFIX,
        )
    }

    private suspend fun findDexMangaIdViaGateway(names: List<String>, anilistId: Int?): String? {
        val alStr = anilistId?.takeIf { it > 0 }?.toString()
        for (name in names) {
            val params = listOf(
                "title" to name,
                "limit" to "15",
                "order[relevance]" to "desc",
                "contentRating[]" to "safe",
                "contentRating[]" to "suggestive",
            )
            val list = try {
                json.decodeFromString(MdList.serializer(), cached("${gwRoot()}/manga/dex/manga?${q(params)}"))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                continue
            }
            if (list.data.isEmpty()) continue
            val exactAl = if (alStr != null) {
                list.data.firstOrNull { e -> e.attributes?.links?.strOrNull("al") == alStr }
            } else {
                null
            }
            if (exactAl != null && exactAl.id.isNotBlank()) return exactAl.id

            var bestEntity: MdEntity? = null
            var bestScore = 0.0
            for (entity in list.data) {
                val score = entityCloseness(entity, names)
                if (score > bestScore) {
                    bestScore = score
                    bestEntity = entity
                }
            }
            if (bestEntity != null && bestScore >= 0.55 && bestEntity.id.isNotBlank()) {
                return bestEntity.id
            }
        }
        return null
    }

    private fun entityCloseness(entity: MdEntity, wantedNames: List<String>): Double {
        val candidates = buildList {
            MangaDexMapper.titleOf(entity)?.let(::add)
            val attrs = entity.attributes
            if (attrs != null) {
                (attrs.title as? JsonObject)?.values?.forEach { v ->
                    (v as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }?.let(::add)
                }
                attrs.altTitles.forEach { map ->
                    map.values.filter { it.isNotBlank() }.forEach(::add)
                }
            }
        }
        var best = 0.0
        for (candidate in candidates) {
            for (wanted in wantedNames) {
                val sim = MangaDexMapper.titleSimilarity(wanted, candidate)
                if (sim > best) best = sim
            }
        }
        return best
    }

    private suspend fun fetchDexFeedViaGateway(dexMangaUuid: String, limit: Int, maxChapters: Int): List<MdEntity> {
        suspend fun fetchPage(offset: Int): MdList {
            val params = buildList {
                add("translatedLanguage[]" to "en")
                add("order[chapter]" to "asc")
                add("limit" to limit.toString())
                if (offset > 0) add("offset" to offset.toString())
                add("includeExternalUrl" to "0")
                add("contentRating[]" to "safe")
                add("contentRating[]" to "suggestive")
            }
            return json.decodeFromString(
                MdList.serializer(),
                cached("${gwRoot()}/manga/dex/manga/${enc(dexMangaUuid)}/feed?${q(params)}"),
            )
        }

        val first = try {
            fetchPage(0)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return emptyList()
        }
        if (first.data.size < limit || first.data.size >= first.total) return first.data
        val all = ArrayList<MdEntity>(minOf(first.total, maxChapters))
        all.addAll(first.data)
        var offset = limit
        val cap = minOf(first.total, maxChapters)
        while (offset < cap) {
            val next = try {
                fetchPage(offset)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                break
            }
            if (next.data.isEmpty()) break
            all.addAll(next.data)
            offset += limit
            if (next.data.size < limit) break
        }
        return all
    }

    /** Resolves MangaDex `at-home/server` through `/manga/dex/at-home/server/...` and wraps images via `/manga/img`. */
    suspend fun dexPagesViaGateway(chapterUuid: String, fresh: Boolean = false): ResolvedGatewayPages = withContext(Dispatchers.IO) {
        val dexGatewayUrl = "${gwRoot()}/manga/dex/at-home/server/${enc(chapterUuid)}"
        val legacyGatewayUrl = "${gwRoot()}/manga?action=c2&id=${enc(chapterUuid)}"
        val candidates = if (fresh) listOf(legacyGatewayUrl, dexGatewayUrl) else listOf(dexGatewayUrl, legacyGatewayUrl)
        for (url in candidates) {
            val set = try {
                val at = json.decodeFromString(MdAtHome.serializer(), http.get(url, GatewayConfig.STREAM_TIMEOUT_MS))
                MangaDexMapper.pageUrlSet(at)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                MangaDexMapper.ChapterPageSet(emptyList(), emptyList())
            }
            if (set.data.isNotEmpty() || set.dataSaver.isNotEmpty()) {
                val rawFull = set.data.ifEmpty { set.dataSaver }
                val rawSaver = set.dataSaver.ifEmpty { set.data }
                val proxiedFull = rawFull.map { u -> proxyImageUrl(u) }
                val proxiedSaver = rawSaver.map { u -> proxyImageUrl(u) }
                val backups = LinkedHashMap<String, String>()
                for (i in proxiedFull.indices) {
                    val alt = proxiedSaver.getOrNull(i) ?: rawSaver.getOrNull(i)
                    if (alt != null && alt != proxiedFull[i]) {
                        backups[proxiedFull[i]] = alt
                    } else if (rawFull[i] != proxiedFull[i]) {
                        backups[proxiedFull[i]] = rawFull[i]
                    }
                }
                return@withContext ResolvedGatewayPages(
                    pageSet = MangaDexMapper.ChapterPageSet(data = proxiedFull, dataSaver = proxiedSaver),
                    backupsByPrimary = backups,
                )
            }
        }
        throw ChapterUnavailableException(chapterUuid)
    }

    // ---- Direct MangaDex fallback methods (kept intact for unit tests & resilience) --------

    private fun browseParams(kind: ComicKind, limit: Int, offset: Int, title: String?, order: Pair<String, String>): List<Pair<String, String>> =
        buildList {
            add("limit" to limit.toString())
            add("offset" to offset.toString())
            add("order[${order.first}]" to order.second)
            add("includes[]" to "cover_art")
            add("contentRating[]" to "safe")
            add("contentRating[]" to "suggestive")
            add("availableTranslatedLanguage[]" to "en")
            add("hasAvailableChapters" to "true")
            kind.languages.forEach { add("originalLanguage[]" to it) }
            title?.takeIf { it.isNotBlank() }?.let { add("title" to it) }
        }

    suspend fun browse(
        kind: ComicKind,
        limit: Int = 24,
        offset: Int = 0,
        order: Pair<String, String> = "followedCount" to "desc",
        title: String? = null,
    ): MdList = withContext(Dispatchers.IO) {
        json.decodeFromString(MdList.serializer(), cached("$apiBase/manga?" + q(browseParams(kind, limit, offset, title, order))))
    }

    suspend fun manga(mangaUuid: String): MdEntity? = withContext(Dispatchers.IO) {
        val url = "$apiBase/manga/${enc(mangaUuid)}?" + q(listOf("includes[]" to "cover_art"))
        json.decodeFromString(MdSingle.serializer(), cached(url)).data
    }

    suspend fun chapters(mangaUuid: String, limit: Int = 500): MdList = chaptersPage(mangaUuid, limit, offset = 0)

    private suspend fun chaptersPage(mangaUuid: String, limit: Int, offset: Int): MdList = withContext(Dispatchers.IO) {
        val params = buildList {
            add("translatedLanguage[]" to "en")
            add("order[chapter]" to "asc")
            add("limit" to limit.toString())
            if (offset > 0) add("offset" to offset.toString())
            add("includeExternalUrl" to "0")
            add("contentRating[]" to "safe")
            add("contentRating[]" to "suggestive")
        }
        json.decodeFromString(MdList.serializer(), cached("$apiBase/manga/$mangaUuid/feed?" + q(params)))
    }

    /** Paginates long series up to [maxChapters] while keeping partial chapters if a later page fails. */
    suspend fun allChapterEntities(mangaUuid: String, limit: Int = 500, maxChapters: Int = 2_000): List<MdEntity> {
        val first = chapters(mangaUuid, limit)
        if (first.data.size < limit || first.data.size >= first.total) return first.data
        val all = ArrayList<MdEntity>(minOf(first.total, maxChapters))
        all.addAll(first.data)
        var offset = limit
        val cap = minOf(first.total, maxChapters)
        while (offset < cap) {
            val next = try {
                chaptersPage(mangaUuid, limit, offset)
            } catch (e: CancellationException) {
                throw e
            } catch (_: IOException) {
                break
            } catch (_: Exception) {
                break
            }
            if (next.data.isEmpty()) break
            all.addAll(next.data)
            offset += limit
            if (next.data.size < limit) break
        }
        return all
    }

    /**
     * Resolves both `data` and `data-saver` page URLs. Tries gateway `action=c2` first (unless
     * [preferDirect] is set for host-switch retry), then MangaDex `at-home/server` directly.
     */
    suspend fun pageSet(chapterUuid: String, preferDirect: Boolean = false): MangaDexMapper.ChapterPageSet = withContext(Dispatchers.IO) {
        val gatewayUrl = GatewayConfig.normalize(gatewayBase()) + GatewayConfig.API + "/manga?action=c2&id=" + enc(chapterUuid)
        val directUrl = "$apiBase/at-home/server/$chapterUuid"
        val candidates = if (preferDirect) listOf(directUrl, gatewayUrl) else listOf(gatewayUrl, directUrl)
        for (url in candidates) {
            val set = try {
                val at = json.decodeFromString(MdAtHome.serializer(), http.get(url, GatewayConfig.STREAM_TIMEOUT_MS))
                MangaDexMapper.pageUrlSet(at)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                MangaDexMapper.ChapterPageSet(emptyList(), emptyList())
            }
            if (set.data.isNotEmpty() || set.dataSaver.isNotEmpty()) return@withContext set
        }
        throw ChapterUnavailableException(chapterUuid)
    }

    /**
     * Chapter pages: gateway proxy first (`action=c2`), then MangaDex `at-home/server` directly.
     * Both can come back empty (upstream bot challenge) → [ChapterUnavailableException], never a crash.
     */
    suspend fun pages(chapterUuid: String, dataSaver: Boolean = false): List<String> {
        val set = pageSet(chapterUuid, preferDirect = false)
        return if (dataSaver) set.dataSaver.ifEmpty { set.data } else set.data.ifEmpty { set.dataSaver }
    }

    companion object {
        val json: Json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
            explicitNulls = false
        }

        private val ALLOWED_COUNTRIES = setOf("JP", "KR", "CN", "TW", "HK")

        private const val ANILIST_FIELDS =
            "id countryOfOrigin status averageScore genres chapters synonyms bannerImage " +
                "description(asHtml: false) startDate { year } " +
                "title { romaji english native } coverImage { extraLarge large color }"

        private const val ANILIST_LIST_QUERY =
            "query(\$page: Int, \$perPage: Int, \$sort: [MediaSort], \$country: CountryCode, \$search: String) { " +
                "Page(page: \$page, perPage: \$perPage) { pageInfo { hasNextPage } " +
                "media(type: MANGA, isAdult: false, countryOfOrigin: \$country, search: \$search, sort: \$sort, format_not_in: [NOVEL, ONE_SHOT]) { " +
                ANILIST_FIELDS + " } } }"

        private const val ANILIST_DETAIL_QUERY =
            "query(\$id: Int) { Media(id: \$id, type: MANGA, isAdult: false) { " + ANILIST_FIELDS + " } }"

        private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    }
}

// -------------------------------------------------------------------------------------- source

/**
 * Signed-gateway Comics catalog (`Manga · Manhwa · Manhua`):
 * - Metadata, HD `s4.anilist.co` covers, synopses, genres & ratings from `/api/public/v1/manga/gql` (AniList).
 * - Chapters & hotlink-proof pages from `/api/public/v1/manga/c1` + `/manga/c2` + `/manga/img` merged with `/manga/dex/...`.
 * - Falls back to direct MangaDex / bundled offline mock catalog ONLY when gateway endpoints fail.
 */
class MangaDexCatalogSource(
    private val api: MangaDexApi,
    private val pageSize: Int = 24,
) : CatalogRepository, PagedCatalog, RoutedCatalog {

    private val summaries = ConcurrentHashMap<String, Item>()
    private val altTitlesById = ConcurrentHashMap<String, List<String>>()
    private val details = ConcurrentHashMap<String, Item>()
    private val pagesCache = ConcurrentHashMap<String, MangaDexMapper.ChapterPageSet>()
    private val dataSaverByUrl = ConcurrentHashMap<String, String>()
    private val refererByUrl = ConcurrentHashMap<String, String>()
    private val refererByHost = ConcurrentHashMap<String, String>()
    private val backupChapters = ConcurrentHashMap<String, List<String>>()

    override fun ownsItem(id: String): Boolean = MangaDexMapper.isComicId(id)
    override fun ownsRow(rowId: String): Boolean = rowId.startsWith(ROW_PREFIX)

    private fun remember(items: List<Item>): List<Item> {
        items.forEach { summaries.putIfAbsent(it.id, it) }
        return items
    }

    private fun rememberAnilist(entries: List<MangaDexMapper.AnilistMappedItem>): List<Item> {
        val out = ArrayList<Item>(entries.size)
        for (entry in entries) {
            summaries[entry.item.id] = entry.item
            if (entry.altTitles.isNotEmpty()) altTitlesById[entry.item.id] = entry.altTitles
            out += entry.item
        }
        return out.distinctBy { it.id }
    }

    private fun rowId(kind: ComicKind, order: String) = "$ROW_PREFIX${kind.name}:$order"

    private fun parseRow(rowId: String): Pair<ComicKind, String>? {
        if (!rowId.startsWith(ROW_PREFIX)) return null
        val rest = rowId.removePrefix(ROW_PREFIX)
        val kind = runCatching { ComicKind.valueOf(rest.substringBefore(':')) }.getOrNull() ?: return null
        return kind to rest.substringAfter(':', "popular")
    }

    private fun anilistSortOf(order: String): String = when (order) {
        "trending" -> "TRENDING_DESC"
        "popular" -> "POPULARITY_DESC"
        "rating" -> "SCORE_DESC"
        "latest" -> "UPDATED_AT_DESC"
        "new" -> "FAVOURITES_DESC"
        else -> "POPULARITY_DESC"
    }

    private fun orderOf(order: String): Pair<String, String> = when (order) {
        "latest" -> "latestUploadedChapter" to "desc"
        "rating" -> "rating" to "desc"
        "new" -> "year" to "desc"
        else -> "followedCount" to "desc"
    }

    private fun rowsOf(kind: ComicKind) = listOf(
        Row(rowId(kind, "trending"), "Trending ${kind.title}", RowStyle.POSTER, emptyList()),
        Row(rowId(kind, "popular"), "Popular ${kind.title}", RowStyle.POSTER, emptyList()),
        Row(rowId(kind, "rating"), "Top Rated ${kind.title}", RowStyle.TOP10, emptyList()),
        Row(rowId(kind, "latest"), "Recently Updated ${kind.title}", RowStyle.POSTER, emptyList()),
    )

    override suspend fun categories(space: Space): List<Category> {
        require(space == Space.COMICS) { "MangaDex serves Comics only" }
        return ComicKind.entries.mapIndexed { i, kind ->
            // Probe the first tab so an offline device throws here → mock fallback for the whole space.
            val heroes = if (i == 0) row(rowId(kind, "popular")).take(5).map { it.id } else emptyList()
            Category(kind.id, Space.COMICS, kind.title, heroes, rowsOf(kind))
        }
    }

    override suspend fun row(rowId: String): List<Item> = rowPage(rowId, 1).items

    override suspend fun rowPage(rowId: String, page: Int): RowPage {
        val (kind, order) = parseRow(rowId) ?: throw NotFoundException(rowId)
        val anilistResult = try {
            api.anilistBrowse(kind = kind, sort = anilistSortOf(order), page = page, perPage = pageSize)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        if (anilistResult != null && anilistResult.items.isNotEmpty()) {
            val items = rememberAnilist(anilistResult.items)
            return RowPage(items, page, anilistResult.hasNextPage)
        }

        val offset = (page - 1).coerceAtLeast(0) * pageSize
        val list = api.browse(kind, pageSize, offset, orderOf(order))
        val items = remember(list.data.mapNotNull { MangaDexMapper.toItem(it, kind, kind.id) }.distinctBy { it.id })
        return RowPage(items, page, offset + list.data.size < list.total && list.data.isNotEmpty())
    }

    override suspend fun item(id: String): Item {
        details[id]?.let { return it }

        val anilistId = MangaDexMapper.anilistIdOf(id)
        if (anilistId != null) {
            return resolveAnilistItem(id, anilistId)
        }

        val uuid = MangaDexMapper.uuidOf(id) ?: throw NotFoundException(id)
        val base = summaries[id]
            ?: runCatching { api.manga(uuid) }.getOrNull()?.let { entity ->
                val kind = ComicKind.ofLanguage(entity.attributes?.originalLanguage)
                MangaDexMapper.toItem(entity, kind, kind.id)
            }?.also { remember(listOf(it)) }
            ?: runCatching {
                api.browse(ComicKind.MANGA, 1, 0).data.firstOrNull { it.id == uuid }
                    ?.let { MangaDexMapper.toItem(it, ComicKind.MANGA, ComicKind.MANGA.id) }
            }.getOrNull()
            ?: throw NotFoundException(id)
        val parsed = runCatching { MangaDexMapper.toChaptersWithBackups(api.allChapterEntities(uuid)) }.getOrNull()
        val chapters = if (!parsed?.chapters.isNullOrEmpty()) {
            parsed!!.backups.forEach { (chapterId, alts) ->
                if (alts.isNotEmpty()) backupChapters[chapterId] = alts
            }
            parsed.chapters
        } else {
            val c1 = runCatching { api.readerChaptersC1(listOfNotNull(base.title, base.altTitle)) }.getOrDefault(emptyList())
            val merged = MangaDexMapper.mergeGatewayChapters(c1, null)
            merged.backups.forEach { (chapterId, alts) ->
                if (alts.isNotEmpty()) backupChapters[chapterId] = alts
            }
            merged.chapters
        }
        val full = base.copy(pages = chapters)
        if (chapters.isNotEmpty()) details[id] = full
        return full
    }

    private suspend fun resolveAnilistItem(id: String, anilistId: Int): Item = coroutineScope {
        val baseItem: Item
        val altTitles: List<String>
        val cachedSummary = summaries[id]
        if (cachedSummary != null) {
            baseItem = cachedSummary
            altTitles = altTitlesById[id].orEmpty()
        } else {
            val fetched = api.anilistDetail(anilistId) ?: throw NotFoundException(id)
            rememberAnilist(listOf(fetched))
            baseItem = fetched.item
            altTitles = fetched.altTitles
        }

        val candidateNames = (listOfNotNull(baseItem.title, baseItem.altTitle) + altTitles)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(4)

        val c1Job = async { runCatching { api.readerChaptersC1(candidateNames) }.getOrDefault(emptyList()) }
        val dexJob = async { runCatching { api.dexChaptersViaGateway(candidateNames, anilistId = anilistId) }.getOrNull() }

        val merged = MangaDexMapper.mergeGatewayChapters(c1Job.await(), dexJob.await())
        merged.backups.forEach { (chapterId, alts) ->
            if (alts.isNotEmpty()) backupChapters[chapterId] = alts
        }

        val full = baseItem.copy(pages = merged.chapters)
        if (merged.chapters.isNotEmpty()) details[id] = full
        full
    }

    fun hasBackupChapter(chapterId: String): Boolean = !backupChapters[chapterId].isNullOrEmpty()

    /** Compressed `data-saver` or raw CDN mirror URL for a primary page URL when available. */
    fun fallbackUrlFor(pageUrl: String): String? = dataSaverByUrl[pageUrl]

    /** Optional `Referer` header required when fetching a raw CDN page URL directly (e.g. offline chapter downloads). */
    fun refererFor(url: String): String? {
        refererByUrl[url]?.let { return it }
        val host = runCatching { URI(url).host?.lowercase() }.getOrNull() ?: return null
        return refererByHost[host]
    }

    private fun recordFallbacks(set: MangaDexMapper.ChapterPageSet) {
        if (set.data.size == set.dataSaver.size) {
            for (i in set.data.indices) {
                val primary = set.data[i]
                val saver = set.dataSaver[i]
                if (primary != saver) dataSaverByUrl[primary] = saver
            }
        }
    }

    private fun recordGatewayPages(resolved: ResolvedGatewayPages) {
        recordFallbacks(resolved.pageSet)
        for ((primary, backup) in resolved.backupsByPrimary) {
            if (primary != backup) dataSaverByUrl[primary] = backup
            if (!resolved.referer.isNullOrBlank()) {
                refererByUrl[backup] = resolved.referer
                runCatching { URI(backup).host?.lowercase() }.getOrNull()?.let { host ->
                    refererByHost[host] = resolved.referer
                }
            }
        }
    }

    /** Resolved page URLs for one chapter; empty list is never returned (throws instead). */
    suspend fun pages(chapterId: String, dataSaver: Boolean = false): List<String> =
        pagesWithFallback(chapterId, dataSaver = dataSaver, fresh = false, preferBackup = false)

    /**
     * Resolves chapter page URLs with full fallback support across `cmch:c2:...`, `cmch:dex:...`, and `cmch:...`:
     * - [dataSaver]: prefers compressed pages when available.
     * - [fresh]: bypasses cached URLs and flips host priority to obtain a fresh node.
     * - [preferBackup]: tries cross-source or duplicate scanlation chapter uploads first.
     */
    suspend fun pagesWithFallback(
        chapterId: String,
        dataSaver: Boolean = false,
        fresh: Boolean = false,
        preferBackup: Boolean = false,
    ): List<String> {
        if (!isComicChapter(chapterId)) throw NotFoundException(chapterId)
        if (!fresh && !preferBackup) {
            pagesCache[chapterId]?.let { cached ->
                return if (dataSaver) cached.dataSaver.ifEmpty { cached.data } else cached.data.ifEmpty { cached.dataSaver }
            }
        }
        if (fresh) pagesCache.remove(chapterId)

        val alts = backupChapters[chapterId].orEmpty()
        val candidates = if (preferBackup && alts.isNotEmpty()) alts + chapterId else listOf(chapterId) + alts

        for (candidate in candidates) {
            val set = try {
                resolveCandidatePageSet(candidate, fresh = fresh && candidate == chapterId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (set != null && (set.data.isNotEmpty() || set.dataSaver.isNotEmpty())) {
                if (candidate == chapterId) pagesCache[chapterId] = set
                return if (dataSaver) set.dataSaver.ifEmpty { set.data } else set.data.ifEmpty { set.dataSaver }
            }
        }
        throw ChapterUnavailableException(MangaDexMapper.chapterUuidOf(chapterId) ?: chapterId)
    }

    private suspend fun resolveCandidatePageSet(candidate: String, fresh: Boolean): MangaDexMapper.ChapterPageSet {
        MangaDexMapper.c2ChapterIdOf(candidate)?.let { rawC2Id ->
            val resolved = api.readerPagesC2(rawC2Id, fresh = fresh)
            recordGatewayPages(resolved)
            return resolved.pageSet
        }
        MangaDexMapper.dexChapterUuidOf(candidate)?.let { dexUuid ->
            val resolved = api.dexPagesViaGateway(dexUuid, fresh = fresh)
            recordGatewayPages(resolved)
            return resolved.pageSet
        }
        val uuid = MangaDexMapper.chapterUuidOf(candidate) ?: candidate
        val set = api.pageSet(uuid, preferDirect = fresh)
        recordFallbacks(set)
        return set
    }

    fun isComicChapter(chapterId: String): Boolean = chapterId.startsWith(MangaDexMapper.CHAPTER_PREFIX)

    override suspend fun byGenre(space: Space, genre: String): List<Item> =
        summaries.values.filter { it.genres.any { g -> g.equals(genre, true) } }

    override suspend fun genres(space: Space): List<String> =
        summaries.values.flatMap { it.genres }.distinct().sorted().take(20)

    override suspend fun allItems(space: Space): List<Item> {
        require(space == Space.COMICS) { "MangaDex serves Comics only" }
        if (summaries.isEmpty()) ComicKind.entries.forEach { runCatching { row(rowId(it, "popular")) } }
        return summaries.values.toList()
    }

    /** In-space comics search (never part of global search — isolation rule). */
    suspend fun search(query: String): List<Item> = search(query, kind = null)

    /** Live comics search optionally scoped to one [ComicKind] (`Manga`, `Manhwa`, `Manhua`). */
    suspend fun search(query: String, kind: ComicKind?): List<Item> = coroutineScope {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return@coroutineScope emptyList()
        val kinds = if (kind != null) listOf(kind) else ComicKind.entries
        kinds.map { k ->
            async {
                val viaGateway = try {
                    rememberAnilist(api.anilistBrowse(kind = k, page = 1, perPage = 16, search = trimmed).items)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
                if (!viaGateway.isNullOrEmpty()) {
                    viaGateway
                } else {
                    runCatching {
                        remember(
                            api.browse(k, 16, 0, "relevance" to "desc", trimmed).data
                                .mapNotNull { MangaDexMapper.toItem(it, k, k.id) },
                        )
                    }.getOrDefault(emptyList())
                }
            }
        }.awaitAll().flatten().distinctBy { it.id }
    }

    companion object {
        const val ROW_PREFIX = "cmrow:"
    }
}

/** Kept for symmetry with the gateway source: raw JSON helpers used by tests & AniList parsing. */
internal fun JsonElement?.textOrNull(): String? = when (this) {
    is JsonPrimitive -> contentOrNull
    is JsonObject -> (this["en"] as? JsonPrimitive)?.contentOrNull
    else -> null
}

private fun JsonObject.strOrNull(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.intOrNull(key: String): Int? =
    (this[key] as? JsonPrimitive)?.intOrNull

private fun JsonObject.boolOrNull(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.booleanOrNull

private fun JsonObject.objOrNull(key: String): JsonObject? =
    this[key] as? JsonObject

private fun JsonObject.objList(key: String): List<JsonObject> =
    (this[key] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()

private fun JsonObject.strList(key: String): List<String> =
    (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) }.orEmpty()
