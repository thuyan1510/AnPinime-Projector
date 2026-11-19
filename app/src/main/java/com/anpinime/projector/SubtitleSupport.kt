package com.anpinime.projector

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream

/**
 * Unified subtitle decoding, MIME detection, SRT normalization, and ExoPlayer DataSource support.
 * Ensures remote (.srt/.vtt/.ass) tracks (OpenSubtitles, VDRK, Aether) and local SAF content:// or
 * file:// subtitle files decode cleanly regardless of BOM, CRLF, encoding (Windows-1258/1252, UTF-16),
 * gzip compression, or non-standard SRT cue formatting.
 */
object SubtitleSupport {

    private val FLEXIBLE_TIMELINE_REGEX = Regex(
        """^\s*(\d{1,3}(?::\d{1,2}){1,2}(?:[.,:]\d{1,4})?)\s*-->\s*(\d{1,3}(?::\d{1,2}){1,2}(?:[.,:]\d{1,4})?)(.*)$"""
    )
    private val VTT_VOICE_OR_CLASS_TAG_REGEX = Regex("""</?(?:v(?:\s+[^>]*)?|c(?:\.[^>]*)?|ruby|rt|lang(?:\s+[^>]*)?)>""", RegexOption.IGNORE_CASE)
    private val VTT_INLINE_TIMESTAMP_REGEX = Regex("""<\d{1,2}:\d{2}(?::\d{2})?[.,]\d{1,3}>""")
    private val ASS_NON_POS_TAG_REGEX = Regex("""\{(?!\\an[1-9]\})[^}]*\}""")
    private val DIGITS_ONLY_REGEX = Regex("""^\d+$""")

    private val WINDOWS_1258: Charset? = runCatching { Charset.forName("windows-1258") }.getOrNull()
    private val WINDOWS_1252: Charset? = runCatching { Charset.forName("windows-1252") }.getOrNull()

    private class InMemoryCookieJar : CookieJar {
        private val store = ConcurrentHashMap<String, MutableList<Cookie>>()

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            if (cookies.isEmpty()) return
            val host = url.host.lowercase(Locale.US)
            val list = store.getOrPut(host) { mutableListOf() }
            synchronized(list) {
                for (cookie in cookies) {
                    list.removeAll { it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path }
                    list.add(cookie)
                }
            }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val now = System.currentTimeMillis()
            val reqHost = url.host.lowercase(Locale.US)
            val matched = ArrayList<Cookie>()
            for ((host, cookies) in store) {
                if (reqHost == host || reqHost.endsWith(".$host")) {
                    synchronized(cookies) {
                        cookies.removeAll { it.expiresAt < now }
                        for (c in cookies) {
                            if (c.matches(url)) matched.add(c)
                        }
                    }
                }
            }
            return matched
        }
    }

    data class NormalizedCue(
        val startMs: Long,
        val endMs: Long,
        val text: String
    )

    fun decompressGzipIfNeeded(rawBytes: ByteArray): ByteArray {
        if (rawBytes.size >= 2 && rawBytes[0] == 0x1F.toByte() && rawBytes[1] == 0x8B.toByte()) {
            val decompressed = runCatching {
                GZIPInputStream(ByteArrayInputStream(rawBytes)).use { it.readBytes() }
            }.getOrNull()
            if (decompressed != null && decompressed.isNotEmpty()) {
                return decompressed
            }
        }
        return rawBytes
    }

    fun decodeSubtitleBytes(rawBytes: ByteArray): String {
        if (rawBytes.isEmpty()) return ""
        val bytes = decompressGzipIfNeeded(rawBytes)
        if (bytes.isEmpty()) return ""

        var offset = 0
        var detectedCharset: Charset? = null
        when {
            bytes.size >= 3 &&
                bytes[0] == 0xEF.toByte() &&
                bytes[1] == 0xBB.toByte() &&
                bytes[2] == 0xBF.toByte() -> {
                detectedCharset = Charsets.UTF_8
                offset = 3
            }
            bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() -> {
                detectedCharset = Charsets.UTF_16BE
                offset = 2
            }
            bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() -> {
                detectedCharset = Charsets.UTF_16LE
                offset = 2
            }
        }

        if (detectedCharset == null && bytes.size >= 8) {
            val sampleLen = minOf(bytes.size, 256)
            var evenZeroes = 0
            var oddZeroes = 0
            for (i in 0 until sampleLen) {
                if (bytes[i].toInt() == 0) {
                    if (i % 2 == 0) evenZeroes++ else oddZeroes++
                }
            }
            val threshold = sampleLen / 6
            if (evenZeroes > threshold && oddZeroes <= 2) detectedCharset = Charsets.UTF_16BE
            else if (oddZeroes > threshold && evenZeroes <= 2) detectedCharset = Charsets.UTF_16LE
        }

        val text = if (detectedCharset != null) {
            String(bytes, offset, bytes.size - offset, detectedCharset)
        } else {
            decodeUtf8StrictOrFallback(bytes, offset)
        }

        return text
            .removePrefix("\uFEFF")
            .replace("\u0000", "")
            .replace("\r\n", "\n")
            .replace('\r', '\n')
    }

    private fun decodeUtf8StrictOrFallback(bytes: ByteArray, offset: Int): String {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val strictUtf8 = runCatching {
            decoder.decode(ByteBuffer.wrap(bytes, offset, bytes.size - offset)).toString()
        }.getOrNull()
        if (strictUtf8 != null) return strictUtf8

        val fallbackCharset = WINDOWS_1258 ?: WINDOWS_1252 ?: Charsets.ISO_8859_1
        return String(bytes, offset, bytes.size - offset, fallbackCharset)
    }

    fun detectSubtitleFormat(urlOrName: String, content: String? = null): String {
        if (!content.isNullOrBlank()) {
            val cleaned = content.removePrefix("\uFEFF").trimStart()
            val firstLine = cleaned.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
            when {
                firstLine.startsWith("WEBVTT", ignoreCase = true) -> return MimeTypes.TEXT_VTT
                cleaned.contains("[Script Info]", ignoreCase = true) ||
                    (cleaned.contains("[Events]", ignoreCase = true) && cleaned.contains("Dialogue:", ignoreCase = true)) -> {
                    return MimeTypes.TEXT_SSA
                }
                cleaned.contains("-->") -> return MimeTypes.APPLICATION_SUBRIP
            }
        }
        val lower = urlOrName.lowercase(Locale.US)
        val pathOnly = lower.substringBefore('?').substringBefore('#')
        return when {
            pathOnly.endsWith(".vtt") || lower.contains("format=vtt") || lower.contains("ext=vtt") || lower.contains("type=vtt") -> {
                MimeTypes.TEXT_VTT
            }
            pathOnly.endsWith(".ass") || pathOnly.endsWith(".ssa") ||
                lower.contains("format=ass") || lower.contains("format=ssa") -> {
                MimeTypes.TEXT_SSA
            }
            else -> MimeTypes.APPLICATION_SUBRIP
        }
    }

    fun formatTrackBadgeLabel(mimeType: String?): String? {
        return when (mimeType?.lowercase(Locale.US)?.trim()) {
            MimeTypes.APPLICATION_SUBRIP, "application/x-subrip", "text/srt", "application/srt" -> "SRT"
            MimeTypes.TEXT_VTT -> "VTT"
            MimeTypes.TEXT_SSA -> "ASS"
            else -> mimeType?.substringAfterLast('/')?.uppercase(Locale.US)
        }
    }

    fun isHtmlErrorPayload(decodedText: String): Boolean {
        val sample = decodedText.trimStart().take(600).lowercase(Locale.US)
        if (sample.isEmpty()) return true
        val looksHtml = sample.startsWith("<!doctype html") ||
            sample.startsWith("<html") ||
            sample.contains("<head>") ||
            sample.contains("just a moment...") ||
            sample.contains("cf-browser-verification")
        if (!looksHtml) return false
        return !decodedText.contains("-->") && !decodedText.contains("Dialogue:", ignoreCase = true)
    }

    fun parseFlexibleTimestampMs(rawStamp: String): Long? {
        var clean = rawStamp.trim().substringBefore(' ').trim()
        if (clean.isEmpty()) return null
        // Support hh:mm:ss:mmm (4 colon-separated segments) used by some legacy SRT tools
        if (clean.count { it == ':' } == 3 && !clean.contains('.') && !clean.contains(',')) {
            val lastColon = clean.lastIndexOf(':')
            clean = clean.substring(0, lastColon) + "." + clean.substring(lastColon + 1)
        }
        clean = clean.replace(',', '.')
        val hmsParts = clean.split(':')
        val (h, m, secPart) = when (hmsParts.size) {
            3 -> Triple(
                hmsParts[0].trim().toLongOrNull() ?: return null,
                hmsParts[1].trim().toLongOrNull() ?: return null,
                hmsParts[2].trim()
            )
            2 -> Triple(
                0L,
                hmsParts[0].trim().toLongOrNull() ?: return null,
                hmsParts[1].trim()
            )
            else -> return null
        }
        val dotSplit = secPart.split('.', limit = 2)
        val s = dotSplit[0].trim().toLongOrNull() ?: return null
        val msStr = dotSplit.getOrElse(1) { "0" }
            .takeWhile { it.isDigit() }
            .padEnd(3, '0')
            .take(3)
        val ms = msStr.toLongOrNull() ?: 0L
        return ((h * 3600L) + (m * 60L) + s) * 1000L + ms
    }

    fun formatSrtTimestamp(ms: Long): String {
        val safe = ms.coerceAtLeast(0L)
        val hours = safe / 3_600_000L
        val mins = (safe % 3_600_000L) / 60_000L
        val secs = (safe % 60_000L) / 1_000L
        val millis = safe % 1_000L
        return String.format(Locale.US, "%02d:%02d:%02d,%03d", hours, mins, secs, millis)
    }

    fun formatVttTimestamp(ms: Long): String = formatSrtTimestamp(ms).replace(',', '.')

    fun parseNormalizedCues(rawText: String): List<NormalizedCue> {
        val clean = rawText
            .removePrefix("\uFEFF")
            .replace("\u0000", "")
            .replace("\r\n", "\n")
            .replace('\r', '\n')
        if (clean.isBlank()) return emptyList()

        val firstNonBlank = clean.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        if ((clean.contains("[Script Info]", ignoreCase = true) || clean.contains("[Events]", ignoreCase = true)) &&
            clean.contains("Dialogue:", ignoreCase = true)
        ) {
            return parseAssDialogueCues(clean)
        }

        val isVtt = firstNonBlank.startsWith("WEBVTT", ignoreCase = true)
        val lines = clean.lines()
        val cues = ArrayList<NormalizedCue>()
        var i = 0
        while (i < lines.size) {
            val trimmed = lines[i].trim()
            if (trimmed.isEmpty()) {
                i++
                continue
            }
            if (isVtt && (trimmed.startsWith("WEBVTT", ignoreCase = true) ||
                    trimmed.startsWith("NOTE", ignoreCase = true) ||
                    trimmed.startsWith("STYLE", ignoreCase = true) ||
                    trimmed.startsWith("REGION", ignoreCase = true))
            ) {
                while (i < lines.size && lines[i].isNotBlank()) i++
                continue
            }

            // Skip numeric cue index or VTT cue identifier if next line is timing line
            val match: MatchResult?
            if (DIGITS_ONLY_REGEX.matches(trimmed)) {
                val nextLine = lines.getOrNull(i + 1)?.trim().orEmpty()
                val nextMatch = FLEXIBLE_TIMELINE_REGEX.find(nextLine)
                if (nextMatch != null) {
                    i += 1
                    match = nextMatch
                } else {
                    i++
                    continue
                }
            } else {
                val direct = FLEXIBLE_TIMELINE_REGEX.find(trimmed)
                if (direct != null) {
                    match = direct
                } else {
                    val nextLine = lines.getOrNull(i + 1)?.trim().orEmpty()
                    val nextMatch = FLEXIBLE_TIMELINE_REGEX.find(nextLine)
                    if (nextMatch != null) {
                        i += 1
                        match = nextMatch
                    } else {
                        i++
                        continue
                    }
                }
            }

            val startMs = parseFlexibleTimestampMs(match.groupValues[1])
            val rawEndMs = parseFlexibleTimestampMs(match.groupValues[2])
            i++

            val bodyLines = ArrayList<String>()
            while (i < lines.size) {
                val rawBodyLine = lines[i]
                if (rawBodyLine.isBlank()) break
                // Handle malformed SRT missing blank line between cues
                val peekTrimmed = rawBodyLine.trim()
                if (FLEXIBLE_TIMELINE_REGEX.matches(peekTrimmed)) break
                if (DIGITS_ONLY_REGEX.matches(peekTrimmed) &&
                    lines.getOrNull(i + 1)?.trim()?.let { FLEXIBLE_TIMELINE_REGEX.matches(it) } == true
                ) {
                    break
                }
                val cleanedLine = if (isVtt) {
                    VTT_INLINE_TIMESTAMP_REGEX.replace(
                        VTT_VOICE_OR_CLASS_TAG_REGEX.replace(rawBodyLine, ""),
                        ""
                    ).trimEnd()
                } else {
                    rawBodyLine.trimEnd()
                }
                if (cleanedLine.isNotBlank()) {
                    bodyLines.add(cleanedLine.trim())
                }
                i++
            }

            if (startMs != null && rawEndMs != null && bodyLines.isNotEmpty()) {
                val endMs = rawEndMs.coerceAtLeast(startMs + 500L)
                cues.add(NormalizedCue(startMs, endMs, bodyLines.joinToString("\n")))
            }
        }
        return cues
    }

    private fun parseAssDialogueCues(clean: String): List<NormalizedCue> {
        val rawCues = SubtitleTranslator.parseTimedCues(clean)
        if (rawCues.isNotEmpty()) {
            return rawCues.map {
                NormalizedCue(it.startMs, it.endMs.coerceAtLeast(it.startMs + 500L), it.text)
            }
        }
        val out = ArrayList<NormalizedCue>()
        for (rawLine in clean.lines()) {
            val line = rawLine.trim()
            if (!line.startsWith("Dialogue:", ignoreCase = true)) continue
            val parts = line.substringAfter(':').trimStart().split(',', limit = 10)
            if (parts.size < 10) continue
            val startMs = parseFlexibleTimestampMs(parts[1]) ?: continue
            val endMs = parseFlexibleTimestampMs(parts[2]) ?: continue
            val text = parts[9]
                .replace("\\N", "\n")
                .replace("\\n", "\n")
                .replace("\\h", " ")
                .replace(ASS_NON_POS_TAG_REGEX, "")
                .trim()
            if (text.isNotEmpty()) {
                out.add(NormalizedCue(startMs, endMs.coerceAtLeast(startMs + 500L), text))
            }
        }
        return out
    }

    /**
     * Converts decoded SRT/VTT/ASS text into strict canonical SubRip format that Media3 SubripParser
     * parses cleanly (trimmed cue index lines, HH:MM:SS,mmm --> HH:MM:SS,mmm, blank line boundaries).
     */
    fun normalizeToSubRip(rawText: String): String {
        val cues = parseNormalizedCues(rawText)
        if (cues.isEmpty()) {
            return rawText
                .removePrefix("\uFEFF")
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .trim() + "\n"
        }
        return buildString(cues.size * 72) {
            cues.forEachIndexed { idx, cue ->
                append(idx + 1)
                append('\n')
                append(formatSrtTimestamp(cue.startMs))
                append(" --> ")
                append(formatSrtTimestamp(cue.endMs))
                append('\n')
                append(cue.text)
                append("\n\n")
            }
        }
    }

    fun normalizeToWebVtt(rawText: String): String {
        val cues = parseNormalizedCues(rawText)
        if (cues.isEmpty()) {
            val clean = rawText.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n').trim()
            return if (clean.startsWith("WEBVTT", ignoreCase = true)) "$clean\n" else "WEBVTT\n\n$clean\n"
        }
        return buildString(cues.size * 72 + 16) {
            append("WEBVTT\n\n")
            for (cue in cues) {
                append(formatVttTimestamp(cue.startMs))
                append(" --> ")
                append(formatVttTimestamp(cue.endMs))
                append('\n')
                append(cue.text)
                append("\n\n")
            }
        }
    }

    /**
     * Produces normalized UTF-8 payload matching the declared ExoPlayer MimeType.
     */
    @Throws(IOException::class)
    fun normalizeSubtitleForMime(rawBytes: ByteArray, expectedMime: String, url: String = ""): ByteArray {
        val decoded = decodeSubtitleBytes(rawBytes)
        if (decoded.isBlank() || isHtmlErrorPayload(decoded)) {
            throw IOException("Empty or HTML subtitle response for $url")
        }
        val actualMime = detectSubtitleFormat(url, decoded)
        val outText = when (expectedMime) {
            MimeTypes.TEXT_SSA -> {
                if (actualMime == MimeTypes.TEXT_SSA) decoded else normalizeToSubRip(decoded)
            }
            MimeTypes.TEXT_VTT -> {
                if (actualMime == MimeTypes.TEXT_VTT &&
                    decoded.trimStart().startsWith("WEBVTT", ignoreCase = true)
                ) {
                    decoded
                } else {
                    normalizeToWebVtt(decoded)
                }
            }
            else -> normalizeToSubRip(decoded)
        }
        return outText.toByteArray(Charsets.UTF_8)
    }

    fun readTextUriOrHttp(
        context: Context,
        url: String,
        headers: Map<String, String>,
        baseClient: OkHttpClient
    ): String? {
        val bytes = fetchRawBytes(context, url, headers, baseClient) ?: return null
        val decoded = decodeSubtitleBytes(bytes)
        if (decoded.isBlank() || isHtmlErrorPayload(decoded)) return null
        return decoded
    }

    fun queryDisplayName(context: Context, uri: Uri): String {
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) {
                        val name = cursor.getString(idx)
                        if (!name.isNullOrBlank()) return name
                    }
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "subtitle.srt"
    }

    fun fetchRawBytes(
        context: Context,
        url: String,
        headers: Map<String, String>,
        baseClient: OkHttpClient
    ): ByteArray? {
        return when {
            url.startsWith("content://", ignoreCase = true) -> {
                runCatching {
                    context.contentResolver.openInputStream(Uri.parse(url))?.use { it.readBytes() }
                }.getOrNull()
            }
            url.startsWith("file://", ignoreCase = true) -> {
                val path = Uri.parse(url).path ?: return null
                runCatching { File(path).readBytes() }.getOrNull()
            }
            url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true) -> {
                fetchHttpBytesWithRetry(url, headers, baseClient)
            }
            else -> runCatching { File(url).takeIf { it.exists() }?.readBytes() }.getOrNull()
        }
    }

    private fun fetchHttpBytesWithRetry(
        url: String,
        headers: Map<String, String>,
        baseClient: OkHttpClient
    ): ByteArray? {
        val cookieClient = baseClient.newBuilder()
            .cookieJar(InMemoryCookieJar())
            .build()
        val isOpenSub = url.contains("opensubtitles.org", ignoreCase = true)
        for (attempt in 0 until 2) {
            val req = Request.Builder().url(url).apply {
                for ((k, v) in headers) header(k, v)
                if (headers.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
                    header(
                        "User-Agent",
                        if (isOpenSub) AetherSourceResolver.OPENSUBTITLES_UA else AetherSourceResolver.USER_AGENT
                    )
                }
                if (isOpenSub && headers.keys.none { it.equals("X-User-Agent", ignoreCase = true) }) {
                    header("X-User-Agent", AetherSourceResolver.OPENSUBTITLES_UA)
                }
                if (headers.keys.none { it.equals("Accept", ignoreCase = true) }) {
                    header("Accept", "text/vtt, application/x-subrip, text/plain, */*;q=0.8")
                }
            }.build()
            val bytes = runCatching {
                cookieClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        throw IOException("HTTP ${resp.code}")
                    }
                    resp.body?.bytes()
                }
            }.getOrNull()
            if (bytes != null && bytes.isNotEmpty()) {
                return bytes
            }
            if (attempt == 0) {
                runCatching { Thread.sleep(250L) }
            }
        }
        return null
    }

    @OptIn(UnstableApi::class)
    fun createExoSubtitleDataSourceFactory(
        context: Context,
        baseClient: OkHttpClient,
        mimeByUrl: Map<String, String>,
        headersByUrl: Map<String, Map<String, String>> = emptyMap()
    ): DataSource.Factory {
        val appContext = context.applicationContext
        val memCache = ConcurrentHashMap<String, ByteArray>()
        return DataSource.Factory {
            object : BaseDataSource(/* isNetwork = */ true) {
                private var activeUri: Uri? = null
                private var data: ByteArray = ByteArray(0)
                private var readPos: Int = 0
                private var bytesRemaining: Int = 0
                private var opened = false

                override fun open(dataSpec: DataSpec): Long {
                    activeUri = dataSpec.uri
                    val urlStr = dataSpec.uri.toString()
                    val expectedMime = mimeByUrl[urlStr] ?: detectSubtitleFormat(urlStr)
                    val reqHeaders = headersByUrl[urlStr].orEmpty()
                    val cacheKey = "$urlStr|$expectedMime"

                    transferInitializing(dataSpec)
                    val normalized = memCache[cacheKey] ?: run {
                        val raw = fetchRawBytes(appContext, urlStr, reqHeaders, baseClient)
                            ?: throw IOException("Failed to fetch subtitle: $urlStr")
                        val converted = normalizeSubtitleForMime(raw, expectedMime, urlStr)
                        if (memCache.size < 24) {
                            memCache[cacheKey] = converted
                        }
                        converted
                    }

                    data = normalized
                    if (dataSpec.position > data.size.toLong()) {
                        throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
                    }
                    readPos = dataSpec.position.toInt()
                    val available = data.size - readPos
                    bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
                        minOf(available.toLong(), dataSpec.length).toInt()
                    } else {
                        available
                    }
                    opened = true
                    transferStarted(dataSpec)
                    return bytesRemaining.toLong()
                }

                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    if (length == 0) return 0
                    if (bytesRemaining == 0) return C.RESULT_END_OF_INPUT
                    val count = minOf(length, bytesRemaining)
                    System.arraycopy(data, readPos, buffer, offset, count)
                    readPos += count
                    bytesRemaining -= count
                    bytesTransferred(count)
                    return count
                }

                override fun getUri(): Uri? = activeUri

                override fun close() {
                    activeUri = null
                    data = ByteArray(0)
                    if (opened) {
                        opened = false
                        transferEnded()
                    }
                }
            }
        }
    }
}
