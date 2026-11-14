package com.mythbrix.cinetor.core.data

/**
 * Last items the user saw on cards (id → summary). Details uses it to start the playback warm-up
 * and the shared poster before the full item resolves. Small LRU, thread-safe.
 */
class ItemPeekCache(private val max: Int = 64) {
    private val map = object : LinkedHashMap<String, Item>(max, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Item>?): Boolean = size > max
    }

    @Synchronized
    fun put(item: Item) {
        map[item.id] = item
    }

    @Synchronized
    fun get(id: String): Item? = map[id]
}
