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

        val match = BOOTSTRAP_TAG_REGEX.find(html) ?: return null
        val config = runCatching { JSONObject(match.groupValues[1].trim()) }.getOrNull() ?: return null
        val rawApi = config.optString("api").trim().ifEmpty { embedUrl }
        val apiUrl = resolveUrl(embedUrl, rawApi)
        val embedOrigin = originOf(embedUrl)
        val frameOrigin = originOf(referer.ifBlank { "https://phim.nguonc.com/" })
        val frameRef = "${frameOrigin.trimEnd('/')}/"

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
        val bootstrapObj = extractBootstrapObject(bootResp, apiUrl) ?: return null

        val issuePayload = JSONObject().apply {
            put("action", "issue")
            put("bootstrap", bootstrapObj)
            put("turnstile_response", JSONObject.NULL)
            put("playlist_format", "hls")
            put("pretty_url", true)
            put("path_chunks", true)
            put("frame_origins", JSONArray().put(frameOrigin))
        }

        val issueResp = postJson(apiUrl, issuePayload, embedOrigin, embedUrl, userAgent, http) ?: return null
        if (issueResp.optBoolean("turnstileRequired", false)) return null

        val rawPlaylist = issueResp.optString("playlist").trim()
        if (rawPlaylist.isEmpty()) return null
        val playlistUrl = resolveUrl(apiUrl, rawPlaylist)

        val streamHeaders = linkedMapOf(
            "Origin" to embedOrigin,
            "Referer" to "${embedOrigin.trimEnd('/')}/",
            "User-Agent" to userAgent
        )

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

    internal fun extractBootstrapObject(resp: JSONObject, apiUrl: String): JSONObject? {
        resp.optJSONObject("bootstrap")?.let { return it }
        val env = resp.optJSONObject("bootstrapEnvelope") ?: return null
        return decryptBootstrapEnvelope(env, apiUrl)
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
