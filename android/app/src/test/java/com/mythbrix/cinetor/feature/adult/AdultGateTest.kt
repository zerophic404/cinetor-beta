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
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AdultGateTest {
    private val sessions = mutableListOf<Boolean>()

    private fun TestScope.gate(
        store: AdultStore,
        kids: MutableStateFlow<Boolean> = MutableStateFlow(false),
    ) = AdultGate(store, backgroundScope, kidsProfile = kids, onSession = { sessions += it })

    @Test
    fun adult_hiddenByDefault() = runTest {
        val g = gate(InMemoryAdultStore())
        runCurrent()
        assertFalse(g.enabled.value)
        assertFalse(g.isEnabled())
        assertFalse(g.unlocked.value)
        assertFalse(Space.ADULT in profileSpaces(g.enabled.value))
        // Download engine told "off" from the start (adult downloads hidden).
        assertEquals(false, sessions.last())
    }

    @Test
    fun adult_opensRightAfterAgeConfirmation_noPin() = runTest {
        val store = InMemoryAdultStore()
        val g = gate(store)
        assertEquals(EnableResult.AgeNotConfirmed, g.enable(ageConfirmed = false))
        runCurrent()
        assertFalse(g.enabled.value)

        assertEquals(EnableResult.Ok, g.enable(ageConfirmed = true))
        runCurrent()
        assertTrue(g.enabled.value)
        assertTrue("no PIN: on means open", g.unlocked.value)
        assertTrue(Space.ADULT in profileSpaces(g.enabled.value))
        assertEquals(true, sessions.last())
        assertEquals(null, store.record)

        // A fresh process (new gate on the same store) is open straight away too.
        val again = gate(store)
        runCurrent()
        assertTrue(again.unlocked.value)

        g.disable()
        runCurrent()
        assertFalse(g.enabled.value)
        assertFalse(g.unlocked.value)
        assertEquals(false, sessions.last())
    }

    @Test
    fun enabling_clearsAPinLeftByOlderVersions() = runTest {
        val store = InMemoryAdultStore()
        store.setPin(PinRecord(PinHasher.hash("1234", "salt"), "salt"))
        store.setAttempts(PinAttempts(3, 0))
        val g = gate(store)
        assertEquals(EnableResult.Ok, g.enable(ageConfirmed = true))
        assertEquals(null, store.record)
        assertEquals(PinAttempts(), store.tries)
    }

    @Test
    fun kidsProfile_cannotEnable() = runTest {
        val store = InMemoryAdultStore()
        val kids = MutableStateFlow(true)
        val g = gate(store, kids)
        runCurrent()
        assertFalse(g.canEnable)
        assertEquals(EnableResult.KidsProfile, g.enable(ageConfirmed = true))
        assertFalse(g.enabled.value)

        // Enabled on an adult profile, then switching to Kids hides it.
        kids.value = false
        runCurrent()
        assertEquals(EnableResult.Ok, g.enable(ageConfirmed = true))
        runCurrent()
        assertTrue(g.enabled.value)
        kids.value = true
        runCurrent()
        assertFalse(g.enabled.value)
        assertFalse(g.unlocked.value)
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
