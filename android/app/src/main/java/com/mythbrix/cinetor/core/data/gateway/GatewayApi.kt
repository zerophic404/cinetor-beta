package com.mythbrix.cinetor.core.data.gateway

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

// Fragment 3/5 of the ownership notice (see core/legal/Ownership.kt). Never shown in the UI.
internal const val OWNERSHIP_F3 = "3f2e28333f2c3f767a283f2c3f28293f773f343d33343f3f28767a2939283b"

/** User's antiblock gateway (docs/gateway.md). Base URL overridable in Settings → Developer. */
object GatewayConfig {
    const val BASE_URL = "https://antiblock-gateway.vercel.app"
    const val API = "/api/public/v1"
    const val CATALOG_TIMEOUT_MS = 10_000L
    const val STREAM_TIMEOUT_MS = 15_000L
    const val CATALOG_TTL_MS = 10 * 60_000L

    /** Signed links expire (`t=`): never reuse a resolved stream for longer than this. */
    const val STREAM_TTL_MS = 20 * 60_000L

    /** Blank / invalid override → default. Trailing slashes dropped. */
    fun normalize(url: String?): String {
        val u = url?.trim()?.trimEnd('/').orEmpty()
        return if (u.startsWith("https://") || u.startsWith("http://")) u else BASE_URL
    }
}

/** HTTP status error from the gateway (403 / 5xx …). */
class GatewayHttpException(val code: Int, message: String) : IOException(message)

/** Raw GET → body. Fake in tests (no network). */
fun interface GatewayHttp {
    /** @throws IOException on transport / non-2xx. */
    suspend fun get(url: String, timeoutMs: Long): String
}

/** OkHttp transport (same style as the TMDB client); cancelling the coroutine cancels the call. */
class OkHttpGatewayHttp(private val client: OkHttpClient) : GatewayHttp {
    private val clients = ConcurrentHashMap<Long, OkHttpClient>()

    override suspend fun get(url: String, timeoutMs: Long): String = suspendCancellableCoroutine { cont ->
        val c = clients.getOrPut(timeoutMs) {
            client.newBuilder().callTimeout(timeoutMs, TimeUnit.MILLISECONDS).readTimeout(timeoutMs, TimeUnit.MILLISECONDS).build()
        }
        val request = Request.Builder().url(url)
            .header("Accept", "application/json")
            // MangaDex rejects browser-like agents; the gateway does not care (docs/gateway.md).
            .header("User-Agent", "CinetorApp/1.0")
            .build()
        val call = c.newCall(request)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { r ->
                    val result = runCatching {
                        if (!r.isSuccessful) throw GatewayHttpException(r.code, "Gateway HTTP ${r.code}")
                        r.body?.string() ?: throw IOException("Gateway empty body")
                    }
                    if (!cont.isActive) return
                    result.fold({ cont.resume(it) }, { cont.resumeWithException(it) })
                }
            }
        })
    }
}

/** Disk layer of [GatewayCache] (files in the app cache dir; in-memory map in tests). */
interface GatewayDiskCache {
    fun read(key: String): Pair<Long, String>?
    fun write(key: String, savedAt: Long, body: String)
}

class FileGatewayDiskCache(private val dir: File) : GatewayDiskCache {
    private fun fileOf(key: String): File {
        val h = MessageDigest.getInstance("SHA-1").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(dir, h)
    }

    override fun read(key: String): Pair<Long, String>? = runCatching {
        val f = fileOf(key)
        if (!f.exists()) return null
        val text = f.readText()
        val nl = text.indexOf('\n')
        text.substring(0, nl).toLong() to text.substring(nl + 1)
    }.getOrNull()

    override fun write(key: String, savedAt: Long, body: String) {
        runCatching {
            dir.mkdirs()
            fileOf(key).writeText("$savedAt\n$body")
        }
    }
}

/** Memory + disk response cache with a TTL (catalog endpoints only — streams are never cached here). */
class GatewayCache(
    private val ttlMs: Long = GatewayConfig.CATALOG_TTL_MS,
    private val clock: () -> Long = System::currentTimeMillis,
    private val disk: GatewayDiskCache? = null,
    private val maxEntries: Int = 200,
) {
    private val memory = object : LinkedHashMap<String, Pair<Long, String>>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Long, String>>?) = size > maxEntries
    }

    @Synchronized
    fun get(key: String): String? {
        val now = clock()
        memory[key]?.let { (at, body) -> if (now - at < ttlMs) return body }
        val d = disk?.read(key) ?: return null
        if (now - d.first >= ttlMs) return null
        memory[key] = d
        return d.second
    }

    /** Any-age copy (offline fallback when the network call fails). */
    @Synchronized
    fun getStale(key: String): String? = memory[key]?.second ?: disk?.read(key)?.second

    @Synchronized
    fun put(key: String, body: String) {
        val now = clock()
        memory[key] = now to body
        disk?.write(key, now, body)
    }
}

// ------------------------------------------------------------------------------------------- DTOs

@Serializable
data class GwItem(
    val name: String? = null,
    @SerialName("poster_url") val posterUrl: String? = null,
    val slug: String? = null,
    @SerialName("subject_id") val subjectId: String? = null,
    val badge: String? = null,
    val rating: String? = null,
    val year: String? = null,
    val genre: String? = null,
    val type: String? = null,
    val country: String? = null,
    val description: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("has_resource") val hasResource: Boolean = true,
    val subtitles: JsonElement? = null,
    val dubs: JsonElement? = null,
    val duration: Int? = null,
)

@Serializable
data class GwPage(
    val page: Int = 1,
    @SerialName("per_page") val perPage: Int = 0,
    @SerialName("has_more") val hasMore: Boolean = false,
    val items: List<GwItem> = emptyList(),
)

@Serializable
data class GwSection(val section: String = "", val count: Int = 0, val items: List<GwItem> = emptyList())

@Serializable
data class GwHome(val status: String? = null, val sections: List<GwSection> = emptyList())

@Serializable
data class GwCover(val url: String? = null, val avgHueDark: String? = null, val avgHueLight: String? = null)

@Serializable
data class GwStar(val name: String = "", val character: String? = null, val staffType: Int = 0)

@Serializable
data class GwSeasonRes(val se: Int = 0, val maxEp: Int = 0, val allEp: String? = null)

@Serializable
data class GwResource(val seasons: List<GwSeasonRes> = emptyList())

@Serializable
data class GwSubject(
    val subjectId: String = "",
    val subjectType: Int = 1,
    val title: String = "",
    val description: String? = null,
    val releaseDate: String? = null,
    val duration: Int? = null,
    val genre: String? = null,
    val cover: GwCover? = null,
    val countryName: String? = null,
    val imdbRatingValue: String? = null,
    val subtitles: String? = null,
    val detailPath: String? = null,
)

@Serializable
data class GwDetailData(
    val subject: GwSubject? = null,
    val stars: List<GwStar> = emptyList(),
    val resource: GwResource? = null,
)

@Serializable
data class GwDetail(val code: Int = 0, val data: GwDetailData? = null)

@Serializable
data class GwSource(
    val source: String? = null,
    val isBdix: Boolean = false,
    val resolution: String? = null,
    val format: String? = null,
    val url: String = "",
    val size: Long? = null,
    val duration: Int? = null,
    val codec: String? = null,
    @SerialName("vip_locked") val vipLocked: Boolean = false,
    val headers: Map<String, String> = emptyMap(),
    @SerialName("proxy_url") val proxyUrl: String? = null,
)

@Serializable
data class GwStream(
    @SerialName("subject_id") val subjectId: String? = null,
    val se: Int = 0,
    val ep: Int = 0,
    @SerialName("has_resource") val hasResource: Boolean = false,
    @SerialName("direct_play_url") val directPlayUrl: String? = null,
    val sources: List<GwSource> = emptyList(),
)

@Serializable
data class GwCaption(val lan: String? = null, val lanName: String? = null, val url: String = "")

@Serializable
data class GwCaptions(val count: Int = 0, val captions: List<GwCaption> = emptyList())

@Serializable
data class GwSuggestion(val title: String? = null)

@Serializable
data class GwSuggest(val suggestions: List<GwSuggestion> = emptyList())

@Serializable
data class GwResolveSource(
    val provider: String? = null,
    val quality: String? = null,
    val url: String = "",
    val type: String? = null,
    val headers: Map<String, String> = emptyMap(),
)

@Serializable
data class GwResolve(val ok: Boolean = false, val sources: List<GwResolveSource> = emptyList())

/** `subtitles` / `dubs` are lists of strings in search results but objects/strings elsewhere. */
internal fun JsonElement?.stringList(): List<String> = when (this) {
    is JsonArray -> mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf { s -> s.isNotBlank() } }
    is JsonPrimitive -> contentOrNull?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
    else -> emptyList()
}

// -------------------------------------------------------------------------------------------- API

/** Thin client for `/api/public/v1`. Catalog calls go through [cache]; streams never do. */
class GatewayApi(
    private val http: GatewayHttp,
    private val cache: GatewayCache? = GatewayCache(),
    private val baseUrl: () -> String = { GatewayConfig.BASE_URL },
) {
    val base: String get() = GatewayConfig.normalize(baseUrl())

    fun url(path: String, params: Map<String, String?> = emptyMap()): String {
        val q = params.entries.filter { !it.value.isNullOrBlank() }
            .joinToString("&") { (k, v) -> enc(k) + "=" + enc(v!!) }
        return base + GatewayConfig.API + "/" + path.trimStart('/') + (if (q.isEmpty()) "" else "?$q")
    }

    /** Relative gateway path (e.g. `direct_play_url`) → absolute. */
    fun absolute(pathOrUrl: String): String =
        if (pathOrUrl.startsWith("http://") || pathOrUrl.startsWith("https://")) pathOrUrl else base + "/" + pathOrUrl.trimStart('/')

    /** Disk cache + JSON decoding never run on the main thread (scroll jank). */
    private suspend fun <T> off(block: suspend () -> T): T = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { block() }

    private suspend fun cached(url: String): String = off { cachedNow(url) }

    private suspend fun cachedNow(url: String): String {
        cache?.get(url)?.let { return it }
        val body = try {
            http.get(url, GatewayConfig.CATALOG_TIMEOUT_MS)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // Offline / gateway down: last cached copy of any age, else the error (→ retry UI).
            return cache?.getStale(url) ?: throw e
        }
        cache?.put(url, body)
        return body
    }

    suspend fun home(): GwHome = off { json.decodeFromString(GwHome.serializer(), cachedNow(url("home"))) }

    /** `movies` | `tv-series` | `animation` | `adult` list page. */
    suspend fun list(path: String, params: Map<String, String?>, page: Int = 1): GwPage =
        off { json.decodeFromString(GwPage.serializer(), cachedNow(url(path, params + ("page" to page.toString())))) }

    suspend fun search(q: String, page: Int = 1): GwPage =
        off { json.decodeFromString(GwPage.serializer(), cachedNow(url("search", mapOf("q" to q, "page" to page.toString())))) }

    suspend fun suggest(q: String): List<String> =
        json.decodeFromString(GwSuggest.serializer(), cached(url("search/suggest", mapOf("q" to q))))
            .suggestions.mapNotNull { it.title?.takeIf { t -> t.isNotBlank() } }

    suspend fun detail(slug: String): GwDetail =
        off { json.decodeFromString(GwDetail.serializer(), cachedNow(url("detail/${enc(slug)}"))) }

    suspend fun stream(subjectId: String, slug: String?, se: Int, ep: Int): GwStream =
        json.decodeFromString(
            GwStream.serializer(),
            http.get(url("stream/$subjectId", mapOf("detail_path" to slug, "se" to "$se", "ep" to "$ep")), GatewayConfig.STREAM_TIMEOUT_MS),
        )

    suspend fun captions(subjectId: String, slug: String?, se: Int, ep: Int): GwCaptions =
        json.decodeFromString(
            GwCaptions.serializer(),
            http.get(url("stream/$subjectId/captions", mapOf("detail_path" to slug, "se" to "$se", "ep" to "$ep")), GatewayConfig.STREAM_TIMEOUT_MS),
        )

    /** `/api/v1/resolve` (secondary, for TMDB-origin items). */
    suspend fun resolve(tmdbId: String, type: String, s: Int?, e: Int?, title: String, year: Int?): GwResolve {
        val params = mapOf(
            "tmdb" to tmdbId, "type" to type, "s" to s?.toString(), "e" to e?.toString(),
            "title" to title, "year" to year?.takeIf { it > 0 }?.toString(), "preferBdix" to "1",
        )
        val q = params.entries.filter { !it.value.isNullOrBlank() }.joinToString("&") { (k, v) -> enc(k) + "=" + enc(v!!) }
        return json.decodeFromString(GwResolve.serializer(), http.get("$base/api/v1/resolve?$q", GatewayConfig.STREAM_TIMEOUT_MS))
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
