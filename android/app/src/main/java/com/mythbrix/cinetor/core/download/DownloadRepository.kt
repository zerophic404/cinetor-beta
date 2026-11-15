package com.mythbrix.cinetor.core.download

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/** Persistence for downloads, per-chunk offsets and events (Room in the app, in-memory in tests). */
interface DownloadStore {
    fun observe(): Flow<List<DownloadRecord>>
    suspend fun all(): List<DownloadRecord>
    suspend fun get(id: String): DownloadRecord?
    suspend fun upsert(record: DownloadRecord)
    /** Removes the record with its chunks and events. */
    suspend fun delete(id: String)
    suspend fun chunks(id: String): List<ChunkState>
    /** Replaces every chunk row of [id]. */
    suspend fun saveChunks(id: String, chunks: List<ChunkState>)
    suspend fun addEvent(event: DownloadEvent)
    suspend fun events(id: String, limit: Int = 50): List<DownloadEvent>
}

/** Thread-safe in-memory store (unit tests, previews). */
class InMemoryDownloadStore : DownloadStore {
    private val records = MutableStateFlow<Map<String, DownloadRecord>>(emptyMap())
    private val chunkMap = MutableStateFlow<Map<String, List<ChunkState>>>(emptyMap())
    private val eventList = MutableStateFlow<List<DownloadEvent>>(emptyList())

    val chunksState: StateFlow<Map<String, List<ChunkState>>> = chunkMap.asStateFlow()

    override fun observe(): Flow<List<DownloadRecord>> =
        records.map { m -> m.values.sortedWith(compareBy({ it.priority }, { it.createdAt })) }

    override suspend fun all() = records.value.values.sortedWith(compareBy({ it.priority }, { it.createdAt }))
    override suspend fun get(id: String) = records.value[id]
    override suspend fun upsert(record: DownloadRecord) = records.update { it + (record.id to record) }
    override suspend fun delete(id: String) {
        records.update { it - id }
        chunkMap.update { it - id }
        eventList.update { l -> l.filter { it.downloadId != id } }
    }
    override suspend fun chunks(id: String) = chunkMap.value[id].orEmpty().sortedBy { it.idx }
    override suspend fun saveChunks(id: String, chunks: List<ChunkState>) = chunkMap.update { it + (id to chunks) }
    override suspend fun addEvent(event: DownloadEvent) = eventList.update { it + event }
    override suspend fun events(id: String, limit: Int) =
        eventList.value.filter { it.downloadId == id }.takeLast(limit).reversed()
}

@Dao
abstract class DownloadDao {
    @Query("SELECT * FROM downloads ORDER BY priority, createdAt")
    abstract fun observeAll(): Flow<List<DownloadRecord>>

    @Query("SELECT * FROM downloads ORDER BY priority, createdAt")
    abstract suspend fun all(): List<DownloadRecord>

    @Query("SELECT * FROM downloads WHERE id = :id")
    abstract suspend fun get(id: String): DownloadRecord?

    @Upsert
    abstract suspend fun upsert(record: DownloadRecord)

    @Query("DELETE FROM downloads WHERE id = :id")
    abstract suspend fun deleteRecord(id: String)

    @Query("SELECT * FROM download_chunks WHERE downloadId = :id ORDER BY idx")
    abstract suspend fun chunks(id: String): List<ChunkState>

    @Query("DELETE FROM download_chunks WHERE downloadId = :id")
    abstract suspend fun deleteChunks(id: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertChunks(chunks: List<ChunkState>)

    @Insert
    abstract suspend fun insertEvent(event: DownloadEvent)

    @Query("SELECT * FROM download_events WHERE downloadId = :id ORDER BY time DESC LIMIT :limit")
    abstract suspend fun events(id: String, limit: Int): List<DownloadEvent>

    @Query("DELETE FROM download_events WHERE downloadId = :id")
    abstract suspend fun deleteEvents(id: String)

    @Transaction
    open suspend fun replaceChunks(id: String, chunks: List<ChunkState>) {
        deleteChunks(id)
        insertChunks(chunks)
    }

    @Transaction
    open suspend fun deleteAll(id: String) {
        deleteChunks(id)
        deleteEvents(id)
        deleteRecord(id)
    }
}

/** App database. Downloads live here; later phases may add tables (bump version + migration). */
@Database(entities = [DownloadRecord::class, ChunkState::class, DownloadEvent::class], version = 2, exportSchema = false)
abstract class CinetorDb : RoomDatabase() {
    abstract fun downloads(): DownloadDao

    companion object {
        @Volatile private var instance: CinetorDb? = null

        fun get(context: Context): CinetorDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, CinetorDb::class.java, "cinetor.db")
                .fallbackToDestructiveMigration()
                .build()
                .also { instance = it }
        }
    }
}

class RoomDownloadStore(private val dao: DownloadDao) : DownloadStore {
    override fun observe() = dao.observeAll()
    override suspend fun all() = dao.all()
    override suspend fun get(id: String) = dao.get(id)
    override suspend fun upsert(record: DownloadRecord) = dao.upsert(record)
    override suspend fun delete(id: String) = dao.deleteAll(id)
    override suspend fun chunks(id: String) = dao.chunks(id)
    override suspend fun saveChunks(id: String, chunks: List<ChunkState>) = dao.replaceChunks(id, chunks)
    override suspend fun addEvent(event: DownloadEvent) = dao.insertEvent(event)
    override suspend fun events(id: String, limit: Int) = dao.events(id, limit)
}
