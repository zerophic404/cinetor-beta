package com.mythbrix.cinetor.feature.music

import androidx.compose.runtime.Immutable
import com.mythbrix.cinetor.core.data.Item
import com.mythbrix.cinetor.core.data.ItemType
import com.mythbrix.cinetor.core.data.Source
import com.mythbrix.cinetor.core.data.SourceKind
import com.mythbrix.cinetor.core.data.Space
import com.mythbrix.cinetor.core.data.gateway.DominantColors
import com.mythbrix.cinetor.core.data.gateway.GatewayCache
import com.mythbrix.cinetor.core.data.gateway.GatewayHttp
import com.mythbrix.cinetor.core.data.gateway.GatewayHttpException
import com.mythbrix.cinetor.core.data.gateway.NoDominantColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.IOException

@Serializable
enum class MusicAudioQuality(
    val queryValue: String,
    val shortLabel: String,
    val badgeLabel: String,
) {
    FLAC("flac", "FLAC", "FLAC Lossless"),
    KBPS_320("320", "320kbps", "320kbps Studio Master");

    fun toggle(): MusicAudioQuality = if (this == FLAC) KBPS_320 else FLAC
}

@Serializable
enum class MusicSearchType(
    val queryValue: String,
    val label: String,
) {
    SONG("song", "Songs"),
    ALBUM("album", "Albums"),
    PLAYLIST("playlist", "Playlists"),
}

@Immutable
@Serializable
data class GwMusicTrack(
    val id: String,
    val title: String,
    val artist: String = "Unknown Artist",
    val album: String = "",
    val albumId: String? = null,
    val year: String? = null,
    val duration: Int = 0,
    val durationLabel: String = "0:00",
    val artwork: String = "",
    val quality: String = MusicAudioQuality.FLAC.badgeLabel,
    val format: String = "flac",
    val hasFlac: Boolean = true,
)

@Immutable
@Serializable
data class GwMusicChart(
    val id: String,
    val title: String,
    val artwork: String = "",
    val count: Int = 0,
    val subtitle: String = "",
)

@Immutable
@Serializable
data class GwMusicAlbum(
    val id: String,
    val title: String,
    val artist: String = "",
    val year: String? = null,
    val artwork: String = "",
    val count: Int = 0,
    val tracks: List<GwMusicTrack> = emptyList(),
)

@Immutable
@Serializable
data class GwMusicPlaylist(
    val id: String,
    val title: String,
    val artwork: String = "",
    val count: Int = 0,
    val tracks: List<GwMusicTrack> = emptyList(),
)

@Immutable
@Serializable
data class GwMusicStream(
    val streamUrl: String,
    val downloadUrl: String,
    val quality: String,
    val format: String,
    val headers: Map<String, String> = emptyMap(),
    val sizeBytes: Long? = null,
)

@Immutable
data class GwMusicTrendingFeed(
    val charts: List<GwMusicChart> = emptyList(),
    val tracks: List<GwMusicTrack> = emptyList(),
    val categories: List<String> = emptyList(),
)

/** Thrown only when the device cannot reach the gateway AND has no cached copy on disk/memory. */
class OfflineNoCacheException(cause: Throwable) : IOException("Music gateway unreachable with no cache", cause)

object GatewayMusicMapper {
    const val TRACK_PREFIX = "mu-tr:"
    const val ALBUM_PREFIX = "mu-al:"
    const val PLAYLIST_PREFIX = "mu-al-pl:"
    private const val DEFAULT_DOMINANT = "#121216"
    private const val DEFAULT_ACCENT = "#E11D2E"

    val DEFAULT_CATEGORIES: List<String> = listOf(
        "Trending",
        "Top Charts",
        "Bollywood",
        "Bengali",
        "Arijit Singh",
        "English Hits",
        "Punjabi",
        "Romantic",
        "Lo-Fi & Chill",
    )

    private val lenientJson = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    fun isGatewayTrackId(id: String): Boolean = id.startsWith(TRACK_PREFIX)
    fun isGatewayAlbumId(id: String): Boolean = id.startsWith(ALBUM_PREFIX)
    fun isGatewayPlaylistId(id: String): Boolean = id.startsWith(PLAYLIST_PREFIX)

    fun rawIdOf(id: String): String = when {
        id.startsWith(PLAYLIST_PREFIX) -> id.removePrefix(PLAYLIST_PREFIX)
        id.startsWith(ALBUM_PREFIX) -> id.removePrefix(ALBUM_PREFIX)
        id.startsWith(TRACK_PREFIX) -> id.removePrefix(TRACK_PREFIX)
        else -> id
    }

    private fun parseObj(raw: String): JsonObject? =
        runCatching { lenientJson.parseToJsonElement(raw) as? JsonObject }.getOrNull()

    private fun JsonElement?.prim(): JsonPrimitive? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }

    private fun JsonObject.str(key: String): String? =
        this[key].prim()?.content?.trim()?.takeIf { it.isNotEmpty() && it != "null" }

    private fun JsonObject.int(key: String): Int? =
        this[key].prim()?.let { it.content.toIntOrNull() ?: it.content.toDoubleOrNull()?.toInt() }

    private fun JsonObject.long(key: String): Long? =
        this[key].prim()?.let { it.content.toLongOrNull() ?: it.content.toDoubleOrNull()?.toLong() }

    private fun JsonObject.bool(key: String): Boolean? =
        this[key].prim()?.content?.lowercase()?.toBooleanStrictOrNull()

    private fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray

    private fun JsonObject.objs(key: String): List<JsonObject> =
        arr(key)?.mapNotNull { it as? JsonObject }.orEmpty()

    private fun parseDurationLabelSeconds(label: String?): Int {
        if (label.isNullOrBlank()) return 0
        val parts = label.trim().split(':')
        return when (parts.size) {
            2 -> (parts[0].toIntOrNull() ?: 0) * 60 + (parts[1].toIntOrNull() ?: 0)
            3 -> (parts[0].toIntOrNull() ?: 0) * 3600 + (parts[1].toIntOrNull() ?: 0) * 60 + (parts[2].toIntOrNull() ?: 0)
            else -> 0
        }
    }

    private fun formatDurationSeconds(sec: Int): String {
        if (sec <= 0) return "0:00"
        val m = sec / 60
        val s = sec % 60
        return "$m:${s.toString().padStart(2, '0')}"
    }

    fun parseTrackObj(o: JsonObject, fallbackArtist: String = "", fallbackAlbum: String = "", fallbackArtwork: String = ""): GwMusicTrack? {
        val id = o.str("id") ?: return null
        val title = o.str("title") ?: o.str("name") ?: return null
        val rawLabel = o.str("durationLabel")
        val rawDur = o.int("duration") ?: 0
        val dur = if (rawDur > 0) rawDur else parseDurationLabelSeconds(rawLabel)
        val label = rawLabel?.takeIf { it != "0:00" || dur == 0 } ?: formatDurationSeconds(dur)
        val hasFlac = o.bool("hasFlac") ?: true
        val defaultQuality = if (hasFlac) MusicAudioQuality.FLAC.badgeLabel else MusicAudioQuality.KBPS_320.badgeLabel
        return GwMusicTrack(
            id = id,
            title = title,
            artist = (o.str("artist") ?: fallbackArtist).ifBlank { "Unknown Artist" },
            album = o.str("album") ?: fallbackAlbum,
            albumId = o.str("albumId"),
            year = o.str("year"),
            duration = dur,
            durationLabel = label,
            artwork = o.str("artwork") ?: o.str("poster") ?: o.str("image") ?: fallbackArtwork,
            quality = o.str("quality") ?: defaultQuality,
            format = o.str("format") ?: if (hasFlac) "flac" else "mp4",
            hasFlac = hasFlac,
        )
    }

    private fun parseChartObj(o: JsonObject): GwMusicChart? {
        val id = o.str("id") ?: return null
        val title = o.str("title") ?: o.str("name") ?: return null
        return GwMusicChart(
            id = id,
            title = title,
            artwork = o.str("artwork") ?: o.str("poster") ?: o.str("image").orEmpty(),
            count = o.int("count") ?: o.int("trackCount") ?: 0,
            subtitle = o.str("subtitle") ?: o.str("artist").orEmpty(),
        )
    }

    private fun parseAlbumSummaryObj(o: JsonObject): GwMusicAlbum? {
        val id = o.str("id") ?: return null
        val title = o.str("title") ?: o.str("name") ?: return null
        val artist = o.str("artist").orEmpty()
        val art = o.str("artwork") ?: o.str("poster") ?: o.str("image").orEmpty()
        val tracks = o.objs("tracks").mapNotNull { parseTrackObj(it, fallbackArtist = artist, fallbackAlbum = title, fallbackArtwork = art) }
        return GwMusicAlbum(
            id = id,
            title = title,
            artist = artist,
            year = o.str("year"),
            artwork = art,
            count = o.int("count") ?: o.int("trackCount") ?: tracks.size,
            tracks = tracks,
        )
    }

    fun parseTrending(raw: String): GwMusicTrendingFeed {
        val json = parseObj(raw) ?: return GwMusicTrendingFeed()
        val charts = json.objs("charts").mapNotNull(::parseChartObj)
        val trackObjs = json.objs("trendingTracks").ifEmpty { json.objs("tracks") }
        val tracks = trackObjs.mapNotNull { parseTrackObj(it) }
        val categories = json.arr("categories")
            ?.mapNotNull { it.prim()?.content?.trim()?.takeIf(String::isNotBlank) }
            .orEmpty()
        return GwMusicTrendingFeed(
            charts = charts,
            tracks = tracks,
            categories = categories.ifEmpty { DEFAULT_CATEGORIES },
        )
    }

    private fun hasMoreOf(json: JsonObject, count: Int): Boolean =
        json.bool("has_more") ?: json.bool("hasMore") ?: (count >= 10)

    fun parseSearchSongs(raw: String): Pair<List<GwMusicTrack>, Boolean> {
        val json = parseObj(raw) ?: return emptyList<GwMusicTrack>() to false
        val items = (json.objs("results").ifEmpty { json.objs("items") }.ifEmpty { json.objs("tracks") })
            .mapNotNull { parseTrackObj(it) }
        return items to hasMoreOf(json, items.size)
    }

    fun parseSearchAlbums(raw: String): Pair<List<GwMusicAlbum>, Boolean> {
        val json = parseObj(raw) ?: return emptyList<GwMusicAlbum>() to false
        val items = (json.objs("results").ifEmpty { json.objs("items") }.ifEmpty { json.objs("albums") })
            .mapNotNull(::parseAlbumSummaryObj)
        return items to hasMoreOf(json, items.size)
    }

    fun parseSearchPlaylists(raw: String): Pair<List<GwMusicChart>, Boolean> {
        val json = parseObj(raw) ?: return emptyList<GwMusicChart>() to false
        val items = (json.objs("results").ifEmpty { json.objs("items") }.ifEmpty { json.objs("playlists") })
            .mapNotNull(::parseChartObj)
        return items to hasMoreOf(json, items.size)
    }

    fun parseAlbum(raw: String, fallbackId: String): GwMusicAlbum? {
        val json = parseObj(raw) ?: return null
        val title = json.str("title") ?: json.str("name") ?: return null
        val artist = json.str("artist").orEmpty()
        val art = json.str("artwork") ?: json.str("poster") ?: json.str("image").orEmpty()
        val tracks = json.objs("tracks").mapNotNull {
            parseTrackObj(it, fallbackArtist = artist, fallbackAlbum = title, fallbackArtwork = art)
        }
        return GwMusicAlbum(
            id = json.str("id") ?: fallbackId,
            title = title,
            artist = artist,
            year = json.str("year"),
            artwork = art,
            count = json.int("count") ?: tracks.size,
            tracks = tracks,
        )
    }

    fun parsePlaylist(raw: String, fallbackId: String): GwMusicPlaylist? {
        val json = parseObj(raw) ?: return null
        val title = json.str("title") ?: json.str("name") ?: return null
        val art = json.str("artwork") ?: json.str("poster") ?: json.str("image").orEmpty()
        val tracks = json.objs("tracks").mapNotNull { parseTrackObj(it, fallbackArtwork = art) }
        return GwMusicPlaylist(
            id = json.str("id") ?: fallbackId,
            title = title,
            artwork = art,
            count = json.int("count")?.takeIf { it > 0 } ?: tracks.size,
            tracks = tracks,
        )
    }

    fun parseLyrics(raw: String): MusicLyrics? {
        val json = parseObj(raw) ?: return null
        val plain = json.str("plainLyrics") ?: json.str("lyrics")
        val synced = json.str("syncedLyrics") ?: json.str("lrc")
        if (plain.isNullOrBlank() && synced.isNullOrBlank()) return null
        return MusicLyrics(
            plainLyrics = plain,
            syncedLyrics = synced,
            lines = LrcParser.parse(synced),
        )
    }

    fun parseStream(raw: String, absoluteUrl: (String) -> String = { it }): GwMusicStream? {
        val json = parseObj(raw) ?: return null
        val rawStreamUrl = json.str("streamUrl") ?: json.str("url") ?: return null
        val streamUrl = absoluteUrl(rawStreamUrl)
        val downloadUrl = json.str("downloadUrl")?.let(absoluteUrl) ?: streamUrl
        val quality = json.str("quality") ?: MusicAudioQuality.KBPS_320.badgeLabel
        val format = json.str("format") ?: "mp4"
        val headers = LinkedHashMap<String, String>()
        (json["headers"] as? JsonObject)?.let { h ->
            h.keys.forEach { k -> h.str(k)?.let { headers[k] = it } }
        }
        return GwMusicStream(
            streamUrl = streamUrl,
            downloadUrl = downloadUrl,
            quality = quality,
            format = format,
            headers = headers,
            sizeBytes = json.long("size") ?: json.long("sizeBytes"),
        )
    }

    fun sourceKindForFormat(format: String, url: String): SourceKind {
        val f = format.trim().lowercase()
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        return when {
            f == "hls" || f == "m3u8" || path.endsWith(".m3u8") -> SourceKind.HLS
            f == "dash" || f == "mpd" || path.endsWith(".mpd") -> SourceKind.DASH
            f == "mp3" || path.endsWith(".mp3") -> SourceKind.MP3
            f == "webm" || f == "opus" || path.endsWith(".webm") -> SourceKind.WEBM
            f == "mkv" || path.endsWith(".mkv") -> SourceKind.MKV
            else -> SourceKind.MP4
        }
    }

    fun sourceOfStream(stream: GwMusicStream, requested: MusicAudioQuality, forDownload: Boolean = false): Source {
        val targetUrl = if (forDownload) stream.downloadUrl else stream.streamUrl
        val kind = sourceKindForFormat(stream.format, targetUrl)
        val label = stream.quality.ifBlank { requested.badgeLabel }
        return Source(
            url = targetUrl,
            kind = kind,
            label = label,
            headers = stream.headers,
            sizeBytes = stream.sizeBytes,
            codec = stream.format.uppercase(),
        )
    }

    fun trackToItem(
        t: GwMusicTrack,
        colors: DominantColors = NoDominantColors,
        trackNumber: Int? = null,
        overrideAlbumId: String? = null,
    ): Item {
        val dom = t.artwork.takeIf { it.isNotBlank() }?.let(colors::colorFor) ?: DEFAULT_DOMINANT
        val parsedYear = t.year?.take(4)?.toIntOrNull() ?: 0
        val mappedAlbumId = overrideAlbumId ?: t.albumId?.takeIf { it.isNotBlank() }?.let {
            if (it.startsWith(ALBUM_PREFIX) || it.startsWith(PLAYLIST_PREFIX)) it else ALBUM_PREFIX + it
        }
        return Item(
            id = if (isGatewayTrackId(t.id)) t.id else TRACK_PREFIX + t.id,
            space = Space.MUSIC,
            categoryId = "mu-tracks",
            type = ItemType.TRACK,
            title = t.title,
            altTitle = t.durationLabel,
            year = parsedYear,
            rating = 0.0,
            maturity = if (t.hasFlac) "FLAC" else "320K",
            genres = emptyList(),
            synopsis = "",
            poster = t.artwork,
            backdrop = t.artwork,
            dominantColor = dom,
            accentColor = DEFAULT_ACCENT,
            durationSec = t.duration,
            artist = t.artist,
            album = t.album.takeIf { it.isNotBlank() },
            albumId = mappedAlbumId,
            trackNumber = trackNumber,
            studio = t.quality,
        )
    }

    fun albumToItem(a: GwMusicAlbum, colors: DominantColors = NoDominantColors): Item {
        val id = if (isGatewayAlbumId(a.id)) a.id else ALBUM_PREFIX + a.id
        val dom = a.artwork.takeIf { it.isNotBlank() }?.let(colors::colorFor) ?: DEFAULT_DOMINANT
        val trackItems = a.tracks.mapIndexed { idx, tr -> trackToItem(tr, colors, idx + 1, overrideAlbumId = id) }
        return Item(
            id = id,
            space = Space.MUSIC,
            categoryId = "mu-albums",
            type = ItemType.ALBUM,
            title = a.title,
            altTitle = if (a.count > 0) "${a.count} tracks" else null,
            year = a.year?.take(4)?.toIntOrNull() ?: 0,
            rating = 0.0,
            maturity = "FLAC",
            genres = emptyList(),
            synopsis = "",
            poster = a.artwork,
            backdrop = a.artwork,
            dominantColor = dom,
            accentColor = DEFAULT_ACCENT,
            artist = a.artist.ifBlank { "Various Artists" },
            trackIds = trackItems.map { it.id },
            studio = MusicAudioQuality.FLAC.badgeLabel,
        )
    }

    fun chartToItem(c: GwMusicChart, colors: DominantColors = NoDominantColors): Item {
        val id = if (isGatewayPlaylistId(c.id)) c.id else PLAYLIST_PREFIX + c.id
        val dom = c.artwork.takeIf { it.isNotBlank() }?.let(colors::colorFor) ?: DEFAULT_DOMINANT
        return Item(
            id = id,
            space = Space.MUSIC,
            categoryId = "mu-charts",
            type = ItemType.ALBUM,
            title = c.title,
            altTitle = if (c.count > 0) "${c.count} tracks" else "Top Chart",
            year = 0,
            rating = 0.0,
            maturity = "FLAC",
            genres = emptyList(),
            synopsis = c.subtitle,
            poster = c.artwork,
            backdrop = c.artwork,
            dominantColor = dom,
            accentColor = DEFAULT_ACCENT,
            artist = c.subtitle.ifBlank { if (c.count > 0) "${c.count} Tracks" else "Curated Playlist" },
            studio = MusicAudioQuality.FLAC.badgeLabel,
        )
    }

    fun playlistToItem(p: GwMusicPlaylist, colors: DominantColors = NoDominantColors): Pair<Item, List<Item>> {
        val id = if (isGatewayPlaylistId(p.id)) p.id else PLAYLIST_PREFIX + p.id
        val dom = p.artwork.takeIf { it.isNotBlank() }?.let(colors::colorFor) ?: DEFAULT_DOMINANT
        val tracks = p.tracks.mapIndexed { idx, tr -> trackToItem(tr, colors, idx + 1, overrideAlbumId = id) }
        val count = tracks.size.takeIf { it > 0 } ?: p.count
        val item = Item(
            id = id,
            space = Space.MUSIC,
            categoryId = "mu-charts",
            type = ItemType.ALBUM,
            title = p.title,
            altTitle = if (count > 0) "$count tracks" else null,
            year = 0,
            rating = 0.0,
            maturity = "FLAC",
            genres = emptyList(),
            synopsis = "",
            poster = p.artwork,
            backdrop = p.artwork,
            dominantColor = dom,
            accentColor = DEFAULT_ACCENT,
            artist = if (count > 0) "$count Tracks · Studio Master" else "Curated Playlist",
            trackIds = tracks.map { it.id },
            studio = MusicAudioQuality.FLAC.badgeLabel,
        )
        return item to tracks
    }

    /** Crisp user-visible quality badge label for a track (`FLAC Lossless` or `320kbps Studio Master`). */
    fun qualityBadgeFor(track: Item, preferred: MusicAudioQuality, activeStreamQuality: String? = null): String {
        if (!activeStreamQuality.isNullOrBlank()) {
            return when {
                activeStreamQuality.contains("flac", ignoreCase = true) -> MusicAudioQuality.FLAC.badgeLabel
                activeStreamQuality.contains("320", ignoreCase = true) -> MusicAudioQuality.KBPS_320.badgeLabel
                else -> activeStreamQuality
            }
        }
        val trackSupportsFlac = track.maturity != "320K"
        return if (preferred == MusicAudioQuality.FLAC && trackSupportsFlac) {
            MusicAudioQuality.FLAC.badgeLabel
        } else {
            MusicAudioQuality.KBPS_320.badgeLabel
        }
    }
}

/**
 * Live gateway client for `/api/public/v1/music/` endpoints.
 * Browse/search/lyrics responses are cached in [cache] (with stale fallback when offline);
 * signed `/music/stream` URLs are never written to disk cache.
 */
class GatewayMusicApi(
    private val http: GatewayHttp,
    private val cache: GatewayCache? = GatewayCache(),
    private val urlFor: (String, Map<String, String?>) -> String,
    private val absoluteUrl: (String) -> String = { it },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    companion object {
        private const val CATALOG_TIMEOUT_MS = 10_000L
        private const val STREAM_TIMEOUT_MS = 15_000L
    }

    private suspend fun cached(path: String, params: Map<String, String?> = emptyMap()): String =
        withContext(ioDispatcher) {
            val url = urlFor(path, params)
            cache?.get(url)?.let { return@withContext it }
            val body = try {
                http.get(url, CATALOG_TIMEOUT_MS)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                cache?.getStale(url)?.let { return@withContext it }
                if (e is IOException && e !is GatewayHttpException) {
                    throw OfflineNoCacheException(e)
                }
                throw e
            }
            cache?.put(url, body)
            body
        }

    suspend fun trending(): GwMusicTrendingFeed =
        GatewayMusicMapper.parseTrending(cached("music/trending"))

    suspend fun searchSongs(query: String, page: Int = 1): Pair<List<GwMusicTrack>, Boolean> {
        if (query.isBlank()) return emptyList<GwMusicTrack>() to false
        val raw = cached("music/search", mapOf("q" to query.trim(), "type" to MusicSearchType.SONG.queryValue, "page" to page.toString()))
        return GatewayMusicMapper.parseSearchSongs(raw)
    }

    suspend fun searchAlbums(query: String, page: Int = 1): Pair<List<GwMusicAlbum>, Boolean> {
        if (query.isBlank()) return emptyList<GwMusicAlbum>() to false
        val raw = cached("music/search", mapOf("q" to query.trim(), "type" to MusicSearchType.ALBUM.queryValue, "page" to page.toString()))
        return GatewayMusicMapper.parseSearchAlbums(raw)
    }

    suspend fun searchPlaylists(query: String, page: Int = 1): Pair<List<GwMusicChart>, Boolean> {
        if (query.isBlank()) return emptyList<GwMusicChart>() to false
        val raw = cached("music/search", mapOf("q" to query.trim(), "type" to MusicSearchType.PLAYLIST.queryValue, "page" to page.toString()))
        return GatewayMusicMapper.parseSearchPlaylists(raw)
    }

    suspend fun album(id: String): GwMusicAlbum? {
        val rawId = GatewayMusicMapper.rawIdOf(id)
        if (rawId.isBlank()) return null
        val raw = cached("music/album", mapOf("id" to rawId))
        return GatewayMusicMapper.parseAlbum(raw, rawId)
    }

    suspend fun playlist(id: String): GwMusicPlaylist? {
        val rawId = GatewayMusicMapper.rawIdOf(id)
        if (rawId.isBlank()) return null
        val raw = cached("music/playlist", mapOf("id" to rawId))
        return GatewayMusicMapper.parsePlaylist(raw, rawId)
    }

    suspend fun lyrics(id: String, title: String = "", artist: String = ""): MusicLyrics? {
        val rawId = GatewayMusicMapper.rawIdOf(id)
        if (rawId.isBlank() && title.isBlank()) return null
        val raw = try {
            cached("music/lyrics", mapOf("id" to rawId, "title" to title, "artist" to artist))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return null
        }
        return GatewayMusicMapper.parseLyrics(raw)
    }

    suspend fun stream(id: String, quality: MusicAudioQuality = MusicAudioQuality.FLAC): GwMusicStream? =
        withContext(ioDispatcher) {
            val rawId = GatewayMusicMapper.rawIdOf(id)
            if (rawId.isBlank()) return@withContext null
            val primary = fetchStreamOnce(rawId, quality.queryValue)
            if (primary != null) return@withContext primary
            if (quality == MusicAudioQuality.FLAC) {
                fetchStreamOnce(rawId, MusicAudioQuality.KBPS_320.queryValue)?.let { return@withContext it }
            }
            fetchStreamOnce(rawId, null)
        }

    private suspend fun fetchStreamOnce(rawId: String, qualityParam: String?): GwMusicStream? {
        val params = if (qualityParam != null) mapOf("id" to rawId, "quality" to qualityParam) else mapOf("id" to rawId)
        val body = try {
            http.get(urlFor("music/stream", params), STREAM_TIMEOUT_MS)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return null
        }
        return GatewayMusicMapper.parseStream(body, absoluteUrl)
    }
}
