package com.mythbrix.cinetor.feature.adult

import com.mythbrix.cinetor.core.data.InMemoryHistoryStorage
import com.mythbrix.cinetor.core.data.Item
import com.mythbrix.cinetor.core.data.ItemType
import com.mythbrix.cinetor.core.data.SearchHistory
import com.mythbrix.cinetor.core.data.Source
import com.mythbrix.cinetor.core.data.SourceKind
import com.mythbrix.cinetor.core.data.Space
import com.mythbrix.cinetor.core.player.ResumeEntry
import com.mythbrix.cinetor.core.player.SourceResolver
import com.mythbrix.cinetor.core.player.continueEntries
import com.mythbrix.cinetor.core.player.progressByItem
import com.mythbrix.cinetor.navigation.profileSpaces
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AdultGateTest {
    private var now = 1_000_000L
    private val sessions = mutableListOf<Boolean>()

    private fun TestScope.gate(
        store: AdultStore,
        kids: MutableStateFlow<Boolean> = MutableStateFlow(false),
    ) = AdultGate(store, backgroundScope, kidsProfile = kids, clock = { now }, onSession = { sessions += it })

    @Test
    fun adult_hiddenByDefault() = runTest {
        val g = gate(InMemoryAdultStore())
        runCurrent()
        assertFalse(g.enabled.value)
        assertFalse(g.isEnabled())
        assertFalse(g.unlocked.value)
        assertFalse(Space.ADULT in profileSpaces(g.enabled.value))
        assertEquals(UnlockResult.NotEnabled, g.unlock("1234"))
        // Download engine told "locked" from the start (adult downloads hidden).
        assertEquals(listOf(false), sessions)
    }

    @Test
    fun adult_requiresPinEachSession() = runTest {
        val store = InMemoryAdultStore()
        val g = gate(store)
        assertEquals(EnableResult.Ok, g.enable(ageConfirmed = true, pin = "1234"))
        runCurrent()
        assertTrue(g.enabled.value)
        assertTrue(Space.ADULT in profileSpaces(g.enabled.value))
        assertFalse("enabling does not open the session", g.unlocked.value)
        assertEquals(UnlockResult.Ok, g.unlock("1234"))
        assertTrue(g.unlocked.value)
        assertEquals(true, sessions.last())

        // Short background keeps the session; ≥ 5 min re-locks.
        g.onBackground(); now += 60_000; g.onForeground()
        assertTrue(g.unlocked.value)
        g.onBackground(); now += 5 * 60_000; g.onForeground()
        assertFalse(g.unlocked.value)
        assertEquals(false, sessions.last())

        // New process = new session: starts locked even though the section stays enabled.
        assertEquals(UnlockResult.Ok, g.unlock("1234"))
        val next = gate(store)
        runCurrent()
        assertTrue(next.enabled.value)
        assertFalse(next.unlocked.value)
        assertEquals(UnlockResult.Ok, next.unlock("1234"))
        assertTrue(next.unlocked.value)
    }

    @Test
    fun pin_hashNotPlaintext() = runTest {
        val store = InMemoryAdultStore()
        val g = gate(store)
        g.enable(ageConfirmed = true, pin = "4821")
        val rec = store.record!!
        assertFalse(rec.hash.contains("4821"))
        assertFalse(rec.salt.contains("4821") && rec.salt.length == 4)
        assertEquals(64, rec.hash.length)
        assertTrue(rec.hash.all { it in "0123456789abcdef" })
        assertTrue(rec.salt.length >= 32)
        assertEquals(rec.hash, PinHasher.hash("4821", rec.salt))
        // Salted: same PIN set again → different salt + hash.
        g.enable(ageConfirmed = true, pin = "4821")
        assertNotEquals(rec.hash, store.record!!.hash)
        assertNotEquals(rec.salt, store.record!!.salt)
        assertTrue(PinHasher.matches("4821", store.record!!))
        assertFalse(PinHasher.matches("4822", store.record!!))
        // Bad input rejected.
        assertEquals(EnableResult.InvalidPin, g.enable(true, "12a4"))
        assertEquals(EnableResult.InvalidPin, g.enable(true, "123"))
        assertEquals(EnableResult.PinMismatch, g.enable(true, "1234", "1235"))
        assertEquals(EnableResult.AgeNotConfirmed, g.enable(false, "1234"))
    }

    @Test
    fun lockout_after5Wrong() = runTest {
        val store = InMemoryAdultStore()
        val g = gate(store)
        g.enable(true, "1234")
        for (i in 1..4) assertEquals(UnlockResult.Wrong(5 - i), g.unlock("0000"))
        val locked = g.unlock("0000")
        assertTrue(locked is UnlockResult.LockedOut)
        assertEquals(now + 30_000, (locked as UnlockResult.LockedOut).untilMs)
        // Even the right PIN is refused during the lockout (and it survives a new session).
        assertTrue(g.unlock("1234") is UnlockResult.LockedOut)
        assertTrue(gate(store).unlock("1234") is UnlockResult.LockedOut)
        assertFalse(g.unlocked.value)
        now += 29_000
        assertTrue(g.unlock("1234") is UnlockResult.LockedOut)
        now += 1_000
        assertEquals(UnlockResult.Ok, g.unlock("1234"))
        assertEquals(PinAttempts(), store.tries)
    }

    @Test
    fun kidsProfile_cannotEnable() = runTest {
        val store = InMemoryAdultStore()
        val kids = MutableStateFlow(true)
        val g = gate(store, kids)
        runCurrent()
        assertFalse(g.canEnable)
        assertEquals(EnableResult.KidsProfile, g.enable(ageConfirmed = true, pin = "1234"))
        assertEquals(null, store.record)
        assertFalse(g.enabled.value)

        // Enabled on an adult profile, then switching to Kids hides + locks it.
        kids.value = false
        runCurrent()
        assertEquals(EnableResult.Ok, g.enable(true, "1234"))
        assertEquals(UnlockResult.Ok, g.unlock("1234"))
        runCurrent()
        assertTrue(g.enabled.value)
        kids.value = true
        runCurrent()
        assertFalse(g.enabled.value)
        assertFalse(g.unlocked.value)
        assertEquals(UnlockResult.NotEnabled, g.unlock("1234"))
    }

    @Test
    fun adult_notInContinueWatching() = runTest {
        fun entry(id: String, space: Space, t: Long) =
            ResumeEntry("default", id, null, positionMs = 60_000, durationMs = 600_000, updatedAt = t, space = space, title = id)
        val entries = listOf(entry("ad-001", Space.ADULT, 3), entry("m1", Space.HOME, 2), entry("ad-002", Space.ADULT, 1))
        assertEquals(listOf("m1"), continueEntries(entries, Space.HOME).map { it.itemId })
        assertTrue(continueEntries(entries, Space.ADULT).isEmpty())
        Space.VIDEO.forEach { s -> assertTrue(continueEntries(entries, s).none { it.space == Space.ADULT }) }
        assertTrue(progressByItem(entries).containsKey("m1"))

        // No recents for adult in-space search.
        val history = SearchHistory(InMemoryHistoryStorage())
        history.record("default", "late night", Space.ADULT)
        history.record("default", "late night", null)
        assertTrue(history.entries("default", Space.ADULT).isEmpty())
        assertEquals(listOf("late night"), history.entries("default", null))

        // Notification / MediaSession metadata never carries the adult title.
        val adult = Item(
            id = "ad-001", space = Space.ADULT, categoryId = "ad-featured", type = ItemType.VIDEO, title = "Late Night Feature 01",
            year = 2020, rating = 7.0, maturity = "18+", genres = emptyList(), synopsis = "", poster = "p", backdrop = "b",
            dominantColor = "#000000", accentColor = "#000000", durationSec = 60,
            sources = listOf(Source("https://example.com/a.mp4", SourceKind.MP4)),
        )
        assertEquals("Private video", SourceResolver.request(adult, null)?.title)
        assertEquals("Private video", SourceResolver.notificationTitle(adult))
        assertEquals("Movie", SourceResolver.notificationTitle(adult.copy(space = Space.HOME, title = "Movie")))
    }
}
