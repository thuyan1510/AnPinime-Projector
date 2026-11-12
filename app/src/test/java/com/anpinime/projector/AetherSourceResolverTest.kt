package com.anpinime.projector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AetherSourceResolverTest {

    @Test
    fun serversListContainsExpectedEntries() {
        val servers = AetherSourceResolver.SERVERS
        assertTrue(servers.isNotEmpty())
        assertEquals("auto", servers[0].id)
        assertTrue(servers.any { it.id == "lul" })
        assertTrue(servers.any { it.id == "lil" })
        assertTrue(servers.any { it.id == "ly" })
        assertTrue(servers.any { it.id == "meridian" })
        assertTrue(servers.any { it.id == "vfast_supreme" })
    }

    @Test
    fun resolveMoviePlaybackProducesNativeResolvedPlayback() {
        val episode = Episode(
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
        val resolved = AetherSourceResolver.resolve(
            episode = episode,
            preferredServerId = "lul",
            onProgress = { _, _, _ -> progressCalls++ }
        )

        assertNotNull(resolved)
        assertFalse("Playback must be native, not web fallback", resolved.isWeb)
        assertTrue("Stream URL must be valid", resolved.url.startsWith("http"))
        assertTrue("Must include Origin header", resolved.headers.containsKey("Origin"))
        assertEquals(AetherSourceResolver.DEFAULT_ORIGIN, resolved.headers["Origin"])
    }
}
