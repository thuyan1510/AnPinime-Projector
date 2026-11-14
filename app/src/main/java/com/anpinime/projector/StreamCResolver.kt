package com.anpinime.projector

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object StreamCResolver {
    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    private val BOOTSTRAP_TAG_REGEX = Regex(
        """<script[^>]*id=["']stream-bootstrap["'][^>]*>(.*?)</script>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )

    fun isStreamCEmbed(embedUrl: String, html: String? = null): Boolean {
        val lower = embedUrl.lowercase()
        if (lower.contains("streamc.xyz")) return true
        if (lower.contains("/embed.php") && (lower.contains("?hash=") || lower.contains("&hash="))) return true
        return !html.isNullOrBlank() && html.contains("id=\"stream-bootstrap\"", ignoreCase = true)
    }

    fun extractFromHtml(
        embedUrl: String,
        html: String,
        referer: String,
        http: OkHttpClient,
        userAgent: String = CatalogService.USER_AGENT
    ): ResolvedPlayback? {
        if (!isStreamCEmbed(embedUrl, html)) return null

        val match = BOOTSTRAP_TAG_REGEX.find(html)
        val config = match?.let { runCatching { JSONObject(it.groupValues[1].trim()) }.getOrNull() }
        val rawApi = config?.optString("api")?.trim().orEmpty().ifEmpty { embedUrl }
        val apiUrl = resolveUrl(embedUrl, rawApi)
        val embedOrigin = originOf(embedUrl)
        val frameOrigin = originOf(referer.ifBlank { "https://phim.nguonc.com/" })
        val frameRef = "${frameOrigin.trimEnd('/')}/"

        val streamHeaders = linkedMapOf(
            "Origin" to embedOrigin,
            "Referer" to "${embedOrigin.trimEnd('/')}/",
            "User-Agent" to userAgent
        )
        val videoHash = Regex("""[?&]hash=([a-zA-Z0-9_-]+)""")
            .find(embedUrl)
            ?.groupValues
            ?.getOrNull(1)
            .orEmpty()
        if (videoHash.isNotEmpty()) {
            streamHeaders["X-StreamC-Hash"] = videoHash
        }

        val bootstrapPayload = JSONObject().apply {
            put("action", "bootstrap")
            put("referrer", frameRef)
            put("frame_origins", JSONArray().put(frameOrigin))
            put("request_grant", true)
            put("playlist_format", "hls")
            put("pretty_url", true)
            put("path_chunks", true)
            put("bootstrap_format", "json")
        }

        val bootResp = postJson(apiUrl, bootstrapPayload, embedOrigin, embedUrl, userAgent, http) ?: return null

        // Fast-path: StreamC r25+ returns preissued.playlist directly in bootstrap response
        if (!bootResp.optBoolean("turnstileEnabled", false)) {
            val preissuedPlaylist = bootResp.optJSONObject("preissued")
                ?.optString("playlist")
                ?.trim()
                .orEmpty()
            if (preissuedPlaylist.isNotEmpty() && preissuedPlaylist != "null") {
                val playlistUrl = resolveUrl(apiUrl, preissuedPlaylist)
                return ResolvedPlayback(
                    url = playlistUrl,
                    headers = streamHeaders,
                    isF4kWrapped = true,
                    isWeb = false,
                    webUrl = embedUrl,
                    mediaKind = StreamMediaKind.HLS,
                    providerId = "nguonc_streamc"
                )
            }
        }

        val bootstrapToken = extractBootstrapToken(bootResp, apiUrl) ?: return null

        val issuePayload = JSONObject().apply {
            put("action", "issue")
            put("bootstrap", bootstrapToken)
            put("turnstile_response", JSONObject.NULL)
            put("playlist_format", "hls")
            put("pretty_url", true)
            put("path_chunks", true)
            put("frame_origins", JSONArray().put(frameOrigin))
        }

        val issueResp = postJson(apiUrl, issuePayload, embedOrigin, embedUrl, userAgent, http) ?: return null
        if (issueResp.optBoolean("turnstileRequired", false)) return null

        val rawPlaylist = issueResp.optString("playlist").trim()
        if (rawPlaylist.isEmpty() || rawPlaylist == "null") return null
        val playlistUrl = resolveUrl(apiUrl, rawPlaylist)

        return ResolvedPlayback(
            url = playlistUrl,
            headers = streamHeaders,
            isF4kWrapped = true,
            isWeb = false,
            webUrl = embedUrl,
            mediaKind = StreamMediaKind.HLS,
            providerId = "nguonc_streamc"
        )
    }

    internal fun extractBootstrapToken(resp: JSONObject, apiUrl: String): Any? {
        resp.optJSONObject("bootstrap")?.let { return it }
        val tokenStr = resp.optString("bootstrap").trim()
        if (tokenStr.isNotEmpty() && tokenStr != "null") {
            return tokenStr
        }
        val env = resp.optJSONObject("bootstrapEnvelope") ?: return null
        return decryptBootstrapEnvelope(env, apiUrl)
    }

    internal fun extractBootstrapObject(resp: JSONObject, apiUrl: String): JSONObject? {
        resp.optJSONObject("bootstrap")?.let { return it }
        val env = resp.optJSONObject("bootstrapEnvelope") ?: return null
        return decryptBootstrapEnvelope(env, apiUrl)
    }

    /**
     * Unwraps StreamC `#ENC-AESGCM;iv=<24-hex>` encrypted M3U8 playlists if present.
     */
    fun unwrapEncryptedM3u8IfNeeded(rawText: String, videoHash: String? = null): String {
        val text = rawText.trim()
        if (!text.startsWith("#ENC-AESGCM;iv=", ignoreCase = true)) return rawText
        val hash = videoHash?.trim().orEmpty()
        if (hash.isEmpty()) return rawText

        val newlineIdx = text.indexOf('\n')
        if (newlineIdx <= 0) return rawText
        val header = text.substring(0, newlineIdx).trim()
        val bodyB64 = text.substring(newlineIdx + 1).replace(Regex("\\s+"), "")
        val ivHex = header.substringAfter("iv=", "").trim()
        if (ivHex.length != 24 || bodyB64.isEmpty()) return rawText

        return runCatching {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec("stream-derive-v1".toByteArray(Charsets.UTF_8), "HmacSHA256"))
            val fullHmac = mac.doFinal(hash.toByteArray(Charsets.UTF_8))
            val keyBytes = fullHmac.copyOfRange(0, 16)
            val iv = ByteArray(12) { i ->
                val hi = Character.digit(ivHex[i * 2], 16)
                val lo = Character.digit(ivHex[i * 2 + 1], 16)
                ((hi shl 4) or lo).toByte()
            }
            val cipherBytes = Base64.getDecoder().decode(bodyB64)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, iv))
            String(cipher.doFinal(cipherBytes), Charsets.UTF_8)
        }.getOrDefault(rawText)
    }

    internal fun decryptBootstrapEnvelope(env: JSONObject, apiUrl: String): JSONObject? {
        return runCatching {
            val iv = Base64.getDecoder().decode(env.optString("iv"))
            val cipherBytes = Base64.getDecoder().decode(env.optString("ciphertext"))
            val tag = Base64.getDecoder().decode(env.optString("tag"))
            val combined = ByteArray(cipherBytes.size + tag.size).apply {
                System.arraycopy(cipherBytes, 0, this, 0, cipherBytes.size)
                System.arraycopy(tag, 0, this, cipherBytes.size, tag.size)
            }
            val keyMaterial = "stream-bootstrap-v1\n$apiUrl".toByteArray(Charsets.UTF_8)
            val key = MessageDigest.getInstance("SHA-256").digest(keyMaterial)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
            cipher.updateAAD(apiUrl.toByteArray(Charsets.UTF_8))
            val plain = cipher.doFinal(combined)
            JSONObject(String(plain, Charsets.UTF_8))
        }.getOrNull()
    }

    private fun postJson(
        apiUrl: String,
        bodyJson: JSONObject,
        origin: String,
        referer: String,
        userAgent: String,
        http: OkHttpClient
    ): JSONObject? {
        return runCatching {
            val req = Request.Builder()
                .url(apiUrl)
                .post(bodyJson.toString().toRequestBody(JSON_MEDIA))
                .header("User-Agent", userAgent)
                .header("Accept", "application/json")
                .header("Origin", origin)
                .header("Referer", referer)
                .build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@runCatching null
                val raw = resp.body?.string().orEmpty().trim()
                if (raw.startsWith("{")) JSONObject(raw) else null
            }
        }.getOrNull()
    }

    private fun originOf(url: String): String {
        return try {
            val u = URI(url)
            val port = if (u.port > 0) ":${u.port}" else ""
            "${u.scheme ?: "https"}://${u.host}$port"
        } catch (_: Throwable) {
            "https://embed1.streamc.xyz"
        }
    }

    private fun resolveUrl(base: String, target: String): String {
        if (target.startsWith("http://") || target.startsWith("https://")) return target
        return try {
            URI(base).resolve(target).toString()
        } catch (_: Throwable) {
            target
        }
    }
}
