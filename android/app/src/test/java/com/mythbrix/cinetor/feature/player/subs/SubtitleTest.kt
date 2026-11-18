package com.mythbrix.cinetor.feature.player.subs

import com.mythbrix.cinetor.core.data.InMemoryHistoryStorage
import com.mythbrix.cinetor.core.player.SubPlan
import com.mythbrix.cinetor.core.player.TrackOption
import com.mythbrix.cinetor.feature.player.knownSubFor
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val SRT = """1
00:00:01,000 --> 00:00:03,500
<i>Hello</i> there

2
00:00:04,000 --> 00:00:06,000
Second line
two

"""

private const val VTT = """WEBVTT

NOTE a comment

00:01.000 --> 00:02.500 align:center
First

intro
00:00:03.000 --> 00:00:04.000
Second
"""

@OptIn(ExperimentalCoroutinesApi::class)
class SubtitleTest {
    @Test
    fun srtParse_basic() {
        val cues = SubtitleParser.parse(SRT, "movie.srt")
        assertEquals(2, cues.size)
        assertEquals(SubCue(1_000, 3_500, "Hello there"), cues[0])
        assertEquals(SubCue(4_000, 6_000, "Second line\ntwo"), cues[1])
        assertEquals(3_723_500L, SubtitleParser.parseTime("01:02:03,500"))
        assertEquals(123_450L, SubtitleParser.parseTime("02:03.45"))
        assertNull(SubtitleParser.parseTime("garbage"))
    }

    @Test
    fun vttParse_skipsHeaderNotesAndSettings() {
        val cues = SubtitleParser.parse(VTT)
        assertEquals(listOf("First", "Second"), cues.map { it.text })
        assertEquals(1_000L, cues[0].startMs)
        assertEquals(2_500L, cues[0].endMs)
    }

    @Test
    fun offset_shiftsCueTimes() {
        val cues = SubtitleParser.parseSrt(SRT)
        // +500 ms: subtitles later.
        assertEquals(listOf(1_500L, 4_500L), SubtitleSync.shift(cues, 500).map { it.startMs })
        // −2 s: earlier; first cue clipped at 0, nothing lost.
        val earlier = SubtitleSync.shift(cues, -2_000)
        assertEquals(listOf(0L, 2_000L), earlier.map { it.startMs })
        assertEquals(1_500L, earlier[0].endMs)
        // Active cue honours the offset both ways.
        assertTrue(SubtitleSync.active(cues, 1_200, 500).isEmpty())
        assertEquals("Hello there", SubtitleSync.active(cues, 1_600, 500).single().text)
        assertEquals("Second line\ntwo", SubtitleSync.active(cues, 2_500, -2_000).single().text)
        // Clamp ±10 s, 50 ms steps.
        assertEquals(10_000L, SubtitleSync.clamp(12_345))
        assertEquals(-10_000L, SubtitleSync.clamp(-99_000))
        assertEquals(1_250L, SubtitleSync.clamp(1_237))
        assertEquals("−1.250 s", SubtitleSync.label(-1_250))
    }

    @Test
    fun style_persists() = runTest {
        val storage = InMemoryHistoryStorage()
        val custom = SubtitleStyle(sizePercent = 150, textColor = 0xFFFFEB3B, bgOpacity = 0.8f, edge = SubEdge.SHADOW, font = SubFont.MONO, bottomPercent = 20)
        SubtitleStyleStore(storage).save("p1", custom)
        // Fresh store over the same storage (app restart) → same style, per profile.
        val reloaded = SubtitleStyleStore(storage)
        assertEquals(custom, reloaded.get("p1"))
        assertEquals(SubtitleStyle(), reloaded.get("p2"))
        // Out-of-range values are clamped on save.
        reloaded.save("p2", SubtitleStyle(sizePercent = 400, bottomPercent = -5))
        assertEquals(200, SubtitleStyleStore(storage).get("p2").sizePercent)
        assertEquals(0, SubtitleStyleStore(storage).get("p2").bottomPercent)
    }

    @Test
    fun defaultStyle_hasTransparentBackgroundAndDropShadow() {
        val def = SubtitleStyle()
        assertEquals(0f, def.bgOpacity, 1e-4f)
        assertEquals(0x00000000L, def.bgArgb)
        assertEquals(SubEdge.SHADOW, def.edge)
    }

    @Test
    fun languageNames_areFullHumanWordsWithoutShortCodes() {
        assertEquals("Bangla", fullSubtitleLanguageName("bn"))
        assertEquals("Bangla", fullSubtitleLanguageName("ben"))
        assertEquals("Bangla", fullSubtitleLanguageName("bengali"))
        assertEquals("Bangla", fullSubtitleLanguageName("BN-BD"))
        assertEquals("English", fullSubtitleLanguageName("en"))
        assertEquals("English", fullSubtitleLanguageName("eng"))
        assertEquals("Hindi", fullSubtitleLanguageName("hi"))
        assertEquals("Hindi", fullSubtitleLanguageName("hin"))
        assertEquals("Urdu", fullSubtitleLanguageName("ur"))
        assertEquals("Chinese", fullSubtitleLanguageName("zh"))
        assertEquals("Chinese", fullSubtitleLanguageName("chi"))
        assertEquals("Chinese", fullSubtitleLanguageName("zh-CN"))
        assertEquals("Arabic", fullSubtitleLanguageName("ar"))
        assertEquals("Korean", fullSubtitleLanguageName("ko"))
        assertEquals("Japanese", fullSubtitleLanguageName("ja"))
        assertEquals("Spanish", fullSubtitleLanguageName("es"))
        assertEquals("French", fullSubtitleLanguageName("fr"))
        assertEquals("Indonesian", fullSubtitleLanguageName("id"))
        assertEquals("Indonesian", fullSubtitleLanguageName("in"))
        assertEquals("Turkish", fullSubtitleLanguageName("tr"))
        assertEquals("Tamil", fullSubtitleLanguageName("ta"))
        assertEquals("Telugu", fullSubtitleLanguageName("te"))
        assertEquals("Original", fullSubtitleLanguageName("und"))
        assertEquals("Original", fullSubtitleLanguageName(""))
        assertEquals("English", formatTrackLanguageLabel("Subtitle 1", "en"))
        assertEquals("Bangla", formatTrackLanguageLabel("BN", "bn"))
        assertEquals(
            listOf("Bangla", "English", "Hindi", "Urdu", "Chinese", "Arabic", "Korean", "Japanese", "Spanish", "French", "Indonesian"),
            POPULAR_SUBTITLE_LANGUAGES.map { it.name },
        )
    }

    @Test
    fun controller_offsetConvertsKnownTrack_elseHint() = runTest {
        val files = mapOf("asset:///mock/subs/en.vtt" to VTT)
        val c = SubtitleController(backgroundScope, SubtitleStyleStore(InMemoryHistoryStorage()), { "p" }, { files.getValue(it) })
        var converted = 0
        c.setOffset(300, "asset:///mock/subs/en.vtt", "English") { converted++ }
        runCurrent()
        assertEquals(300L, c.state.value.offsetMs)
        assertNotNull(c.state.value.sideLoaded)
        assertEquals(1, converted)
        c.reset()
        c.setOffset(-400)
        assertNull(c.state.value.sideLoaded)
        assertNotNull(c.state.value.hint)
    }

    @Test
    fun knownSub_matchesLabelThenLanguage() {
        val subs = listOf(SubPlan("asset:///mock/subs/en.vtt", "text/vtt", "en", "English"), SubPlan("asset:///mock/subs/bn.vtt", "text/vtt", "bn", "Bangla"))
        assertEquals("bn", knownSubFor(TrackOption("2:0", "Bangla", "bn", true), subs)?.language)
        assertEquals("en", knownSubFor(TrackOption("2:1", "Subtitle 1", "en", true), subs)?.language)
        assertNull(knownSubFor(null, subs))
    }
}
