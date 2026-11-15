package com.mythbrix.cinetor.feature.music

import androidx.compose.runtime.Immutable
import com.mythbrix.cinetor.core.data.Item
import kotlin.random.Random

enum class RepeatMode { OFF, ALL, ONE }

/**
 * Pure play queue. [items] is the play order (what the queue sheet shows), [index] the current track.
 * Shuffle puts the current track first and shuffles the rest; [original] keeps the unshuffled order so
 * turning shuffle off restores it (current track kept).
 */
@Immutable
data class MusicQueue(
    val items: List<Item>,
    val index: Int = 0,
    val shuffle: Boolean = false,
    val repeat: RepeatMode = RepeatMode.OFF,
    val original: List<Item> = items,
) {
    val current: Item? get() = items.getOrNull(index)
    val upNext: List<Item> get() = if (index + 1 < items.size) items.subList(index + 1, items.size) else emptyList()

    /** Drag-reorder [from] → [to]; the current track stays current wherever it lands. */
    fun move(from: Int, to: Int): MusicQueue {
        if (from !in items.indices || to !in items.indices || from == to) return this
        val list = items.toMutableList().apply { add(to, removeAt(from)) }
        val newIndex = when {
            from == index -> to
            from < index && to >= index -> index - 1
            from > index && to <= index -> index + 1
            else -> index
        }
        return copy(items = list, index = newIndex, original = if (shuffle) original else list)
    }

    /** Remove a queued track (not the current one). Null = nothing changes. */
    fun removeAt(i: Int): MusicQueue {
        if (i !in items.indices || i == index) return this
        val removed = items[i]
        val list = items.toMutableList().apply { removeAt(i) }
        return copy(
            items = list,
            index = if (i < index) index - 1 else index,
            original = original.filterNot { it.id == removed.id },
        )
    }

    fun playAt(i: Int): MusicQueue = if (i in items.indices) copy(index = i) else this

    /**
     * Next track. [auto] = previous one finished: repeat ONE replays it; a manual skip always advances.
     * Null = end of queue (repeat OFF).
     */
    fun next(auto: Boolean): MusicQueue? = when {
        items.isEmpty() -> null
        auto && repeat == RepeatMode.ONE -> this
        index + 1 < items.size -> copy(index = index + 1)
        repeat != RepeatMode.OFF -> copy(index = 0)
        else -> null
    }

    fun previous(): MusicQueue = when {
        index > 0 -> copy(index = index - 1)
        repeat != RepeatMode.OFF && items.isNotEmpty() -> copy(index = items.size - 1)
        else -> this
    }

    fun toggleShuffle(random: Random = Random.Default): MusicQueue {
        val cur = current ?: return copy(shuffle = !shuffle)
        return if (!shuffle) {
            val rest = items.filterIndexed { i, _ -> i != index }.shuffled(random)
            copy(items = listOf(cur) + rest, index = 0, shuffle = true, original = items)
        } else {
            val restored = original.ifEmpty { items }
            copy(items = restored, index = restored.indexOfFirst { it.id == cur.id }.coerceAtLeast(0), shuffle = false, original = restored)
        }
    }

    fun cycleRepeat(): MusicQueue = copy(
        repeat = when (repeat) {
            RepeatMode.OFF -> RepeatMode.ALL
            RepeatMode.ALL -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.OFF
        },
    )
}
