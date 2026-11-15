package com.mythbrix.cinetor.core.player

import com.mythbrix.cinetor.core.data.Item
import com.mythbrix.cinetor.core.data.ItemType
import com.mythbrix.cinetor.core.data.Source
import com.mythbrix.cinetor.core.data.SourceKind
import com.mythbrix.cinetor.core.data.Sub
import com.mythbrix.cinetor.core.data.SubFormat
import com.mythbrix.cinetor.core.data.gateway.GatewayApi
import com.mythbrix.cinetor.core.data.gateway.GatewayConfig
import com.mythbrix.cinetor.core.data.gateway.GatewayMapper
import com.mythbrix.cinetor.core.data.gateway.GwCaption
import com.mythbrix.cinetor.core.data.gateway.GwSource
import com.mythbrix.cinetor.core.data.tmdb.TmdbMapper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.util.concurrent.ConcurrentHashMap

/** What play / download needs for one title or episode. [fallback] = gateway proxy (`direct_play_url`). */
data class ResolvedStream(val sources: List<Source>, val subtitles: List<Sub>, val fallback: Source?)

/**
 * Attaches real gateway sources to an item right before warm / play / download (signed links expire,
 * so nothing is baked into the catalog). Gateway items: `/stream/{id}?detail_path=&se=&ep=` (+ captions).
 * TMDB-origin items: `/api/v1/resolve`, then gateway search by title + year; nothing → no sources
 * ("No source available"). Results are cached for [GatewayConfig.STREAM_TTL_MS] per (title, se, ep).
 */
class GatewaySourceResolver(
    private val api: GatewayApi,
    private val slugOf: (String) -> String? = { null },
    private val clock: () -> Long = System::currentTimeMillis,
    /** Default-quality cap (Settings / data saver / low tier); first source = best at or below it. */
    private val maxHeight: () -> Int? = { null },
    private val log: (String) -> Unit = {},
    /** The gateway answered but had nothing playable → the catalog hides that title from now on. */
    private val onNoSource: (String) -> Unit = {},
) {
    private class Entry(val at: Long, val stream: ResolvedStream)

    private val cache = ConcurrentHashMap<String, Entry>()

    /** TMDB id → gateway (subjectId, slug), remembered for the session. */
    private val tmdbMatches = ConcurrentHashMap<String, Pair<String, String?>>()

    fun handles(item: Item): Boolean = GatewayMapper.isGatewayId(item.id) || item.id.startsWith(TmdbMapper.ID_PREFIX)

    /** (season, episode) numbers for the gateway; movie = (0, 0); series without [episodeId] → first episode. */
    fun numbersOf(item: Item, episodeId: String?): Pair<Int, Int> {
        GatewayMapper.parseEpisodeId(episodeId)?.let { return it }
        if (item.seasons.isEmpty()) return if (item.type == ItemType.SERIES) 1 to 1 else 0 to 0
        val season = item.seasons.firstOrNull { s -> s.episodes.any { it.id == episodeId } } ?: item.seasons.first()
        val ep = season.episodes.firstOrNull { it.id == episodeId } ?: season.episodes.firstOrNull()
        return season.n to (ep?.n ?: 1)
    }

    /** Item with the resolved sources/subs on the target episode (or the movie). No match → sources emptied. */
    suspend fun attach(item: Item, episodeId: String?, fresh: Boolean = false): Item {
        if (!handles(item)) return item
        // Card summary of a series (no seasons yet): wait for the full item, never bind a stream to the title.
        if (item.type == ItemType.SERIES && item.seasons.isEmpty()) return item
        var failed = false
        val stream = try {
            resolve(item, episodeId, fresh)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("gateway resolve failed for ${item.id}: ${e.message}")
            failed = true
            null
        }
        // Network failure: keep whatever the item carries (offline download stubs play from disk).
        if (failed) return item
        if ((stream == null || (stream.sources.isEmpty() && stream.fallback == null))) onNoSource(item.id)
        if (stream != null && stream.sources.isEmpty() && stream.fallback != null) {
            return withStream(item, episodeId, stream.copy(sources = listOf(stream.fallback)))
        }
        return withStream(item, episodeId, stream ?: ResolvedStream(emptyList(), emptyList(), null))
    }

    /** After a failed re-resolve: play the gateway proxy (MP4 with server-side headers). Null = none. */
    fun fallback(item: Item, episodeId: String?): Item? {
        val (se, ep) = numbersOf(item, episodeId)
        val entry = listOfNotNull(keyFor(item, se, ep), "${item.id}|$se|$ep").firstNotNullOfOrNull { cache[it]?.takeIf { e -> e.stream.fallback != null } }
            ?: return null
        val proxy = entry.stream.fallback ?: return null
        return withStream(item, episodeId, ResolvedStream(listOf(proxy), entry.stream.subtitles, proxy))
    }

    private fun keyFor(item: Item, se: Int, ep: Int): String? {
        val sid = GatewayMapper.subjectIdOf(item.id) ?: tmdbMatches[item.id]?.first ?: item.id
        return "$sid|$se|$ep"
    }

    private fun withStream(item: Item, episodeId: String?, s: ResolvedStream): Item {
        if (item.seasons.isEmpty()) return item.copy(sources = s.sources, subtitles = s.subtitles.ifEmpty { item.subtitles })
        val targetId = episodeId ?: item.seasons.firstOrNull()?.episodes?.firstOrNull()?.id
        return item.copy(
            seasons = item.seasons.map { season ->
                season.copy(
                    episodes = season.episodes.map { e ->
                        if (e.id == targetId) {
                            e.copy(sources = s.sources, subtitles = s.subtitles.ifEmpty { e.subtitles })
                        } else {
                            // Never leave mock/TMDB placeholder streams on other episodes: cached real ones or none.
                            e.copy(sources = cachedSources(item, season.n, e.n))
                        }
                    },
                )
            },
        )
    }

    private fun cachedSources(item: Item, se: Int, ep: Int): List<Source> {
        val key = keyFor(item, se, ep) ?: return emptyList()
        val e = cache[key] ?: return emptyList()
        return if (clock() - e.at < GatewayConfig.STREAM_TTL_MS) e.stream.sources else emptyList()
    }

    suspend fun resolve(item: Item, episodeId: String?, fresh: Boolean = false): ResolvedStream? {
        val (se, ep) = numbersOf(item, episodeId)
        if (GatewayMapper.isGatewayId(item.id)) {
            val sid = GatewayMapper.subjectIdOf(item.id) ?: return null
            return streamFor(sid, item.slug ?: slugOf(item.id), se, ep, fresh)
        }
        // TMDB-origin item.
        val tmdbKey = "${item.id}|$se|$ep"
        if (!fresh) cache[tmdbKey]?.takeIf { clock() - it.at < GatewayConfig.STREAM_TTL_MS }?.let { return it.stream }
        resolveApi(item, se, ep)?.let { s ->
            cache[tmdbKey] = Entry(clock(), s)
            return s
        }
        val match = tmdbMatches[item.id] ?: searchMatch(item)?.also { tmdbMatches[item.id] = it } ?: return null
        return streamFor(match.first, match.second, se, ep, fresh)?.also { cache[tmdbKey] = Entry(clock(), it) }
    }

    private suspend fun resolveApi(item: Item, se: Int, ep: Int): ResolvedStream? = try {
        val (media, tmdbId) = TmdbMapper.parseId(item.id) ?: return null
        val r = api.resolve(tmdbId.toString(), media, se.takeIf { media == TmdbMapper.TV }, ep.takeIf { media == TmdbMapper.TV }, item.title, item.year)
        val sources = r.sources.filter { it.url.isNotBlank() }.map { s ->
            val h = heightOf(s.quality)
            Source(s.url, kindOf(s.type, s.url), listOfNotNull(s.quality, s.provider).joinToString(" · "), s.headers, height = h)
        }
        if (r.ok && sources.isNotEmpty()) ResolvedStream(sortSources(sources, maxHeight()), emptyList(), null) else null
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    /** Gateway search by title (+ year ±1, type) for a TMDB title. */
    private suspend fun searchMatch(item: Item): Pair<String, String?>? {
        val wantTv = item.type == ItemType.SERIES
        val norm = normalize(item.title)
        val hits = api.search(item.title).items.filter { it.subjectId != null }
        val best = hits.firstOrNull { h ->
            val y = h.year?.take(4)?.toIntOrNull() ?: 0
            normalize(h.name.orEmpty()) == norm && (item.year == 0 || y == 0 || kotlin.math.abs(y - item.year) <= 1) &&
                ((h.type == "tv") == wantTv)
        } ?: hits.firstOrNull { h -> normalize(h.name.orEmpty()).startsWith(norm) && ((h.type == "tv") == wantTv) }
        return best?.let { it.subjectId!! to it.slug }
    }

    private suspend fun streamFor(sid: String, slug: String?, se: Int, ep: Int, fresh: Boolean): ResolvedStream? {
        val key = "$sid|$se|$ep"
        if (!fresh) cache[key]?.takeIf { clock() - it.at < GatewayConfig.STREAM_TTL_MS }?.let { return it.stream }
        val result = coroutineScope {
            val subs = async {
                try {
                    api.captions(sid, slug, se, ep).captions.mapNotNull { captionToSub(it) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    emptyList()
                }
            }
            val s = api.stream(sid, slug, se, ep)
            val sources = sortSources(s.sources.mapNotNull { toSource(it) }, maxHeight())
            val fallback = s.directPlayUrl?.takeIf { it.isNotBlank() }?.let { Source(api.absolute(it), SourceKind.MP4, "Gateway proxy") }
            if (sources.isEmpty() && fallback == null) null else ResolvedStream(sources, subs.await(), fallback)
        }
        if (result != null) {
            cache[key] = Entry(clock(), result)
            log("gateway stream $key: ${result.sources.size} sources")
        }
        return result
    }

    companion object {
        fun heightOf(resolution: String?): Int? = resolution?.let { Regex("(\\d{3,4})").find(it)?.groupValues?.get(1)?.toIntOrNull() }

        fun kindOf(format: String?, url: String): SourceKind = when (format?.uppercase()) {
            "DASH", "MPD" -> SourceKind.DASH
            "HLS", "M3U8" -> SourceKind.HLS
            "MP4" -> SourceKind.MP4
            "MKV" -> SourceKind.MKV
            else -> when (SourceResolver.streamTypeOfUrl(url)) {
                StreamType.HLS -> SourceKind.HLS
                StreamType.DASH -> SourceKind.DASH
                else -> SourceKind.MP4
            }
        }

        fun toSource(g: GwSource): Source? {
            if (g.url.isBlank() || g.vipLocked) return null
            val h = heightOf(g.resolution)
            val kind = kindOf(g.format, g.url)
            val label = listOfNotNull(h?.let { "${it}p" } ?: g.resolution, kind.name, if (isHevc(g.codec)) "HEVC" else null).joinToString(" · ")
            return Source(g.url, kind, label, g.headers, height = h, sizeBytes = g.size?.takeIf { it > 0 }, codec = g.codec)
        }

        private fun isHevc(codec: String?): Boolean = codec?.lowercase()?.let { it.startsWith("hevc") || it.contains("265") } ?: false

        /**
         * Highest resolution first; at equal height H.264 MP4 before HEVC / DASH (decodes everywhere).
         * With a cap, sources at or below it lead (best first), the rest follow.
         */
        fun sortSources(sources: List<Source>, cap: Int? = null): List<Source> {
            val byQuality = sources.sortedWith(
                compareByDescending<Source> { it.height ?: 0 }
                    .thenBy { if (isHevc(it.codec)) 1 else 0 }
                    .thenBy { if (it.kind == SourceKind.MP4) 0 else 1 },
            )
            if (cap == null) return byQuality
            val (fit, above) = byQuality.partition { (it.height ?: 0) <= cap }
            return fit + above.reversed()
        }

        fun captionToSub(c: GwCaption): Sub? {
            if (c.url.isBlank()) return null
            val path = c.url.substringBefore('?').lowercase()
            val format = when {
                path.endsWith(".vtt") -> SubFormat.VTT
                path.endsWith(".ass") || path.endsWith(".ssa") -> SubFormat.ASS
                else -> SubFormat.SRT
            }
            val lang = c.lan?.takeIf { it.isNotBlank() } ?: "und"
            return Sub(lang, c.lanName?.takeIf { it.isNotBlank() } ?: lang.uppercase(), c.url, format)
        }

        private fun normalize(s: String): String = s.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
    }
}
