package com.mythbrix.cinetor.core.data

/** Thread-safe in-memory LRU (default 100 entries) for remote search results keyed by normalized query. */
class QueryCache<V>(private val maxSize: Int = 100) {
    init {
        require(maxSize > 0)
    }

    private val map = object : LinkedHashMap<String, V>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, V>?): Boolean = size > maxSize
    }

    @Synchronized
    fun get(key: String): V? = map[key]

    @Synchronized
    fun put(key: String, value: V) {
        map[key] = value
    }

    @Synchronized
    fun keys(): List<String> = map.keys.toList()

    val size: Int
        @Synchronized get() = map.size
}
