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

    @Test fun streamCRawMpegTsWithPngExtensionRelaysAsVideoMp2t() {
        val upstream = MockWebServer()
        val rawTs = ByteArray(376) { idx -> if (idx % 188 == 0) 0x47 else (idx and 0x7F).toByte() }
        upstream.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.getHeader("Origin") != "https://embed1.streamc.xyz") {
                    return MockResponse().setResponseCode(403)
                }
                return when {
                    request.path!!.startsWith("/eyJ0") -> MockResponse()
                        .setHeader("Content-Type", "application/vnd.apple.mpegurl")
                        .setBody("#EXTM3U\n#EXTINF:2.0,\n${upstream.url("/chunk/streamaaa0000.png")}\n#EXT-X-ENDLIST\n")
                    request.path!!.endsWith(".png") -> MockResponse()
                        .setHeader("Content-Type", "image/png")
                        .setBody(Buffer().write(rawTs))
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        upstream.start()
        val relay = FilmMediaRelay(mapOf("Origin" to "https://embed1.streamc.xyz"), "fixture")
        try {
            val proxyMaster = relay.start(upstream.url("/eyJ0abc").toString(), isHlsHint = true)
            assertTrue(proxyMaster.contains("/proxy.m3u8"))
            val playlist = URL(proxyMaster).readText()
            val segUrl = playlist.lines().first { it.startsWith("http") }
            val conn = URL(segUrl).openConnection() as HttpURLConnection
            assertEquals(200, conn.responseCode)
            assertEquals("video/mp2t", conn.contentType)
            assertArrayEquals(rawTs, conn.inputStream.use { it.readBytes() })
            conn.disconnect()
        } finally {
            relay.stop()
            upstream.shutdown()
        }
    }

    @Test fun rewritesExtXMediaAudioSubtitlesAndExtXKeyWithProperMimeTypes() {
        val upstream = MockWebServer()
        // Key starting with 0x47 ('G') verifies .key extension takes precedence over TS sync byte detection
        val aesKey = byteArrayOf(0x47, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x88.toByte(), 0x99.toByte(), 0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte(), 0xEE.toByte(), 0xFF.toByte())
        val adtsFrame = byteArrayOf(0xFF.toByte(), 0xF1.toByte(), 0x50, 0x80.toByte(), 0x01, 0x3F, 0xFC.toByte())
        upstream.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return when (request.path) {
                    "/master.m3u8" -> MockResponse()
                        .setHeader("Content-Type", "application/vnd.apple.mpegurl")
                        .setBody(
                            "#EXTM3U\n" +
                                "#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"aud\",NAME=\"English\",URI=\"/audio/playlist\"\n" +
                                "#EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID=\"sub\",NAME=\"English\",URI=\"/subs/eng\"\n" +
                                "#EXT-X-STREAM-INF:BANDWIDTH=1000000,AUDIO=\"aud\"\n" +
                                "/video/main\n"
                        )
                    "/audio/playlist" -> MockResponse()
                        .setHeader("Content-Type", "application/vnd.apple.mpegurl")
                        .setBody(
                            "#EXTM3U\n" +
                                "#EXT-X-KEY:METHOD=AES-128,URI=\"/key/secret\"\n" +
                                "#EXTINF:4.0,\n/seg/aud00.png\n#EXT-X-ENDLIST\n"
                        )
                    "/subs/eng" -> MockResponse()
                        .setHeader("Content-Type", "application/vnd.apple.mpegurl")
                        .setBody("#EXTM3U\n#EXTINF:4.0,\n/seg/sub00.vtt\n#EXT-X-ENDLIST\n")
                    "/key/secret" -> MockResponse()
                        .setHeader("Content-Type", "text/plain")
                        .setBody(Buffer().write(aesKey))
                    "/seg/aud00.png" -> MockResponse()
                        .setHeader("Content-Type", "image/png")
                        .setBody(Buffer().write(adtsFrame))
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        upstream.start()
        val relay = FilmMediaRelay(emptyMap(), "fixture")
        try {
            val proxyMaster = relay.start(upstream.url("/master.m3u8").toString(), isHlsHint = true)
            val masterBody = URL(proxyMaster).readText()
            val uris = Regex("""URI="([^"]+)"""").findAll(masterBody).map { it.groupValues[1] }.toList()
            assertEquals(2, uris.size)
            assertTrue("Audio media playlist must get .m3u8 proxy ext", uris[0].contains("/proxy.m3u8?u="))
            assertTrue("Subtitle media playlist must get .m3u8 proxy ext", uris[1].contains("/proxy.m3u8?u="))

            val audioPlaylist = URL(uris[0]).readText()
            val keyProxyUrl = Regex("""URI="([^"]+)"""").find(audioPlaylist)!!.groupValues[1]
            assertTrue("EXT-X-KEY must get .key proxy ext", keyProxyUrl.contains("/proxy.key?u="))
            val keyConn = URL(keyProxyUrl).openConnection() as HttpURLConnection
            assertEquals("application/octet-stream", keyConn.contentType)
            assertArrayEquals(aesKey, keyConn.inputStream.use { it.readBytes() })
            keyConn.disconnect()

            val adtsSegUrl = audioPlaylist.lines().first { it.startsWith("http") }
            val adtsConn = URL(adtsSegUrl).openConnection() as HttpURLConnection
            assertEquals("audio/aac", adtsConn.contentType)
            assertArrayEquals(adtsFrame, adtsConn.inputStream.use { it.readBytes() })
            adtsConn.disconnect()
        } finally {
            relay.stop()
            upstream.shutdown()
        }
    }

    @Test fun detectsPngWrappedAdtsAudioAndFmp4MimeTypes() {
        val upstream = MockWebServer()
        val pngHeader = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10, 0, 0, 0, 0, 73, 69, 78, 68, 0, 0, 0, 0)
        val wrappedAdts = pngHeader + byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 4, 0, 0)
        val rawFmp4 = byteArrayOf(0, 0, 0, 24, 'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte(), 'i'.code.toByte(), 's'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte())
        upstream.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return when (request.path) {
                    "/aud.png" -> MockResponse().setHeader("Content-Type", "image/png").setBody(Buffer().write(wrappedAdts))
                    "/init.dat" -> MockResponse().setHeader("Content-Type", "image/png").setBody(Buffer().write(rawFmp4))
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        upstream.start()
        val relayInit = FilmMediaRelay(emptyMap(), "fixture")
        val relayAud = FilmMediaRelay(emptyMap(), "fixture")
        try {
            val base = relayInit.start(upstream.url("/init.dat").toString(), isHlsHint = false)
            val initConn = URL(base).openConnection() as HttpURLConnection
            assertEquals("video/mp4", initConn.contentType)
            assertArrayEquals(rawFmp4, initConn.inputStream.use { it.readBytes() })
            initConn.disconnect()

            val adtsProxyUrl = relayAud.start(upstream.url("/aud.png").toString(), isHlsHint = false)
            val c = URL(adtsProxyUrl).openConnection() as HttpURLConnection
            assertEquals("audio/aac", c.contentType)
            c.disconnect()
        } finally {
            relayInit.stop()
            relayAud.stop()
            upstream.shutdown()
        }
    }
}
