package com.anpinime.projector

import org.junit.Assert.*
import org.junit.Test

class Phim4KVIPResolverTest {

    @Test
    fun isVIPURLIdentifiesVipHosts() {
        assertTrue(Phim4KVIPResolver.isVIPURL("https://phim4k.lol/stream/movie.mkv"))
        assertTrue(Phim4KVIPResolver.isVIPURL("https://vip.phim4k.space/video.m3u8"))
        assertTrue(Phim4KVIPResolver.isVIPURL("https://cdn.p4kzz.workers.dev/play"))
        assertTrue(Phim4KVIPResolver.isVIPURL("https://svatv.cryboiz.workers.dev/gate"))
        assertTrue(Phim4KVIPResolver.isVIPURL("single_file.mkv"))

        assertFalse(Phim4KVIPResolver.isVIPURL("https://free.cryboiz.workers.dev/api"))
        assertFalse(Phim4KVIPResolver.isVIPURL("https://api.phim4k.space/rest-api"))
        assertFalse(Phim4KVIPResolver.isVIPURL("https://phim.nguonc.com/phim/test"))
        assertFalse(Phim4KVIPResolver.isVIPURL("https://phimapi.com/phim/test"))
    }

    @Test
    fun extractFileIDExtractsCleanId() {
        assertEquals("movie", Phim4KVIPResolver.extractFileID("https://phim4k.lol/stream/movie.mkv?token=123#frag"))
        assertEquals("E6E8NBA94K72", Phim4KVIPResolver.extractFileID("https://cdn.phim4k.lol/E6E8NBA94K72"))
        assertEquals("12345", Phim4KVIPResolver.extractFileID("https://phim4k.lol/play/12345/"))
    }

    @Test
    fun deriveDailySecretProducesExpectedHexFormat() {
        val secret = Phim4KVIPResolver.HMAC_SECRET
        val dateUtc = "20261104"
        val derived = Phim4KVIPResolver.deriveDailySecret(secret, dateUtc)
        assertNotNull(derived)
        assertTrue(derived!!.startsWith("$secret:"))
        val hexPart = derived.substringAfter(":")
        // AES-GCM ciphertext + 16-byte tag
        assertTrue(hexPart.length >= 32)
    }

    @Test
    fun buildGatewayURLBuildsValidSignedUrl() {
        val fileId = "E6E8NBA94K72"
        val fixedTs = 1730736000L
        val url = Phim4KVIPResolver.buildGatewayURL(fileId, timestampSec = fixedTs)
        assertTrue(url.startsWith("https://svatv.cryboiz.workers.dev/E6E8NBA94K72?token="))
        assertTrue(url.contains("&ts="))

        val tsParam = url.substringAfter("&ts=").substringBefore('&')
        // tsParam is 4 bytes hex = 8 characters
        assertEquals(8, tsParam.length)

        val tokenParam = url.substringAfter("token=").substringBefore('&')
        // SHA-256 HMAC is 32 bytes = 64 hex characters
        assertEquals(64, tokenParam.length)
    }

    @Test
    fun movieSourceDirectoryDecryptsEnvelope() {
        val envelopeJson = """
        {
          "alg": "AES-GCM-256",
          "encoding": "base64url",
          "iv": "vE2T2_94W_6eDq6X",
          "data": {
            "api": "api.phim4k.space",
            "cdn": "svpc.cryboiz.workers.dev",
            "free1": "free.cryboiz.workers.dev",
            "tokens": "bbbb411dea44849"
          }
        }
        """.trimIndent()
        val cfg = MovieSourceDirectory.decodeConfigPayload(envelopeJson)
        assertNotNull(cfg)
        assertEquals("https://api.phim4k.space", cfg!!.coreBaseUrl)
        assertEquals("bbbb411dea44849", cfg.coreApiKey)
        assertEquals("https://free.cryboiz.workers.dev", cfg.free1)
        assertEquals("svpc.cryboiz.workers.dev", cfg.cdn)
    }
}
