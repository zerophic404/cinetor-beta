package com.mythbrix.cinetor.core.download

import com.mythbrix.cinetor.core.data.Source
import com.mythbrix.cinetor.core.data.SourceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** docs/requirements-hardening.md §4 — download options / location / delete. */
class DownloadsUxTest {

    private fun src(kind: SourceKind, label: String? = null, h: Int? = null, size: Long? = null, url: String = "https://cdn/$label") =
        Source(url = url, kind = kind, label = label, height = h, sizeBytes = size)

    private fun opt(h: Int?, kind: SourceKind, bytes: Long, url: String = "https://cdn/$h-$kind") = QualityOption(
        source = src(kind, "${h}p", h, bytes, url),
        kind = if (kind == SourceKind.MP4 || kind == SourceKind.MKV) DownloadKind.PROGRESSIVE else DownloadKind.SEGMENTED,
        label = "${h}p · $kind",
        height = h,
        estimatedBytes = bytes,
    )

    @Test
    fun options_identicalResolutionFormatSizeShownOnce() {
        val dup = listOf(
            opt(1080, SourceKind.MP4, 1_000),
            opt(1080, SourceKind.MP4, 1_000, url = "https://mirror/1080"), // same res+format+size → one entry
            opt(1080, SourceKind.DASH, 1_000),
            opt(720, SourceKind.MP4, 500),
            opt(720, SourceKind.MP4, 500),
            opt(480, SourceKind.MP4, 200),
        )
        val unique = dedupeQualityOptions(dup)
        assertEquals(listOf("1080p · DASH", "1080p · MP4", "720p · MP4", "480p · MP4"), unique.map { it.label })
        // The primary (first) source of each group survives, not the mirror.
        assertEquals("https://cdn/1080-MP4", unique.first { it.label == "1080p · MP4" }.source.url)
        // Idempotent.
        assertEquals(unique, dedupeQualityOptions(unique))
    }

    @Test
    fun options_probedSizeWinsOverUnknown() {
        val mixed = listOf(opt(1080, SourceKind.MP4, -1), opt(1080, SourceKind.MP4, 999, url = "https://cdn/probed"))
        val unique = dedupeQualityOptions(mixed)
        assertEquals(1, unique.size)
        assertEquals(999L, unique.single().estimatedBytes)
        // Highest resolution first so the sheet reads top-down.
        val sorted = dedupeQualityOptions(listOf(opt(480, SourceKind.MP4, 1), opt(1080, SourceKind.MP4, 2), opt(720, SourceKind.MP4, 3)))
        assertEquals(listOf(1080, 720, 480), sorted.map { it.height })
    }

    @Test
    fun location_labelAndFolderName() {
        val none = DownloadLocation()
        assertFalse(none.chosen)
        assertEquals(DownloadLocations.APP_STORAGE_LABEL, DownloadLocations.label(none, adult = false))
        // Adult downloads never leave the private folder, whatever the user picked.
        val picked = DownloadLocation("content://com.android.externalstorage.documents/tree/primary%3AMovies%2FCinetor", "Movies/Cinetor")
        assertTrue(picked.chosen)
        assertEquals(DownloadLocations.ADULT_LABEL, DownloadLocations.label(picked, adult = true))
        assertEquals("Movies/Cinetor", DownloadLocations.label(picked, adult = false))
        assertEquals("Movies/Cinetor", DownloadLocations.folderName(picked.treeUri))
        assertEquals("Download", DownloadLocations.folderName("content://x/tree/primary%3ADownload"))
        assertEquals("Chosen folder", DownloadLocations.folderName("content://x/tree/primary%3A"))
        // A chosen folder with an empty label still reads sensibly.
        assertEquals("Chosen folder", DownloadLocations.label(DownloadLocation("content://x/tree/abc", ""), adult = false))
    }

    @Test
    fun export_onlyFinishedPublicProgressiveFiles() {
        val loc = DownloadLocation("content://x/tree/primary%3AMovies", "Movies")
        val base = DownloadRecord(
            id = "d1", itemId = "gw:1", title = "Movie", kind = DownloadKind.PROGRESSIVE,
            url = "u", filePath = "/data/x.mp4", status = DownloadStatus.COMPLETED, container = "mp4",
        )
        assertTrue(DownloadLocations.needsExport(base, loc))
        // No folder chosen → nothing is copied anywhere.
        assertFalse(DownloadLocations.needsExport(base, DownloadLocation()))
        assertFalse(DownloadLocations.needsExport(base.copy(status = DownloadStatus.DOWNLOADING), loc))
        assertFalse(DownloadLocations.needsExport(base.copy(adult = true), loc)) // 18+ stays private
        assertFalse(DownloadLocations.needsExport(base.copy(kind = DownloadKind.SEGMENTED), loc))
        assertFalse(DownloadLocations.needsExport(base.copy(exportUri = "content://x/doc/1"), loc)) // already done
        assertFalse(DownloadLocations.needsExport(base.copy(filePath = ""), loc))
    }

    @Test
    fun export_fileNameIsSafeAndKeepsContainer() {
        val r = DownloadRecord(
            id = "gw:1:s1e2", itemId = "gw:1", title = "Movie: The / Sequel?", subtitle = "S1:E2 · Pilot",
            kind = DownloadKind.PROGRESSIVE, url = "u", container = "mkv", qualityLabel = "1080p",
        )
        val name = DownloadLocations.fileName(r)
        assertTrue(name, name.endsWith(".mkv"))
        listOf('/', '\\', ':', '?', '*').forEach { assertFalse(name, name.contains(it)) }
        assertTrue(name, name.contains("1080p"))
        // Degenerate metadata still yields a usable name.
        assertEquals("d.mp4", DownloadLocations.fileName(DownloadRecord(id = "d", itemId = "i", title = "", kind = DownloadKind.PROGRESSIVE, url = "u", qualityLabel = "")))
    }

    @Test
    fun delete_offersCancelRemoveEntryAndDeleteFile() {
        assertEquals(
            listOf(DeleteChoice.CANCEL, DeleteChoice.REMOVE_ENTRY, DeleteChoice.DELETE_FILE),
            DeleteChoice.entries.toList(),
        )
    }
}
