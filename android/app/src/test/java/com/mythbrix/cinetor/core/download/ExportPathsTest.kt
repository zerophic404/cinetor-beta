package com.mythbrix.cinetor.core.download

import org.junit.Assert.assertEquals
import org.junit.Test

class ExportPathsTest {
    @Test
    fun comics_goUnderComicsAndTitle() {
        assertEquals(listOf("Comics", "Solo Leveling"), ExportPaths.comicFolders("Solo Leveling"))
        assertEquals(listOf("Comics", "Re_Zero_ Starting Life"), ExportPaths.comicFolders("Re/Zero: Starting Life"))
        assertEquals(listOf("Comics", "Comic"), ExportPaths.comicFolders("///"))
    }

    @Test
    fun pdfNames_sortInReadingOrder() {
        assertEquals("Chapter 007.pdf", ExportPaths.comicPdfName(7, "Chapter 7"))
        assertEquals("Chapter 012 - The Hunt Begins.pdf", ExportPaths.comicPdfName(12, "The Hunt Begins"))
        assertEquals("Chapter 100.pdf", ExportPaths.comicPdfName(100, ""))
        val names = listOf(10, 2, 1).map { ExportPaths.comicPdfName(it, "") }.sorted()
        assertEquals(listOf("Chapter 001.pdf", "Chapter 002.pdf", "Chapter 010.pdf"), names)
    }

    @Test
    fun audio_goesToMusic_videoStaysAtTop() {
        assertEquals(listOf("Music"), ExportPaths.foldersFor("audio/flac", "flac"))
        assertEquals(listOf("Music"), ExportPaths.foldersFor(null, "mp3"))
        assertEquals(emptyList<String>(), ExportPaths.foldersFor("video/mp4", "mp4"))
    }
}
