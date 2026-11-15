package com.mythbrix.cinetor.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.runtime.CompositionLocalProvider
import androidx.navigation.NavGraphBuilder
import com.mythbrix.cinetor.core.data.Item
import com.mythbrix.cinetor.core.ui.LocalNavAnimatedScope
import com.mythbrix.cinetor.core.ui.LocalSharedTransitionScope
import com.mythbrix.cinetor.design.CinetorTokens
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.mythbrix.cinetor.core.data.AppContainer
import com.mythbrix.cinetor.core.data.Space
import com.mythbrix.cinetor.design.AppBackground
import com.mythbrix.cinetor.design.Cinetor
import com.mythbrix.cinetor.design.CinetorMark
import com.mythbrix.cinetor.design.GlassLevel
import com.mythbrix.cinetor.design.glass
import com.mythbrix.cinetor.feature.about.AboutScreen
import com.mythbrix.cinetor.feature.adult.AdultGate
import com.mythbrix.cinetor.feature.adult.AdultSpace
import com.mythbrix.cinetor.feature.comics.ComicDetails
import com.mythbrix.cinetor.feature.comics.ComicIds
import com.mythbrix.cinetor.feature.comics.ComicsHome
import com.mythbrix.cinetor.feature.details.DetailsScreen
import com.mythbrix.cinetor.feature.downloads.DownloadsScreen
import com.mythbrix.cinetor.feature.home.SpaceScreen
import com.mythbrix.cinetor.feature.profile.EditProfile
import com.mythbrix.cinetor.feature.profile.LibraryScreen
import com.mythbrix.cinetor.feature.profile.MeScreen
import com.mythbrix.cinetor.feature.profile.ProfilePicker
import com.mythbrix.cinetor.feature.search.SearchScreen
import com.mythbrix.cinetor.feature.settings.SettingsScreen
import dev.chrisbanes.haze.haze
import com.mythbrix.cinetor.feature.music.AlbumScreen
import com.mythbrix.cinetor.feature.music.MusicHome
import com.mythbrix.cinetor.feature.music.MusicMiniBarHeight
import com.mythbrix.cinetor.feature.tv.LiveTvScreen
import kotlinx.coroutines.flow.distinctUntilChanged
import com.mythbrix.cinetor.tvui.FocusMemory
import com.mythbrix.cinetor.tvui.LocalTvRefocus
import com.mythbrix.cinetor.feature.player.HostMode
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import com.mythbrix.cinetor.tvui.TvDest
import com.mythbrix.cinetor.tvui.TvHomeScreen
import com.mythbrix.cinetor.tvui.TvShell
import com.mythbrix.cinetor.tvui.space
import com.mythbrix.cinetor.tvui.tvNavEntries
import kotlinx.coroutines.flow.map

private val TopBarHeight = 56.dp

/** Where the current destination puts us: a video tab, an isolated space, or "unchanged". */
private sealed interface Placement {
    data class Video(val tab: TabKey) : Placement
    data class Isolated(val space: Space, val tab: TabKey) : Placement
    data object Neutral : Placement
}

private fun placementOf(entry: NavBackStackEntry?): Placement {
    if (entry == null) return Placement.Video(TabKey.HOME)
    val d = entry.destination
    return when {
        d.hasRoute<HomeRoute>() -> Placement.Video(TabKey.HOME)
        d.hasRoute<AnimeRoute>() -> Placement.Video(TabKey.ANIME)
        d.hasRoute<DramaRoute>() -> Placement.Video(TabKey.DRAMA)
        d.hasRoute<SearchRoute>() -> Placement.Video(TabKey.SEARCH)
        d.hasRoute<MeRoute>() -> Placement.Video(TabKey.ME)
        d.hasRoute<SpaceRoute>() ->
            Placement.Isolated(Space.valueOf(entry.toRoute<SpaceRoute>().space), TabKey.SPACE_BROWSE)
        d.hasRoute<SpaceSearchRoute>() ->
            Placement.Isolated(Space.valueOf(entry.toRoute<SpaceSearchRoute>().space), TabKey.SPACE_SEARCH)
        else -> Placement.Neutral
    }
}

private class ShellMemory(var space: Space? = null, var tab: TabKey? = TabKey.HOME)

/** App shell: glass top bar + adaptive nav (bottom bar / rail / TV rail) + spaces switcher + NavHost. */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun CinetorNavHost(container: AppContainer, modifier: Modifier = Modifier) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val memory = remember { ShellMemory() }
    val placement = remember(entry) {
        when (val p = placementOf(entry)) {
            is Placement.Video -> memory.apply { space = null; tab = p.tab }
            is Placement.Isolated -> memory.apply { space = p.space; tab = p.tab }
            Placement.Neutral -> memory
        }.let { it.space to it.tab }
    }
    val activeSpace = placement.first
    val selectedTab = placement.second

    val config = LocalConfiguration.current
    val layout = navLayoutFor(config.screenWidthDp, config.screenHeightDp, container.deviceProfile.isTv)
    // Gate state (not the raw flag): a Kids profile hides the Adult space.
    val adultEnabled by container.adult.enabled.collectAsStateWithLifecycle()

    val onTab: (TabKey) -> Unit = { key ->
        when (key) {
            TabKey.HOME -> nav.navigateTab(HomeRoute)
            TabKey.ANIME -> nav.navigateTab(AnimeRoute)
            TabKey.DRAMA -> nav.navigateTab(DramaRoute)
            TabKey.SEARCH -> nav.navigateTab(SearchRoute)
            TabKey.ME -> nav.navigateTab(MeRoute)
            TabKey.SPACE_BROWSE -> activeSpace?.let { s ->
                if (!nav.popBackStack(SpaceRoute(s.name), inclusive = false)) nav.enterSpace(s)
            }
            TabKey.SPACE_SEARCH -> activeSpace?.let { s ->
                nav.navigate(SpaceSearchRoute(s.name)) { launchSingleTop = true }
            }
            TabKey.SPACES -> nav.navigateTab(MeRoute)
            TabKey.EXIT_SPACE -> nav.navigateTab(HomeRoute)
        }
    }

    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val musicActive by remember(container) { container.music.state.map { it.active }.distinctUntilChanged() }
        .collectAsStateWithLifecycle(initialValue = false)
    val isTv = layout == NavLayout.TV_RAIL
    // TV: the shell already applies 5% overscan margins and there is no top bar.
    val contentPadding = if (isTv) {
        PaddingValues(top = 8.dp, bottom = 24.dp + (if (musicActive) MusicMiniBarHeight else 0.dp))
    } else {
        PaddingValues(
            top = statusTop + TopBarHeight + 8.dp,
            bottom = navBottom + (if (layout == NavLayout.BOTTOM_BAR) BottomBarHeight + 24.dp else 16.dp) +
                (if (musicActive) MusicMiniBarHeight else 0.dp),
        )
    }
    // Last focused card per TV screen; lives above the NavHost so it survives Details / player / back.
    val focusMemory = remember { FocusMemory() }
    val videoSpace: @Composable (Space) -> Unit = { sp ->
        if (isTv) TvHomeScreen(sp, container, focusMemory, openCardTv(container, nav))
        else SpaceScreen(sp, container, contentPadding, openCardPhone(container, nav))
    }
    val haze = Cinetor.haze
    val tabs = navTabsFor(activeSpace)
    val openItem: (String) -> Unit = { id ->
        if (ComicIds.isComic(id)) nav.navigate(ComicDetailsRoute(id)) else nav.navigate(DetailsRoute(id))
    }
    val openCard: (Item, String?) -> Unit = { item, key ->
        container.peek.put(item)
        if (item.space == Space.COMICS) nav.navigate(ComicDetailsRoute(item.id))
        else nav.navigate(DetailsRoute(item.id, key.orEmpty()))
    }
    val exitAdult: () -> Unit = { nav.navigateTab(HomeRoute) }

    val navHost: @Composable () -> Unit = {
        SharedTransitionLayout(Modifier.fillMaxSize()) {
            CompositionLocalProvider(LocalSharedTransitionScope provides this) {
                NavHost(
                    navController = nav,
                    startDestination = HomeRoute,
                    enterTransition = { fadeIn(tween(220)) },
                    exitTransition = { fadeOut(tween(160)) },
                ) {
                    screen<HomeRoute> { videoSpace(Space.HOME) }
                    screen<AnimeRoute> { videoSpace(Space.ANIME) }
                    screen<DramaRoute> { videoSpace(Space.DRAMA) }
                    screen<SearchRoute> { SearchScreen(null, container, contentPadding, openItem) }
                    screen<MeRoute> {
                        MeScreen(
                            container, contentPadding,
                            onSettings = { nav.navigate(SettingsRoute) },
                            onAbout = { nav.navigate(AboutRoute) },
                            onDownloads = { nav.navigate(DownloadsRoute) },
                            onProfiles = { nav.navigate(ProfilesRoute) },
                            onLibrary = { nav.navigate(LibraryRoute) },
                            spaces = profileSpaces(adultEnabled),
                            onSpace = { s -> nav.enterSpace(s) },
                        )
                    }
                    screen<ProfilesRoute> {
                        ProfilePicker(
                            container.profiles, contentPadding,
                            onEdit = { nav.navigate(EditProfileRoute(it)) },
                            onAdd = { nav.navigate(EditProfileRoute()) },
                            onDone = { nav.popBackStack() },
                        )
                    }
                    screen<EditProfileRoute> { e ->
                        EditProfile(container.profiles, e.toRoute<EditProfileRoute>().id, contentPadding) { nav.popBackStack() }
                    }
                    screen<LibraryRoute> {
                        LibraryScreen(container.profiles, contentPadding, openItem, onClearHistory = { container.clearHistory() })
                    }
                    screen<SpaceRoute> { e ->
                        when (val sp = Space.valueOf(e.toRoute<SpaceRoute>().space)) {
                            Space.TV -> LiveTvScreen(container, contentPadding)
                            Space.MUSIC -> MusicHome(container, contentPadding, onOpenAlbum = { nav.navigate(AlbumRoute(it)) })
                            Space.COMICS -> ComicsHome(container, contentPadding, openCard)
                            Space.ADULT -> AdultSpace(container.adult, contentPadding, exitAdult) {
                                videoSpace(sp)
                            }
                            else -> videoSpace(sp)
                        }
                    }
                    screen<SpaceSearchRoute> { e ->
                        val sp = Space.valueOf(e.toRoute<SpaceSearchRoute>().space)
                        val open: (String) -> Unit = when (sp) {
                            Space.TV -> { id -> container.playTvById(id) }
                            Space.MUSIC -> { id -> if (id.startsWith("mu-al")) nav.navigate(AlbumRoute(id)) else container.music.playById(id) }
                            else -> openItem
                        }
                        if (sp == Space.ADULT) {
                            AdultSpace(container.adult, contentPadding, exitAdult) {
                                SearchScreen(sp, container, contentPadding, open)
                            }
                        } else {
                            SearchScreen(sp, container, contentPadding, open)
                        }
                    }
                    screen<AlbumRoute> { e -> AlbumScreen(e.toRoute<AlbumRoute>().id, container, contentPadding) }
                    screen<ComicDetailsRoute> { e -> ComicDetails(e.toRoute<ComicDetailsRoute>().id, container, contentPadding) }
                    composable<DetailsRoute>(
                        enterTransition = {
                            fadeIn(tween(260)) + scaleIn(tween(320, easing = CinetorTokens.Motion.emphasized), initialScale = 0.94f)
                        },
                        popExitTransition = {
                            fadeOut(tween(200)) + scaleOut(tween(220), targetScale = 0.96f)
                        },
                    ) { e ->
                        CompositionLocalProvider(LocalNavAnimatedScope provides this) {
                            val r = e.toRoute<DetailsRoute>()
                            if (AdultGate.isAdultId(r.id)) {
                                AdultSpace(container.adult, contentPadding, exitAdult) {
                                    DetailsScreen(r.id, r.origin, container, contentPadding, openCard)
                                }
                            } else {
                                DetailsScreen(r.id, r.origin, container, contentPadding, openCard)
                            }
                        }
                    }
                    screen<SettingsRoute> {
                        SettingsScreen(
                            container, contentPadding,
                            onProfiles = { nav.navigate(ProfilesRoute) },
                            onAbout = { nav.navigate(AboutRoute) },
                        )
                    }
                    screen<AboutRoute> { AboutScreen(contentPadding) }
                    screen<DownloadsRoute> {
                        DownloadsScreen(container.downloads, contentPadding, openItem, onPlay = { container.playDownload(it) })
                    }
                }
            }
        }
    }

    if (isTv) {
        val tvEntries = remember(adultEnabled, activeSpace) { tvNavEntries(adultEnabled, activeSpace) }
        // Full-screen player closed → screens restore their focus (FocusMemory) instead of losing it.
        val playerFull by remember(container) {
            container.playback.state.map { it.mode == HostMode.FULL && it.item != null }.distinctUntilChanged()
        }.collectAsStateWithLifecycle(initialValue = false)
        var refocusTick by remember { mutableIntStateOf(0) }
        LaunchedEffect(playerFull) { if (!playerFull) refocusTick++ }
        AppBackground(modifier.fillMaxSize().haze(haze)) {
            CompositionLocalProvider(LocalTvRefocus provides refocusTick) {
                TvShell(
                    entries = tvEntries,
                    selected = tvSelectedDest(entry, activeSpace, selectedTab),
                    routeKey = entry?.id,
                    refocusKey = refocusTick,
                    onPick = { dest -> nav.openTvDest(dest, activeSpace) },
                    onMenu = { nav.navigate(SettingsRoute) { launchSingleTop = true } },
                ) { navHost() }
            }
        }
        return
    }

    Box(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxSize()) {
            if (layout != NavLayout.BOTTOM_BAR) {
                AdaptiveNavBar(layout, tabs, selectedTab, activeSpace, onTab)
            }
            Box(Modifier.weight(1f).fillMaxHeight()) {
                AppBackground(Modifier.haze(haze)) { navHost() }
                GlassTopBar(
                    title = activeSpace?.displayName ?: "Cinetor",
                    modifier = Modifier.align(Alignment.TopCenter),
                )
                if (layout == NavLayout.BOTTOM_BAR) {
                    AdaptiveNavBar(
                        layout, tabs, selectedTab, activeSpace, onTab,
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }
            }
        }
    }
}

@Composable
private fun GlassTopBar(title: String, modifier: Modifier = Modifier) {
    val tokens = Cinetor.tokens
    Row(
        modifier
            .fillMaxWidth()
            .glass(RoundedCornerShape(bottomStart = 20.dp, bottomEnd = 20.dp), GlassLevel.BAR)
            .statusBarsPadding()
            .height(TopBarHeight)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Plain brand mark: the spaces switcher moved into Me → Spaces.
        CinetorMark(Modifier.padding(4.dp).size(36.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, color = tokens.textPrimary)
    }
}

/** composable<T> that exposes its AnimatedVisibilityScope for shared elements. */
private inline fun <reified T : Any> NavGraphBuilder.screen(
    noinline content: @Composable AnimatedContentScope.(NavBackStackEntry) -> Unit,
) {
    composable<T> { entry ->
        CompositionLocalProvider(LocalNavAnimatedScope provides this) { content(entry) }
    }
}

private fun openCardTv(container: AppContainer, nav: NavHostController): (Item, String?) -> Unit = { item, _ ->
    container.peek.put(item)
    if (item.space == Space.COMICS) nav.navigate(ComicDetailsRoute(item.id)) else nav.navigate(DetailsRoute(item.id, ""))
}

private fun openCardPhone(container: AppContainer, nav: NavHostController): (Item, String?) -> Unit = { item, key ->
    container.peek.put(item)
    if (item.space == Space.COMICS) nav.navigate(ComicDetailsRoute(item.id))
    else nav.navigate(DetailsRoute(item.id, key.orEmpty()))
}

/** Side-nav highlight for the current destination (Details etc. keep the space they came from). */
private fun tvSelectedDest(entry: NavBackStackEntry?, activeSpace: Space?, tab: TabKey?): TvDest? {
    val d = entry?.destination
    return when {
        d == null -> TvDest.HOME
        d.hasRoute<SearchRoute>() || d.hasRoute<SpaceSearchRoute>() -> TvDest.SEARCH
        d.hasRoute<DownloadsRoute>() -> TvDest.DOWNLOADS
        d.hasRoute<LibraryRoute>() -> TvDest.LIBRARY
        d.hasRoute<ProfilesRoute>() || d.hasRoute<EditProfileRoute>() -> TvDest.PROFILES
        d.hasRoute<SettingsRoute>() || d.hasRoute<AboutRoute>() -> TvDest.SETTINGS
        activeSpace != null -> when (activeSpace) {
            Space.TV -> TvDest.LIVE_TV
            Space.MUSIC -> TvDest.MUSIC
            Space.COMICS -> TvDest.COMICS
            Space.ADULT -> TvDest.ADULT
            Space.ANIME -> TvDest.ANIME
            Space.DRAMA -> TvDest.DRAMA
            else -> TvDest.HOME
        }
        tab == TabKey.ANIME -> TvDest.ANIME
        tab == TabKey.DRAMA -> TvDest.DRAMA
        else -> TvDest.HOME
    }
}

private fun NavHostController.openTvDest(dest: TvDest, activeSpace: Space?) {
    when (dest) {
        TvDest.SEARCH ->
            if (activeSpace != null && !activeSpace.isVideo) navigate(SpaceSearchRoute(activeSpace.name)) { launchSingleTop = true }
            else navigateTab(SearchRoute)
        TvDest.HOME -> navigateTab(HomeRoute)
        TvDest.ANIME -> navigateTab(AnimeRoute)
        TvDest.DRAMA -> navigateTab(DramaRoute)
        TvDest.DOWNLOADS -> navigate(DownloadsRoute) { launchSingleTop = true }
        TvDest.LIBRARY -> navigate(LibraryRoute) { launchSingleTop = true }
        TvDest.PROFILES -> navigate(ProfilesRoute) { launchSingleTop = true }
        TvDest.SETTINGS -> navigate(SettingsRoute) { launchSingleTop = true }
        else -> dest.space()?.let { enterSpace(it) }
    }
}

private fun NavHostController.navigateTab(route: Any) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/** Opened from Me → Spaces (TV: Profile group): back returns to where it was opened (Me). */
private fun NavHostController.enterSpace(space: Space) {
    navigate(SpaceRoute(space.name)) {
        launchSingleTop = true
    }
}
