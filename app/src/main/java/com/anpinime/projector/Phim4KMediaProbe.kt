package com.anpinime.projector

import okhttp3.OkHttpClient
import okhttp3.Request

object Phim4KMediaProbe {
    const val PROBE_BYTE_LIMIT = 2048

    fun mediaKind(url: String, contentType: String? = null): StreamMediaKind {
        val lowerUrl = url.lowercase()
        val pathPart = lowerUrl.substringBefore('?').substringBefore('#')
        val ct = (contentType ?: "").lowercase()

        if (ct.contains("mpegurl") || ct.contains("x-mpegurl") || pathPart.endsWith(".m3u8") || lowerUrl.contains(".m3u8")) {
            return StreamMediaKind.HLS
        }
        if (ct.contains("dash+xml") || pathPart.endsWith(".mpd") || lowerUrl.contains(".mpd")) {
            return StreamMediaKind.DASH
        }
        if (ct.contains("matroska") || ct.contains("x-matroska") || pathPart.endsWith(".mkv") || lowerUrl.contains(".mkv")) {
            return StreamMediaKind.MKV
        }
        if (ct.contains("video/mp4") || ct.contains("video/quicktime") || ct.contains("video/webm") ||
            pathPart.endsWith(".mp4") || pathPart.endsWith(".m4v") || pathPart.endsWith(".mov") || pathPart.endsWith(".webm")
        ) {
            return StreamMediaKind.MP4
        }
        return StreamMediaKind.UNKNOWN
    }

    fun shouldFallbackToRangeProbe(statusCode: Int, contentType: String?, url: String): Boolean {
        if (statusCode in listOf(401, 403, 405, 501)) return true
        if (statusCode in 200..299) {
            val ct = (contentType ?: "").lowercase()
            if (ct.contains("text/html") || ct.contains("application/json")) return true
            return mediaKind(url, contentType) == StreamMediaKind.UNKNOWN
        }
        return false
    }

    fun classifyRangeProbe(
        statusCode: Int,
        contentType: String?,
        firstBytes: ByteArray,
        url: String
    ): StreamMediaKind? {
        if (statusCode !in 200..299 && statusCode != 206) return null
        val ct = (contentType ?: "").lowercase()
        if (ct.contains("text/html") || ct.contains("application/json")) return null

        // 1. Check binary magic signatures first
        if (firstBytes.size >= 4 &&
            firstBytes[0] == 0x1A.toByte() &&
            firstBytes[1] == 0x45.toByte() &&
            firstBytes[2] == 0xDF.toByte() &&
            firstBytes[3] == 0xA3.toByte()
        ) {
            return StreamMediaKind.MKV
        }

        if (firstBytes.size >= 8 &&
            firstBytes[4] == 'f'.code.toByte() &&
            firstBytes[5] == 't'.code.toByte() &&
            firstBytes[6] == 'y'.code.toByte() &&
            firstBytes[7] == 'p'.code.toByte()
        ) {
            return StreamMediaKind.MP4
        }

        val asciiPrefix = String(
            firstBytes,
            0,
            minOf(firstBytes.size, 256),
            Charsets.ISO_8859_1
        ).trimStart()

        if (asciiPrefix.startsWith("#EXTM3U", ignoreCase = true)) {
            return StreamMediaKind.HLS
        }
        if (asciiPrefix.startsWith("<MPD", ignoreCase = true) ||
            (asciiPrefix.startsWith("<?xml", ignoreCase = true) && asciiPrefix.contains("<MPD", ignoreCase = true))
        ) {
            return StreamMediaKind.DASH
        }
        if (asciiPrefix.startsWith("{") || asciiPrefix.startsWith("[") ||
            asciiPrefix.startsWith("<!doctype", ignoreCase = true) ||
            asciiPrefix.startsWith("<html", ignoreCase = true) ||
            asciiPrefix.startsWith("error", ignoreCase = true)
        ) {
            return null
        }

        val byMeta = mediaKind(url, contentType)
        if (byMeta != StreamMediaKind.UNKNOWN) return byMeta

        if ((statusCode == 206 || ct.startsWith("video/") || ct.contains("octet-stream")) && firstBytes.size >= 16) {
            return StreamMediaKind.MP4
        }
        return null
    }

    fun probeStream(
        client: OkHttpClient,
        url: String,
        headers: Map<String, String> = emptyMap()
    ): StreamMediaKind? {
        val known = mediaKind(url, null)
        // Perform lightweight HEAD first
        var headStatus = -1
        var headContentType: String? = null
        try {
            val headBuilder = Request.Builder().url(url).head()
            headers.forEach { (k, v) -> headBuilder.header(k, v) }
            client.newCall(headBuilder.build()).execute().use { resp ->
                headStatus = resp.code
                headContentType = resp.header("Content-Type")
            }
        } catch (_: Throwable) {
        }

        if (headStatus in 200..299 && !shouldFallbackToRangeProbe(headStatus, headContentType, url)) {
            val kind = mediaKind(url, headContentType)
            if (kind != StreamMediaKind.UNKNOWN) return kind
        }

        // Bounded Range GET (0..2047 bytes)
        return try {
            val getBuilder = Request.Builder()
                .url(url)
                .header("Range", "bytes=0-${PROBE_BYTE_LIMIT - 1}")
            headers.forEach { (k, v) -> getBuilder.header(k, v) }
            client.newCall(getBuilder.build()).execute().use { resp ->
                val ct = resp.header("Content-Type")
                val stream = resp.body?.byteStream()
                val buf = ByteArray(PROBE_BYTE_LIMIT)
                var totalBytes: Int = 0
                if (stream != null) {
                    while (totalBytes < PROBE_BYTE_LIMIT) {
                        val n: Int = stream.read(buf, totalBytes, PROBE_BYTE_LIMIT - totalBytes)
                        if (n <= 0) break
                        totalBytes += n
                    }
                }
                val slice = buf.copyOf(totalBytes)
                classifyRangeProbe(resp.code, ct, slice, url)
            }
        } catch (_: Throwable) {
            if (known != StreamMediaKind.UNKNOWN) known else null
        }
    }
}
