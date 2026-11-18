package com.mythbrix.cinetor.core.player

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import com.mythbrix.cinetor.core.data.DubOption
import com.mythbrix.cinetor.core.data.Item
import com.mythbrix.cinetor.core.data.ItemType
import com.mythbrix.cinetor.core.data.Source
import com.mythbrix.cinetor.core.data.SourceKind
import com.mythbrix.cinetor.core.data.Space
import com.mythbrix.cinetor.core.data.Sub
import com.mythbrix.cinetor.core.data.SubFormat
import com.mythbrix.cinetor.core.data.fullLanguageName
import com.mythbrix.cinetor.core.data.gateway.GatewayApi
import com.mythbrix.cinetor.core.data.gateway.GatewayConfig
import com.mythbrix.cinetor.core.data.gateway.GatewayMapper
import com.mythbrix.cinetor.core.data.gateway.GwCaption
import com.mythbrix.cinetor.core.data.gateway.GwManifest
import com.mythbrix.cinetor.core.data.gateway.GwSource
import com.mythbrix.cinetor.core.data.gateway.GwStream
import com.mythbrix.cinetor.core.data.gateway.dubOptionList
import com.mythbrix.cinetor.core.data.tmdb.TmdbMapper
import java.net.URLDecoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

/** What play / download needs for one title or episode. [fallback] = gateway proxy (`direct_play_url`). */
data class ResolvedStream(val sources: List<Source>, val subtitles: List<Sub>, val fallback: Source?)

/** Live network mode updated by `GatewayNetworkWatcher` on Android (false = unmetered Wi-Fi/Ethernet, true = metered mobile). */
object NetworkState {
    @Volatile
    var mobile: Boolean = false
}

/** Hardware HEVC 10-bit capability probe, read once; defaults to `true` in JVM unit tests. */
object DeviceCodecs {
    val hevc10: Boolean by lazy {
        runCatching {
            val infos = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            if (infos.isNullOrEmpty()) {
                true
            } else {
                infos.any { info ->
                    !info.isEncoder && info.supportedTypes.orEmpty().any { it.equals("video/hevc", ignoreCase = true) } &&
                        info.getCapabilitiesForType("video/hevc")?.profileLevels.orEmpty().any {
                            it.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10
                        }
                }
            }
        }.getOrDefault(true)
    }
}

/**
 * Session-level stream health & codec tracker ported from Cinetor Beta `StreamResolver`:
 * - Hosts returning 403/428/429/451 rest for 6 hours.
 * - Dead URLs returning 404/410 are skipped for 6 hours.
 * - Devices where HEVC 10-bit fails demote HEVC streams to the end of the queue.
 * - Optional `preferDualAudio` promotes multi-language audio streams.
 */
object LinkHealth {
    const val REST_MS = 6 * 60 * 60 * 1_000L

    private val hosts = ConcurrentHashMap<String, Long>()
    private val links = ConcurrentHashMap<String, Long>()

    @Volatile
    var hevcFailed: Boolean = false

    @Volatile
    var preferDualAudio: Boolean = false

    private val HEVC_HINT = Regex("""(?i)(x265|hevc|h\.?265|10[ -]?bit|hdr10?\b)""")

    internal fun hostOf(url: String): String =
        url.substringAfter("://", "").substringBefore('/').substringBefore(':').lowercase(Locale.US)

    fun failed(url: String, code: Int, nowMs: Long = System.currentTimeMillis()) {
        val until = nowMs + REST_MS
        when (code) {
            403, 428, 429, 451 -> hostOf(url).takeIf { it.isNotEmpty() }?.let { hosts[it] = until }
            404, 410 -> links[url.substringBefore('?')] = until
        }
    }

    fun markDeadLink(url: String, nowMs: Long = System.currentTimeMillis()) = failed(url, 404, nowMs)

    fun refused(url: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val host = hostOf(url)
        return (host.isNotEmpty() && (hosts[host] ?: 0L) > nowMs) || (links[url.substringBefore('?')] ?: 0L) > nowMs
    }

    fun undecodable(source: Source): Boolean {
        if (!hevcFailed && DeviceCodecs.hevc10) return false
        return GatewaySourceResolver.isHevc(source.codec) ||
            HEVC_HINT.containsMatchIn(source.label.orEmpty() + " " + GatewaySourceResolver.safeDecodeUrl(source.url.substringBefore('?')))
    }

    fun reset() {
        hosts.clear()
        links.clear()
        hevcFailed = false
        preferDualAudio = false
    }
}

/**
 * Attaches real gateway sources to an item right before warm / play / download (signed links expire,
 * so nothing is baked into the catalog). Gateway items: `/stream/{id}?detail_path=&se=&ep=&full=1` (+ captions).
 * TMDB-origin items: `/api/v1/resolve`, then gateway search by title + year; nothing → no sources
 * ("No source available"). Results are cached for [GatewayConfig.STREAM_TTL_MS] per (title, se, ep, network).
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
    /** True when the active connection is metered mobile data; defaults to [NetworkState.mobile]. */
    private val isMobile: () -> Boolean = { NetworkState.mobile },
) {
    private class Entry(
        val at: Long,
        val stream: ResolvedStream,
        val checkedDubIds: Set<String> = emptySet(),
    )

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
        val baseKey = keyFor(item, se, ep)
        val candidates = listOfNotNull(
            baseKey?.let { "$it|${netTag()}" },
            baseKey?.let { "$it|w" },
            baseKey?.let { "$it|m" },
            baseKey,
            "${item.id}|$se|$ep|${netTag()}",
            "${item.id}|$se|$ep",
        )
        val entry = candidates.firstNotNullOfOrNull { cache[it]?.takeIf { e -> e.stream.fallback != null } } ?: return null
        val proxy = entry.stream.fallback ?: return null
        return withStream(item, episodeId, ResolvedStream(listOf(proxy), entry.stream.subtitles, proxy))
    }

    private fun netTag(): String = if (isMobile()) "m" else "w"

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
        val base = keyFor(item, se, ep) ?: return emptyList()
        val e = cache["$base|${netTag()}"] ?: cache["$base|w"] ?: cache["$base|m"] ?: cache[base] ?: return emptyList()
        return if (clock() - e.at < GatewayConfig.STREAM_TTL_MS) e.stream.sources else emptyList()
    }

    suspend fun resolve(item: Item, episodeId: String?, fresh: Boolean = false): ResolvedStream? {
        val (se, ep) = numbersOf(item, episodeId)
        val adultItem = item.space == Space.ADULT || item.id.startsWith(GatewayMapper.ADULT_PREFIX)
        if (GatewayMapper.isGatewayId(item.id)) {
            val sid = GatewayMapper.subjectIdOf(item.id) ?: return null
            val resolvedSlug = item.slug ?: slugOf(item.id) ?: sid
            val primaryAudio = GatewayMapper.explicitDubLanguageOf(item.title, null, resolvedSlug) ?: "Original"
            val primaryStream = runCatching {
                streamFor(
                    sid = sid,
                    slug = resolvedSlug,
                    se = se,
                    ep = ep,
                    fresh = fresh,
                    adultAllowed = adultItem,
                    itemDubs = item.dubs,
                    primaryAudioLang = primaryAudio,
                )
            }.getOrElse { e ->
                if (e is CancellationException) throw e
                if (GatewayMapper.parseTmdbSubjectId(sid) == null) throw e
                null
            }
            if (primaryStream != null) return primaryStream

            GatewayMapper.parseTmdbSubjectId(sid)?.let { (media, tmdbId) ->
                val tmdbMedia = if (media == "tv") TmdbMapper.TV else "movie"
                resolveByTmdbId(tmdbId.toLong(), tmdbMedia, se, ep, item.title, item.year, adultItem)?.let { return it }
                val match = tmdbMatches[item.id] ?: searchMatch(item)?.also { tmdbMatches[item.id] = it } ?: return null
                return streamFor(match.first, match.second, se, ep, fresh, adultItem, item.dubs, primaryAudio)
            }
            return null
        }
        // TMDB-origin item.
        val tmdbKey = "${item.id}|$se|$ep|${netTag()}"
        if (!fresh) cache[tmdbKey]?.takeIf { clock() - it.at < GatewayConfig.STREAM_TTL_MS }?.let { return it.stream }
        resolveApi(item, se, ep, adultItem)?.let { s ->
            cache[tmdbKey] = Entry(clock(), s)
            return s
        }
        val match = tmdbMatches[item.id] ?: searchMatch(item)?.also { tmdbMatches[item.id] = it } ?: return null
        return streamFor(match.first, match.second, se, ep, fresh, adultItem)?.also { cache[tmdbKey] = Entry(clock(), it) }
    }

    private suspend fun resolveApi(item: Item, se: Int, ep: Int, adultAllowed: Boolean): ResolvedStream? {
        val (media, tmdbId) = TmdbMapper.parseId(item.id) ?: return null
        return resolveByTmdbId(tmdbId, media, se, ep, item.title, item.year, adultAllowed)
    }

    private suspend fun resolveByTmdbId(
        tmdbId: Long,
        media: String,
        se: Int,
        ep: Int,
        title: String,
        year: Int,
        adultAllowed: Boolean,
    ): ResolvedStream? = try {
        val r = api.resolve(tmdbId.toString(), media, se.takeIf { media == TmdbMapper.TV }, ep.takeIf { media == TmdbMapper.TV }, title, year)
        val gwSources = r.sources.filter { it.url.isNotBlank() }.map { s ->
            GwSource(
                source = s.provider,
                isBdix = s.isBdix || s.provider.equals("bdix", ignoreCase = true),
                resolution = s.quality,
                format = s.type,
                url = s.url,
                headers = s.headers,
            )
        }
        val ranked = rankGwSources(
            raw = gwSources,
            baseUrl = api.base,
            directPlayUrl = null,
            mobile = isMobile(),
            cap = maxHeight(),
            adultAllowed = adultAllowed,
            nowMs = clock(),
        )
        if (r.ok && ranked.isNotEmpty()) ResolvedStream(ranked, emptyList(), null) else null
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    /** Gateway search by title (+ year ±1, type) for a TMDB title. */
    private suspend fun searchMatch(item: Item): Pair<String, String?>? {
        val wantTv = item.type == ItemType.SERIES
        val norm = normalize(item.title)
        val hits = api.search(item.title).items.filter { it.subjectId != null }
        val best = hits.firstOrNull { h ->
            val y = h.year?.take(4)?.toIntOrNull() ?: 0
            normalize(h.resolvedName.orEmpty()) == norm && (item.year == 0 || y == 0 || kotlin.math.abs(y - item.year) <= 1) &&
                ((h.type == "tv") == wantTv)
        } ?: hits.firstOrNull { h -> normalize(h.resolvedName.orEmpty()).startsWith(norm) && ((h.type == "tv") == wantTv) }
        return best?.let { it.subjectId!! to it.resolvedSlug }
    }

    private suspend fun fetchStreamWithRescue(sid: String, slug: String?, se: Int, ep: Int, full: Boolean = true): GwStream {
        var s = api.stream(sid, slug, se, ep, full = full)
        if (s.sources.isEmpty() && s.dash.isEmpty() && s.hls.isEmpty() && s.directPlayUrl.isNullOrBlank() && s.trailerUrl.isNullOrBlank()) {
            // Rescue movies indexed as S1E1 or single-part TV specials indexed as 0/0 on the gateway.
            if (se == 0 && ep == 0) {
                runCatching { api.stream(sid, slug, 1, 1, full = full) }.getOrNull()?.takeIf {
                    it.sources.isNotEmpty() || it.dash.isNotEmpty() || it.hls.isNotEmpty() || !it.directPlayUrl.isNullOrBlank()
                }?.let { s = it }
            } else if (se == 1 && ep == 1) {
                runCatching { api.stream(sid, slug, 0, 0, full = full) }.getOrNull()?.takeIf {
                    it.sources.isNotEmpty() || it.dash.isNotEmpty() || it.hls.isNotEmpty() || !it.directPlayUrl.isNullOrBlank()
                }?.let { s = it }
            }
        }
        return s
    }

    private suspend fun streamFor(
        sid: String,
        slug: String?,
        se: Int,
        ep: Int,
        fresh: Boolean,
        adultAllowed: Boolean,
        itemDubs: List<DubOption> = emptyList(),
        primaryAudioLang: String = "Original",
    ): ResolvedStream? {
        val mobileNow = isMobile()
        val key = "$sid|$se|$ep|${if (mobileNow) "m" else "w"}"
        val neededDubIds = if (adultAllowed) {
            emptySet()
        } else {
            itemDubs.asSequence().filter { it.subjectId != sid }.map { it.subjectId }.distinct().take(MAX_DUBS).toSet()
        }
        if (!fresh) {
            cache[key]?.takeIf {
                clock() - it.at < GatewayConfig.STREAM_TTL_MS && it.checkedDubIds.containsAll(neededDubIds)
            }?.let { return it.stream }
        }
        var checkedDubIds: Set<String> = neededDubIds
        val result = coroutineScope {
            val subs = async {
                try {
                    api.captions(sid, slug, se, ep).captions.mapNotNull { captionToSub(it) }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    emptyList()
                }
            }
            val candidateDubsDeferred = async {
                if (adultAllowed) {
                    emptyList()
                } else if (itemDubs.isNotEmpty()) {
                    itemDubs.filter { it.subjectId != sid }.distinctBy { it.subjectId }.take(MAX_DUBS)
                } else if (!slug.isNullOrBlank()) {
                    runCatching { api.detail(slug).data?.subject?.dubs.dubOptionList(excludeSubjectId = sid) }
                        .getOrDefault(emptyList())
                        .take(MAX_DUBS)
                } else {
                    emptyList()
                }
            }
            val mainStreamDeferred = async { fetchStreamWithRescue(sid, slug, se, ep, full = true) }
            // Dub streams start in parallel (in this scope, so they never hold each other up).
            val outer = this
            val dubJobsDeferred = async {
                candidateDubsDeferred.await().map { dub ->
                    dub to outer.async {
                        runCatching { fetchStreamWithRescue(dub.subjectId, dub.slug, se, ep, full = false) }.getOrNull()
                    }
                }
            }

            // The main stream alone decides readiness: dubs and captions get a short grace window
            // after it instead of the slowest dub gating Play. Late dubs are cancelled here and
            // fetched on demand by the Audio menu's sibling-dub path.
            val s = mainStreamDeferred.await()
            val dubJobs = withTimeoutOrNull(DUB_GRACE_MS) { dubJobsDeferred.await() }
            if (dubJobs == null) dubJobsDeferred.cancel()
            val jobs = dubJobs.orEmpty()
            if (jobs.isNotEmpty()) withTimeoutOrNull(DUB_GRACE_MS) { jobs.map { it.second }.awaitAll() }
            val (finished, late) = jobs.partition { (_, d) -> d.isCompleted && !d.isCancelled }
            late.forEach { (_, d) -> d.cancel() }
            checkedDubIds = if (dubJobs != null && late.isEmpty()) {
                neededDubIds + jobs.map { it.first.subjectId }
            } else {
                finished.map { it.first.subjectId }.toSet()
            }
            val dubStreams = finished.map { (dub, d) -> fullLanguageName(dub.languageName) to completedOrNull(d) }
            val unlockedRaw = unlockStreamSources(s)
            val fallback = s.directPlayUrl?.takeIf { it.isNotBlank() && extractYouTubeVideoId(it) == null }
                ?.let { Source(api.absolute(it), SourceKind.MP4, "Gateway proxy", audioLang = primaryAudioLang) }

            val dubRanked = dubStreams.flatMap { (dubLang, ds) ->
                if (ds == null) {
                    emptyList()
                } else {
                    rankGwSources(
                        raw = unlockStreamSources(ds),
                        baseUrl = api.base,
                        directPlayUrl = if (mobileNow) ds.directPlayUrl else null,
                        mobile = mobileNow,
                        cap = maxHeight(),
                        adultAllowed = false,
                        nowMs = clock(),
                        defaultAudioLang = dubLang,
                        forceAudioLabel = true,
                    )
                }
            }

            val mainSources = rankGwSources(
                raw = unlockedRaw,
                baseUrl = api.base,
                directPlayUrl = if (mobileNow) s.directPlayUrl else null,
                mobile = mobileNow,
                cap = maxHeight(),
                adultAllowed = adultAllowed,
                nowMs = clock(),
                defaultAudioLang = primaryAudioLang,
                forceAudioLabel = dubRanked.isNotEmpty(),
            )
            val mergedSources = (mainSources + dubRanked).distinctBy { it.url }
            val captions = withTimeoutOrNull(CAPTION_GRACE_MS) { subs.await() } ?: emptyList<Sub>().also { subs.cancel() }
            if (mergedSources.isEmpty() && fallback == null) null else ResolvedStream(mergedSources, captions, fallback)
        }
        if (result != null) {
            val now = clock()
            cache[key] = Entry(now, result, checkedDubIds)
            cache["$sid|$se|$ep"] = Entry(now, result, checkedDubIds)
            log("gateway stream $key: ${result.sources.size} sources")
        }
        return result
    }

    /**
     * Searches online WebVTT subtitles via `/api/public/v1/subtitles/search`, falling back to
     * top 3 `/search` matches -> `/captions` wrapped through `/api/public/v1/subtitle?url=...`.
     * All language names & labels are normalized via [fullLanguageName].
     */
    suspend fun searchOnlineSubtitles(
        query: String,
        itemId: String? = null,
        season: Int = 0,
        episode: Int = 0,
        lang: String? = null,
    ): List<KnownSubtitle> = coroutineScope {
        val cleanQuery = query.trim()
        val sid = itemId?.let { GatewayMapper.subjectIdOf(it) }
        val slug = itemId?.let { slugOf(it) } ?: sid
        if (cleanQuery.isEmpty() && sid == null) return@coroutineScope emptyList()

        val se = season.coerceAtLeast(0)
        val ep = episode.coerceAtLeast(0)
        val wantLang = lang?.trim()
            ?.takeIf { it.isNotEmpty() && !it.equals("all", ignoreCase = true) && !it.equals("any", ignoreCase = true) }
            ?.let(::fullLanguageName)
            ?.takeIf { it != "Original" }

        val primaryResults = try {
            val resp = api.subtitleSearch(q = cleanQuery, subjectId = sid, slug = slug, se = se, ep = ep, lang = lang)
            resp.subtitles.mapNotNull { sub ->
                val rawUrl = (sub.vttUrl ?: sub.url)?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val absUrl = api.subtitleProxyUrl(rawUrl).takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val fullLang = fullLanguageName(sub.language?.takeIf { it.isNotBlank() } ?: sub.label.orEmpty())
                val cleanLabel = normalizeOnlineSubLabel(sub.label, fullLang)
                KnownSubtitle(
                    url = absUrl,
                    label = cleanLabel,
                    language = fullLang,
                    id = sub.id?.takeIf { it.isNotBlank() } ?: absUrl,
                )
            }.distinctBy { it.url }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }

        val resolved = if (primaryResults.isNotEmpty()) {
            primaryResults
        } else {
            val candidates = LinkedHashMap<String, String?>()
            if (!sid.isNullOrBlank()) candidates[sid] = slug
            if (cleanQuery.isNotEmpty()) {
                val searchHits = runCatching { api.search(cleanQuery).items }.getOrDefault(emptyList())
                for (hit in searchHits) {
                    val hSid = hit.subjectId?.takeIf { it.isNotBlank() } ?: continue
                    if (candidates.size >= 4) break
                    candidates.putIfAbsent(hSid, hit.resolvedSlug ?: hSid)
                }
            }
            candidates.entries.take(3).map { (cSid, cSlug) ->
                async {
                    try {
                        var caps = api.captions(cSid, cSlug, se, ep).captions
                        if (caps.isEmpty() && se == 1 && ep == 1) {
                            caps = runCatching { api.captions(cSid, cSlug, 0, 0).captions }.getOrDefault(emptyList())
                        } else if (caps.isEmpty() && se == 0 && ep == 0) {
                            caps = runCatching { api.captions(cSid, cSlug, 1, 1).captions }.getOrDefault(emptyList())
                        }
                        caps.mapNotNull { c ->
                            if (c.url.isBlank()) return@mapNotNull null
                            val proxied = api.subtitleProxyUrl(c.url).takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                            val fullLang = fullLanguageName(c.lanName?.takeIf { it.isNotBlank() } ?: c.lan.orEmpty())
                            KnownSubtitle(
                                url = proxied,
                                label = fullLang,
                                language = fullLang,
                                id = proxied,
                            )
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        emptyList()
                    }
                }
            }.awaitAll().flatten().distinctBy { it.url }
        }

        if (wantLang == null) {
            resolved
        } else {
            resolved.sortedBy { if (it.language.equals(wantLang, ignoreCase = true)) 0 else 1 }
        }
    }

    companion object {
        private const val MAX_DUBS = 4
        private val NAMED_4K = Regex("""(?i)\b(4k|uhd|ultra\s*hd)\b""")
        private val NAMED_2K = Regex("""(?i)\b(2k|qhd)\b""")
        private val NAMED_FHD = Regex("""(?i)\b(fhd|full\s*hd)\b""")
        private val NAMED_HD = Regex("""(?i)\bhd\b""")

        /** Extra wait for dub streams once the main stream is in (keeps the green dot near 1 s). */
        internal const val DUB_GRACE_MS = 400L

        /** Extra wait for embedded captions once the main stream is in. */
        internal const val CAPTION_GRACE_MS = 1_000L

        private val PLAY_HEADERS = mapOf("Referer" to "https://movieboxhd.net/")

        private val IMAGE_PATH = Regex("""(?i)\.(jpe?g|png|webp|gif|bmp|svg)$|/null$""")

        private val ADULT_LINK = Regex(
            """(?i)(porn|xvideos|xnxx|xhamster|redtube|youporn|spankbang|eporner|onlyfans|brazzers|\bsex\b|xxx)""",
        )

        private val DUAL_AUDIO = Regex(
            """(?i)(dual[ ._-]?audio|multi[ ._-]?audio|\bdual\b|hin(di)?[ ._-]+eng(lish)?|eng(lish)?[ ._-]+hin(di)?|ben(gali)?[ ._-]+eng(lish)?)""",
        )

        private val BDIX_URL_HINT = Regex(
            """(?i)(^ftp://|\bbdix\b|\.bd/|://(10\.|192\.168\.|172\.(1[6-9]|2\d|3[0-1])\.|103\.))""",
        )

        private val YT_SCHEME = Regex("""(?i)^yt:(?://)?([A-Za-z0-9_-]{11})(?:[?&/#].*)?$""")
        private val YT_PATH = Regex(
            """(?i)(?:youtu\.be/|youtube(?:-nocookie)?\.com/(?:embed/|v/|shorts/|live/))([A-Za-z0-9_-]{11})""",
        )
        private val YT_WATCH_QUERY = Regex(
            """(?i)youtube(?:-nocookie)?\.com/watch\?(?:[^#]*&)?v=([A-Za-z0-9_-]{11})""",
        )
        private val BARE_YT_ID = Regex("""^[A-Za-z0-9_-]{11}$""")

        private val AUDIO_LANG_TOKENS = listOf(
            "Hindi" to Regex("""(?i)\bhindi\b"""),
            "Bengali" to Regex("""(?i)\b(bengali|bangla)\b"""),
            "Tamil" to Regex("""(?i)\btamil\b"""),
            "Telugu" to Regex("""(?i)\btelugu\b"""),
            "English" to Regex("""(?i)\benglish\b"""),
        )

        internal data class Candidate(
            val source: Source,
            val isBdix: Boolean,
            val isProxy: Boolean,
            val isYouTube: Boolean,
            val priority: Int,
        )

        fun extractYouTubeVideoId(rawUrl: String, format: String? = null, source: String? = null): String? {
            val u = rawUrl.trim()
            if (u.isEmpty()) return null
            YT_SCHEME.find(u)?.groupValues?.get(1)?.let { return it }
            YT_PATH.find(u)?.groupValues?.get(1)?.let { return it }
            YT_WATCH_QUERY.find(u)?.groupValues?.get(1)?.let { return it }
            val ytHint = format.equals("YOUTUBE", ignoreCase = true) ||
                format.equals("YT", ignoreCase = true) ||
                source.equals("YouTube", ignoreCase = true)
            if (ytHint && BARE_YT_ID.matches(u)) return u
            return null
        }

        fun toYouTubeEmbedUrl(videoId: String): String = "https://www.youtube-nocookie.com/embed/$videoId"

        fun isYouTubeEmbedUrl(url: String): Boolean = url.startsWith("https://www.youtube-nocookie.com/embed/")

        fun isImageLink(url: String): Boolean {
            val path = url.substringBefore('?').substringBefore('#')
            return IMAGE_PATH.containsMatchIn(path) || "image.tmdb.org" in path
        }

        fun isAdultLink(url: String): Boolean = ADULT_LINK.containsMatchIn(url.substringBefore('?'))

        internal fun safeDecodeUrl(raw: String): String =
            runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)

        fun isDualAudio(label: String?, url: String): Boolean =
            DUAL_AUDIO.containsMatchIn((label.orEmpty()) + " " + safeDecodeUrl(url.substringBefore('?')))

        /**
         * Vertical resolution from a provider label: `1080p` / `2160` digits first, then named tiers
         * (`4K`, `UHD` → 2160, `2K`, `QHD` → 1440, `FHD` → 1080, `HD` → 720). Without the named tiers
         * a `4K` source had no height and vanished from the player's Quality menu.
         */
        fun heightOf(resolution: String?): Int? {
            val r = resolution?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            Regex("(\\d{3,4})").find(r)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it >= 144 }?.let { return it }
            return when {
                NAMED_4K.containsMatchIn(r) -> 2160
                NAMED_2K.containsMatchIn(r) -> 1440
                NAMED_FHD.containsMatchIn(r) -> 1080
                NAMED_HD.containsMatchIn(r) -> 720
                else -> null
            }
        }

        fun kindOf(format: String?, url: String): SourceKind = when (format?.uppercase(Locale.US)) {
            "DASH", "MPD" -> SourceKind.DASH
            "HLS", "M3U8" -> SourceKind.HLS
            "MP4", "YOUTUBE", "YT" -> SourceKind.MP4
            "MKV" -> SourceKind.MKV
            "WEBM" -> SourceKind.WEBM
            else -> when (SourceResolver.streamTypeOfUrl(url)) {
                StreamType.HLS -> SourceKind.HLS
                StreamType.DASH -> SourceKind.DASH
                else -> SourceKind.MP4
            }
        }

        internal fun isHevc(codec: String?): Boolean =
            codec?.lowercase(Locale.US)?.let { it.startsWith("hevc") || it.contains("265") } ?: false

        fun formatSize(bytes: Long?): String {
            val b = bytes ?: return ""
            if (b <= 0L) return ""
            return if (b >= 1L shl 30) {
                String.format(Locale.US, "%.1f GB", b / (1024.0 * 1024.0 * 1024.0))
            } else {
                String.format(Locale.US, "%d MB", (b / (1024L * 1024L)).coerceAtLeast(1L))
            }
        }

        fun qualityLabel(height: Int?, rawResolution: String? = null): String = when {
            height != null && height >= 2160 -> "4K UHD"
            height != null && height >= 1440 -> "2K QHD"
            height != null && height >= 1080 -> "1080p FHD"
            height != null && height >= 720 -> "720p HD"
            height != null && height > 0 -> "${height}p"
            else -> {
                val cleaned = rawResolution?.trim().orEmpty()
                if (
                    cleaned.isEmpty() ||
                    cleaned.contains("moviebox", true) ||
                    cleaned.contains("youtube", true) ||
                    cleaned.contains("bdix", true) ||
                    cleaned.contains("server", true) ||
                    cleaned.contains("zee bangla", true)
                ) {
                    "Auto"
                } else {
                    cleaned
                }
            }
        }

        private fun detectAudioLabel(g: GwSource): String? {
            if (isDualAudio(listOfNotNull(g.language, g.audio).joinToString(" "), g.url)) {
                return "Dual Audio"
            }
            val explicit = listOfNotNull(g.language, g.audio).joinToString(" ").trim()
            if (explicit.isEmpty()) return null
            for ((canonical, regex) in AUDIO_LANG_TOKENS) {
                if (regex.containsMatchIn(explicit)) return fullLanguageName(canonical)
            }
            val normalized = fullLanguageName(explicit)
            return normalized.takeIf { it != "Original" }
        }

        private fun normalizeOnlineSubLabel(rawLabel: String?, fullLang: String): String {
            val trimmed = rawLabel?.trim().orEmpty()
            if (trimmed.isEmpty()) return fullLang
            val parts = trimmed.split("·", " - ").map { it.trim() }.filter { it.isNotEmpty() }
            if (parts.size <= 1) return fullLang
            val extra = parts.drop(1).filter { p ->
                p.length > 3 && !fullLanguageName(p).equals(fullLang, ignoreCase = true)
            }
            return if (extra.isEmpty()) fullLang else (listOf(fullLang) + extra).joinToString(" · ")
        }

        /** Unlocks 1080p DASH/HLS manifests and YouTube trailers from [GwStream] when absent from `sources`. */
        fun unlockStreamSources(s: GwStream): List<GwSource> {
            val out = ArrayList<GwSource>(s.sources.size + s.dash.size + s.hls.size + 1)
            out.addAll(s.sources)

            fun addManifests(list: List<GwManifest>, defaultFormat: String) {
                for (m in list) {
                    if (m.vipLocked || m.url.isBlank()) continue
                    val maxRes = m.resolutions.orEmpty()
                        .split(',')
                        .mapNotNull { it.trim().filter(Char::isDigit).toIntOrNull() }
                        .maxOrNull()
                    val kind = kindOf(m.format ?: defaultFormat, m.url)
                    val alreadyHas = out.any { existing ->
                        !existing.vipLocked &&
                            existing.url.isNotBlank() &&
                            (existing.url == m.url || (kindOf(existing.format, existing.url) == kind && heightOf(existing.resolution) == maxRes))
                    }
                    if (alreadyHas) continue
                    val hdrs = LinkedHashMap<String, String>()
                    hdrs["Referer"] = "https://movieboxhd.net/"
                    hdrs["Origin"] = "https://movieboxhd.net"
                    val cookie = m.signCookie?.takeIf { it.isNotBlank() }
                    if (cookie != null) {
                        hdrs["Cookie"] = cookie
                        val headerKey = m.signHeaderKey?.takeIf { it.isNotBlank() } ?: "X-MB-Token"
                        hdrs[headerKey] = cookie
                    }
                    out += GwSource(
                        source = "Gateway",
                        isBdix = false,
                        resolution = maxRes?.let { "${it}p" },
                        format = defaultFormat,
                        url = m.url,
                        size = m.size?.toLongOrNull(),
                        duration = m.duration,
                        codec = m.codecName,
                        vipLocked = false,
                        headers = hdrs,
                    )
                }
            }

            addManifests(s.dash, "DASH")
            addManifests(s.hls, "HLS")

            s.trailerUrl?.takeIf { it.isNotBlank() }?.let { trailer ->
                val ytId = extractYouTubeVideoId(trailer, "YOUTUBE", "YouTube")
                if (ytId != null && out.none { extractYouTubeVideoId(it.url, it.format, it.source) == ytId }) {
                    out += GwSource(
                        source = "YouTube",
                        resolution = "720p",
                        format = "YOUTUBE",
                        url = toYouTubeEmbedUrl(ytId),
                    )
                }
            }

            return out
        }

        fun toSource(
            g: GwSource,
            defaultAudioLang: String = "Original",
            forceAudioLabel: Boolean = false,
        ): Source? {
            if (g.url.isBlank() || g.vipLocked) return null
            val cleanDefaultAudio = fullLanguageName(defaultAudioLang)
            val explicitTag = detectAudioLabel(g)
            val resolvedAudioLang = if (explicitTag == "Dual Audio") cleanDefaultAudio else (explicitTag ?: cleanDefaultAudio)
            val labelAudioTag = explicitTag ?: if (forceAudioLabel) cleanDefaultAudio else null
            val ytId = extractYouTubeVideoId(g.url, g.format, g.source)
            if (ytId != null) {
                val h = heightOf(g.resolution) ?: 720
                val label = listOfNotNull(qualityLabel(h, g.resolution), labelAudioTag).joinToString(" · ")
                return Source(
                    url = toYouTubeEmbedUrl(ytId),
                    kind = SourceKind.MP4,
                    label = label,
                    headers = emptyMap(),
                    height = h,
                    audioLang = resolvedAudioLang,
                )
            }
            if (isImageLink(g.url)) return null
            val h = heightOf(g.resolution)
            val kind = kindOf(g.format, g.url)
            val size = g.size?.takeIf { it > 0 }
            val label = listOfNotNull(
                qualityLabel(h, g.resolution),
                labelAudioTag,
                kind.name,
                if (isHevc(g.codec)) "HEVC" else null,
                formatSize(size).takeIf { it.isNotEmpty() },
            ).joinToString(" · ")
            val isBdix = g.isBdix || g.source.equals("bdix", ignoreCase = true) || BDIX_URL_HINT.containsMatchIn(g.url)
            val headers = when {
                g.headers.isNotEmpty() -> g.headers
                !isBdix && g.source.equals("MovieBox", ignoreCase = true) -> PLAY_HEADERS
                else -> emptyMap()
            }
            return Source(
                url = g.url,
                kind = kind,
                label = label,
                headers = headers,
                height = h,
                sizeBytes = size,
                codec = g.codec,
                audioLang = resolvedAudioLang,
            )
        }

        private fun absoluteUrl(baseUrl: String, pathOrUrl: String): String {
            val base = GatewayConfig.normalize(baseUrl)
            return if (pathOrUrl.startsWith("http://") || pathOrUrl.startsWith("https://")) {
                pathOrUrl
            } else {
                "$base/${pathOrUrl.trimStart('/')}"
            }
        }

        /**
         * Expands raw [GwSource] entries into direct + progressive gateway proxy candidates and ranks
         * them for the current network ([mobile] = false for unmetered Wi-Fi, true for metered mobile data).
         */
        fun rankGwSources(
            raw: List<GwSource>,
            baseUrl: String = GatewayConfig.BASE_URL,
            directPlayUrl: String? = null,
            mobile: Boolean = NetworkState.mobile,
            cap: Int? = null,
            adultAllowed: Boolean = false,
            nowMs: Long = System.currentTimeMillis(),
            defaultAudioLang: String = "Original",
            forceAudioLabel: Boolean = false,
        ): List<Source> {
            val cleanDefaultAudio = fullLanguageName(defaultAudioLang)
            val candidates = ArrayList<Candidate>(raw.size * 2 + 1)
            for (g in raw) {
                val primary = toSource(g, defaultAudioLang = cleanDefaultAudio, forceAudioLabel = forceAudioLabel) ?: continue
                if (!adultAllowed && isAdultLink(primary.url)) continue
                val yt = isYouTubeEmbedUrl(primary.url)
                val bdix = !yt && (g.isBdix || g.source.equals("bdix", ignoreCase = true) || BDIX_URL_HINT.containsMatchIn(primary.url))
                val prio = when {
                    bdix -> 0
                    yt -> 2
                    else -> 1
                }
                candidates += Candidate(
                    source = primary,
                    isBdix = bdix,
                    isProxy = false,
                    isYouTube = yt,
                    priority = prio,
                )

                // Progressive MP4/MKV proxy failover: never emit proxy_url for DASH/HLS manifests or YouTube embeds.
                val rawProxy = g.proxyUrl?.takeIf { it.isNotBlank() }
                val progressive = primary.kind == SourceKind.MP4 || primary.kind == SourceKind.MKV || primary.kind == SourceKind.WEBM
                if (!yt && !bdix && progressive && rawProxy != null) {
                    val proxyAbs = absoluteUrl(baseUrl, rawProxy)
                    if (
                        proxyAbs.startsWith("http") &&
                        !proxyAbs.contains("youtube", ignoreCase = true) &&
                        !proxyAbs.contains("embed", ignoreCase = true) &&
                        !proxyAbs.substringBefore('?').endsWith(".mpd", ignoreCase = true) &&
                        !proxyAbs.substringBefore('?').endsWith(".m3u8", ignoreCase = true)
                    ) {
                        val proxySource = primary.copy(
                            url = proxyAbs,
                            kind = SourceKind.MP4,
                            label = listOfNotNull(primary.label?.takeIf { it.isNotBlank() }, "Proxy").joinToString(" · "),
                            headers = emptyMap(),
                        )
                        candidates += Candidate(
                            source = proxySource,
                            isBdix = false,
                            isProxy = true,
                            isYouTube = false,
                            priority = 1,
                        )
                    }
                }
            }

            // On metered mobile data, if no per-source proxy_url existed, surface directPlayUrl as top gateway proxy.
            if (mobile && !directPlayUrl.isNullOrBlank() && candidates.none { it.isProxy }) {
                val proxyAbs = absoluteUrl(baseUrl, directPlayUrl)
                if (extractYouTubeVideoId(proxyAbs) == null && (!isAdultLink(proxyAbs) || adultAllowed)) {
                    val proxyLabel = listOfNotNull(
                        "720p HD",
                        if (forceAudioLabel) cleanDefaultAudio else null,
                        "Proxy",
                    ).joinToString(" · ")
                    candidates += Candidate(
                        source = Source(
                            url = proxyAbs,
                            kind = SourceKind.MP4,
                            label = proxyLabel,
                            headers = emptyMap(),
                            height = 720,
                            audioLang = cleanDefaultAudio,
                        ),
                        isBdix = false,
                        isProxy = true,
                        isYouTube = false,
                        priority = 1,
                    )
                }
            }

            return rankCandidates(candidates, mobile = mobile, cap = cap, nowMs = nowMs)
        }

        private fun isAdaptive(s: Source): Boolean =
            s.kind == SourceKind.HLS || s.kind == SourceKind.DASH

        /** Wi-Fi quality order when uncapped: high-definition 1080p/1440p/4K before SD. */
        private fun wifiQualityRank(height: Int?): Int {
            val h = height ?: 0
            return when {
                h >= 1000 -> 0
                h in 700..999 -> 1
                h in 450..699 -> 2
                h in 1..449 -> 3
                else -> 4
            }
        }

        /** Mobile data fit when uncapped: 720p first, then 480p, 1080p, 360p — sharp yet light on cellular. */
        private fun mobileFit(height: Int?): Int {
            val h = height ?: 0
            return when {
                h in 700..800 -> 0
                h in 450..699 -> 1
                h in 1000..1150 -> 2
                h in 300..449 -> 3
                h == 0 -> 4
                else -> 5
            }
        }

        private fun rankCandidates(
            all: List<Candidate>,
            mobile: Boolean,
            cap: Int?,
            nowMs: Long,
        ): List<Source> {
            val unique = all.distinctBy { it.source.url }.filterNot { isImageLink(it.source.url) }
            if (unique.isEmpty()) return emptyList()
            val usable = unique.filterNot { LinkHealth.refused(it.source.url, nowMs) }.ifEmpty { unique }

            val baseSorted = if (!mobile) {
                // Unmetered Wi-Fi: BDIX direct first, then direct high-speed CDN, then YouTube, then proxy fallbacks.
                usable.sortedWith(
                    compareBy<Candidate> {
                        when {
                            it.isBdix -> 0
                            !it.isProxy && !it.isYouTube -> 1
                            it.isYouTube -> 2
                            else -> 3
                        }
                    }
                        .thenBy { it.priority }
                        .thenBy { wifiQualityRank(it.source.height) }
                        .thenByDescending { it.source.height ?: 0 }
                        .thenBy { if (isHevc(it.source.codec)) 1 else 0 }
                        .thenBy { if (it.source.kind == SourceKind.MP4) 0 else 1 }
                        .thenByDescending { it.source.sizeBytes ?: 0L },
                )
            } else {
                // Metered Mobile Data: reliable gateway proxy & adaptive web streams first; BDIX last.
                usable.sortedWith(
                    compareBy<Candidate> {
                        when {
                            it.isBdix -> 3
                            it.isProxy || isAdaptive(it.source) -> 0
                            !it.isYouTube -> 1
                            else -> 2
                        }
                    }
                        .thenBy { it.priority }
                        .thenBy { mobileFit(it.source.height) }
                        .thenBy { it.source.sizeBytes ?: Long.MAX_VALUE }
                        .thenBy { if (isHevc(it.source.codec)) 1 else 0 },
                )
            }

            // Undecodable HEVC goes last; when preferDualAudio is active, dual-audio sources float up within decodable ones.
            val healthAdjusted = baseSorted.sortedBy { c ->
                val undec = if (LinkHealth.undecodable(c.source)) 2 else 0
                val dual = if (LinkHealth.preferDualAudio && !isDualAudio(c.source.label, c.source.url)) 1 else 0
                undec + dual
            }

            val sources = healthAdjusted.map { it.source }
            if (cap == null) return sources
            val (fit, above) = sources.partition { (it.height ?: 0) <= cap }
            return fit + above.sortedBy { it.height ?: Int.MAX_VALUE }
        }

        /**
         * Pure ranking over already-mapped [Source] items (backward-compatible with callers & tests).
         * Highest resolution first on Wi-Fi (H.264 MP4 ahead of HEVC / DASH at equal height);
         * with a [cap], sources at or below it lead (best first) and higher resolutions follow.
         */
        fun sortSources(
            sources: List<Source>,
            cap: Int? = null,
            mobile: Boolean = false,
            nowMs: Long = System.currentTimeMillis(),
        ): List<Source> {
            val candidates = sources.map { s ->
                val yt = isYouTubeEmbedUrl(s.url)
                val proxy = !yt && (s.url.contains("/api/public/v1/stream/") || s.label?.contains("Proxy", ignoreCase = true) == true)
                val bdix = !yt && !proxy && BDIX_URL_HINT.containsMatchIn(s.url)
                Candidate(
                    source = s,
                    isBdix = bdix,
                    isProxy = proxy,
                    isYouTube = yt,
                    priority = if (bdix) 0 else if (yt) 2 else 1,
                )
            }
            return rankCandidates(candidates, mobile = mobile, cap = cap, nowMs = nowMs)
        }

        fun captionToSub(c: GwCaption): Sub? {
            if (c.url.isBlank()) return null
            val path = c.url.substringBefore('?').lowercase(Locale.US)
            val format = when {
                path.endsWith(".vtt") -> SubFormat.VTT
                path.endsWith(".ass") || path.endsWith(".ssa") -> SubFormat.ASS
                else -> SubFormat.SRT
            }
            val lang = c.lan?.takeIf { it.isNotBlank() } ?: "und"
            val humanLabel = fullLanguageName(c.lanName?.takeIf { it.isNotBlank() } ?: lang)
            return Sub(lang, humanLabel, c.url, format)
        }

        private fun normalize(s: String): String = s.lowercase(Locale.US).replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
private fun <T> completedOrNull(d: Deferred<T>): T? = runCatching { d.getCompleted() }.getOrNull()
