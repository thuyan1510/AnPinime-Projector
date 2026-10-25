package com.anpinime.projector

import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.Dispatcher
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

class FilmMediaRelayTest {
    @Test fun playlistSegmentsSubtitlesAndHeadersSurviveRelay() {
        val upstream = MockWebServer()
        val payload = byteArrayOf(0x47, 1, 2, 3, 4)
        val png = byteArrayOf(137.toByte(),80,78,71,13,10,26,10,0,0,0,0,73,69,78,68,0,0,0,0)
        upstream.dispatcher = object : Dispatcher() {
          override fun dispatch(request: RecordedRequest): MockResponse {
            if (request.getHeader("X-F4K-PT") != "ticket-fixture") return MockResponse().setResponseCode(403)
            val (mime, bytes) = when (request.path) {
                "/master.m3u8" -> "application/vnd.apple.mpegurl" to
                    "#EXTM3U\n#EXT-X-MEDIA:TYPE=SUBTITLES,URI=\"subs.vtt\"\n#EXTINF:5,\nsegment.ts\n#EXT-X-ENDLIST\n".toByteArray()
                "/subs.vtt" -> "text/vtt" to "WEBVTT\n\n00:00.000 --> 00:01.000\nXin chào\n".toByteArray()
                else -> "image/png" to (png + payload)
            }
            return MockResponse().setHeader("Content-Type", mime).setBody(Buffer().write(bytes))
          }
        }
        upstream.start()
        val relay = FilmMediaRelay(mapOf("X-F4K-PT" to "ticket-fixture"), "fixture")
        try {
            val url = relay.start(upstream.url("/master.m3u8").toString())
            val playlist = URL(url).readText()
            assertFalse(playlist.contains("URI=\"subs.vtt\""))
            val segment = playlist.lines().first { it.startsWith("http") }
            assertArrayEquals(payload, URL(segment).readBytes())
            val range = URL(segment).openConnection() as HttpURLConnection
            range.setRequestProperty("Range", "bytes=1-3")
            assertEquals(206, range.responseCode)
            assertArrayEquals(byteArrayOf(1,2,3), range.inputStream.use { it.readBytes() })
            range.disconnect()
            val subtitle = Regex("URI=\"([^\"]+)\"").find(playlist)!!.groupValues[1]
            val sub = URL(subtitle).openConnection() as HttpURLConnection
            assertEquals("text/vtt", sub.contentType)
            assertTrue(sub.inputStream.bufferedReader().use { it.readText() }.contains("Xin chào"))
            sub.disconnect()
        } finally {
            relay.stop()
            upstream.shutdown()
        }
    }

    @Test fun unwrappedMediaAndInvalidPngAreNotCorrupted() {
        val ts = byteArrayOf(0x47, 0, 1)
        assertArrayEquals(ts, FilmMediaRelay.unwrapPng(ts))
        val invalid = byteArrayOf(137.toByte(),80,78,71,13,10,26,10,127,127,127,127,0,0,0,0,0)
        assertArrayEquals(invalid, FilmMediaRelay.unwrapPng(invalid))
    }
}
