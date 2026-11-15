package com.mythbrix.cinetor.core.data

import com.mythbrix.cinetor.core.data.gateway.PagedCatalog
import com.mythbrix.cinetor.core.data.gateway.RoutedCatalog
import com.mythbrix.cinetor.core.data.gateway.RowPage
import com.mythbrix.cinetor.core.data.tmdb.TmdbCatalog
import com.mythbrix.cinetor.core.data.tmdb.TmdbMapper
import kotlinx.coroutines.CancellationException

/**
 * Routes catalog calls. Video spaces (Home/Anime/Drama) try [chain] in order (Gateway → TMDB) and fall
 * back to [mock] when every remote fails (offline / HTTP error). Adult uses only [adultChain] (gateway,
 * non-empty solely while the gate is unlocked) else mock; the other isolated spaces (TV, Music, Comics)
 * are always [mock]. Item / row ids route to the remote that owns them ([RoutedCatalog], legacy TMDB
 * prefixes for plain remotes), everything else to [mock].
 */
class HybridCatalog(
    private val chain: List<CatalogRepository>,
    private val mock: CatalogRepository,
    private val adultChain: () -> List<CatalogRepository> = { emptyList() },
    /** False → that space never shows mock data (gateway-only spaces): the last remote error propagates. */
    private val mockFallback: (Space) -> Boolean = { true },
) : CatalogRepository, PagedCatalog {

    constructor(remote: CatalogRepository?, mock: CatalogRepository) : this(listOfNotNull(remote), mock)

    private fun remotesFor(space: Space): List<CatalogRepository> = when {
        space.isVideo -> chain
        space == Space.ADULT -> adultChain()
        else -> emptyList()
    }

    private suspend fun <T> spaceCall(space: Space, block: suspend (CatalogRepository) -> T): T {
        var last: Exception? = null
        for (r in remotesFor(space)) {
            try {
                return block(r)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                last = e // next in chain
            }
        }
        if (!mockFallback(space)) throw last ?: IllegalStateException("${space.displayName} is unavailable")
        return block(mock)
    }

    private fun ownsItem(r: CatalogRepository, id: String): Boolean =
        (r as? RoutedCatalog)?.ownsItem(id) ?: id.startsWith(TmdbMapper.ID_PREFIX)

    private fun ownsRow(r: CatalogRepository, rowId: String): Boolean =
        (r as? RoutedCatalog)?.ownsRow(rowId) ?: rowId.startsWith(TmdbCatalog.ROW_PREFIX)

    private fun itemOwner(id: String): CatalogRepository =
        (chain + adultChain()).firstOrNull { ownsItem(it, id) } ?: mock

    private fun rowOwner(rowId: String): CatalogRepository =
        (chain + adultChain()).firstOrNull { ownsRow(it, rowId) } ?: mock

    override suspend fun categories(space: Space): List<Category> = spaceCall(space) { it.categories(space) }

    override suspend fun item(id: String): Item = itemOwner(id).item(id)

    override suspend fun row(rowId: String): List<Item> = rowOwner(rowId).row(rowId)

    override suspend fun rowPage(rowId: String, page: Int): RowPage {
        val owner = rowOwner(rowId)
        if (owner is PagedCatalog) return owner.rowPage(rowId, page)
        return if (page <= 1) RowPage(owner.row(rowId), 1, false) else RowPage(emptyList(), page, false)
    }

    override suspend fun byGenre(space: Space, genre: String): List<Item> = spaceCall(space) { it.byGenre(space, genre) }

    override suspend fun genres(space: Space): List<String> = spaceCall(space) { it.genres(space) }

    override suspend fun allItems(space: Space): List<Item> = spaceCall(space) { it.allItems(space) }
}
