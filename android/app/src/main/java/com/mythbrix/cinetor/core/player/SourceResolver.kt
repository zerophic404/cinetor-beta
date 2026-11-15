package com.mythbrix.cinetor.core.player

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import com.mythbrix.cinetor.core.data.Episode
import com.mythbrix.cinetor.core.data.Item
import com.mythbrix.cinetor.core.data.ItemType
import com.mythbrix.cinetor.core.data.Source
import com.mythbrix.cinetor.core.data.SourceKind
import com.mythbrix.cinetor.core.data.Space
import com.mythbrix.cinetor.core.data.Sub
import com.mythbrix.cinetor.core.data.SubFormat
import com.mythbrix.cinetor.core.download.DownloadCacheProvider
import com.mythbrix.cinetor.core.download.OfflineResolver
import com.mythbrix.cinetor.core.download.OfflineSource
import com.mythbrix.cinetor.core.download.SidecarSub

/** Container/protocol family chosen for a source (drives which Media3 MediaSource is used). */
enum class StreamType(val mimeType: String?) {
    HLS("application/x-mpegURL"),
    DASH("application/dash+xml"),
    SS("application/vnd.ms-sstr+xml"),
    RTSP("application/x-rtsp"),
    /** MP4/MKV/WebM/MP3: extractor sniffing, no MIME hint needed. */
    PROGRESSIVE(null),
}

/** Side-loaded subtitle ready for `MediaItem.SubtitleConfiguration`. */
data class SubPlan(val url: String, val mimeType: String, val language: String, val label: String)

/** Pure, Media3-free description of what to play (unit-testable). */
data class PlaybackPlan(
    val url: String,
    val type: StreamType,
    /** Request headers (custom source headers + Cinetor User-Agent). */
    val headers: Map<String, String>,
    val subtitles: List<SubPlan>,
)

/** What the player loads: plan + identity + metadata for the UI. */
data class PlaybackRequest(
    /** `itemId` (movie) or `itemId#episodeId`. */
    val key: String,
    val itemId: String,
    val episodeId: String?,
    val title: String,
    val subtitle: String?,
    val plan: PlaybackPlan,
    val durationSec: Int?,
    val introStartSec: Int?,
    val introEndSec: Int?,
)

object SourceResolver {
    const val USER_AGENT = "Cinetor/1.0 (Android; Media3)"

    /** Pure MIME/type decision: explicit kind first, then URL extension. */
    fun streamTypeOf(source: Source): StreamType {
        when (source.kind) {
            SourceKind.HLS -> return StreamType.HLS
            SourceKind.DASH -> return StreamType.DASH
            SourceKind.SS -> return StreamType.SS
            SourceKind.RTSP -> return StreamType.RTSP
            else -> Unit
        }
        return streamTypeOfUrl(source.url)
    }

    fun streamTypeOfUrl(url: String): StreamType {
        val lower = url.lowercase()
        if (lower.startsWith("rtsp://") || lower.startsWith("rtsps://")) return StreamType.RTSP
        val path = lower.substringBefore('?').substringBefore('#')
        return when {
            path.endsWith(".m3u8") || path.endsWith(".m3u") -> StreamType.HLS
            path.endsWith(".mpd") -> StreamType.DASH
            path.endsWith(".ism") || path.endsWith(".isml") || path.endsWith("/manifest") ||
                path.contains(".ism/manifest") -> StreamType.SS
            else -> StreamType.PROGRESSIVE
        }
    }

    fun subtitleMime(format: SubFormat): String = when (format) {
        SubFormat.VTT -> "text/vtt"
        SubFormat.SRT -> "application/x-subrip"
        SubFormat.ASS -> "text/x-ssa"
    }

    /** Pure plan: type, merged headers (source headers win over defaults), side-loaded subs. */
    fun plan(source: Source, subs: List<Sub>): PlaybackPlan {
        val headers = LinkedHashMap<String, String>()
        headers["User-Agent"] = USER_AGENT
        source.headers.forEach { (k, v) ->
            // Header names are case-insensitive: a custom User-Agent replaces the default.
            headers.keys.firstOrNull { it.equals(k, ignoreCase = true) }?.let { headers.remove(it) }
            headers[k] = v
        }
        return PlaybackPlan(
            url = source.url,
            type = streamTypeOf(source),
            headers = headers,
            subtitles = subs.map { SubPlan(subUrl(it.url), subtitleMime(it.format), it.lang, it.label) },
        )
    }

    /** Mock subs are bundled asset paths (`subs/x.vtt`); remote URLs pass through. */
    fun subUrl(url: String): String = when {
        url.contains("://") -> url
        else -> "asset:///mock/" + url.removePrefix("/")
    }

    /** Best source: HLS/DASH first (adaptive), then the rest in catalog order. */
    fun pickSource(sources: List<Source>): Source? =
        // Gateway sources arrive pre-sorted for the quality cap (GatewaySourceResolver.sortSources).
        if (sources.any { it.height != null }) sources.firstOrNull() else sources.firstOrNull { it.kind == SourceKind.HLS || it.kind == SourceKind.DASH } ?: sources.firstOrNull()

    /** All episodes of [item] in watch order. */
    fun episodes(item: Item): List<Episode> = item.seasons.flatMap { it.episodes }

    /** Pure: item (+episode) → request. Series with no [episodeId] play the first episode. Null = nothing playable. */
    fun request(item: Item, episodeId: String?): PlaybackRequest? {
        val eps = episodes(item)
        if (item.type == ItemType.SERIES || eps.isNotEmpty()) {
            val ep = eps.firstOrNull { it.id == episodeId } ?: eps.firstOrNull()
            if (ep != null) {
                val src = pickSource(ep.sources) ?: pickSource(item.sources) ?: return null
                val season = item.seasons.firstOrNull { s -> s.episodes.any { it.id == ep.id } }?.n ?: 1
                return PlaybackRequest(
                    key = keyOf(item.id, ep.id),
                    itemId = item.id,
                    episodeId = ep.id,
                    title = notificationTitle(item),
                    subtitle = "S$season:E${ep.n} · ${ep.title}",
                    plan = plan(src, ep.subtitles.ifEmpty { item.subtitles }),
                    durationSec = ep.durationSec,
                    introStartSec = ep.introStartSec,
                    introEndSec = ep.introEndSec,
                )
            }
        }
        val src = pickSource(item.sources) ?: return null
        return PlaybackRequest(
            key = keyOf(item.id, null),
            itemId = item.id,
            episodeId = null,
            title = notificationTitle(item),
            subtitle = null,
            plan = plan(src, item.subtitles),
            durationSec = item.durationSec,
            introStartSec = item.introStartSec,
            introEndSec = item.introEndSec,
        )
    }

    /** Title for MediaSession/notification metadata: adult titles are never shown outside the app. */
    fun notificationTitle(item: Item): String = if (item.space == Space.ADULT) "Private video" else item.title

    fun keyOf(itemId: String, episodeId: String?): String = if (episodeId == null) itemId else "$itemId#$episodeId"

    /** Next episode after [episodeId] across seasons, or null (movie / last episode). */
    fun nextEpisode(item: Item, episodeId: String?): Episode? {
        if (episodeId == null) return null
        val eps = episodes(item)
        val i = eps.indexOfFirst { it.id == episodeId }
        return if (i >= 0) eps.getOrNull(i + 1) else null
    }

    /** Pure: subtitle mime from a file extension (sidecar subs of downloads). */
    fun subtitleMimeOfExt(ext: String): String = when (ext.lowercase()) {
        "srt" -> "application/x-subrip"
        "ass", "ssa" -> "text/x-ssa"
        else -> "text/vtt"
    }

    /** Pure: subs usable offline = bundled/local ones from the plan + downloaded sidecar files. */
    fun offlineSubs(plan: PlaybackPlan, sidecars: List<SidecarSub>): List<SubPlan> =
        plan.subtitles.filter { OfflineResolver.isOfflineSubUrl(it.url) } +
            sidecars.map { SubPlan("file://" + it.path, subtitleMimeOfExt(it.ext), it.lang, it.lang.uppercase()) }

    private fun subConfigs(subs: List<SubPlan>): List<MediaItem.SubtitleConfiguration> = subs.map { s ->
        MediaItem.SubtitleConfiguration.Builder(Uri.parse(s.url))
            .setMimeType(s.mimeType)
            .setLanguage(s.language)
            .setLabel(s.label)
            .setSelectionFlags(0)
            .build()
    }

    private fun metadata(request: PlaybackRequest): MediaMetadata =
        MediaMetadata.Builder().setTitle(request.title).setSubtitle(request.subtitle).build()

    /** Android: progressive download on disk → MediaSource (no network at all). */
    @OptIn(UnstableApi::class)
    fun toLocalMediaSource(context: android.content.Context, request: PlaybackRequest, file: OfflineSource.LocalFile): MediaSource {
        val item = MediaItem.Builder()
            .setUri(Uri.fromFile(file.file))
            .setMediaId(request.key)
            .setSubtitleConfigurations(subConfigs(offlineSubs(request.plan, file.sidecars)))
            .setMediaMetadata(metadata(request))
            .build()
        return DefaultMediaSourceFactory(androidx.media3.datasource.DefaultDataSource.Factory(context)).createMediaSource(item)
    }

    /** Android: HLS/DASH download → MediaSource reading segments from the download cache (never writes). */
    @OptIn(UnstableApi::class)
    fun toCachedMediaSource(
        context: android.content.Context,
        request: PlaybackRequest,
        cached: OfflineSource.Cached,
        provider: DownloadCacheProvider,
    ): MediaSource {
        val upstream = androidx.media3.datasource.DefaultDataSource.Factory(context, httpFactory(request.plan.headers))
        val item = cached.request.toMediaItem().buildUpon()
            .setMediaId(request.key)
            .setSubtitleConfigurations(subConfigs(offlineSubs(request.plan, emptyList())))
            .setMediaMetadata(metadata(request))
            .build()
        return DefaultMediaSourceFactory(provider.cacheDataSourceFactory(upstream)).createMediaSource(item)
    }

    /** Android: plan → MediaItem (MIME hint + side-loaded subtitles). */
    fun toMediaItem(request: PlaybackRequest): MediaItem {
        val p = request.plan
        val subs = subConfigs(p.subtitles)
        return MediaItem.Builder()
            .setUri(p.url)
            .setMediaId(request.key)
            .apply { p.type.mimeType?.let { setMimeType(it) } }
            .setSubtitleConfigurations(subs)
            .setMediaMetadata(
                MediaMetadata.Builder().setTitle(request.title).setSubtitle(request.subtitle).build(),
            )
            .build()
    }

    /** Android: HTTP factory with the plan's headers as default request properties. */
    @OptIn(UnstableApi::class)
    fun httpFactory(headers: Map<String, String>): DefaultHttpDataSource.Factory {
        val ua = headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value ?: USER_AGENT
        return DefaultHttpDataSource.Factory()
            .setUserAgent(ua)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(8_000)
            .setReadTimeoutMs(12_000)
            .setDefaultRequestProperties(headers.filterKeys { !it.equals("User-Agent", true) })
    }

    /** Android: MediaSource through the shared cache (RTSP bypasses HTTP/cache internally). */
    @OptIn(UnstableApi::class)
    fun toMediaSource(context: android.content.Context, request: PlaybackRequest, cache: SimpleCache?): MediaSource {
        val http = httpFactory(request.plan.headers)
        val upstream: DataSource.Factory = androidx.media3.datasource.DefaultDataSource.Factory(context, http)
        val factory: DataSource.Factory = if (cache != null && request.plan.type != StreamType.RTSP) {
            CacheDataSource.Factory()
                .setCache(cache)
                .setUpstreamDataSourceFactory(upstream)
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        } else {
            upstream
        }
        return DefaultMediaSourceFactory(factory).createMediaSource(toMediaItem(request))
    }
}

/** Kept for API parity with the plan doc (`SourceResolver.toMediaItem(source, subs)`). */
fun SourceResolver.toMediaItem(source: Source, subs: List<Sub>, key: String = source.url): MediaItem =
    toMediaItem(
        PlaybackRequest(key, key, null, "", null, plan(source, subs), null, null, null),
    )
