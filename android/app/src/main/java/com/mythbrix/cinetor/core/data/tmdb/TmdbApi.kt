package com.mythbrix.cinetor.core.data.tmdb

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.URLEncoder
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Raw GET → body text. Abstracted so JVM tests feed JSON fixtures (no network). */
fun interface TmdbHttp {
    /** @throws IOException on transport / non-2xx. */
    suspend fun get(url: String): String
}

/** OkHttp transport with the v4 bearer token; cancelling the coroutine cancels the call. */
class OkHttpTmdbHttp(private val client: OkHttpClient, private val token: String) : TmdbHttp {
    override suspend fun get(url: String): String = suspendCancellableCoroutine { cont ->
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .build()
        val call = client.newCall(request)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { r ->
                    val result = runCatching {
                        if (!r.isSuccessful) throw IOException("TMDB HTTP ${r.code}")
                        r.body?.string() ?: throw IOException("TMDB empty body")
                    }
                    if (!cont.isActive) return
                    result.fold({ cont.resume(it) }, { cont.resumeWithException(it) })
                }
            }
        })
    }
}

@Serializable
data class TmdbResult(
    val id: Long,
    @SerialName("media_type") val mediaType: String? = null,
    val title: String? = null,
    val name: String? = null,
    @SerialName("original_title") val originalTitle: String? = null,
    @SerialName("original_name") val originalName: String? = null,
    val overview: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    @SerialName("genre_ids") val genreIds: List<Int> = emptyList(),
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("first_air_date") val firstAirDate: String? = null,
    @SerialName("vote_average") val voteAverage: Double = 0.0,
    val adult: Boolean = false,
    @SerialName("origin_country") val originCountry: List<String> = emptyList(),
    @SerialName("original_language") val originalLanguage: String? = null,
)

@Serializable
data class TmdbPage(
    val page: Int = 1,
    val results: List<TmdbResult> = emptyList(),
    @SerialName("total_pages") val totalPages: Int = 1,
)

@Serializable
data class TmdbGenre(val id: Int, val name: String = "")

@Serializable
data class TmdbCast(
    val name: String = "",
    val order: Int = 0,
    val character: String? = null,
    @SerialName("profile_path") val profilePath: String? = null,
)

@Serializable
data class TmdbCredits(val cast: List<TmdbCast> = emptyList())

@Serializable
data class TmdbSeasonSummary(
    @SerialName("season_number") val seasonNumber: Int,
    val name: String? = null,
    @SerialName("episode_count") val episodeCount: Int = 0,
)

@Serializable
data class TmdbEpisode(
    val id: Long,
    @SerialName("episode_number") val episodeNumber: Int,
    val name: String? = null,
    val overview: String? = null,
    val runtime: Int? = null,
    @SerialName("still_path") val stillPath: String? = null,
)

@Serializable
data class TmdbSeason(
    @SerialName("season_number") val seasonNumber: Int,
    val name: String? = null,
    val episodes: List<TmdbEpisode> = emptyList(),
)

@Serializable
data class TmdbProductionCompany(val name: String = "")

/** `/movie/{id}` or `/tv/{id}` with `append_to_response=credits`. */
@Serializable
data class TmdbDetails(
    val id: Long,
    val title: String? = null,
    val name: String? = null,
    @SerialName("original_title") val originalTitle: String? = null,
    @SerialName("original_name") val originalName: String? = null,
    val overview: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    val genres: List<TmdbGenre> = emptyList(),
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("first_air_date") val firstAirDate: String? = null,
    @SerialName("vote_average") val voteAverage: Double = 0.0,
    val adult: Boolean = false,
    val runtime: Int? = null,
    @SerialName("episode_run_time") val episodeRunTime: List<Int> = emptyList(),
    @SerialName("origin_country") val originCountry: List<String> = emptyList(),
    @SerialName("original_language") val originalLanguage: String? = null,
    val seasons: List<TmdbSeasonSummary> = emptyList(),
    val credits: TmdbCredits? = null,
    @SerialName("production_companies") val productionCompanies: List<TmdbProductionCompany> = emptyList(),
)

/** Thin TMDB v3 REST client. Every request carries `include_adult=false`. */
class TmdbApi(private val http: TmdbHttp) {

    suspend fun page(path: String, params: Map<String, String> = emptyMap()): TmdbPage =
        json.decodeFromString(TmdbPage.serializer(), http.get(url(path, params)))

    suspend fun details(media: String, id: Long): TmdbDetails =
        json.decodeFromString(
            TmdbDetails.serializer(),
            http.get(url("$media/$id", mapOf("append_to_response" to "credits"))),
        )

    suspend fun season(tvId: Long, n: Int): TmdbSeason =
        json.decodeFromString(TmdbSeason.serializer(), http.get(url("tv/$tvId/season/$n")))

    suspend fun searchMulti(query: String): TmdbPage =
        page("search/multi", mapOf("query" to query, "page" to "1"))

    companion object {
        const val BASE = "https://api.themoviedb.org/3/"

        val json: Json = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
            explicitNulls = false
        }

        /** Builds a request URL; `include_adult=false` is forced on every call. */
        fun url(path: String, params: Map<String, String> = emptyMap()): String {
            val all = LinkedHashMap<String, String>()
            all["language"] = "en-US"
            all.putAll(params)
            all["include_adult"] = "false"
            return BASE + path + "?" + all.entries.joinToString("&") { (k, v) -> enc(k) + "=" + enc(v) }
        }

        private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
    }
}
