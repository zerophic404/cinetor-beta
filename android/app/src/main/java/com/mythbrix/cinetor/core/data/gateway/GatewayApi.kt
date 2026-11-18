package com.mythbrix.cinetor.core.data.gateway

import com.mythbrix.cinetor.core.data.DubOption
import com.mythbrix.cinetor.core.data.fullLanguageName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
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
    const val FULL_STREAM_TIMEOUT_MS = 25_000L
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

/** Pure HMAC-SHA256 request signer & User-Agent router for gateway endpoints. */
object GatewaySigner {
    const val BROWSER_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36 Cinetor/2.0"
    const val MANGADEX_USER_AGENT = "CinetorApp/1.0"

    private val SERVER_TIME_RE = Regex(""""serverTime"\s*:\s*(\d+)""")

    private data class Origin(val scheme: String, val host: String, val port: Int)

    private fun parseOrigin(url: String): Origin? = runCatching {
        val trimmed = url.trim()
        val schemeEnd = trimmed.indexOf("://")
        if (schemeEnd <= 0) return null
        val rest = trimmed.substring(schemeEnd + 3)
        val authorityEnd = listOf(rest.indexOf('/'), rest.indexOf('?'), rest.indexOf('#')).filter { it >= 0 }.minOrNull() ?: rest.length
        val originOnly = trimmed.substring(0, schemeEnd + 3 + authorityEnd)
        val u = URI(originOnly)
        val scheme = u.scheme?.lowercase(Locale.US)?.takeIf { it == "https" || it == "http" } ?: return null
        val host = u.host?.lowercase(Locale.US)?.takeIf { it.isNotEmpty() } ?: return null
        val port = when {
            u.port > 0 -> u.port
            scheme == "https" -> 443
            else -> 80
        }
        Origin(scheme, host, port)
    }.getOrNull()

    private fun hostOf(url: String): String =
        url.substringAfter("://", "").substringBefore('/').substringBefore(':').lowercase(Locale.US)

    /** Exact encoded URL pathname before `?` or `#` (e.g. `/api/public/v1/home`). */
    fun encodedPathOf(url: String): String {
        val withoutScheme = url.substringAfter("://", "")
        val slash = withoutScheme.indexOf('/')
        if (slash < 0) return "/"
        val pathAndRest = withoutScheme.substring(slash)
        val q = pathAndRest.indexOf('?')
        val h = pathAndRest.indexOf('#')
        val end = listOf(q, h).filter { it >= 0 }.minOrNull() ?: pathAndRest.length
        return pathAndRest.substring(0, end).ifEmpty { "/" }
    }

    /**
     * Returns `true` ONLY when [url] matches the exact scheme+host(+port) of `GatewayConfig.normalize(gatewayBase)`
     * AND its path starts with `/api/` (excluding `/api/public/v1/health` and `/api/v1/health`).
     * Never signs `mangadex.org` or third-party hosts.
     */
    fun shouldSign(url: String, gatewayBase: String): Boolean {
        val target = parseOrigin(url) ?: return false
        val base = parseOrigin(GatewayConfig.normalize(gatewayBase)) ?: return false
        if (target != base) return false
        val path = encodedPathOf(url)
        if (!path.startsWith("/api/")) return false
        val clean = path.trimEnd('/')
        return clean != "/api/public/v1/health" && clean != "/api/v1/health"
    }

    fun userAgentFor(url: String, @Suppress("UNUSED_PARAMETER") gatewayBase: String = GatewayConfig.BASE_URL): String {
        val host = hostOf(url)
        return if (host == "mangadex.org" || host.endsWith(".mangadex.org")) MANGADEX_USER_AGENT else BROWSER_USER_AGENT
    }

    fun signHeaders(secret: String, method: String, url: String, nowEpochSec: Long): Map<String, String> {
        if (secret.isBlank()) return emptyMap()
        val encodedPath = encodedPathOf(url)
        val ts = nowEpochSec.toString()
        val payload = "${method.uppercase(Locale.US)}:$encodedPath:$ts"
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val sigHex = mac.doFinal(payload.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        return mapOf(
            "X-App-Timestamp" to ts,
            "X-App-Signature" to sigHex,
        )
    }

    /** Extracts server epoch seconds from JSON `"serverTime"` or RFC 1123 HTTP `Date` header. */
    fun parseServerEpochSec(errorBody: String?, dateHeader: String?): Long? {
        errorBody?.let { body ->
            SERVER_TIME_RE.find(body)?.groupValues?.get(1)?.toLongOrNull()?.let { raw ->
                return if (raw > 100_000_000_000L) raw / 1000L else raw
            }
        }
        val d = dateHeader?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return runCatching {
            val fmt = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("GMT")
            }
            fmt.parse(d)?.let { it.time / 1000L }
        }.getOrNull()
    }
}

/** OkHttp transport (same style as the TMDB client); cancelling the coroutine cancels the call. */
class OkHttpGatewayHttp(
    private val client: OkHttpClient,
    private val signingSecret: () -> String = { "" },
    private val gatewayBase: () -> String = { GatewayConfig.BASE_URL },
    private val nowMs: () -> Long = System::currentTimeMillis,
) : GatewayHttp {
    private data class RawReply(val code: Int, val body: String?, val dateHeader: String?)

    private val clients = ConcurrentHashMap<Long, OkHttpClient>()

    @Volatile
    private var clockOffsetSec: Long = 0L

    override suspend fun get(url: String, timeoutMs: Long): String {
        val base = gatewayBase()
        val secret = signingSecret()
        val sign = secret.isNotBlank() && GatewaySigner.shouldSign(url, base)
        val first = executeOnce(url, timeoutMs, base, if (sign) secret else "")
        if (first.code == 401 && sign) {
            val serverSec = GatewaySigner.parseServerEpochSec(first.body, first.dateHeader)
            if (serverSec != null) {
                clockOffsetSec = serverSec - (nowMs() / 1000L)
                val retry = executeOnce(url, timeoutMs, base, secret)
                if (retry.code in 200..299) {
                    return retry.body ?: throw IOException("Gateway empty body")
                }
                throw GatewayHttpException(retry.code, "Gateway HTTP ${retry.code}")
            }
        }
        if (first.code !in 200..299) {
            throw GatewayHttpException(first.code, "Gateway HTTP ${first.code}")
        }
        return first.body ?: throw IOException("Gateway empty body")
    }

    private suspend fun executeOnce(url: String, timeoutMs: Long, base: String, secret: String): RawReply =
        suspendCancellableCoroutine { cont ->
            val c = clients.getOrPut(timeoutMs) {
                client.newBuilder()
                    .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                    .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                    .build()
            }
            val builder = Request.Builder().url(url)
                .header("Accept", "application/json")
                .header("User-Agent", GatewaySigner.userAgentFor(url, base))
            if (secret.isNotBlank()) {
                val epochSec = (nowMs() / 1000L) + clockOffsetSec
                GatewaySigner.signHeaders(secret, "GET", url, epochSec).forEach { (k, v) ->
                    builder.header(k, v)
                }
            }
            val call = c.newCall(builder.build())
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use { r ->
                        val result = runCatching {
                            RawReply(r.code, r.body?.string(), r.header("Date"))
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
    val title: String? = null,
    @SerialName("base_title") val baseTitle: String? = null,
    @SerialName("poster_url") val posterUrl: String? = null,
    val slug: String? = null,
    @SerialName("detail_path") val detailPath: String? = null,
    @SerialName("subject_id") val subjectId: String? = null,
    val badge: String? = null,
    val rating: String? = null,
    val year: String? = null,
    val genre: String? = null,
    val type: String? = null,
    @SerialName("subjectType") val subjectType: Int? = null,
    val country: String? = null,
    val description: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("has_resource") val hasResource: Boolean = true,
    val subtitles: JsonElement? = null,
    val dubs: JsonElement? = null,
    val duration: Int? = null,
) {
    val resolvedName: String?
        get() = (name ?: title)?.trim()?.takeIf { it.isNotEmpty() }

    val resolvedSlug: String?
        get() = (slug ?: detailPath)?.trim()?.takeIf { it.isNotEmpty() }
}

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
data class GwStar(
    val name: String = "",
    val character: String? = null,
    val staffType: Int = 0,
    val avatarUrl: String? = null,
)

@Serializable
data class GwEpisodeRes(
    val episode: Int? = null,
    val name: String? = null,
    val stillUrl: String? = null,
    @SerialName("still_url") val stillUrlSnake: String? = null,
    val stillPath: String? = null,
) {
    val resolvedStill: String?
        get() = (stillUrl ?: stillUrlSnake ?: stillPath)?.takeIf { it.isNotBlank() }
}

@Serializable
data class GwSeasonRes(
    val se: Int = 0,
    val season: Int? = null,
    val maxEp: Int = 0,
    val allEp: String? = null,
    val episodes: List<GwEpisodeRes> = emptyList(),
)

@Serializable
data class GwResource(val seasons: List<GwSeasonRes> = emptyList())

@Serializable
data class GwVideoAddress(
    val videoId: String? = null,
    val definition: String? = null,
    val url: String? = null,
    val duration: Int? = null,
    val width: Int? = null,
    val height: Int? = null,
    val size: Long? = null,
)

@Serializable
data class GwTrailer(
    val videoAddress: GwVideoAddress? = null,
    val cover: GwCover? = null,
    val url: String? = null,
)

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
    val stills: GwCover? = null,
    val corner: String? = null,
    val countryName: String? = null,
    val imdbRatingValue: String? = null,
    val subtitles: String? = null,
    val dubs: JsonElement? = null,
    val trailer: GwTrailer? = null,
    @SerialName("trailer_url") val trailerUrl: String? = null,
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
data class GwManifest(
    val format: String? = null,
    val id: String? = null,
    val url: String = "",
    val resolutions: String? = null,
    val size: String? = null,
    val duration: Int? = null,
    val codecName: String? = null,
    val signCookie: String? = null,
    val signHeaderKey: String? = null,
    val vipLocked: Boolean = false,
)

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
    val language: String? = null,
    val audio: String? = null,
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
    val hls: List<GwManifest> = emptyList(),
    val dash: List<GwManifest> = emptyList(),
    @SerialName("trailer_url") val trailerUrl: String? = null,
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
    val isBdix: Boolean = false,
    val headers: Map<String, String> = emptyMap(),
)

@Serializable
data class GwResolve(val ok: Boolean = false, val sources: List<GwResolveSource> = emptyList())

@Serializable
data class GwSubtitleResult(
    val id: String? = null,
    val language: String? = null,
    val label: String? = null,
    @SerialName("vtt_url") val vttUrl: String? = null,
    val url: String? = null,
)

@Serializable
data class GwSubtitleSearch(
    val count: Int = 0,
    val subtitles: List<GwSubtitleResult> = emptyList(),
)

/** `subtitles` / `dubs` are lists of strings in search results, or lists of objects in browse/detail. */
internal fun JsonElement?.stringList(): List<String> = when (this) {
    is JsonArray -> mapNotNull { el ->
        when (el) {
            is JsonPrimitive -> el.contentOrNull?.takeIf { it.isNotBlank() }
            is JsonObject -> {
                // type == 1 in /detail dubs is a burnt-in subtitle variant, not an audio dub; original == true is Original Audio.
                val typeInt = (el["type"] as? JsonPrimitive)?.intOrNull ?: 0
                val isOriginal = (el["original"] as? JsonPrimitive)?.booleanOrNull == true
                if (typeInt != 0 || isOriginal) {
                    null
                } else {
                    listOf("language_name", "lanName", "language", "name", "lanCode", "language_code")
                        .firstNotNullOfOrNull { k -> (el[k] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } }
                }
            }
            else -> null
        }
    }
    is JsonPrimitive -> contentOrNull?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
    else -> emptyList()
}

/** Extracts structured [DubOption] entries from `/detail` (`subjectId`/`detailPath`/`lanName`) or `/search` (`subject_id`/`slug`/`language_name`). */
internal fun JsonElement?.dubOptionList(excludeSubjectId: String? = null): List<DubOption> {
    val arr = this as? JsonArray ?: return emptyList()
    val seen = LinkedHashSet<String>()
    val out = ArrayList<DubOption>()
    for (el in arr) {
        val obj = el as? JsonObject ?: continue
        val typeInt = (obj["type"] as? JsonPrimitive)?.intOrNull ?: 0
        if (typeInt != 0) continue
        val isOriginal = (obj["original"] as? JsonPrimitive)?.booleanOrNull == true
        val sid = listOf("subjectId", "subject_id")
            .firstNotNullOfOrNull { k -> (obj[k] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() } }
            ?: continue
        if (excludeSubjectId != null && sid == excludeSubjectId) continue
        if (!seen.add(sid)) continue
        val slug = listOf("detailPath", "detail_path", "slug")
            .firstNotNullOfOrNull { k -> (obj[k] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() } }
            ?: sid
        val rawCode = listOf("lanCode", "language_code", "lan", "code")
            .firstNotNullOfOrNull { k -> (obj[k] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() } }
            .orEmpty()
        val rawName = listOf("lanName", "language_name", "language", "name")
            .firstNotNullOfOrNull { k -> (obj[k] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() } }
            .orEmpty()
        val langName = if (isOriginal && (rawName.isEmpty() || rawName.contains("original", ignoreCase = true))) {
            "Original"
        } else {
            fullLanguageName(rawName.ifBlank { rawCode })
        }
        out += DubOption(
            subjectId = sid,
            slug = slug,
            languageName = langName,
            languageCode = rawCode.lowercase(Locale.US),
        )
    }
    return out
}

// ------------------------------------------------------------------------------- TMDB Metadata Bridge

data class TmdbCandidate(
    val tmdbId: Int,
    val mediaType: String,
    val title: String,
    val originalTitle: String? = null,
    val year: Int = 0,
    val rating: Double = 0.0,
    val posterUrl: String = "",
    val backdropUrl: String = "",
    val synopsis: String = "",
)

interface GatewayTmdbBridge {
    val isConfigured: Boolean
    suspend fun searchMulti(query: String): List<TmdbCandidate>
    suspend fun personCredits(personName: String): List<TmdbCandidate>
    suspend fun detail(mediaType: String, tmdbId: Int): TmdbCandidate?
}

object NoGatewayTmdbBridge : GatewayTmdbBridge {
    override val isConfigured: Boolean = false
    override suspend fun searchMulti(query: String): List<TmdbCandidate> = emptyList()
    override suspend fun personCredits(personName: String): List<TmdbCandidate> = emptyList()
    override suspend fun detail(mediaType: String, tmdbId: Int): TmdbCandidate? = null
}

/**
 * Lightweight TMDB v3/v4 helper for hybrid search, cold deep-links (`gw:tmdb-tv-*` / `gw:tmdb-movie-*`),
 * and actor filmographies (`castWorks`). Supports both Bearer `TMDB_TOKEN` and v3 `TMDB_API_KEY`.
 */
class HttpGatewayTmdbBridge(
    private val client: OkHttpClient,
    private val bearerToken: () -> String = { "" },
    private val apiKey: () -> String = { "" },
    private val apiBase: String = "https://api.themoviedb.org/3",
    private val imageBase: String = "https://image.tmdb.org/t/p",
) : GatewayTmdbBridge {

    override val isConfigured: Boolean
        get() = bearerToken().isNotBlank() || apiKey().isNotBlank()

    private suspend fun getJson(path: String, params: Map<String, String> = emptyMap()): JsonObject? {
        val token = bearerToken().trim()
        val key = apiKey().trim()
        if (token.isEmpty() && key.isEmpty()) return null
        val allParams = LinkedHashMap<String, String>(params)
        if (token.isEmpty() && key.isNotEmpty()) {
            allParams["api_key"] = key
        }
        val query = allParams.entries
            .filter { it.value.isNotBlank() }
            .joinToString("&") { (k, v) -> URLEncoder.encode(k, "UTF-8") + "=" + URLEncoder.encode(v, "UTF-8") }
        val fullUrl = apiBase.trimEnd('/') + "/" + path.trimStart('/') + (if (query.isEmpty()) "" else "?$query")
        val body = withContext(Dispatchers.IO) {
            suspendCancellableCoroutine<String?> { cont ->
                val reqBuilder = Request.Builder()
                    .url(fullUrl)
                    .header("Accept", "application/json")
                if (token.isNotEmpty()) {
                    reqBuilder.header("Authorization", "Bearer $token")
                }
                val call = client.newCall(reqBuilder.build())
                cont.invokeOnCancellation { call.cancel() }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (cont.isActive) cont.resume(null)
                    }

                    override fun onResponse(call: Call, response: Response) {
                        response.use { r ->
                            val str = if (r.code in 200..299) runCatching { r.body?.string() }.getOrNull() else null
                            if (cont.isActive) cont.resume(str)
                        }
                    }
                })
            }
        } ?: return null
        return runCatching { GatewayApi.json.parseToJsonElement(body) as? JsonObject }.getOrNull()
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    private fun JsonObject.numDouble(key: String): Double =
        (this[key] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull() ?: 0.0

    private fun parseCandidate(obj: JsonObject, forcedMediaType: String? = null): TmdbCandidate? {
        if ((obj["adult"] as? JsonPrimitive)?.booleanOrNull == true) return null
        val media = (forcedMediaType ?: obj.str("media_type"))?.lowercase(Locale.US) ?: return null
        if (media != "movie" && media != "tv") return null
        val id = (obj["id"] as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 } ?: return null
        val title = (if (media == "tv") obj.str("name") ?: obj.str("title") else obj.str("title") ?: obj.str("name"))
            ?: return null
        val origTitle = if (media == "tv") obj.str("original_name") else obj.str("original_title")
        val dateStr = if (media == "tv") obj.str("first_air_date") else obj.str("release_date")
        val year = dateStr?.take(4)?.toIntOrNull() ?: 0
        val poster = obj.str("poster_path")?.let { p -> if (p.startsWith("http")) p else "$imageBase/w500/${p.trimStart('/')}" }.orEmpty()
        val backdrop = obj.str("backdrop_path")?.let { b -> if (b.startsWith("http")) b else "$imageBase/w780/${b.trimStart('/')}" }
            ?: poster
        return TmdbCandidate(
            tmdbId = id,
            mediaType = media,
            title = title,
            originalTitle = origTitle,
            year = year,
            rating = obj.numDouble("vote_average"),
            posterUrl = poster,
            backdropUrl = backdrop,
            synopsis = obj.str("overview").orEmpty(),
        )
    }

    override suspend fun searchMulti(query: String): List<TmdbCandidate> {
        val q = query.trim()
        if (q.isEmpty() || !isConfigured) return emptyList()
        val root = getJson(
            "search/multi",
            mapOf("query" to q, "include_adult" to "false", "language" to "en-US", "page" to "1"),
        ) ?: return emptyList()
        val results = root["results"] as? JsonArray ?: return emptyList()
        return results.mapNotNull { (it as? JsonObject)?.let { o -> parseCandidate(o) } }.take(12)
    }

    override suspend fun personCredits(personName: String): List<TmdbCandidate> {
        val q = personName.trim()
        if (q.isEmpty() || !isConfigured) return emptyList()
        val searchRoot = getJson(
            "search/person",
            mapOf("query" to q, "include_adult" to "false", "language" to "en-US", "page" to "1"),
        ) ?: return emptyList()
        val personObj = (searchRoot["results"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return emptyList()
        val personId = (personObj["id"] as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 } ?: return emptyList()
        val creditsRoot = getJson("person/$personId/combined_credits", mapOf("language" to "en-US")) ?: return emptyList()
        val castArr = (creditsRoot["cast"] as? JsonArray).orEmpty()
        val crewArr = (creditsRoot["crew"] as? JsonArray).orEmpty().filter { el ->
            val job = ((el as? JsonObject)?.get("job") as? JsonPrimitive)?.contentOrNull.orEmpty()
            job.equals("Director", ignoreCase = true) || job.equals("Creator", ignoreCase = true)
        }
        return (castArr + crewArr)
            .mapNotNull { el ->
                val obj = el as? JsonObject ?: return@mapNotNull null
                val pop = obj.numDouble("popularity")
                parseCandidate(obj)?.let { pop to it }
            }
            .sortedByDescending { it.first }
            .map { it.second }
            .distinctBy { "${it.mediaType}:${it.tmdbId}" }
            .take(24)
    }

    override suspend fun detail(mediaType: String, tmdbId: Int): TmdbCandidate? {
        if (tmdbId <= 0 || !isConfigured) return null
        val kind = if (mediaType.equals("tv", ignoreCase = true)) "tv" else "movie"
        val obj = getJson("$kind/$tmdbId", mapOf("language" to "en-US")) ?: return null
        return parseCandidate(obj, forcedMediaType = kind)
    }
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

    private val catalogPermits = Semaphore(5)

    /** Disk cache + JSON decoding never run on the main thread (scroll jank). */
    private suspend fun <T> off(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }

    private suspend fun cached(url: String): String = off { cachedNow(url) }

    private suspend fun cachedNow(url: String): String {
        cache?.get(url)?.let { return it }
        return catalogPermits.withPermit {
            cache?.get(url)?.let { return@withPermit it }
            val body = try {
                http.get(url, GatewayConfig.CATALOG_TIMEOUT_MS)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e is GatewayHttpException && e.code == 451) throw e
                // Offline / gateway down: last cached copy of any age, else the error (→ retry UI).
                return@withPermit cache?.getStale(url) ?: throw e
            }
            cache?.put(url, body)
            body
        }
    }

    suspend fun home(): GwHome = off { json.decodeFromString(GwHome.serializer(), cachedNow(url("home"))) }

    /** `movies` | `tv-series` | `animation` | `short-tv` | `zee-bangla` | `adult` list page. */
    suspend fun list(path: String, params: Map<String, String?>, page: Int = 1): GwPage = off {
        try {
            json.decodeFromString(GwPage.serializer(), cachedNow(url(path, params + ("page" to page.toString()))))
        } catch (e: GatewayHttpException) {
            // Adult endpoint returns HTTP 451 in geo-restricted regions; treat as an empty page rather than crashing.
            if (path.trim('/') == "adult" && e.code == 451) GwPage(page = page, hasMore = false, items = emptyList())
            else throw e
        }
    }

    suspend fun search(q: String, page: Int = 1): GwPage =
        off { json.decodeFromString(GwPage.serializer(), cachedNow(url("search", mapOf("q" to q, "page" to page.toString())))) }

    suspend fun suggest(q: String): List<String> =
        json.decodeFromString(GwSuggest.serializer(), cached(url("search/suggest", mapOf("q" to q))))
            .suggestions.mapNotNull { it.title?.takeIf { t -> t.isNotBlank() } }

    suspend fun detail(slug: String): GwDetail =
        off { json.decodeFromString(GwDetail.serializer(), cachedNow(url("detail/${enc(slug)}"))) }

    suspend fun stream(subjectId: String, slug: String?, se: Int, ep: Int, full: Boolean = false): GwStream {
        val params = LinkedHashMap<String, String?>()
        params["detail_path"] = slug
        params["se"] = "$se"
        params["ep"] = "$ep"
        if (full) params["full"] = "1"
        val timeout = if (full) GatewayConfig.FULL_STREAM_TIMEOUT_MS else GatewayConfig.STREAM_TIMEOUT_MS
        return json.decodeFromString(GwStream.serializer(), http.get(url("stream/$subjectId", params), timeout))
    }

    suspend fun captions(subjectId: String, slug: String?, se: Int, ep: Int): GwCaptions =
        json.decodeFromString(
            GwCaptions.serializer(),
            http.get(url("stream/$subjectId/captions", mapOf("detail_path" to slug, "se" to "$se", "ep" to "$ep")), GatewayConfig.STREAM_TIMEOUT_MS),
        )

    /** `/api/public/v1/subtitles/search` online WebVTT subtitle lookup. */
    suspend fun subtitleSearch(
        q: String,
        subjectId: String? = null,
        slug: String? = null,
        se: Int = 0,
        ep: Int = 0,
        lang: String? = null,
    ): GwSubtitleSearch {
        val params = LinkedHashMap<String, String?>()
        if (q.isNotBlank()) params["q"] = q
        if (!subjectId.isNullOrBlank()) params["subject_id"] = subjectId
        if (!slug.isNullOrBlank()) params["detail_path"] = slug
        params["se"] = "$se"
        params["ep"] = "$ep"
        if (!lang.isNullOrBlank()) params["lang"] = lang
        return off {
            json.decodeFromString(
                GwSubtitleSearch.serializer(),
                http.get(url("subtitles/search", params), GatewayConfig.STREAM_TIMEOUT_MS),
            )
        }
    }

    /** Wraps an external SRT/VTT URL through `/api/public/v1/subtitle?url=...` for CORS-safe WebVTT conversion. */
    fun subtitleProxyUrl(rawUrl: String): String {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) return ""
        if (trimmed.contains("/api/public/v1/subtitle?") || trimmed.startsWith(base)) return absolute(trimmed)
        return url("subtitle", mapOf("url" to trimmed))
    }

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
