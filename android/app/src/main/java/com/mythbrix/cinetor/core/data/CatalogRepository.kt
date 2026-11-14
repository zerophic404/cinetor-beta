package com.mythbrix.cinetor.core.data

/** Reads a text asset by path (e.g. `mock/home.json`). Abstracted so JVM tests can read files. */
fun interface AssetReader {
    fun read(path: String): String
}

interface CatalogRepository {
    suspend fun categories(space: Space): List<Category>

    /** @throws NotFoundException for unknown ids. */
    suspend fun item(id: String): Item

    /** Resolved items of a row. @throws NotFoundException for unknown row ids. */
    suspend fun row(rowId: String): List<Item>

    suspend fun byGenre(space: Space, genre: String): List<Item>

    /** Genre chips for a space. */
    suspend fun genres(space: Space): List<String>

    /** Every item of a space (used to build search indexes). */
    suspend fun allItems(space: Space): List<Item>
}
