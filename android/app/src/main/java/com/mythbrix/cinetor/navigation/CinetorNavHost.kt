package com.mythbrix.cinetor.navigation

import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.mythbrix.cinetor.core.data.AppContainer
import com.mythbrix.cinetor.core.data.Item
import com.mythbrix.cinetor.core.data.Space
import com.mythbrix.cinetor.core.ui.LocalNavAnimatedScope
import com.mythbrix.cinetor.core.ui.LocalSharedTransitionScope
import com.mythbrix.cinetor.design.AppBackground
import com.mythbrix.cinetor.design.Cinetor
import com.mythbrix.cinetor.design.CinetorTokens
import com.mythbrix.cinetor.design.LocalInsideHazeSource
import com.mythbrix.cinetor.design.liquidGlassButton
import com.mythbrix.cinetor.feature.about.AboutScreen
import com.mythbrix.cinetor.feature.adult.AdultGate
import com.mythbrix.cinetor.feature.adult.AdultSpace
import com.mythbrix.cinetor.feature.comics.ComicDetails
import com.mythbrix.cinetor.feature.comics.ComicIds
import com.mythbrix.cinetor.feature.comics.ComicsHome
import com.mythbrix.cinetor.feature.details.DetailsScreen
import com.mythbrix.cinetor.feature.downloads.DownloadsScreen
import com.mythbrix.cinetor.feature.home.SpaceScreen
import com.mythbrix.cinetor.feature.music.AlbumScreen
import com.mythbrix.cinetor.feature.music.MusicHome
import com.mythbrix.cinetor.feature.music.MusicMiniBarHeight
import com.mythbrix.cinetor.feature.player.HostMode
import com.mythbrix.cinetor.feature.profile.EditProfile
import com.mythbrix.cinetor.feature.profile.LibraryScreen
import com.mythbrix.cinetor.feature.profile.MeScreen
import com.mythbrix.cinetor.feature.profile.ProfilePicker
import com.mythbrix.cinetor.feature.search.SearchScreen
import com.mythbrix.cinetor.feature.settings.SettingsScreen
import com.mythbrix.cinetor.feature.tv.LiveTvScreen
import com.mythbrix.cinetor.tvui.FocusMemory
import com.mythbrix.cinetor.tvui.LocalTvRefocus
import com.mythbrix.cinetor.tvui.TvDest
import com.mythbrix.cinetor.tvui.TvHomeScreen
import com.mythbrix.cinetor.tvui.TvShell
import com.mythbrix.cinetor.tvui.space
import com.mythbrix.cinetor.tvui.tvNavEntries
import dev.chrisbanes.haze.haze
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

private val TopBarHeight = 56.dp

private val ApertureBladePaths = listOf(
    "M42.50,62.99L19.72,76.14A40,40 0,0 1,42.50,10.71L42.50,37.01Z",
    "M42.50,37.01L42.50,10.71A40,40 0,0 1,87.78,63.15L65.00,50.00Z",
    "M65.00,50.00L87.78,63.15A40,40 0,0 1,19.72,76.14L42.50,62.99Z",
)
private const val AperturePlayPath = "M65.00,50.00L42.50,62.99L42.50,37.01Z"
private val ApertureBladeColors = longArrayOf(0xFFE11D2EL, 0xFFC8102EL, 0xFFFF2D3FL)
private const val ApertureSeamWidth = 3f

/** Where the current destination puts us: a main 5-tab section, an isolated space, or "unchanged". */
private sealed interface Placement {
    data class Main(val space: Space?, val tab: TabKey?) : Placement
    data class Isolated(val space: Space, val tab: TabKey) : Placement
    data object Neutral : Placement
}

private fun placementOf(entry: NavBackStackEntry?): Placement {
    if (entry == null) return Placement.Main(null, TabKey.HOME)
    val d = entry.destination
    return when {
        d.hasRoute<HomeRoute>() -> Placement.Main(null, TabKey.HOME)
        d.hasRoute<AnimeRoute>() -> Placement.Main(null, TabKey.ANIME)
        d.hasRoute<DramaRoute>() -> Placement.Main(null, TabKey.DRAMA)
        d.hasRoute<TvRoute>() -> Placement.Main(Space.TV, TabKey.TV)
        d.hasRoute<MeRoute>() -> Placement.Main(null, TabKey.ME)
        d.hasRoute<SearchRoute>() -> Placement.Main(null, null)
        d.hasRoute<SpaceRoute>() -> {
            val sp = Space.valueOf(entry.toRoute<SpaceRoute>().space)
            if (sp == Space.TV) Placement.Main(Space.TV, TabKey.TV)
            else Placement.Isolated(sp, TabKey.SPACE_BROWSE)
        }
        d.hasRoute<SpaceSearchRoute>() -> {
            val sp = Space.valueOf(entry.toRoute<SpaceSearchRoute>().space)
            if (sp == Space.TV) Placement.Main(Space.TV, null)
            else Placement.Isolated(sp, TabKey.SPACE_SEARCH)
        }
        else -> Placement.Neutral
    }
}

private fun sectionBadgeFor(entry: NavBackStackEntry?, activeSpace: Space?, selectedTab: TabKey?): String? {
    val d = entry?.destination
    if (d != null && (d.hasRoute<SearchRoute>() || d.hasRoute<SpaceSearchRoute>())) {
        return isolatedSearchSpaceFor(activeSpace)?.let { if (it == Space.ADULT) "18+" else it.displayName }
    }
    return when {
        activeSpace == Space.ADULT -> "18+"
        activeSpace == Space.MUSIC -> "Music"
        activeSpace == Space.COMICS -> "Comics"
        activeSpace == Space.TV || selectedTab == TabKey.TV -> "TV"
        selectedTab == TabKey.ANIME -> "Anime"
        selectedTab == TabKey.DRAMA -> "Drama"
        else -> null
    }
}

private class ShellMemory(var space: Space? = null, var tab: TabKey? = TabKey.HOME)

/** App shell: Liquid Glass Cinetor Beta header + global/in-space Search icon + adaptive nav + NavHost. */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun CinetorNavHost(container: AppContainer, modifier: Modifier = Modifier) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val memory = remember { ShellMemory() }
    val placement = remember(entry) {
        when (val p = placementOf(entry)) {
            is Placement.Main -> memory.apply { space = p.space; tab = p.tab }
            is Placement.Isolated -> memory.apply { space = p.space; tab = p.tab }
            Placement.Neutral -> memory
        }.let { it.space to it.tab }
    }
    val activeSpace = placement.first
    val selectedTab = placement.second

    // Mini player / PiP → expand: return to (or open) the playing title's Details page, where the
    // player docks inline in the top slot with the same episode list.
    LaunchedEffect(nav, container.playback) {
        container.playback.openDetails.collect { id ->
            val current = nav.currentBackStackEntry
            val onIt = current?.destination?.hasRoute<DetailsRoute>() == true &&
                runCatching { current.toRoute<DetailsRoute>().id }.getOrNull() == id
            if (!onIt) nav.navigate(DetailsRoute(id)) { launchSingleTop = true }
        }
    }
    val onSearchRoute = entry?.destination?.let {
        it.hasRoute<SearchRoute>() || it.hasRoute<SpaceSearchRoute>()
    } == true

    val config = LocalConfiguration.current
    val layout = navLayoutFor(config.screenWidthDp, config.screenHeightDp, container.deviceProfile.isTv)
    // Gate state (not the raw flag): a Kids profile hides the Adult space.
    val adultEnabled by container.adult.enabled.collectAsStateWithLifecycle()

    val onTopSearch: () -> Unit = {
        val isolated = isolatedSearchSpaceFor(activeSpace)
        if (isolated != null) {
            nav.navigate(SpaceSearchRoute(isolated.name)) { launchSingleTop = true }
        } else {
            nav.navigate(SearchRoute) { launchSingleTop = true }
        }
    }

    val onTab: (TabKey) -> Unit = { key ->
        when (key) {
            TabKey.HOME -> nav.navigateTab(HomeRoute)
            TabKey.ANIME -> nav.navigateTab(AnimeRoute)
            TabKey.DRAMA -> nav.navigateTab(DramaRoute)
            TabKey.TV -> nav.navigateTab(TvRoute)
            TabKey.SEARCH -> onTopSearch()
            TabKey.ME -> nav.navigateTab(MeRoute)
            TabKey.SPACE_BROWSE -> activeSpace?.let { s ->
                if (s == Space.TV) nav.navigateTab(TvRoute)
                else if (!nav.popBackStack(SpaceRoute(s.name), inclusive = false)) nav.enterSpace(s)
            }
            TabKey.SPACE_SEARCH -> activeSpace?.let { s ->
                nav.navigate(SpaceSearchRoute(s.name)) { launchSingleTop = true }
            }
            TabKey.SPACES -> if (!nav.popBackStack(MeRoute, inclusive = false)) nav.navigateTab(MeRoute)
            TabKey.EXIT_SPACE -> if (!nav.popBackStack(HomeRoute, inclusive = false)) nav.navigateTab(HomeRoute)
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
        if (item.space == Space.COMICS) {
            nav.navigate(ComicDetailsRoute(item.id))
        } else {
            val autoPlay = key == "autoplay"
            val origin = if (autoPlay) "" else key.orEmpty()
            nav.navigate(DetailsRoute(item.id, origin = origin, autoPlay = autoPlay))
        }
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
                    screen<TvRoute> { LiveTvScreen(container, contentPadding) }
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
                            onSpace = { s ->
                                if (s == Space.TV) nav.navigateTab(TvRoute) else nav.enterSpace(s)
                            },
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
                                    DetailsScreen(
                                        r.id,
                                        r.origin,
                                        container,
                                        contentPadding,
                                        openCard,
                                        onOpenDownloads = { nav.navigate(DownloadsRoute) },
                                        autoPlay = r.autoPlay,
                                    )
                                }
                            } else {
                                DetailsScreen(
                                    r.id,
                                    r.origin,
                                    container,
                                    contentPadding,
                                    openCard,
                                    onOpenDownloads = { nav.navigate(DownloadsRoute) },
                                    autoPlay = r.autoPlay,
                                )
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
        val tvEntries = remember(adultEnabled, activeSpace) {
            tvNavEntries(adultEnabled, isolatedSearchSpaceFor(activeSpace))
        }
        // Full-screen player closed → screens restore their focus (FocusMemory) instead of losing it.
        val playerFull by remember(container) {
            container.playback.state.map { it.mode == HostMode.FULL && it.item != null }.distinctUntilChanged()
        }.collectAsStateWithLifecycle(initialValue = false)
        var refocusTick by remember { mutableIntStateOf(0) }
        LaunchedEffect(playerFull) { if (!playerFull) refocusTick++ }
        AppBackground(modifier.fillMaxSize().haze(haze)) {
            CompositionLocalProvider(
                LocalInsideHazeSource provides true,
                LocalTvRefocus provides refocusTick,
            ) {
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

    val sectionBadge = remember(entry, activeSpace, selectedTab) {
        sectionBadgeFor(entry, activeSpace, selectedTab)
    }

    Box(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxSize()) {
            if (layout != NavLayout.BOTTOM_BAR) {
                AdaptiveNavBar(layout, tabs, selectedTab, activeSpace, onTab)
            }
            Box(Modifier.weight(1f).fillMaxHeight()) {
                AppBackground(Modifier.haze(haze)) {
                    CompositionLocalProvider(LocalInsideHazeSource provides true) {
                        navHost()
                    }
                }
                GlassTopBar(
                    sectionBadge = sectionBadge,
                    searchActive = onSearchRoute,
                    onSearch = onTopSearch,
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

/**
 * Flat top bar across all Phone/Tablet sections: it sits on the app's own background (no card
 * shape, corners or border) and fades out at its lower edge so scrolling content slides under it
 * seamlessly. 3-blade red Aperture iris + `CINE` Light / `TOR` Bold (`0.34.em` tracking), optional
 * section pill, and a persistent top-right Liquid Glass `Search` button (`contentDescription = "Search"`).
 */
@Composable
private fun GlassTopBar(
    sectionBadge: String?,
    searchActive: Boolean,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = Cinetor.tokens
    val searchTint = if (searchActive) tokens.accent else tokens.textPrimary
    val searchInteraction = remember { MutableInteractionSource() }
    val flatFill = remember(tokens.bg) {
        Brush.verticalGradient(
            0f to tokens.bg.copy(alpha = 0.97f),
            0.82f to tokens.bg.copy(alpha = 0.92f),
            1f to tokens.bg.copy(alpha = 0f),
        )
    }

    Row(
        modifier
            .fillMaxWidth()
            .background(flatFill)
            .statusBarsPadding()
            .height(TopBarHeight)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.clearAndSetSemantics { contentDescription = "Cinetor" },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            HeaderApertureMark(size = 26.dp, seamColor = tokens.bg)
            Text(
                text = buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.Light)) { append("CINE") }
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("TOR") }
                },
                color = tokens.textPrimary,
                fontSize = 17.sp,
                letterSpacing = 0.34.em,
                maxLines = 1,
            )
        }

        if (sectionBadge != null) {
            Box(
                modifier = Modifier
                    .padding(start = 10.dp)
                    .clip(RoundedCornerShape(percent = 50))
                    .background(tokens.glassFill)
                    .border(CinetorTokens.Shape.strokeHairline, tokens.glassStroke, RoundedCornerShape(percent = 50))
                    .padding(horizontal = 10.dp, vertical = 3.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = sectionBadge,
                    style = MaterialTheme.typography.labelMedium,
                    color = tokens.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Spacer(Modifier.weight(1f))

        Box(
            modifier = Modifier
                .size(40.dp)
                .liquidGlassButton(CircleShape, active = searchActive, interaction = searchInteraction)
                .clickable(interactionSource = searchInteraction, indication = null, role = Role.Button, onClick = onSearch)
                .semantics { contentDescription = "Search" },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.Search,
                contentDescription = null,
                tint = searchTint,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun HeaderApertureMark(size: Dp, seamColor: Color, modifier: Modifier = Modifier) {
    val blades = remember { ApertureBladePaths.map { PathParser().parsePathString(it).toPath() } }
    val play = remember { PathParser().parsePathString(AperturePlayPath).toPath() }
    val colors = remember { ApertureBladeColors.map { Color(it) } }
    Canvas(modifier.size(size)) {
        val k = this.size.minDimension / 100f
        scale(k, k, pivot = Offset.Zero) {
            blades.forEachIndexed { i, blade ->
                drawPath(blade, colors[i])
                drawPath(blade, seamColor, style = Stroke(width = ApertureSeamWidth, join = StrokeJoin.Round))
            }
            drawPath(play, Color.White)
        }
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

private fun openCardTv(container: AppContainer, nav: NavHostController): (Item, String?) -> Unit = { item, key ->
    container.peek.put(item)
    if (item.space == Space.COMICS) {
        nav.navigate(ComicDetailsRoute(item.id))
    } else {
        val autoPlay = key == "autoplay"
        val origin = if (autoPlay) "" else key.orEmpty()
        nav.navigate(DetailsRoute(item.id, origin = origin, autoPlay = autoPlay))
    }
}

private fun openCardPhone(container: AppContainer, nav: NavHostController): (Item, String?) -> Unit = { item, key ->
    container.peek.put(item)
    if (item.space == Space.COMICS) {
        nav.navigate(ComicDetailsRoute(item.id))
    } else {
        val autoPlay = key == "autoplay"
        val origin = if (autoPlay) "" else key.orEmpty()
        nav.navigate(DetailsRoute(item.id, origin = origin, autoPlay = autoPlay))
    }
}

/** Side-nav highlight for the current destination (Details etc. keep the space they came from). */
private fun tvSelectedDest(entry: NavBackStackEntry?, activeSpace: Space?, tab: TabKey?): TvDest? {
    val d = entry?.destination
    return when {
        d == null -> TvDest.HOME
        d.hasRoute<SearchRoute>() || d.hasRoute<SpaceSearchRoute>() -> TvDest.SEARCH
        d.hasRoute<DownloadsRoute>() -> TvDest.DOWNLOADS
        d.hasRoute<LibraryRoute>() -> TvDest.LIBRARY
        d.hasRoute<SettingsRoute>() || d.hasRoute<AboutRoute>() ||
            d.hasRoute<ProfilesRoute>() || d.hasRoute<EditProfileRoute>() -> TvDest.SETTINGS
        d.hasRoute<TvRoute>() -> TvDest.LIVE_TV
        activeSpace != null -> when (activeSpace) {
            Space.TV -> TvDest.LIVE_TV
            Space.MUSIC -> TvDest.MUSIC
            Space.COMICS -> TvDest.COMICS
            Space.ADULT -> TvDest.ADULT
            Space.ANIME -> TvDest.ANIME
            Space.DRAMA -> TvDest.DRAMA
            else -> TvDest.HOME
        }
        tab == TabKey.TV -> TvDest.LIVE_TV
        tab == TabKey.ANIME -> TvDest.ANIME
        tab == TabKey.DRAMA -> TvDest.DRAMA
        else -> TvDest.HOME
    }
}

private fun NavHostController.openTvDest(dest: TvDest, activeSpace: Space?) {
    when (dest) {
        TvDest.SEARCH -> {
            val isolated = isolatedSearchSpaceFor(activeSpace)
            if (isolated != null) navigate(SpaceSearchRoute(isolated.name)) { launchSingleTop = true }
            else navigate(SearchRoute) { launchSingleTop = true }
        }
        TvDest.HOME -> navigateTab(HomeRoute)
        TvDest.ANIME -> navigateTab(AnimeRoute)
        TvDest.DRAMA -> navigateTab(DramaRoute)
        TvDest.LIVE_TV -> navigateTab(TvRoute)
        TvDest.DOWNLOADS -> navigate(DownloadsRoute) { launchSingleTop = true }
        TvDest.LIBRARY -> navigate(LibraryRoute) { launchSingleTop = true }
        TvDest.SETTINGS -> navigate(SettingsRoute) { launchSingleTop = true }
        else -> dest.space()?.let { sp -> if (sp == Space.TV) navigateTab(TvRoute) else enterSpace(sp) }
    }
}

/**
 * Switches primary tabs cleanly:
 * - If [route] is already beneath a pushed sub-screen (`Search`, `Details`, `Settings`) on the active
 *   stack, pops back directly to [route] instead of saving & re-restoring the sub-screen on top of it.
 * - Pops any transient `SearchRoute` / `SpaceSearchRoute` before `saveState = true` so switching tabs
 *   from Search never traps the previous tab on Search when returning later.
 */
private inline fun <reified T : Any> NavHostController.navigateTab(route: T) {
    if (currentBackStackEntry?.destination?.hasRoute<T>() == true) return
    if (popBackStack(route, inclusive = false)) return
    if (currentBackStackEntry?.destination?.let { it.hasRoute<SearchRoute>() || it.hasRoute<SpaceSearchRoute>() } == true) {
        popBackStack()
        if (currentBackStackEntry?.destination?.hasRoute<T>() == true) return
        if (popBackStack(route, inclusive = false)) return
    }
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/** Opened from Me → Spaces (TV: Spaces group): back returns to where it was opened (Me). */
private fun NavHostController.enterSpace(space: Space) {
    navigate(SpaceRoute(space.name)) {
        launchSingleTop = true
    }
}
