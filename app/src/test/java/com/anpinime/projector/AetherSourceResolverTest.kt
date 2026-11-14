package com.anpinime.projector

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class AetherSourceResolverTest {

    @Test
    fun serversListContainsExpectedEntries() {
        val servers = AetherSourceResolver.SERVERS
        assertTrue(servers.isNotEmpty())
        assertEquals("auto", servers[0].id)
        assertTrue(servers.any { it.id == "lul" })
        assertTrue(servers.any { it.id == "ly" })
        assertTrue(servers.any { it.id == "lil" })
        assertTrue(servers.any { it.id == "rock" })
        assertTrue(servers.any { it.id == "shore" })
    }

    @Test
    fun subtitleUrlAndLanguageNormalizationWorkCorrectly() {
        val raw = "https://dl.opensubtitles.org/en/download/src-api/vrf-19d60c60/filead/1952863678.gz"
        val normalized = AetherSourceResolver.normalizeOpenSubtitlesUrl(raw)
        assertEquals(
            "https://dl.opensubtitles.org/en/download/subencoding-utf8/src-api/vrf-19d60c60/filead/1952863678",
            normalized
        )
        assertEquals("vi", AetherSourceResolver.normalizeSubtitleLanguage("Vietnamese2"))
        assertEquals("vi", AetherSourceResolver.normalizeSubtitleLanguage("Tiếng Việt"))
        assertEquals("en", AetherSourceResolver.normalizeSubtitleLanguage("English HI3"))
        assertEquals("pt", AetherSourceResolver.normalizeSubtitleLanguage("Portuguese (BR)"))
    }

    @Test
    fun filmMediaRelayUnwrapsTsDaChunkAndTrailingIend() {
        val fakeTs = byteArrayOf(0x47, 0x40, 0x11, 0x10, 0x01, 0x02)
        val pngHeader = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)
        fun chunk(type: String, payload: ByteArray): ByteArray {
            val out = ByteArrayOutputStream()
            val len = payload.size
            out.write((len ushr 24) and 0xFF)
            out.write((len ushr 16) and 0xFF)
            out.write((len ushr 8) and 0xFF)
            out.write(len and 0xFF)
            out.write(type.toByteArray(Charsets.US_ASCII))
            out.write(payload)
            out.write(byteArrayOf(0, 0, 0, 0)) // dummy CRC
            return out.toByteArray()
        }

        val tsDaPng = ByteArrayOutputStream().apply {
            write(pngHeader)
            write(chunk("IHDR", ByteArray(13)))
            write(chunk("tsDa", fakeTs))
            write(chunk("IEND", ByteArray(0)))
        }.toByteArray()
        assertArrayEquals(fakeTs, FilmMediaRelay.unwrapPng(tsDaPng))

        val postIendPng = ByteArrayOutputStream().apply {
            write(pngHeader)
            write(chunk("IHDR", ByteArray(13)))
            write(chunk("IEND", ByteArray(0)))
            write(fakeTs)
        }.toByteArray()
        assertArrayEquals(fakeTs, FilmMediaRelay.unwrapPng(postIendPng))
    }

    @Test
    fun resolveMovieAndTvPlaybackProduceNativeVerifiedStreams() {
        val movieEp = Episode(
            title = "The Godfather",
            url = "https://aether.st/media/tmdb-movie-238",
            number = 1,
            season = "Server 1 · Tự động",
            source = ContentSource.AETHER,
            playbackKind = "aether_stream",
            slug = "movie:238",
            episodeKey = "auto"
        )

        var progressCalls = 0
        val lulResolved = AetherSourceResolver.resolve(
            episode = movieEp,
            preferredServerId = "lul",
            onProgress = { _, _, _ -> progressCalls++ }
        )
        assertFalse(lulResolved.isWeb)
        assertTrue(lulResolved.url.startsWith("http"))
        assertEquals(AetherSourceResolver.DEFAULT_ORIGIN, lulResolved.headers["Origin"])
        assertTrue("lul must enable FilmMediaRelay", lulResolved.isF4kWrapped)
        assertTrue(progressCalls >= 1)

        val lyResolved = AetherSourceResolver.resolve(movieEp, preferredServerId = "ly")
        assertFalse(lyResolved.isWeb)
        assertEquals("ly", lyResolved.providerId)
        assertFalse("ly must not use justice proxy directly", lyResolved.url.contains("justice.raccoon"))
        assertEquals("https://vidly.pro", lyResolved.headers["Origin"])
        assertTrue("ly must enable FilmMediaRelay", lyResolved.isF4kWrapped)

        val rockResolved = AetherSourceResolver.resolve(movieEp, preferredServerId = "rock")
        assertFalse(rockResolved.isWeb)
        assertEquals("rock", rockResolved.providerId)
        assertTrue("rock must enable FilmMediaRelay", rockResolved.isF4kWrapped)

        val shoreResolved = AetherSourceResolver.resolve(movieEp, preferredServerId = "shore")
        assertFalse(shoreResolved.isWeb)
        assertTrue("shore must enable FilmMediaRelay", shoreResolved.isF4kWrapped)

        val tvEp = Episode(
            title = "S1E1",
            url = "https://aether.st/media/tmdb-tv-1396/3572/62085",
            number = 1,
            season = "Mùa 1",
            source = ContentSource.AETHER,
            playbackKind = "aether_stream",
            slug = "tv:1396",
            seasonNum = 1,
            episodeNum = 1,
            episodeKey = "auto"
        )
        val tvResolved = AetherSourceResolver.resolve(tvEp, preferredServerId = "ly")
        assertFalse(tvResolved.isWeb)
        assertTrue(tvResolved.url.startsWith("http"))
        assertTrue("TV episode should yield external subtitles", tvResolved.subtitles.isNotEmpty())
        assertTrue(tvResolved.subtitles.none { it.url.endsWith(".gz", ignoreCase = true) })
    }
}
