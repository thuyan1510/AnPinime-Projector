package com.anpinime.projector

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object MovieSourceDirectory {
    const val DIRECTORY_URL = "https://rip.cryboiz.workers.dev/api/add"
    private const val SECRET_SEED = "phim4k@pro@1989"
    private const val PREF_NAME = "anpinime_source2_directory"
    private const val PREF_KEY_JSON = "directory_json"
    private const val PREF_KEY_UPDATED_AT = "updated_at_ms"
    private const val CACHE_TTL_MS = 6 * 60 * 60 * 1000L // 6 hours

    // Client-side configuration decoding key from iOS reference
    private val DIRECTORY_KEY = byteArrayOf(
        206.toByte(), 51, 195.toByte(), 123, 77, 239.toByte(), 26, 201.toByte(),
        30, 27, 136.toByte(), 218.toByte(), 57, 173.toByte(), 133.toByte(), 211.toByte(),
        137.toByte(), 62, 112, 164.toByte(), 243.toByte(), 123, 157.toByte(), 247.toByte(),
        17, 203.toByte(), 108, 161.toByte(), 208.toByte(), 77, 197.toByte(), 2
    )

    const val DEFAULT_CORE_BASE_URL = "https://api.phim4k.space"
    const val DEFAULT_CORE_API_KEY = "bbbb411dea44849"
    const val DEFAULT_FREE1_BASE_URL = "https://free.cryboiz.workers.dev"
    const val DEFAULT_NGUONC_BASE_URL = "https://phim.nguonc.com"
    const val DEFAULT_KKPHIM_BASE_URL = "https://phimapi.com"
    const val DEFAULT_KKPHIM_SITE_URL = "https://kkphim.com"
    const val DEFAULT_CDN_HOST = "svpc.cryboiz.workers.dev"

    data class Config(
        val api: List<String> = listOf(DEFAULT_CORE_BASE_URL),
        val tokens: List<String> = listOf(DEFAULT_CORE_API_KEY),
        val free1: String = DEFAULT_FREE1_BASE_URL,
        val free2: String = "",
        val cdn: String = DEFAULT_CDN_HOST
    ) {
        val coreBaseUrl: String
            get() = api.firstOrNull { it.isNotBlank() }?.trimEnd('/') ?: DEFAULT_CORE_BASE_URL

        val coreApiKey: String
            get() = tokens.firstOrNull { it.isNotBlank() }?.trim() ?: DEFAULT_CORE_API_KEY

        fun toJson(): String = JSONObject().apply {
            put("api", JSONArray(api))
            put("tokens", JSONArray(tokens))
            put("free1", free1)
            put("free2", free2)
            put("cdn", cdn)
        }.toString()

        companion object {
            fun fromJsonObject(obj: JSONObject, fallback: Config = Config()): Config {
                val apiList = ArrayList<String>()
                val apiArr = obj.optJSONArray("api")
                if (apiArr != null) {
                    for (i in 0 until apiArr.length()) {
                        val u = normalizeUrl(apiArr.optString(i))
                        if (u.isNotEmpty()) apiList.add(u)
                    }
                } else {
                    val singleApi = obj.optString("api").trim()
                    if (singleApi.isNotEmpty()) {
                        val u = normalizeUrl(singleApi)
                        if (u.isNotEmpty()) apiList.add(u)
                    }
                }

                val tokenList = ArrayList<String>()
                val tokArr = obj.optJSONArray("tokens")
                if (tokArr != null) {
                    for (i in 0 until tokArr.length()) {
                        val t = tokArr.optString(i).trim()
                        if (t.isNotEmpty()) tokenList.add(t)
                    }
                } else {
                    val singleToken = obj.optString("tokens").trim()
                    if (singleToken.isNotEmpty()) {
                        tokenList.add(singleToken)
                    }
                }

                val f1 = normalizeUrl(obj.optString("free1"))
                val f2 = normalizeUrl(obj.optString("free2"))
                val cdnVal = obj.optString("cdn").trim().ifEmpty { fallback.cdn }

                return Config(
                    api = if (apiList.isNotEmpty()) apiList else fallback.api,
                    tokens = if (tokenList.isNotEmpty()) tokenList else fallback.tokens,
                    free1 = if (f1.isNotEmpty()) f1 else fallback.free1,
                    free2 = if (f2.isNotEmpty()) f2 else fallback.free2,
                    cdn = cdnVal
                )
            }
        }
    }

    @Volatile
    private var memoryConfig: Config? = null

    @Volatile
    private var lastFetchTimeMs: Long = 0L

    fun currentConfig(context: Context?): Config {
        memoryConfig?.let { return it }
        if (context != null) {
            val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            val saved = prefs.getString(PREF_KEY_JSON, null)
            lastFetchTimeMs = prefs.getLong(PREF_KEY_UPDATED_AT, 0L)
            if (!saved.isNullOrBlank()) {
                try {
                    val cfg = Config.fromJsonObject(JSONObject(saved))
                    memoryConfig = cfg
                    return cfg
                } catch (_: Throwable) {
                }
            }
        }
        val def = Config()
        memoryConfig = def
        return def
    }

    fun refreshIfNeeded(
        context: Context?,
        force: Boolean = false,
        fetcher: (String) -> String?
    ): Config {
        val current = currentConfig(context)
        val now = System.currentTimeMillis()
        if (!force && lastFetchTimeMs > 0L && (now - lastFetchTimeMs) < CACHE_TTL_MS) {
            return current
        }

        val rawResponse = try {
            fetcher(DIRECTORY_URL)
        } catch (_: Throwable) {
            null
        } ?: return current

        val merged = decodeConfigPayload(rawResponse, current) ?: return current
        memoryConfig = merged
        lastFetchTimeMs = now
        if (context != null) {
            context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(PREF_KEY_JSON, merged.toJson())
                .putLong(PREF_KEY_UPDATED_AT, now)
                .apply()
        }
        return merged
    }

    fun decodeConfigPayload(rawPayload: String, fallback: Config = Config()): Config? {
        val trimmed = rawPayload.trim()
        if (trimmed.isEmpty()) return null

        return try {
            val outer = JSONObject(trimmed)
            val cipherText = outer.optString("ciphertext").ifEmpty {
                val d = outer.opt("data")
                if (d is String) d else ""
            }
            if (outer.has("iv") && cipherText.isNotEmpty()) {
                val decrypted = decryptAesGcm(
                    ivBase64 = outer.optString("iv"),
                    dataBase64 = cipherText
                ) ?: return null
                val decObj = JSONObject(decrypted)
                val dataObj = decObj.optJSONObject("data") ?: decObj
                Config.fromJsonObject(dataObj, fallback)
            } else {
                val dataObj = outer.optJSONObject("data") ?: outer
                Config.fromJsonObject(dataObj, fallback)
            }
        } catch (_: Throwable) {
            null
        }
    }

    internal fun decodeBase64Url(input: String): ByteArray {
        val clean = input.trim().replace('-', '+').replace('_', '/')
        val padded = clean + "=".repeat((4 - clean.length % 4) % 4)
        return try {
            java.util.Base64.getDecoder().decode(padded)
        } catch (_: Throwable) {
            try {
                Base64.decode(padded, Base64.DEFAULT)
            } catch (_: Throwable) {
                ByteArray(0)
            }
        }
    }

    internal fun decryptAesGcm(ivBase64: String, dataBase64: String): String? {
        return try {
            val iv = decodeBase64Url(ivBase64)
            val sealed = decodeBase64Url(dataBase64)
            if (sealed.size <= 16) return null
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(DIRECTORY_KEY, "AES"), GCMParameterSpec(128, iv))
            String(cipher.doFinal(sealed), Charsets.UTF_8)
        } catch (_: Throwable) {
            try {
                val keyBytes = MessageDigest.getInstance("SHA-256")
                    .digest(SECRET_SEED.toByteArray(Charsets.UTF_8))
                val iv = decodeBase64Url(ivBase64)
                val sealed = decodeBase64Url(dataBase64)
                if (sealed.size <= 16) return null
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, iv))
                String(cipher.doFinal(sealed), Charsets.UTF_8)
            } catch (_: Throwable) {
                null
            }
        }
    }

    private fun normalizeUrl(raw: String?): String {
        val trimmed = raw?.trim()?.trimEnd('/') ?: return ""
        if (trimmed.isEmpty()) return ""
        val withScheme = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            trimmed
        } else {
            "https://$trimmed"
        }
        return withScheme
    }
}
