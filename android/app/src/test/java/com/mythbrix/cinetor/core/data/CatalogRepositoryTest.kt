package com.mythbrix.cinetor.core.data

import com.mythbrix.cinetor.TestFiles
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CatalogRepositoryTest {
    private val repo = TestFiles.catalog()

    @Test
    fun catalog_loadsAllSevenFiles() = runTest {
        for (space in Space.entries) {
            val categories = repo.categories(space)
            assertTrue("$space has categories", categories.isNotEmpty())
            categories.forEach { assertEquals(space, it.space) }
            val items = repo.allItems(space)
            assertTrue("$space has items", items.isNotEmpty())
            items.forEach { assertEquals("${it.id} space", space, it.space) }
        }
        val total = Space.entries.sumOf { repo.allItems(it).size }
        assertEquals(267, total)
    }

    @Test
    fun rowsAndHeroes_resolveWithinSameSpace() = runTest {
        for (space in Space.entries) {
            for (category in repo.categories(space)) {
                category.heroIds.forEach { assertEquals(space, repo.item(it).space) }
                for (row in category.rows) {
                    val items = repo.row(row.id)
                    assertEquals(row.itemIds.size, items.size)
                    items.forEach { assertEquals(space, it.space) }
                }
            }
        }
    }

    @Test
    fun contractDeltas_areParsed() = runTest {
        val tv = repo.file(Space.TV)
        assertTrue(tv.epgAnchor != null)
        val channel = repo.allItems(Space.TV).first { it.type == ItemType.CHANNEL }
        assertTrue(channel.channelNumber != null && channel.programs.isNotEmpty())
        val comic = repo.allItems(Space.COMICS).first()
        assertTrue(comic.pages.isNotEmpty() && comic.pages.first().pages.isNotEmpty())
        assertTrue(comic.format != null && comic.readingDirection != null)
        val track = repo.allItems(Space.MUSIC).first { it.type == ItemType.TRACK }
        assertTrue(track.albumId != null && track.artist != null)
        assertTrue(repo.allItems(Space.ADULT).all { it.type == ItemType.VIDEO })
        assertTrue(repo.genres(Space.HOME).isNotEmpty())
    }

    @Test
    fun byGenre_filtersWithinSpace() = runTest {
        val genre = repo.genres(Space.ANIME).first()
        val items = repo.byGenre(Space.ANIME, genre)
        assertTrue(items.isNotEmpty())
        assertTrue(items.all { it.space == Space.ANIME && genre in it.genres })
    }

    @Test
    fun item_unknownId_throwsNotFound() = runTest {
        try {
            repo.item("does-not-exist")
            fail("expected NotFoundException")
        } catch (e: NotFoundException) {
            assertEquals("does-not-exist", e.id)
        }
        try {
            repo.row("no-such-row")
            fail("expected NotFoundException")
        } catch (e: NotFoundException) {
            assertEquals("no-such-row", e.id)
        }
    }

    @Test
    fun hexColor_parsesRgbAndRgba() {
        assertEquals(0xFFE50914L, parseHexColor("#E50914"))
        assertEquals(0x73FF2D3BL, parseHexColor("#FF2D3B73"))
    }
}
