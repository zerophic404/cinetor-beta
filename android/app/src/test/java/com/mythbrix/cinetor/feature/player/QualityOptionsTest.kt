package com.mythbrix.cinetor.feature.player

import com.mythbrix.cinetor.core.data.Source
import com.mythbrix.cinetor.core.data.SourceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QualityOptionsTest {

    private fun src(url: String, height: Int?, lang: String, codec: String? = "h264") =
        Source(url = url, kind = SourceKind.MP4, height = height, audioLang = lang, codec = codec)

    @Test
    fun standardHeight_snapsProviderHeightsToTheLadder() {
        assertEquals(1080, standardHeight(1088))
        assertEquals(2160, standardHeight(2176))
        assertEquals(720, standardHeight(720))
        assertNull(standardHeight(0))
        assertNull(standardHeight(null))
    }

    @Test
    fun qualityOptions_listEachResolutionOnce_preferringTheActiveLanguage() {
        val sources = listOf(
            src("hi-1080", 1080, "Hindi"),
            src("hi-1080-dash", 1080, "Hindi", codec = "hevc"),
            src("hi-480", 480, "Hindi"),
            src("en-1080", 1080, "English"),
            src("en-720", 720, "English"),
            src("hi-2160", 2160, "Hindi", codec = "hevc"),
            src("hi-360", 360, "Hindi"),
        )
        val options = qualityOptions(sources, activeIndex = 0)
        assertEquals(listOf("2160p", "1080p", "720p", "480p", "360p"), options.map { it.label })
        // Hindi serves every rung it has; 720p exists only in English, so it is listed once from there.
        assertEquals(0, options.first { it.height == 1080 }.sourceIndex)
        assertEquals(5, options.first { it.height == 2160 }.sourceIndex)
        assertEquals(4, options.first { it.height == 720 }.sourceIndex)
    }

    @Test
    fun qualityOptions_keep4K_whenOnlyAnotherLanguageHasIt() {
        val sources = listOf(
            src("hi-1080", 1080, "Hindi"),
            src("orig-2160", 2160, "Original", codec = "hevc"),
            src("orig-1080", 1080, "Original"),
        )
        val options = qualityOptions(sources, activeIndex = 0)
        assertEquals(listOf("2160p", "1080p"), options.map { it.label })
        assertEquals(1, options.first { it.height == 2160 }.sourceIndex)
        assertEquals(0, options.first { it.height == 1080 }.sourceIndex)
    }

    @Test
    fun qualityOptions_preferH264_whenTheActiveSourceIsNotOnThatRung() {
        val sources = listOf(
            src("en-720", 720, "English"),
            src("en-1080-hevc", 1080, "English", codec = "hevc"),
            src("en-1080-h264", 1080, "English", codec = "h264"),
        )
        val options = qualityOptions(sources, activeIndex = 0)
        assertEquals(listOf(1080, 720), options.map { it.height })
        assertEquals(2, options.first { it.height == 1080 }.sourceIndex)
    }

    @Test
    fun qualityOptions_emptyWhenNoSources() {
        assertEquals(emptyList<QualityOption>(), qualityOptions(emptyList(), 0))
    }
}
