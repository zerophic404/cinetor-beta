package com.mythbrix.cinetor.core.data

import kotlin.math.abs

/**
 * Attaches mock test-stream sources to metadata-only items (TMDB). No real providers — ever.
 * Picks are deterministic per id so the same title always plays the same stream.
 */
object MockSourceProvider {
    private val STREAMS: List<List<Source>> = listOf(
        listOf(
            Source("https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8", SourceKind.HLS, "Auto · HLS"),
            Source("https://vjs.zencdn.net/v/oceans.mp4", SourceKind.MP4, "Server 2"),
        ),
        listOf(
            Source(
                "https://demo.unified-streaming.com/k8s/features/stable/video/tears-of-steel/tears-of-steel.ism/.m3u8",
                SourceKind.HLS,
                "Multi-audio · HLS",
            ),
            Source("https://media.w3.org/2010/05/sintel/trailer.mp4", SourceKind.MP4, "Server 2"),
        ),
        listOf(
            Source("https://storage.googleapis.com/shaka-demo-assets/angel-one/dash.mpd", SourceKind.DASH, "DASH"),
            Source("https://media.w3.org/2010/05/bunny/trailer.mp4", SourceKind.MP4, "Server 2"),
        ),
    )

    fun sourcesFor(id: String): List<Source> = STREAMS[abs(id.hashCode() % STREAMS.size)]

    /** Movies get item-level sources; every episode of a series gets its own. */
    fun attach(item: Item): Item = when {
        item.seasons.isNotEmpty() -> item.copy(
            seasons = item.seasons.map { s ->
                s.copy(episodes = s.episodes.map { e -> if (e.sources.isEmpty()) e.copy(sources = sourcesFor(e.id)) else e })
            },
        )
        item.sources.isEmpty() -> item.copy(sources = sourcesFor(item.id))
        else -> item
    }
}
