package com.mythbrix.cinetor.feature.comics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ComicPdfTest {
    @Test
    fun normalPage_isOneSlice() {
        assertEquals(listOf(0 until 1_800), ComicPdf.slices(1_200, 1_800))
    }

    @Test
    fun longStrip_isCutIntoContiguousSlices() {
        val slices = ComicPdf.slices(800, 12_000)
        assertTrue(slices.size > 1)
        assertEquals(0, slices.first().first)
        assertEquals(11_999, slices.last().last)
        slices.zipWithNext().forEach { (a, b) -> assertEquals(a.last + 1, b.first) }
        // Every slice lays out at PDF width 595: height in points stays within the page cap.
        slices.forEach { assertTrue(it.count() * (595f / 800) <= 1_401f) }
    }

    @Test
    fun emptyImage_hasNoSlices() {
        assertEquals(emptyList<IntRange>(), ComicPdf.slices(0, 100))
        assertEquals(emptyList<IntRange>(), ComicPdf.slices(100, 0))
    }
}
