package com.mythbrix.cinetor.feature.player

import android.annotation.SuppressLint
import android.content.Context
import android.content.MutableContextWrapper
import android.graphics.Color as AndroidColor
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.annotation.Keep
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.mythbrix.cinetor.core.data.Source
import com.mythbrix.cinetor.core.player.CinetorPlayer
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * Pure YouTube video ID extractor across `youtube.com/watch?v=...`, `youtu.be/...`,
 * `youtube-nocookie.com/embed/...`, `youtube.com/shorts|live|v/...`, and `yt://<videoId>`.
 */
fun extractYouTubeVideoId(url: String?): String? =
    com.mythbrix.cinetor.core.player.extractYouTubeVideoId(url)

fun isYouTubeUrl(url: String?): Boolean = extractYouTubeVideoId(url) != null

/**
 * R8-safe JavaScript bridge receiving chromeless YouTube IFrame API events and marshaling
 * them onto the Android main looper before touching [CinetorPlayer].
 */
@Keep
class YouTubeAndroidBridge(
    private val postToMain: (() -> Unit) -> Unit = { block -> Handler(Looper.getMainLooper()).post(block) },
    private val onReadyCallback: (Double) -> Unit,
    private val onProgressCallback: (Double, Double, Int) -> Unit,
    private val onStateChangeCallback: (Int) -> Unit,
    private val onErrorCallback: (Int) -> Unit,
) {
    @Keep
    @JavascriptInterface
    fun onReady(durationSec: Double) {
        postToMain { onReadyCallback(durationSec) }
    }

    @Keep
    @JavascriptInterface
    fun onProgress(currentSec: Double, durationSec: Double, state: Int) {
        postToMain { onProgressCallback(currentSec, durationSec, state) }
    }

    @Keep
    @JavascriptInterface
    fun onStateChange(state: Int) {
        postToMain { onStateChangeCallback(state) }
    }

    @Keep
    @JavascriptInterface
    fun onError(errorCode: Int) {
        postToMain { onErrorCallback(errorCode) }
    }
}

/**
 * Builds the chromeless YouTube HTML document loaded with base URL `https://www.youtube-nocookie.com`.
 * Uses vertical overscan (`top: -60px; height: calc(100% + 120px)`) inside an `overflow: hidden`
 * non-interactive stage so YouTube branding/title/watermarks are clipped without cropping 16:9 video.
 */
fun buildChromelessYouTubeHtml(
    videoId: String,
    startSec: Int = 0,
    autoplay: Boolean = true,
): String {
    val safeId = videoId.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(20)
    val safeStart = startSec.coerceAtLeast(0)
    val autoVal = if (autoplay) 1 else 0
    val startLine = if (safeStart > 0) "'start': $safeStart," else ""
    return """
        <!DOCTYPE html>
        <html>
        <head>
        <meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
        <style>
          * { box-sizing: border-box; margin: 0; padding: 0; }
          html, body {
            width: 100%; height: 100%;
            background: #000; overflow: hidden;
            user-select: none; -webkit-user-select: none;
            pointer-events: none;
          }
          #wrapper {
            position: fixed; inset: 0;
            width: 100%; height: 100%;
            overflow: hidden; background: #000;
            pointer-events: none;
            transform-origin: center center;
          }
          #player {
            position: absolute;
            top: -60px;
            left: 0;
            width: 100%;
            height: calc(100% + 120px);
            pointer-events: none;
          }
          iframe {
            width: 100% !important;
            height: 100% !important;
            border: 0 !important;
            pointer-events: none !important;
          }
          #loader-shield {
            position: fixed;
            inset: 0;
            background: #000;
            z-index: 999999;
            pointer-events: none;
          }
        </style>
        </head>
        <body>
        <div id="wrapper"><div id="player"></div></div>
        <div id="loader-shield"></div>
        <script>
          var ytPlayer = null;
          var timer = null;
          window.cinetorSetScale = function(sx, sy) {
            var w = document.getElementById('wrapper');
            if (w) w.style.transform = 'scale(' + sx + ',' + sy + ')';
          };
          function loadApi(src, fallback) {
            var tag = document.createElement('script');
            tag.src = src;
            if (fallback) {
              tag.onerror = function() { loadApi(fallback, null); };
            }
            var first = document.getElementsByTagName('script')[0];
            first.parentNode.insertBefore(tag, first);
          }
          loadApi('https://www.youtube-nocookie.com/iframe_api', 'https://www.youtube.com/iframe_api');

          function onYouTubeIframeAPIReady() {
            ytPlayer = new YT.Player('player', {
              host: 'https://www.youtube-nocookie.com',
              videoId: '$safeId',
              playerVars: {
                'autoplay': $autoVal,
                'controls': 0,
                'modestbranding': 1,
                'rel': 0,
                'showinfo': 0,
                'iv_load_policy': 3,
                'cc_load_policy': 0,
                'disablekb': 1,
                'fs': 0,
                'playsinline': 1,
                'enablejsapi': 1,
                $startLine
                'origin': 'https://www.youtube-nocookie.com'
              },
              events: {
                'onReady': onPlayerReady,
                'onStateChange': onPlayerStateChange,
                'onError': onPlayerError
              }
            });
            window.ytPlayer = ytPlayer;
          }

          function hideShield() {
            var shield = document.getElementById('loader-shield');
            if (shield) shield.style.display = 'none';
          }

          function onPlayerReady(event) {
            window.ytPlayer = event.target;
            if ($autoVal === 1) event.target.playVideo();
            if (window.AndroidBridge) {
              window.AndroidBridge.onReady(event.target.getDuration() || 0);
            }
            if (timer) clearInterval(timer);
            timer = setInterval(function() {
              if (ytPlayer && ytPlayer.getCurrentTime && window.AndroidBridge) {
                window.AndroidBridge.onProgress(
                  ytPlayer.getCurrentTime() || 0,
                  ytPlayer.getDuration() || 0,
                  ytPlayer.getPlayerState ? ytPlayer.getPlayerState() : -1
                );
              }
            }, 250);
          }

          function onPlayerStateChange(event) {
            if (event.data === 1 || event.data === 2) hideShield();
            if (window.AndroidBridge) {
              window.AndroidBridge.onStateChange(event.data);
            }
          }

          function onPlayerError(event) {
            hideShield();
            if (window.AndroidBridge) {
              window.AndroidBridge.onError(typeof event.data === 'number' ? event.data : -1);
            }
          }

          window.addEventListener('pagehide', function() {
            if (timer) { clearInterval(timer); timer = null; }
          });
        </script>
        </body>
        </html>
    """.trimIndent()
}

/**
 * Blocks touch and D-pad focus from ever entering the internal Chromium [WebView] hierarchy,
 * so Compose [PlayerGestures], [MiniPlayer] drag/pinch, and Android TV D-pad keys own all input.
 */
private class NonInteractiveWebContainer(context: Context) : FrameLayout(context) {
    init {
        isClickable = false
        isFocusable = false
        isFocusableInTouchMode = false
        descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        setBackgroundColor(AndroidColor.BLACK)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent?): Boolean = true
    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean = false
    override fun dispatchKeyEvent(event: KeyEvent?): Boolean = false
}

/**
 * Holds a single chromeless [WebView] instance for the active YouTube session so transitions
 * between FULL, MINI, and PiP reparent the live view without reloading or leaking on 2 GB RAM devices.
 */
class YouTubeWebViewHolder(
    private val appContext: Context,
    private val playerProvider: () -> CinetorPlayer?,
) {
    private val contextWrapper = MutableContextWrapper(appContext)
    private var webView: WebView? = null
    private var loadedToken: String? = null
    private var disposed = false

    private val bridge = YouTubeAndroidBridge(
        onReadyCallback = { dur -> playerProvider()?.onYouTubeReady(dur) },
        onProgressCallback = { cur, dur, st -> playerProvider()?.onYouTubeProgress(cur, dur, st) },
        onStateChangeCallback = { st -> playerProvider()?.onYouTubeStateChange(st) },
        onErrorCallback = { code -> playerProvider()?.onYouTubeError(code) },
    )

    @SuppressLint("SetJavaScriptEnabled")
    fun obtain(
        hostContext: Context,
        sessionKey: String?,
        videoId: String,
        startSec: Int,
        autoplay: Boolean,
    ): WebView {
        contextWrapper.baseContext = hostContext
        val token = "${sessionKey.orEmpty()}:$videoId"
        val existing = webView
        if (existing != null && !disposed) {
            if (loadedToken != token) {
                loadedToken = token
                val html = buildChromelessYouTubeHtml(videoId, startSec, autoplay)
                existing.loadDataWithBaseURL(YOUTUBE_NOCOOKIE_ORIGIN, html, "text/html", "UTF-8", null)
            }
            return existing
        }
        disposed = false
        loadedToken = token
        val created = WebView(contextWrapper).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            setBackgroundColor(AndroidColor.BLACK)
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            isClickable = false
            isFocusable = false
            isFocusableInTouchMode = false
            isLongClickable = false
            setOnLongClickListener { true }
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                mediaPlaybackRequiresUserGesture = false
                loadWithOverviewMode = true
                useWideViewPort = true
                cacheMode = WebSettings.LOAD_DEFAULT
                userAgentString = CHROMELESS_UA
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = true
            }
            webChromeClient = WebChromeClient()
            addJavascriptInterface(this@YouTubeWebViewHolder.bridge, BRIDGE_NAME)
            val html = buildChromelessYouTubeHtml(videoId, startSec, autoplay)
            loadDataWithBaseURL(YOUTUBE_NOCOOKIE_ORIGIN, html, "text/html", "UTF-8", null)
        }
        webView = created
        return created
    }

    fun releaseHostContext() {
        contextWrapper.baseContext = appContext
    }

    fun evaluate(script: String) {
        val wv = webView ?: return
        if (disposed) return
        wv.post {
            if (!disposed && webView === wv) {
                runCatching { wv.evaluateJavascript(script, null) }
            }
        }
    }

    fun destroy() {
        val wv = webView ?: return
        webView = null
        loadedToken = null
        disposed = true
        contextWrapper.baseContext = appContext
        runCatching {
            (wv.parent as? ViewGroup)?.removeView(wv)
            wv.removeJavascriptInterface(BRIDGE_NAME)
            wv.stopLoading()
            wv.onPause()
            wv.loadUrl("about:blank")
            wv.clearHistory()
            wv.removeAllViews()
            wv.destroy()
        }
    }

    companion object {
        const val YOUTUBE_NOCOOKIE_ORIGIN = "https://www.youtube-nocookie.com"
        const val BRIDGE_NAME = "AndroidBridge"
        private const val CHROMELESS_UA =
            "Mozilla/5.0 (Linux; Android 13; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    }
}

/**
 * Remembers and manages the lifecycle of the chromeless YouTube [WebView] at [PlayerHost] scope.
 * Destroys the [WebView] immediately when playback leaves YouTube mode or closes.
 */
@Composable
fun rememberYouTubeWebViewHolder(
    activeVideoId: String?,
    cinetorPlayer: CinetorPlayer?,
): YouTubeWebViewHolder? {
    val context = LocalContext.current
    val latestPlayer = rememberUpdatedState(cinetorPlayer)
    val holder = remember(activeVideoId != null) {
        if (activeVideoId != null) YouTubeWebViewHolder(context.applicationContext) { latestPlayer.value } else null
    }

    DisposableEffect(holder, cinetorPlayer) {
        if (holder != null && cinetorPlayer != null) {
            cinetorPlayer.attachYouTubeExecutor { script -> holder.evaluate(script) }
        }
        onDispose {
            cinetorPlayer?.attachYouTubeExecutor(null)
            holder?.destroy()
        }
    }
    return holder
}

/** Scale factors for [AspectMode] + pinch zoom on the chromeless YouTube stage. */
fun youtubeScaleFor(aspect: AspectMode, zoom: Float = 1f): Pair<Float, Float> {
    val z = zoom.coerceAtLeast(0.5f)
    return when (aspect) {
        AspectMode.FIT -> z to z
        AspectMode.FILL -> (1.18f * z) to (1.18f * z)
        AspectMode.ZOOM -> (1.34f * z) to (1.34f * z)
        AspectMode.STRETCH -> (1.18f * z) to (1.0f * z)
    }
}

/** Renders the shared chromeless YouTube [WebView] inside a non-interactive container. */
@Composable
fun ChromelessYouTubeSurface(
    youtubeHolder: YouTubeWebViewHolder,
    sessionKey: String?,
    videoId: String,
    startPositionMs: Long,
    playWhenReady: Boolean,
    aspect: AspectMode,
    modifier: Modifier = Modifier,
    zoom: Float = 1f,
) {
    val (sx, sy) = youtubeScaleFor(aspect, zoom)
    LaunchedEffect(youtubeHolder, sx, sy) {
        youtubeHolder.evaluate("if(window.cinetorSetScale)window.cinetorSetScale($sx,$sy);")
    }
    AndroidView(
        factory = { ctx ->
            NonInteractiveWebContainer(ctx).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
            }
        },
        update = { container ->
            val wv = youtubeHolder.obtain(
                hostContext = container.context,
                sessionKey = sessionKey,
                videoId = videoId,
                startSec = (startPositionMs / 1000L).toInt(),
                autoplay = playWhenReady,
            )
            if (wv.parent !== container) {
                (wv.parent as? ViewGroup)?.removeView(wv)
                container.removeAllViews()
                container.addView(wv)
                wv.onResume()
            }
        },
        onRelease = { container ->
            container.removeAllViews()
            youtubeHolder.releaseHostContext()
        },
        modifier = modifier,
    )
}

// ------------------------------------------------ Dub & Quality Preference Persistence (Beta cad284a parity)

data class PlaybackPref(
    val dub: String? = null,
    val height: Int? = null,
)

interface PlaybackPreferenceStore {
    fun getTitlePref(profileId: String, seriesOrTitleId: String): PlaybackPref?
    fun setTitlePref(profileId: String, seriesOrTitleId: String, pref: PlaybackPref)
    fun getGlobalPref(profileId: String): PlaybackPref?
    fun setGlobalPref(profileId: String, pref: PlaybackPref)
}

class InMemoryPlaybackPreferenceStore : PlaybackPreferenceStore {
    private val byTitle = ConcurrentHashMap<String, PlaybackPref>()
    private val byProfile = ConcurrentHashMap<String, PlaybackPref>()

    override fun getTitlePref(profileId: String, seriesOrTitleId: String): PlaybackPref? =
        byTitle["$profileId:${seriesBaseId(seriesOrTitleId)}"]

    override fun setTitlePref(profileId: String, seriesOrTitleId: String, pref: PlaybackPref) {
        byTitle["$profileId:${seriesBaseId(seriesOrTitleId)}"] = pref
    }

    override fun getGlobalPref(profileId: String): PlaybackPref? = byProfile[profileId]

    override fun setGlobalPref(profileId: String, pref: PlaybackPref) {
        byProfile[profileId] = pref
    }
}

/** Strips episode suffixes (`#ep1`, `-s1-e2`, `:ep1`) so every episode of a series shares one preference key. */
fun seriesBaseId(id: String): String {
    val beforeHash = id.substringBefore('#')
    val stripped = beforeHash
        .replace(Regex("(?i)[-:/]s?\\d+[-:/]e?\\d+$"), "")
        .replace(Regex("(?i)[-:/]e(pisode)?[-:]?\\d+$"), "")
    return stripped.ifBlank { beforeHash }
}

private val SIZE_TOKEN = Regex("""(?i)^[\d.]+\s*[kmgt]?b$""")
private val QUALITY_TOKEN = Regex("""(?i)^(?:\d{3,4}p(?:\s*(?:hd|fhd|qhd|uhd))?|4k(?:\s*uhd)?|2k(?:\s*qhd)?|auto|hd|sd|fhd|qhd|uhd)$""")
private val FORMAT_TOKEN = Regex("""(?i)^(?:mp4|mkv|m4v|avi|webm|mov|ts|hls|dash|h264|h265|hevc|av1|aac|flac|mp3)$""")
private val PROVIDER_TOKEN = Regex("""(?i)^(?:youtube|direct|proxy|gateway(?:\s*proxy)?|dual\s*audio|multi\s*audio|server\s*\d*|source\s*\d*|bdix|moviebox|zee\s*bangla)$""")
private val BRACKET_DUB = Regex("""\[([A-Za-z][A-Za-z\s-]{1,20})]""")

/** Extracts a dub/audio language name (e.g. `"Hindi"`, `"Bengali"`, `"Tamil"`) from a [Source.label], or null. */
fun dubOfSource(source: Source): String? {
    val label = source.label?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    BRACKET_DUB.find(label)?.groupValues?.getOrNull(1)?.trim()?.let { candidate ->
        if (!isNonDubToken(candidate)) return candidate
    }
    val parts = label.split("·", "|", "/").map { it.trim().trim('[', ']', '(', ')').trim() }
    for (part in parts) {
        if (part.isEmpty() || isNonDubToken(part)) continue
        return part
    }
    return null
}

private fun isNonDubToken(token: String): Boolean =
    SIZE_TOKEN.matches(token) ||
        QUALITY_TOKEN.matches(token) ||
        FORMAT_TOKEN.matches(token) ||
        PROVIDER_TOKEN.matches(token)

/**
 * Selects the best [Source] index matching saved dub & quality preferences across episodes:
 * 1. Exact dub + height match
 * 2. Exact dub + closest height
 * 3. Any source with target dub
 * 4. Closest height when a height preference exists
 * 5. Default index 0
 */
fun preferredSourceIndex(
    sources: List<Source>,
    titlePref: PlaybackPref?,
    globalPref: PlaybackPref?,
): Int {
    if (sources.size < 2) return 0
    val targetDub = titlePref?.dub?.takeIf { it.isNotBlank() } ?: globalPref?.dub?.takeIf { it.isNotBlank() }
    val targetHeight = titlePref?.height ?: globalPref?.height

    if (targetDub != null) {
        if (targetHeight != null) {
            val exact = sources.indexOfFirst {
                dubOfSource(it).equals(targetDub, ignoreCase = true) && it.height == targetHeight
            }
            if (exact >= 0) return exact

            val closestSameDub = sources.withIndex()
                .filter { (_, src) -> dubOfSource(src).equals(targetDub, ignoreCase = true) && (src.height ?: 0) > 0 }
                .minByOrNull { (_, src) -> abs((src.height ?: 0) - targetHeight) }
                ?.index
            if (closestSameDub != null) return closestSameDub
        }

        val anySameDub = sources.indexOfFirst { dubOfSource(it).equals(targetDub, ignoreCase = true) }
        if (anySameDub >= 0) return anySameDub
    }

    if (targetHeight != null) {
        val closestHeight = sources.withIndex()
            .filter { (_, src) -> (src.height ?: 0) > 0 }
            .minByOrNull { (_, src) -> abs((src.height ?: 0) - targetHeight) }
            ?.index
        if (closestHeight != null) return closestHeight
    }

    return 0
}
