package com.anpinime.projector

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

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

    @Test
    fun parsesAssDialogueCuesHtmlEntitiesAndRejectsHtmlErrorPages() {
        val ass = """
            [Script Info]
            Title: Test
            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            Dialogue: 0,0:00:01.20,0:00:03.45,Default,,0,0,0,,{\i1}Hello &amp; welcome{\i0}\N1 &lt; 2 &quot;ok&quot;
        """.trimIndent()
        val cues = SubtitleTranslator.parseTimedCues(ass)
        assertEquals(1, cues.size)
        assertEquals(1_200L, cues[0].startMs)
        assertEquals(3_450L, cues[0].endMs)
        assertEquals("Hello & welcome\n1 < 2 \"ok\"", cues[0].text)

        val htmlError = "<!doctype html><html><head><title>Just a moment...</title></head><body>403</body></html>"
        assertTrue(SubtitleTranslator.parseTimedCues(htmlError).isEmpty())
    }

    @Test
    fun translateBatchViaHtmlMatchesAetherProtocolAndRestoresLineBreaks() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""[["Xin chào!<br /> Bạn khỏe không?","Tạm biệt"],["en","en"]]""")
        )
        server.start()
        try {
            val client = OkHttpClient()
            val out = SubtitleTranslator.translateBatchViaHtml(
                texts = listOf("Hello!\nHow are you?", "Goodbye"),
                client = client,
                targetLang = "vi",
                endpointUrl = server.url("/v1/translateHtml").toString(),
                apiKey = "test-api-key",
                fallbackTranslateFn = null,
                retryDelayMs = 0L
            )
            val recorded = server.takeRequest()
            assertEquals("POST", recorded.method)
            assertEquals("test-api-key", recorded.getHeader("X-goog-api-key"))
            assertTrue(recorded.getHeader("Content-Type").orEmpty().contains("application/json+protobuf"))
            val bodyStr = recorded.body.readUtf8()
            assertEquals("""[[["Hello!<br />How are you?","Goodbye"],"auto","vi"],"te"]""", bodyStr)
            assertEquals(listOf("Xin chào!\nBạn khỏe không?", "Tạm biệt"), out)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun translateAllProgressiveFastDeduplicatesPhrasesAndRoundTripsDiskCache() {
        val phrases = listOf("Yeah.", "What?", "Look at the sky!", "Let's go.")
        val cues = (0 until 240).map { i ->
            TimedSubtitleCue(
                startMs = i * 2000L,
                endMs = i * 2000L + 1500L,
                startStamp = "00:00:00.000",
                endStamp = "00:00:01.500",
                text = phrases[i % phrases.size]
            )
        }
        val sentStrings = AtomicInteger(0)
        val waveReady = AtomicBoolean(false)
        val translated = SubtitleTranslator.translateAllProgressiveFast(
            cues = cues,
            currentPositionMs = 200_000L,
            workers = 2,
            batchSize = 80,
            translateBatchFn = { uniqueList ->
                sentStrings.addAndGet(uniqueList.size)
                uniqueList.map { "VI:$it" }
            },
            onWaveReady = {
                waveReady.set(true)
            }
        )
        assertTrue(waveReady.get())
        assertEquals(240, translated.size)
        // With deduplication of 4 distinct phrases across batches, unique calls stay minimal
        assertTrue("Expected deduped phrase count <= 16, was ${sentStrings.get()}", sentStrings.get() <= 16)
        assertEquals("VI:Look at the sky!", SubtitleTranslator.findActiveCueText(cues, translated, 4_500L))

        val tmpFile = File.createTempFile("sub_cache_test", ".json")
        try {
            SubtitleTranslator.saveCachedTranslations(tmpFile, cues.size, translated)
            val loaded = SubtitleTranslator.loadCachedTranslations(tmpFile, cues.size)
            assertEquals(240, loaded.size)
            assertEquals("VI:Yeah.", loaded[0])
            assertTrue(SubtitleTranslator.loadCachedTranslations(tmpFile, 999).isEmpty())
        } finally {
            tmpFile.delete()
        }
    }
}
