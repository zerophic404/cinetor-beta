package com.mythbrix.cinetor.core.download

/**
 * Where exported files land inside the user's chosen folder:
 * comics → `Comics/<Title>/<Chapter>.pdf`, music → `Music/<Track>.<ext>`, videos stay at the top level.
 */
object ExportPaths {
    const val COMICS = "Comics"
    const val MUSIC = "Music"

    /** One safe path segment: letters, digits, space, dot, dash, underscore; never empty, ≤ 80 chars. */
    fun segment(raw: String, fallback: String): String =
        raw.replace(Regex("[^\\p{L}\\p{N} ._-]"), "_")
            .replace(Regex("\\s+"), " ")
            .trim(' ', '.', '_')
            .take(80)
            .ifBlank { fallback }

    /** `Comics/<Title>` for a comic's chapters. */
    fun comicFolders(comicTitle: String): List<String> = listOf(COMICS, segment(comicTitle, "Comic"))

    /** `Chapter 12 - Title.pdf`; the number keeps chapters in reading order when sorted by name. */
    fun comicPdfName(chapterNumber: Int, chapterTitle: String): String {
        val n = "Chapter %03d".format(chapterNumber.coerceAtLeast(0))
        val t = segment(chapterTitle, "")
        val base = if (t.isBlank() || t.equals("Chapter $chapterNumber", ignoreCase = true)) n else "$n - $t"
        return "$base.pdf"
    }

    /** Subfolders for a finished download record: audio goes under `Music`. */
    fun foldersFor(mimeType: String?, container: String): List<String> {
        val audio = mimeType?.startsWith("audio/", ignoreCase = true) == true ||
            container.lowercase() in setOf("mp3", "flac", "m4a", "aac", "ogg", "opus", "wav")
        return if (audio) listOf(MUSIC) else emptyList()
    }
}
