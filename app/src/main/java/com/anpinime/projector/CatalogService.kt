package com.anpinime.projector

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.CookieHandler
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.zip.Inflater
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object CatalogService {
    const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 11; SmartProjector K670 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    private const val FILM4K_BASE = "https://film4k.net"
    private var animeBaseUrl = "https://animevietsub.vip"
    private var f4kTicket: String? = null
    private var f4kTicketExpiry = 0L

    private var phim4kFree1 = "https://phimapi.com"
    private var phim4kFree1Mirror = "https://free.cryboiz.workers.dev"
    private var phim4kFree2 = "https://phim.nguonc.com"
    private var phim4kCoreUrl = "https://api.phim4k.online"
    private var phim4kCoreKey = "bbbb411dea44849"
    private var directoryLoaded = false

    init {
        if (CookieHandler.getDefault() == null) {
            CookieHandler.setDefault(CookieManager(null, CookiePolicy.ACCEPT_ALL))
        }
    }

    val animeCategories = listOf(
        AnimeCategory("Mới cập nhật", "/anime-moi/"),
        AnimeCategory("TV/Series", "/anime-bo/"),
        AnimeCategory("Movie/OVA", "/anime-le/"),
        AnimeCategory("Hoạt hình Trung Quốc", "/hoat-hinh-trung-quoc/"),
        AnimeCategory("Đang chiếu", "/danh-sach/list-dang-chieu/"),
        AnimeCategory("Trọn bộ", "/danh-sach/list-tron-bo/"),
        AnimeCategory("Hành động", "/the-loai/hanh-dong/"),
        AnimeCategory("Phiêu lưu", "/the-loai/phieu-luu/"),
        AnimeCategory("Hài hước", "/the-loai/hai-huoc/"),
        AnimeCategory("Tình cảm", "/the-loai/tinh-cam/"),
        AnimeCategory("Phép thuật", "/the-loai/phep-thuat/"),
        AnimeCategory("Kinh dị", "/the-loai/kinh-di/")
    )

    val phim4kCategories = listOf(
        AnimeCategory("Mới cập nhật", "danh-sach/phim-moi-cap-nhat-v3"),
        AnimeCategory("Phim bộ", "v1/api/danh-sach/phim-bo"),
        AnimeCategory("Phim lẻ", "v1/api/danh-sach/phim-le"),
        AnimeCategory("Hoạt hình", "v1/api/danh-sach/hoat-hinh"),
        AnimeCategory("TV Shows", "v1/api/danh-sach/tv-shows"),
        AnimeCategory("Hàn Quốc", "v1/api/quoc-gia/han-quoc"),
        AnimeCategory("Trung Quốc", "v1/api/quoc-gia/trung-quoc"),
        AnimeCategory("Việt Nam", "v1/api/quoc-gia/viet-nam"),
        AnimeCategory("Âu Mỹ", "v1/api/quoc-gia/au-my"),
        AnimeCategory("Nhật Bản", "v1/api/quoc-gia/nhat-ban"),
        AnimeCategory("Thái Lan", "v1/api/quoc-gia/thai-lan"),
        AnimeCategory("Hành Động", "v1/api/the-loai/hanh-dong"),
        AnimeCategory("Tình Cảm", "v1/api/the-loai/tinh-cam"),
        AnimeCategory("Hài Hước", "v1/api/the-loai/hai-huoc"),
        AnimeCategory("Cổ Trang", "v1/api/the-loai/co-trang"),
        AnimeCategory("Kinh Dị", "v1/api/the-loai/kinh-di"),
        AnimeCategory("Viễn Tưởng", "v1/api/the-loai/vien-tuong"),
        AnimeCategory("Bí Ẩn", "v1/api/the-loai/bi-an")
    )

    var film4kCategories: List<AnimeCategory> = listOf(
        AnimeCategory("Phim lẻ", "type=movie"),
        AnimeCategory("Phim bộ", "type=tv")
    )
        private set

    fun categoriesFor(source: ContentSource): List<AnimeCategory> = when (source) {
        ContentSource.FILM4K -> film4kCategories
        ContentSource.PHIM4K -> phim4kCategories
        ContentSource.ANIME -> animeCategories
    }

    suspend fun loadHome(source: ContentSource): List<AnimeSection> = withContext(Dispatchers.IO) {
        when (source) {
            ContentSource.FILM4K -> loadFilm4kHome()
            ContentSource.PHIM4K -> loadPhim4kHome()
            ContentSource.ANIME -> loadAnimeHome()
        }
    }

    suspend fun loadList(source: ContentSource, path: String, page: Int = 1): List<Anime> = withContext(Dispatchers.IO) {
        when (source) {
            ContentSource.FILM4K -> {
                val query = if (path.isBlank()) "page=$page" else "$path&page=$page"
                val json = getJson("$FILM4K_BASE/api/home?$query")
                parseFilm4kItems(json.optJSONArray("list"))
            }
            ContentSource.PHIM4K -> {
                val target = path.ifBlank { "danh-sach/phim-moi-cap-nhat-v3" }.trimStart('/')
                for (host in listOf(phim4kFree1, phim4kFree1Mirror)) {
                    try {
                        val json = getJson("$host/$target?page=$page")
                        val items = parsePhim4kItems(json, host)
                        if (items.isNotEmpty()) return@withContext items
                    } catch (_: Throwable) {}
                }
                emptyList()
            }
            ContentSource.ANIME -> {
                ensureAnimeDomain()
                val clean = if (path.startsWith("http")) path else "$animeBaseUrl/${path.trimStart('/')}"
                val pageUrl = if (page > 1) "${clean.trimEnd('/')}/trang-$page.html" else clean
                val html = getText(pageUrl, referer = animeBaseUrl)
                parseAnimeItems(html, animeBaseUrl)
            }
        }
    }

    suspend fun search(source: ContentSource, query: String): List<Anime> = withContext(Dispatchers.IO) {
        val enc = URLEncoder.encode(query.trim(), "UTF-8")
        when (source) {
            ContentSource.FILM4K -> {
                val json = getJson("$FILM4K_BASE/api/search/suggest?q=$enc")
                parseFilm4kItems(json.optJSONArray("list"))
            }
            ContentSource.PHIM4K -> {
                for (host in listOf(phim4kFree1, phim4kFree1Mirror)) {
                    try {
                        val json = getJson("$host/v1/api/tim-kiem?keyword=$enc")
                        val items = parsePhim4kItems(json, host)
                        if (items.isNotEmpty()) return@withContext items
                    } catch (_: Throwable) {}
                }
                try {
                    val json = getJson("$phim4kFree2/api/films/search?keyword=$enc")
                    return@withContext parsePhim4kItems(json, phim4kFree2)
                } catch (_: Throwable) {}
                emptyList()
            }
            ContentSource.ANIME -> {
                ensureAnimeDomain()
                val html = getText("$animeBaseUrl/tim-kiem/$enc/", referer = animeBaseUrl)
                parseAnimeItems(html, animeBaseUrl)
            }
        }
    }

    suspend fun loadDetail(anime: Anime): AnimeDetail = withContext(Dispatchers.IO) {
        when (anime.source) {
            ContentSource.FILM4K -> loadFilm4kDetail(anime)
            ContentSource.PHIM4K -> loadPhim4kDetail(anime)
            ContentSource.ANIME -> loadAnimeDetail(anime)
        }
    }

    suspend fun resolvePlayback(episode: Episode): ResolvedPlayback = withContext(Dispatchers.IO) {
        when (episode.playbackKind) {
            "film4k" -> resolveFilm4kPlayback(episode)
            "direct" -> ResolvedPlayback(
                url = episode.directUrl,
                headers = mapOf(
                    "Referer" to (episode.refererUrl.ifBlank { "$phim4kCoreUrl/" }),
                    "User-Agent" to USER_AGENT
                )
            )
            "phim4k" -> resolvePhim4kPlayback(episode)
            "anime" -> resolveAnimePlayback(episode)
            else -> throw IllegalStateException("Không hỗ trợ định dạng phát này.")
        }
    }

    // =========================================================================
    // NGUỒN PHIM 1 (Film4k.net)
    // =========================================================================

    private fun loadFilm4kHome(): List<AnimeSection> {
        val json = getJson("$FILM4K_BASE/api/home")
        val genres = json.optJSONArray("genres")
        if (genres != null && genres.length() > 0) {
            val cats = ArrayList<AnimeCategory>()
            cats.add(AnimeCategory("Phim lẻ", "type=movie"))
            cats.add(AnimeCategory("Phim bộ", "type=tv"))
            for (i in 0 until genres.length()) {
                val g = genres.optString(i)
                if (g.isNotBlank()) {
                    cats.add(AnimeCategory(g, "genre=${URLEncoder.encode(g, "UTF-8")}"))
                }
            }
            film4kCategories = cats
        }
        val map = listOf(
            "Mới cập nhật" to "list",
            "Phim nổi bật" to "top",
            "Phim lẻ mới" to "newMovies",
            "Phim bộ mới" to "newSeries"
        )
        return map.mapNotNull { (title, key) ->
            val items = parseFilm4kItems(json.optJSONArray(key))
            if (items.isEmpty()) null else AnimeSection(title, items)
        }
    }

    private fun parseFilm4kItems(arr: JSONArray?): List<Anime> {
        if (arr == null) return emptyList()
        val seen = HashSet<String>()
        val out = ArrayList<Anime>(arr.length())
        for (i in 0 until arr.length()) {
            val row = arr.optJSONObject(i) ?: continue
            val slug = row.optString("slug")
            val title = localizedText(row.opt("title"))
            if (slug.isBlank() || title.isBlank() || !seen.add(slug)) continue
            val enTitle = (row.opt("title") as? JSONObject)?.optString("en") ?: ""
            val epCount = row.optString("epCount")
            val epText = if (row.optString("mediaType") == "tv") {
                if (epCount.isBlank() || epCount == "null") "Phim bộ" else "$epCount tập"
            } else "Phim lẻ"
            val year = row.optString("year").takeIf { it != "null" } ?: ""
            val country = localizedText(row.opt("country"))
            val infoParts = listOf(year, country, epText).filter { it.isNotBlank() }
            val subtitle = if (enTitle.isNotBlank() && !enTitle.equals(title, ignoreCase = true)) {
                enTitle
            } else infoParts.joinToString(" • ")
            val poster = resolveUrl(localizedText(row.opt("poster")), FILM4K_BASE)
            val backdrop = resolveUrl(localizedText(row.opt("backdrop")), FILM4K_BASE) ?: poster
            val scoreNum = row.optDouble("score", 0.0)
            val score = if (scoreNum > 0 && scoreNum.isFinite()) String.format("%.1f", scoreNum) else ""
            out.add(
                Anime(
                    title = title,
                    subtitle = subtitle,
                    url = "$FILM4K_BASE/movie/$slug",
                    imageUrl = poster,
                    bannerUrl = backdrop,
                    episodeText = epText,
                    quality = row.optString("maxRes"),
                    score = score,
                    summary = localizedText(row.opt("overview")),
                    info = infoParts.joinToString(" • "),
                    source = ContentSource.FILM4K
                )
            )
        }
        return out
    }

    private fun loadFilm4kDetail(anime: Anime): AnimeDetail {
        val slug = anime.url.substringAfterLast('/')
        val json = getJson("$FILM4K_BASE/api/title/$slug")
        val movie = json.optJSONObject("movie") ?: JSONObject()
        val maxRes = movie.optString("maxRes").ifBlank { anime.quality }
        val rows = json.optJSONArray("episodes")
        val episodes = ArrayList<Episode>()
        if (rows == null || rows.length() == 0) {
            val sources = json.optJSONArray("sources") ?: movie.optJSONArray("sources")
            val count = (sources?.length() ?: 1).coerceAtLeast(1)
            for (sIdx in 0 until count) {
                val srcObj = sources?.optJSONObject(sIdx)
                val label = srcObj?.optString("label")?.ifBlank { null }
                    ?: srcObj?.optString("name")?.ifBlank { null }
                    ?: if (count > 1) "Server ${sIdx + 1}" else "Phim lẻ"
                val badge = srcObj?.optString("quality")?.ifBlank { maxRes } ?: maxRes
                episodes.add(
                    Episode(
                        title = "Xem phim",
                        url = "$FILM4K_BASE/watch/$slug",
                        number = 1,
                        season = label,
                        qualityBadge = badge,
                        source = ContentSource.FILM4K,
                        playbackKind = "film4k",
                        slug = slug,
                        seasonNum = -1,
                        episodeNum = -1,
                        serverIndex = sIdx
                    )
                )
            }
        } else {
            for (i in 0 until rows.length()) {
                val row = rows.optJSONObject(i) ?: continue
                val season = row.optInt("season", 1)
                val number = row.optInt("episode", i + 1)
                val srcs = row.optJSONArray("sources")
                if (srcs == null || srcs.length() == 0) continue
                val key = String.format("s%02de%02d", season, number)
                episodes.add(
                    Episode(
                        title = "Tập $number",
                        url = "$FILM4K_BASE/watch/$slug?e=$key&from=0",
                        number = number,
                        season = "Phần $season",
                        qualityBadge = maxRes,
                        source = ContentSource.FILM4K,
                        playbackKind = "film4k",
                        slug = slug,
                        seasonNum = season,
                        episodeNum = number,
                        serverIndex = 0
                    )
                )
            }
            episodes.sortWith(compareBy({ it.season }, { it.number }))
        }
        val year = movie.optString("year").takeIf { it != "null" } ?: ""
        val country = localizedText(movie.opt("country"))
        val typeText = if (movie.optString("mediaType") == "tv") "Phim bộ" else "Phim lẻ"
        return AnimeDetail(
            title = localizedText(movie.opt("title")).ifBlank { anime.title },
            posterUrl = resolveUrl(localizedText(movie.opt("poster")), FILM4K_BASE) ?: anime.imageUrl,
            bannerUrl = resolveUrl(localizedText(movie.opt("backdrop")), FILM4K_BASE) ?: anime.bannerUrl ?: anime.imageUrl,
            summary = localizedText(movie.opt("overview")).ifBlank { anime.summary.ifBlank { "Chưa có nội dung giới thiệu." } },
            info = listOf(year, country, maxRes, typeText).filter { it.isNotBlank() }.joinToString(" • "),
            episodes = episodes
        )
    }

    private fun resolveFilm4kPlayback(episode: Episode): ResolvedPlayback {
        val now = System.currentTimeMillis()
        if (f4kTicket == null || now > f4kTicketExpiry - 30_000L) {
            try {
                val ticketJson = postJson("$FILM4K_BASE/api/play-ticket")
                f4kTicket = ticketJson.optString("token").takeIf { it.isNotBlank() }
                val exp = ticketJson.optLong("expires", 0L)
                f4kTicketExpiry = if (exp > 0L) exp else (now + 120_000L)
            } catch (_: Throwable) {}
        }
        val watchJson = getJson("$FILM4K_BASE/api/watch/${episode.slug}")
        val sourcesArray: JSONArray? = if (episode.seasonNum > 0 && episode.episodeNum > 0) {
            val eps = watchJson.optJSONArray("episodes")
            var matched: JSONArray? = null
            if (eps != null) {
                for (i in 0 until eps.length()) {
                    val row = eps.optJSONObject(i) ?: continue
                    if (row.optInt("season") == episode.seasonNum && row.optInt("episode") == episode.episodeNum) {
                        matched = row.optJSONArray("sources")
                        break
                    }
                }
            }
            matched
        } else {
            watchJson.optJSONArray("sources")
        }
        if (sourcesArray == null || sourcesArray.length() == 0) {
            throw IllegalStateException("Không tìm thấy luồng phát cho tập này.")
        }
        val nativeCandidates = ArrayList<JSONObject>()
        val allCandidates = ArrayList<JSONObject>()
        for (i in 0 until sourcesArray.length()) {
            val obj = sourcesArray.optJSONObject(i) ?: continue
            allCandidates.add(obj)
            val u = obj.optString("url")
            val clean = u.substringBefore('?').lowercase()
            if (obj.opt("drm") == null && (clean.endsWith(".m3u8") || clean.endsWith(".mp4") || clean.endsWith(".mov"))) {
                nativeCandidates.add(obj)
            }
        }
        val pool = if (nativeCandidates.isNotEmpty()) nativeCandidates else allCandidates
        val chosen = pool[episode.serverIndex.coerceIn(0, pool.size - 1)]
        val streamUrl = resolveUrl(chosen.optString("url"), FILM4K_BASE)
            ?: throw IllegalStateException("Địa chỉ luồng phát không hợp lệ.")
        val headers = HashMap<String, String>()
        headers["Referer"] = episode.url
        headers["Origin"] = FILM4K_BASE
        headers["User-Agent"] = USER_AGENT
        f4kTicket?.let { headers["X-F4K-PT"] = it }
        return ResolvedPlayback(
            url = streamUrl,
            headers = headers,
            isF4kWrapped = streamUrl.contains("film4k", ignoreCase = true)
        )
    }

    // =========================================================================
    // NGUỒN PHIM 2 (Phim4K + VIP Nhanh + NguonC + Core API)
    // =========================================================================

    private fun refreshPhim4kDirectoryIfNeeded() {
        if (directoryLoaded) return
        directoryLoaded = true
        try {
            val raw = getBytes("https://rip.cryboiz.workers.dev/api/add", timeoutMs = 3000)
            val env = JSONObject(String(raw, Charsets.UTF_8))
            if (env.optString("alg") == "AES-GCM-256") {
                val iv = decodeBase64Url(env.optString("iv"))
                val sealed = decodeBase64Url(env.optString("ciphertext"))
                if (iv.isNotEmpty() && sealed.size > 16) {
                    val keyBytes = byteArrayOf(
                        206.toByte(), 51, 195.toByte(), 123, 77, 239.toByte(), 26, 201.toByte(),
                        30, 27, 136.toByte(), 218.toByte(), 57, 173.toByte(), 133.toByte(), 211.toByte(),
                        137.toByte(), 62, 112, 164.toByte(), 243.toByte(), 123, 157.toByte(), 247.toByte(),
                        17, 203.toByte(), 108, 161.toByte(), 208.toByte(), 77, 197.toByte(), 2
                    )
                    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, iv))
                    val plain = cipher.doFinal(sealed)
                    val data = JSONObject(String(plain, Charsets.UTF_8)).optJSONObject("data")
                    if (data != null) {
                        data.optString("free1").takeIf { it.isNotBlank() }?.let {
                            phim4kFree1Mirror = if (it.startsWith("http")) it.trimEnd('/') else "https://${it.trimEnd('/')}"
                        }
                        data.optString("free2").takeIf { it.isNotBlank() }?.let {
                            phim4kFree2 = if (it.startsWith("http")) it.trimEnd('/') else "https://${it.trimEnd('/')}"
                        }
                        data.optString("api").takeIf { it.isNotBlank() }?.let {
                            phim4kCoreUrl = if (it.startsWith("http")) it.trimEnd('/') else "https://${it.trimEnd('/')}"
                        }
                        data.optString("tokens").takeIf { it.isNotBlank() }?.let {
                            phim4kCoreKey = it.trim()
                        }
                    }
                }
            }
        } catch (_: Throwable) {}
    }

    private suspend fun loadPhim4kHome(): List<AnimeSection> = coroutineScope {
        refreshPhim4kDirectoryIfNeeded()
        val sectionsConfig = listOf(
            "Mới cập nhật" to "danh-sach/phim-moi-cap-nhat-v3",
            "Drama xứ Kim Chi" to "v1/api/quoc-gia/han-quoc",
            "Thiên đường Drama Tàu" to "v1/api/quoc-gia/trung-quoc",
            "Phim Việt" to "v1/api/quoc-gia/viet-nam",
            "Phim hoạt hình" to "v1/api/danh-sach/hoat-hinh",
            "Phim lẻ" to "v1/api/danh-sach/phim-le",
            "Phim bộ mới nhất" to "v1/api/danh-sach/phim-bo"
        )
        val jobs = sectionsConfig.mapIndexed { idx, (title, path) ->
            async(Dispatchers.IO) {
                for (host in listOf(phim4kFree1, phim4kFree1Mirror)) {
                    try {
                        val json = getJson("$host/$path?page=1")
                        val items = parsePhim4kItems(json, host)
                        if (items.isNotEmpty()) {
                            return@async idx to AnimeSection(title, items, path)
                        }
                    } catch (_: Throwable) {}
                }
                idx to null
            }
        }
        val resolved = jobs.awaitAll().sortedBy { it.first }.mapNotNull { it.second }
        if (resolved.isNotEmpty()) return@coroutineScope resolved

        // Fallback to Free2 (NguonC) if Free1 hosts are both unreachable
        val fallbackJson = getJson("$phim4kFree2/api/films/phim-moi-cap-nhat?page=1")
        val fallbackItems = parsePhim4kItems(fallbackJson, phim4kFree2)
        if (fallbackItems.isEmpty()) throw IllegalStateException("Chưa kết nối được máy chủ Nguồn phim 2.")
        listOf(AnimeSection("Mới cập nhật", fallbackItems, "api/films/phim-moi-cap-nhat"))
    }

    private fun parsePhim4kItems(json: JSONObject, baseHost: String): List<Anime> {
        val data = json.optJSONObject("data") ?: json
        val rows = data.optJSONArray("items") ?: data.optJSONArray("movies") ?: json.optJSONArray("items") ?: return emptyList()
        val cdnRaw = data.optString("APP_DOMAIN_CDN_IMAGE").ifBlank { json.optString("pathImage") }
        val cdnBase = if (cdnRaw.isNotBlank()) cdnRaw.trimEnd('/') + "/" else "${baseHost.trimEnd('/')}/"
        val isFree2 = baseHost.contains("nguonc", ignoreCase = true)
        val detailPrefix = if (isFree2) "$baseHost/api/film/" else "$baseHost/phim/"

        val seen = HashSet<String>()
        val out = ArrayList<Anime>(rows.length())
        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            val slug = row.optString("slug")
            val title = row.optString("name").ifBlank { row.optString("title") }
            if (slug.isBlank() || title.isBlank() || !seen.add(slug)) continue
            val originName = row.optString("origin_name").ifBlank { row.optString("original_name") }
            val poster = resolveUrl(row.optString("poster_url").ifBlank { row.optString("thumb_url") }, cdnBase)
            val banner = resolveUrl(row.optString("thumb_url").ifBlank { row.optString("poster_url") }, cdnBase)
            val epText = row.optString("episode_current").ifBlank { row.optString("current_episode") }
            val qual = row.optString("quality").ifBlank { row.optString("lang") }
            val year = row.optString("year").takeIf { it != "null" && it != "0" } ?: ""
            val lang = row.optString("lang")
            val tmdbScore = row.optJSONObject("tmdb")?.optDouble("vote_average", 0.0) ?: 0.0
            val score = if (tmdbScore > 0.0 && tmdbScore.isFinite()) String.format("%.1f", tmdbScore) else ""
            val infoParts = listOf(year, qual, lang, epText).filter { it.isNotBlank() }.distinct()
            val sub = if (originName.isNotBlank() && !originName.equals(title, ignoreCase = true)) {
                originName
            } else infoParts.joinToString(" • ")
            out.add(
                Anime(
                    title = title,
                    subtitle = sub,
                    url = detailPrefix + slug,
                    imageUrl = poster,
                    bannerUrl = banner ?: poster,
                    episodeText = epText,
                    quality = qual,
                    score = score,
                    summary = stripHtml(row.optString("description").ifBlank { row.optString("content") }),
                    info = infoParts.joinToString(" • "),
                    source = ContentSource.PHIM4K
                )
            )
        }
        return out
    }

    private suspend fun loadPhim4kDetail(anime: Anime): AnimeDetail = coroutineScope {
        refreshPhim4kDirectoryIfNeeded()
        val slug = anime.url.substringAfterLast('/')
        val candidates = listOf(
            "$phim4kFree1/phim/$slug" to "VIP Nhanh",
            "$phim4kFree1Mirror/phim/$slug" to "Phim4K",
            "$phim4kFree2/api/film/$slug" to "NguonC"
        )

        val jobs = candidates.mapIndexed { idx, (url, prefix) ->
            async(Dispatchers.IO) {
                try {
                    val json = getJson(url)
                    idx to Triple(url, prefix, json)
                } catch (_: Throwable) {
                    idx to null
                }
            }
        }

        val coreJob = async(Dispatchers.IO) {
            try {
                fetchCorePhim4kServers(slug, anime.title)
            } catch (_: Throwable) {
                emptyList()
            }
        }

        val results = jobs.awaitAll().sortedBy { it.first }.mapNotNull { it.second }
        if (results.isEmpty()) {
            throw IllegalStateException("Không tải được chi tiết phim từ Nguồn phim 2.")
        }

        var movieTitle = anime.title
        var poster = anime.imageUrl
        var banner = anime.bannerUrl ?: anime.imageUrl
        var summary = anime.summary
        var info = anime.info
        val allEpisodes = ArrayList<Episode>()

        for ((detailUrl, prefix, json) in results) {
            val movie = json.optJSONObject("movie") ?: continue
            if (movieTitle.isBlank()) movieTitle = movie.optString("name").ifBlank { anime.title }
            if (poster.isNullOrBlank()) poster = resolveUrl(movie.optString("poster_url"), detailUrl)
            if (banner.isNullOrBlank()) banner = resolveUrl(movie.optString("thumb_url"), detailUrl)
            val rawDesc = stripHtml(movie.optString("content").ifBlank { movie.optString("description") })
            if (summary.isBlank() && rawDesc.isNotBlank()) summary = rawDesc
            val year = movie.optString("year").takeIf { it != "null" && it != "0" } ?: ""
            val qual = movie.optString("quality")
            val lang = movie.optString("lang")
            if (info.isBlank()) {
                info = listOf(year, qual, lang).filter { it.isNotBlank() }.joinToString(" • ")
            }
            allEpisodes.addAll(extractLegacyDirectEpisodes(json, detailUrl, prefix))
        }

        val coreEpisodes = coreJob.await()
        if (coreEpisodes.isNotEmpty()) {
            allEpisodes.addAll(0, coreEpisodes)
        }

        AnimeDetail(
            title = movieTitle,
            posterUrl = poster,
            bannerUrl = banner ?: poster,
            summary = summary.ifBlank { "Chưa có nội dung giới thiệu." },
            info = info,
            episodes = allEpisodes
        )
    }

    private fun extractLegacyDirectEpisodes(json: JSONObject, detailUrl: String, prefix: String): List<Episode> {
        val movie = json.optJSONObject("movie") ?: JSONObject()
        val servers = json.optJSONArray("episodes") ?: movie.optJSONArray("episodes") ?: return emptyList()
        val movieQuality = movie.optString("quality")
        val out = ArrayList<Episode>()
        for (sIdx in 0 until servers.length()) {
            val server = servers.optJSONObject(sIdx) ?: continue
            val rows = server.optJSONArray("server_data") ?: server.optJSONArray("items") ?: continue
            val rawName = server.optString("server_name").ifBlank { "Server ${sIdx + 1}" }
            val groupName = "$prefix · $rawName"
            val badge = detectQualityBadge("$rawName $movieQuality")
            for (eIdx in 0 until rows.length()) {
                val row = rows.optJSONObject(eIdx) ?: continue
                val m3u8 = row.optString("link_m3u8").ifBlank { row.optString("m3u8") }.ifBlank { row.optString("link") }
                val cleanUrl = m3u8.trim()
                if (cleanUrl.startsWith("http", ignoreCase = true)) {
                    val epTitle = row.optString("name").ifBlank { "Tập ${eIdx + 1}" }
                    out.add(
                        Episode(
                            title = epTitle,
                            url = detailUrl,
                            number = eIdx + 1,
                            season = groupName,
                            qualityBadge = badge,
                            source = ContentSource.PHIM4K,
                            playbackKind = "direct",
                            directUrl = cleanUrl,
                            refererUrl = detailUrl
                        )
                    )
                }
            }
        }
        return out
    }

    private fun fetchCorePhim4kServers(slug: String, title: String): List<Episode> {
        val enc = URLEncoder.encode(title, "UTF-8")
        val searchUrl = "$phim4kCoreUrl/rest-api/v130/search?q=$enc&page=1&type=movietvserieslive"
        val searchJson = getJson(searchUrl, extraHeaders = mapOf("API-KEY" to phim4kCoreKey), timeoutMs = 5000)
        val candidates = ArrayList<Pair<JSONObject, String>>()
        val movies = searchJson.optJSONArray("movie")
        if (movies != null) {
            for (i in 0 until movies.length()) {
                movies.optJSONObject(i)?.let { candidates.add(it to "movie") }
            }
        }
        val series = searchJson.optJSONArray("tvseries")
        if (series != null) {
            for (i in 0 until series.length()) {
                series.optJSONObject(i)?.let { candidates.add(it to "tvseries") }
            }
        }
        val matched = candidates.firstOrNull { (obj, _) ->
            obj.optString("slug").equals(slug, ignoreCase = true) ||
                obj.optString("title").contains(title, ignoreCase = true)
        } ?: candidates.firstOrNull() ?: return emptyList()

        val vid = matched.first.optString("videos_id")
        if (vid.isBlank()) return emptyList()
        val detailUrl = "$phim4kCoreUrl/rest-api/v130/single_details?type=${matched.second}&id=$vid"
        val detail = getJson(detailUrl, extraHeaders = mapOf("API-KEY" to phim4kCoreKey), timeoutMs = 5000)
        val out = ArrayList<Episode>()
        val videos = detail.optJSONArray("videos")
        if (videos != null) {
            for (i in 0 until videos.length()) {
                val v = videos.optJSONObject(i) ?: continue
                val fileUrl = v.optString("file_url").trim()
                if (!fileUrl.startsWith("http", ignoreCase = true)) continue
                val label = v.optString("label").ifBlank { "Server VIP ${i + 1}" }
                val badge = detectQualityBadge("$label $fileUrl")
                out.add(
                    Episode(
                        title = "Xem phim",
                        url = detailUrl,
                        number = 1,
                        season = "Phim4K VIP · $label",
                        qualityBadge = badge,
                        source = ContentSource.PHIM4K,
                        playbackKind = "direct",
                        directUrl = fileUrl,
                        refererUrl = phim4kCoreUrl
                    )
                )
            }
        }
        return out
    }

    private fun resolvePhim4kPlayback(episode: Episode): ResolvedPlayback {
        if (episode.directUrl.isNotBlank()) {
            return ResolvedPlayback(
                url = episode.directUrl,
                headers = mapOf(
                    "Referer" to episode.refererUrl.ifBlank { "$phim4kFree1/" },
                    "User-Agent" to USER_AGENT
                )
            )
        }
        val slug = episode.url.substringAfterLast('/')
        for (host in listOf(episode.url, "$phim4kFree1/phim/$slug", "$phim4kFree1Mirror/phim/$slug")) {
            try {
                val json = getJson(host)
                val movie = json.optJSONObject("movie") ?: JSONObject()
                val groups = json.optJSONArray("episodes") ?: movie.optJSONArray("episodes") ?: continue
                val group = groups.optJSONObject(episode.serverIndex) ?: groups.optJSONObject(0) ?: continue
                val rows = group.optJSONArray("server_data") ?: group.optJSONArray("items") ?: continue
                val row = rows.optJSONObject(episode.episodeIndex) ?: rows.optJSONObject(0) ?: continue
                val streamUrl = row.optString("link_m3u8").ifBlank { row.optString("m3u8") }.ifBlank { row.optString("link") }
                if (streamUrl.startsWith("http", ignoreCase = true)) {
                    return ResolvedPlayback(
                        url = streamUrl,
                        headers = mapOf("Referer" to host, "User-Agent" to USER_AGENT)
                    )
                }
            } catch (_: Throwable) {}
        }
        throw IllegalStateException("Không lấy được luồng phát từ máy chủ này.")
    }

    // =========================================================================
    // ANIME (AnimeVietsub - Pure Kotlin AES-CBC + Zlib Decryptor)
    // =========================================================================

    private fun ensureAnimeDomain() {
        try {
            val conn = (URL("https://bit.ly/animevietsubtv").openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 5000
                readTimeout = 5000
                setRequestProperty("User-Agent", USER_AGENT)
            }
            conn.connect()
            val finalUrl = conn.url
            if (finalUrl != null && finalUrl.host.contains("animevietsub", ignoreCase = true)) {
                animeBaseUrl = "${finalUrl.protocol}://${finalUrl.host}"
            }
            conn.disconnect()
        } catch (_: Throwable) {}
    }

    private fun loadAnimeHome(): List<AnimeSection> {
        ensureAnimeDomain()
        val html = getText(animeBaseUrl, referer = animeBaseUrl)
        val items = parseAnimeItems(html, animeBaseUrl)
        if (items.isEmpty()) throw IllegalStateException("Không tải được danh sách AnimeVietsub.")
        val sections = ArrayList<AnimeSection>()
        sections.add(AnimeSection("Mới cập nhật", items, "/anime-moi/"))
        val extraPaths = listOf(
            "Hoạt hình Trung Quốc" to "/hoat-hinh-trung-quoc/",
            "Anime Bộ" to "/anime-bo/",
            "Anime Lẻ / Movie" to "/anime-le/"
        )
        for ((title, path) in extraPaths) {
            try {
                val subHtml = getText("$animeBaseUrl$path", referer = animeBaseUrl)
                val subItems = parseAnimeItems(subHtml, animeBaseUrl)
                if (subItems.isNotEmpty()) {
                    sections.add(AnimeSection(title, subItems, path))
                }
            } catch (_: Throwable) {}
        }
        return sections
    }

    private fun parseAnimeItems(html: String, baseUrl: String): List<Anime> {
        val articleRegex = Regex("""<article[^>]*class="[^"]*TPost[^"]*"[^>]*>(.*?)</article>""", RegexOption.DOT_MATCHES_ALL)
        val hrefRegex = Regex("""<a[^>]+href=["']([^"']+)["']""")
        val titleRegex = Regex("""<h2[^>]*class="[^"]*Title[^"]*"[^>]*>(.*?)</h2>""", RegexOption.DOT_MATCHES_ALL)
        val imgRegex = Regex("""<img[^>]+(?:data-src|data-original|src)=["']([^"']+)["']""")
        val epRegex = Regex("""<span[^>]*class="[^"]*mli-eps[^"]*"[^>]*>(.*?)</span>""", RegexOption.DOT_MATCHES_ALL)
        val qualRegex = Regex("""<span[^>]*class="[^"]*mli-quality[^"]*"[^>]*>(.*?)</span>""", RegexOption.DOT_MATCHES_ALL)
        val scoreRegex = Regex("""<div[^>]*class="[^"]*anime-avg-user-rating[^"]*"[^>]*>(.*?)</div>""", RegexOption.DOT_MATCHES_ALL)

        val seen = HashSet<String>()
        val out = ArrayList<Anime>()
        for (match in articleRegex.findAll(html)) {
            val block = match.groupValues[1]
            val rawHref = hrefRegex.find(block)?.groupValues?.get(1) ?: continue
            val fullUrl = resolveUrl(rawHref, baseUrl) ?: continue
            if (!seen.add(fullUrl)) continue
            val title = stripHtml(titleRegex.find(block)?.groupValues?.get(1) ?: "")
            if (title.isBlank()) continue
            val img = imgRegex.find(block)?.groupValues?.get(1)?.let { resolveUrl(it, baseUrl) }
            val ep = stripHtml(epRegex.find(block)?.groupValues?.get(1) ?: "")
            val qual = stripHtml(qualRegex.find(block)?.groupValues?.get(1) ?: "")
            val score = stripHtml(scoreRegex.find(block)?.groupValues?.get(1) ?: "")
            out.add(
                Anime(
                    title = title,
                    subtitle = listOf(ep, qual).filter { it.isNotBlank() }.joinToString(" • "),
                    url = fullUrl,
                    imageUrl = img,
                    bannerUrl = img,
                    episodeText = ep,
                    quality = qual,
                    score = score,
                    info = listOf(ep, qual).filter { it.isNotBlank() }.joinToString(" • "),
                    source = ContentSource.ANIME
                )
            )
        }
        return out
    }

    private fun loadAnimeDetail(anime: Anime): AnimeDetail {
        val html = getText(anime.url, referer = animeBaseUrl)
        val watchHref = Regex("""<a[^>]+href=["']([^"']*xem-phim[^"']*)["']""").find(html)?.groupValues?.get(1)
        val watchUrl = watchHref?.let { resolveUrl(it, anime.url) } ?: "${anime.url.trimEnd('/')}/xem-phim.html"
        val watchHtml = try {
            getText(watchUrl, referer = anime.url)
        } catch (_: Throwable) {
            html
        }
        val filmId = Regex("""filmInfo\.filmID\s*=\s*parseInt\(['"]?(\d+)['"]?\)""").find(watchHtml)?.groupValues?.get(1)
            ?: Regex("""data-id=["'](\d+)["']""").find(watchHtml)?.groupValues?.get(1)
            ?: ""

        val epRegex = Regex(
            """<a[^>]+class="[^"]*episode-link[^"]*"[^>]*data-play=["']([^"']+)["'][^>]*data-hash=["']([^"']+)["'][^>]*>(.*?)</a>""",
            RegexOption.DOT_MATCHES_ALL
        )
        val episodes = ArrayList<Episode>()
        var idx = 1
        for (m in epRegex.findAll(watchHtml)) {
            val hash = m.groupValues[2].trim()
            val epTitle = stripHtml(m.groupValues[3]).ifBlank { "Tập $idx" }
            if (hash.isNotBlank()) {
                episodes.add(
                    Episode(
                        title = epTitle,
                        url = watchUrl,
                        number = idx,
                        season = "AnimeVietsub",
                        qualityBadge = "FHD",
                        source = ContentSource.ANIME,
                        playbackKind = "anime",
                        animeHash = hash,
                        animeFilmId = filmId
                    )
                )
                idx++
            }
        }
        if (episodes.isEmpty()) {
            val defaultHash = Regex("""data-hash=["']([^"']+)["']""").find(watchHtml)?.groupValues?.get(1) ?: ""
            if (defaultHash.isNotBlank()) {
                episodes.add(
                    Episode(
                        title = "Tập 1",
                        url = watchUrl,
                        number = 1,
                        season = "AnimeVietsub",
                        qualityBadge = "FHD",
                        source = ContentSource.ANIME,
                        playbackKind = "anime",
                        animeHash = defaultHash,
                        animeFilmId = filmId
                    )
                )
            }
        }
        val desc = stripHtml(
            Regex("""<div[^>]*class="[^"]*Description[^"]*"[^>]*>(.*?)</div>""", RegexOption.DOT_MATCHES_ALL)
                .find(html)?.groupValues?.get(1) ?: ""
        ).ifBlank { anime.summary.ifBlank { "Chưa có nội dung giới thiệu." } }

        return AnimeDetail(
            title = anime.title,
            posterUrl = anime.imageUrl,
            bannerUrl = anime.bannerUrl ?: anime.imageUrl,
            summary = desc,
            info = anime.info,
            episodes = episodes
        )
    }

    private fun resolveAnimePlayback(episode: Episode): ResolvedPlayback {
        val base = URL(episode.url)
        val origin = "${base.protocol}://${base.host}"
        val body = "link=${URLEncoder.encode(episode.animeHash, "UTF-8")}&id=${URLEncoder.encode(episode.animeFilmId, "UTF-8")}"
        val conn = (URL("$origin/ajax/player").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 10000
            readTimeout = 12000
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Referer", episode.url)
            setRequestProperty("Origin", origin)
            setRequestProperty("X-Requested-With", "XMLHttpRequest")
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
        }
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val responseText = conn.inputStream.use { String(it.readBytes(), Charsets.UTF_8) }
        conn.disconnect()

        var encrypted = responseText.trim()
        try {
            val j = JSONObject(encrypted)
            encrypted = j.optString("link").ifBlank { j.optString("data").ifBlank { encrypted } }
        } catch (_: Throwable) {}

        val raw = decodeBase64Url(encrypted)
        if (raw.size <= 16) throw IllegalStateException("Dữ liệu luồng Anime không hợp lệ.")
        val iv = raw.copyOfRange(0, 16)
        val cipherBytes = raw.copyOfRange(16, raw.size)
        val keyDigest = MessageDigest.getInstance("SHA-256")
            .digest("dm_thang_suc_vat_get_link_an_dbt".toByteArray(Charsets.UTF_8))
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyDigest, "AES"), IvParameterSpec(iv))
        val decrypted = cipher.doFinal(cipherBytes)

        val clearText = try {
            val inflater = Inflater(true)
            inflater.setInput(decrypted)
            val bos = ByteArrayOutputStream()
            val buf = ByteArray(4096)
            while (!inflater.finished() && !inflater.needsInput()) {
                val count = inflater.inflate(buf)
                if (count <= 0) break
                bos.write(buf, 0, count)
            }
            inflater.end()
            String(bos.toByteArray(), Charsets.UTF_8)
        } catch (_: Throwable) {
            String(decrypted, Charsets.UTF_8)
        }

        val normalized = clearText.replace("\\/", "/")
        val m3u8Match = Regex("""https?://[^\s"'<>]+\.(?:m3u8|mp4)[^\s"'<>]*""", RegexOption.IGNORE_CASE).find(normalized)
        val streamUrl = m3u8Match?.value
            ?: throw IllegalStateException("Không tìm thấy luồng HLS cho tập Anime này.")

        return ResolvedPlayback(
            url = streamUrl,
            headers = mapOf(
                "Referer" to "$origin/",
                "Origin" to origin,
                "User-Agent" to USER_AGENT
            )
        )
    }

    // =========================================================================
    // HELPERS
    // =========================================================================

    private fun detectQualityBadge(text: String): String {
        val up = text.uppercase()
        return when {
            up.contains("4K") || up.contains("2160") -> "4K"
            up.contains("FHD") || up.contains("1080") || up.contains("FULL") -> "FHD"
            up.contains("HD") || up.contains("720") -> "HD"
            else -> "FHD"
        }
    }

    private fun localizedText(value: Any?): String = when (value) {
        is String -> value
        is Number -> value.toString()
        is JSONObject -> value.optString("vi").ifBlank { value.optString("en") }
        else -> ""
    }

    private fun resolveUrl(raw: String?, base: String): String? {
        val clean = raw?.trim().orEmpty()
        if (clean.isEmpty() || clean == "null") return null
        return try {
            val u = URL(URL(base), clean)
            if (u.protocol == "http" || u.protocol == "https") u.toString() else null
        } catch (_: Throwable) {
            null
        }
    }

    private fun stripHtml(raw: String): String =
        raw.replace(Regex("<[^>]+>"), " ")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#039;", "'")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun decodeBase64Url(input: String): ByteArray {
        val clean = input.trim().replace('-', '+').replace('_', '/')
        val padded = clean + "=".repeat((4 - clean.length % 4) % 4)
        return Base64.decode(padded, Base64.DEFAULT)
    }

    private fun getBytes(
        url: String,
        referer: String? = null,
        extraHeaders: Map<String, String> = emptyMap(),
        timeoutMs: Int = 10000
    ): ByteArray {
        var lastError: Throwable? = null
        for (attempt in 1..2) {
            var conn: HttpURLConnection? = null
            try {
                val u = URL(url)
                conn = (u.openConnection() as HttpURLConnection).apply {
                    connectTimeout = timeoutMs
                    readTimeout = timeoutMs + 3000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", USER_AGENT)
                    setRequestProperty("Accept", "application/json,text/html,*/*")
                    val origin = "${u.protocol}://${u.host}"
                    setRequestProperty("Origin", origin)
                    setRequestProperty("Referer", referer ?: "$origin/")
                    for ((k, v) in extraHeaders) {
                        setRequestProperty(k, v)
                    }
                }
                val code = conn.responseCode
                if (code !in 200..299) throw IllegalStateException("HTTP $code")
                return conn.inputStream.use { it.readBytes() }
            } catch (t: Throwable) {
                lastError = t
            } finally {
                conn?.disconnect()
            }
        }
        throw lastError ?: IllegalStateException("Lỗi kết nối máy chủ.")
    }

    private fun getText(url: String, referer: String? = null): String =
        String(getBytes(url, referer = referer), Charsets.UTF_8)

    private fun getJson(
        url: String,
        extraHeaders: Map<String, String> = emptyMap(),
        timeoutMs: Int = 10000
    ): JSONObject = JSONObject(String(getBytes(url, extraHeaders = extraHeaders, timeoutMs = timeoutMs), Charsets.UTF_8))

    private fun postJson(url: String): JSONObject {
        var conn: HttpURLConnection? = null
        try {
            val u = URL(url)
            conn = (u.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 8000
                readTimeout = 10000
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Origin", "${u.protocol}://${u.host}")
                setRequestProperty("Referer", "${u.protocol}://${u.host}/")
            }
            return JSONObject(String(conn.inputStream.use { it.readBytes() }, Charsets.UTF_8))
        } finally {
            conn?.disconnect()
        }
    }
}
