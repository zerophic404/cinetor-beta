package com.mythbrix.cinetor.feature.player.subs

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val MAX_BYTES = 4 * 1024 * 1024

/** Reads a subtitle file: bundled asset (asset:///), SAF/content Uri, or http(s). Capped at 4 MB. */
suspend fun loadSubtitleText(context: Context, uri: String): String = withContext(Dispatchers.IO) {
    val stream = when {
        uri.startsWith("asset:///") -> context.assets.open(uri.removePrefix("asset:///"))
        uri.startsWith("http://") || uri.startsWith("https://") -> java.net.URL(uri).openStream()
        else -> context.contentResolver.openInputStream(Uri.parse(uri)) ?: error("cannot open $uri")
    }
    stream.use { s ->
        val bytes = s.readBytes()
        require(bytes.size <= MAX_BYTES) { "subtitle file too large" }
        String(bytes, Charsets.UTF_8)
    }
}

/** SAF display name ("movie.en.srt"), falling back to the last path segment. */
fun displayNameOf(context: Context, uri: Uri): String {
    runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val name = c.getString(0)
                if (!name.isNullOrBlank()) return name
            }
        }
    }
    return uri.lastPathSegment?.substringAfterLast('/') ?: "subtitles.srt"
}
