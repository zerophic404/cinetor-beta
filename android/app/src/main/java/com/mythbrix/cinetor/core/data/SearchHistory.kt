package com.mythbrix.cinetor.core.data

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
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** Persists the whole history blob (JSON). Local only — never synced or sent anywhere. */
interface HistoryStorage {
    suspend fun load(): String?
    suspend fun save(json: String)
}

class InMemoryHistoryStorage(private var blob: String? = null) : HistoryStorage {
    override suspend fun load(): String? = blob
    override suspend fun save(json: String) {
        blob = json
    }
}

@Serializable
data class HistoryEntry(val query: String, val ts: Long)

/**
 * Recent searches per profile. Global (video-space) history and each isolated space's in-space
 * history live in separate buckets: an isolated-space query is NEVER written to the global list.
 * Newest first, deduped case-insensitively, max [max] per bucket.
 */
class SearchHistory(
    private val storage: HistoryStorage,
    private val clock: () -> Long = System::currentTimeMillis,
    private val max: Int = MAX,
) {
    private val mutex = Mutex()
    private val state = MutableStateFlow<Map<String, List<HistoryEntry>>>(emptyMap())
    private var loaded = false

    /** Pauses recording (pause-history toggle). */
    @Volatile
    var paused: Boolean = false

    fun observe(profileId: String, space: Space?): Flow<List<String>> = flow {
        ensureLoaded()
        val key = scopeKey(profileId, space)
        emitAll(state.map { m -> m[key].orEmpty().map { it.query } })
    }.distinctUntilChanged()

    suspend fun entries(profileId: String, space: Space?): List<String> {
        ensureLoaded()
        return state.value[scopeKey(profileId, space)].orEmpty().map { it.query }
    }

    suspend fun record(profileId: String, query: String, space: Space?) {
        val q = query.trim().replace(Regex("\\s+"), " ")
        // Adult searches are never remembered (no recents for the gated space).
        if (q.isEmpty() || paused || space == Space.ADULT) return
        update(profileId, space) { list ->
            (listOf(HistoryEntry(q, clock())) + list.filterNot { it.query.equals(q, ignoreCase = true) }).take(max)
        }
    }

    suspend fun delete(profileId: String, query: String, space: Space?) =
        update(profileId, space) { list -> list.filterNot { it.query.equals(query, ignoreCase = true) } }

    suspend fun clear(profileId: String, space: Space?) = update(profileId, space) { emptyList() }

    private suspend fun update(profileId: String, space: Space?, f: (List<HistoryEntry>) -> List<HistoryEntry>) {
        ensureLoaded()
        mutex.withLock {
            val key = scopeKey(profileId, space)
            val next = state.value.toMutableMap()
            val list = f(next[key].orEmpty())
            if (list.isEmpty()) next.remove(key) else next[key] = list
            state.value = next
            storage.save(json.encodeToString(SERIALIZER, next))
        }
    }

    private suspend fun ensureLoaded() {
        if (loaded) return
        mutex.withLock {
            if (loaded) return
            val blob = runCatching { storage.load() }.getOrNull()
            state.value = blob?.let { runCatching { json.decodeFromString(SERIALIZER, it) }.getOrNull() }.orEmpty()
            loaded = true
        }
    }

    companion object {
        const val MAX = 50
        const val GLOBAL = "GLOBAL"
        private val json = Json { ignoreUnknownKeys = true }
        private val SERIALIZER = MapSerializer(String.serializer(), ListSerializer(HistoryEntry.serializer()))

        /** Video spaces (or null) share the profile's global bucket; each isolated space has its own. */
        fun scopeKey(profileId: String, space: Space?): String =
            profileId + "|" + if (space == null || space.isVideo) GLOBAL else space.name
    }
}
