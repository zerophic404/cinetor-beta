package com.mythbrix.cinetor.feature.profile

import com.mythbrix.cinetor.core.data.Item
import com.mythbrix.cinetor.core.data.ItemType
import com.mythbrix.cinetor.core.data.Space
import com.mythbrix.cinetor.core.player.ResumeEntry
import com.mythbrix.cinetor.feature.adult.AdultGate
import com.mythbrix.cinetor.feature.adult.EnableResult
import com.mythbrix.cinetor.feature.adult.InMemoryAdultStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProfilesTest {
    private var now = 1_000L
    private var ids = 0

    private fun TestScope.manager(
        store: ProfileStore = InMemoryProfileStore(),
        deleted: MutableList<String> = mutableListOf(),
    ) = ProfileManager(
        store, InMemoryActiveProfileStore(), backgroundScope,
        clock = { now++ }, newId = { "p${++ids}" }, onDeleted = { deleted += it },
    )

    private fun item(id: String, space: Space) = Item(
        id = id, space = space, categoryId = "", type = ItemType.VIDEO, title = id, year = 2024, rating = 0.0,
        maturity = "", genres = emptyList(), synopsis = "", poster = "", backdrop = "",
        dominantColor = "#000000", accentColor = "#000000",
    )

    private fun resume(profile: String, id: String, space: Space, pos: Long = 30_000, at: Long = now++) =
        ResumeEntry(profile, id, null, pos, 100_000, at, space, title = id)

    @Test
    fun continueWatching_onlyVideoSpaces() = runTest {
        val history = listOf(
            WatchHistory("a", Space.HOME, "h1", 10_000, 100_000, 10),
            WatchHistory("a", Space.ANIME, "a1", 10_000, 100_000, 20),
            WatchHistory("a", Space.DRAMA, "d1", 10_000, 100_000, 30),
            WatchHistory("a", Space.TV, "tv1", 10_000, 100_000, 40),
            WatchHistory("a", Space.MUSIC, "mu1", 10_000, 100_000, 50),
            WatchHistory("a", Space.COMICS, "co1", 10_000, 100_000, 60),
            WatchHistory("a", Space.ADULT, "ad1", 10_000, 100_000, 70),
            WatchHistory("a", Space.HOME, "done", 99_000, 100_000, 80),
        )
        val cw = continueWatching(history)
        assertEquals(listOf("d1", "a1", "h1"), cw.map { it.itemId })
        assertTrue(cw.all { it.space.isVideo })
        assertEquals(listOf("a1"), continueWatching(history, Space.ANIME).map { it.itemId })

        // History recording itself never stores isolated spaces.
        val store = InMemoryProfileStore()
        val m = manager(store)
        m.load()
        m.recordHistory(resume(ProfileManager.MAIN_ID, "h2", Space.HOME))
        m.recordHistory(resume(ProfileManager.MAIN_ID, "ad2", Space.ADULT))
        m.recordHistory(resume(ProfileManager.MAIN_ID, "tv2", Space.TV))
        assertEquals(listOf("h2"), store.allHistory(ProfileManager.MAIN_ID).map { it.itemId })
    }

    @Test
    fun deleteProfile_removesItsData() = runTest {
        val store = InMemoryProfileStore()
        val deleted = mutableListOf<String>()
        val m = manager(store, deleted)
        m.load()
        val kid = (m.create("Kid") as CreateResult.Ok).profile
        // Kid's data
        assertEquals(SelectResult.Ok, m.select(kid.id))
        m.toggleWatchlist(item("x1", Space.HOME))
        m.recordHistory(resume(kid.id, "x2", Space.DRAMA))
        // Main's data
        assertEquals(SelectResult.Ok, m.select(ProfileManager.MAIN_ID))
        m.toggleWatchlist(item("m1", Space.ANIME))
        m.recordHistory(resume(ProfileManager.MAIN_ID, "m2", Space.HOME))
        assertEquals(SelectResult.Ok, m.select(kid.id))
        runCurrent()

        assertTrue(m.delete(kid.id))
        runCurrent()
        assertTrue(store.allHistory(kid.id).isEmpty())
        assertTrue(store.allWatchlist(kid.id).isEmpty())
        assertEquals(listOf(kid.id), deleted) // resume / search recents hook
        assertFalse(store.allProfiles().any { it.id == kid.id })
        // Active falls back to a remaining profile; other profile's data untouched.
        assertEquals(ProfileManager.MAIN_ID, m.activeId.value)
        assertEquals(listOf("m1"), store.allWatchlist(ProfileManager.MAIN_ID).map { it.itemId })
        assertEquals(listOf("m2"), store.allHistory(ProfileManager.MAIN_ID).map { it.itemId })
        assertEquals(setOf("m1"), m.watchlistIds.value)
        // Last profile can never be deleted.
        assertFalse(m.delete(ProfileManager.MAIN_ID))
    }

    @Test
    fun maxFiveProfiles() = runTest {
        val store = InMemoryProfileStore()
        val m = manager(store)
        m.load()
        repeat(4) { i -> assertTrue(m.create("P$i") is CreateResult.Ok) }
        assertEquals(ProfileManager.MAX_PROFILES, store.allProfiles().size)
        assertEquals(CreateResult.LimitReached, m.create("Sixth"))
        assertEquals(5, store.allProfiles().size)
        assertEquals(CreateResult.InvalidName, manager(InMemoryProfileStore()).create("  "))
    }

    @Test
    fun kidsProfile_cannotEnableAdult() = runTest {
        val m = manager()
        m.load()
        val kid = (m.create("Kiddo", kids = true) as CreateResult.Ok).profile
        val adultStore = InMemoryAdultStore()
        val gate = AdultGate(adultStore, backgroundScope, kidsProfile = m.isKids)
        runCurrent()
        assertFalse(canEnableAdult(kid))
        assertTrue(canEnableAdult(ProfileManager.mainProfile()))

        assertEquals(SelectResult.Ok, m.select(kid.id))
        runCurrent()
        assertTrue(m.isKids.value)
        assertFalse(gate.canEnable)
        assertEquals(EnableResult.KidsProfile, gate.enable(ageConfirmed = true))
        assertFalse(gate.enabled.value)

        // Enabled on the main profile → still hidden while the Kids profile is active.
        assertEquals(SelectResult.Ok, m.select(ProfileManager.MAIN_ID))
        runCurrent()
        assertEquals(EnableResult.Ok, gate.enable(ageConfirmed = true))
        runCurrent()
        assertTrue(gate.enabled.value)
        assertEquals(SelectResult.Ok, m.select(kid.id))
        runCurrent()
        assertFalse(gate.enabled.value)
    }

    @Test
    fun profilePin_requiredToSwitch() = runTest {
        val m = manager()
        m.load()
        val p = (m.create("Locked", pin = "4321") as CreateResult.Ok).profile
        assertTrue(p.hasPin())
        assertEquals(SelectResult.PinRequired, m.select(p.id))
        assertEquals(SelectResult.WrongPin, m.select(p.id, "0000"))
        assertEquals(ProfileManager.MAIN_ID, m.activeId.value)
        assertEquals(SelectResult.Ok, m.select(p.id, "4321"))
        assertEquals(p.id, m.activeId.value)
    }

    @Test
    fun historyRecordingResumeStore_writesProfileHistory() = runTest {
        val store = InMemoryProfileStore()
        val m = manager(store)
        m.load()
        val inner = com.mythbrix.cinetor.core.player.ResumeRepository(com.mythbrix.cinetor.core.data.InMemoryHistoryStorage())
        val resumeStore = HistoryRecordingResumeStore(inner) { m.recordHistory(it) }
        resumeStore.save(resume(ProfileManager.MAIN_ID, "h9", Space.HOME))
        assertEquals("h9", inner.latest(ProfileManager.MAIN_ID, "h9")?.itemId)
        assertEquals(listOf("h9"), store.allHistory(ProfileManager.MAIN_ID).map { it.itemId })
        resumeStore.clearProfile(ProfileManager.MAIN_ID)
        assertEquals(null, inner.latest(ProfileManager.MAIN_ID, "h9"))
    }
}
