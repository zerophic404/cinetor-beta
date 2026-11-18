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
import java.util.Locale
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

enum class SubEdge(val label: String) { NONE("None"), OUTLINE("Outline"), SHADOW("Shadow") }
enum class SubFont(val label: String) { DEFAULT("Default"), SERIF("Serif"), MONO("Mono"), BOLD("Bold") }

data class SubtitleLanguageChip(val code: String, val name: String)

val POPULAR_SUBTITLE_LANGUAGES: List<SubtitleLanguageChip> = listOf(
    SubtitleLanguageChip("bn", "Bangla"),
    SubtitleLanguageChip("en", "English"),
    SubtitleLanguageChip("hi", "Hindi"),
    SubtitleLanguageChip("ur", "Urdu"),
    SubtitleLanguageChip("zh", "Chinese"),
    SubtitleLanguageChip("ar", "Arabic"),
    SubtitleLanguageChip("ko", "Korean"),
    SubtitleLanguageChip("ja", "Japanese"),
    SubtitleLanguageChip("es", "Spanish"),
    SubtitleLanguageChip("fr", "French"),
    SubtitleLanguageChip("id", "Indonesian"),
)

private val LANGUAGE_NAMES: Map<String, String> = mapOf(
    "bn" to "Bangla",
    "ben" to "Bangla",
    "bengali" to "Bangla",
    "bangla" to "Bangla",
    "en" to "English",
    "eng" to "English",
    "english" to "English",
    "hi" to "Hindi",
    "hin" to "Hindi",
    "hindi" to "Hindi",
    "ur" to "Urdu",
    "urd" to "Urdu",
    "urdu" to "Urdu",
    "zh" to "Chinese",
    "chi" to "Chinese",
    "zho" to "Chinese",
    "cmn" to "Chinese",
    "yue" to "Cantonese",
    "chinese" to "Chinese",
    "mandarin" to "Chinese",
    "ar" to "Arabic",
    "ara" to "Arabic",
    "arabic" to "Arabic",
    "ko" to "Korean",
    "kor" to "Korean",
    "korean" to "Korean",
    "ja" to "Japanese",
    "jpn" to "Japanese",
    "japanese" to "Japanese",
    "es" to "Spanish",
    "spa" to "Spanish",
    "spanish" to "Spanish",
    "fr" to "French",
    "fre" to "French",
    "fra" to "French",
    "french" to "French",
    "id" to "Indonesian",
    "ind" to "Indonesian",
    "in" to "Indonesian",
    "indonesian" to "Indonesian",
    "tr" to "Turkish",
    "tur" to "Turkish",
    "turkish" to "Turkish",
    "ta" to "Tamil",
    "tam" to "Tamil",
    "tamil" to "Tamil",
    "te" to "Telugu",
    "tel" to "Telugu",
    "telugu" to "Telugu",
    "ml" to "Malayalam",
    "mal" to "Malayalam",
    "malayalam" to "Malayalam",
    "pa" to "Punjabi",
    "pan" to "Punjabi",
    "punjabi" to "Punjabi",
    "ru" to "Russian",
    "rus" to "Russian",
    "russian" to "Russian",
    "pt" to "Portuguese",
    "por" to "Portuguese",
    "portuguese" to "Portuguese",
    "ms" to "Malay",
    "may" to "Malay",
    "msa" to "Malay",
    "malay" to "Malay",
    "th" to "Thai",
    "tha" to "Thai",
    "thai" to "Thai",
    "vi" to "Vietnamese",
    "vie" to "Vietnamese",
    "vietnamese" to "Vietnamese",
    "tl" to "Filipino",
    "fil" to "Filipino",
    "tgl" to "Filipino",
    "tagalog" to "Filipino",
    "filipino" to "Filipino",
    "de" to "German",
    "ger" to "German",
    "deu" to "German",
    "german" to "German",
    "it" to "Italian",
    "ita" to "Italian",
    "italian" to "Italian",
    "fa" to "Persian",
    "per" to "Persian",
    "fas" to "Persian",
    "persian" to "Persian",
    "nl" to "Dutch",
    "dut" to "Dutch",
    "nld" to "Dutch",
    "dutch" to "Dutch",
    "pl" to "Polish",
    "pol" to "Polish",
    "polish" to "Polish",
    "uk" to "Ukrainian",
    "ukr" to "Ukrainian",
    "ukrainian" to "Ukrainian",
    "el" to "Greek",
    "gre" to "Greek",
    "ell" to "Greek",
    "greek" to "Greek",
    "he" to "Hebrew",
    "heb" to "Hebrew",
    "iw" to "Hebrew",
    "hebrew" to "Hebrew",
    "sv" to "Swedish",
    "swe" to "Swedish",
    "swedish" to "Swedish",
    "no" to "Norwegian",
    "nor" to "Norwegian",
    "nb" to "Norwegian",
    "nn" to "Norwegian",
    "norwegian" to "Norwegian",
    "da" to "Danish",
    "dan" to "Danish",
    "danish" to "Danish",
    "fi" to "Finnish",
    "fin" to "Finnish",
    "finnish" to "Finnish",
    "ro" to "Romanian",
    "rum" to "Romanian",
    "ron" to "Romanian",
    "romanian" to "Romanian",
    "hu" to "Hungarian",
    "hun" to "Hungarian",
    "hungarian" to "Hungarian",
    "cs" to "Czech",
    "cze" to "Czech",
    "ces" to "Czech",
    "czech" to "Czech",
    "my" to "Burmese",
    "bur" to "Burmese",
    "mya" to "Burmese",
    "burmese" to "Burmese",
    "ne" to "Nepali",
    "nep" to "Nepali",
    "nepali" to "Nepali",
    "si" to "Sinhala",
    "sin" to "Sinhala",
    "sinhala" to "Sinhala",
    "mr" to "Marathi",
    "mar" to "Marathi",
    "marathi" to "Marathi",
    "gu" to "Gujarati",
    "guj" to "Gujarati",
    "gujarati" to "Gujarati",
    "kn" to "Kannada",
    "kan" to "Kannada",
    "kannada" to "Kannada",
    "or" to "Odia",
    "ori" to "Odia",
    "odia" to "Odia",
    "as" to "Assamese",
    "asm" to "Assamese",
    "assamese" to "Assamese",
    "original" to "Original",
)

private val GENERIC_TRACK_RE = Regex("""^(?i)(subtitle|audio|track)\s*\d+$""")
private val PAREN_SHORT_CODE_RE = Regex("""\(([A-Za-z]{2,3}(?:-[A-Za-z0-9]{2,4})?)\)$""")

/** Pure mapper converting any ISO language code or alias into a full human language name (never 2/3-letter codes). */
fun fullSubtitleLanguageName(raw: String?): String {
    val cleaned = raw?.trim().orEmpty()
    if (cleaned.isEmpty()) return "Original"
    val lower = cleaned.lowercase(Locale.US)
    if (lower == "und" || lower == "unknown" || lower == "mul" || lower == "zxx" || lower == "default") return "Original"
    LANGUAGE_NAMES[lower]?.let { return it }
    val primary = lower.substringBefore('-').substringBefore('_').substringBefore(' ').trim()
    LANGUAGE_NAMES[primary]?.let { return it }
    if (primary.length in 2..3 && primary.all { it.isLetter() }) {
        val locName = runCatching {
            Locale.forLanguageTag(primary).getDisplayLanguage(Locale.ENGLISH)
        }.getOrNull()?.trim().orEmpty()
        if (locName.length > 3 && !locName.equals(primary, ignoreCase = true)) {
            return if (locName.equals("Bengali", ignoreCase = true)) "Bangla"
            else locName.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString() }
        }
        return "Original"
    }
    return cleaned.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString() }
}

/** Formats an audio/subtitle track's display label so short codes (`bn`, `en`, `Subtitle 1`) become full words. */
fun formatTrackLanguageLabel(label: String?, language: String?, fallback: String = "Original"): String {
    val rawLabel = label?.trim().orEmpty()
    val rawLang = language?.trim().orEmpty()
    if (rawLabel.isEmpty() || GENERIC_TRACK_RE.matches(rawLabel) || rawLabel.equals("und", ignoreCase = true)) {
        return if (rawLang.isNotEmpty()) fullSubtitleLanguageName(rawLang) else fallback
    }
    if (rawLabel.length <= 3 && rawLabel.all { it.isLetter() }) {
        return fullSubtitleLanguageName(rawLang.ifEmpty { rawLabel })
    }
    val parenMatch = PAREN_SHORT_CODE_RE.find(rawLabel)
    if (parenMatch != null) {
        val code = parenMatch.groupValues[1]
        val full = fullSubtitleLanguageName(code)
        val prefix = rawLabel.substring(0, parenMatch.range.first).trim()
        return if (prefix.equals(full, ignoreCase = true)) full else "$prefix ($full)"
    }
    val mapped = LANGUAGE_NAMES[rawLabel.lowercase(Locale.US)]
    return mapped ?: rawLabel
}

/** Subtitle look (per profile). Colors are ARGB; default background is 0 % transparent with crisp drop shadow. */
@Serializable
data class SubtitleStyle(
    /** 50–200 %. */
    val sizePercent: Int = 100,
    val textColor: Long = 0xFFFFFFFF,
    val bgColor: Long = 0xFF000000,
    /** 0..1 background opacity (default 0f = completely transparent box). */
    val bgOpacity: Float = 0f,
    val edge: SubEdge = SubEdge.SHADOW,
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
