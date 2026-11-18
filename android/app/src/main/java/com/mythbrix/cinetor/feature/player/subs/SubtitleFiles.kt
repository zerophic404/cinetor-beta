package com.mythbrix.cinetor.feature.player.subs

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.mythbrix.cinetor.BuildConfig
import com.mythbrix.cinetor.CinetorApplication
import com.mythbrix.cinetor.core.data.gateway.GatewaySigner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

private const val MAX_BYTES = 4 * 1024 * 1024

private val fallbackHttpClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
}

/** Reads a subtitle file: bundled asset (asset:///), SAF/content Uri, or signed/plain http(s). Capped at 4 MB. */
suspend fun loadSubtitleText(context: Context, uri: String): String = withContext(Dispatchers.IO) {
    if (uri.startsWith("http://") || uri.startsWith("https://")) {
        return@withContext fetchRemoteSubtitleText(context, uri)
    }
    val stream = when {
        uri.startsWith("asset:///") -> context.assets.open(uri.removePrefix("asset:///"))
        else -> context.contentResolver.openInputStream(Uri.parse(uri)) ?: error("cannot open subtitle")
    }
    stream.use { s ->
        val bytes = s.readBytes()
        require(bytes.size <= MAX_BYTES) { "subtitle file too large" }
        String(bytes, Charsets.UTF_8)
    }
}

private fun fetchRemoteSubtitleText(context: Context, uri: String): String {
    val container = (context.applicationContext as? CinetorApplication)?.container
    val client = container?.httpClient ?: fallbackHttpClient
    val gatewayBase = runCatching { container?.settingsState?.state?.value?.gatewayUrl.orEmpty() }.getOrDefault("")
    val secret = BuildConfig.GATEWAY_SIGNING_SECRET
    val shouldSign = secret.isNotBlank() && GatewaySigner.shouldSign(uri, gatewayBase)

    fun buildReq(epochSec: Long): Request {
        val b = Request.Builder()
            .url(uri)
            .header("User-Agent", GatewaySigner.userAgentFor(uri, gatewayBase))
        if (shouldSign) {
            GatewaySigner.signHeaders(secret, "GET", uri, epochSec).forEach { (k, v) -> b.header(k, v) }
        }
        return b.build()
    }

    val firstEpoch = System.currentTimeMillis() / 1000L
    client.newCall(buildReq(firstEpoch)).execute().use { res ->
        if (res.code == 401 && shouldSign) {
            val errBody = runCatching { res.body?.string() }.getOrNull()
            val serverSec = GatewaySigner.parseServerEpochSec(errBody, res.header("Date"))
            if (serverSec != null) {
                client.newCall(buildReq(serverSec)).execute().use { retry ->
                    if (!retry.isSuccessful) throw IOException("Subtitle load failed")
                    val bytes = retry.body?.bytes() ?: throw IOException("Empty subtitle")
                    require(bytes.size <= MAX_BYTES) { "subtitle file too large" }
                    return String(bytes, Charsets.UTF_8)
                }
            }
        }
        if (!res.isSuccessful) throw IOException("Subtitle load failed")
        val bytes = res.body?.bytes() ?: throw IOException("Empty subtitle")
        require(bytes.size <= MAX_BYTES) { "subtitle file too large" }
        return String(bytes, Charsets.UTF_8)
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
