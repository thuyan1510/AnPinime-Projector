package com.anpinime.projector

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class StreamCResolverTest {

    @Test
    fun extractsPlainJsonBootstrapAndPlaylistFromStreamC() {
        val server = MockWebServer()
        server.start()
        val apiUrl = server.url("/embed.php?hash=abc123").toString()
        val expectedPlaylist = server.url("/eyJ0IjoicGxheWxpc3QifQ").toString()

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.method == "POST") {
                    val reqJson = JSONObject(request.body.readUtf8())
                    assertEquals("https://phim.nguonc.com", reqJson.getJSONArray("frame_origins").getString(0))
                    assertEquals("hls", reqJson.getString("playlist_format"))
                    return when (reqJson.getString("action")) {
                        "bootstrap" -> MockResponse().setBody(
                            JSONObject().put("bootstrap", JSONObject().put("grant", "ok-token")).toString()
                        )
                        "issue" -> {
                            assertEquals("ok-token", reqJson.getJSONObject("bootstrap").getString("grant"))
                            MockResponse().setBody(
                                JSONObject()
                                    .put("playlistFormat", "hls")
                                    .put("playlist", expectedPlaylist)
                                    .toString()
                            )
                        }
                        else -> MockResponse().setResponseCode(400)
                    }
                }
                return MockResponse().setResponseCode(404)
            }
        }

        try {
            val html = """
                <html><body>
                <script id="stream-bootstrap" type="application/json">{"api":"$apiUrl","assets":{}}</script>
                </body></html>
            """.trimIndent()
            val resolved = StreamCResolver.extractFromHtml(
                embedUrl = apiUrl,
                html = html,
                referer = "https://phim.nguonc.com/api/film/test",
                http = OkHttpClient()
            )
            assertNotNull(resolved)
            assertFalse(resolved!!.isWeb)
            assertTrue(resolved.isF4kWrapped)
            assertEquals(StreamMediaKind.HLS, resolved.mediaKind)
            assertEquals(expectedPlaylist, resolved.url)
            assertTrue(resolved.headers.containsKey("Origin"))
            assertTrue(resolved.headers.containsKey("Referer"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun decryptsAesGcmBootstrapEnvelopeWhenProvided() {
        val apiUrl = "https://embed1.streamc.xyz/embed.php?hash=deadbeef"
        val innerJson = """{"grant":"secret-grant","exp":9999999999}"""
        val key = MessageDigest.getInstance("SHA-256")
            .digest("stream-bootstrap-v1\n$apiUrl".toByteArray(Charsets.UTF_8))
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        cipher.updateAAD(apiUrl.toByteArray(Charsets.UTF_8))
        val encPlusTag = cipher.doFinal(innerJson.toByteArray(Charsets.UTF_8))
        val ciphertext = encPlusTag.copyOfRange(0, encPlusTag.size - 16)
        val tag = encPlusTag.copyOfRange(encPlusTag.size - 16, encPlusTag.size)

        val envelope = JSONObject()
            .put("alg", "aesgcm-v1")
            .put("iv", Base64.getEncoder().encodeToString(iv))
            .put("ciphertext", Base64.getEncoder().encodeToString(ciphertext))
            .put("tag", Base64.getEncoder().encodeToString(tag))
        val resp = JSONObject().put("bootstrapEnvelope", envelope)

        val decrypted = StreamCResolver.extractBootstrapObject(resp, apiUrl)
        assertNotNull(decrypted)
        assertEquals("secret-grant", decrypted!!.getString("grant"))
    }
}
