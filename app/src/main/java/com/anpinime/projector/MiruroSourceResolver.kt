package com.anpinime.projector

import androidx.media3.common.MimeTypes
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URL
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class MiruroStreamCandidate(
    val key: String,
    val providerId: String,
    val serverName: String,
    val trackType: String,
    val streamUrl: String,
    val format: String,
    val headers: Map<String, String>,
    val subtitles: List<ExternalSubtitleTrack>,
    val displayLabel: String
)

/**
 * Parses Miruro `sourcesData` (`providerOrder`, `tracks` [ssub/sub/dub], `providers`, `servers`, `subtitles`),
 * caches per-episode server candidates for instant failover, normalizes & prioritizes Vietnamese subtitles
 * (merging sibling provider subtitles when a video server has none), and probes HLS reachability.
 */
object MiruroSourceResolver {

    const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

    private const val CACHE_TTL_MS = 20 * 60 * 1000L
    private val TRACK_PRIORITY = mapOf("ssub" to 0, "sub" to 1, "dub" to 2)

    private val candidateCache = ConcurrentHashMap<String, Pair<Long, List<MiruroStreamCandidate>>>()
    private val probePool = Executors.newCachedThreadPool()

    fun cacheKey(mediaId: String, episodeNumber: Int): String = "${mediaId.trim()}:$episodeNumber"

    fun cacheSourcesData(mediaId: String, episodeNumber: Int, sourcesData: JSONObject): List<MiruroStreamCandidate> {
        val candidates = parseSourcesData(sourcesData)
        if (candidates.isNotEmpty() && mediaId.isNotBlank()) {
            candidateCache[cacheKey(mediaId, episodeNumber)] = System.currentTimeMillis() to candidates
        }
        return candidates
    }

    fun getCachedCandidates(mediaId: String, episodeNumber: Int): List<MiruroStreamCandidate> {
        val entry = candidateCache[cacheKey(mediaId, episodeNumber)] ?: return emptyList()
        if ((System.currentTimeMillis() - entry.first) > CACHE_TTL_MS) {
            candidateCache.remove(cacheKey(mediaId, episodeNumber))
            return emptyList()
        }
        return entry.second
    }

    fun clearCache() {
        candidateCache.clear()
    }

    fun hasVietnameseSubtitle(subtitles: List<ExternalSubtitleTrack>): Boolean {
        return subtitles.any { isVietnameseTrack(it) }
    }

    fun isVietnameseTrack(track: ExternalSubtitleTrack): Boolean {
        val lang = track.language.trim().lowercase(Locale.US)
        val label = track.label.trim().lowercase(Locale.US)
        return lang == "vi" ||
            lang == "vie" ||
            lang == "vn" ||
            lang.startsWith("vi-") ||
            lang.startsWith("vi_") ||
            label.contains("vietnamese") ||
            label.contains("tiếng việt") ||
            label.contains("vietsub") ||
            label.contains("việt")
    }

    fun isEnglishTrack(track: ExternalSubtitleTrack): Boolean {
        val lang = track.language.trim().lowercase(Locale.US)
        val label = track.label.trim().lowercase(Locale.US)
        return lang == "en" ||
            lang == "eng" ||
            lang.startsWith("en-") ||
            lang.startsWith("en_") ||
            label.contains("english") ||
            label.contains("tiếng anh")
    }

    fun normalizeLanguageCode(rawLang: String, rawLabel: String): String {
        val l = rawLang.trim().lowercase(Locale.US)
        val lbl = rawLabel.trim().lowercase(Locale.US)
        return when {
            l in setOf("vi", "vie", "vn", "vietnamese") || l.startsWith("vi-") || lbl.contains("vietnamese") || lbl.contains("tiếng việt") -> "vi"
            l in setOf("en", "eng", "english") || l.startsWith("en-") || lbl.contains("english") -> "en"
            l in setOf("ja", "jpn", "jp", "japanese") || lbl.contains("japanese") -> "ja"
            l in setOf("ko", "kor", "kr", "korean") || lbl.contains("korean") -> "ko"
            l in setOf("zh", "chi", "zho", "cn", "chinese") || lbl.contains("chinese") -> "zh"
            l in setOf("es", "spa", "spanish") || lbl.contains("spanish") -> "es"
            l in setOf("fr", "fre", "fra", "french") || lbl.contains("french") -> "fr"
            l in setOf("de", "ger", "deu", "german") || lbl.contains("german") -> "de"
            l in setOf("pt", "por", "portuguese") || lbl.contains("portuguese") -> "pt"
            l in setOf("id", "ind", "indonesian") || lbl.contains("indonesian") -> "id"
            l in setOf("th", "tha", "thai") || lbl.contains("thai") -> "th"
            l in setOf("ar", "ara", "arabic") || lbl.contains("arabic") -> "ar"
            l in setOf("ru", "rus", "russian") || lbl.contains("russian") -> "ru"
            l.isNotEmpty() -> l
            else -> "und"
        }
    }

    fun defaultHeadersForUrl(url: String, existingHeaders: Map<String, String> = emptyMap()): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for ((k, v) in existingHeaders) {
            if (k.isNotBlank() && v.isNotBlank()) out[k] = v
        }
        val host = try {
            URL(url).host.lowercase(Locale.US)
        } catch (_: Throwable) {
            url.lowercase(Locale.US)
        }
        val hasOrigin = out.keys.any { it.equals("Origin", ignoreCase = true) }
        val hasReferer = out.keys.any { it.equals("Referer", ignoreCase = true) }

        when {
            host.contains("pearlwinter.top") || host.contains("sunwaveclear.top") || host.contains("keeply.top") -> {
                if (!hasOrigin) out["Origin"] = "https://strm.cx"
                if (!hasReferer) out["Referer"] = "https://strm.cx/"
            }
            host.contains("krussdomi.com") || host.contains("advancedairesearchlab.xyz") -> {
                if (!hasOrigin) out["Origin"] = "https://krussdomi.com"
                if (!hasReferer) out["Referer"] = "https://krussdomi.com/"
            }
            host.contains("megaplay.buzz") || host.contains("nexabloom.top") ||
                host.contains("shiora.top") || host.contains("akirax.buzz") ||
                host.contains("laggedduringcutscene.site") || host.contains("imgnex.top") -> {
                if (!hasReferer) out["Referer"] = "https://megaplay.buzz/"
            }
        }
        if (out.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
            out["User-Agent"] = USER_AGENT
        }
        return out
    }

    fun sortSubtitlesVietnameseFirst(subtitles: List<ExternalSubtitleTrack>): List<ExternalSubtitleTrack> {
        if (subtitles.isEmpty()) return emptyList()
        val dedup = LinkedHashMap<String, ExternalSubtitleTrack>()
        for (sub in subtitles) {
            if (sub.url.isNotBlank() && !dedup.containsKey(sub.url)) {
                dedup[sub.url] = sub
            }
        }
        return dedup.values.sortedWith(
            compareBy<ExternalSubtitleTrack> {
                when {
                    isVietnameseTrack(it) -> 0
                    isEnglishTrack(it) -> 1
                    else -> 2
                }
            }.thenBy { it.label.lowercase(Locale.US) }
        )
    }

    /**
     * Parses a Miruro `sourcesData` JSON object into prioritized [MiruroStreamCandidate] entries.
     * Order: track priority (`ssub` -> `sub` -> `dub`), then `providerOrder`, then server order.
     */
    fun parseSourcesData(sourcesData: JSONObject): List<MiruroStreamCandidate> {
        val providerRank = HashMap<String, Int>()
        val orderArr = sourcesData.optJSONArray("providerOrder")
        if (orderArr != null) {
            for (i in 0 until orderArr.length()) {
                val p = orderArr.optString(i).trim().lowercase(Locale.US)
                if (p.isNotEmpty()) providerRank[p] = i
            }
        }

        val configObj = sourcesData.optJSONObject("config")
        val tracksArr = sourcesData.optJSONArray("tracks") ?: return emptyList()

        // First pass: collect global subtitle pool across all providers so servers without
        // their own subtitles (e.g. `bee`) still inherit Vietnamese/English soft-subs from `sun`/`hop`!
        val globalSubtitles = ArrayList<ExternalSubtitleTrack>()
        val rawEntries = ArrayList<Triple<Int, Int, MiruroStreamCandidate>>()
        val seenUrls = HashSet<String>()

        for (tIdx in 0 until tracksArr.length()) {
            val trackObj = tracksArr.optJSONObject(tIdx) ?: continue
            val trackType = trackObj.optString("track").trim().lowercase(Locale.US).ifBlank { "sub" }
            val trackRank = TRACK_PRIORITY[trackType] ?: 3
            val providersArr = trackObj.optJSONArray("providers") ?: continue

            for (pIdx in 0 until providersArr.length()) {
                val provObj = providersArr.optJSONObject(pIdx) ?: continue
                val providerId = provObj.optString("provider").trim().lowercase(Locale.US)
                if (providerId.isEmpty()) continue

                val provCfg = configObj?.optJSONObject(providerId)
                if (provCfg != null && provCfg.has("visible") && !provCfg.optBoolean("visible", true)) {
                    continue
                }
                val pRank = providerRank[providerId] ?: (100 + pIdx)

                val serversArr = provObj.optJSONArray("servers")
                // Extract default headers from the first server of this provider for its subtitles
                val firstServerHeaders = LinkedHashMap<String, String>()
                if (serversArr != null && serversArr.length() > 0) {
                    val hObj = serversArr.optJSONObject(0)?.optJSONObject("headers")
                    if (hObj != null) {
                        val keys = hObj.keys()
                        while (keys.hasNext()) {
                            val k = keys.next()
                            val v = hObj.optString(k).trim()
                            if (k.isNotBlank() && v.isNotBlank()) firstServerHeaders[k] = v
                        }
                    }
                }

                // Parse this provider's subtitles
                val provSubs = ArrayList<ExternalSubtitleTrack>()
                val subsArr = provObj.optJSONArray("subtitles")
                if (subsArr != null) {
                    for (sIdx in 0 until subsArr.length()) {
                        val subObj = subsArr.optJSONObject(sIdx) ?: continue
                        val fileUrl = subObj.optString("file").trim()
                            .ifBlank { subObj.optString("url").trim() }
                        if (!fileUrl.startsWith("http", ignoreCase = true)) continue
                        val rawLang = subObj.optString("language").trim()
                        val rawLabel = subObj.optString("label").trim()
                        val normLang = normalizeLanguageCode(rawLang, rawLabel)
                        val displayLabel = when {
                            normLang == "vi" -> "🇻🇳 ${rawLabel.ifBlank { "Tiếng Việt" }}"
                            rawLabel.isNotBlank() -> rawLabel
                            else -> normLang.uppercase(Locale.US)
                        }
                        val subHeaders = defaultHeadersForUrl(fileUrl, firstServerHeaders)
                        // Always request normalized WebVTT from SubtitleSupport so ASS-in-.jpeg and VTT both render cleanly
                        val track = ExternalSubtitleTrack(
                            url = fileUrl,
                            language = normLang,
                            label = displayLabel,
                            mimeType = MimeTypes.TEXT_VTT,
                            headers = subHeaders
                        )
                        provSubs.add(track)
                        globalSubtitles.add(track)
                    }
                }

                if (serversArr == null) continue
                for (svIdx in 0 until serversArr.length()) {
                    val svObj = serversArr.optJSONObject(svIdx) ?: continue
                    val svName = svObj.optString("server").trim().ifBlank { "server-${svIdx + 1}" }
                    val svHeaders = LinkedHashMap<String, String>()
                    val hObj = svObj.optJSONObject("headers")
                    if (hObj != null) {
                        val keys = hObj.keys()
                        while (keys.hasNext()) {
                            val k = keys.next()
                            val v = hObj.optString(k).trim()
                            if (k.isNotBlank() && v.isNotBlank()) svHeaders[k] = v
                        }
                    }
                    val streamsArr = svObj.optJSONArray("streams") ?: continue
                    for (stIdx in 0 until streamsArr.length()) {
                        val stObj = streamsArr.optJSONObject(stIdx) ?: continue
                        val streamUrl = stObj.optString("url").trim()
                        if (!streamUrl.startsWith("http", ignoreCase = true)) continue
                        if (!seenUrls.add(streamUrl)) continue

                        val fmt = stObj.optString("format").trim().lowercase(Locale.US)
                            .ifBlank { stObj.optString("type").trim().lowercase(Locale.US) }
                        val mergedHeaders = defaultHeadersForUrl(streamUrl, svHeaders)
                        val trackTag = when (trackType) {
                            "ssub" -> "SoftSub"
                            "dub" -> "Dub"
                            else -> "Sub"
                        }
                        val serverKey = "$providerId:$trackType:$svName"
                        val candKey = if (stIdx == 0) serverKey else "$serverKey:${stIdx + 1}"
                        val displayLabel = "$svName ($trackTag)" + if (streamsArr.length() > 1) " · ${stIdx + 1}" else ""
                        val display = "${providerId.uppercase(Locale.US)} · $displayLabel"
                        val cand = MiruroStreamCandidate(
                            key = candKey,
                            providerId = providerId,
                            serverName = svName,
                            trackType = trackType,
                            streamUrl = streamUrl,
                            format = fmt,
                            headers = mergedHeaders,
                            subtitles = sortSubtitlesVietnameseFirst(provSubs),
                            displayLabel = display
                        )
                        rawEntries.add(Triple(trackRank, pRank * 100 + svIdx, cand))
                    }
                }
            }
        }

        val sortedGlobalSubs = sortSubtitlesVietnameseFirst(globalSubtitles)
        val sortedEntries = rawEntries.sortedWith(compareBy<Triple<Int, Int, MiruroStreamCandidate>> { it.first }.thenBy { it.second })

        // Ensure every candidate has Vietnamese-first subtitles:
        // own subtitles first + any missing Vietnamese/English subtitles from sibling providers in sourcesData
        return sortedEntries.map { (_, _, cand) ->
            val merged = if (cand.subtitles.isEmpty()) {
                sortedGlobalSubs
            } else if (!hasVietnameseSubtitle(cand.subtitles) && hasVietnameseSubtitle(sortedGlobalSubs)) {
                val viFromOthers = sortedGlobalSubs.filter { isVietnameseTrack(it) }
                sortSubtitlesVietnameseFirst(viFromOthers + cand.subtitles)
            } else {
                cand.subtitles
            }
            cand.copy(subtitles = merged)
        }
    }

    /**
     * Picks and probes the best working candidate from [candidates] using fast parallel wave probing
     * (3 servers at a time with 4s timeout), automatically failing over across `sourcesData` servers
     * if a server returns non-200 or invalid HLS.
     */
    fun resolveWithAutoFailover(
        candidates: List<MiruroStreamCandidate>,
        client: OkHttpClient,
        preferredKey: String = "auto",
        excludeKeys: Set<String> = emptySet(),
        onProgress: ((sourceName: String, current: Int, total: Int) -> Unit)? = null
    ): ResolvedPlayback? {
        if (candidates.isEmpty()) return null
        val normalizedExclude = excludeKeys.map { it.lowercase(Locale.US) }.toSet()
        val ordered = ArrayList<MiruroStreamCandidate>(candidates.size)

        if (preferredKey.isNotBlank() && !preferredKey.equals("auto", ignoreCase = true)) {
            candidates.firstOrNull { it.key.equals(preferredKey, ignoreCase = true) }?.let {
                ordered.add(it)
            }
        }
        for (c in candidates) {
            if (ordered.none { it.key.equals(c.key, ignoreCase = true) } &&
                !normalizedExclude.contains(c.key.lowercase(Locale.US))
            ) {
                ordered.add(c)
            }
        }
        if (ordered.isEmpty()) return null

        val fastClient = client.newBuilder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .callTimeout(5, TimeUnit.SECONDS)
            .build()

        val total = ordered.size
        var checked = 0
        for (wave in ordered.chunked(3)) {
            onProgress?.invoke(wave.first().displayLabel, (checked + 1).coerceAtMost(total), total)
            if (wave.size == 1) {
                val cand = wave.first()
                probePlayback(cand, fastClient)?.let { return it }
                checked++
                continue
            }
            val tasks = wave.map { cand ->
                Callable {
                    probePlayback(cand, fastClient)
                }
            }
            val winner = try {
                val futures = probePool.invokeAll(tasks, 5, TimeUnit.SECONDS)
                var best: ResolvedPlayback? = null
                for (f in futures) {
                    val res = try {
                        if (!f.isCancelled) f.get() else null
                    } catch (_: Throwable) {
                        null
                    }
                    if (res != null) {
                        best = res
                        break
                    }
                }
                best
            } catch (_: Throwable) {
                null
            }
            if (winner != null) {
                return winner
            }
            checked += wave.size
        }
        // If all quick probes timed out (e.g. slow network), still return the first candidate so relay/ExoPlayer can try
        return candidateToPlayback(ordered.first())
    }

    fun candidateToPlayback(cand: MiruroStreamCandidate, wrappedSegments: Boolean = false): ResolvedPlayback {
        val kind = mediaKind(cand)
        val path = cand.streamUrl.substringBefore('?').lowercase(Locale.US)
        return ResolvedPlayback(
            url = cand.streamUrl,
            headers = cand.headers,
            isF4kWrapped = kind == StreamMediaKind.HLS &&
                (wrappedSegments || wrappedExtensions.any(path::endsWith)),
            isWeb = false,
            webUrl = "",
            mediaKind = kind,
            subtitles = cand.subtitles,
            providerId = cand.key
        )
    }

    private fun mediaKind(candidate: MiruroStreamCandidate): StreamMediaKind {
        val urlKind = Phim4KMediaProbe.mediaKind(candidate.streamUrl)
        if (urlKind != StreamMediaKind.UNKNOWN) return urlKind
        return when (candidate.format) {
            "mp4", "video/mp4" -> StreamMediaKind.MP4
            "dash", "mpd", "application/dash+xml" -> StreamMediaKind.DASH
            "mkv", "video/x-matroska" -> StreamMediaKind.MKV
            else -> StreamMediaKind.HLS
        }
    }

    private val wrappedExtensions = listOf(".png", ".jpg", ".jpeg", ".webp", ".f4k")

    private fun probePlayback(cand: MiruroStreamCandidate, client: OkHttpClient): ResolvedPlayback? {
        return try {
            val reqBuilder = Request.Builder()
                .url(cand.streamUrl)
                .header("Accept", "*/*")
            for ((k, v) in cand.headers) {
                reqBuilder.header(k, v)
            }
            client.newCall(reqBuilder.build()).execute().use { resp ->
                if (!resp.isSuccessful) return null
                if (mediaKind(cand) != StreamMediaKind.HLS) return candidateToPlayback(cand)

                val body = resp.body?.string().orEmpty()
                if (!body.contains("#EXTM3U", ignoreCase = true)) return null
                val wrapped = body.lineSequence().map(String::trim)
                    .filter { it.isNotEmpty() && !it.startsWith('#') }
                    .map { it.substringBefore('?').lowercase(Locale.US) }
                    .any { path -> wrappedExtensions.any(path::endsWith) }
                candidateToPlayback(cand, wrappedSegments = wrapped)
            }
        } catch (_: Exception) {
            null
        }
    }
}
