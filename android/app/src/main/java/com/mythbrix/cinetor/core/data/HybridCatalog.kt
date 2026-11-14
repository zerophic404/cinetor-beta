package com.mythbrix.cinetor.core.data

import com.mythbrix.cinetor.core.data.tmdb.TmdbCatalog
import com.mythbrix.cinetor.core.data.tmdb.TmdbMapper
import kotlinx.coroutines.CancellationException

/**
 * Routes catalog calls: video spaces (Home/Anime/Drama) → [remote] (TMDB) when configured,
 * isolated spaces → always [mock]. Any remote failure on a space-level call falls back to [mock]
 * (offline / bad token / HTTP error). Ids are routed by prefix (`tmdb-` items, `tmdb:` rows).
 */
class HybridCatalog(
    private val remote: CatalogRepository?,
    private val mock: CatalogRepository,
) : CatalogRepository {

    private fun useRemote(space: Space): Boolean = remote != null && space.isVideo

    private suspend fun <T> spaceCall(space: Space, block: suspend (CatalogRepository) -> T): T {
        val r = remote
        if (r == null || !useRemote(space)) return block(mock)
        return try {
            block(r)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            block(mock)
        }
    }

    override suspend fun categories(space: Space): List<Category> = spaceCall(space) { it.categories(space) }

    override suspend fun item(id: String): Item {
        val r = remote
        return if (r != null && id.startsWith(TmdbMapper.ID_PREFIX)) r.item(id) else mock.item(id)
    }

    override suspend fun row(rowId: String): List<Item> {
        val r = remote
        return if (r != null && rowId.startsWith(TmdbCatalog.ROW_PREFIX)) r.row(rowId) else mock.row(rowId)
    }

    override suspend fun byGenre(space: Space, genre: String): List<Item> = spaceCall(space) { it.byGenre(space, genre) }

    override suspend fun genres(space: Space): List<String> = spaceCall(space) { it.genres(space) }

    override suspend fun allItems(space: Space): List<Item> = spaceCall(space) { it.allItems(space) }
}
