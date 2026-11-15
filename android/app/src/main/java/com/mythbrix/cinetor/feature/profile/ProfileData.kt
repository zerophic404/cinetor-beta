package com.mythbrix.cinetor.feature.profile

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert
import com.mythbrix.cinetor.core.data.Space
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/** A viewer profile (max 5). Avatar = index into [Avatars]; PIN = salted hash (never the PIN itself). */
@Entity(tableName = "profile")
data class Profile(
    @PrimaryKey val id: String,
    val name: String,
    val avatar: Int = 0,
    val color: Long = 0xFFE50914,
    val kids: Boolean = false,
    val pinHash: String? = null,
    val pinSalt: String? = null,
    /** AppTheme name; null = app default. */
    val theme: String? = null,
    /** Preferred subtitle / audio language (ISO 639-1); null = app default. */
    val subtitleLang: String? = null,
    val audioLang: String? = null,
    val createdAt: Long = 0,
)

fun Profile.hasPin(): Boolean = pinHash != null && pinSalt != null

/** Watched item of a profile (one row per profile + space + item; latest position). */
@Entity(tableName = "history", primaryKeys = ["profileId", "space", "itemId"])
data class WatchHistory(
    val profileId: String,
    val space: Space,
    val itemId: String,
    val pos: Long,
    val durationMs: Long = 0,
    val updatedAt: Long,
    val title: String = "",
    val poster: String = "",
    val backdrop: String = "",
    val episodeLabel: String? = null,
)

/** Extensions (not properties) so Room never sees them as columns. */
fun WatchHistory.isFinished(): Boolean = durationMs > 0 && pos >= durationMs * 95 / 100
fun WatchHistory.progress(): Float = if (durationMs > 0) (pos.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

/** "My List" entry of a profile. */
@Entity(tableName = "watchlist", primaryKeys = ["profileId", "itemId"])
data class WatchlistItem(
    val profileId: String,
    val itemId: String,
    val space: Space,
    val title: String = "",
    val poster: String = "",
    val backdrop: String = "",
    val addedAt: Long = 0,
)

/** Profile persistence (Room in the app, [InMemoryProfileStore] in tests). */
interface ProfileStore {
    fun profiles(): Flow<List<Profile>>
    suspend fun allProfiles(): List<Profile>
    suspend fun upsertProfile(profile: Profile)

    /** Removes the profile with its history + watchlist. */
    suspend fun deleteProfile(id: String)
    fun history(profileId: String): Flow<List<WatchHistory>>
    suspend fun allHistory(profileId: String): List<WatchHistory>
    suspend fun upsertHistory(entry: WatchHistory)
    suspend fun clearHistory(profileId: String)
    fun watchlist(profileId: String): Flow<List<WatchlistItem>>
    suspend fun allWatchlist(profileId: String): List<WatchlistItem>
    suspend fun addWatchlist(item: WatchlistItem)
    suspend fun removeWatchlist(profileId: String, itemId: String)
}

class InMemoryProfileStore : ProfileStore {
    private val profileMap = MutableStateFlow<Map<String, Profile>>(emptyMap())
    private val historyList = MutableStateFlow<List<WatchHistory>>(emptyList())
    private val watchList = MutableStateFlow<List<WatchlistItem>>(emptyList())

    override fun profiles(): Flow<List<Profile>> = profileMap.map { m -> m.values.sortedBy { it.createdAt } }
    override suspend fun allProfiles() = profileMap.value.values.sortedBy { it.createdAt }
    override suspend fun upsertProfile(profile: Profile) = profileMap.update { it + (profile.id to profile) }
    override suspend fun deleteProfile(id: String) {
        profileMap.update { it - id }
        historyList.update { l -> l.filter { it.profileId != id } }
        watchList.update { l -> l.filter { it.profileId != id } }
    }
    override fun history(profileId: String) =
        historyList.map { l -> l.filter { it.profileId == profileId }.sortedByDescending { it.updatedAt } }
    override suspend fun allHistory(profileId: String) =
        historyList.value.filter { it.profileId == profileId }.sortedByDescending { it.updatedAt }
    override suspend fun upsertHistory(entry: WatchHistory) = historyList.update { l ->
        l.filterNot { it.profileId == entry.profileId && it.space == entry.space && it.itemId == entry.itemId } + entry
    }
    override suspend fun clearHistory(profileId: String) = historyList.update { l -> l.filter { it.profileId != profileId } }
    override fun watchlist(profileId: String) =
        watchList.map { l -> l.filter { it.profileId == profileId }.sortedByDescending { it.addedAt } }
    override suspend fun allWatchlist(profileId: String) =
        watchList.value.filter { it.profileId == profileId }.sortedByDescending { it.addedAt }
    override suspend fun addWatchlist(item: WatchlistItem) = watchList.update { l ->
        l.filterNot { it.profileId == item.profileId && it.itemId == item.itemId } + item
    }
    override suspend fun removeWatchlist(profileId: String, itemId: String) =
        watchList.update { l -> l.filterNot { it.profileId == profileId && it.itemId == itemId } }
}

@Dao
abstract class ProfileDao {
    @Query("SELECT * FROM profile ORDER BY createdAt")
    abstract fun observeProfiles(): Flow<List<Profile>>

    @Query("SELECT * FROM profile ORDER BY createdAt")
    abstract suspend fun allProfiles(): List<Profile>

    @Upsert
    abstract suspend fun upsertProfile(profile: Profile)

    @Query("DELETE FROM profile WHERE id = :id")
    abstract suspend fun deleteProfileRow(id: String)

    @Query("SELECT * FROM history WHERE profileId = :profileId ORDER BY updatedAt DESC")
    abstract fun observeHistory(profileId: String): Flow<List<WatchHistory>>

    @Query("SELECT * FROM history WHERE profileId = :profileId ORDER BY updatedAt DESC")
    abstract suspend fun allHistory(profileId: String): List<WatchHistory>

    @Upsert
    abstract suspend fun upsertHistory(entry: WatchHistory)

    @Query("DELETE FROM history WHERE profileId = :profileId")
    abstract suspend fun clearHistory(profileId: String)

    @Query("SELECT * FROM watchlist WHERE profileId = :profileId ORDER BY addedAt DESC")
    abstract fun observeWatchlist(profileId: String): Flow<List<WatchlistItem>>

    @Query("SELECT * FROM watchlist WHERE profileId = :profileId ORDER BY addedAt DESC")
    abstract suspend fun allWatchlist(profileId: String): List<WatchlistItem>

    @Upsert
    abstract suspend fun addWatchlist(item: WatchlistItem)

    @Query("DELETE FROM watchlist WHERE profileId = :profileId AND itemId = :itemId")
    abstract suspend fun removeWatchlist(profileId: String, itemId: String)

    @Query("DELETE FROM watchlist WHERE profileId = :profileId")
    abstract suspend fun clearWatchlist(profileId: String)

    @Transaction
    open suspend fun deleteProfile(id: String) {
        clearHistory(id)
        clearWatchlist(id)
        deleteProfileRow(id)
    }
}

/** Separate DB from downloads (CinetorDb) → no cross-feature migration. */
@Database(entities = [Profile::class, WatchHistory::class, WatchlistItem::class], version = 1, exportSchema = false)
abstract class ProfileDb : RoomDatabase() {
    abstract fun profiles(): ProfileDao

    companion object {
        @Volatile private var instance: ProfileDb? = null

        fun get(context: Context): ProfileDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, ProfileDb::class.java, "profiles.db")
                .fallbackToDestructiveMigration()
                .build()
                .also { instance = it }
        }
    }
}

class RoomProfileStore(private val dao: ProfileDao) : ProfileStore {
    override fun profiles() = dao.observeProfiles()
    override suspend fun allProfiles() = dao.allProfiles()
    override suspend fun upsertProfile(profile: Profile) = dao.upsertProfile(profile)
    override suspend fun deleteProfile(id: String) = dao.deleteProfile(id)
    override fun history(profileId: String) = dao.observeHistory(profileId)
    override suspend fun allHistory(profileId: String) = dao.allHistory(profileId)
    override suspend fun upsertHistory(entry: WatchHistory) = dao.upsertHistory(entry)
    override suspend fun clearHistory(profileId: String) = dao.clearHistory(profileId)
    override fun watchlist(profileId: String) = dao.observeWatchlist(profileId)
    override suspend fun allWatchlist(profileId: String) = dao.allWatchlist(profileId)
    override suspend fun addWatchlist(item: WatchlistItem) = dao.addWatchlist(item)
    override suspend fun removeWatchlist(profileId: String, itemId: String) = dao.removeWatchlist(profileId, itemId)
}
