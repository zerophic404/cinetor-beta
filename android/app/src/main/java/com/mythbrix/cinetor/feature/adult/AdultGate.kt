package com.mythbrix.cinetor.feature.adult

import com.mythbrix.cinetor.core.data.Item
import com.mythbrix.cinetor.core.data.Space
import com.mythbrix.cinetor.core.data.gateway.GatewayLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
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
}

/** Kids profile flag — stub (always off) until profiles land in Task 5.1. */
object KidsProfileStub {
    val active: MutableStateFlow<Boolean> = MutableStateFlow(false)
}

/**
 * Adult gate (Android-free, TestScope-drivable). No PIN: the section is either off (default, hidden
 * everywhere) or on after a one-time 18+ confirmation in Settings, and it is never available on a
 * Kids profile. [unlocked] simply mirrors [enabled] for callers that gate content on the session;
 * [onSession] mirrors it to the download engine (adult downloads hidden while off).
 */
class AdultGate(
    private val store: AdultStore,
    scope: CoroutineScope,
    private val kidsProfile: StateFlow<Boolean> = KidsProfileStub.active,
    private val onSession: (Boolean) -> Unit = {},
) {
    /** Adult section offered — never on a Kids profile. */
    val enabled: StateFlow<Boolean> = combine(store.enabled, kidsProfile) { e, kids -> e && !kids }
        .stateIn(scope, SharingStarted.Eagerly, false)

    /** Same as [enabled]: with no PIN there is no separate locked state. */
    val unlocked: StateFlow<Boolean> get() = enabled

    init {
        onSession(false)
        scope.launch { enabled.collect { onSession(it) } }
    }

    /** Settings toggle availability. */
    val canEnable: Boolean get() = !kidsProfile.value

    suspend fun isEnabled(): Boolean = store.enabled.first() && !kidsProfile.value

    /** Turns the section on once the user confirms they are 18+. Clears any PIN left by older versions. */
    suspend fun enable(ageConfirmed: Boolean): EnableResult {
        if (kidsProfile.value) return EnableResult.KidsProfile
        if (!ageConfirmed) return EnableResult.AgeNotConfirmed
        store.setPin(null)
        store.setAttempts(PinAttempts())
        store.setEnabled(true)
        return EnableResult.Ok
    }

    /** Turns the section off (hidden everywhere again). */
    suspend fun disable() {
        store.setEnabled(false)
        store.setPin(null)
        store.setAttempts(PinAttempts())
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
