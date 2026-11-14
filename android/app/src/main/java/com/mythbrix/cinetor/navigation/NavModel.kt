package com.mythbrix.cinetor.navigation

import com.mythbrix.cinetor.core.data.Space

/** Pure navigation model (JVM-testable): which tabs/layout to show. */
enum class TabKey { HOME, ANIME, DRAMA, SEARCH, ME, SPACE_BROWSE, SPACE_SEARCH, SPACES, EXIT_SPACE }

data class TabSpec(val key: TabKey, val label: String)

val VideoTabs: List<TabSpec> = listOf(
    TabSpec(TabKey.HOME, "Home"),
    TabSpec(TabKey.ANIME, "Anime"),
    TabSpec(TabKey.DRAMA, "Drama"),
    TabSpec(TabKey.SEARCH, "Search"),
    TabSpec(TabKey.ME, "Me"),
)

/** Video spaces → Home · Anime · Drama · Search · Me; an isolated space swaps in its own tabs. */
fun navTabsFor(activeSpace: Space?): List<TabSpec> {
    if (activeSpace == null || activeSpace.isVideo) return VideoTabs
    val browse = when (activeSpace) {
        Space.TV -> "Live"
        Space.COMICS -> "Library"
        else -> "Browse"
    }
    return listOf(
        TabSpec(TabKey.SPACE_BROWSE, browse),
        TabSpec(TabKey.SPACE_SEARCH, "Search"),
        TabSpec(TabKey.SPACES, "Spaces"),
        TabSpec(TabKey.EXIT_SPACE, "Video"),
    )
}

/** Isolated spaces offered by the switcher; Adult only when enabled. */
fun switcherSpaces(adultEnabled: Boolean): List<Space> =
    Space.ISOLATED.filter { it != Space.ADULT || adultEnabled }

enum class NavLayout { BOTTOM_BAR, RAIL, TV_RAIL }

/** Bottom bar on phones; rail on expanded width or landscape tablets; TV rail on TVs. */
fun navLayoutFor(widthDp: Int, heightDp: Int, isTv: Boolean): NavLayout = when {
    isTv -> NavLayout.TV_RAIL
    widthDp >= 840 -> NavLayout.RAIL
    widthDp >= 600 && widthDp > heightDp -> NavLayout.RAIL
    else -> NavLayout.BOTTOM_BAR
}
