package com.anpinime.projector

import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future

data class TimedSubtitleCue(
    val startMs: Long,
    val endMs: Long,
    val startStamp: String,
    val endStamp: String,
    val text: String
)

object SubtitleTranslator {
    const val DEFAULT_GTX_ENDPOINT =
        "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=vi&dt=t"
    const val DEFAULT_TRANSLATE_HTML_ENDPOINT =
        "https://translate-pa.googleapis.com/v1/translateHtml"
    const val DEFAULT_TRANSLATE_API_KEY =
        "" + "AIzaSyATBXajvzQLTDHEQbcpq0Ihe0vWDHmO520"

    private const val SEP_TOKEN = "@@99@@"
    private const val LINEBREAK_PLACEHOLDER = "⏎"
    private val JSON_PROTOBUF_MEDIA_TYPE = "application/json+protobuf; charset=utf-8".toMediaType()
    private val SEP_SPLIT_REGEX = Regex("""\s*@@\s*99\s*@@\s*""")
    private val LINEBREAK_RESTORE_REGEX = Regex("""\s*⏎\s*""")
    private val TS_LINE_REGEX = Regex(
        """^((?:\d{1,2}:)?\d{2}:\d{2}[.,]\d{3})\s*-->\s*((?:\d{1,2}:)?\d{2}:\d{2}[.,]\d{3})(.*)$"""
    )
    private val BR_TAG_REGEX = Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE)
    private val ASS_OVERRIDE_REGEX = Regex("""\{\\?[^}]*\}""")
    private val HTML_TAG_REGEX = Regex("""<[^>]+>""")
    private val HTML_PAGE_GUARD_REGEX = Regex(
        """(?is)^\s*(?:<!doctype\s+html|<html\b|<head\b|<title>.*?(?:404|error|just a moment|attention required|xác minh))"""
    )
    private val CUE_INDEX_LINE_REGEX = Regex("""^\d{1,6}$""")

    fun parseTimedCues(rawText: String): List<TimedSubtitleCue> {
        val clean = rawText.removePrefix("\uFEFF").replace("\r\n", "\n").replace("\r", "\n")
        if (clean.isBlank()) return emptyList()
        if (HTML_PAGE_GUARD_REGEX.containsMatchIn(clean) && !TS_LINE_REGEX.containsMatchIn(clean) && !clean.contains("Dialogue:", ignoreCase = true)) {
            return emptyList()
        }
        if (clean.contains("Dialogue:", ignoreCase = true) && !TS_LINE_REGEX.containsMatchIn(clean)) {
            val assCues = parseAssCues(clean)
            if (assCues.isNotEmpty()) return assCues
        }

        val cues = ArrayList<TimedSubtitleCue>()
        val rawLines = clean.lines()
        var idx = 0
        while (idx < rawLines.size) {
            val trimmed = rawLines[idx].trim()
            if (trimmed.equals("STYLE", ignoreCase = true) ||
                trimmed.equals("REGION", ignoreCase = true) ||
                (trimmed.startsWith("NOTE", ignoreCase = true) && !TS_LINE_REGEX.containsMatchIn(trimmed))
            ) {
                idx++
                while (idx < rawLines.size && rawLines[idx].isNotBlank()) idx++
                continue
            }
            val match = TS_LINE_REGEX.find(trimmed)
            if (match == null) {
                idx++
                continue
            }
            val startRaw = match.groupValues[1].replace(',', '.')
            val endRaw = match.groupValues[2].replace(',', '.')
            val endExtra = match.groupValues[3]
            val normStart = if (startRaw.count { it == ':' } == 1) "00:$startRaw" else startRaw
            val normEndTime = if (endRaw.count { it == ':' } == 1) "00:$endRaw" else endRaw
            val startMs = parseTimestampMs(normStart)
            val endMs = parseTimestampMs(normEndTime)
            idx++
            val textLines = ArrayList<String>(3)
            while (idx < rawLines.size) {
                val cur = rawLines[idx]
                val curTrim = cur.trim()
                if (curTrim.isEmpty()) {
                    idx++
                    break
                }
                if (TS_LINE_REGEX.containsMatchIn(curTrim)) {
                    // Remove trailing numeric index belonging to the next cue
                    if (textLines.isNotEmpty() && CUE_INDEX_LINE_REGEX.matches(textLines.last().trim())) {
                        textLines.removeAt(textLines.lastIndex)
                    }
                    break
                }
                textLines.add(cur)
                idx++
            }
            if (startMs == null || endMs == null) continue
            val text = cleanSubtitleText(textLines.joinToString("\n"))
            if (text.isNotEmpty()) {
                cues.add(
                    TimedSubtitleCue(
                        startMs = startMs,
                        endMs = endMs.coerceAtLeast(startMs + 200L),
                        startStamp = normStart,
                        endStamp = "$normEndTime$endExtra",
                        text = text
                    )
                )
            }
        }
        cues.sortBy { it.startMs }
        return cues
    }

    private fun parseAssCues(clean: String): List<TimedSubtitleCue> {
        val cues = ArrayList<TimedSubtitleCue>()
        var inEvents = !clean.contains("[Events]", ignoreCase = true)
        var startCol = 1
        var endCol = 2
        var textCol = 9
        var minCols = 10

        for (rawLine in clean.lines()) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith(";")) continue
            if (line.startsWith("[") && line.endsWith("]")) {
                inEvents = line.equals("[Events]", ignoreCase = true)
                continue
            }
            if (!inEvents) continue
            if (line.startsWith("Format:", ignoreCase = true)) {
                val cols = line.substringAfter(':').split(',').map { it.trim().lowercase() }
                cols.indexOf("start").takeIf { it >= 0 }?.let { startCol = it }
                cols.indexOf("end").takeIf { it >= 0 }?.let { endCol = it }
                cols.indexOf("text").takeIf { it >= 0 }?.let { textCol = it; minCols = it + 1 }
                continue
            }
            if (!line.startsWith("Dialogue:", ignoreCase = true)) continue
            val payload = line.substringAfter(':').trimStart()
            val fields = payload.split(',', limit = minCols)
            if (fields.size <= maxOf(startCol, endCol, textCol)) continue
            val startMs = parseTimestampMs(fields[startCol]) ?: continue
            val endMs = parseTimestampMs(fields[endCol]) ?: continue
            val rawDialogue = fields[textCol]
                .replace("\\N", "\n")
                .replace("\\n", "\n")
                .replace("\\h", " ")
                .replace(ASS_OVERRIDE_REGEX, "")
            val text = cleanSubtitleText(rawDialogue)
            if (text.isNotEmpty()) {
                cues.add(
                    TimedSubtitleCue(
                        startMs = startMs,
                        endMs = endMs.coerceAtLeast(startMs + 200L),
                        startStamp = formatVttTimestamp(startMs),
                        endStamp = formatVttTimestamp(endMs.coerceAtLeast(startMs + 200L)),
                        text = text
                    )
                )
            }
        }
        cues.sortBy { it.startMs }
        return cues
    }

    private fun formatVttTimestamp(ms: Long): String {
        val safe = ms.coerceAtLeast(0L)
        val hours = safe / 3_600_000L
        val mins = (safe % 3_600_000L) / 60_000L
        val secs = (safe % 60_000L) / 1_000L
        val millis = safe % 1_000L
        return String.format(java.util.Locale.US, "%02d:%02d:%02d.%03d", hours, mins, secs, millis)
    }

    private fun cleanSubtitleText(raw: String): String {
        val withNewlines = BR_TAG_REGEX.replace(raw, "\n")
        val noTags = HTML_TAG_REGEX.replace(withNewlines, "")
        val decoded = decodeHtmlEntities(noTags)
        return decoded.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
            .trim()
    }

    internal fun decodeHtmlEntities(input: String): String {
        if (!input.contains('&')) return input
        var s = input
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
        s = Regex("""&#(\d+);""").replace(s) { m ->
            val code = m.groupValues[1].toIntOrNull()
            if (code != null && code in 1..0x10FFFF) String(Character.toChars(code)) else m.value
        }
        s = Regex("""&#x([0-9a-fA-F]+);""").replace(s) { m ->
            val code = m.groupValues[1].toIntOrNull(16)
            if (code != null && code in 1..0x10FFFF) String(Character.toChars(code)) else m.value
        }
        return s
    }

    fun parseTimestampMs(stamp: String): Long? {
        val clean = stamp.trim().substringBefore(' ').replace(',', '.')
        val hmsParts = clean.split(':')
        val (h, m, secStr) = when (hmsParts.size) {
            3 -> Triple(hmsParts[0].toLongOrNull() ?: return null, hmsParts[1].toLongOrNull() ?: return null, hmsParts[2])
            2 -> Triple(0L, hmsParts[0].toLongOrNull() ?: return null, hmsParts[1])
            else -> return null
        }
        val secParts = secStr.split('.', limit = 2)
        val s = secParts[0].toLongOrNull() ?: return null
        val msRaw = secParts.getOrElse(1) { "0" }.padEnd(3, '0').take(3)
        val ms = msRaw.toLongOrNull() ?: 0L
        return ((h * 3600L) + (m * 60L) + s) * 1000L + ms
    }

    fun findCueIndexNear(cues: List<TimedSubtitleCue>, positionMs: Long): Int {
        if (cues.isEmpty()) return 0
        var lo = 0
        var hi = cues.lastIndex
        var firstAfter = cues.size
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (cues[mid].startMs > positionMs) {
                firstAfter = mid
                hi = mid - 1
            } else {
                lo = mid + 1
            }
        }
        val candidate = (firstAfter - 1).coerceAtLeast(0)
        // Walk back briefly if a long cue overlapped past a shorter preceding one
        var idx = candidate
        while (idx >= 0 && cues[idx].startMs >= positionMs - 60_000L) {
            if (positionMs in cues[idx].startMs..cues[idx].endMs) return idx
            idx--
        }
        return firstAfter.coerceAtMost(cues.lastIndex)
    }

    fun findActiveCueText(
        cues: List<TimedSubtitleCue>,
        translatedMap: Map<Int, String>,
        positionMs: Long,
        fallbackToOriginal: Boolean = false
    ): String? {
        if (cues.isEmpty()) return null
        var lo = 0
        var hi = cues.lastIndex
        var firstAfter = cues.size
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (cues[mid].startMs > positionMs) {
                firstAfter = mid
                hi = mid - 1
            } else {
                lo = mid + 1
            }
        }
        val activeLines = ArrayList<String>(2)
        var idx = firstAfter - 1
        val minStart = (positionMs - 120_000L).coerceAtLeast(0L)
        while (idx >= 0 && cues[idx].startMs >= minStart) {
            val cue = cues[idx]
            if (positionMs <= cue.endMs) {
                val txt = translatedMap[idx]?.takeIf { it.isNotBlank() }
                    ?: if (fallbackToOriginal) cue.text.trim().takeIf { it.isNotBlank() } else null
                if (txt != null) activeLines.add(0, txt)
            }
            idx--
        }
        return if (activeLines.isEmpty()) null else activeLines.joinToString("\n")
    }

    fun planBatchRanges(
        totalCues: Int,
        centerIndex: Int,
        wave1Before: Int = 3,
        wave1After: Int = 32,
        batchSize: Int = 35
    ): List<IntRange> {
        if (totalCues <= 0) return emptyList()
        val safeCenter = centerIndex.coerceIn(0, totalCues - 1)
        val wave1Start = (safeCenter - wave1Before).coerceAtLeast(0)
        val wave1End = (safeCenter + wave1After).coerceAtMost(totalCues - 1)

        val ranges = ArrayList<IntRange>()
        ranges.add(wave1Start..wave1End)

        var pos = wave1End + 1
        while (pos < totalCues) {
            val end = (pos + batchSize - 1).coerceAtMost(totalCues - 1)
            ranges.add(pos..end)
            pos = end + 1
        }

        var prevEnd = wave1Start - 1
        while (prevEnd >= 0) {
            val start = (prevEnd - batchSize + 1).coerceAtLeast(0)
            ranges.add(start..prevEnd)
            prevEnd = start - 1
        }
        return ranges
    }

    fun translateBatchTexts(texts: List<String>, translateFn: (String) -> String?): List<String> {
        if (texts.isEmpty()) return emptyList()
        if (texts.size == 1) {
            val single = translateFn(texts[0])?.trim()?.ifEmpty { texts[0] } ?: texts[0]
            return listOf(single)
        }
        val prepared = texts.map { raw ->
            raw.replace("@@99@@", "@99@")
                .replace("\n", " $LINEBREAK_PLACEHOLDER ")
                .trim()
        }
        val joined = prepared.joinToString("\n$SEP_TOKEN\n")
        val translated = translateFn(joined)
        val parts = translated?.split(SEP_SPLIT_REGEX)
        if (parts != null && parts.size == texts.size) {
            return texts.indices.map { idx ->
                parts[idx]
                    .replace(LINEBREAK_RESTORE_REGEX, "\n")
                    .trim()
                    .ifEmpty { texts[idx] }
            }
        }
        val mid = texts.size / 2
        val left = translateBatchTexts(texts.subList(0, mid), translateFn)
        val right = translateBatchTexts(texts.subList(mid, texts.size), translateFn)
        return left + right
    }

    fun translateAllProgressive(
        cues: List<TimedSubtitleCue>,
        currentPositionMs: Long,
        workers: Int = 6,
        translateFn: (String) -> String?,
        onWaveReady: (waveCues: Map<Int, String>) -> Unit = {},
        onBatchUpdated: (doneCues: Int, totalCues: Int) -> Unit = { _, _ -> }
    ): Map<Int, String> {
        val translated = ConcurrentHashMap<Int, String>(cues.size)
        if (cues.isEmpty()) {
            onWaveReady(translated)
            return translated
        }
        val centerIdx = findCueIndexNear(cues, currentPositionMs)
        val ranges = planBatchRanges(cues.size, centerIdx)
        val wave1Range = ranges.first()
        val wave1Texts = (wave1Range.first..wave1Range.last).map { cues[it].text }
        val wave1Out = translateBatchTexts(wave1Texts, translateFn)
        for ((offset, cueIdx) in wave1Range.withIndex()) {
            translated[cueIdx] = wave1Out[offset]
        }
        onWaveReady(translated)
        onBatchUpdated(translated.size, cues.size)

        if (ranges.size > 1) {
            val pool = Executors.newFixedThreadPool(workers.coerceIn(1, 8))
            try {
                val futures = ArrayList<Future<*>>(ranges.size - 1)
                for (rIdx in 1 until ranges.size) {
                    val range = ranges[rIdx]
                    futures.add(pool.submit {
                        val batchTexts = (range.first..range.last).map { cues[it].text }
                        val batchOut = translateBatchTexts(batchTexts, translateFn)
                        for ((offset, cueIdx) in range.withIndex()) {
                            translated[cueIdx] = batchOut[offset]
                        }
                        onBatchUpdated(translated.size, cues.size)
                    })
                }
                for (f in futures) {
                    runCatching { f.get() }
                }
            } finally {
                pool.shutdownNow()
            }
        }
        return translated
    }

    fun translateAllProgressiveFast(
        cues: List<TimedSubtitleCue>,
        currentPositionMs: Long,
        workers: Int = 3,
        batchSize: Int = 80,
        seedTranslations: Map<Int, String> = emptyMap(),
        translateBatchFn: (List<String>) -> List<String?>,
        onWaveReady: (waveCues: Map<Int, String>) -> Unit = {},
        onBatchTranslated: (batchCues: Map<Int, String>) -> Unit = {},
        onBatchUpdated: (doneCues: Int, totalCues: Int) -> Unit = { _, _ -> }
    ): Map<Int, String> {
        val translated = ConcurrentHashMap<Int, String>(cues.size)
        if (cues.isEmpty()) {
            onWaveReady(translated)
            return translated
        }
        val phraseCache = ConcurrentHashMap<String, String>()
        for ((idx, txt) in seedTranslations) {
            if (idx in cues.indices && txt.isNotBlank()) {
                translated[idx] = txt
                val norm = cues[idx].text.trim()
                if (norm.isNotEmpty()) phraseCache.putIfAbsent(norm, txt)
            }
        }

        val safeBatch = batchSize.coerceIn(10, 120)
        val centerIdx = findCueIndexNear(cues, currentPositionMs)
        val waveBefore = (safeBatch / 6).coerceAtLeast(8)
        val waveAfter = (safeBatch - 1 - waveBefore).coerceAtLeast(10)
        val ranges = planBatchRanges(
            totalCues = cues.size,
            centerIndex = centerIdx,
            wave1Before = waveBefore,
            wave1After = waveAfter,
            batchSize = safeBatch
        )

        fun processRange(range: IntRange) {
            val batchDelta = HashMap<Int, String>((range.last - range.first + 1).coerceAtLeast(1))
            val missingByPhrase = LinkedHashMap<String, ArrayList<Int>>()
            for (cueIdx in range) {
                if (translated.containsKey(cueIdx)) continue
                val norm = cues[cueIdx].text.trim()
                if (norm.isEmpty()) continue
                val known = phraseCache[norm]
                if (!known.isNullOrBlank()) {
                    translated[cueIdx] = known
                    batchDelta[cueIdx] = known
                } else {
                    missingByPhrase.getOrPut(norm) { ArrayList(1) }.add(cueIdx)
                }
            }
            if (missingByPhrase.isNotEmpty()) {
                val uniqueTexts = missingByPhrase.keys.toList()
                val results = runCatching { translateBatchFn(uniqueTexts) }.getOrNull()
                if (results != null) {
                    for (i in uniqueTexts.indices) {
                        val out = results.getOrNull(i)?.trim()
                        if (!out.isNullOrEmpty()) {
                            val src = uniqueTexts[i]
                            phraseCache.putIfAbsent(src, out)
                            missingByPhrase[src]?.forEach { cueIdx ->
                                translated[cueIdx] = out
                                batchDelta[cueIdx] = out
                            }
                        }
                    }
                }
            }
            if (batchDelta.isNotEmpty()) {
                onBatchTranslated(batchDelta)
            }
        }

        processRange(ranges.first())
        onWaveReady(HashMap(translated))
        onBatchUpdated(translated.size, cues.size)

        if (ranges.size > 1) {
            val pool = Executors.newFixedThreadPool(workers.coerceIn(1, 4))
            try {
                val futures = ArrayList<Future<*>>(ranges.size - 1)
                for (rIdx in 1 until ranges.size) {
                    val range = ranges[rIdx]
                    futures.add(pool.submit {
                        processRange(range)
                        onBatchUpdated(translated.size, cues.size)
                    })
                }
                for (f in futures) {
                    runCatching { f.get() }
                }
            } finally {
                pool.shutdownNow()
            }
        }
        return translated
    }

    fun buildVtt(cues: List<TimedSubtitleCue>, translatedMap: Map<Int, String>): String {
        val sb = StringBuilder(cues.size * 64 + 16)
        sb.append("WEBVTT\n\n")
        for (idx in cues.indices) {
            val cue = cues[idx]
            val txt = translatedMap[idx]?.takeIf { it.isNotBlank() } ?: cue.text
            sb.append(cue.startStamp).append(" --> ").append(cue.endStamp).append('\n')
            sb.append(txt).append("\n\n")
        }
        return sb.toString()
    }

    internal fun parseTranslateHtmlResponse(rawJson: String, expectedSize: Int): List<String>? {
        return runCatching {
            val outer = JSONArray(rawJson)
            val arr = outer.optJSONArray(0) ?: return@runCatching null
            if (arr.length() != expectedSize) return@runCatching null
            val out = ArrayList<String>(expectedSize)
            for (i in 0 until expectedSize) {
                out.add(cleanSubtitleText(arr.optString(i, "")))
            }
            out
        }.getOrNull()
    }

    fun translateBatchViaHtml(
        texts: List<String>,
        client: OkHttpClient,
        targetLang: String = "vi",
        endpointUrl: String = DEFAULT_TRANSLATE_HTML_ENDPOINT,
        apiKey: String = DEFAULT_TRANSLATE_API_KEY,
        userAgent: String = AetherSourceResolver.USER_AGENT,
        fallbackTranslateFn: ((String) -> String?)? = { single ->
            translateViaGtx(single, client, DEFAULT_GTX_ENDPOINT, userAgent)
        },
        retryDelayMs: Long = 100L
    ): List<String?> {
        if (texts.isEmpty()) return emptyList()
        val inputArray = JSONArray()
        for (raw in texts) {
            inputArray.put(raw.replace("\r\n", "\n").replace("\r", "\n").replace("\n", "<br />"))
        }
        val payload = JSONArray()
            .put(JSONArray().put(inputArray).put("auto").put(targetLang))
            .put("te")
            .toString()

        var lastStatus = 0
        for (attempt in 0 until 3) {
            if (attempt > 0 && retryDelayMs > 0L) {
                runCatching { Thread.sleep(retryDelayMs * attempt) }
            }
            val parsed = runCatching {
                val reqBuilder = Request.Builder()
                    .url(endpointUrl)
                    .post(payload.toRequestBody(JSON_PROTOBUF_MEDIA_TYPE))
                    .header("User-Agent", userAgent)
                    .header("Accept", "*/*")
                if (apiKey.isNotBlank()) {
                    reqBuilder.header("X-goog-api-key", apiKey)
                }
                client.newCall(reqBuilder.build()).execute().use { resp ->
                    lastStatus = resp.code
                    if (!resp.isSuccessful) return@runCatching null
                    val body = resp.body?.string().orEmpty()
                    parseTranslateHtmlResponse(body, texts.size)
                }
            }.getOrNull()
            if (parsed != null) return parsed
            if (lastStatus in 400..404 || lastStatus == 413) break
        }

        if (texts.size > 1) {
            val mid = texts.size / 2
            val left = translateBatchViaHtml(
                texts = texts.subList(0, mid),
                client = client,
                targetLang = targetLang,
                endpointUrl = endpointUrl,
                apiKey = apiKey,
                userAgent = userAgent,
                fallbackTranslateFn = fallbackTranslateFn,
                retryDelayMs = retryDelayMs
            )
            val right = translateBatchViaHtml(
                texts = texts.subList(mid, texts.size),
                client = client,
                targetLang = targetLang,
                endpointUrl = endpointUrl,
                apiKey = apiKey,
                userAgent = userAgent,
                fallbackTranslateFn = fallbackTranslateFn,
                retryDelayMs = retryDelayMs
            )
            return left + right
        }

        val singleOut = fallbackTranslateFn?.invoke(texts[0])?.trim()?.takeIf { it.isNotEmpty() }
        return listOf(singleOut)
    }

    fun computeSubCacheKey(sourceKey: String, cueCount: Int, targetLang: String = "vi"): String {
        val raw = "$targetLang|$cueCount|$sourceKey"
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(32)
    }

    fun loadCachedTranslations(cacheFile: File, expectedCueCount: Int): Map<Int, String> {
        if (!cacheFile.isFile || expectedCueCount <= 0) return emptyMap()
        return runCatching {
            val obj = JSONObject(cacheFile.readText(Charsets.UTF_8))
            if (obj.optInt("total", -1) != expectedCueCount) return@runCatching emptyMap()
            val items = obj.optJSONObject("items") ?: return@runCatching emptyMap()
            val map = HashMap<Int, String>(items.length())
            val keys = items.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val idx = k.toIntOrNull() ?: continue
                val txt = items.optString(k, "").trim()
                if (idx in 0 until expectedCueCount && txt.isNotEmpty()) {
                    map[idx] = txt
                }
            }
            map
        }.getOrDefault(emptyMap())
    }

    fun saveCachedTranslations(cacheFile: File, totalCues: Int, translated: Map<Int, String>) {
        if (totalCues <= 0 || translated.isEmpty()) return
        runCatching {
            cacheFile.parentFile?.mkdirs()
            val items = JSONObject()
            for ((idx, txt) in translated) {
                if (idx in 0 until totalCues && txt.isNotBlank()) {
                    items.put(idx.toString(), txt)
                }
            }
            if (items.length() == 0) return
            val root = JSONObject()
                .put("total", totalCues)
                .put("items", items)
            val tmp = File(cacheFile.parentFile, "${cacheFile.name}.tmp")
            tmp.writeText(root.toString(), Charsets.UTF_8)
            if (!tmp.renameTo(cacheFile)) {
                cacheFile.writeText(root.toString(), Charsets.UTF_8)
                tmp.delete()
            }
        }
    }

    fun translateViaGtx(
        text: String,
        client: OkHttpClient,
        endpointUrl: String = DEFAULT_GTX_ENDPOINT,
        userAgent: String = AetherSourceResolver.USER_AGENT
    ): String? {
        return runCatching {
            val body = FormBody.Builder()
                .add("q", text)
                .build()
            val req = Request.Builder()
                .url(endpointUrl)
                .post(body)
                .header("User-Agent", userAgent)
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@runCatching null
                val raw = resp.body?.string().orEmpty()
                val outer = JSONArray(raw)
                val segs = outer.optJSONArray(0) ?: return@runCatching null
                val sb = StringBuilder()
                for (k in 0 until segs.length()) {
                    val seg = segs.optJSONArray(k) ?: continue
                    val piece = seg.optString(0)
                    if (piece.isNotEmpty() && piece != "null") sb.append(piece)
                }
                sb.toString()
            }
        }.getOrNull()
    }
}
