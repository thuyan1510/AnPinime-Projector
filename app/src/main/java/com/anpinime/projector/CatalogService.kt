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
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.Inflater
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object VideoTokenGenerator {
    private const val HMAC_SECRET_2 = "5e8d1b4f9c2a6e730b1f8d4a92c5e3d1"
    private const val CDN_GATEWAY = "https://svatv.cryboiz.workers.dev"

    private fun sha256(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(data)

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    private fun deriveDailySecretV2(secret: String, dateUtc: String): String {
        val keyBytes = sha256(secret.toByteArray(Charsets.UTF_8))
        val ivSource = sha256("iv:$secret".toByteArray(Charsets.UTF_8))
        val iv = ivSource.copyOfRange(0, 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(128, iv)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"), spec)
        val plaintext = "$secret:$dateUtc".toByteArray(Charsets.UTF_8)
        val ciphertext = cipher.doFinal(plaintext)
        return "$secret:${ciphertext.joinToString("") { "%02x".format(it) }}"
    }

    private fun otpMask(signingSecret: String): ByteArray {
        val sigBytes = signingSecret.toByteArray(Charsets.UTF_8)
        val salt = "otp-ts-mask".toByteArray(Charsets.UTF_8)
        return hmacSha256(sigBytes, salt).copyOfRange(0, 4)
    }

    private fun xorIntToHex8(unixSec: Int, mask: ByteArray): String {
        val raw = ByteBuffer.allocate(4).putInt(unixSec).array()
        val xored = ByteArray(4) { i -> (raw[i].toInt() xor mask[i].toInt()).toByte() }
        return xored.joinToString("") { "%02x".format(it) }
    }

    fun genSignedCdnUrl(filenameOrId: String): String {
        val cleanId = filenameOrId.substringBefore('?').trim().trimEnd('/')
            .substringAfterLast('/').substringBeforeLast('.')
        val sdf = SimpleDateFormat("yyyyMMdd", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val dateUtc = sdf.format(Date())
        val signingSecret = deriveDailySecretV2(HMAC_SECRET_2, dateUtc)
        val nowSec = (System.currentTimeMillis() / 1000L).toInt()
        val mask = otpMask(signingSecret)
        val tsHex = xorIntToHex8(nowSec, mask)
        val msg = "$cleanId:$nowSec"
        val token = hmacSha256(signingSecret.toByteArray(Charsets.UTF_8), msg.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return "$CDN_GATEWAY/$cleanId?token=$token&ts=$tsHex"
    }

    fun resolveSecureStreamUrl(rawUrlOrId: String): String {
        val trimmed = rawUrlOrId.trim()
        if (trimmed.contains("sv.p4kzz.workers.dev", ignoreCase = true)) {
            return trimmed
        }
        val signedUrl = genSignedCdnUrl(trimmed)
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(signedUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10000
                readTimeout = 12000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "okhttp/4.9.0")
                setRequestProperty("Accept", "application/json")
            }
            val stream = if (conn.responseCode in 200..399) conn.inputStream else conn.errorStream
            val body = stream?.use { String(it.readBytes(), Charsets.UTF_8) }.orEmpty()
            val json = JSONObject(body)
            val fastUrl = json.optString("url")
            if (fastUrl.isNotBlank()) {
                return fastUrl
            }
        } catch (_: Throwable) {
        } finally {
            conn?.disconnect()
        }
        return signedUrl
    }
}

object CatalogService {
    val USER_AGENT: String
        get() = WebViewHelper.userAgent

    private const val FILM4K_BASE = "https://film4k.net"
    var animeBaseUrl = "https://animevietsub.sale"
        private set
    private var animeDomainResolvedAt = 0L

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
        AnimeCategory("Kinh dị", "/the-loai/kinh-di/"),
        AnimeCategory("Học đường", "/the-loai/truong-hoc/"),
        AnimeCategory("Shounen", "/the-loai/shounen/"),
        AnimeCategory("Đời thường", "/the-loai/doi-thuong/"),
        AnimeCategory("Siêu nhiên", "/the-loai/sieu-nhien/")
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
                for (host in listOf(phim4kFree1Mirror, phim4kFree1)) {
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
                val html = fetchAnimeHtml(pageUrl, referer = animeBaseUrl)
                parseAnimeItems(html, animeBaseUrl)
            }
        }
    }

    suspend fun search(source: ContentSource, query: String): List<Anime> = withContext(Dispatchers.IO) {
        val cleanQuery = query.trim()
        if (cleanQuery.isEmpty()) return@withContext emptyList()
        val enc = URLEncoder.encode(cleanQuery, "UTF-8")
        when (source) {
            ContentSource.FILM4K -> {
                val json = getJson("$FILM4K_BASE/api/search/suggest?q=$enc")
                parseFilm4kItems(json.optJSONArray("list"))
            }
            ContentSource.PHIM4K -> {
                for (host in listOf(phim4kFree1Mirror, phim4kFree1)) {
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
                val encodedPath = enc.replace("+", "%20")
                val html = fetchAnimeHtml("$animeBaseUrl/tim-kiem/$encodedPath/", referer = animeBaseUrl)
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
            "phim4k_vip" -> {
                val direct = episode.directUrl.ifBlank { episode.url }
                val resolvedStream = VideoTokenGenerator.resolveSecureStreamUrl(direct)
                ResolvedPlayback(
                    url = resolvedStream,
                    headers = mapOf(
                        "User-Agent" to "exoplayer"
                    ),
                    isWeb = false,
                    webUrl = episode.url,
                    isF4kWrapped = false
                )
            }
            "direct" -> {
                val originHost = try {
                    val u = URL(episode.refererUrl.ifBlank { phim4kFree1Mirror })
                    "${u.protocol}://${u.host}"
                } catch (_: Throwable) {
                    phim4kFree1Mirror
                }
                val isWrapped = episode.directUrl.contains("/stream/hls", ignoreCase = true) ||
                    episode.directUrl.contains("film4k", ignoreCase = true)
                ResolvedPlayback(
                    url = episode.directUrl,
                    headers = mapOf(
                        "Referer" to "$originHost/",
                        "Origin" to originHost,
                        "User-Agent" to USER_AGENT
                    ),
                    isWeb = false,
                    webUrl = episode.url,
                    isF4kWrapped = isWrapped
                )
            }
            "web" -> {
                val originHost = try {
                    val u = URL(episode.refererUrl.ifBlank { episode.url })
                    "${u.protocol}://${u.host}"
                } catch (_: Throwable) {
                    phim4kFree2
                }
                ResolvedPlayback(
                    url = episode.directUrl.ifBlank { episode.url },
                    headers = mapOf(
                        "Referer" to "$originHost/",
                        "Origin" to originHost,
                        "User-Agent" to USER_AGENT
                    ),
                    isWeb = true,
                    webUrl = episode.directUrl.ifBlank { episode.url }
                )
            }
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
            val raw = getBytes("https://rip.cryboiz.workers.dev/api/add", timeoutMs = 3500)
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
        // Prioritize free.cryboiz.workers.dev first so Vietnamese ISPs never stall on phimapi.com
        val jobs = sectionsConfig.mapIndexed { idx, (title, path) ->
            async(Dispatchers.IO) {
                for (host in listOf(phim4kFree1Mirror, phim4kFree1)) {
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
            "$phim4kFree2/api/film/$slug" to "NguonC"
        )

        val jobs = candidates.mapIndexed { idx, (url, prefix) ->
            async(Dispatchers.IO) {
                try {
                    val json = getJson(url, timeoutMs = 8000)
                    idx to Triple(url, prefix, json)
                } catch (_: Throwable) {
                    idx to null
                }
            }
        }

        val results = jobs.awaitAll().sortedBy { it.first }.mapNotNull { it.second }

        var movieTitle = anime.title
        var poster = anime.imageUrl
        var banner = anime.bannerUrl ?: anime.imageUrl
        var summary = anime.summary
        var info = anime.info
        val legacyEpisodes = ArrayList<Episode>()

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
            legacyEpisodes.addAll(extractLegacyDirectEpisodes(json, detailUrl, prefix))
        }

        val allEpisodes = ArrayList<Episode>()

        // 1. Core Phim4K VIP servers (direct 1080p/4K CDN streams via VideoTokenGenerator)
        try {
            val coreEpisodes = fetchCorePhim4kServers(
                slug = slug,
                title = movieTitle,
                detailUrl = anime.url,
                fallbackEpisodes = emptyList()
            )
            if (coreEpisodes.isNotEmpty()) {
                allEpisodes.addAll(coreEpisodes)
            }
        } catch (_: Throwable) {}

        // 2. VIP Nhanh (phimapi.com) and NguonC
        allEpisodes.addAll(legacyEpisodes)

        if (allEpisodes.isEmpty() && results.isEmpty()) {
            throw IllegalStateException("Không tải được chi tiết phim từ Nguồn phim 2.")
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
        val movieLang = movie.optString("lang")
        val out = ArrayList<Episode>()
        val directFields = listOf("link_m3u8", "m3u8", "stream_url", "video_url", "file_url", "link")
        val embedFields = listOf("link_embed", "embed")

        for (sIdx in 0 until servers.length()) {
            val server = servers.optJSONObject(sIdx) ?: continue
            val rows = server.optJSONArray("server_data") ?: server.optJSONArray("items") ?: continue
            val rawName = server.optString("server_name").ifBlank { "Máy chủ ${sIdx + 1}" }
            val groupName = "$prefix · $rawName"
            val badge = detectQualityBadge("$rawName $movieQuality $movieLang")
            for (eIdx in 0 until rows.length()) {
                val row = rows.optJSONObject(eIdx) ?: continue
                val epTitle = row.optString("name").ifBlank { "Tập ${eIdx + 1}" }

                var chosenDirect = ""
                for (f in directFields) {
                    val v = row.optString(f).trim()
                    if (v.startsWith("http", ignoreCase = true)) {
                        chosenDirect = v
                        break
                    }
                }

                // If direct field wasn't present, check if link_embed has ?url=https://...m3u8
                var chosenEmbed = ""
                for (f in embedFields) {
                    val v = row.optString(f).trim()
                    if (v.startsWith("http", ignoreCase = true)) {
                        chosenEmbed = v
                        if (chosenDirect.isBlank() && v.contains("url=http", ignoreCase = true)) {
                            val inner = v.substringAfter("url=").substringBefore("&").trim()
                            if (inner.startsWith("http", ignoreCase = true) &&
                                (inner.contains(".m3u8", ignoreCase = true) || inner.contains(".mp4", ignoreCase = true))
                            ) {
                                chosenDirect = inner
                            }
                        }
                        break
                    }
                }

                if (chosenDirect.isNotBlank()) {
                    if (chosenDirect.contains("sf.phim4k.space", ignoreCase = true)) {
                        continue
                    }
                    val fallbackWeb = when {
                        chosenEmbed.isNotBlank() -> chosenEmbed
                        chosenDirect.startsWith("http", ignoreCase = true) &&
                            (chosenDirect.contains(".m3u8", ignoreCase = true) || chosenDirect.contains("/stream/hls", ignoreCase = true)) ->
                            try { "https://player.phimapi.com/player/?url=" + URLEncoder.encode(chosenDirect, "UTF-8") } catch (_: Throwable) { detailUrl }
                        else -> detailUrl
                    }
                    out.add(
                        Episode(
                            title = epTitle,
                            url = fallbackWeb,
                            number = eIdx + 1,
                            season = groupName,
                            qualityBadge = badge,
                            source = ContentSource.PHIM4K,
                            playbackKind = "direct",
                            directUrl = chosenDirect,
                            refererUrl = detailUrl
                        )
                    )
                } else if (chosenEmbed.isNotBlank()) {
                    out.add(
                        Episode(
                            title = epTitle,
                            url = detailUrl,
                            number = eIdx + 1,
                            season = groupName,
                            qualityBadge = badge,
                            source = ContentSource.PHIM4K,
                            playbackKind = "web",
                            directUrl = chosenEmbed,
                            refererUrl = detailUrl
                        )
                    )
                }
            }
        }
        return out
    }

    private fun fetchCorePhim4kServers(
        slug: String,
        title: String,
        detailUrl: String,
        fallbackEpisodes: List<Episode> = emptyList()
    ): List<Episode> {
        val searchQueries = mutableListOf<String>()
        if (title.isNotBlank()) {
            searchQueries.add(title.trim())
            if (title.contains("(")) {
                val before = title.substringBefore('(').trim()
                if (before.isNotBlank() && !searchQueries.contains(before)) searchQueries.add(before)
            }
            if (title.contains(")")) {
                val after = title.substringAfterLast(')').trim()
                if (after.isNotBlank() && !searchQueries.contains(after)) searchQueries.add(after)
            }
        }
        val slugClean = slug.replace('-', ' ').trim()
        if (slugClean.isNotBlank() && !searchQueries.contains(slugClean)) {
            searchQueries.add(slugClean)
        }

        val candidates = ArrayList<Pair<JSONObject, String>>()
        for (q in searchQueries) {
            try {
                val enc = URLEncoder.encode(q, "UTF-8")
                val searchUrl = "$phim4kCoreUrl/rest-api/v130/search?q=$enc&page=1&type=movietvserieslive"
                val searchJson = getJson(searchUrl, extraHeaders = mapOf("API-KEY" to phim4kCoreKey), timeoutMs = 4500)
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
                if (candidates.isNotEmpty()) break
            } catch (_: Throwable) {}
        }

        if (candidates.isEmpty()) return emptyList()

        val matched = candidates.firstOrNull { (obj, _) ->
            val objSlug = obj.optString("slug")
            val objTitle = obj.optString("title")
            objSlug.equals(slug, ignoreCase = true) ||
                objTitle.contains(title, ignoreCase = true) ||
                (title.isNotBlank() && title.contains(objTitle, ignoreCase = true))
        } ?: candidates.first()

        val vid = matched.first.optString("videos_id")
        if (vid.isBlank()) return emptyList()
        val singleUrl = "$phim4kCoreUrl/rest-api/v130/single_details?type=${matched.second}&id=$vid"
        val detail = getJson(singleUrl, extraHeaders = mapOf("API-KEY" to phim4kCoreKey), timeoutMs = 5000)
        val out = ArrayList<Episode>()

        val videos = detail.optJSONArray("videos")
        if (videos != null) {
            for (i in 0 until videos.length()) {
                val v = videos.optJSONObject(i) ?: continue
                val fileUrl = v.optString("file_url").trim()
                if (fileUrl.isBlank()) continue
                val label = v.optString("label").ifBlank { "Server VIP #${i + 1}" }
                val badge = detectQualityBadge("$label $fileUrl")
                val isInternalCdn = fileUrl.contains("phim4k.lol", ignoreCase = true) ||
                    !fileUrl.startsWith("http", ignoreCase = true) ||
                    (!fileUrl.contains(".m3u8", ignoreCase = true) && !fileUrl.contains("/stream/hls", ignoreCase = true))

                val epTitle = if (videos.length() > 1) "Server ${i + 1}" else "Xem phim"
                out.add(
                    Episode(
                        title = epTitle,
                        url = detailUrl,
                        number = i + 1,
                        season = "Phim4K VIP · $label",
                        qualityBadge = badge,
                        source = ContentSource.PHIM4K,
                        playbackKind = if (isInternalCdn) "phim4k_vip" else "direct",
                        directUrl = fileUrl,
                        refererUrl = phim4kCoreUrl
                    )
                )
            }
        }

        val seasons = detail.optJSONArray("season")
        if (seasons != null) {
            for (sIdx in 0 until seasons.length()) {
                val sObj = seasons.optJSONObject(sIdx) ?: continue
                val seasonName = sObj.optString("seasons_name").ifBlank { "Season ${sIdx + 1}" }
                val badge = detectQualityBadge(seasonName)
                val eps = sObj.optJSONArray("episodes") ?: continue
                for (eIdx in 0 until eps.length()) {
                    val epObj = eps.optJSONObject(eIdx) ?: continue
                    val rawEpName = epObj.optString("episodes_name")
                    val epMatch = Regex("""(?i)(?:E|Tập\s*)(\d+)""").find(rawEpName)
                    val epNum = epMatch?.groupValues?.get(1)?.toIntOrNull() ?: (eIdx + 1)
                    val epTitle = "Tập $epNum"
                    val fileUrl = epObj.optString("file_url").trim()
                    if (fileUrl.isBlank()) continue
                    val isInternalCdn = fileUrl.contains("phim4k.lol", ignoreCase = true) ||
                        !fileUrl.startsWith("http", ignoreCase = true) ||
                        (!fileUrl.contains(".m3u8", ignoreCase = true) && !fileUrl.contains("/stream/hls", ignoreCase = true))

                    out.add(
                        Episode(
                            title = epTitle,
                            url = detailUrl,
                            number = epNum,
                            season = "Phim4K VIP · $seasonName",
                            qualityBadge = badge,
                            source = ContentSource.PHIM4K,
                            playbackKind = if (isInternalCdn) "phim4k_vip" else "direct",
                            directUrl = fileUrl,
                            refererUrl = phim4kCoreUrl
                        )
                    )
                }
            }
        }
        return out
    }

    private fun resolvePhim4kPlayback(episode: Episode): ResolvedPlayback {
        if (episode.directUrl.isNotBlank()) {
            val originHost = try {
                val u = URL(episode.refererUrl.ifBlank { phim4kFree1Mirror })
                "${u.protocol}://${u.host}"
            } catch (_: Throwable) {
                phim4kFree1Mirror
            }
            return ResolvedPlayback(
                url = episode.directUrl,
                headers = mapOf(
                    "Referer" to "$originHost/",
                    "Origin" to originHost,
                    "User-Agent" to USER_AGENT
                )
            )
        }
        val slug = episode.url.substringAfterLast('/')
        for (host in listOf(episode.url, "$phim4kFree1Mirror/phim/$slug", "$phim4kFree1/phim/$slug")) {
            try {
                val json = getJson(host)
                val movie = json.optJSONObject("movie") ?: JSONObject()
                val groups = json.optJSONArray("episodes") ?: movie.optJSONArray("episodes") ?: continue
                val group = groups.optJSONObject(episode.serverIndex) ?: groups.optJSONObject(0) ?: continue
                val rows = group.optJSONArray("server_data") ?: group.optJSONArray("items") ?: continue
                val row = rows.optJSONObject(episode.episodeIndex) ?: rows.optJSONObject(0) ?: continue
                val streamUrl = row.optString("link_m3u8").ifBlank { row.optString("m3u8") }.ifBlank { row.optString("link") }
                if (streamUrl.startsWith("http", ignoreCase = true)) {
                    val u = URL(host)
                    val origin = "${u.protocol}://${u.host}"
                    return ResolvedPlayback(
                        url = streamUrl,
                        headers = mapOf("Referer" to "$origin/", "Origin" to origin, "User-Agent" to USER_AGENT)
                    )
                }
            } catch (_: Throwable) {}
        }
        throw IllegalStateException("Không lấy được luồng phát từ máy chủ này.")
    }

    // =========================================================================
    // ANIME (AnimeVietsub - Domain Resolver + Cloudflare WebView + AES-CBC)
    // =========================================================================

    private fun ensureAnimeDomain(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - animeDomainResolvedAt < 300_000L) return
        val fallbacks = listOf(
            "https://animevietsub.sale",
            "https://animevietsub.vip",
            "https://animevietsub.xyz",
            "https://animevietsub.link"
        )
        try {
            var current = "https://bit.ly/animevietsubtv"
            for (hop in 0 until 6) {
                val conn = (URL(current).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    connectTimeout = 4500
                    readTimeout = 4500
                    setRequestProperty("User-Agent", USER_AGENT)
                }
                try {
                    val code = conn.responseCode
                    val loc = conn.getHeaderField("Location")
                    if (code in 300..399 && !loc.isNullOrBlank()) {
                        val nextUrl = URL(URL(current), loc)
                        if (nextUrl.host.contains("animevietsub", ignoreCase = true)) {
                            animeBaseUrl = "${nextUrl.protocol}://${nextUrl.host}"
                            animeDomainResolvedAt = now
                        }
                        current = nextUrl.toString()
                    } else {
                        val u = conn.url
                        if (u != null && u.host.contains("animevietsub", ignoreCase = true)) {
                            animeBaseUrl = "${u.protocol}://${u.host}"
                            animeDomainResolvedAt = now
                        }
                        break
                    }
                } finally {
                    conn.disconnect()
                }
            }
        } catch (_: Throwable) {
            if (animeBaseUrl.isBlank()) {
                animeBaseUrl = fallbacks.first()
            }
        }
    }

    private suspend fun fetchAnimeHtml(url: String, referer: String? = null): String {
        var targetUrl = url
        var conn: HttpURLConnection? = null
        try {
            val u = URL(targetUrl)
            conn = (u.openConnection() as HttpURLConnection).apply {
                connectTimeout = 10000
                readTimeout = 12000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                setRequestProperty("Accept-Language", "vi-VN,vi;q=0.9,en;q=0.8")
                setRequestProperty("Referer", referer ?: "$animeBaseUrl/")
                WebViewHelper.getCookieHeader(targetUrl)?.let { setRequestProperty("Cookie", it) }
            }
            val code = conn.responseCode
            val finalUrl = conn.url?.toString() ?: targetUrl
            if (conn.url != null && conn.url.host.contains("animevietsub", ignoreCase = true)) {
                animeBaseUrl = "${conn.url.protocol}://${conn.url.host}"
                animeDomainResolvedAt = System.currentTimeMillis()
            }
            WebViewHelper.saveSetCookieHeaders(finalUrl, conn.headerFields)
            val stream = if (code in 200..399) conn.inputStream else conn.errorStream
            val html = stream?.use { String(it.readBytes(), Charsets.UTF_8) }.orEmpty()
            if (code in 200..399 && html.isNotBlank() && !WebViewHelper.containsVerification(html)) {
                return html
            }
            targetUrl = finalUrl
        } catch (_: Throwable) {
        } finally {
            conn?.disconnect()
        }

        // Fallback to real Android WebView to bypass Cloudflare 403 / JS challenge
        val (browserHtml, resolvedUrl) = WebViewHelper.fetchHtml(targetUrl)
        if (resolvedUrl.isNotBlank()) {
            try {
                val u = URL(resolvedUrl)
                if (u.host.contains("animevietsub", ignoreCase = true)) {
                    animeBaseUrl = "${u.protocol}://${u.host}"
                    animeDomainResolvedAt = System.currentTimeMillis()
                }
            } catch (_: Throwable) {}
        }
        if (browserHtml.isBlank()) {
            throw IllegalStateException("Không tải được dữ liệu từ AnimeVietsub.")
        }
        return browserHtml
    }

    private suspend fun loadAnimeHome(): List<AnimeSection> {
        ensureAnimeDomain()
        val html = fetchAnimeHtml(animeBaseUrl, referer = animeBaseUrl)
        val sections = ArrayList<AnimeSection>()
        val parsedSections = parseAnimeSections(html, animeBaseUrl)
        sections.addAll(parsedSections)

        if (sections.size < 2) {
            val extraPaths = listOf(
                "Hoạt hình Trung Quốc" to "/hoat-hinh-trung-quoc/",
                "Đang chiếu" to "/danh-sach/list-dang-chieu/",
                "Trọn bộ" to "/danh-sach/list-tron-bo/"
            )
            for ((title, path) in extraPaths) {
                try {
                    val subHtml = fetchAnimeHtml("$animeBaseUrl$path", referer = animeBaseUrl)
                    val subItems = parseAnimeItems(subHtml, animeBaseUrl)
                    if (subItems.isNotEmpty() && sections.none { it.title.equals(title, ignoreCase = true) }) {
                        sections.add(AnimeSection(title, subItems, path))
                    }
                } catch (_: Throwable) {}
            }
        }
        if (sections.isEmpty()) throw IllegalStateException("Không tải được danh sách AnimeVietsub.")
        return sections
    }

    private fun parseAnimeSections(html: String, baseUrl: String): List<AnimeSection> {
        val sectionRegex = Regex("""(?is)<section\b[^>]*>(.*?)</section>""")
        val headingRegex = Regex("""(?is)<h[1-3][^>]*>(.*?)</h[1-3]>""")
        val out = ArrayList<AnimeSection>()
        val seenTitles = HashSet<String>()

        for (m in sectionRegex.findAll(html)) {
            val block = m.groupValues[1]
            val rawTitle = headingRegex.find(block)?.groupValues?.get(1)?.let { stripHtml(it) }
                ?.takeIf { it.isNotBlank() } ?: "Anime mới cập nhật"
            val items = parseAnimeItems(block, baseUrl)
            if (items.isNotEmpty()) {
                val normKey = foldVietnamese(rawTitle).let { if (it.contains("cap nhat")) "latest-updates" else it }
                if (seenTitles.add(normKey)) {
                    out.add(AnimeSection(rawTitle, items, "/anime-moi/"))
                }
            }
        }
        if (out.isEmpty()) {
            val items = parseAnimeItems(html, baseUrl)
            if (items.isNotEmpty()) {
                out.add(AnimeSection("Anime mới cập nhật", items, "/anime-moi/"))
            }
        }
        return out
    }

    private fun parseAnimeItems(html: String, baseUrl: String): List<Anime> {
        val markerRegex = Regex("""(?is)<(?:article|div|li)\b[^>]*class=["'][^"']*(?:TPost|ml-item|film-item|movie-item)[^"']*["'][^>]*>""")
        val matches = markerRegex.findAll(html).toList()
        val candidates = ArrayList<String>()
        if (matches.isNotEmpty()) {
            for (i in matches.indices) {
                val start = matches[i].range.first
                val nextStart = if (i + 1 < matches.size) matches[i + 1].range.first else html.length
                val end = minOf(nextStart, start + 14000)
                if (end > start) {
                    candidates.add(html.substring(start, end))
                }
            }
        } else {
            val linkRegex = Regex("""(?is)<a\b[^>]*href=["'][^"']+["'][^>]*>.*?</a>""")
            for (m in linkRegex.findAll(html)) {
                candidates.add(m.value)
            }
        }

        val hrefRegex = Regex("""(?is)href\s*=\s*["']([^"']+)["']""")
        val h2TitleRegex = Regex("""(?is)<h2[^>]*class=["'][^"']*Title[^"']*["'][^>]*>(.*?)</h2>""")
        val divTitleRegex = Regex("""(?is)<div[^>]*class=["'][^"']*Title[^"']*["'][^>]*>(.*?)</div>""")
        val attrTitleRegex = Regex("""(?is)(?:alt|title)\s*=\s*["']([^"']+)["']""")
        val epRegex = Regex("""(?is)<span[^>]*class=["'][^"']*mli-eps[^"']*["'][^>]*>(.*?)</span>""")
        val qualRegex = Regex("""(?is)<span[^>]*class=["'][^"']*mli-quality[^"']*["'][^>]*>(.*?)</span>""")
        val starRegex = Regex("""(?is)fa-star[^>]*></i>\s*([0-9.]+)""")
        val avgScoreRegex = Regex("""(?is)<div[^>]*class=["'][^"']*anime-avg-user-rating[^"']*["'][^>]*>(.*?)</div>""")

        val seen = HashSet<String>()
        val out = ArrayList<Anime>()
        for (block in candidates) {
            val rawHref = hrefRegex.find(block)?.groupValues?.get(1) ?: continue
            val fullUrl = resolveUrl(rawHref.replace("&amp;", "&"), baseUrl) ?: continue
            val lowerPath = try { URL(fullUrl).path.lowercase() } catch (_: Throwable) { "" }
            if (!lowerPath.contains("phim") && !lowerPath.endsWith(".html")) continue

            val rawTitle = h2TitleRegex.find(block)?.groupValues?.get(1)
                ?: divTitleRegex.find(block)?.groupValues?.get(1)
                ?: attrTitleRegex.find(block)?.groupValues?.get(1)
                ?: continue
            val title = stripHtml(rawTitle)
            if (title.length <= 1 || !seen.add(fullUrl)) continue

            val img = extractAnimeImageSource(block)?.let { resolveUrl(it.replace("&amp;", "&"), baseUrl) }
            val ep = stripHtml(epRegex.find(block)?.groupValues?.get(1).orEmpty())
            val qual = stripHtml(qualRegex.find(block)?.groupValues?.get(1).orEmpty())
            val score = stripHtml(
                starRegex.find(block)?.groupValues?.get(1)
                    ?: avgScoreRegex.find(block)?.groupValues?.get(1)
                    ?: ""
            )
            val infoStr = listOf(ep, qual).filter { it.isNotBlank() }.joinToString(" • ")

            out.add(
                Anime(
                    title = title,
                    subtitle = infoStr,
                    url = fullUrl,
                    imageUrl = img,
                    bannerUrl = img,
                    episodeText = ep,
                    quality = qual.ifBlank { "FHD" },
                    score = score,
                    info = infoStr,
                    source = ContentSource.ANIME
                )
            )
        }
        return out
    }

    private fun extractAnimeImageSource(block: String): String? {
        val imgTag = Regex("""(?is)(<img\b[^>]*>)""").find(block)?.groupValues?.get(1) ?: return null
        for (attr in listOf("data-original", "data-src", "data-lazy-src", "data-lazy", "src")) {
            val m = Regex("""(?is)(?:^|\s)$attr\s*=\s*["']([^"']+)["']""").find(imgTag)
            val v = m?.groupValues?.get(1)?.trim().orEmpty()
            if (v.isNotEmpty() && !v.startsWith("data:", ignoreCase = true) && !v.contains("blank.gif", ignoreCase = true)) {
                return v
            }
        }
        return null
    }

    private suspend fun loadAnimeDetail(anime: Anime): AnimeDetail {
        val html = fetchAnimeHtml(anime.url, referer = animeBaseUrl)
        val title = Regex("""(?is)<h1[^>]*>(.*?)</h1>""").find(html)?.groupValues?.get(1)?.let { stripHtml(it) }
            ?.takeIf { it.isNotBlank() } ?: anime.title
        val banner = Regex("""(?is)<meta\s+(?:property=["']og:image["']|itemprop=["']image["'])\s+content=["']([^"']*banner[^"']*)["']""")
            .find(html)?.groupValues?.get(1)?.let { resolveUrl(it, animeBaseUrl) }
        val poster = Regex("""(?is)<meta\s+(?:property=["']og:image["']|itemprop=["']image["'])\s+content=["']([^"']+)["']""")
            .find(html)?.groupValues?.get(1)?.let { resolveUrl(it, animeBaseUrl) } ?: anime.imageUrl
        val desc = Regex("""(?is)<div[^>]*class=["'][^"']*Description[^"']*["'][^>]*>(.*?)</div>""")
            .find(html)?.groupValues?.get(1)?.let { stripHtml(it) }
            ?.takeIf { it.isNotBlank() } ?: anime.summary.ifBlank { "Chưa có nội dung giới thiệu." }
        val info = Regex("""(?is)<div[^>]*class=["'][^"']*(?:Info|film-info)[^"']*["'][^>]*>(.*?)</div>""")
            .find(html)?.groupValues?.get(1)?.let { stripHtml(it) }
            ?.takeIf { it.isNotBlank() } ?: anime.info

        // Parse episodes from detail page first, then watch page if detail page only has the "Xem phim" link
        var episodes = parseAnimeEpisodesFromHtml(html, anime.url)
        val hasRealEpisodeList = episodes.size > 1 || episodes.any { it.animeHash.isNotBlank() }
        if (!hasRealEpisodeList) {
            val watchHref = Regex("""(?is)<a[^>]+href=["']([^"']*xem-phim[^"']*)["']""").find(html)?.groupValues?.get(1)
            val watchUrl = watchHref?.let { resolveUrl(it, anime.url) } ?: "${anime.url.trimEnd('/')}/xem-phim.html"
            try {
                val watchHtml = fetchAnimeHtml(watchUrl, referer = anime.url)
                val watchEpisodes = parseAnimeEpisodesFromHtml(watchHtml, watchUrl)
                if (watchEpisodes.isNotEmpty()) {
                    episodes = watchEpisodes
                }
            } catch (_: Throwable) {}
        }

        if (episodes.isEmpty()) {
            episodes = listOf(
                Episode(
                    title = "Tập 1",
                    url = "${anime.url.trimEnd('/')}/xem-phim.html",
                    number = 1,
                    season = "AnimeVietsub",
                    qualityBadge = "FHD",
                    source = ContentSource.ANIME,
                    playbackKind = "anime"
                )
            )
        }

        return AnimeDetail(
            title = title,
            posterUrl = poster,
            bannerUrl = banner ?: poster,
            summary = desc,
            info = info,
            episodes = episodes
        )
    }

    private fun parseAnimeEpisodesFromHtml(html: String, pageUrl: String): List<Episode> {
        val filmId = Regex("""filmInfo\.filmID\s*=\s*parseInt\(['"]?(\d+)['"]?\)""").find(html)?.groupValues?.get(1)
            ?: Regex("""data-id=["'](\d+)["']""").find(html)?.groupValues?.get(1)
            ?: ""

        val aTagRegex = Regex("""(?is)<a\b([^>]*)>(.*?)</a>""")
        val hrefRegex = Regex("""(?is)href\s*=\s*["']([^"']+)["']""")
        val hashRegex = Regex("""(?is)data-hash\s*=\s*["']([^"']+)["']""")
        val seasonRegex = Regex("""(?is)data-(?:season|season-id)\s*=\s*["']([^"']+)["']""")
        val numRegex = Regex("""(?i)(?:tập|tap|ep)[^0-9]*([0-9]+)""")

        val out = ArrayList<Episode>()
        val seen = HashSet<String>()

        for (m in aTagRegex.findAll(html)) {
            val attrs = m.groupValues[1]
            val body = m.groupValues[2]
            val rawHref = hrefRegex.find(attrs)?.groupValues?.get(1) ?: ""
            val hash = hashRegex.find(attrs)?.groupValues?.get(1)?.trim().orEmpty()
            val lower = "$attrs $rawHref".lowercase()

            val isEpisodeLink = lower.contains("btn-episode") ||
                lower.contains("episode-link") ||
                hash.isNotBlank() ||
                lower.contains("xem-phim") ||
                Regex("""/(?:tap|ep)-?\d+""").containsMatchIn(lower)
            if (!isEpisodeLink) continue

            val resolvedEpUrl = if (rawHref.isNotBlank() && !rawHref.startsWith("javascript:", ignoreCase = true) && rawHref != "#") {
                resolveUrl(rawHref.replace("&amp;", "&"), pageUrl) ?: pageUrl
            } else {
                pageUrl
            }

            val rawTitle = stripHtml(body)
            // Skip generic "Xem phim" button when actual numbered episodes exist on the same page
            val numStr = numRegex.find("$rawTitle $rawHref")?.groupValues?.get(1)
                ?: rawTitle.toIntOrNull()?.toString()
            val number = numStr?.toIntOrNull()
            val epTitle = when {
                rawTitle.isNotBlank() -> rawTitle
                number != null -> "Tập $number"
                else -> "Xem phim"
            }
            val season = seasonRegex.find(attrs)?.groupValues?.get(1)?.let { stripHtml(it) }
                ?.takeIf { it.isNotBlank() } ?: "AnimeVietsub"

            val key = "$resolvedEpUrl|$epTitle|$hash"
            if (seen.add(key)) {
                out.add(
                    Episode(
                        title = epTitle,
                        url = resolvedEpUrl,
                        number = number ?: (out.size + 1),
                        season = season,
                        qualityBadge = "FHD",
                        source = ContentSource.ANIME,
                        playbackKind = "anime",
                        animeHash = hash,
                        animeFilmId = filmId
                    )
                )
            }
        }

        // Filter out the generic "Xem phim" entry if real episodes were found
        val filtered = if (out.size > 1) {
            out.filterNot { it.title.equals("Xem phim", ignoreCase = true) && it.animeHash.isBlank() }
                .ifEmpty { out }
        } else {
            out
        }

        return filtered.sortedWith(compareBy({ it.number }, { it.title }))
    }

    private fun extractAnimePlayerParams(html: String): Pair<String, String>? {
        val filmId = Regex("""filmInfo\.filmID\s*=\s*parseInt\(['"]?(\d+)['"]?\)""").find(html)?.groupValues?.get(1)
            ?: Regex("""data-id=["'](\d+)["']""").find(html)?.groupValues?.get(1)
            ?: return null
        val hash = Regex("""AnimeVsub\(['"]([^'"]+)['"]""").find(html)?.groupValues?.get(1)
            ?: Regex("""class=["'][^"']*active[^"']*["'][^>]*data-hash=["']([^"']+)["']""").find(html)?.groupValues?.get(1)
            ?: Regex("""data-hash=["']([^"']+)["']""").find(html)?.groupValues?.get(1)
            ?: return null
        if (filmId.isBlank() || hash.isBlank()) return null
        return filmId to hash
    }

    private suspend fun resolveAnimePlayback(episode: Episode): ResolvedPlayback {
        var filmId = episode.animeFilmId
        var hash = episode.animeHash
        var epPageUrl = episode.url

        if (filmId.isBlank() || hash.isBlank()) {
            try {
                val epHtml = fetchAnimeHtml(epPageUrl, referer = animeBaseUrl)
                val params = extractAnimePlayerParams(epHtml)
                if (params != null) {
                    filmId = params.first
                    hash = params.second
                }
            } catch (_: Throwable) {}
        }

        val base = try { URL(epPageUrl) } catch (_: Throwable) { URL(animeBaseUrl) }
        val origin = "${base.protocol}://${base.host}"

        if (filmId.isNotBlank() && hash.isNotBlank()) {
            // 1. Try direct HTTP POST with shared CookieManager cookies + Kotlin AES-CBC + Inflater
            try {
                val body = "link=${URLEncoder.encode(hash, "UTF-8")}&id=${URLEncoder.encode(filmId, "UTF-8")}"
                val conn = (URL("$origin/ajax/player").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = 8000
                    readTimeout = 10000
                    setRequestProperty("User-Agent", USER_AGENT)
                    setRequestProperty("Referer", epPageUrl)
                    setRequestProperty("Origin", origin)
                    setRequestProperty("X-Requested-With", "XMLHttpRequest")
                    setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                    WebViewHelper.getCookieHeader(origin)?.let { setRequestProperty("Cookie", it) }
                }
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val responseText = conn.inputStream.use { String(it.readBytes(), Charsets.UTF_8) }
                conn.disconnect()

                val decoded = decryptAnimePlayerPayload(responseText, origin, epPageUrl)
                if (decoded != null) return decoded
            } catch (_: Throwable) {}

            // 2. Fallback: execute fetch('/ajax/player') + WebCrypto AES-CBC inside WebView on origin
            val wvResolved = WebViewHelper.resolveAnimeAjaxInWebView(
                episodeUrl = epPageUrl,
                filmId = filmId,
                hash = hash
            )
            if (wvResolved != null) return wvResolved
        }

        // 3. Final Fallback: open episode page in full-screen isolated WebView player
        return ResolvedPlayback(
            url = epPageUrl,
            headers = mapOf(
                "Referer" to "$origin/",
                "Origin" to origin,
                "User-Agent" to USER_AGENT
            ),
            isWeb = true,
            webUrl = epPageUrl
        )
    }

    private fun decryptAnimePlayerPayload(
        responseText: String,
        origin: String,
        epPageUrl: String
    ): ResolvedPlayback? {
        var encrypted = responseText.trim()
        try {
            val j = JSONObject(encrypted)
            encrypted = j.optString("link").ifBlank { j.optString("data").ifBlank { j.optString("html").ifBlank { encrypted } } }
        } catch (_: Throwable) {}

        val raw = decodeBase64Url(encrypted)
        if (raw.size <= 16) return null
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
        if (m3u8Match != null) {
            return ResolvedPlayback(
                url = m3u8Match.value,
                headers = mapOf(
                    "Referer" to "$origin/",
                    "Origin" to origin,
                    "User-Agent" to USER_AGENT
                ),
                isWeb = false,
                webUrl = epPageUrl
            )
        }
        val iframeMatch = Regex("""https?://[^\s"'<>]+""", RegexOption.IGNORE_CASE).find(normalized)
        if (iframeMatch != null) {
            return ResolvedPlayback(
                url = iframeMatch.value,
                headers = mapOf(
                    "Referer" to "$origin/",
                    "Origin" to origin,
                    "User-Agent" to USER_AGENT
                ),
                isWeb = true,
                webUrl = epPageUrl
            )
        }
        return null
    }

    // =========================================================================
    // HELPERS
    // =========================================================================

    private fun foldVietnamese(text: String): String {
        val norm = Normalizer.normalize(text, Normalizer.Form.NFD)
        return norm.replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
            .replace('đ', 'd')
            .replace('Đ', 'D')
            .lowercase()
    }

    private fun detectQualityBadge(text: String): String {
        val up = text.uppercase()
        val parts = ArrayList<String>()
        when {
            up.contains("4K") || up.contains("2160") -> parts.add("4K")
            up.contains("FHD") || up.contains("1080") || up.contains("FULL") -> parts.add("FHD")
            up.contains("HD") || up.contains("720") -> parts.add("HD")
            else -> parts.add("FHD")
        }
        val folded = foldVietnamese(text)
        when {
            folded.contains("thuyet minh") -> parts.add("TM")
            folded.contains("long tieng") -> parts.add("LT")
            folded.contains("vietsub") -> parts.add("VS")
        }
        return parts.joinToString(" · ")
    }

    private fun localizedText(value: Any?): String = when (value) {
        is String -> value
        is Number -> value.toString()
        is JSONObject -> value.optString("vi").ifBlank { value.optString("en") }
        else -> ""
    }

    private fun resolveUrl(raw: String?, base: String): String? {
        val clean = raw?.trim().orEmpty()
        if (clean.isEmpty() || clean == "null" || clean.startsWith("javascript:", true) || clean.startsWith("data:", true)) {
            return null
        }
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
            .replace("&#39;", "'")
            .replace("&#x27;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
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
                    WebViewHelper.getCookieHeader(url)?.let { setRequestProperty("Cookie", it) }
                    for ((k, v) in extraHeaders) {
                        setRequestProperty(k, v)
                    }
                }
                val code = conn.responseCode
                WebViewHelper.saveSetCookieHeaders(conn.url?.toString() ?: url, conn.headerFields)
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
