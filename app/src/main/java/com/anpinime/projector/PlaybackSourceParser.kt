package com.anpinime.projector

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

/** Parses media fields before considering HTML so script/advert URLs cannot win. */
internal object PlaybackSourceParser {
    data class Source(val url: String, val isWeb: Boolean)

    fun parse(text: String, base: String): Source? {
        fun absolute(raw: String): String? = try {
            URI(base).resolve(raw.trim().replace("&amp;", "&")).toString()
                .takeIf { it.startsWith("https://") || it.startsWith("http://") }
        } catch (_: Exception) { null }
        fun media(url: String) = Regex("(?i)\\.(m3u8|mp4|mkv|webm)([?#]|$)|/stream/hls").containsMatchIn(url)
        fun item(value: Any?, depth: Int = 0): Source? {
            if (depth > 8) return null
            return when (value) {
                is String -> value.takeIf { it.startsWith("http://") || it.startsWith("https://") ||
                    it.startsWith("/") || media(it) }?.let { absolute(it) }?.let { Source(it, !media(it)) }
                is JSONArray -> (0 until value.length()).mapNotNull { item(value.opt(it), depth + 1) }
                    .let { sources -> sources.firstOrNull { !it.isWeb } ?: sources.firstOrNull() }
                is JSONObject -> listOf("sources", "player", "file", "src", "url", "data", "link")
                    .mapNotNull { item(value.opt(it), depth + 1) }
                    .let { sources -> sources.firstOrNull { !it.isWeb } ?: sources.firstOrNull() }
                else -> null
            }
        }
        val clean = text.trim().replace("\\/", "/")
        try {
            val json: Any = if (clean.startsWith("[")) JSONArray(clean) else JSONObject(clean)
            item(json)?.let { return it }
        } catch (_: Exception) {}
        for ((tag, web) in listOf("(?:video|source)" to false, "iframe" to true)) {
            val match = Regex("""(?is)<$tag\b[^>]*\bsrc\s*=\s*["']([^"']+)["']""").find(clean)
            match?.groupValues?.get(1)?.let { absolute(it) }?.let { return Source(it, web) }
        }
        Regex("""https?://[^\s"'<>]+\.(?:m3u8|mp4|mkv|webm)[^\s"'<>]*""", RegexOption.IGNORE_CASE)
            .find(clean)?.value?.let { absolute(it) }?.let { return Source(it, false) }
        if (!clean.contains(Regex("[\\s<>]")) && (clean.startsWith("https://") || clean.startsWith("http://"))) {
            absolute(clean)?.let { return Source(it, !media(it)) }
        }
        return null
    }
}
