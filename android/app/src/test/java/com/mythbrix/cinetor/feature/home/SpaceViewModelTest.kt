package com.mythbrix.cinetor.feature.home

import com.mythbrix.cinetor.TestFiles
import com.mythbrix.cinetor.core.data.Space
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpaceViewModelTest {
    @Test
    fun loadSections_homeHasMoviesAndSeriesWithPosters() = runTest {
        val ready = SpaceViewModel.loadSections(Space.HOME, TestFiles.catalog())
        assertEquals(listOf("Movies", "Series"), ready.sections.map { it.title })
        assertTrue(ready.sections.all { s -> s.items.isNotEmpty() && s.items.all { it.poster.startsWith("images/") } })
        assertTrue(ready.heroColor!!.startsWith("#"))
    }
}
