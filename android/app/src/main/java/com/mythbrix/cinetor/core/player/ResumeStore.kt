package com.mythbrix.cinetor.core.player

import com.mythbrix.cinetor.core.data.HistoryStorage
import com.mythbrix.cinetor.core.data.Item
import com.mythbrix.cinetor.core.data.Space
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

const val DEFAULT_PROFILE = "default"

/** Saved playback position (one per profile + item + episode). Carries card info for Continue Watching. */
@Serializable
data class ResumeEntry(
    val profileId: String,
    val itemId: String,
    val episodeId: String? = null,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
    val space: Space,
    val title: String = "",
    val backdrop: String = "",
    val poster: String = "",
    val dominantColor: String = "#000000",
    val accentColor: String = "#000000",
    /** e.g. "S1:E3" for series. */
    val episodeLabel: String? = null,
) {
    val fraction: Float get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

    /** ≥ 95 % watched → treated as finished (restart from 0, hidden from Continue Watching). */
    val finished: Boolean get() = durationMs > 0 && positionMs >= durationMs * 95 / 100
}

/** Where playback should start for a saved entry. */
fun startPositionFor(entry: ResumeEntry?): Long =
    if (entry == null || entry.finished || entry.positionMs < 3_000) 0 else entry.positionMs

interface ResumeStore {
    /** Newest first. */
    fun observe(profileId: String): Flow<List<ResumeEntry>>
    suspend fun get(profileId: String, itemId: String, episodeId: String?): ResumeEntry?

    /** Most recent entry of [itemId] (any episode) — series resume picks the episode from it. */
    suspend fun latest(profileId: String, itemId: String): ResumeEntry?
    suspend fun save(entry: ResumeEntry)

    /** Drops every entry of [profileId] (profile deleted / history cleared). */
    suspend fun clearProfile(profileId: String) {}
}

/**
 * Resume positions as one JSON blob (DataStore in the app, in-memory in tests). Room is not in the
 * project yet; when the download engine adds it this can move behind the same interface.
 */
class ResumeRepository(
    private val storage: HistoryStorage,
    private val max: Int = 300,
) : ResumeStore {
    private val mutex = Mutex()
    private val state = MutableStateFlow<List<ResumeEntry>>(emptyList())
    private var loaded = false
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(ResumeEntry.serializer())

    private suspend fun ensureLoaded() {
        if (loaded) return
        mutex.withLock {
            if (loaded) return
            state.value = storage.load()
                ?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() }
                .orEmpty()
                .sortedByDescending { it.updatedAt }
            loaded = true
        }
    }

    override fun observe(profileId: String): Flow<List<ResumeEntry>> = flow {
        ensureLoaded()
        emitAll(state.map { list -> list.filter { it.profileId == profileId } }.distinctUntilChanged())
    }

    override suspend fun get(profileId: String, itemId: String, episodeId: String?): ResumeEntry? {
        ensureLoaded()
        return state.value.firstOrNull { it.profileId == profileId && it.itemId == itemId && it.episodeId == episodeId }
    }

    override suspend fun latest(profileId: String, itemId: String): ResumeEntry? {
        ensureLoaded()
        return state.value.firstOrNull { it.profileId == profileId && it.itemId == itemId }
    }

    override suspend fun save(entry: ResumeEntry) {
        ensureLoaded()
        val blob = mutex.withLock {
            val next = (listOf(entry) + state.value.filterNot {
                it.profileId == entry.profileId && it.itemId == entry.itemId && it.episodeId == entry.episodeId
            }).take(max)
            state.value = next
            json.encodeToString(serializer, next)
        }
        storage.save(blob)
    }

    override suspend fun clearProfile(profileId: String) {
        ensureLoaded()
        val blob = mutex.withLock {
            val next = state.value.filterNot { it.profileId == profileId }
            state.value = next
            json.encodeToString(serializer, next)
        }
        storage.save(blob)
    }
}

/**
 * Continue Watching for [space]: video spaces only (isolation rule), unfinished, latest entry per
 * item, newest first.
 */
fun continueEntries(entries: List<ResumeEntry>, space: Space): List<ResumeEntry> {
    if (!space.isVideo) return emptyList()
    return entries.asSequence()
        .filter { it.space == space && it.space.isVideo }
        .sortedByDescending { it.updatedAt }
        .distinctBy { it.itemId }
        .filter { !it.finished && it.positionMs > 0 }
        .toList()
}

/** Card stub from a resume entry (full item loads in Details by id). */
fun ResumeEntry.toCardItem(): Item = Item(
    id = itemId, space = space, categoryId = "", type = com.mythbrix.cinetor.core.data.ItemType.VIDEO,
    title = title, year = 0, rating = 0.0, maturity = "", genres = emptyList(), synopsis = "",
    poster = poster, backdrop = backdrop, dominantColor = dominantColor, accentColor = accentColor,
    durationSec = (durationMs / 1000).toInt(),
)

/** Progress map for cards: itemId → fraction of the latest entry (unfinished only). */
fun progressByItem(entries: List<ResumeEntry>): Map<String, Float> =
    entries.sortedByDescending { it.updatedAt }.distinctBy { it.itemId }
        .filter { !it.finished }.associate { it.itemId to it.fraction }
