package com.mythbrix.cinetor.feature.player.subs

import com.mythbrix.cinetor.core.data.HistoryStorage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

enum class SubEdge(val label: String) { NONE("None"), OUTLINE("Outline"), SHADOW("Shadow") }
enum class SubFont(val label: String) { DEFAULT("Default"), SERIF("Serif"), MONO("Mono"), BOLD("Bold") }

/** Subtitle look (per profile). Colors are ARGB. */
@Serializable
data class SubtitleStyle(
    /** 50–200 %. */
    val sizePercent: Int = 100,
    val textColor: Long = 0xFFFFFFFF,
    val bgColor: Long = 0xFF000000,
    /** 0..1 background opacity. */
    val bgOpacity: Float = 0.35f,
    val edge: SubEdge = SubEdge.OUTLINE,
    val font: SubFont = SubFont.DEFAULT,
    /** Distance from the bottom, % of the video height (0–40). */
    val bottomPercent: Int = 8,
) {
    fun normalized(): SubtitleStyle = copy(
        sizePercent = sizePercent.coerceIn(50, 200),
        bgOpacity = bgOpacity.coerceIn(0f, 1f),
        bottomPercent = bottomPercent.coerceIn(0, 40),
    )

    /** Background ARGB with [bgOpacity] applied as alpha. */
    val bgArgb: Long get() = ((bgOpacity.coerceIn(0f, 1f) * 255).toLong() shl 24) or (bgColor and 0xFFFFFF)

    companion object {
        val TEXT_COLORS = listOf(0xFFFFFFFF, 0xFFFFEB3B, 0xFF00E5FF, 0xFF76FF03, 0xFFFF5252)
        val BG_COLORS = listOf(0xFF000000, 0xFF424242, 0xFF0D47A1)
    }
}

/** Per-profile subtitle styles as one JSON blob (DataStore in the app, in-memory in tests). */
class SubtitleStyleStore(private val storage: HistoryStorage) {
    private val mutex = Mutex()
    private var cache: Map<String, SubtitleStyle>? = null
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = MapSerializer(String.serializer(), SubtitleStyle.serializer())

    private suspend fun all(): Map<String, SubtitleStyle> {
        cache?.let { return it }
        return mutex.withLock {
            cache ?: (storage.load()?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() }.orEmpty())
                .also { cache = it }
        }
    }

    suspend fun get(profileId: String): SubtitleStyle = all()[profileId] ?: SubtitleStyle()

    suspend fun save(profileId: String, style: SubtitleStyle) {
        val next = all() + (profileId to style.normalized())
        mutex.withLock { cache = next }
        storage.save(json.encodeToString(serializer, next))
    }
}

/** Side-loaded subtitle rendered by Cinetor ([SubtitleOverlay]). */
data class SideLoadedSubs(val label: String, val cues: List<SubCue>)

data class SubtitleUiState(
    val style: SubtitleStyle = SubtitleStyle(),
    val sideLoaded: SideLoadedSubs? = null,
    val offsetMs: Long = 0,
    /** Shown when sync can't apply (embedded stream track without a known URL). */
    val hint: String? = null,
    val loading: Boolean = false,
)

/**
 * Subtitle state for the player (style per profile, external SRT/VTT, sync offset). Side-loaded
 * subs are parsed + rendered by Cinetor so the offset works both ways; a catalog track (URL known)
 * converts to side-loaded when an offset is set; unknown embedded tracks can't be shifted → hint.
 */
class SubtitleController(
    private val scope: CoroutineScope,
    private val store: SubtitleStyleStore,
    private val profileId: () -> String,
    /** Reads a subtitle file (asset:///, content://, http(s)://). */
    private val loader: suspend (String) -> String,
) {
    private val _state = MutableStateFlow(SubtitleUiState())
    val state: StateFlow<SubtitleUiState> = _state.asStateFlow()
    private var loadJob: Job? = null

    fun loadStyle() {
        scope.launch {
            val s = runCatching { store.get(profileId()) }.getOrDefault(SubtitleStyle())
            _state.update { it.copy(style = s) }
        }
    }

    fun setStyle(style: SubtitleStyle) {
        val n = style.normalized()
        _state.update { it.copy(style = n) }
        scope.launch { runCatching { store.save(profileId(), n) } }
    }

    /** New title: drop side-loaded subs + offset. */
    fun reset() {
        loadJob?.cancel()
        _state.update { it.copy(sideLoaded = null, offsetMs = 0, hint = null, loading = false) }
    }

    /** Parse [uri] (name used for format detection + label) and render it ourselves. */
    fun loadExternal(uri: String, name: String, onLoaded: () -> Unit = {}) {
        loadJob?.cancel()
        _state.update { it.copy(loading = true, hint = null) }
        loadJob = scope.launch {
            val cues = try {
                SubtitleParser.parse(loader(uri), name)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
            if (cues.isEmpty()) {
                _state.update { it.copy(loading = false, hint = "Couldn't read subtitles from $name") }
            } else {
                _state.update { it.copy(loading = false, sideLoaded = SideLoadedSubs(name, cues), hint = null) }
                onLoaded()
            }
        }
    }

    fun clearSideLoaded() = _state.update { it.copy(sideLoaded = null, hint = null) }

    /**
     * Set the sync offset. Without side-loaded subs, [knownTrackUrl] (catalog track currently
     * shown by Media3) is loaded side-loaded so the shift applies; else a hint is shown.
     */
    fun setOffset(offsetMs: Long, knownTrackUrl: String? = null, knownTrackLabel: String? = null, onConverted: () -> Unit = {}) {
        val o = SubtitleSync.clamp(offsetMs)
        _state.update { it.copy(offsetMs = o) }
        if (o == 0L || _state.value.sideLoaded != null) return
        if (knownTrackUrl != null) {
            loadExternal(knownTrackUrl, knownTrackLabel ?: knownTrackUrl.substringAfterLast('/'), onConverted)
        } else {
            _state.update { it.copy(hint = "Sync works with side-loaded subtitles — load a file or pick a catalog track") }
        }
    }

    fun nudge(deltaMs: Long, knownTrackUrl: String? = null, knownTrackLabel: String? = null, onConverted: () -> Unit = {}) =
        setOffset(_state.value.offsetMs + deltaMs, knownTrackUrl, knownTrackLabel, onConverted)
}
