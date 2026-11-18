package com.mythbrix.cinetor.core.perf

import android.content.Context
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.SingletonImageLoader
import com.mythbrix.cinetor.core.data.AppContainer
import com.mythbrix.cinetor.core.data.Category
import com.mythbrix.cinetor.core.data.Item
import com.mythbrix.cinetor.core.data.Row
import com.mythbrix.cinetor.core.data.RowStyle
import com.mythbrix.cinetor.core.data.Space
import com.mythbrix.cinetor.core.ui.sizedImageRequest
import com.mythbrix.cinetor.feature.home.heroDeckCardWidthDp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap

/** How much of a space the startup pre-warm loads, per pass. */
data class PrewarmPlan(
    /** Rows of the space's first tab (the one shown on open). */
    val firstTabRows: Int,
    /** Rows of every other tab (0 = skip other tabs). */
    val otherTabRows: Int,
    /** Thumbnails per row: a phone shows ~3.5 cards, so 12 covers two swipes. */
    val cardsPerRow: Int = 12,
    val heroes: Int = 6,
) {
    companion object {
        /** Under the splash: what the first screen of each space needs. */
        val Splash = PrewarmPlan(firstTabRows = 6, otherTabRows = 0)

        /** Right after the splash: the rest of the first tab and the top of every other tab. */
        val Background = PrewarmPlan(firstTabRows = Int.MAX_VALUE, otherTabRows = 4)
    }
}

/** Rows of [cats] that [plan] covers, in display order, never repeating a row id. */
internal fun prewarmRows(cats: List<Category>, plan: PrewarmPlan): List<Row> {
    val seen = HashSet<String>()
    return cats.flatMapIndexed { i, cat ->
        val take = if (i == 0) plan.firstTabRows else plan.otherTabRows
        cat.rows.filter { it.style != RowStyle.CONTINUE }.take(take)
    }.filter { seen.add(it.id) }
}

/**
 * Loads catalog rows and their thumbnails ahead of time so a space's first frame, a tab switch or a
 * scroll never shows a loading shimmer. Rows land in the gateway's memory/disk cache; images are
 * enqueued with the exact request each card builds ([sizedImageRequest]), so cards hit Coil's
 * memory cache. Adult is pre-warmed only while the section is turned on in Settings.
 */
class StartupPrewarmer(
    private val context: Context,
    private val container: AppContainer,
    private val tier: PerformanceTier,
    private val density: Density,
    private val screenWidthDp: Float,
) {
    private val loader = runCatching { SingletonImageLoader.get(context) }.getOrNull()
    private val enqueued = ConcurrentHashMap.newKeySet<String>()
    private val rowPermits = Semaphore(6)
    private val doneRows = ConcurrentHashMap.newKeySet<String>()

    private fun image(path: String, width: Dp, height: Dp, large: Boolean) {
        val l = loader ?: return
        if (path.isBlank()) return
        val request = sizedImageRequest(context, path, width, height, density, tier, large)
        val key = request.data?.toString() ?: return
        if (!enqueued.add("$key@${width.value}x${height.value}")) return
        runCatching { l.enqueue(request) }
    }

    private fun cardImage(item: Item, style: RowStyle) {
        when (style) {
            RowStyle.LANDSCAPE -> image(item.backdrop.ifBlank { item.poster }, CARD_WIDE_W, CARD_WIDE_W * 9f / 16f, large = false)
            RowStyle.CIRCLE -> image(item.poster, 96.dp, 96.dp, large = false)
            else -> image(item.poster, CARD_POSTER_W, CARD_POSTER_W * 1.5f, large = false)
        }
    }

    private fun heroImage(item: Item) {
        val wide = screenWidthDp > 600f
        val w = heroDeckCardWidthDp(screenWidthDp, wide).dp
        val h = if (wide) (w * 7.2f / 16f).coerceIn(260.dp, 420.dp) else w / 0.75f
        val art = if (wide) item.backdrop.ifBlank { item.poster } else item.poster.ifBlank { item.backdrop }
        image(art, w, h, large = true)
    }

    private suspend fun rowItems(row: Row): List<Item> = rowPermits.withPermit {
        try {
            container.catalog.row(row.id)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Pre-warms [space] per [plan]. Returns once rows are fetched and thumbnails are enqueued. */
    suspend fun warm(space: Space, plan: PrewarmPlan) = coroutineScope {
        if (space == Space.ADULT && !container.adult.enabled.value) return@coroutineScope
        val cats = try {
            container.catalog.categories(space)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return@coroutineScope
        }
        val rows = prewarmRows(cats, plan).filter { it.id !in doneRows }
        val fetched = rows.map { row -> async { row to rowItems(row) } }.awaitAll()
        val byId = HashMap<String, Item>()
        fetched.forEach { (row, items) ->
            if (items.isNotEmpty()) doneRows += row.id
            items.take(plan.cardsPerRow).forEach { item ->
                byId[item.id] = item
                container.peek.put(item)
                cardImage(item, row.style)
            }
        }
        val heroIds = cats.firstOrNull()?.heroIds.orEmpty().take(plan.heroes)
        heroIds.map { id ->
            async {
                byId[id] ?: container.peek.get(id) ?: try {
                    container.catalog.item(id)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
            }
        }.awaitAll().filterNotNull().forEach { hero ->
            container.peek.put(hero)
            heroImage(hero)
        }
    }

    private companion object {
        val CARD_POSTER_W = 112.dp
        val CARD_WIDE_W = 216.dp
    }
}
