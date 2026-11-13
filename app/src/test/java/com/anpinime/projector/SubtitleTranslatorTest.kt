package com.anpinime.projector

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

class SubtitleTranslatorTest {

    @Test
    fun parsesTimedVttAndSrtCuesAccurately() {
        val srt = """
            1
            00:00:01,250 --> 00:00:03,500
            <i>Hello</i>
            world!

            2
            01:02.000 --> 01:05.500 align:center
            Second cue
        """.trimIndent()

        val cues = SubtitleTranslator.parseTimedCues(srt)
        assertEquals(2, cues.size)
        assertEquals(1250L, cues[0].startMs)
        assertEquals(3500L, cues[0].endMs)
        assertEquals("Hello\nworld!", cues[0].text)
        assertEquals(62_000L, cues[1].startMs)
        assertEquals(65_500L, cues[1].endMs)
        assertEquals("Second cue", cues[1].text)
    }

    @Test
    fun plansWave1AroundCurrentPositionAndCoversAllIndicesOnce() {
        val ranges = SubtitleTranslator.planBatchRanges(
            totalCues = 120,
            centerIndex = 50,
            wave1Before = 3,
            wave1After = 32,
            batchSize = 35
        )
        assertEquals(47..82, ranges[0])
        val covered = ranges.flatMap { it.toList() }.sorted()
        assertEquals((0 until 120).toList(), covered)
    }

    @Test
    fun wave1RendersImmediatelyAndMultiLineSurvivesTranslation() {
        val raw = (0 until 80).joinToString("\n\n") { idx ->
            val sec = idx * 3
            val start = String.format("00:%02d:%02d.000", sec / 60, sec % 60)
            val end = String.format("00:%02d:%02d.500", (sec + 2) / 60, (sec + 2) % 60)
            "$start --> $end\nLine $idx\nPart B"
        }
        val cues = SubtitleTranslator.parseTimedCues(raw)
        val waveReadyCalled = AtomicBoolean(false)
        val result = SubtitleTranslator.translateAllProgressive(
            cues = cues,
            currentPositionMs = 150_000L, // index 50
            workers = 4,
            translateFn = { joined ->
                joined.split("\n@@99@@\n").joinToString("\n@@99@@\n") { "VI: $it" }
            },
            onWaveReady = { waveMap ->
                waveReadyCalled.set(true)
                val activeAt50 = SubtitleTranslator.findActiveCueText(cues, waveMap, 150_500L)
                assertEquals("VI: Line 50\nPart B", activeAt50)
            }
        )
        assertTrue(waveReadyCalled.get())
        assertEquals(80, result.size)
        val vtt = SubtitleTranslator.buildVtt(cues, result)
        assertTrue(vtt.startsWith("WEBVTT"))
        assertTrue(vtt.contains("VI: Line 0\nPart B"))
        assertTrue(vtt.contains("VI: Line 79\nPart B"))
    }

    @Test
    fun bisectFallbackHandlesCorruptedSeparatorWithoutSingleCueStorm() {
        var calls = 0
        val out = SubtitleTranslator.translateBatchTexts(listOf("A", "B", "C", "D")) { q ->
            calls++
            val items = q.split("\n@@99@@\n")
            if (items.size == 4) {
                // Corrupt separator on batch of 4
                items.joinToString(" ")
            } else {
                items.joinToString("\n@@99@@\n") { "vi-$it" }
            }
        }
        assertEquals(listOf("vi-A", "vi-B", "vi-C", "vi-D"), out)
        assertEquals(3, calls) // 1 batch of 4 + 2 halves of 2 (instead of 5 calls!)
    }
}
