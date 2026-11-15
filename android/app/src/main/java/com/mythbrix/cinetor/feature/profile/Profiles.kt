package com.mythbrix.cinetor.feature.profile

import com.mythbrix.cinetor.core.data.Item
import com.mythbrix.cinetor.core.data.Space
import com.mythbrix.cinetor.core.player.DEFAULT_PROFILE
import com.mythbrix.cinetor.core.player.ResumeEntry
import com.mythbrix.cinetor.core.player.ResumeStore
import com.mythbrix.cinetor.design.AppTheme
import com.mythbrix.cinetor.feature.adult.PinHasher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/** Generated avatar set: a shape drawn behind the initial, tinted with the profile colour. No image files. */
enum class AvatarShape { CIRCLE, SQUIRCLE, HEXAGON, DIAMOND, STAR, TICKET }

object Avatars {
    val shapes: List<AvatarShape> = AvatarShape.entries
    val colors: List<Long> = listOf(
        0xFFE50914, 0xFFFF6B35, 0xFFF5C518, 0xFF2ECC71, 0xFF1FB6FF, 0xFF7B61FF, 0xFFFF4FA3, 0xFF9AA0A6,
    )
    fun shape(index: Int): AvatarShape = shapes[Math.floorMod(index, shapes.size)]
    fun initial(name: String): String = name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
}

/** Persisted id of the profile in use. */
interface ActiveProfileStore {
    suspend fun load(): String?
    suspend fun save(id: String)
}

class InMemoryActiveProfileStore(var id: String? = null) : ActiveProfileStore {
    override suspend fun load(): String? = id
    override suspend fun save(id: String) { this.id = id }
}

sealed interface CreateResult {
    data class Ok(val profile: Profile) : CreateResult
    data object LimitReached : CreateResult
    data object InvalidName : CreateResult
    data object InvalidPin : CreateResult
}

sealed interface SelectResult {
    data object Ok : SelectResult
    data object PinRequired : SelectResult
    data object WrongPin : SelectResult
    data object Missing : SelectResult
}

/**
 * Profiles + per-profile library (Android-free, TestScope-drivable). Main profile id = [DEFAULT_PROFILE]
 * so pre-Phase-5 resume / search history stays with it. [onDeleted] wipes the profile's data held in
 * other stores (resume positions, search recents, subtitle style).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProfileManager(
    private val store: ProfileStore,
    private val activeStore: ActiveProfileStore,
    scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString().take(8) },
    private val onDeleted: suspend (String) -> Unit = {},
) {
    private val mutex = Mutex()
    private val _activeId = MutableStateFlow(MAIN_ID)
    val activeId: StateFlow<String> = _activeId.asStateFlow()

    val profiles: StateFlow<List<Profile>> = store.profiles().stateIn(scope, SharingStarted.Eagerly, emptyList())

    val active: StateFlow<Profile> = combine(profiles, _activeId) { list, id ->
        list.firstOrNull { it.id == id } ?: list.firstOrNull() ?: mainProfile()
    }.stateIn(scope, SharingStarted.Eagerly, mainProfile())

    /** Feeds the adult gate: Kids profile → adult hidden + cannot be enabled. */
    val isKids: StateFlow<Boolean> = active.map { it.kids }.stateIn(scope, SharingStarted.Eagerly, false)

    val watchlist: StateFlow<List<WatchlistItem>> = _activeId.flatMapLatest { store.watchlist(it) }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val watchlistIds: StateFlow<Set<String>> = watchlist.map { l -> l.map { it.itemId }.toSet() }
        .stateIn(scope, SharingStarted.Eagerly, emptySet())

    val history: StateFlow<List<WatchHistory>> = _activeId.flatMapLatest { store.history(it) }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** Creates the main profile on first run and restores the last active profile. */
    suspend fun load() = mutex.withLock {
        if (store.allProfiles().isEmpty()) store.upsertProfile(mainProfile())
        val saved = activeStore.load()
        val all = store.allProfiles()
        val pick = all.firstOrNull { it.id == saved } ?: all.first()
        _activeId.value = pick.id
    }

    suspend fun create(
        name: String,
        avatar: Int = 0,
        color: Long = Avatars.colors.first(),
        kids: Boolean = false,
        pin: String? = null,
    ): CreateResult = mutex.withLock {
        val clean = name.trim()
        if (clean.isEmpty() || clean.length > MAX_NAME) return CreateResult.InvalidName
        if (store.allProfiles().size >= MAX_PROFILES) return CreateResult.LimitReached
        if (pin != null && !PinHasher.isValidPin(pin)) return CreateResult.InvalidPin
        val salt = pin?.let { PinHasher.newSalt() }
        val p = Profile(
            id = newId(), name = clean, avatar = avatar, color = color, kids = kids,
            pinHash = pin?.let { PinHasher.hash(it, salt!!) }, pinSalt = salt, createdAt = clock(),
        )
        store.upsertProfile(p)
        CreateResult.Ok(p)
    }

    /** Edit name / avatar / colour / kids / languages / theme (PIN via [setPin]). */
    suspend fun update(profile: Profile): Boolean {
        val current = store.allProfiles().firstOrNull { it.id == profile.id } ?: return false
        val clean = profile.name.trim()
        if (clean.isEmpty() || clean.length > MAX_NAME) return false
        store.upsertProfile(profile.copy(name = clean, pinHash = current.pinHash, pinSalt = current.pinSalt))
        return true
    }

    /** null clears the PIN. */
    suspend fun setPin(id: String, pin: String?): Boolean {
        val p = store.allProfiles().firstOrNull { it.id == id } ?: return false
        if (pin == null) {
            store.upsertProfile(p.copy(pinHash = null, pinSalt = null))
            return true
        }
        if (!PinHasher.isValidPin(pin)) return false
        val salt = PinHasher.newSalt()
        store.upsertProfile(p.copy(pinHash = PinHasher.hash(pin, salt), pinSalt = salt))
        return true
    }

    /** Deletes [id] and all its data. The last profile cannot be deleted; deleting the active one switches. */
    suspend fun delete(id: String): Boolean = mutex.withLock {
        val all = store.allProfiles()
        if (all.size <= 1 || all.none { it.id == id }) return false
        store.deleteProfile(id)
        runCatching { onDeleted(id) }
        if (_activeId.value == id) {
            val next = store.allProfiles().first()
            _activeId.value = next.id
            activeStore.save(next.id)
        }
        true
    }

    /** Switch profile; PIN-protected profiles need [pin]. */
    suspend fun select(id: String, pin: String? = null): SelectResult {
        val p = store.allProfiles().firstOrNull { it.id == id } ?: return SelectResult.Missing
        if (p.id != _activeId.value && p.hasPin()) {
            if (pin == null) return SelectResult.PinRequired
            if (!PinHasher.matches(pin, com.mythbrix.cinetor.feature.adult.PinRecord(p.pinHash!!, p.pinSalt!!))) {
                return SelectResult.WrongPin
            }
        }
        _activeId.value = p.id
        activeStore.save(p.id)
        return SelectResult.Ok
    }

    suspend fun toggleWatchlist(item: Item) {
        val pid = _activeId.value
        if (store.allWatchlist(pid).any { it.itemId == item.id }) {
            store.removeWatchlist(pid, item.id)
        } else {
            store.addWatchlist(
                WatchlistItem(pid, item.id, item.space, item.title, item.poster, item.backdrop, clock()),
            )
        }
    }

    /** Called on every resume save. History keeps video spaces only (isolation rule: TV/Music/Comics/Adult never). */
    suspend fun recordHistory(entry: ResumeEntry) {
        if (!entry.space.isVideo) return
        store.upsertHistory(
            WatchHistory(
                profileId = entry.profileId, space = entry.space, itemId = entry.itemId, pos = entry.positionMs,
                durationMs = entry.durationMs, updatedAt = entry.updatedAt, title = entry.title,
                poster = entry.poster, backdrop = entry.backdrop, episodeLabel = entry.episodeLabel,
            ),
        )
    }

    suspend fun clearHistory(profileId: String = _activeId.value) = store.clearHistory(profileId)

    suspend fun setTheme(theme: AppTheme?) {
        val p = store.allProfiles().firstOrNull { it.id == _activeId.value } ?: return
        store.upsertProfile(p.copy(theme = theme?.name))
    }

    companion object {
        const val MAX_PROFILES = 5
        const val MAX_NAME = 20
        const val MAIN_ID = DEFAULT_PROFILE
        fun mainProfile(): Profile = Profile(id = MAIN_ID, name = "Main", avatar = 0, color = Avatars.colors.first())
    }
}

/** Kids profiles can never turn on the 18+ section. */
fun canEnableAdult(profile: Profile): Boolean = !profile.kids

/** Effective theme: profile override else app default. */
fun themeFor(profile: Profile?, appDefault: AppTheme): AppTheme =
    profile?.theme?.let { n -> AppTheme.entries.firstOrNull { it.name == n } } ?: appDefault

/**
 * Home "Continue Watching" from a profile's history: HOME/ANIME/DRAMA only (never isolated spaces),
 * unfinished, started, newest first, one per item.
 */
fun continueWatching(history: List<WatchHistory>, space: Space? = null): List<WatchHistory> =
    history.asSequence()
        .filter { it.space.isVideo && (space == null || it.space == space) }
        .sortedByDescending { it.updatedAt }
        .distinctBy { it.itemId }
        .filter { !it.isFinished() && it.pos > 0 }
        .toList()

/** Resume store that also writes each save into the active profile's Room history. */
class HistoryRecordingResumeStore(
    private val inner: ResumeStore,
    private val onSave: suspend (ResumeEntry) -> Unit,
) : ResumeStore by inner {
    override suspend fun save(entry: ResumeEntry) {
        inner.save(entry)
        runCatching { onSave(entry) }
    }
}
