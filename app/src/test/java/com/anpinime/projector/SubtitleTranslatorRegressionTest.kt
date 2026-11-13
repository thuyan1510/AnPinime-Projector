package com.anpinime.projector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

class SubtitleTranslatorRegressionTest {

    private fun msToStamp(ms: Long): String {
        val h = ms / 3_600_000L
        val m = (ms % 3_600_000L) / 60_000L
        val s = (ms % 60_000L) / 1000L
        val rem = ms % 1000L
        return String.format("%02d:%02d:%02d.%03d", h, m, s, rem)
    }

    @Test
    fun translateAllProgressiveFast_deliversAllWavesPastWave1AndPhraseCacheHits() {
        val total = 1021
        val cues = (0 until total).map { idx ->
            val start = idx * 3000L + 500L
            // Repeat every 200th line so phraseCache hits trigger across later waves too.
            val text = if (idx >= 200 && idx % 50 == 0) "Line 10" else "Line $idx"
            TimedSubtitleCue(
                startMs = start,
                endMs = start + 2500L,
                startStamp = msToStamp(start),
                endStamp = msToStamp(start + 2500L),
                text = text
            )
        }

        val liveClientMap = ConcurrentHashMap<Int, String>()
        var wave1Size = 0

        val finalResult = SubtitleTranslator.translateAllProgressiveFast(
            cues = cues,
            currentPositionMs = 30_000L, // Near cue 10 (Wave 1 centers around cue 10)
            workers = 3,
            batchSize = 80,
            translateBatchFn = { inputs -> inputs.map { "VI: $it" } },
            onWaveReady = { wave ->
                wave1Size = wave.size
                liveClientMap.putAll(wave)
            },
            onBatchTranslated = { batch ->
                liveClientMap.putAll(batch)
            }
        )

        assertTrue("Wave 1 should remain small for fast startup", wave1Size in 60..90)
        assertEquals("Returned map must contain all 1021 cues", total, finalResult.size)
        assertEquals("Incremental callbacks must deliver all 1021 cues", total, liveClientMap.size)

        // Verify cue inside Wave 1 AND cues well beyond Wave 1 (unique + phraseCache hits + tail) render cleanly
        assertEquals("VI: Line 10", SubtitleTranslator.findActiveCueText(cues, liveClientMap, cues[10].startMs + 200L))
        assertEquals("VI: Line 10", SubtitleTranslator.findActiveCueText(cues, liveClientMap, cues[500].startMs + 200L))
        assertEquals("VI: Line 501", SubtitleTranslator.findActiveCueText(cues, liveClientMap, cues[501].startMs + 200L))
        assertEquals("VI: Line 901", SubtitleTranslator.findActiveCueText(cues, liveClientMap, cues[901].startMs + 200L))
        assertEquals("VI: Line 1020", SubtitleTranslator.findActiveCueText(cues, liveClientMap, cues[1020].startMs + 200L))
    }

    @Test
    fun findActiveCueText_respectsFallbackToOriginalFlag() {
        val cues = listOf(
            TimedSubtitleCue(1000L, 4000L, "00:00:01.000", "00:00:04.000", "Hello world"),
            TimedSubtitleCue(5000L, 9000L, "00:00:05.000", "00:00:09.000", "Missed cue")
        )
        val partialMap = mapOf(0 to "Xin chào thế giới")

        assertNull(SubtitleTranslator.findActiveCueText(cues, partialMap, 6000L, fallbackToOriginal = false))
        assertEquals(
            "Missed cue",
            SubtitleTranslator.findActiveCueText(cues, partialMap, 6000L, fallbackToOriginal = true)
        )
        assertEquals(
            "Xin chào thế giới",
            SubtitleTranslator.findActiveCueText(cues, partialMap, 2000L, fallbackToOriginal = true)
        )
    }

    @Test
    fun parseTimedCues_skipsRegionAndStyleBlocksBeforeCues() {
        val vtt = """
            WEBVTT

            REGION
            id:fred
            width:40%

            STYLE
            ::cue { color: white; }

            NOTE comment block

            00:00:01.000 --> 00:00:03.500 align:start position:0%
            First caption line
        """.trimIndent()

        val parsed = SubtitleTranslator.parseTimedCues(vtt)
        assertEquals(1, parsed.size)
        assertEquals(1000L, parsed[0].startMs)
        assertEquals(3500L, parsed[0].endMs)
        assertEquals("First caption line", parsed[0].text)
        assertNotNull(SubtitleTranslator.findActiveCueText(parsed, mapOf(0 to "Dòng đầu tiên"), 1500L))
    }
}
