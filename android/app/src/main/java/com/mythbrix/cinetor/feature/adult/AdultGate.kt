package com.mythbrix.cinetor.feature.adult

import com.mythbrix.cinetor.core.data.Item
import com.mythbrix.cinetor.core.data.Space
import com.mythbrix.cinetor.core.data.gateway.GatewayLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.security.MessageDigest
import java.security.SecureRandom

/** Category/sort filter pills for the Adult space (`For You`, `Hottest`, `Latest`, `Top Rated`). */
enum class AdultSortChip(val label: String, val sortParam: String?) {
    FOR_YOU("For You", null),
    HOTTEST("Hottest", "hottest"),
    LATEST("Latest", "latest"),
    TOP_RATED("Top Rated", "rating"),
}

/** Content kind tabs within Adult browse (`Movies`, `Series`). */
enum class AdultKindFilter(val label: String, val kindParam: String) {
    MOVIES("Movies", "movies"),
    SERIES("Series", "tv-series"),
}

/** Builds a gateway-routed Adult catalog row id compatible with [GatewayLayout.parseRow]. */
fun adultGatewayRowId(kind: AdultKindFilter, sort: AdultSortChip): String {
    val sortKey = sort.sortParam ?: "latest"
    return "${GatewayLayout.ROW_PREFIX}${Space.ADULT.name}:adult?kind=${kind.kindParam}&sort=$sortKey"
}

/** Salted SHA-256 PIN hashing. The PIN itself is never stored. */
object PinHasher {
    const val PIN_LENGTH = 4
    private const val ROUNDS = 5_000

    fun isValidPin(pin: String): Boolean = pin.length == PIN_LENGTH && pin.all { it in '0'..'9' }

    fun newSalt(random: SecureRandom = SecureRandom()): String =
        ByteArray(16).also(random::nextBytes).toHex()

    /** hex(SHA-256 iterated [ROUNDS]× over salt ‖ ':' ‖ pin) — iterations slow down offline guessing. */
    fun hash(pin: String, salt: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        var h = md.digest("$salt:$pin".toByteArray(Charsets.UTF_8))
        repeat(ROUNDS - 1) {
            md.reset()
            md.update(salt.toByteArray(Charsets.UTF_8))
            h = md.digest(h)
        }
        return h.toHex()
    }

    /** Constant-time compare. */
    fun matches(pin: String, record: PinRecord): Boolean =
        MessageDigest.isEqual(hash(pin, record.salt).toByteArray(), record.hash.toByteArray())

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}

data class PinRecord(val hash: String, val salt: String)

/** Wrong-PIN counter + lockout end (persisted so killing the app does not reset it). */
data class PinAttempts(val failed: Int = 0, val lockoutUntilMs: Long = 0)

/** Persistence for the gate (DataStore in the app, in-memory in tests). */
interface AdultStore {
    val enabled: Flow<Boolean>
    suspend fun setEnabled(on: Boolean)
    suspend fun pin(): PinRecord?
    suspend fun setPin(record: PinRecord?)
    suspend fun attempts(): PinAttempts
    suspend fun setAttempts(attempts: PinAttempts)
}

class InMemoryAdultStore : AdultStore {
    private val _enabled = MutableStateFlow(false)
    override val enabled: Flow<Boolean> = _enabled
    var record: PinRecord? = null
    var tries = PinAttempts()
    override suspend fun setEnabled(on: Boolean) { _enabled.value = on }
    override suspend fun pin(): PinRecord? = record
    override suspend fun setPin(record: PinRecord?) { this.record = record }
    override suspend fun attempts(): PinAttempts = tries
    override suspend fun setAttempts(attempts: PinAttempts) { tries = attempts }
}

sealed interface EnableResult {
    data object Ok : EnableResult
    data object KidsProfile : EnableResult
    data object AgeNotConfirmed : EnableResult
    data object InvalidPin : EnableResult
    data object PinMismatch : EnableResult
}

sealed interface UnlockResult {
    data object Ok : UnlockResult
    data class Wrong(val remaining: Int) : UnlockResult
    data class LockedOut(val untilMs: Long) : UnlockResult
    data object NotEnabled : UnlockResult
}

/** Kids profile flag — stub (always off) until profiles land in Task 5.1. */
object KidsProfileStub {
    val active: MutableStateFlow<Boolean> = MutableStateFlow(false)
}

/**
 * Adult gate (Android-free, TestScope-drivable).
 * - Off by default; enabling needs 18+ confirmation + a 4-digit PIN (salted hash) and is refused on Kids profiles.
 * - The unlocked session lives in memory only → every app process ("session") starts locked; it re-locks after
 *   [backgroundTimeoutMs] in the background.
 * - [maxAttempts] wrong PINs → [lockoutMs] lockout.
 * - [onSession] mirrors the unlocked state to the download engine (adult downloads hidden while locked).
 */
class AdultGate(
    private val store: AdultStore,
    scope: CoroutineScope,
    private val kidsProfile: StateFlow<Boolean> = KidsProfileStub.active,
    private val clock: () -> Long = System::currentTimeMillis,
    private val onSession: (Boolean) -> Unit = {},
    private val backgroundTimeoutMs: Long = 5 * 60_000L,
    val maxAttempts: Int = 5,
    private val lockoutMs: Long = 30_000L,
) {
    /** Adult space offered (switcher) — never on a Kids profile. */
    val enabled: StateFlow<Boolean> = combine(store.enabled, kidsProfile) { e, kids -> e && !kids }
        .stateIn(scope, SharingStarted.Eagerly, false)

    private val _unlocked = MutableStateFlow(false)
    val unlocked: StateFlow<Boolean> = _unlocked.asStateFlow()

    private var backgroundAt: Long? = null

    init {
        onSession(false)
        scope.launch { kidsProfile.collect { kids -> if (kids) lock() } }
    }

    /** Settings toggle availability. */
    val canEnable: Boolean get() = !kidsProfile.value

    suspend fun isEnabled(): Boolean = store.enabled.first() && !kidsProfile.value

    suspend fun enable(ageConfirmed: Boolean, pin: String, confirmPin: String = pin): EnableResult {
        if (kidsProfile.value) return EnableResult.KidsProfile
        if (!ageConfirmed) return EnableResult.AgeNotConfirmed
        if (!PinHasher.isValidPin(pin)) return EnableResult.InvalidPin
        if (pin != confirmPin) return EnableResult.PinMismatch
        val salt = PinHasher.newSalt()
        store.setPin(PinRecord(PinHasher.hash(pin, salt), salt))
        store.setAttempts(PinAttempts())
        store.setEnabled(true)
        lock()
        return EnableResult.Ok
    }

    /** Turns the section off and forgets the PIN. */
    suspend fun disable() {
        lock()
        store.setEnabled(false)
        store.setPin(null)
        store.setAttempts(PinAttempts())
    }

    /** Remaining lockout in ms (0 = none). */
    suspend fun lockoutRemainingMs(): Long = (store.attempts().lockoutUntilMs - clock()).coerceAtLeast(0)

    /** Checks [pin] without opening the session (e.g. confirm before disabling). Counts toward lockout. */
    suspend fun verify(pin: String): UnlockResult {
        if (!isEnabled()) return UnlockResult.NotEnabled
        val now = clock()
        val a = store.attempts()
        if (a.lockoutUntilMs > now) return UnlockResult.LockedOut(a.lockoutUntilMs)
        val record = store.pin() ?: return UnlockResult.NotEnabled
        if (PinHasher.matches(pin, record)) {
            if (a != PinAttempts()) store.setAttempts(PinAttempts())
            return UnlockResult.Ok
        }
        val failed = a.failed + 1
        return if (failed >= maxAttempts) {
            val until = now + lockoutMs
            store.setAttempts(PinAttempts(0, until))
            UnlockResult.LockedOut(until)
        } else {
            store.setAttempts(PinAttempts(failed, 0))
            UnlockResult.Wrong(maxAttempts - failed)
        }
    }

    suspend fun unlock(pin: String): UnlockResult {
        val r = verify(pin)
        if (r == UnlockResult.Ok) setUnlocked(true)
        return r
    }

    fun lock() = setUnlocked(false)

    fun onBackground() {
        backgroundAt = clock()
    }

    fun onForeground() {
        val since = backgroundAt ?: return
        backgroundAt = null
        if (clock() - since >= backgroundTimeoutMs) lock()
    }

    private fun setUnlocked(value: Boolean) {
        val changed = _unlocked.value != value
        _unlocked.value = value
        if (changed) onSession(value)
    }

    companion object {
        /** Mock adult ids (`ad-001`…) and gateway adult ids (`ad-gw:…`). */
        fun isAdultId(id: String): Boolean = id.startsWith("ad-")
        fun isAdult(item: Item?): Boolean = item?.space == Space.ADULT

        /** Hard safety screen ported from Cinetor Beta `AdultCatalog.safeName`. */
        fun isSafeAdultTitle(name: String): Boolean {
            val trimmed = name.trim()
            return trimmed.isNotEmpty() && !BLOCKED_TITLE_TERMS.containsMatchIn(trimmed)
        }

        private val BLOCKED_TITLE_TERMS = Regex(
            """(?i)\b(teen\w*|school\w*|college girl|student\w*|tuition|beti|bachi|underage|minors?|enjo|first (time|pain)|lolita|loli|shota|child\w*|kids?|young girls?|virgin|step[- ]?(daughter|son|sister|brother|mom|mother)|rape\w*|forced?|drugged|sleeping|unconscious|incest|dog|animal\w*|bestial\w*|anime|animation|hentai|cartoon|leak\w*|mms|hidden cam\w*|spy cam\w*)\b""",
        )
    }
}
