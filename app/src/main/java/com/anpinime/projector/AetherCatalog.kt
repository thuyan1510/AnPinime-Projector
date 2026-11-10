package com.anpinime.projector

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URI
import java.net.URLEncoder
import java.util.Locale

object AetherCatalog {
    const val RENTRY_DOMAINS_URL = "https://rentry.co/aetherdomains"
    const val TMDB_PRIMARY_BASE_URL = "https://api.themoviedb.org/3"
    const val TMDB_FALLBACK_BASE_URL = "https://api.tmdb.org/3"
    const val TMDB_IMAGE_POSTER_BASE = "https://image.tmdb.org/t/p/w500"
    const val TMDB_IMAGE_BACKDROP_BASE = "https://image.tmdb.org/t/p/w780"
    const val LABEL_AETHER = "Nguồn 2 · Aether"

    // Public read-only TMDB v4 Bearer token used by Aether web client
    const val TMDB_READ_TOKEN =
        "eyJhbGciOiJIUzI1NiJ9.eyJhdWQiOiI1YjEwYWNhZDFhNjY3ZTQwMDEyMGVjMTc1ZDBjZTFmZCIsIm5iZiI6MTcyNDk1Mjg3MC45NDA4NDcsInN1YiI6IjY2ZDBhOTgyODQ1OWYzM2FmMjBmYjdkNSIsInNjb3BlcyI6WyJhcGlfcmVhZCJdLCJ2ZXJzaW9uIjoxfQ.ScGHs1VZTLGpUKWPG7EA-2T29OPcqW_qpJjKL5Yhrjc"

    val FALLBACK_DOMAINS: List<String> = listOf(
        "https://aether.st",
        "https://aether.ist",
        "https://aether.cx"
    )

    private const val PREF_NAME = "anpinime_aether_domains"
    private const val PREF_KEY_DOMAINS_JSON = "domains_json"
    private const val PREF_KEY_PRIMARY_DOMAIN = "primary_domain"
    private const val PREF_KEY_UPDATED_AT = "updated_at_ms"
    private const val DOMAIN_CACHE_TTL_MS = 6 * 60 * 60 * 1000L // 6 hours

    @Volatile
    private var cachedDomains: List<String> = FALLBACK_DOMAINS

    @Volatile
    private var cachedPrimaryDomain: String = FALLBACK_DOMAINS.first()

    @Volatile
    private var lastDomainRefreshMs: Long = 0L

    data class MediaRef(
        val mediaType: String, // "movie" or "tv"
        val tmdbId: String,
        val seasonId: String = "",
        val episodeId: String = ""
    )

    fun tmdbHeaders(): Map<String, String> = mapOf(
        "Accept" to "application/json",
        "Authorization" to "Bearer $TMDB_READ_TOKEN"
    )

    fun normalizeHttpsDomain(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val trimmed = raw.trim().trimEnd('/')
        if (!trimmed.startsWith("https://", ignoreCase = true)) return null
        return try {
            val uri = URI(trimmed)
            val scheme = uri.scheme?.lowercase(Locale.US) ?: return null
            val host = uri.host?.lowercase(Locale.US)?.trim() ?: return null
            if (scheme != "https" || host.isEmpty() || !host.contains('.')) return null
            if (host == "rentry.co" || host.endsWith(".rentry.co")) return null
            val portPart = if (uri.port > 0 && uri.port != 443) ":${uri.port}" else ""
            "https://$host$portPart"
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Parses active HTTPS Aether domains from https://rentry.co/aetherdomains HTML (or markdown/text fallback).
     * Prioritizes rows marked with ✅ first, then 🟡, and skips inactive ❌ rows.
     */
    fun parseDomainsFromRentryHtml(rawHtml: String): List<String> {
        if (rawHtml.isBlank()) return emptyList()
        val preferred = LinkedHashSet<String>()
        val secondary = LinkedHashSet<String>()

        try {
            val doc = Jsoup.parse(rawHtml)
            val rows = doc.select("table tr")
            for (row in rows) {
                val cells = row.select("td")
                if (cells.isEmpty()) continue
                val firstCell = cells[0]
                val rawHref = firstCell.selectFirst("a[href]")?.attr("href")
                    ?: firstCell.text().trim()
                val domain = normalizeHttpsDomain(rawHref) ?: continue
                val statusText = if (cells.size > 1) cells[1].text() else row.text()

                when {
                    statusText.contains("❌") -> {
                        // If an inactive domain row points to a redirect target, extract that target as secondary
                        val redirectLinks = if (cells.size > 1) cells[1].select("a[href]") else emptyList()
                        for (a in redirectLinks) {
                            val target = normalizeHttpsDomain(a.attr("href"))
                            if (target != null && target != domain) {
                                secondary.add(target)
                            }
                        }
                    }
                    statusText.contains("✅") -> preferred.add(domain)
                    statusText.contains("🟡") -> secondary.add(domain)
                    else -> secondary.add(domain)
                }
            }
        } catch (_: Throwable) {
        }

        // Plain-text / markdown table fallback if HTML table wasn't matched
        if (preferred.isEmpty() && secondary.isEmpty()) {
            val urlRegex = Regex("""https://[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}""")
            for (line in rawHtml.lineSequence()) {
                if (line.contains("❌")) continue
                val match = urlRegex.find(line)?.value ?: continue
                val domain = normalizeHttpsDomain(match) ?: continue
                if (line.contains("✅")) {
                    preferred.add(domain)
                } else {
                    secondary.add(domain)
                }
            }
        }

        val combined = ArrayList<String>(preferred.size + secondary.size)
        combined.addAll(preferred)
        for (d in secondary) {
            if (!combined.contains(d)) combined.add(d)
        }
        return combined
    }

    fun getActiveDomains(context: Context? = null): List<String> {
        if (context != null && lastDomainRefreshMs == 0L) {
            loadCachedDomainsFromPrefs(context)
        }
        return cachedDomains.ifEmpty { FALLBACK_DOMAINS }
    }

    fun getPrimaryDomain(context: Context? = null): String {
        val list = getActiveDomains(context)
        val primary = cachedPrimaryDomain
        return if (primary.isNotBlank() && list.contains(primary)) primary else list.first()
    }

    fun setPreferredDomain(context: Context?, domain: String) {
        val normalized = normalizeHttpsDomain(domain) ?: return
        cachedPrimaryDomain = normalized
        val updated = LinkedHashSet<String>()
        updated.add(normalized)
        updated.addAll(cachedDomains)
        updated.addAll(FALLBACK_DOMAINS)
        cachedDomains = updated.toList()
        if (context != null) {
            context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(PREF_KEY_PRIMARY_DOMAIN, normalized)
                .putString(PREF_KEY_DOMAINS_JSON, JSONArray(cachedDomains).toString())
                .apply()
        }
    }

    private fun loadCachedDomainsFromPrefs(context: Context) {
        try {
            val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            lastDomainRefreshMs = prefs.getLong(PREF_KEY_UPDATED_AT, 0L)
            val rawJson = prefs.getString(PREF_KEY_DOMAINS_JSON, null)
            val savedPrimary = normalizeHttpsDomain(prefs.getString(PREF_KEY_PRIMARY_DOMAIN, null))
            val list = LinkedHashSet<String>()
            if (!rawJson.isNullOrBlank()) {
                val arr = JSONArray(rawJson)
                for (i in 0 until arr.length()) {
                    normalizeHttpsDomain(arr.optString(i))?.let { list.add(it) }
                }
            }
            for (fb in FALLBACK_DOMAINS) {
                normalizeHttpsDomain(fb)?.let { list.add(it) }
            }
            if (list.isNotEmpty()) {
                cachedDomains = list.toList()
                cachedPrimaryDomain = if (savedPrimary != null && list.contains(savedPrimary)) {
                    savedPrimary
                } else {
                    cachedDomains.first()
                }
            }
        } catch (_: Throwable) {
        }
    }

    fun refreshDomainsIfNeeded(
        context: Context?,
        force: Boolean = false,
        fetcher: (String) -> String?,
        reachabilityProbe: ((String) -> Boolean)? = null
    ): List<String> {
        if (context != null && lastDomainRefreshMs == 0L) {
            loadCachedDomainsFromPrefs(context)
        }
        val now = System.currentTimeMillis()
        if (!force && lastDomainRefreshMs > 0L && (now - lastDomainRefreshMs) < DOMAIN_CACHE_TTL_MS && cachedDomains.isNotEmpty()) {
            return cachedDomains
        }

        val candidates = LinkedHashSet<String>()
        val html = try {
            fetcher(RENTRY_DOMAINS_URL)
        } catch (_: Throwable) {
            null
        }
        if (!html.isNullOrBlank()) {
            for (d in parseDomainsFromRentryHtml(html)) {
                candidates.add(d)
            }
        }
        for (d in cachedDomains) {
            normalizeHttpsDomain(d)?.let { candidates.add(it) }
        }
        for (fb in FALLBACK_DOMAINS) {
            normalizeHttpsDomain(fb)?.let { candidates.add(it) }
        }

        val ordered = if (reachabilityProbe != null) {
            val reachable = ArrayList<String>()
            val unreachable = ArrayList<String>()
            for (domain in candidates) {
                val ok = try {
                    reachabilityProbe(domain)
                } catch (_: Throwable) {
                    false
                }
                if (ok) reachable.add(domain) else unreachable.add(domain)
            }
            reachable + unreachable
        } else {
            candidates.toList()
        }.ifEmpty { FALLBACK_DOMAINS }

        cachedDomains = ordered
        cachedPrimaryDomain = ordered.first()
        lastDomainRefreshMs = now

        if (context != null) {
            try {
                context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putString(PREF_KEY_DOMAINS_JSON, JSONArray(ordered).toString())
                    .putString(PREF_KEY_PRIMARY_DOMAIN, cachedPrimaryDomain)
                    .putLong(PREF_KEY_UPDATED_AT, now)
                    .apply()
            } catch (_: Throwable) {
            }
        }
        return ordered
    }

    // =========================================================================
    // URL & ID Helpers (Stable TMDB IDs + Streambert-compatible Aether URLs)
    // =========================================================================

    fun buildStableItemUrl(mediaType: String, tmdbId: String): String {
        val cleanType = if (mediaType.equals("tv", ignoreCase = true) || mediaType.equals("show", ignoreCase = true)) {
            "tv"
        } else {
            "movie"
        }
        return "aether://$cleanType/${tmdbId.trim()}"
    }

    fun buildMovieUrl(tmdbId: String, baseDomain: String = getPrimaryDomain()): String {
        val domain = normalizeHttpsDomain(baseDomain) ?: FALLBACK_DOMAINS.first()
        return "$domain/media/tmdb-movie-${tmdbId.trim()}"
    }

    fun buildTvEpisodeUrl(
        tmdbId: String,
        seasonId: String,
        episodeId: String,
        baseDomain: String = getPrimaryDomain()
    ): String {
        val domain = normalizeHttpsDomain(baseDomain) ?: FALLBACK_DOMAINS.first()
        val sId = seasonId.trim().ifEmpty { "1" }
        val eId = episodeId.trim().ifEmpty { "1" }
        return "$domain/media/tmdb-tv-${tmdbId.trim()}/$sId/$eId"
    }

    fun rewriteUrlToDomain(url: String, newDomain: String): String {
        val targetDomain = normalizeHttpsDomain(newDomain) ?: return url
        val ref = parseMediaRef(url)
        if (ref != null) {
            return if (ref.mediaType == "tv") {
                buildTvEpisodeUrl(ref.tmdbId, ref.seasonId, ref.episodeId, targetDomain)
            } else {
                buildMovieUrl(ref.tmdbId, targetDomain)
            }
        }
        return try {
            val uri = URI(url)
            val path = uri.rawPath ?: ""
            val query = uri.rawQuery?.let { "?$it" } ?: ""
            "$targetDomain$path$query"
        } catch (_: Throwable) {
            url
        }
    }

    fun parseMediaRef(urlOrId: String?): MediaRef? {
        if (urlOrId.isNullOrBlank()) return null
        val trimmed = urlOrId.trim()

        // 1. Stable internal URI: aether://movie/12345 or aether://tv/1396/3682/234561
        if (trimmed.startsWith("aether://", ignoreCase = true)) {
            val rest = trimmed.removePrefix("aether://").removePrefix("AETHER://")
            val parts = rest.split('/').filter { it.isNotBlank() }
            if (parts.size >= 2) {
                val type = if (parts[0].equals("tv", ignoreCase = true)) "tv" else "movie"
                val id = parts[1].takeWhile { it.isDigit() }
                if (id.isNotEmpty()) {
                    val sId = parts.getOrNull(2).orEmpty()
                    val eId = parts.getOrNull(3).orEmpty()
                    return MediaRef(type, id, sId, eId)
                }
            }
        }

        // 2. Colon shorthand: movie:12345 or tv:1396
        if (trimmed.startsWith("movie:", ignoreCase = true) || trimmed.startsWith("tv:", ignoreCase = true)) {
            val parts = trimmed.split(':')
            if (parts.size >= 2) {
                val type = if (parts[0].equals("tv", ignoreCase = true)) "tv" else "movie"
                val id = parts[1].takeWhile { it.isDigit() }
                if (id.isNotEmpty()) {
                    return MediaRef(type, id, parts.getOrNull(2).orEmpty(), parts.getOrNull(3).orEmpty())
                }
            }
        }

        // 3. Aether web URL: https://aether.st/media/tmdb-movie-823464 or /media/tmdb-tv-1396/3572/62085
        val movieMatch = Regex("""/media/tmdb-movie-(\d+)""", RegexOption.IGNORE_CASE).find(trimmed)
        if (movieMatch != null) {
            return MediaRef("movie", movieMatch.groupValues[1])
        }

        val tvMatch = Regex("""/media/tmdb-(?:tv|show)-(\d+)(?:-[^/]+)?(?:/([^/?#]+))?(?:/([^/?#]+))?""", RegexOption.IGNORE_CASE).find(trimmed)
        if (tvMatch != null) {
            return MediaRef(
                mediaType = "tv",
                tmdbId = tvMatch.groupValues[1],
                seasonId = tvMatch.groupValues.getOrNull(2).orEmpty(),
                episodeId = tvMatch.groupValues.getOrNull(3).orEmpty()
            )
        }

        return null
    }

    // =========================================================================
    // Home Sections & Categories (TMDB-backed Aether Catalog)
    // =========================================================================

    val homeSectionsConfig: List<Pair<String, String>> = listOf(
        "🔥 Xu hướng trong tuần" to "trending/all/week",
        "🎬 Phim lẻ thịnh hành" to "trending/movie/week",
        "📺 Phim bộ thịnh hành" to "trending/tv/week",
        "🍿 Phim chiếu rạp mới" to "movie/now_playing",
        "⭐ Phim lẻ điểm cao" to "movie/top_rated",
        "🏆 Phim bộ điểm cao" to "tv/top_rated"
    )

    fun defaultCategories(): List<AnimeCategory> = listOf(
        // Danh mục
        AnimeCategory("Xu hướng trong tuần", "trending/all/week", "Danh mục"),
        AnimeCategory("Phim lẻ thịnh hành", "trending/movie/week", "Danh mục"),
        AnimeCategory("Phim bộ thịnh hành", "trending/tv/week", "Danh mục"),
        AnimeCategory("Phim lẻ phổ biến", "movie/popular", "Danh mục"),
        AnimeCategory("Phim bộ phổ biến", "tv/popular", "Danh mục"),
        AnimeCategory("Phim chiếu rạp", "movie/now_playing", "Danh mục"),
        AnimeCategory("Phim lẻ điểm cao", "movie/top_rated", "Danh mục"),
        AnimeCategory("Phim bộ điểm cao", "tv/top_rated", "Danh mục"),
        AnimeCategory("Phim bộ đang chiếu", "tv/on_the_air", "Danh mục"),

        // Thể loại Phim lẻ
        AnimeCategory("Hành động (Phim lẻ)", "discover/movie?with_genres=28", "Thể loại Phim lẻ"),
        AnimeCategory("Phiêu lưu (Phim lẻ)", "discover/movie?with_genres=12", "Thể loại Phim lẻ"),
        AnimeCategory("Hoạt hình (Phim lẻ)", "discover/movie?with_genres=16", "Thể loại Phim lẻ"),
        AnimeCategory("Hài (Phim lẻ)", "discover/movie?with_genres=35", "Thể loại Phim lẻ"),
        AnimeCategory("Hình sự (Phim lẻ)", "discover/movie?with_genres=80", "Thể loại Phim lẻ"),
        AnimeCategory("Chính kịch (Phim lẻ)", "discover/movie?with_genres=18", "Thể loại Phim lẻ"),
        AnimeCategory("Gia đình (Phim lẻ)", "discover/movie?with_genres=10751", "Thể loại Phim lẻ"),
        AnimeCategory("Giả tưởng (Phim lẻ)", "discover/movie?with_genres=14", "Thể loại Phim lẻ"),
        AnimeCategory("Kinh dị (Phim lẻ)", "discover/movie?with_genres=27", "Thể loại Phim lẻ"),
        AnimeCategory("Bí ẩn (Phim lẻ)", "discover/movie?with_genres=9648", "Thể loại Phim lẻ"),
        AnimeCategory("Lãng mạn (Phim lẻ)", "discover/movie?with_genres=10749", "Thể loại Phim lẻ"),
        AnimeCategory("Khoa học viễn tưởng", "discover/movie?with_genres=878", "Thể loại Phim lẻ"),
        AnimeCategory("Gây cấn (Phim lẻ)", "discover/movie?with_genres=53", "Thể loại Phim lẻ"),
        AnimeCategory("Chiến tranh (Phim lẻ)", "discover/movie?with_genres=10752", "Thể loại Phim lẻ"),

        // Thể loại Phim bộ
        AnimeCategory("Hành động & Phiêu lưu (Bộ)", "discover/tv?with_genres=10759", "Thể loại Phim bộ"),
        AnimeCategory("Hoạt hình (Phim bộ)", "discover/tv?with_genres=16", "Thể loại Phim bộ"),
        AnimeCategory("Hài (Phim bộ)", "discover/tv?with_genres=35", "Thể loại Phim bộ"),
        AnimeCategory("Hình sự (Phim bộ)", "discover/tv?with_genres=80", "Thể loại Phim bộ"),
        AnimeCategory("Chính kịch (Phim bộ)", "discover/tv?with_genres=18", "Thể loại Phim bộ"),
        AnimeCategory("Bí ẩn (Phim bộ)", "discover/tv?with_genres=9648", "Thể loại Phim bộ"),
        AnimeCategory("Viễn tưởng & Giả tưởng (Bộ)", "discover/tv?with_genres=10765", "Thể loại Phim bộ"),

        // Quốc gia
        AnimeCategory("Phim Âu Mỹ (Lẻ)", "discover/movie?with_origin_country=US", "Quốc gia"),
        AnimeCategory("Phim Âu Mỹ (Bộ)", "discover/tv?with_origin_country=US", "Quốc gia"),
        AnimeCategory("Phim Hàn Quốc (Lẻ)", "discover/movie?with_origin_country=KR", "Quốc gia"),
        AnimeCategory("Phim Hàn Quốc (Bộ)", "discover/tv?with_origin_country=KR", "Quốc gia"),
        AnimeCategory("Phim Trung Quốc (Lẻ)", "discover/movie?with_origin_country=CN", "Quốc gia"),
        AnimeCategory("Phim Trung Quốc (Bộ)", "discover/tv?with_origin_country=CN", "Quốc gia"),
        AnimeCategory("Phim Nhật Bản (Lẻ)", "discover/movie?with_origin_country=JP", "Quốc gia"),
        AnimeCategory("Phim Nhật Bản (Bộ)", "discover/tv?with_origin_country=JP", "Quốc gia"),
        AnimeCategory("Phim Thái Lan", "discover/movie?with_origin_country=TH", "Quốc gia"),
        AnimeCategory("Phim Việt Nam", "discover/movie?with_origin_country=VN", "Quốc gia"),
        AnimeCategory("Phim Anh Quốc", "discover/movie?with_origin_country=GB", "Quốc gia")
    )

    fun buildTmdbEndpointUrl(
        pathAndQuery: String,
        page: Int = 1,
        language: String = "vi-VN",
        baseUrl: String = TMDB_PRIMARY_BASE_URL
    ): String {
        val cleanPath = pathAndQuery.trim().trimStart('/')
        val sep = if (cleanPath.contains('?')) "&" else "?"
        val pageParam = if (cleanPath.contains("page=")) "" else "&page=${page.coerceAtLeast(1)}"
        val adultParam = if (cleanPath.contains("include_adult=")) "" else "&include_adult=false"
        return "${baseUrl.trimEnd('/')}/$cleanPath${sep}language=$language$pageParam$adultParam"
    }

    fun buildTmdbSearchUrl(
        query: String,
        page: Int = 1,
        language: String = "vi-VN",
        baseUrl: String = TMDB_PRIMARY_BASE_URL
    ): String {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        return "${baseUrl.trimEnd('/')}/search/multi?query=$encoded&include_adult=false&language=$language&page=${page.coerceAtLeast(1)}"
    }

    fun parseItems(
        rawJson: String,
        defaultMediaType: String? = null,
        source: ContentSource = ContentSource.AETHER
    ): List<Anime> {
        if (rawJson.isBlank()) return emptyList()
        val result = ArrayList<Anime>()
        val seen = HashSet<String>()

        try {
            val root = JSONObject(rawJson)
            val arr = root.optJSONArray("results") ?: return emptyList()
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val rawType = item.optString("media_type").lowercase(Locale.US)
                if (rawType == "person") continue

                val inferredType = when {
                    rawType == "tv" || rawType == "show" -> "tv"
                    rawType == "movie" -> "movie"
                    !defaultMediaType.isNullOrBlank() -> defaultMediaType.lowercase(Locale.US)
                    item.has("first_air_date") || item.has("name") && !item.has("title") -> "tv"
                    else -> "movie"
                }

                val id = item.optInt("id", 0)
                if (id <= 0) continue
                val idStr = id.toString()
                val dedupeKey = "$inferredType:$idStr"
                if (!seen.add(dedupeKey)) continue

                val title = if (inferredType == "tv") {
                    item.optString("name").ifBlank { item.optString("original_name") }
                } else {
                    item.optString("title").ifBlank { item.optString("original_title") }
                }.trim()
                if (title.isEmpty()) continue

                val originalTitle = if (inferredType == "tv") {
                    item.optString("original_name")
                } else {
                    item.optString("original_title")
                }.trim()

                val releaseDate = if (inferredType == "tv") {
                    item.optString("first_air_date")
                } else {
                    item.optString("release_date")
                }.trim()
                val year = releaseDate.take(4).takeIf { it.length == 4 && it.all(Char::isDigit) }.orEmpty()

                val posterPath = item.optString("poster_path").trim()
                val backdropPath = item.optString("backdrop_path").trim()
                val posterUrl = if (posterPath.startsWith("/")) "$TMDB_IMAGE_POSTER_BASE$posterPath" else null
                val bannerUrl = when {
                    backdropPath.startsWith("/") -> "$TMDB_IMAGE_BACKDROP_BASE$backdropPath"
                    posterUrl != null -> posterUrl
                    else -> null
                }
                if (posterUrl == null && bannerUrl == null) continue

                val voteAverage = item.optDouble("vote_average", 0.0)
                val score = if (!voteAverage.isNaN() && voteAverage > 0.0) {
                    String.format(Locale.US, "%.1f", voteAverage)
                } else {
                    ""
                }

                val typeLabel = if (inferredType == "tv") "Phim bộ" else "Phim lẻ"
                val subtitle = buildString {
                    if (originalTitle.isNotEmpty() && !originalTitle.equals(title, ignoreCase = true)) {
                        append(originalTitle)
                    }
                    if (year.isNotEmpty()) {
                        if (isNotEmpty()) append(" · ")
                        append(year)
                    }
                }

                result.add(
                    Anime(
                        title = title,
                        subtitle = subtitle,
                        url = buildStableItemUrl(inferredType, idStr),
                        imageUrl = posterUrl ?: bannerUrl,
                        bannerUrl = bannerUrl ?: posterUrl,
                        episodeText = typeLabel,
                        quality = "FHD · Vietsub",
                        score = score,
                        summary = item.optString("overview").trim(),
                        info = listOfNotNull(
                            year.takeIf { it.isNotEmpty() },
                            typeLabel,
                            "Aether"
                        ).joinToString(" • "),
                        source = source,
                        catalogLabel = LABEL_AETHER
                    )
                )
            }
        } catch (_: Throwable) {
        }
        return result
    }

    fun parseMovieDetail(
        rawJson: String,
        fallbackOverview: String = "",
        domains: List<String> = getActiveDomains(),
        source: ContentSource = ContentSource.AETHER
    ): AnimeDetail? {
        if (rawJson.isBlank()) return null
        return try {
            val obj = JSONObject(rawJson)
            val id = obj.optInt("id", 0)
            if (id <= 0) return null
            val idStr = id.toString()

            val title = obj.optString("title").ifBlank { obj.optString("original_title") }.trim()
            if (title.isEmpty()) return null
            val originalTitle = obj.optString("original_title").trim()
            val year = obj.optString("release_date").take(4)
            val runtime = obj.optInt("runtime", 0)
            val voteAvg = obj.optDouble("vote_average", 0.0)
            val score = if (!voteAvg.isNaN() && voteAvg > 0.0) String.format(Locale.US, "%.1f", voteAvg) else ""

            val posterPath = obj.optString("poster_path").trim()
            val backdropPath = obj.optString("backdrop_path").trim()
            val posterUrl = if (posterPath.startsWith("/")) "$TMDB_IMAGE_POSTER_BASE$posterPath" else null
            val bannerUrl = if (backdropPath.startsWith("/")) "$TMDB_IMAGE_BACKDROP_BASE$backdropPath" else posterUrl

            val genres = parseNames(obj.optJSONArray("genres"))
            val countries = parseCountries(obj.optJSONArray("production_countries"), obj.optJSONArray("origin_country"))
            val cast = parseCast(obj.optJSONObject("credits"))
            val overview = obj.optString("overview").trim()
                .ifBlank { fallbackOverview.trim() }
                .ifBlank { "Phim lẻ phát trực tiếp qua nguồn Aether với phụ đề đa ngôn ngữ & dịch tự động sang Tiếng Việt." }

            val primaryDomain = domains.firstOrNull { it.isNotBlank() } ?: FALLBACK_DOMAINS.first()
            val serverLabel = "Aether · Tự động"
            val playUrl = buildMovieUrl(idStr, primaryDomain)
            val episodes = listOf(
                Episode(
                    title = "Full HD",
                    url = playUrl,
                    number = 1,
                    season = serverLabel,
                    qualityBadge = "FHD · Auto Sub Việt",
                    source = source,
                    playbackKind = "aether_web",
                    slug = "movie:$idStr",
                    seasonNum = 1,
                    episodeNum = 1,
                    serverIndex = 0,
                    episodeIndex = 0,
                    directUrl = playUrl,
                    refererUrl = primaryDomain,
                    detailUrl = buildStableItemUrl("movie", idStr),
                    serverName = serverLabel,
                    seasonId = "1",
                    episodeId = idStr,
                    audioLabel = "Multi Audio",
                    subtitleLabel = "Vietsub / Dịch tự động"
                )
            )

            AnimeDetail(
                title = title,
                posterUrl = posterUrl ?: bannerUrl,
                bannerUrl = bannerUrl ?: posterUrl,
                summary = overview,
                info = listOfNotNull(
                    originalTitle.takeIf { it.isNotEmpty() && !it.equals(title, ignoreCase = true) },
                    year.takeIf { it.isNotEmpty() },
                    runtime.takeIf { it > 0 }?.let { "${it} phút" },
                    score.takeIf { it.isNotEmpty() }?.let { "⭐ $it" },
                    countries.takeIf { it.isNotEmpty() },
                    genres.takeIf { it.isNotEmpty() },
                    cast.takeIf { it.isNotEmpty() }?.let { "Diễn viên: $it" }
                ).joinToString(" • "),
                episodes = episodes,
                watchUrl = episodes.firstOrNull()?.url
            )
        } catch (_: Throwable) {
            null
        }
    }

    data class TvSeasonInfo(
        val seasonTmdbId: String,
        val seasonNumber: Int,
        val title: String,
        val episodeCount: Int
    )

    fun parseTvSeasonsList(rawShowJson: String): List<TvSeasonInfo> {
        if (rawShowJson.isBlank()) return emptyList()
        val list = ArrayList<TvSeasonInfo>()
        try {
            val obj = JSONObject(rawShowJson)
            val seasonsArr = obj.optJSONArray("seasons") ?: return emptyList()
            for (i in 0 until seasonsArr.length()) {
                val s = seasonsArr.optJSONObject(i) ?: continue
                val sNum = s.optInt("season_number", 0)
                if (sNum <= 0) continue
                val sId = s.optInt("id", 0).takeIf { it > 0 }?.toString() ?: sNum.toString()
                val epCount = s.optInt("episode_count", 0)
                val name = s.optString("name").trim().ifEmpty { "Mùa $sNum" }
                list.add(TvSeasonInfo(sId, sNum, name, epCount))
            }
            list.sortBy { it.seasonNumber }
        } catch (_: Throwable) {
        }
        return list
    }

    fun parseTvDetail(
        rawShowJson: String,
        seasonPayloads: Map<Int, String>,
        fallbackOverview: String = "",
        primaryDomain: String = getPrimaryDomain(),
        source: ContentSource = ContentSource.AETHER
    ): AnimeDetail? {
        if (rawShowJson.isBlank()) return null
        return try {
            val obj = JSONObject(rawShowJson)
            val showId = obj.optInt("id", 0)
            if (showId <= 0) return null
            val showIdStr = showId.toString()

            val title = obj.optString("name").ifBlank { obj.optString("original_name") }.trim()
            if (title.isEmpty()) return null
            val originalTitle = obj.optString("original_name").trim()
            val year = obj.optString("first_air_date").take(4)
            val totalSeasons = obj.optInt("number_of_seasons", 1)
            val totalEpisodes = obj.optInt("number_of_episodes", 0)
            val voteAvg = obj.optDouble("vote_average", 0.0)
            val score = if (!voteAvg.isNaN() && voteAvg > 0.0) String.format(Locale.US, "%.1f", voteAvg) else ""

            val posterPath = obj.optString("poster_path").trim()
            val backdropPath = obj.optString("backdrop_path").trim()
            val posterUrl = if (posterPath.startsWith("/")) "$TMDB_IMAGE_POSTER_BASE$posterPath" else null
            val bannerUrl = if (backdropPath.startsWith("/")) "$TMDB_IMAGE_BACKDROP_BASE$backdropPath" else posterUrl

            val genres = parseNames(obj.optJSONArray("genres"))
            val countries = parseCountries(obj.optJSONArray("production_countries"), obj.optJSONArray("origin_country"))
            val cast = parseCast(obj.optJSONObject("credits"))
            val overview = obj.optString("overview").trim()
                .ifBlank { fallbackOverview.trim() }
                .ifBlank { "Phim bộ phát trực tiếp qua nguồn Aether với phụ đề đa ngôn ngữ & dịch tự động sang Tiếng Việt." }

            val seasonsInfo = parseTvSeasonsList(rawShowJson)
            val episodes = ArrayList<Episode>()

            for ((sIdx, sInfo) in seasonsInfo.withIndex()) {
                val seasonLabel = "Mùa ${sInfo.seasonNumber}"
                val seasonRaw = seasonPayloads[sInfo.seasonNumber]
                var addedForSeason = 0
                if (!seasonRaw.isNullOrBlank()) {
                    try {
                        val sObj = JSONObject(seasonRaw)
                        val realSeasonTmdbId = sObj.optInt("id", 0).takeIf { it > 0 }?.toString() ?: sInfo.seasonTmdbId
                        val epArr = sObj.optJSONArray("episodes")
                        if (epArr != null) {
                            for (eIdx in 0 until epArr.length()) {
                                val epObj = epArr.optJSONObject(eIdx) ?: continue
                                val epNum = epObj.optInt("episode_number", eIdx + 1)
                                val epTmdbId = epObj.optInt("id", 0).takeIf { it > 0 }?.toString() ?: epNum.toString()
                                val epName = epObj.optString("name").trim()
                                val epTitle = if (epName.isNotEmpty() && !epName.equals("Episode $epNum", ignoreCase = true) && !epName.equals("Tập $epNum", ignoreCase = true)) {
                                    "Tập $epNum · $epName"
                                } else {
                                    "Tập $epNum"
                                }
                                val playUrl = buildTvEpisodeUrl(showIdStr, realSeasonTmdbId, epTmdbId, primaryDomain)
                                episodes.add(
                                    Episode(
                                        title = epTitle,
                                        url = playUrl,
                                        number = epNum,
                                        season = seasonLabel,
                                        qualityBadge = "FHD · Auto Sub Việt",
                                        source = source,
                                        playbackKind = "aether_web",
                                        slug = "tv:$showIdStr",
                                        seasonNum = sInfo.seasonNumber,
                                        episodeNum = epNum,
                                        serverIndex = sIdx,
                                        episodeIndex = eIdx,
                                        directUrl = playUrl,
                                        refererUrl = primaryDomain,
                                        detailUrl = buildStableItemUrl("tv", showIdStr),
                                        serverName = seasonLabel,
                                        seasonId = realSeasonTmdbId,
                                        episodeId = epTmdbId,
                                        audioLabel = "Multi Audio",
                                        subtitleLabel = "Vietsub / Dịch tự động"
                                    )
                                )
                                addedForSeason++
                            }
                        }
                    } catch (_: Throwable) {
                    }
                }

                if (addedForSeason == 0 && sInfo.episodeCount > 0) {
                    val count = sInfo.episodeCount.coerceAtMost(50)
                    for (epNum in 1..count) {
                        val playUrl = buildTvEpisodeUrl(showIdStr, sInfo.seasonTmdbId, epNum.toString(), primaryDomain)
                        episodes.add(
                            Episode(
                                title = "Tập $epNum",
                                url = playUrl,
                                number = epNum,
                                season = seasonLabel,
                                qualityBadge = "FHD · Auto Sub Việt",
                                source = source,
                                playbackKind = "aether_web",
                                slug = "tv:$showIdStr",
                                seasonNum = sInfo.seasonNumber,
                                episodeNum = epNum,
                                serverIndex = sIdx,
                                episodeIndex = epNum - 1,
                                directUrl = playUrl,
                                refererUrl = primaryDomain,
                                detailUrl = buildStableItemUrl("tv", showIdStr),
                                serverName = seasonLabel,
                                seasonId = sInfo.seasonTmdbId,
                                episodeId = epNum.toString(),
                                audioLabel = "Multi Audio",
                                subtitleLabel = "Vietsub / Dịch tự động"
                            )
                        )
                    }
                }
            }

            if (episodes.isEmpty()) {
                val playUrl = buildTvEpisodeUrl(showIdStr, "1", "1", primaryDomain)
                episodes.add(
                    Episode(
                        title = "Tập 1",
                        url = playUrl,
                        number = 1,
                        season = "Mùa 1",
                        qualityBadge = "FHD · Auto Sub Việt",
                        source = source,
                        playbackKind = "aether_web",
                        slug = "tv:$showIdStr",
                        seasonNum = 1,
                        episodeNum = 1,
                        directUrl = playUrl,
                        refererUrl = primaryDomain,
                        detailUrl = buildStableItemUrl("tv", showIdStr),
                        serverName = "Mùa 1",
                        seasonId = "1",
                        episodeId = "1",
                        audioLabel = "Multi Audio",
                        subtitleLabel = "Vietsub / Dịch tự động"
                    )
                )
            }

            AnimeDetail(
                title = title,
                posterUrl = posterUrl ?: bannerUrl,
                bannerUrl = bannerUrl ?: posterUrl,
                summary = overview,
                info = listOfNotNull(
                    originalTitle.takeIf { it.isNotEmpty() && !it.equals(title, ignoreCase = true) },
                    year.takeIf { it.isNotEmpty() },
                    "$totalSeasons mùa",
                    totalEpisodes.takeIf { it > 0 }?.let { "$it tập" },
                    score.takeIf { it.isNotEmpty() }?.let { "⭐ $it" },
                    countries.takeIf { it.isNotEmpty() },
                    genres.takeIf { it.isNotEmpty() },
                    cast.takeIf { it.isNotEmpty() }?.let { "Diễn viên: $it" }
                ).joinToString(" • "),
                episodes = episodes,
                watchUrl = episodes.firstOrNull()?.url
            )
        } catch (_: Throwable) {
            null
        }
    }

    private fun parseNames(arr: JSONArray?): String {
        if (arr == null) return ""
        val list = ArrayList<String>()
        for (i in 0 until arr.length()) {
            val name = arr.optJSONObject(i)?.optString("name")?.trim().orEmpty()
            if (name.isNotEmpty()) list.add(name)
        }
        return list.joinToString(", ")
    }

    private fun parseCountries(prodArr: JSONArray?, originArr: JSONArray?): String {
        val list = ArrayList<String>()
        if (prodArr != null) {
            for (i in 0 until prodArr.length()) {
                val name = prodArr.optJSONObject(i)?.optString("name")?.trim().orEmpty()
                if (name.isNotEmpty()) list.add(name)
            }
        }
        if (list.isEmpty() && originArr != null) {
            for (i in 0 until originArr.length()) {
                val code = originArr.optString(i).trim()
                if (code.isNotEmpty()) list.add(code)
            }
        }
        return list.joinToString(", ")
    }

    private fun parseCast(creditsObj: JSONObject?): String {
        val castArr = creditsObj?.optJSONArray("cast") ?: return ""
        val list = ArrayList<String>()
        val max = castArr.length().coerceAtMost(8)
        for (i in 0 until max) {
            val name = castArr.optJSONObject(i)?.optString("name")?.trim().orEmpty()
            if (name.isNotEmpty()) list.add(name)
        }
        return list.joinToString(", ")
    }
}
