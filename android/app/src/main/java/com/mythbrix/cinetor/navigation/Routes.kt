package com.mythbrix.cinetor.navigation

import kotlinx.serialization.Serializable

// Type-safe navigation routes (navigation-compose 2.8).

@Serializable data object HomeRoute
@Serializable data object AnimeRoute
@Serializable data object DramaRoute
@Serializable data object SearchRoute
@Serializable data object MeRoute

/** Root of an isolated space (TV, Music, Comics, Adult); [space] = `Space.name`. */
@Serializable data class SpaceRoute(val space: String)

/** In-space search of an isolated space. */
@Serializable data class SpaceSearchRoute(val space: String)

/** [origin] = shared-element key of the tapped poster ("" = none → fade/scale). */
@Serializable data class DetailsRoute(val id: String, val origin: String = "")
@Serializable data object SettingsRoute
@Serializable data object AboutRoute
@Serializable data object DownloadsRoute

/** Music album (isolated Music space). */
@Serializable data class AlbumRoute(val id: String)
