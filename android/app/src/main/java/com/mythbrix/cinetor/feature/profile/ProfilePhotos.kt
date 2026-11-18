package com.mythbrix.cinetor.feature.profile

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Chosen profile picture per profile id (image URL), kept outside the Room profile table so no
 * schema migration is needed. Empty = the generated shape avatar.
 */
object ProfilePhotos {
    private const val PREFS = "profile_photos"
    private var prefs: android.content.SharedPreferences? = null
    private val _photos = MutableStateFlow<Map<String, String>>(emptyMap())
    val photos: StateFlow<Map<String, String>> = _photos.asStateFlow()

    fun install(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        _photos.value = p.all.mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.toMap()
    }

    /** Sets (or with null clears) the picture of [profileId]. */
    fun set(profileId: String, url: String?) {
        _photos.value = if (url.isNullOrBlank()) _photos.value - profileId else _photos.value + (profileId to url)
        prefs?.edit()?.apply { if (url.isNullOrBlank()) remove(profileId) else putString(profileId, url) }?.apply()
    }
}

/** One pickable character picture. */
data class CharacterPic(val name: String, val imageUrl: String)

/** Picker tabs: generated built-ins, anime characters, movie & series heroes / villains. */
enum class CharacterSet(val label: String) { BUILT_IN("Built-in"), ANIME("Anime"), HEROES("Heroes") }

/**
 * Popular character pictures loaded at runtime from public character databases (no artwork is
 * bundled in the APK): MyAnimeList's top characters via Jikan, and a curated list of well-known
 * movie & series heroes / villains from the open superhero dataset. Results are memoised.
 */
class CharacterGallery(private val client: OkHttpClient) {
    private val mutex = Mutex()
    private val cache = HashMap<CharacterSet, List<CharacterPic>>()
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun load(set: CharacterSet): List<CharacterPic> {
        if (set == CharacterSet.BUILT_IN) return emptyList()
        mutex.withLock { cache[set]?.let { return it } }
        val list = try {
            withContext(Dispatchers.IO) {
                when (set) {
                    CharacterSet.ANIME -> parseJikanTopCharacters(get(JIKAN_TOP))
                    CharacterSet.HEROES -> parseHeroes(get(HEROES_ALL))
                    CharacterSet.BUILT_IN -> emptyList()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
        if (list.isNotEmpty()) mutex.withLock { cache[set] = list }
        return list
    }

    private fun get(url: String): String =
        client.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) error("HTTP ${r.code}")
            r.body?.string().orEmpty()
        }

    internal fun parseJikanTopCharacters(body: String): List<CharacterPic> {
        val data = (json.parseToJsonElement(body) as? JsonObject)?.get("data") as? JsonArray ?: return emptyList()
        return data.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val name = o["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val url = o["images"]?.jsonObject?.get("jpg")?.jsonObject?.get("image_url")?.jsonPrimitive?.contentOrNull
                ?: return@mapNotNull null
            CharacterPic(name, url)
        }.distinctBy { it.imageUrl }
    }

    internal fun parseHeroes(body: String): List<CharacterPic> {
        val byName = json.parseToJsonElement(body).jsonArray.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val name = o["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val url = o["images"]?.jsonObject?.get("md")?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            name to url
        }.toMap()
        return POPULAR_HEROES.mapNotNull { n -> byName[n]?.let { CharacterPic(n, it) } }
    }

    companion object {
        private const val JIKAN_TOP = "https://api.jikan.moe/v4/top/characters?limit=25"
        private const val HEROES_ALL = "https://cdn.jsdelivr.net/gh/akabab/superhero-api@0.3.0/api/all.json"

        /** Best-known screen heroes & villains, in display order. */
        val POPULAR_HEROES = listOf(
            "Spider-Man", "Iron Man", "Batman", "Superman", "Wonder Woman", "Captain America", "Thor", "Hulk",
            "Black Widow", "Black Panther", "Doctor Strange", "Wolverine", "Deadpool", "Joker", "Harley Quinn",
            "Loki", "Thanos", "Flash", "Aquaman", "Groot", "Scarlet Witch", "Vision", "Captain Marvel", "Venom",
        )
    }
}
