package com.anpinime.projector

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object AetherSourceResolver {
    const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"
    const val DEFAULT_ORIGIN = "https://aether.st"
    const val DEFAULT_REFERER = "https://aether.st/"
    const val OPENSUBTITLES_UA = "VLSub 0.10.2"

    fun activeOrigin(): String = AetherCatalog.getPrimaryDomain().trimEnd('/')
    fun activeReferer(): String = "${activeOrigin()}/"

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val probeClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    data class ServerOption(
        val id: String,
        val name: String,
        val description: String,
        val qualityBadge: String = "1080p"
    )

    val SERVERS: List<ServerOption> = listOf(
        ServerOption("auto", "Tự động", "Tự động quét nguồn ổn định nhanh nhất", "FHD · Auto"),
        ServerOption("lul", "Lul", "Nguồn chính 1080p · Tốc độ cao", "1080p"),
        ServerOption("ly", "Edge (Ly)", "Nguồn 1080p Multi Audio & Sub", "1080p"),
        ServerOption("lil", "Lil", "Nguồn trực tiếp 1080p (Phim lẻ)", "1080p"),
        ServerOption("rock", "VidRock", "Nguồn 1080p ổn định", "1080p"),
        ServerOption("shore", "Shore", "Nguồn dự phòng 1080p", "1080p")
    )

    data class StreamResult(
        val streamUrl: String,
        val headers: Map<String, String>,
        val sourceId: String,
        val sourceName: String,
        val quality: String = "1080p",
        val subtitles: List<ExternalSubtitleTrack> = emptyList(),
        val needsRelay: Boolean = false
    )

    fun resolve(
        episode: Episode,
        preferredServerId: String = "auto",
        onProgress: ((sourceName: String, current: Int, total: Int) -> Unit)? = null
    ): ResolvedPlayback {
        val ref = AetherCatalog.parseMediaRef(episode.url.ifBlank { episode.directUrl })
            ?: AetherCatalog.parseMediaRef(episode.slug)
            ?: run {
                val slugParts = episode.slug.split(':')
                if (slugParts.size >= 2) {
                    AetherCatalog.MediaRef(slugParts[0], slugParts[1], episode.seasonNum.coerceAtLeast(1).toString(), episode.episodeNum.coerceAtLeast(1).toString())
                } else null
            }

        val mediaType = ref?.mediaType ?: if (episode.slug.startsWith("tv:", ignoreCase = true)) "tv" else "movie"
        val tmdbId = ref?.tmdbId?.takeIf { it.isNotEmpty() }
            ?: episode.slug.substringAfter(':').takeWhile { it.isDigit() }.takeIf { it.isNotEmpty() }
            ?: "238"

        val season = if (mediaType == "tv") {
            episode.seasonNum.coerceAtLeast(1).toString()
        } else "1"
        val epNum = if (mediaType == "tv") {
            episode.episodeNum.coerceAtLeast(1).toString()
        } else "1"

        val targetServerId = if (preferredServerId != "auto") {
            preferredServerId
        } else {
            episode.episodeKey.takeIf { it.isNotBlank() && it != "auto" } ?: "auto"
        }

        val result = if (targetServerId != "auto") {
            onProgress?.invoke(serverDisplayName(targetServerId), 1, 1)
            val specific = querySpecificScraper(targetServerId, mediaType, tmdbId, season, epNum)
                ?.let { verifyStreamResult(it) }
            specific ?: sweepScrapers(mediaType, tmdbId, season, epNum, onProgress, excludeId = targetServerId)
        } else {
            sweepScrapers(mediaType, tmdbId, season, epNum, onProgress)
        } ?: throw IllegalStateException("Không tìm thấy luồng phát hợp lệ từ các máy chủ Thúy An cho phim này.")

        // Collect subtitles from all available providers
        val allSubs = ArrayList<ExternalSubtitleTrack>()
        allSubs.addAll(result.subtitles)
        val externalSubs = fetchExternalSubtitles(mediaType, tmdbId, season, epNum)
        for (sub in externalSubs) {
            if (allSubs.none { it.url.equals(sub.url, ignoreCase = true) }) {
                allSubs.add(sub)
            }
        }

        // Sort so Vietnamese is on top, then English, then others
        allSubs.sortWith(Comparator { a, b ->
            val aVi = a.language.startsWith("vi", ignoreCase = true) || a.label.contains("việt", ignoreCase = true) || a.label.contains("viet", ignoreCase = true)
            val bVi = b.language.startsWith("vi", ignoreCase = true) || b.label.contains("việt", ignoreCase = true) || b.label.contains("viet", ignoreCase = true)
            if (aVi && !bVi) return@Comparator -1
            if (!aVi && bVi) return@Comparator 1

            val aEn = a.language.startsWith("en", ignoreCase = true) || a.label.contains("eng", ignoreCase = true)
            val bEn = b.language.startsWith("en", ignoreCase = true) || b.label.contains("eng", ignoreCase = true)
            if (aEn && !bEn) return@Comparator -1
            if (!aEn && bEn) return@Comparator 1
            0
        })

        val mediaKind = when {
            result.streamUrl.contains(".mpd", ignoreCase = true) -> StreamMediaKind.DASH
            result.streamUrl.contains(".mkv", ignoreCase = true) -> StreamMediaKind.MKV
            result.streamUrl.contains(".mp4", ignoreCase = true) -> StreamMediaKind.MP4
            else -> StreamMediaKind.HLS
        }

        return ResolvedPlayback(
            url = result.streamUrl,
            headers = result.headers,
            isF4kWrapped = result.needsRelay,
            isWeb = false,
            webUrl = episode.url,
            mediaKind = mediaKind,
            subtitles = allSubs,
            providerId = result.sourceId
        )
    }

    private fun serverDisplayName(id: String): String {
        return SERVERS.firstOrNull { it.id.equals(id, ignoreCase = true) }?.name ?: id
    }

    private fun sweepScrapers(
        mediaType: String,
        tmdbId: String,
        season: String,
        episode: String,
        onProgress: ((sourceName: String, current: Int, total: Int) -> Unit)?,
        excludeId: String? = null
    ): StreamResult? {
        val order = listOf("lul", "ly", "lil", "rock", "shore")
            .filterNot { excludeId != null && it.equals(excludeId, ignoreCase = true) }
        val total = order.size.coerceAtLeast(1)
        for ((idx, scraperId) in order.withIndex()) {
            val name = serverDisplayName(scraperId)
            onProgress?.invoke(name, idx + 1, total)
            val raw = querySpecificScraper(scraperId, mediaType, tmdbId, season, episode) ?: continue
            val verified = verifyStreamResult(raw)
            if (verified != null) {
                return verified
            }
        }
        return null
    }

    private fun querySpecificScraper(
        scraperId: String,
        mediaType: String,
        tmdbId: String,
        season: String,
        episode: String
    ): StreamResult? {
        val path = if (mediaType == "tv") "/tv/$tmdbId/$season/$episode" else "/movie/$tmdbId"
        return when (scraperId.lowercase()) {
            "lul" -> queryLul(path)
            "lil" -> queryLil(path)
            "ly" -> queryLy(path)
            "rock" -> queryRock(path)
            "shore" -> queryShore(mediaType, tmdbId, season, episode)
            else -> queryLul(path)
        }
    }

    private fun verifyStreamResult(result: StreamResult): StreamResult? {
        return try {
            val probe1 = fetchPlaylistHead(result.streamUrl, result.headers, maxBytes = 16 * 1024) ?: return null
            val (finalUrl, body) = probe1
            val trimmed = body.trimStart('\uFEFF', ' ', '\t', '\r', '\n')
            if (!trimmed.startsWith("#EXTM3U")) return null

            if (!trimmed.contains("#EXTINF:")) {
                val variantLine = trimmed.lineSequence()
                    .map { it.trim() }
                    .firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
                if (variantLine != null) {
                    val variantUrl = URI(finalUrl).resolve(variantLine).toString()
                    val vProbe = fetchPlaylistHead(variantUrl, result.headers, maxBytes = 4096) ?: return null
                    if (!vProbe.second.trimStart('\uFEFF', ' ', '\t', '\r', '\n').startsWith("#EXTM3U")) {
                        return null
                    }
                }
            }
            if (finalUrl != result.streamUrl && result.sourceId == "lul") {
                result.copy(streamUrl = finalUrl)
            } else {
                result
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun fetchPlaylistHead(
        url: String,
        headers: Map<String, String>,
        maxBytes: Int = 8192
    ): Pair<String, String>? {
        val reqBuilder = Request.Builder().url(url)
        for ((k, v) in headers) {
            reqBuilder.header(k, v)
        }
        if (headers.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
            reqBuilder.header("User-Agent", USER_AGENT)
        }
        return probeClient.newCall(reqBuilder.build()).execute().use { resp ->
            if (!resp.isSuccessful) return@use null
            val bodySource = resp.body?.source() ?: return@use null
            val buf = okio.Buffer()
            while (buf.size < maxBytes) {
                val read = bodySource.read(buf, maxBytes - buf.size)
                if (read == -1L) break
            }
            resp.request.url.toString() to buf.readUtf8()
        }
    }

    private fun queryLul(path: String): StreamResult? {
        val url = "https://lul.raccoonunbutteredbagel.zip$path"
        val json = fetchJson(url) ?: return null
        val stream = json.optString("stream").trim()
        if (stream.isEmpty()) return null
        return StreamResult(
            streamUrl = stream,
            headers = mapOf(
                "Origin" to activeOrigin(),
                "Referer" to activeReferer(),
                "User-Agent" to USER_AGENT
            ),
            sourceId = "lul",
            sourceName = "Lul",
            quality = "1080p",
            needsRelay = true
        )
    }

    private fun queryLil(path: String): StreamResult? {
        val url = "https://lil.raccoonunbutteredbagel.zip$path"
        val json = fetchJson(url) ?: return null
        val streamObj = json.optJSONObject("stream")
        val streamUrl = streamObj?.optString("url")?.trim() ?: json.optString("stream").trim()
        if (streamUrl.isEmpty()) return null
        return StreamResult(
            streamUrl = streamUrl,
            headers = mapOf(
                "Origin" to activeOrigin(),
                "Referer" to activeReferer(),
                "User-Agent" to USER_AGENT
            ),
            sourceId = "lil",
            sourceName = "Lil",
            quality = "1080p",
            needsRelay = true
        )
    }

    private fun queryLy(path: String): StreamResult? {
        val url = "https://ly.raccoonunbutteredbagel.zip$path"
        val json = fetchJson(url) ?: return null
        val stream = json.optString("stream").trim()
        if (stream.isEmpty()) return null
        val proxyHeaders = json.optJSONObject("proxyHeaders")
        val origin = proxyHeaders?.optString("Origin")?.takeIf { it.isNotEmpty() } ?: "https://vidly.pro"
        val referer = proxyHeaders?.optString("Referer")?.takeIf { it.isNotEmpty() } ?: "https://vidly.pro/"
        val ua = proxyHeaders?.optString("User-Agent")?.takeIf { it.isNotEmpty() } ?: USER_AGENT

        // Route via FilmMediaRelay so OkHttp headers + `.png`-disguised TS MIME + multi-audio playlists work cleanly
        return StreamResult(
            streamUrl = stream,
            headers = mapOf(
                "Origin" to origin,
                "Referer" to referer,
                "User-Agent" to ua
            ),
            sourceId = "ly",
            sourceName = "Edge (Ly)",
            quality = json.optString("quality", "1080p"),
            needsRelay = true
        )
    }

    private fun queryRock(path: String): StreamResult? {
        val url = "https://rock.raccoonunbutteredbagel.zip$path"
        val json = fetchJson(url) ?: return null
        val stream = json.optString("stream").trim()
        if (stream.isEmpty()) return null

        // vidrock streams wrap TS in PNG `tsDa` chunks or text/plain TS; FilmMediaRelay unwraps cleanly
        return StreamResult(
            streamUrl = stream,
            headers = mapOf(
                "Origin" to "https://vidrock.to",
                "Referer" to "https://vidrock.to/",
                "User-Agent" to USER_AGENT
            ),
            sourceId = "rock",
            sourceName = "VidRock",
            quality = "1080p",
            needsRelay = true
        )
    }

    private fun queryShore(mediaType: String, tmdbId: String, season: String, episode: String): StreamResult? {
        val shorePath = if (mediaType == "tv") "/show/$tmdbId?s=$season&e=$episode" else "/movie/$tmdbId"
        val url = "https://shore.raccoonunbutteredbagel.zip$shorePath"
        val json = fetchJson(url) ?: return null
        var stream = json.optString("stream").trim()
        if (stream.isEmpty()) return null
        val shoreHeaders = mapOf(
            "Origin" to activeOrigin(),
            "Referer" to activeReferer(),
            "User-Agent" to USER_AGENT
        )
        if (stream.startsWith("/")) {
            val rawProxyUrl = "https://shore.raccoonunbutteredbagel.zip$stream"
            val unwrapped = runCatching {
                val u = URI(rawProxyUrl)
                val q = u.rawQuery.orEmpty()
                q.split('&').firstOrNull { it.startsWith("url=") }?.substringAfter("url=")
                    ?.let { java.net.URLDecoder.decode(it, "UTF-8") }
            }.getOrNull()?.takeIf { it.startsWith("http") }
            stream = if (unwrapped != null) {
                val probed = runCatching { fetchPlaylistHead(unwrapped, shoreHeaders, maxBytes = 4096) }.getOrNull()
                if (probed != null && probed.second.trimStart('\uFEFF', ' ', '\t', '\r', '\n').startsWith("#EXTM3U")) {
                    unwrapped
                } else {
                    rawProxyUrl
                }
            } else {
                rawProxyUrl
            }
        }
        return StreamResult(
            streamUrl = stream,
            headers = shoreHeaders,
            sourceId = "shore",
            sourceName = "Shore",
            quality = "1080p",
            needsRelay = true
        )
    }

    private fun fetchJson(url: String): JSONObject? {
        return try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Origin", activeOrigin())
                .header("Referer", activeReferer())
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string().orEmpty()
                if (body.startsWith("{")) JSONObject(body) else null
            }
        } catch (_: Throwable) {
            null
        }
    }

    fun normalizeOpenSubtitlesUrl(rawUrl: String): String {
        var u = rawUrl.trim()
        if (u.isEmpty()) return ""
        if (u.endsWith(".gz", ignoreCase = true)) {
            u = u.dropLast(3)
        }
        if (!u.contains("subencoding-utf8", ignoreCase = true) && u.contains("/download/", ignoreCase = true)) {
            u = u.replace("/download/", "/download/subencoding-utf8/", ignoreCase = true)
        }
        return u
    }

    fun normalizeSubtitleLanguage(rawLabel: String, rawIso: String = ""): String {
        val code = rawIso.lowercase(Locale.US).trim()
        if (code in setOf("vi", "vie")) return "vi"
        if (code in setOf("en", "eng")) return "en"

        val base = rawLabel.lowercase(Locale.US)
            .replace(Regex("""\([^)]*\)"""), " ")
            .replace(Regex("""\bhi\d*\b"""), " ")
            .replace(Regex("""\d+"""), " ")
            .trim()
        return when {
            base.contains("viet") || base.contains("tiếng việt") -> "vi"
            base.startsWith("english") || base == "eng" -> "en"
            base.startsWith("spanish") -> "es"
            base.startsWith("french") -> "fr"
            base.startsWith("german") -> "de"
            base.startsWith("japanese") -> "ja"
            base.startsWith("korean") -> "ko"
            base.startsWith("chinese") -> "zh"
            base.startsWith("thai") -> "th"
            base.startsWith("indonesian") -> "id"
            base.startsWith("arabic") -> "ar"
            base.startsWith("russian") -> "ru"
            base.startsWith("portuguese") || base.startsWith("brazilian") -> "pt"
            base.startsWith("italian") -> "it"
            base.startsWith("turkish") -> "tr"
            base.startsWith("dutch") -> "nl"
            base.startsWith("polish") -> "pl"
            base.startsWith("hindi") -> "hi"
            base.startsWith("malay") -> "ms"
            base.startsWith("ukrainian") -> "uk"
            code.length in 2..3 -> code
            else -> base.take(2).ifEmpty { "und" }
        }
    }

    fun fetchExternalSubtitles(
        mediaType: String,
        tmdbId: String,
        season: String,
        episode: String
    ): List<ExternalSubtitleTrack> {
        val out = ArrayList<ExternalSubtitleTrack>()
        val pool = Executors.newFixedThreadPool(3)
        try {
            val vdrkFuture = pool.submit(Callable {
                fetchVdrkSubtitles(mediaType, tmdbId, season, episode)
            })
            val openSubFuture = pool.submit(Callable {
                fetchOpenSubtitles(mediaType, tmdbId, season, episode)
            })

            val vdrkSubs = try { vdrkFuture.get(8, TimeUnit.SECONDS) } catch (_: Throwable) { emptyList() }
            val osSubs = try { openSubFuture.get(9, TimeUnit.SECONDS) } catch (_: Throwable) { emptyList() }

            // Prioritize OpenSubtitles UTF-8 + vdrk with bounded non-excessive count per language
            val merged = osSubs + vdrkSubs
            val langCounts = HashMap<String, Int>()
            for (sub in merged) {
                val limit = when (sub.language) {
                    "vi" -> 12
                    "en" -> 6
                    else -> 1
                }
                val cnt = langCounts[sub.language] ?: 0
                if (cnt < limit && out.none { it.url.equals(sub.url, ignoreCase = true) }) {
                    out.add(sub)
                    langCounts[sub.language] = cnt + 1
                }
                if (out.size >= 30) break
            }
        } finally {
            pool.shutdownNow()
        }
        return out
    }

    private fun fetchVdrkSubtitles(
        mediaType: String,
        tmdbId: String,
        season: String,
        episode: String
    ): List<ExternalSubtitleTrack> {
        val out = ArrayList<ExternalSubtitleTrack>()
        val subUrl = if (mediaType == "tv") {
            "https://sub.vdrk.site/v1/tv/$tmdbId/$season/$episode"
        } else {
            "https://sub.vdrk.site/v1/movie/$tmdbId"
        }
        try {
            val req = Request.Builder()
                .url(subUrl)
                .header("User-Agent", USER_AGENT)
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string().orEmpty()
                    if (body.startsWith("[")) {
                        val arr = JSONArray(body)
                        for (i in 0 until arr.length()) {
                            val o = arr.optJSONObject(i) ?: continue
                            val file = o.optString("file").trim()
                            val label = o.optString("label").trim()
                            if (file.isNotEmpty()) {
                                val lang = normalizeSubtitleLanguage(label)
                                val mime = if (file.lowercase(Locale.US).contains(".srt")) "application/x-subrip" else "text/vtt"
                                val prettyLabel = if (lang == "vi" && !label.contains("🇻🇳")) "🇻🇳 $label (VDRK)" else "$label (VDRK)"
                                out.add(ExternalSubtitleTrack(file, lang, prettyLabel, mime))
                            }
                        }
                    }
                }
            }
        } catch (_: Throwable) {}
        return out
    }

    private fun fetchOpenSubtitles(
        mediaType: String,
        tmdbId: String,
        season: String,
        episode: String
    ): List<ExternalSubtitleTrack> {
        val out = ArrayList<ExternalSubtitleTrack>()
        try {
            val tmdbExternalUrl = if (mediaType == "tv") {
                "https://api.themoviedb.org/3/tv/$tmdbId/external_ids"
            } else {
                "https://api.themoviedb.org/3/movie/$tmdbId/external_ids"
            }
            val tmdbReq = Request.Builder()
                .url(tmdbExternalUrl)
                .header("Authorization", "Bearer ${AetherCatalog.TMDB_READ_TOKEN}")
                .build()
            val imdbId = httpClient.newCall(tmdbReq).execute().use { tmdbResp ->
                if (!tmdbResp.isSuccessful) return emptyList()
                val body = tmdbResp.body?.string().orEmpty()
                JSONObject(body).optString("imdb_id").trim()
            }
            if (imdbId.isEmpty()) return emptyList()

            val numImdb = imdbId.removePrefix("tt")
            for ((queryLang, isoLang, prefix) in listOf(
                Triple("vie", "vi", "🇻🇳"),
                Triple("eng", "en", "🇬🇧")
            )) {
                val osUrl = if (mediaType == "tv") {
                    "https://rest.opensubtitles.org/search/episode-$episode/imdbid-$numImdb/season-$season/sublanguageid-$queryLang"
                } else {
                    "https://rest.opensubtitles.org/search/imdbid-$numImdb/sublanguageid-$queryLang"
                }
                val osReq = Request.Builder()
                    .url(osUrl)
                    .header("User-Agent", USER_AGENT)
                    .header("X-User-Agent", OPENSUBTITLES_UA)
                    .build()
                try {
                    httpClient.newCall(osReq).execute().use { osResp ->
                        if (!osResp.isSuccessful) return@use
                        val osBody = osResp.body?.string().orEmpty()
                        if (!osBody.startsWith("[")) return@use
                        val osArr = JSONArray(osBody)
                        val ranked = ArrayList<Pair<Int, ExternalSubtitleTrack>>()
                        for (i in 0 until osArr.length()) {
                            val item = osArr.optJSONObject(i) ?: continue
                            val rawDl = item.optString("SubDownloadLink").trim()
                            val cleanDl = normalizeOpenSubtitlesUrl(rawDl)
                            if (cleanDl.isEmpty()) continue
                            val fmt = item.optString("SubFormat").lowercase(Locale.US).trim()
                            if (fmt in setOf("sub", "idx", "smi", "txt")) continue
                            val mime = when (fmt) {
                                "vtt" -> "text/vtt"
                                "ass", "ssa" -> "text/x-ssa"
                                else -> "application/x-subrip"
                            }
                            val name = item.optString("SubFileName").trim()
                                .ifEmpty { if (isoLang == "vi") "Tiếng Việt (OpenSubtitles)" else "English (OpenSubtitles)" }
                            val downloads = item.optString("SubDownloadsCnt").toIntOrNull() ?: 0
                            ranked.add(
                                downloads to ExternalSubtitleTrack(
                                    cleanDl,
                                    isoLang,
                                    "$prefix $name",
                                    mime
                                )
                            )
                        }
                        ranked.sortByDescending { it.first }
                        ranked.take(6).forEach { out.add(it.second) }
                    }
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}
        return out
    }
}
