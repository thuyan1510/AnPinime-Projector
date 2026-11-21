package com.anpinime.projector

import org.junit.Assert.*
import org.junit.Test

class PlaybackSourceParserTest {
    private val base = "https://example.test/watch/episode"

    @Test
    fun kkPhimNestedEpisodesWithNullManifestKeepThePlayableEmbed() {
        val raw = """
            {"data":{"item":{"name":"Test movie"},"episodes":[{"server_name":"Vietsub","server_data":[
                {"name":"Full","slug":"full","link_m3u8":null,"link_embed":"//player.test.invalid/film"}
            ]}]}}
        """.trimIndent()
        val detailUrl = "https://api.test.invalid/phim/test-movie"
        val detail = MovieCatalog.parseDetail(raw, detailUrl, "https://api.test.invalid", source = ContentSource.KKPHIM)
        val episode = checkNotNull(detail).episodes.single()
        assertEquals("https://player.test.invalid/film", episode.directUrl)
        assertEquals(episode.directUrl, episode.refererUrl)
        val stream = checkNotNull(MovieCatalog.extractStreamFromDetail(raw, episode))
        assertEquals(episode.directUrl, stream.primaryUrl)
        assertFalse(stream.isDirectM3u8)
    }

    @Test fun nestedRelativeManifest() {
        val source = PlaybackSourceParser.parse("""{"player":{"sources":[{"file":"/stream/hls?id=1"}]}}""", base)!!
        assertEquals("https://example.test/stream/hls?id=1", source.url)
        assertFalse(source.isWeb)
    }

    @Test fun encryptedPayloadMustNotBecomeRelativeUrl() {
        assertNull(PlaybackSourceParser.parse("""{"data":"Abcdef0123456789=="}""", base))
    }

    @Test fun prefersMediaOverEmbed() {
        val source = PlaybackSourceParser.parse("""{"sources":["https://example.test/embed",{"src":"https://cdn.test/master.m3u8?token=abc"}]}""", base)!!
        assertFalse(source.isWeb)
        assertEquals("https://cdn.test/master.m3u8?token=abc", source.url)
    }

    @Test fun htmlIframeIgnoresScriptUrl() {
        val source = PlaybackSourceParser.parse("""<script src="https://ads.test/a.js"></script><iframe src="/embed?id=1&amp;ep=2"></iframe>""", base)!!
        assertEquals("https://example.test/embed?id=1&ep=2", source.url)
        assertTrue(source.isWeb)
    }

    @Test fun rejectsScriptOnlyPage() {
        assertNull(PlaybackSourceParser.parse("""<script src="https://ads.test/a.js"></script>""", base))
    }

    @Test fun htmlSourceAndEscapedJson() {
        assertFalse(PlaybackSourceParser.parse("""<video><source src="//cdn.test/file.mp4"></video>""", base)!!.isWeb)
        assertEquals("https://cdn.test/a.m3u8", PlaybackSourceParser.parse("""{"file":"https:\/\/cdn.test\/a.m3u8"}""", base)!!.url)
    }
}
