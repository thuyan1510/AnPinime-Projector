package com.anpinime.projector

import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
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

    private const val SEP_TOKEN = "@@99@@"
    private const val LINEBREAK_PLACEHOLDER = "⏎"
    private val SEP_SPLIT_REGEX = Regex("""\s*@@\s*99\s*@@\s*""")
    private val LINEBREAK_RESTORE_REGEX = Regex("""\s*⏎\s*""")
    private val TS_LINE_REGEX = Regex(
        """^((?:\d{1,2}:)?\d{2}:\d{2}[.,]\d{3})\s*-->\s*((?:\d{1,2}:)?\d{2}:\d{2}[.,]\d{3})(.*)$"""
    )

    fun parseTimedCues(rawText: String): List<TimedSubtitleCue> {
        val clean = rawText.removePrefix("\uFEFF").replace("\r\n", "\n").replace("\r", "\n")
        val blocks = clean.split(Regex("""\n{2,}"""))
        val cues = ArrayList<TimedSubtitleCue>()

        for (block in blocks) {
            val lines = block.trim().lines()
            if (lines.isEmpty()) continue
            val tsIdx = lines.indexOfFirst { TS_LINE_REGEX.containsMatchIn(it.trim()) }
            if (tsIdx < 0 || tsIdx + 1 >= lines.size) continue
            val match = TS_LINE_REGEX.find(lines[tsIdx].trim()) ?: continue

            val startRaw = match.groupValues[1].replace(',', '.')
            val endRaw = match.groupValues[2].replace(',', '.')
            val endExtra = match.groupValues[3]
            val normStart = if (startRaw.count { it == ':' } == 1) "00:$startRaw" else startRaw
            val normEndTime = if (endRaw.count { it == ':' } == 1) "00:$endRaw" else endRaw
            val startMs = parseTimestampMs(normStart) ?: continue
            val endMs = parseTimestampMs(normEndTime) ?: continue

            val text = lines.drop(tsIdx + 1).joinToString("\n")
                .replace(Regex("""<[^>]+>"""), "")
                .trim()
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
        positionMs: Long
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
        val minStart = (positionMs - 60_000L).coerceAtLeast(0L)
        while (idx >= 0 && cues[idx].startMs >= minStart) {
            val cue = cues[idx]
            if (positionMs <= cue.endMs) {
                val txt = translatedMap[idx]?.takeIf { it.isNotBlank() }
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
