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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

// This app is the complete property of Mythbrix. Do not touch, retrieve, reverse-engineer, scrape or
// hack anything without Mythbrix's explicit permission.

/**
 * Comics kind = MangaDex `originalLanguage` (user rule: Manga / Manhwa / Manhua are separate tabs).
 */
enum class ComicKind(val id: String, val title: String, val languages: List<String>) {
    MANGA("cm:manga", "Manga", listOf("ja")),
    MANHWA("cm:manhwa", "Manhwa", listOf("ko")),
    MANHUA("cm:manhua", "Manhua", listOf("zh", "zh-hk"));

    companion object {
        fun ofId(id: String): ComicKind? = entries.firstOrNull { it.id == id }
        fun ofLanguage(lang: String?): ComicKind =
            entries.firstOrNull { k -> k.languages.any { it.equals(lang, true) } } ?: MANGA
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
    val year: Int? = null,
    val status: String? = null,
    val contentRating: String? = null,
    val originalLanguage: String? = null,
    val translatedLanguage: String? = null,
    val lastChapter: String? = null,
    val chapter: String? = null,
    val volume: String? = null,
    val pages: Int = 0,
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
data class MdList(val result: String = "ok", val total: Int = 0, val limit: Int = 0, val offset: Int = 0, val data: List<MdEntity> = emptyList())

@Serializable
data class MdChapterData(val hash: String = "", val data: List<String> = emptyList(), val dataSaver: List<String> = emptyList())

@Serializable
data class MdAtHome(val result: String = "ok", val baseUrl: String = "", val chapter: MdChapterData = MdChapterData())

// ------------------------------------------------------------------------------------- mapping

/** Pure MangaDex JSON → Cinetor models (unit-testable, no Android, no network). */
object MangaDexMapper {
    const val ID_PREFIX = "cm:"
    const val CHAPTER_PREFIX = "cmch:"
    const val COVERS = "https://uploads.mangadex.org/covers"
    const val ACCENT = "#E50914"

    fun isComicId(id: String): Boolean = id.startsWith(ID_PREFIX)
    fun uuidOf(id: String): String? = id.removePrefix(ID_PREFIX).takeIf { it.isNotBlank() && it != id }
    fun chapterUuidOf(id: String): String? = id.removePrefix(CHAPTER_PREFIX).takeIf { it.isNotBlank() && it != id }

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

    /** `/manga/{id}/feed` → chapters, deduplicated per chapter number, in reading order. Pages resolve later. */
    fun toChapters(list: MdList): List<Chapter> {
        val seen = HashSet<String>()
        return list.data.asSequence()
            .filter { it.id.isNotBlank() && (it.attributes?.pages ?: 0) > 0 }
            .mapNotNull { e ->
                val a = e.attributes ?: return@mapNotNull null
                val num = a.chapter?.trim().orEmpty()
                val key = num.ifEmpty { e.id }
                if (!seen.add(key)) return@mapNotNull null
                val n = num.toDoubleOrNull()
                val label = titleOf(e)
                Chapter(
                    id = CHAPTER_PREFIX + e.id,
                    n = n?.toInt() ?: 0,
                    title = buildString {
                        append(if (num.isEmpty()) "Oneshot" else "Chapter $num")
                        if (!label.isNullOrBlank()) append(" · ").append(label)
                    },
                    // Placeholder page slots: the real URLs are resolved per chapter (gateway → MangaDex).
                    pages = emptyList(),
                )
            }
            .toList()
    }

    /** `at-home/server` response → ordered page URLs (`{baseUrl}/data/{hash}/{file}`). */
    fun pageUrls(at: MdAtHome, dataSaver: Boolean = false): List<String> {
        val base = at.baseUrl.trimEnd('/')
        val hash = at.chapter.hash
        if (base.isEmpty() || hash.isEmpty()) return emptyList()
        val files = if (dataSaver && at.chapter.dataSaver.isNotEmpty()) at.chapter.dataSaver else at.chapter.data
        val dir = if (dataSaver && at.chapter.dataSaver.isNotEmpty()) "data-saver" else "data"
        return files.filter { it.isNotBlank() }.map { "$base/$dir/$hash/$it" }
    }
}

/** Thrown when a chapter has no pages anywhere (gateway proxy empty + MangaDex empty). */
class ChapterUnavailableException(val chapterId: String) : Exception("Chapter unavailable")

// ----------------------------------------------------------------------------------------- API

/**
 * MangaDex public API for browse / search / chapters, and the user's gateway (`/manga?action=c2`)
 * for chapter page servers with a direct MangaDex fallback (docs/gateway.md → "Comics plan").
 */
class MangaDexApi(
    private val http: GatewayHttp,
    private val cache: GatewayCache? = GatewayCache(),
    private val gatewayBase: () -> String = { GatewayConfig.BASE_URL },
    private val apiBase: String = "https://api.mangadex.org",
) {
    private suspend fun cached(url: String): String = withContext(Dispatchers.IO) {
        cache?.get(url)?.let { return@withContext it }
        val body = try {
            http.get(url, GatewayConfig.CATALOG_TIMEOUT_MS)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return@withContext cache?.getStale(url) ?: throw e
        }
        cache?.put(url, body)
        body
    }

    private fun q(pairs: List<Pair<String, String>>): String =
        pairs.joinToString("&") { (k, v) -> enc(k) + "=" + enc(v) }

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

    suspend fun browse(kind: ComicKind, limit: Int = 24, offset: Int = 0, order: Pair<String, String> = "followedCount" to "desc", title: String? = null): MdList =
        withContext(Dispatchers.IO) {
            json.decodeFromString(MdList.serializer(), cached("$apiBase/manga?" + q(browseParams(kind, limit, offset, title, order))))
        }

    suspend fun chapters(mangaUuid: String, limit: Int = 500): MdList = withContext(Dispatchers.IO) {
        val url = "$apiBase/manga/$mangaUuid/feed?" + q(
            listOf(
                "translatedLanguage[]" to "en",
                "order[chapter]" to "asc",
                "limit" to limit.toString(),
                "includeExternalUrl" to "0",
                "contentRating[]" to "safe",
                "contentRating[]" to "suggestive",
            ),
        )
        json.decodeFromString(MdList.serializer(), cached(url))
    }

    /**
     * Chapter pages: gateway proxy first (`action=c2`), then MangaDex `at-home/server` directly.
     * Both can come back empty (upstream bot challenge) → [ChapterUnavailableException], never a crash.
     */
    suspend fun pages(chapterUuid: String, dataSaver: Boolean = false): List<String> = withContext(Dispatchers.IO) {
        val gateway = GatewayConfig.normalize(gatewayBase()) + GatewayConfig.API + "/manga?action=c2&id=" + enc(chapterUuid)
        val viaGateway = runCatching {
            MangaDexMapper.pageUrls(json.decodeFromString(MdAtHome.serializer(), http.get(gateway, GatewayConfig.STREAM_TIMEOUT_MS)), dataSaver)
        }.getOrDefault(emptyList())
        if (viaGateway.isNotEmpty()) return@withContext viaGateway
        val direct = runCatching {
            MangaDexMapper.pageUrls(
                json.decodeFromString(MdAtHome.serializer(), http.get("$apiBase/at-home/server/$chapterUuid", GatewayConfig.STREAM_TIMEOUT_MS)),
                dataSaver,
            )
        }.getOrDefault(emptyList())
        if (direct.isEmpty()) throw ChapterUnavailableException(chapterUuid)
        direct
    }

    companion object {
        val json: Json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
            explicitNulls = false
        }

        private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    }
}

// -------------------------------------------------------------------------------------- source

/**
 * Comics catalog from MangaDex (metadata, covers, chapters) with pages served through the gateway.
 * Tabs = Manga / Manhwa / Manhua by `originalLanguage`. Row ids: `cmrow:<KIND>:<order>`.
 * Failures throw → HybridCatalog falls back to the bundled mock comics (offline only).
 */
class MangaDexCatalogSource(
    private val api: MangaDexApi,
    private val pageSize: Int = 24,
) : CatalogRepository, PagedCatalog, RoutedCatalog {

    private val summaries = ConcurrentHashMap<String, Item>()
    private val details = ConcurrentHashMap<String, Item>()
    private val pagesCache = ConcurrentHashMap<String, List<String>>()

    override fun ownsItem(id: String): Boolean = MangaDexMapper.isComicId(id)
    override fun ownsRow(rowId: String): Boolean = rowId.startsWith(ROW_PREFIX)

    private fun remember(items: List<Item>): List<Item> {
        items.forEach { summaries.putIfAbsent(it.id, it) }
        return items
    }

    private fun rowId(kind: ComicKind, order: String) = "$ROW_PREFIX${kind.name}:$order"

    private fun parseRow(rowId: String): Pair<ComicKind, String>? {
        if (!rowId.startsWith(ROW_PREFIX)) return null
        val rest = rowId.removePrefix(ROW_PREFIX)
        val kind = runCatching { ComicKind.valueOf(rest.substringBefore(':')) }.getOrNull() ?: return null
        return kind to rest.substringAfter(':', "popular")
    }

    private fun orderOf(order: String): Pair<String, String> = when (order) {
        "latest" -> "latestUploadedChapter" to "desc"
        "rating" -> "rating" to "desc"
        "new" -> "year" to "desc"
        else -> "followedCount" to "desc"
    }

    private fun rowsOf(kind: ComicKind) = listOf(
        Row(rowId(kind, "popular"), "Popular ${kind.title}", RowStyle.POSTER, emptyList()),
        Row(rowId(kind, "latest"), "Recently Updated", RowStyle.LANDSCAPE, emptyList()),
        Row(rowId(kind, "rating"), "Top Rated", RowStyle.TOP10, emptyList()),
        Row(rowId(kind, "new"), "New Releases", RowStyle.POSTER, emptyList()),
    )

    override suspend fun categories(space: Space): List<Category> {
        require(space == Space.COMICS) { "MangaDex serves Comics only" }
        return ComicKind.entries.mapIndexed { i, kind ->
            // Probe the first tab so a dead MangaDex throws here → mock fallback for the whole space.
            val heroes = if (i == 0) row(rowId(kind, "popular")).take(5).map { it.id } else emptyList()
            Category(kind.id, Space.COMICS, kind.title, heroes, rowsOf(kind))
        }
    }

    override suspend fun row(rowId: String): List<Item> = rowPage(rowId, 1).items

    override suspend fun rowPage(rowId: String, page: Int): RowPage {
        val (kind, order) = parseRow(rowId) ?: throw NotFoundException(rowId)
        val offset = (page - 1).coerceAtLeast(0) * pageSize
        val list = api.browse(kind, pageSize, offset, orderOf(order))
        val items = remember(list.data.mapNotNull { MangaDexMapper.toItem(it, kind, kind.id) }.distinctBy { it.id })
        return RowPage(items, page, offset + list.data.size < list.total && list.data.isNotEmpty())
    }

    override suspend fun item(id: String): Item {
        details[id]?.let { return it }
        val uuid = MangaDexMapper.uuidOf(id) ?: throw NotFoundException(id)
        val summary = summaries[id]
        val base = summary ?: api.browse(ComicKind.MANGA, 1, 0).data.firstOrNull { it.id == uuid }
            ?.let { MangaDexMapper.toItem(it, ComicKind.MANGA, ComicKind.MANGA.id) }
            ?: throw NotFoundException(id)
        val chapters = runCatching { MangaDexMapper.toChapters(api.chapters(uuid)) }.getOrDefault(emptyList())
        val full = base.copy(pages = chapters)
        details[id] = full
        return full
    }

    /** Resolved page URLs for one chapter; empty list is never returned (throws instead). */
    suspend fun pages(chapterId: String, dataSaver: Boolean = false): List<String> {
        pagesCache[chapterId]?.let { return it }
        val uuid = MangaDexMapper.chapterUuidOf(chapterId) ?: throw NotFoundException(chapterId)
        val urls = api.pages(uuid, dataSaver)
        pagesCache[chapterId] = urls
        return urls
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
    suspend fun search(query: String): List<Item> {
        if (query.isBlank()) return emptyList()
        return ComicKind.entries.flatMap { kind ->
            runCatching {
                remember(
                    api.browse(kind, 12, 0, "relevance" to "desc", query.trim()).data
                        .mapNotNull { MangaDexMapper.toItem(it, kind, kind.id) },
                )
            }.getOrDefault(emptyList())
        }.distinctBy { it.id }
    }

    companion object {
        const val ROW_PREFIX = "cmrow:"
    }
}

/** Kept for symmetry with the gateway source: raw JSON helpers used by tests. */
internal fun JsonElement?.textOrNull(): String? = when (this) {
    is JsonPrimitive -> contentOrNull
    is JsonObject -> (this["en"] as? JsonPrimitive)?.contentOrNull
    else -> null
}
