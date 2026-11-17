package com.mythbrix.cinetor.core.download

import com.mythbrix.cinetor.core.data.Episode
import com.mythbrix.cinetor.core.data.Item
import com.mythbrix.cinetor.core.data.ItemType
import com.mythbrix.cinetor.core.data.Season
import com.mythbrix.cinetor.core.data.Source
import com.mythbrix.cinetor.core.data.SourceKind
import com.mythbrix.cinetor.core.data.Space

/** Pure (Media3-free) answer to "where does this title play from?". */
sealed interface OfflinePlan {
    /** Progressive download: play the file directly (no network). */
    data class LocalFile(val path: String, val mimeType: String?, val sidecars: List<SidecarSub> = emptyList()) : OfflinePlan

    /** HLS/DASH download: segments live in the Media3 download cache. */
    data class Cached(val downloadId: String, val kind: DownloadKind) : OfflinePlan
}

/** Subtitle file saved next to a progressive download (`<name>.<lang>.<ext>`). */
data class SidecarSub(val lang: String, val path: String, val ext: String)

object OfflineResolver {
    /**
     * Completed download for [itemId]/[episodeId] → local source, else null (stream online).
     * Progressive files must still exist on disk ([fileExists]); a deleted file falls back to online.
     */
    fun resolve(
        completed: Map<String, DownloadRecord>,
        itemId: String,
        episodeId: String?,
        fileExists: (String) -> Boolean,
    ): OfflinePlan? {
        val r = completed[downloadIdFor(itemId, episodeId)] ?: return null
        if (r.status != DownloadStatus.COMPLETED) return null
        return when (r.kind) {
            DownloadKind.PROGRESSIVE -> r.filePath.takeIf { it.isNotBlank() && fileExists(it) }
                ?.let { path -> OfflinePlan.LocalFile(path, r.mimeType, sidecars(r).filter { fileExists(it.path) }) }
            DownloadKind.HLS, DownloadKind.DASH -> OfflinePlan.Cached(r.id, r.kind)
        }
    }

    /** Expected sidecar subtitle paths (mirrors ProgressiveRunner.downloadSidecars naming). */
    fun sidecars(r: DownloadRecord): List<SidecarSub> {
        if (r.filePath.isBlank()) return emptyList()
        val slash = r.filePath.lastIndexOf('/')
        val dir = if (slash >= 0) r.filePath.substring(0, slash) else ""
        val name = r.filePath.substring(slash + 1)
        val base = if (name.contains('.')) name.substringBeforeLast('.') else name
        return r.sidecarSubs.lines().filter { it.contains('|') }.mapNotNull { line ->
            val lang = line.substringBefore('|')
            val url = line.substringAfter('|')
            if (!url.startsWith("http")) return@mapNotNull null
            val ext = url.substringAfterLast('.', "vtt").take(4)
            SidecarSub(lang, (if (dir.isEmpty()) "" else "$dir/") + "$base.$lang.$ext", ext)
        }
    }

    /** Subtitle URLs that work in airplane mode (bundled assets / local files). */
    fun isOfflineSubUrl(url: String): Boolean = url.startsWith("asset:") || url.startsWith("file:") || url.startsWith("/")

    /**
     * Minimal playable [Item] rebuilt from the record, so a download plays even when the catalog
     * (TMDB) is unreachable. Episode downloads become a one-episode series.
     */
    fun playableItem(r: DownloadRecord): Item {
        val source = Source(url = r.url, kind = sourceKind(r), headers = r.headerMap)
        val space = runCatching { Space.valueOf(r.space) }.getOrDefault(Space.HOME)
        val dur = r.durationSec ?: 0
        val cleanTitle = r.title.replace(Regex("""\s*·\s*S\d+:?E\d+$""", RegexOption.IGNORE_CASE), "").ifBlank { r.title }
        val base = Item(
            id = r.itemId, space = space, categoryId = "downloads",
            type = if (r.episodeId != null) ItemType.SERIES else ItemType.MOVIE,
            title = cleanTitle, year = 0, rating = 0.0, maturity = "", genres = emptyList(), synopsis = "",
            poster = r.poster, backdrop = r.poster, dominantColor = "#000000", accentColor = "#E50914",
        )
        val epId = r.episodeId ?: return base.copy(durationSec = r.durationSec, sources = listOf(source))
        val epCodeRegex = Regex("""S0*(\d+):?E0*(\d+)""", RegexOption.IGNORE_CASE)
        val m = epCodeRegex.find(r.subtitle.orEmpty()) ?: epCodeRegex.find(r.title)
        val season = m?.groupValues?.get(1)?.toIntOrNull() ?: 1
        val n = m?.groupValues?.get(2)?.toIntOrNull() ?: 1
        val epTitle = r.subtitle?.substringAfter("· ", "")?.takeIf { it.isNotBlank() } ?: "Episode $n"
        val ep = Episode(id = epId, n = n, title = epTitle, durationSec = dur, still = r.poster, sources = listOf(source))
        return base.copy(seasons = listOf(Season(n = season, episodes = listOf(ep))))
    }

    fun sourceKind(r: DownloadRecord): SourceKind = when (r.kind) {
        DownloadKind.HLS -> SourceKind.HLS
        DownloadKind.DASH -> SourceKind.DASH
        DownloadKind.PROGRESSIVE -> when (r.container.lowercase()) {
            "mkv" -> SourceKind.MKV
            "webm" -> SourceKind.WEBM
            "mp3" -> SourceKind.MP3
            else -> SourceKind.MP4
        }
    }
}
