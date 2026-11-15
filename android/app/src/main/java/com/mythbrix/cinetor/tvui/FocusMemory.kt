package com.mythbrix.cinetor.tvui

/** Position of a focused card on a TV screen: content row + index inside that row. */
data class FocusPos(val row: Int, val index: Int) {
    val isHero: Boolean get() = row == HERO_ROW

    companion object {
        /** Row id for the hero banner above the content rows. */
        const val HERO_ROW = -1
        val HERO = FocusPos(HERO_ROW, 0)
    }
}

/**
 * Last focused card per screen (Review Focus #5). Survives the screen leaving composition
 * (Details / player / back) because it lives in the TV shell, above the NavHost.
 * Also remembers the last column per row so Up/Down return to where the user was in that row.
 * Pure + JVM-testable.
 */
class FocusMemory {
    private val last = HashMap<String, FocusPos>()
    private val columns = HashMap<String, HashMap<Int, Int>>()

    fun save(screen: String, pos: FocusPos) {
        last[screen] = pos
        if (pos.isHero) return
        columns.getOrPut(screen) { HashMap() }[pos.row] = pos.index
    }

    fun get(screen: String): FocusPos? = last[screen]

    /** Last index focused in [row] of [screen] (null = never visited). */
    fun columnFor(screen: String, row: Int): Int? = columns[screen]?.get(row)

    /**
     * Where focus should go when [screen] comes back with rows of [rowSizes] (after back or a reload).
     * Clamped to the current data: shorter row → last card; fewer rows → last non-empty row.
     * Null = nothing remembered or no content (caller focuses its default, e.g. hero Play).
     */
    fun resolve(screen: String, rowSizes: List<Int>): FocusPos? {
        val pos = last[screen] ?: return null
        if (pos.isHero) return pos
        if (rowSizes.none { it > 0 }) return null
        var row = pos.row.coerceIn(0, rowSizes.lastIndex)
        while (row > 0 && rowSizes[row] == 0) row--
        if (rowSizes[row] == 0) row = rowSizes.indexOfFirst { it > 0 }
        return FocusPos(row, pos.index.coerceIn(0, rowSizes[row] - 1))
    }

    fun clear(screen: String) {
        last.remove(screen)
        columns.remove(screen)
    }
}

enum class Dpad { UP, DOWN, LEFT, RIGHT }

/** Result of a D-pad move inside a screen's card grid. */
sealed interface GridMove {
    data class To(val pos: FocusPos) : GridMove
    /** Leave the grid to the left → side nav. */
    data object ExitLeft : GridMove
    /** Leave the grid upward → hero / tabs above the rows. */
    data object ExitUp : GridMove
    /** Edge with nothing beyond: consume the key so focus doesn't jump somewhere odd. */
    data object Stay : GridMove
}

/** Pure D-pad rules for rows of cards (rows may be empty; Up/Down skip them and keep the remembered column). */
object TvGrid {
    fun move(from: FocusPos, dir: Dpad, rowSizes: List<Int>, columnFor: (Int) -> Int? = { null }): GridMove {
        val size = rowSizes.getOrElse(from.row) { 0 }
        return when (dir) {
            Dpad.RIGHT -> if (from.index + 1 < size) GridMove.To(from.copy(index = from.index + 1)) else GridMove.Stay
            Dpad.LEFT -> if (from.index > 0) GridMove.To(from.copy(index = from.index - 1)) else GridMove.ExitLeft
            Dpad.DOWN -> {
                val row = (from.row + 1..rowSizes.lastIndex).firstOrNull { rowSizes[it] > 0 }
                    ?: return GridMove.Stay
                GridMove.To(FocusPos(row, (columnFor(row) ?: from.index).coerceIn(0, rowSizes[row] - 1)))
            }
            Dpad.UP -> {
                val row = (from.row - 1 downTo 0).firstOrNull { rowSizes[it] > 0 }
                    ?: return GridMove.ExitUp
                GridMove.To(FocusPos(row, (columnFor(row) ?: from.index).coerceIn(0, rowSizes[row] - 1)))
            }
        }
    }
}
