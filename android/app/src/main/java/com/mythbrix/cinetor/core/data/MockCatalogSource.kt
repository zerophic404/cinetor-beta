package com.mythbrix.cinetor.core.data

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * [CatalogRepository] over the bundled mock JSON. Files are parsed once, lazily, off the main
 * thread; every public call then applies [latency] so loading states are exercised.
 */
class MockCatalogSource(
    private val reader: AssetReader,
    private val latency: MockLatency = MockLatency.Default,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** Spaces whose JSON is bundled (the APK ships only TV / Music / Comics). */
    private val spaces: List<Space> = Space.entries,
) : CatalogRepository {

    private class Data(
        val files: Map<Space, CatalogFile>,
        val itemsById: Map<String, Item>,
        val rowsById: Map<String, Row>,
    )

    private val mutex = Mutex()

    @Volatile
    private var data: Data? = null

    /** Parses all catalog files (no latency). Safe to call repeatedly / concurrently. */
    suspend fun preload() {
        load()
    }

    /** Raw file access (TV epgAnchor, avatars, ...). */
    suspend fun file(space: Space): CatalogFile = load().files.getValue(space)

    private suspend fun load(): Data {
        data?.let { return it }
        return mutex.withLock {
            data ?: withContext(ioDispatcher) { parseAll() }.also { data = it }
        }
    }

    private suspend fun parseAll(): Data = coroutineScope {
        val files = spaces
            .map { space -> async { space to parse(space) } }
            .awaitAll()
            .toMap()
        val items = HashMap<String, Item>()
        val rows = HashMap<String, Row>()
        files.values.forEach { f ->
            f.items.forEach { items[it.id] = it }
            f.categories.forEach { c -> c.rows.forEach { rows[it.id] = it } }
        }
        Data(files, items, rows)
    }

    private fun parse(space: Space): CatalogFile {
        val file = json.decodeFromString(CatalogFile.serializer(), reader.read(space.assetPath))
        check(file.space == space) { "${space.assetPath} declares space ${file.space}" }
        return file
    }

    override suspend fun categories(space: Space): List<Category> {
        val d = load()
        latency.await()
        return d.files.getValue(space).categories
    }

    override suspend fun item(id: String): Item {
        val d = load()
        latency.await()
        return d.itemsById[id] ?: throw NotFoundException(id)
    }

    override suspend fun row(rowId: String): List<Item> {
        val d = load()
        latency.await()
        val row = d.rowsById[rowId] ?: throw NotFoundException(rowId)
        return row.itemIds.mapNotNull { d.itemsById[it] }
    }

    override suspend fun byGenre(space: Space, genre: String): List<Item> {
        val d = load()
        latency.await()
        return d.files.getValue(space).items.filter { item ->
            item.genres.any { it.equals(genre, ignoreCase = true) }
        }
    }

    override suspend fun genres(space: Space): List<String> {
        val d = load()
        latency.await()
        val file = d.files.getValue(space)
        return file.genres.ifEmpty { file.items.flatMap { it.genres }.distinct() }
    }

    override suspend fun allItems(space: Space): List<Item> {
        val d = load()
        latency.await()
        return d.files.getValue(space).items
    }

    companion object {
        val json: Json = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
            explicitNulls = false
        }
    }
}
