package com.anpinime.projector

import android.net.Uri
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

object AetherSourceResolver {
    const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"
    const val DEFAULT_ORIGIN = "https://aether.st"
    const val DEFAULT_REFERER = "https://aether.st/"
    const val PROXY_JUST = "https://just.raccoonunbutteredbagel.zip/m3u8-proxy"
    const val PROXY_JUSTICE = "https://justice.raccoonunbutteredbagel.zip/m3u8-proxy"

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
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
        ServerOption("auto", "Tự động", "Tự động quét nguồn khả dụng nhanh nhất", "FHD · Auto"),
        ServerOption("lul", "Lul", "Nguồn chính 1080p · Tốc độ cao", "1080p"),
        ServerOption("lil", "Lil", "Nguồn trực tiếp 1080p", "1080p"),
        ServerOption("ly", "Edge (Ly)", "Nguồn 1080p fMP4", "1080p"),
        ServerOption("meridian", "Meridian", "Nguồn 1080p đa phụ đề", "1080p"),
        ServerOption("rock", "VidRock", "Nguồn đa chất lượng 1080p/720p", "1080p"),
        ServerOption("shore", "Shore", "Nguồn 1080p ổn định", "1080p"),
        ServerOption("tik", "Tik", "Nguồn 1080p quốc tế", "1080p"),
        ServerOption("tiki", "Tiki", "Nguồn 360p tiết kiệm dữ liệu", "360p"),
        ServerOption("vfast_supreme", "VFast · Supreme (4K)", "Máy chủ 4K chất lượng cao", "4K"),
        ServerOption("vfast_euro", "VFast · Euro", "Máy chủ âm thanh gốc", "1080p"),
        ServerOption("vfast_prime", "VFast · Prime", "Máy chủ 4K dự phòng", "4K"),
        ServerOption("vfast_zenith", "VFast · Zenith", "Máy chủ phụ", "1080p"),
        ServerOption("filler", "Filler", "Nguồn dự phòng", "1080p")
    )

    data class StreamResult(
        val streamUrl: String,
        val headers: Map<String, String>,
        val sourceId: String,
        val sourceName: String,
        val quality: String = "1080p",
        val subtitles: List<ExternalSubtitleTrack> = emptyList()
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
            querySpecificScraper(targetServerId, mediaType, tmdbId, season, epNum)
                ?: run {
                    // Fallback to auto sweep if user's specific choice failed
                    sweepScrapers(mediaType, tmdbId, season, epNum, onProgress)
                }
        } else {
            sweepScrapers(mediaType, tmdbId, season, epNum, onProgress)
        } ?: throw IllegalStateException("Không tìm thấy luồng phát hợp lệ từ các máy chủ Aether cho phim này.")

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
            isF4kWrapped = false,
            isWeb = false,
            webUrl = episode.url,
            mediaKind = mediaKind,
            subtitles = allSubs
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
        onProgress: ((sourceName: String, current: Int, total: Int) -> Unit)?
    ): StreamResult? {
        val order = listOf(
            "lul", "lil", "ly", "meridian", "rock", "shore", "tik",
            "vfast_supreme", "vfast_euro", "tiki", "filler", "vidy", "baguette", "yildiz"
        )
        val total = order.size
        for ((idx, scraperId) in order.withIndex()) {
            val name = serverDisplayName(scraperId)
            onProgress?.invoke(name, idx + 1, total)
            val res = querySpecificScraper(scraperId, mediaType, tmdbId, season, episode)
            if (res != null) {
                return res
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
            "meridian" -> queryMeridian(path)
            "rock" -> queryRock(path)
            "shore" -> queryShore(path)
            "tik" -> queryTik(path)
            "tiki" -> queryTiki(path)
            "vfast_supreme" -> queryVfast(path, "Supreme")
            "vfast_euro" -> queryVfast(path, "Euro")
            "vfast_prime" -> queryVfast(path, "Prime")
            "vfast_zenith" -> queryVfast(path, "Zenith")
            "vfast" -> queryVfast(path, "Supreme")
            "filler" -> queryFiller(path)
            "vidy" -> queryAetherCxScraper("vidy", path)
            "baguette" -> queryBaguette(path)
            "yildiz" -> queryAetherCxScraper("yildiz", path)
            else -> queryLul(path)
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
                "Origin" to DEFAULT_ORIGIN,
                "Referer" to DEFAULT_REFERER,
                "User-Agent" to USER_AGENT
            ),
            sourceId = "lul",
            sourceName = "Lul",
            quality = "1080p"
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
                "Origin" to DEFAULT_ORIGIN,
                "Referer" to DEFAULT_REFERER,
                "User-Agent" to USER_AGENT
            ),
            sourceId = "lil",
            sourceName = "Lil",
            quality = "1080p"
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

        // Rewrite through Aether's justice m3u8 proxy to ensure 100% CORS & segment success
        val headersJson = JSONObject().apply {
            put("Origin", origin)
            put("Referer", referer)
            put("User-Agent", ua)
        }.toString()
        val proxiedUrl = "$PROXY_JUSTICE?url=${URLEncoder.encode(stream, "UTF-8")}&headers=${URLEncoder.encode(headersJson, "UTF-8")}"

        return StreamResult(
            streamUrl = proxiedUrl,
            headers = mapOf(
                "Origin" to origin,
                "Referer" to referer,
                "User-Agent" to ua
            ),
            sourceId = "ly",
            sourceName = "Edge (Ly)",
            quality = json.optString("quality", "1080p")
        )
    }

    private fun queryMeridian(path: String): StreamResult? {
        val url = "https://meridian.raccoonunbutteredbagel.zip$path"
        val json = fetchJson(url) ?: return null
        val stream = json.optString("url").trim().ifEmpty { json.optString("stream").trim() }
        if (stream.isEmpty()) return null

        val subs = ArrayList<ExternalSubtitleTrack>()
        val subsArr = json.optJSONArray("subtitles")
        if (subsArr != null) {
            for (i in 0 until subsArr.length()) {
                val s = subsArr.optJSONObject(i) ?: continue
                val sUrl = s.optString("url").trim()
                val lang = s.optString("language").trim()
                if (sUrl.isNotEmpty()) {
                    subs.add(ExternalSubtitleTrack(sUrl, lang.lowercase(), lang, "text/vtt"))
                }
            }
        }

        return StreamResult(
            streamUrl = stream,
            headers = mapOf(
                "Origin" to DEFAULT_ORIGIN,
                "Referer" to DEFAULT_REFERER,
                "User-Agent" to USER_AGENT
            ),
            sourceId = "meridian",
            sourceName = "Meridian",
            quality = "1080p",
            subtitles = subs
        )
    }

    private fun queryRock(path: String): StreamResult? {
        val url = "https://rock.raccoonunbutteredbagel.zip$path"
        val json = fetchJson(url) ?: return null
        val stream = json.optString("stream").trim()
        if (stream.isEmpty()) return null

        val origin = "https://vidrock.to"
        val referer = "https://vidrock.to/"
        val headersJson = JSONObject().apply {
            put("Origin", origin)
            put("Referer", referer)
            put("User-Agent", USER_AGENT)
        }.toString()
        val proxiedUrl = "$PROXY_JUST?url=${URLEncoder.encode(stream, "UTF-8")}&headers=${URLEncoder.encode(headersJson, "UTF-8")}"

        return StreamResult(
            streamUrl = proxiedUrl,
            headers = mapOf(
                "Origin" to origin,
                "Referer" to referer,
                "User-Agent" to USER_AGENT
            ),
            sourceId = "rock",
            sourceName = "VidRock",
            quality = "1080p"
        )
    }

    private fun queryShore(path: String): StreamResult? {
        val url = "https://shore.raccoonunbutteredbagel.zip$path"
        val json = fetchJson(url) ?: return null
        var stream = json.optString("stream").trim()
        if (stream.isEmpty()) return null
        if (stream.startsWith("/")) {
            stream = "https://backend.raccoonunbutteredbagel.zip$stream"
        }
        return StreamResult(
            streamUrl = stream,
            headers = mapOf(
                "Origin" to DEFAULT_ORIGIN,
                "Referer" to DEFAULT_REFERER,
                "User-Agent" to USER_AGENT
            ),
            sourceId = "shore",
            sourceName = "Shore",
            quality = "1080p"
        )
    }

    private fun queryTik(path: String): StreamResult? {
        val url = "https://tik.raccoonunbutteredbagel.zip$path"
        val json = fetchJson(url) ?: return null
        val streams = json.optJSONArray("streams")
        val streamObj = streams?.optJSONObject(0)
        val stream = streamObj?.optString("url")?.trim() ?: json.optString("stream").trim()
        if (stream.isEmpty()) return null
        return StreamResult(
            streamUrl = stream,
            headers = mapOf(
                "Origin" to DEFAULT_ORIGIN,
                "Referer" to DEFAULT_REFERER,
                "User-Agent" to USER_AGENT
            ),
            sourceId = "tik",
            sourceName = "Tik",
            quality = "1080p"
        )
    }

    private fun queryTiki(path: String): StreamResult? {
        val url = "https://tiki.raccoonunbutteredbagel.zip$path"
        val json = fetchJson(url) ?: return null
        val stream = json.optString("stream").trim()
        if (stream.isEmpty()) return null
        return StreamResult(
            streamUrl = stream,
            headers = mapOf(
                "Origin" to DEFAULT_ORIGIN,
                "Referer" to DEFAULT_REFERER,
                "User-Agent" to USER_AGENT
            ),
            sourceId = "tiki",
            sourceName = "Tiki",
            quality = "360p"
        )
    }

    private fun queryVfast(path: String, serverName: String): StreamResult? {
        val url = "https://vfast.raccoonunbutteredbagel.zip$path?server=${URLEncoder.encode(serverName, "UTF-8")}"
        val json = fetchJson(url) ?: return null
        val proxiedUrl = json.optString("url").trim()
        val directUrl = json.optString("directUrl").trim()
        val playUrl = proxiedUrl.ifEmpty { directUrl }
        if (playUrl.isEmpty()) return null

        val referer = json.optString("referer").ifEmpty { "https://vidcore.io/" }
        val origin = "https://vidcore.io"

        val subs = ArrayList<ExternalSubtitleTrack>()
        val tracksArr = json.optJSONArray("tracks")
        if (tracksArr != null) {
            for (i in 0 until tracksArr.length()) {
                val t = tracksArr.optJSONObject(i) ?: continue
                val f = t.optString("file").trim()
                val l = t.optString("label").trim()
                if (f.isNotEmpty()) {
                    subs.add(ExternalSubtitleTrack(f, l.lowercase(), l, "text/vtt"))
                }
            }
        }

        val is4k = json.optBoolean("has4k", false) || serverName.contains("4k", ignoreCase = true) || serverName.equals("Supreme", ignoreCase = true)
        return StreamResult(
            streamUrl = playUrl,
            headers = mapOf(
                "Origin" to origin,
                "Referer" to referer,
                "User-Agent" to USER_AGENT
            ),
            sourceId = "vfast_${serverName.lowercase()}",
            sourceName = "VFast · $serverName",
            quality = if (is4k) "4K" else "1080p",
            subtitles = subs
        )
    }

    private fun queryFiller(path: String): StreamResult? {
        val url = "https://filler.raccoonunbutteredbagel.zip$path"
        val json = fetchJson(url) ?: return null
        val stream = json.optString("stream").trim()
        if (stream.isEmpty()) return null
        return StreamResult(
            streamUrl = stream,
            headers = mapOf(
                "Origin" to DEFAULT_ORIGIN,
                "Referer" to DEFAULT_REFERER,
                "User-Agent" to USER_AGENT
            ),
            sourceId = "filler",
            sourceName = "Filler",
            quality = "1080p"
        )
    }

    private fun queryBaguette(path: String): StreamResult? {
        val url = "https://baguette.aether.cx/api$path"
        val json = fetchJson(url) ?: return null
        val streams = json.optJSONArray("streams")
        val streamObj = streams?.optJSONObject(0)
        val stream = streamObj?.optString("url")?.trim() ?: json.optString("stream").trim()
        if (stream.isEmpty()) return null
        return StreamResult(
            streamUrl = stream,
            headers = mapOf(
                "Origin" to DEFAULT_ORIGIN,
                "Referer" to DEFAULT_REFERER,
                "User-Agent" to USER_AGENT
            ),
            sourceId = "baguette",
            sourceName = "Baguette",
            quality = "1080p"
        )
    }

    private fun queryAetherCxScraper(name: String, path: String): StreamResult? {
        val url = "https://$name.aether.cx$path"
        val json = fetchJson(url) ?: return null
        val stream = json.optString("stream").trim().ifEmpty { json.optString("url").trim() }
        if (stream.isEmpty()) return null
        return StreamResult(
            streamUrl = stream,
            headers = mapOf(
                "Origin" to DEFAULT_ORIGIN,
                "Referer" to DEFAULT_REFERER,
                "User-Agent" to USER_AGENT
            ),
            sourceId = name,
            sourceName = name.replaceFirstChar { it.uppercase() },
            quality = "1080p"
        )
    }

    private fun fetchJson(url: String): JSONObject? {
        return try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Origin", DEFAULT_ORIGIN)
                .header("Referer", DEFAULT_REFERER)
                .build()
            val resp = httpClient.newCall(req).execute()
            if (!resp.isSuccessful) return null
            val body = resp.body?.string().orEmpty()
            if (body.startsWith("{")) JSONObject(body) else null
        } catch (_: Throwable) {
            null
        }
    }

    fun fetchExternalSubtitles(
        mediaType: String,
        tmdbId: String,
        season: String,
        episode: String
    ): List<ExternalSubtitleTrack> {
        val out = ArrayList<ExternalSubtitleTrack>()

        // 1. Aether Subtitle Cache (sub.vdrk.site)
        val subUrl = if (mediaType == "tv") {
            "https://sub.vdrk.site/v1/tv/$tmdbId/$season/$episode"
        } else {
            "https://sub.vdrk.site/v1/movie/$tmdbId"
        }
        try {
            val req = Request.Builder()
                .url(subUrl)
                .header("User-Agent", USER_AGENT)
                .header("Origin", DEFAULT_ORIGIN)
                .header("Referer", DEFAULT_REFERER)
                .build()
            val resp = httpClient.newCall(req).execute()
            if (resp.isSuccessful) {
                val body = resp.body?.string().orEmpty()
                if (body.startsWith("[")) {
                    val arr = JSONArray(body)
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        val file = o.optString("file").trim()
                        val label = o.optString("label").trim()
                        if (file.isNotEmpty()) {
                            val lang = if (label.contains("viet", ignoreCase = true)) "vi" else label.lowercase()
                            out.add(ExternalSubtitleTrack(file, lang, label, "text/vtt"))
                        }
                    }
                }
            }
        } catch (_: Throwable) {}

        // 2. OpenSubtitles for Vietnamese
        try {
            // Find IMDB ID from TMDB
            val tmdbExternalUrl = if (mediaType == "tv") {
                "https://api.themoviedb.org/3/tv/$tmdbId/external_ids"
            } else {
                "https://api.themoviedb.org/3/movie/$tmdbId/external_ids"
            }
            val tmdbReq = Request.Builder()
                .url(tmdbExternalUrl)
                .header("Authorization", "Bearer ${AetherCatalog.TMDB_READ_TOKEN}")
                .build()
            val tmdbResp = httpClient.newCall(tmdbReq).execute()
            if (tmdbResp.isSuccessful) {
                val body = tmdbResp.body?.string().orEmpty()
                val imdbId = JSONObject(body).optString("imdb_id").trim()
                if (imdbId.isNotEmpty()) {
                    val osUrl = "https://rest.opensubtitles.org/search/imdbid-${imdbId.removePrefix("tt")}/sublanguageid-vie"
                    val osReq = Request.Builder()
                        .url(osUrl)
                        .header("User-Agent", "TemporaryUserAgent")
                        .build()
                    val osResp = httpClient.newCall(osReq).execute()
                    if (osResp.isSuccessful) {
                        val osBody = osResp.body?.string().orEmpty()
                        if (osBody.startsWith("[")) {
                            val osArr = JSONArray(osBody)
                            for (i in 0 until osArr.length()) {
                                val item = osArr.optJSONObject(i) ?: continue
                                val dl = item.optString("SubDownloadLink").trim()
                                val name = item.optString("SubFileName").trim().ifEmpty { "Tiếng Việt (OpenSubtitles)" }
                                if (dl.isNotEmpty()) {
                                    // OpenSubtitles dl link
                                    out.add(ExternalSubtitleTrack(dl, "vi", "🇻🇳 $name", "application/x-subrip"))
                                }
                            }
                        }
                    }
                }
            }
        } catch (_: Throwable) {}

        return out
    }
}
