package com.mythbrix.cinetor.navigation

import kotlinx.serialization.Serializable

// Type-safe navigation routes (navigation-compose 2.8).

@Serializable data object HomeRoute
@Serializable data object AnimeRoute
@Serializable data object DramaRoute
@Serializable data object TvRoute
@Serializable data object SearchRoute
@Serializable data object MeRoute

/** Root of an isolated space (Music, Comics, Adult; also accepts TV); [space] = `Space.name`. */
@Serializable data class SpaceRoute(val space: String)

/** In-space search of an isolated space. */
@Serializable data class SpaceSearchRoute(val space: String)

/**
 * Title details route.
 * [origin] = shared-element key of the tapped poster ("" = none → fade/scale).
 * [autoPlay] = true when launched from Hero `Play` so playback starts inline inside `DetailsScreen`.
 */
@Serializable data class DetailsRoute(
    val id: String,
    val origin: String = "",
    val autoPlay: Boolean = false,
)

@Serializable data object SettingsRoute
@Serializable data object AboutRoute
@Serializable data object DownloadsRoute

/** Music album (isolated Music space). */
@Serializable data class AlbumRoute(val id: String)

/** Comic details (Task 4.3); the reader itself is an overlay above the NavHost. */
@Serializable data class ComicDetailsRoute(val id: String)

/** Profiles (Task 5.1): picker, add/edit ([id] "" = new), library of the active profile. */
@Serializable data object ProfilesRoute
@Serializable data class EditProfileRoute(val id: String = "")
@Serializable data object LibraryRoute
