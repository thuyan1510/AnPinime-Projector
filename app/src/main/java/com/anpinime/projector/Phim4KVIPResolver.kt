package com.anpinime.projector

import android.net.Uri
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URI
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Resolves Phim4K VIP / 4K / 1080p stream URLs through the time-authenticated CDN gateway.
 * Converted directly from iOS Phim4KVIPResolver.swift with exact daily AES-GCM and OTP HMAC signing.
 */
object Phim4KVIPResolver {
    const val HMAC_SECRET = "5e8d1b4f9c2a6e730b1f8d4a92c5e3d1"
    const val PRIMARY_GATEWAY = "https://svatv.cryboiz.workers.dev"
    const val FALLBACK_GATEWAY = "https://svpc.cryboiz.workers.dev"

    fun isVIPURL(rawUrl: String): Boolean {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) return false
        if (trimmed.startsWith("phim4k-vip://", ignoreCase = true)) return true
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            return trimmed.endsWith(".mkv", ignoreCase = true) ||
                trimmed.endsWith(".mp4", ignoreCase = true) ||
                !trimmed.contains("/")
        }
        val host = try {
            URI(trimmed).host?.lowercase() ?: ""
        } catch (_: Throwable) {
            try {
                Uri.parse(trimmed).host?.lowercase() ?: ""
            } catch (_: Throwable) {
                ""
            }
        }
        if (host.isEmpty()) return false
        if (host == "free.cryboiz.workers.dev" || host == "rip.cryboiz.workers.dev" || host == "api.phim4k.space") {
            return false
        }
        return host.contains("phim4k.lol") ||
            host.contains("phim4k.space") ||
            host.contains("p4kzz.workers.dev") ||
            host.contains("cryboiz.workers.dev")
    }

    fun extractFileID(rawUrl: String): String {
        val clean = rawUrl.substringBefore('?').substringBefore('#').trimEnd('/')
        var fileId = clean.substringAfterLast('/')
        val dotIdx = fileId.lastIndexOf('.')
        if (dotIdx > 0) {
            fileId = fileId.substring(0, dotIdx)
        }
        return fileId.trim()
    }

    /**
     * Derives the daily signing key using AES-GCM-256 over secret:dateUtc.
     * Matches iOS Phim4KVIPResolver.deriveDailySecret exactly.
     */
    fun deriveDailySecret(secret: String, dateUtc: String): String? {
        return try {
            val keyBytes = MessageDigest.getInstance("SHA-256").digest(secret.toByteArray(Charsets.UTF_8))
            val ivSource = MessageDigest.getInstance("SHA-256")
                .digest(("iv:$secret").toByteArray(Charsets.UTF_8))
                .copyOfRange(0, 12)
            val plaintext = "$secret:$dateUtc".toByteArray(Charsets.UTF_8)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, ivSource))
            val sealed = cipher.doFinal(plaintext)
            val hex = sealed.joinToString("") { "%02x".format(it) }
            "$secret:$hex"
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Builds signed CDN gateway URL with OTP timestamp masking and HMAC-SHA256 token.
     * Matches iOS Phim4KVIPResolver.buildGatewayURL exactly.
     */
    fun buildGatewayURL(
        fileID: String,
        gateway: String = PRIMARY_GATEWAY,
        timestampSec: Long? = null
    ): String {
        val sdf = SimpleDateFormat("yyyyMMdd", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val dateUtc = sdf.format(Date())
        val signingSecret = deriveDailySecret(HMAC_SECRET, dateUtc) ?: HMAC_SECRET
        val nowSec = (timestampSec ?: (System.currentTimeMillis() / 1000L)).toInt()

        val sigKey = SecretKeySpec(signingSecret.toByteArray(Charsets.UTF_8), "HmacSHA256")
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(sigKey)
        val code = mac.doFinal("otp-ts-mask".toByteArray(Charsets.UTF_8))
        val mask = code.copyOfRange(0, 4)

        val rawBytes = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(nowSec).array()
        val xored = ByteArray(4) { i -> (rawBytes[i].toInt() xor mask[i].toInt()).toByte() }
        val tsHex = xored.joinToString("") { "%02x".format(it) }

        val msg = "$fileID:$nowSec"
        mac.reset()
        mac.init(sigKey)
        val tokenCode = mac.doFinal(msg.toByteArray(Charsets.UTF_8))
        val token = tokenCode.joinToString("") { "%02x".format(it) }

        val baseGw = gateway.trimEnd('/')
        return "$baseGw/$fileID?token=$token&ts=$tsHex"
    }

    /**
     * Resolves the final fast direct CDN stream URL via the gateway.
     */
    fun resolveStreamUrl(client: OkHttpClient, rawUrlOrID: String): String? {
        val trimmed = rawUrlOrID.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.contains("sv.p4kzz.workers.dev", ignoreCase = true)) {
            return trimmed
        }
        val fileID = extractFileID(trimmed)
        if (fileID.isEmpty()) {
            return trimmed.takeIf { it.startsWith("http") }
        }

        // Try primary gateway (svatv) then fallback gateway (svpc)
        val gateways = listOf(PRIMARY_GATEWAY, FALLBACK_GATEWAY)
        var lastSignedUrl: String? = null

        for (gw in gateways) {
            val signedUrl = buildGatewayURL(fileID = fileID, gateway = gw)
            lastSignedUrl = signedUrl
            try {
                val req = Request.Builder()
                    .url(signedUrl)
                    .header("User-Agent", "okhttp/4.9.0")
                    .header("Accept", "application/json")
                    .build()
                val fastUrl: String? = client.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val body = resp.body?.string().orEmpty().trim()
                        if (body.startsWith("{")) {
                            val json = JSONObject(body)
                            val u = json.optString("url").trim()
                            if (u.startsWith("http://") || u.startsWith("https://")) {
                                u
                            } else null
                        } else null
                    } else null
                }
                if (!fastUrl.isNullOrEmpty()) {
                    return fastUrl
                }
            } catch (_: Throwable) {
                // Try next gateway
            }
        }

        return lastSignedUrl ?: trimmed.takeIf { it.startsWith("http") }
    }
}
