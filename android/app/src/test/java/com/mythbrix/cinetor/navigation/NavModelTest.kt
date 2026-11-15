package com.mythbrix.cinetor.navigation

import com.mythbrix.cinetor.core.data.Space
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavModelTest {
    @Test
    fun videoSpaces_showFiveTabs() {
        val expected = listOf(TabKey.HOME, TabKey.ANIME, TabKey.DRAMA, TabKey.SEARCH, TabKey.ME)
        assertEquals(expected, navTabsFor(null).map { it.key })
        assertEquals(expected, navTabsFor(Space.ANIME).map { it.key })
    }

    @Test
    fun spaceSwitch_swapsNavBar() {
        for (space in Space.ISOLATED) {
            val keys = navTabsFor(space).map { it.key }
            assertTrue(TabKey.SPACE_BROWSE in keys && TabKey.SPACE_SEARCH in keys && TabKey.EXIT_SPACE in keys)
            assertFalse("global search hidden in $space", TabKey.SEARCH in keys)
            assertFalse(TabKey.HOME in keys)
        }
    }

    @Test
    fun switcher_hidesAdultUnlessEnabled() {
        assertEquals(listOf(Space.TV, Space.MUSIC, Space.COMICS), profileSpaces(adultEnabled = false))
        assertTrue(Space.ADULT in profileSpaces(adultEnabled = true))
    }

    @Test
    fun layout_bottomBarRailTv() {
        assertEquals(NavLayout.BOTTOM_BAR, navLayoutFor(411, 891, isTv = false))
        assertEquals(NavLayout.RAIL, navLayoutFor(891, 411, isTv = false))
        assertEquals(NavLayout.RAIL, navLayoutFor(1280, 800, isTv = false))
        assertEquals(NavLayout.RAIL, navLayoutFor(700, 600, isTv = false))
        assertEquals(NavLayout.BOTTOM_BAR, navLayoutFor(600, 960, isTv = false))
        assertEquals(NavLayout.TV_RAIL, navLayoutFor(960, 540, isTv = true))
    }

    @Test
    fun meScreen_listsSpaces_switcherRemoved() {
        assertFalse(TOP_BAR_HAS_SWITCHER)
        assertEquals(listOf(Space.TV, Space.MUSIC, Space.COMICS), profileSpaces(adultEnabled = false))
        assertEquals(listOf(Space.TV, Space.MUSIC, Space.COMICS, Space.ADULT), profileSpaces(adultEnabled = true))
        assertEquals("Live TV & Sports", spaceEntryLabel(Space.TV).first)
        assertEquals("Manga · Manhwa · Manhua", spaceEntryLabel(Space.COMICS).second)
        assertEquals("18+", spaceEntryLabel(Space.ADULT).first)
        // Inside an isolated space the old "Spaces" tab now returns to Me
        for (space in Space.ISOLATED) {
            assertEquals("Me", navTabsFor(space).first { it.key == TabKey.SPACES }.label)
        }
    }
}
