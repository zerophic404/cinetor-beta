package com.mythbrix.cinetor.core.download

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * User-chosen download folder (docs/requirements-hardening.md §4). Picked with
 * `ACTION_OPEN_DOCUMENT_TREE`, remembered as the default, changeable in Settings → Downloads.
 *
 * Transfers always land in app-private storage first (the chunked downloader needs random access for
 * resume, and resume metadata must stay app-private), then the finished file is copied into the chosen
 * tree. "Delete file" removes both copies.
 */
data class DownloadLocation(val treeUri: String = "", val label: String = "") {
    val chosen: Boolean get() = treeUri.isNotBlank()
}

/** Pure helpers (unit-testable, no Android). */
object DownloadLocations {
    const val APP_STORAGE_LABEL = "Internal storage (app)"
    const val ADULT_LABEL = "Internal · private folder (hidden)"

    /** Human label for the Location row. */
    fun label(location: DownloadLocation, adult: Boolean): String = when {
        adult -> ADULT_LABEL
        location.chosen -> location.label.ifBlank { "Chosen folder" }
        else -> APP_STORAGE_LABEL
    }

    /**
     * Last path segment of a SAF tree URI as a readable folder name,
     * e.g. `content://…/tree/primary%3AMovies%2FCinetor` → `Movies/Cinetor`.
     */
    fun folderName(treeUri: String): String {
        val decoded = treeUri.replace("%3A", ":").replace("%3a", ":").replace("%2F", "/").replace("%2f", "/")
        val tail = decoded.substringAfterLast("/tree/", decoded).substringAfterLast("/document/")
        val afterColon = tail.substringAfter(':', tail)
        return afterColon.trim('/').ifBlank { "Chosen folder" }
    }

    /** Safe display file name for the exported copy. */
    fun fileName(record: DownloadRecord): String {
        val ext = record.container.ifBlank { "mp4" }
        val base = listOfNotNull(record.title, record.subtitle, record.qualityLabel.takeIf { it.isNotBlank() })
            .joinToString(" - ")
            .replace(Regex("[^A-Za-z0-9 ._-]"), "_")
            .trim()
            .take(96)
            .ifBlank { record.id }
        return "$base.$ext"
    }

    /** Which downloads can be exported: a finished progressive file that is not exported yet. */
    fun needsExport(record: DownloadRecord, location: DownloadLocation): Boolean =
        location.chosen &&
            record.status == DownloadStatus.COMPLETED &&
            record.kind == DownloadKind.PROGRESSIVE &&
            !record.adult &&
            record.exportUri.isBlank() &&
            record.filePath.isNotBlank()
}

/** What the delete dialog offers (docs/requirements-hardening.md §4). */
enum class DeleteChoice { CANCEL, REMOVE_ENTRY, DELETE_FILE }

/** SAF side effects, isolated so the rest of the stack stays testable. */
class DownloadExporter(context: Context) {
    private val app = context.applicationContext

    /** Persists read/write access across restarts. */
    fun remember(treeUri: Uri) = runCatching {
        app.contentResolver.takePersistableUriPermission(
            treeUri,
            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
    }.isSuccess

    /** Copies [file] into the chosen tree; returns the new document URI or null. */
    suspend fun export(treeUri: String, file: File, displayName: String, mime: String?): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                val tree = Uri.parse(treeUri)
                val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
                val doc = DocumentsContract.createDocument(app.contentResolver, parent, mime ?: "video/mp4", displayName)
                    ?: return@runCatching null
                app.contentResolver.openOutputStream(doc)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                doc.toString()
            }.getOrNull()
        }

    /** Deletes the exported document (if any) and the app-private file. Returns true when something went. */
    suspend fun deleteFiles(record: DownloadRecord): Boolean = withContext(Dispatchers.IO) {
        var gone = false
        if (record.exportUri.isNotBlank()) {
            gone = runCatching { DocumentsContract.deleteDocument(app.contentResolver, Uri.parse(record.exportUri)) }.getOrDefault(false)
        }
        if (record.filePath.isNotBlank()) {
            val f = File(record.filePath)
            if (f.exists()) gone = (if (f.isDirectory) f.deleteRecursively() else f.delete()) || gone
        }
        gone
    }
}
