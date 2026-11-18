package com.mythbrix.cinetor.navigation

import com.mythbrix.cinetor.core.data.Space

/** Pure navigation model (JVM-testable): which tabs/layout/search target to show. */
enum class TabKey {
    HOME,
    ANIME,
    DRAMA,
    TV,
    SEARCH,
    ME,
    SPACE_BROWSE,
    SPACE_SEARCH,
    SPACES,
    EXIT_SPACE,
}

data class TabSpec(val key: TabKey, val label: String)

val VideoTabs: List<TabSpec> = listOf(
    TabSpec(TabKey.HOME, "Home"),
    TabSpec(TabKey.ANIME, "Anime"),
    TabSpec(TabKey.DRAMA, "Drama"),
    TabSpec(TabKey.TV, "TV"),
    TabSpec(TabKey.ME, "Me"),
)

/**
 * Main video sections + Live TV/Sports share the 5-tab bar (`Home · Anime · Drama · TV · Me`);
 * isolated `MUSIC`, `COMICS`, and `ADULT` swap in their own space navigation bar.
 */
fun navTabsFor(activeSpace: Space?): List<TabSpec> {
    if (activeSpace == null || activeSpace.isVideo || activeSpace == Space.TV) return VideoTabs
    val browse = when (activeSpace) {
        Space.COMICS -> "Library"
        else -> "Browse"
    }
    return listOf(
        TabSpec(TabKey.SPACE_BROWSE, browse),
        TabSpec(TabKey.SPACE_SEARCH, "Search"),
        TabSpec(TabKey.SPACES, "Me"),
        TabSpec(TabKey.EXIT_SPACE, "Video"),
    )
}

/**
 * Isolated spaces listed in `Me → Spaces`: `TV` moved into primary navigation (`VideoTabs`),
 * and `18+` (`Space.ADULT`) is hidden unless [adultEnabled] is `true`.
 */
fun profileSpaces(adultEnabled: Boolean): List<Space> =
    Space.ISOLATED.filter { it != Space.TV && (it != Space.ADULT || adultEnabled) }

/**
 * Target space for the persistent top-right `Search` icon:
 * returns the isolated space (`MUSIC`, `COMICS`, `ADULT`) for `SpaceSearchRoute`,
 * or `null` when global `SearchRoute` applies (`HOME`, `ANIME`, `DRAMA`, `TV`, `ME`).
 */
fun isolatedSearchSpaceFor(activeSpace: Space?): Space? =
    activeSpace?.takeIf { !it.isVideo && it != Space.TV }

@Deprecated("Top spaces switcher removed; spaces live in Me", ReplaceWith("profileSpaces(adultEnabled)"))
fun switcherSpaces(adultEnabled: Boolean): List<Space> = profileSpaces(adultEnabled)

/** The top logo no longer opens anything (switcher removed). */
const val TOP_BAR_HAS_SWITCHER = false

/** Label + subtitle of a space entry in Me → Spaces. */
fun spaceEntryLabel(space: Space): Pair<String, String> = when (space) {
    Space.TV -> "Live TV & Sports" to "Channels, guide, live events"
    Space.MUSIC -> "Music" to "Albums, tracks, queue"
    Space.COMICS -> "Comics" to "Manga · Manhwa · Manhua"
    Space.ADULT -> "18+" to "Movies & series for adults"
    else -> space.displayName to ""
}

enum class NavLayout { BOTTOM_BAR, RAIL, TV_RAIL }

/** Bottom bar on phones; rail on expanded width or landscape tablets; TV rail on TVs. */
fun navLayoutFor(widthDp: Int, heightDp: Int, isTv: Boolean): NavLayout = when {
    isTv -> NavLayout.TV_RAIL
    widthDp >= 840 -> NavLayout.RAIL
    widthDp >= 600 && widthDp > heightDp -> NavLayout.RAIL
    else -> NavLayout.BOTTOM_BAR
}
