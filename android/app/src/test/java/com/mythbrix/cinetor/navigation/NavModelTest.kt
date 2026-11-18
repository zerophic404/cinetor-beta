package com.mythbrix.cinetor.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.LiveTv
import com.mythbrix.cinetor.core.data.Space
import com.mythbrix.cinetor.core.perf.PerformanceTier
import com.mythbrix.cinetor.design.AppTheme
import com.mythbrix.cinetor.design.GlassLevel
import com.mythbrix.cinetor.design.glassAllowsLiveBlur
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavModelTest {
    @Test
    fun videoTabs_replacesSearchWithTvInMainFiveTabs() {
        val expectedKeys = listOf(TabKey.HOME, TabKey.ANIME, TabKey.DRAMA, TabKey.TV, TabKey.ME)
        val expectedLabels = listOf("Home", "Anime", "Drama", "TV", "Me")
        assertEquals(expectedKeys, VideoTabs.map { it.key })
        assertEquals(expectedLabels, VideoTabs.map { it.label })
        assertFalse(TabKey.SEARCH in VideoTabs.map { it.key })
        assertEquals(Icons.Rounded.LiveTv, iconFor(TabKey.TV, null))
    }

    @Test
    fun navTabsFor_videoAndTvKeepConsistentMainFiveTabs() {
        assertEquals(VideoTabs, navTabsFor(null))
        assertEquals(VideoTabs, navTabsFor(Space.HOME))
        assertEquals(VideoTabs, navTabsFor(Space.ANIME))
        assertEquals(VideoTabs, navTabsFor(Space.DRAMA))
        assertEquals(VideoTabs, navTabsFor(Space.TV))
    }

    @Test
    fun navTabsFor_isolatedSpacesSwapNavBar() {
        val isolatedNonTv = listOf(Space.MUSIC, Space.COMICS, Space.ADULT)
        for (space in isolatedNonTv) {
            val tabs = navTabsFor(space)
            val keys = tabs.map { it.key }
            assertTrue(TabKey.SPACE_BROWSE in keys && TabKey.SPACE_SEARCH in keys && TabKey.EXIT_SPACE in keys)
            assertFalse("global search hidden in $space", TabKey.SEARCH in keys)
            assertFalse(TabKey.HOME in keys)
            assertFalse(TabKey.TV in keys)
            assertEquals("Me", tabs.first { it.key == TabKey.SPACES }.label)
            assertEquals("Video", tabs.first { it.key == TabKey.EXIT_SPACE }.label)
        }
    }

    @Test
    fun profileSpaces_removesTvAndGatesAdultByFlag() {
        assertFalse(TOP_BAR_HAS_SWITCHER)
        assertEquals(listOf(Space.MUSIC, Space.COMICS), profileSpaces(adultEnabled = false))
        assertEquals(listOf(Space.MUSIC, Space.COMICS, Space.ADULT), profileSpaces(adultEnabled = true))
        assertFalse(Space.TV in profileSpaces(adultEnabled = false))
        assertFalse(Space.TV in profileSpaces(adultEnabled = true))
        assertEquals("Manga · Manhwa · Manhua", spaceEntryLabel(Space.COMICS).second)
        assertEquals("18+", spaceEntryLabel(Space.ADULT).first)
    }

    @Test
    fun isolatedSearchSpaceFor_routesTopRightSearchCleanly() {
        assertNull(isolatedSearchSpaceFor(null))
        assertNull(isolatedSearchSpaceFor(Space.HOME))
        assertNull(isolatedSearchSpaceFor(Space.ANIME))
        assertNull(isolatedSearchSpaceFor(Space.DRAMA))
        assertNull(isolatedSearchSpaceFor(Space.TV))
        assertEquals(Space.MUSIC, isolatedSearchSpaceFor(Space.MUSIC))
        assertEquals(Space.COMICS, isolatedSearchSpaceFor(Space.COMICS))
        assertEquals(Space.ADULT, isolatedSearchSpaceFor(Space.ADULT))
    }

    @Test
    fun detailsRoute_supportsOptionalAutoPlayForInlinePlayback() {
        val standard = DetailsRoute(id = "gw:movie:1")
        assertEquals("", standard.origin)
        assertFalse(standard.autoPlay)

        val heroAutoPlay = DetailsRoute(id = "gw:movie:1", autoPlay = true)
        assertEquals("", heroAutoPlay.origin)
        assertTrue(heroAutoPlay.autoPlay)
    }

    @Test
    fun glassAllowsLiveBlur_neverBlursInsideHazeSource() {
        for (tier in PerformanceTier.entries) {
            for (level in GlassLevel.entries) {
                for (theme in AppTheme.entries) {
                    assertFalse(
                        "inside haze source must never call hazeChild ($tier/$level/$theme)",
                        glassAllowsLiveBlur(insideHazeSource = true, tier = tier, level = level, theme = theme),
                    )
                }
            }
        }
        assertTrue(glassAllowsLiveBlur(insideHazeSource = false, tier = PerformanceTier.HIGH, level = GlassLevel.BAR, theme = AppTheme.GLASS))
        assertFalse(glassAllowsLiveBlur(insideHazeSource = false, tier = PerformanceTier.HIGH, level = GlassLevel.BAR, theme = AppTheme.MATTE))
        assertFalse(glassAllowsLiveBlur(insideHazeSource = false, tier = PerformanceTier.LOW, level = GlassLevel.BAR, theme = AppTheme.GLASS))
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
}
