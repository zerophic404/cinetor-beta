package com.mythbrix.cinetor.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Audiotrack
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ClosedCaption
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mythbrix.cinetor.core.data.DubOption
import com.mythbrix.cinetor.core.data.Source
import com.mythbrix.cinetor.core.player.KnownSubtitle
import com.mythbrix.cinetor.core.player.PLAYBACK_SPEEDS
import com.mythbrix.cinetor.core.player.PlayerUiState
import com.mythbrix.cinetor.design.Cinetor
import com.mythbrix.cinetor.feature.player.subs.POPULAR_SUBTITLE_LANGUAGES
import com.mythbrix.cinetor.feature.player.subs.formatTrackLanguageLabel
import com.mythbrix.cinetor.feature.player.subs.fullSubtitleLanguageName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

enum class SettingsPage { MAIN, QUALITY, SPEED, AUDIO, SUBTITLES, SLEEP }

/** "Normal" for 1×, else "1.25×". */
fun speedLabel(speed: Float): String =
    if (speed == 1f) "Normal" else (if (speed % 1f == 0f) "${speed.toInt()}" else speed.toString().trimEnd('0')) + "×"

private val STANDARD_HEIGHTS = intArrayOf(4320, 2160, 1440, 1080, 720, 480, 360, 240, 144)

/** Snaps a provider height (`1088`, `1072`, `2176`) to the nearest standard ladder rung (`1080`, `2160`). */
fun standardHeight(height: Int?): Int? {
    val h = height?.takeIf { it > 0 } ?: return null
    return STANDARD_HEIGHTS.minByOrNull { kotlin.math.abs(it - h) }
}

/** One Quality menu entry: a single resolution, backed by the best source for the active language. */
data class QualityOption(val height: Int, val sourceIndex: Int) {
    val label: String get() = "${height}p"
}

private fun isPreferredCodec(src: Source): Boolean {
    val c = src.codec.orEmpty().lowercase()
    return c.isEmpty() || "264" in c || "avc" in c
}

/**
 * Quality menu entries for the language currently playing: exactly one entry per resolution
 * (`2160p`, `1080p`, `720p`, `480p`, `360p`), highest first, never repeated per language, codec
 * or container. The active source wins its rung; otherwise H.264 beats HEVC/DASH variants.
 */
fun qualityOptions(sources: List<Source>, activeIndex: Int): List<QualityOption> {
    if (sources.isEmpty()) return emptyList()
    val activeLang = sources.getOrNull(activeIndex)?.let(::audioDubOf)
    val sameLang = sources.indices.filter { i ->
        activeLang == null || audioDubOf(sources[i])?.equals(activeLang, ignoreCase = true) != false
    }
    return sameLang
        .mapNotNull { i -> standardHeight(sources[i].height)?.let { it to i } }
        .groupBy({ it.first }, { it.second })
        .map { (height, indices) ->
            val pick = indices.firstOrNull { it == activeIndex }
                ?: indices.firstOrNull { isPreferredCodec(sources[it]) }
                ?: indices.first()
            QualityOption(height, pick)
        }
        .sortedByDescending { it.height }
}

fun qualityLabel(ui: PlayerUiState, screen: PlayerScreenState? = null): String {
    screen?.sources?.getOrNull(screen.sourceIndex)?.let { src ->
        if (screen.sources.size > 1 && ui.tracks.forcedHeight == null) {
            standardHeight(src.height)?.let { return "${it}p" }
        }
    }
    val t = ui.tracks
    return t.forcedHeight?.let { "${it}p" } ?: ("Auto" + (t.playingHeight?.takeIf { it > 0 }?.let { " (${it}p)" } ?: ""))
}

/** Extra subtitle hooks (load file, style, sync, and in-app online subtitle search). */
class SubtitleExtras(
    val onLoadFile: (() -> Unit)? = null,
    val onStyle: (() -> Unit)? = null,
    val onSync: (() -> Unit)? = null,
    val sideLoadedLabel: String? = null,
    val onClearSideLoaded: (() -> Unit)? = null,
    val onSearchOnline: (suspend (query: String, lang: String?) -> List<KnownSubtitle>)? = null,
    val onPickOnlineSub: ((KnownSubtitle) -> Unit)? = null,
)

private data class AudioDubEntry(
    val language: String,
    val fromSources: Boolean,
    val siblingOption: DubOption? = null,
)

private fun buildAudioDubEntries(screen: PlayerScreenState): List<AudioDubEntry> {
    val map = LinkedHashMap<String, AudioDubEntry>()
    for (src in screen.sources) {
        val lang = audioDubOf(src) ?: continue
        val key = lang.lowercase()
        if (!map.containsKey(key)) {
            map[key] = AudioDubEntry(language = lang, fromSources = true)
        }
    }
    for (dub in screen.item?.dubs.orEmpty()) {
        val lang = fullSubtitleLanguageName(dub.languageName.ifBlank { dub.languageCode })
        val key = lang.lowercase()
        val existing = map[key]
        if (existing == null) {
            map[key] = AudioDubEntry(language = lang, fromSources = false, siblingOption = dub)
        } else if (existing.siblingOption == null) {
            map[key] = existing.copy(siblingOption = dub)
        }
    }
    val priority = listOf("hindi", "original", "english", "bangla", "dual audio")
    return map.values.sortedWith(
        compareBy<AudioDubEntry> { entry ->
            val idx = priority.indexOf(entry.language.lowercase())
            if (idx >= 0) idx else priority.size
        }.thenBy { it.language },
    )
}

/** Player settings: Quality / Speed / Audio (Dubs + Tracks) / Subtitles (+ Online Search) / Sleep timer. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    ui: PlayerUiState,
    screen: PlayerScreenState,
    holder: PlayerStateHolder,
    onDismiss: () -> Unit,
    extras: SubtitleExtras = SubtitleExtras(),
    initialPage: SettingsPage = SettingsPage.MAIN,
) {
    var page by remember(initialPage) { mutableStateOf(initialPage) }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    var onlineQuery by remember(screen.title) { mutableStateOf(screen.title) }
    var onlineLangCode by remember { mutableStateOf<String?>(null) }
    var onlineResults by remember(screen.generation) { mutableStateOf<List<KnownSubtitle>>(emptyList()) }
    var onlineLoading by remember { mutableStateOf(false) }
    var onlineSearched by remember(screen.generation) { mutableStateOf(false) }
    var onlineError by remember { mutableStateOf<String?>(null) }

    fun runOnlineSearch(queryOverride: String = onlineQuery, langOverride: String? = onlineLangCode) {
        val searchFn = extras.onSearchOnline ?: return
        val q = queryOverride.trim().ifEmpty { screen.title.trim() }
        if (q.isEmpty()) return
        onlineLoading = true
        onlineSearched = true
        onlineError = null
        scope.launch {
            try {
                onlineResults = searchFn(q, langOverride)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                onlineResults = emptyList()
                onlineError = "Couldn't search subtitles right now."
            } finally {
                onlineLoading = false
            }
        }
    }

    LaunchedEffect(page, screen.title) {
        if (page == SettingsPage.SUBTITLES && extras.onSearchOnline != null && !onlineSearched && screen.title.isNotBlank()) {
            runOnlineSearch(screen.title, onlineLangCode)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheet,
        containerColor = Color(0xF0101010),
        contentColor = Color.White,
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 12.dp)) {
            when (page) {
                SettingsPage.MAIN -> {
                    val selectedText = ui.tracks.text.firstOrNull { it.selected }
                    val subLabel = extras.sideLoadedLabel?.let { fullSubtitleLanguageName(it) }
                        ?: selectedText?.let { formatTrackLanguageLabel(it.label, it.language, "On") }
                        ?: "Off"
                    val activeDub = screen.sources.getOrNull(screen.sourceIndex)?.let(::audioDubOf)
                    val selectedAudio = ui.tracks.audio.firstOrNull { it.selected }
                    val audioSummary = activeDub
                        ?: selectedAudio?.let { formatTrackLanguageLabel(it.label, it.language, "Original") }
                        ?: "Original"
                    SheetRow(Icons.Rounded.HighQuality, "Quality", qualityLabel(ui, screen)) { page = SettingsPage.QUALITY }
                    SheetRow(Icons.Rounded.Speed, "Playback speed", speedLabel(ui.speed)) { page = SettingsPage.SPEED }
                    SheetRow(Icons.Rounded.Audiotrack, "Audio", audioSummary) { page = SettingsPage.AUDIO }
                    SheetRow(Icons.Rounded.ClosedCaption, "Subtitles", subLabel) { page = SettingsPage.SUBTITLES }
                    SheetRow(Icons.Rounded.Bedtime, "Sleep timer", screen.sleep.label) { page = SettingsPage.SLEEP }
                }

                SettingsPage.QUALITY -> SubPage("Quality", { if (initialPage == SettingsPage.MAIN) page = SettingsPage.MAIN else onDismiss() }) {
                    val t = ui.tracks
                    val options = qualityOptions(screen.sources, screen.sourceIndex)
                    val currentHeight = standardHeight(screen.sources.getOrNull(screen.sourceIndex)?.height)
                    // Adaptive (HLS/DASH) rungs only add resolutions the provider sources don't already cover.
                    val trackRungs = t.video
                        .mapNotNull { q -> standardHeight(q.height)?.let { it to q.height } }
                        .distinctBy { it.first }
                        .filter { (h, _) -> options.none { it.height == h } }
                        .sortedByDescending { it.first }
                    if (t.video.isNotEmpty()) {
                        val auto = "Auto" + (t.playingHeight?.takeIf { it > 0 }?.let { " (${standardHeight(it)}p)" } ?: "")
                        item { Choice(auto, t.autoQuality && options.size <= 1) { holder.selectQuality(null); onDismiss() } }
                    }
                    items(options, key = { "q${it.height}" }) { opt ->
                        Choice(opt.label, t.forcedHeight == null && opt.height == currentHeight) {
                            if (opt.sourceIndex != screen.sourceIndex) holder.selectSource(opt.sourceIndex)
                            else holder.selectQuality(null)
                            onDismiss()
                        }
                    }
                    items(trackRungs, key = { "t${it.first}" }) { (h, raw) ->
                        Choice("${h}p", t.forcedHeight == raw) { holder.selectQuality(raw); onDismiss() }
                    }
                    if (options.isEmpty() && t.video.isEmpty()) {
                        item { Choice(currentHeight?.let { "${it}p" } ?: "Auto", true) { onDismiss() } }
                    }
                }

                SettingsPage.SPEED -> SubPage("Playback speed", { if (initialPage == SettingsPage.MAIN) page = SettingsPage.MAIN else onDismiss() }) {
                    items(PLAYBACK_SPEEDS, key = { it }) { sp ->
                        Choice(speedLabel(sp), ui.speed == sp) { holder.setSpeed(sp); onDismiss() }
                    }
                }

                SettingsPage.AUDIO -> SubPage("Audio", { if (initialPage == SettingsPage.MAIN) page = SettingsPage.MAIN else onDismiss() }) {
                    val dubEntries = buildAudioDubEntries(screen)
                    val activeDub = screen.sources.getOrNull(screen.sourceIndex)?.let(::audioDubOf)
                    if (dubEntries.isNotEmpty()) {
                        item { SectionLabel("Audio / Dub language") }
                        items(dubEntries, key = { "dub:${it.language}" }) { entry ->
                            val isSelected = activeDub?.equals(entry.language, ignoreCase = true) == true ||
                                (activeDub == null && dubEntries.size == 1)
                            Choice(
                                label = entry.language,
                                selected = isSelected,
                                badge = if (entry.language.equals("Original", ignoreCase = true)) "Original" else "Dub",
                            ) {
                                if (entry.fromSources) holder.selectDub(entry.language)
                                else if (entry.siblingOption != null) holder.selectDubOption(entry.siblingOption)
                                else holder.selectDub(entry.language)
                                onDismiss()
                            }
                        }
                    }
                    if (ui.tracks.audio.isNotEmpty()) {
                        if (dubEntries.isNotEmpty()) {
                            item { SectionLabel("Stream audio tracks") }
                        }
                        items(ui.tracks.audio, key = { it.id }) { a ->
                            val cleanLabel = formatTrackLanguageLabel(a.label, a.language, "Original")
                            Choice(cleanLabel, a.selected, badge = "Track") {
                                holder.selectAudio(a.id)
                                onDismiss()
                            }
                        }
                    }
                    if (dubEntries.isEmpty() && ui.tracks.audio.isEmpty()) {
                        item { Choice(activeDub ?: "Original", true) { onDismiss() } }
                    }
                }

                SettingsPage.SUBTITLES -> SubPage("Subtitles", { if (initialPage == SettingsPage.MAIN) page = SettingsPage.MAIN else onDismiss() }) {
                    val anySelected = extras.sideLoadedLabel != null || ui.tracks.text.any { it.selected }
                    item {
                        Choice("Off", !anySelected) {
                            extras.onClearSideLoaded?.invoke()
                            holder.selectText(null)
                            onDismiss()
                        }
                    }
                    extras.sideLoadedLabel?.let { raw ->
                        val label = fullSubtitleLanguageName(raw)
                        item { Choice(label, true, badge = "Loaded") { onDismiss() } }
                    }
                    items(ui.tracks.text, key = { it.id }) { t ->
                        val fullLabel = formatTrackLanguageLabel(t.label, t.language, "Original")
                        Choice(fullLabel, t.selected && extras.sideLoadedLabel == null) {
                            extras.onClearSideLoaded?.invoke()
                            holder.selectText(t.id)
                            onDismiss()
                        }
                    }

                    if (extras.onSearchOnline != null) {
                        item {
                            SectionLabel("Search online subtitles")
                            OnlineSubtitleSearchBox(
                                query = onlineQuery,
                                onQueryChange = { onlineQuery = it },
                                selectedLangCode = onlineLangCode,
                                onPickLang = { code ->
                                    onlineLangCode = code
                                    runOnlineSearch(onlineQuery, code)
                                },
                                loading = onlineLoading,
                                onSearch = { runOnlineSearch(onlineQuery, onlineLangCode) },
                            )
                        }
                        if (onlineError != null) {
                            item {
                                Text(
                                    onlineError.orEmpty(),
                                    color = Color(0xFFFF8A80),
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                                )
                            }
                        } else if (!onlineLoading && onlineSearched && onlineResults.isEmpty()) {
                            item {
                                Text(
                                    "No matching online subtitles found.",
                                    color = Color.White.copy(alpha = 0.6f),
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                                )
                            }
                        }
                        items(onlineResults, key = { "online:${it.id}" }) { sub ->
                            val cleanName = formatTrackLanguageLabel(sub.label, sub.language, "Original")
                            Choice(
                                label = cleanName,
                                selected = extras.sideLoadedLabel?.equals(cleanName, ignoreCase = true) == true,
                                badge = "Online",
                            ) {
                                extras.onPickOnlineSub?.invoke(sub)
                                onDismiss()
                            }
                        }
                    }

                    item { SectionLabel("Tools") }
                    extras.onLoadFile?.let { f -> item { Action("Load subtitle file (SRT/VTT)…") { f(); onDismiss() } } }
                    extras.onSync?.let { f -> item { Action("Sync offset…") { f(); onDismiss() } } }
                    extras.onStyle?.let { f -> item { Action("Subtitle style…") { f(); onDismiss() } } }
                }

                SettingsPage.SLEEP -> SubPage("Sleep timer", { if (initialPage == SettingsPage.MAIN) page = SettingsPage.MAIN else onDismiss() }) {
                    items(SleepOption.entries, key = { it.name }) { o ->
                        Choice(o.label, screen.sleep == o) { holder.setSleep(o); onDismiss() }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = Cinetor.tokens.accent,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
    )
}

@Composable
private fun OnlineSubtitleSearchBox(
    query: String,
    onQueryChange: (String) -> Unit,
    selectedLangCode: String?,
    onPickLang: (String?) -> Unit,
    loading: Boolean,
    onSearch: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                placeholder = { Text("Movie or series title", color = Color.White.copy(alpha = 0.45f)) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = Cinetor.tokens.accent,
                    unfocusedBorderColor = Color.White.copy(alpha = 0.22f),
                    cursorColor = Cinetor.tokens.accent,
                ),
                modifier = Modifier.weight(1f),
            )
            Box(
                Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(Cinetor.tokens.accent)
                    .clickable(enabled = !loading, onClick = onSearch)
                    .semantics { contentDescription = "Search online subtitles" }
                    .padding(horizontal = 14.dp, vertical = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (loading) {
                    CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                } else {
                    Icon(Icons.Rounded.Search, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                }
            }
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item(key = "lang:all") {
                LangFilterChip("All", selectedLangCode == null) { onPickLang(null) }
            }
            items(POPULAR_SUBTITLE_LANGUAGES, key = { "lang:${it.code}" }) { lang ->
                LangFilterChip(lang.name, selectedLangCode == lang.code) {
                    onPickLang(if (selectedLangCode == lang.code) null else lang.code)
                }
            }
        }
    }
}

@Composable
private fun LangFilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Text(
        label,
        color = Color.White,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
        modifier = Modifier
            .clip(shape)
            .background(if (selected) Cinetor.tokens.accent else Color.White.copy(alpha = 0.08f))
            .border(1.dp, if (selected) Cinetor.tokens.accent else Color.White.copy(alpha = 0.16f), shape)
            .clickable(onClick = onClick)
            .semantics { contentDescription = "Subtitle language $label" }
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

@Composable
private fun SheetRow(icon: ImageVector, title: String, value: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).semantics { contentDescription = title }
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = Color.White)
        Spacer(Modifier.width(16.dp))
        Text(
            title,
            style = MaterialTheme.typography.bodyLarge,
            color = Color.White,
            maxLines = 1,
            softWrap = false,
        )
        Spacer(Modifier.width(16.dp))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.6f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f),
        )
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = Color.White.copy(alpha = 0.6f))
    }
}

@Composable
private fun SubPage(title: String, onBack: () -> Unit, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onBack).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, contentDescription = "Back", tint = Color.White)
        Spacer(Modifier.width(8.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color.White)
    }
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 460.dp), content = content)
}

@Composable
private fun Choice(label: String, selected: Boolean, badge: String? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).semantics { contentDescription = label }
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Spacer(Modifier.size(12.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (selected) Cinetor.tokens.accent else Color.White,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        badge?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.55f),
            )
        }
        if (selected) Icon(Icons.Rounded.Check, contentDescription = null, tint = Cinetor.tokens.accent)
    }
}

@Composable
private fun Action(label: String, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.bodyLarge,
        color = Color.White.copy(alpha = 0.9f),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 32.dp, vertical = 12.dp),
    )
}
