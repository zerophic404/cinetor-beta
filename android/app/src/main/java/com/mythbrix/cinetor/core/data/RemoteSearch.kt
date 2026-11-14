package com.mythbrix.cinetor.core.data

/** Network title search (TMDB `/search/multi`). Implementations return ONLY video-space items, never adult. */
fun interface RemoteSearch {
    suspend fun search(query: String): List<Item>
}
