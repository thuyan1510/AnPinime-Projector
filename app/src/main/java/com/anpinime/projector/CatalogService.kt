package com.anpinime.projector

import android.content.Context
import android.net.Uri
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.Normalizer
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object CatalogService {
    const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
    val animeBaseUrl: String
        get() = resolveAnimeDomain(false)

    private const val ANIME_SHORTLINK = "https://bit.ly/animevietsubtv"
    private const val ANIME_VERIFY_PATH = "/phim/bleach-sennen-kessen-hen-kashin-tan-a5985/"
    private const val ANIME_DOMAIN_TTL_MS = 6 * 60 * 60 * 1000L // 6 hours
    private const val PREF_ANIME = "anpinime_anime_config"
    private const val KEY_CUSTOM_ANIME_URL = "custom_anime_url"
    private const val KEY_VERIFIED_ANIME_URL = "verified_anime_url"
    private const val KEY_VERIFIED_ANIME_AT = "verified_anime_at_ms"

    val animeFallbackHosts = listOf(
        "https://animevietsub.best",
        "https://animevietsub.vip",
        "https://animevietsub.news",
        "https://animevietsub.xyz",
        "https://animevietsub.fan",
        "https://animevietsub.bio",
        "https://animevietsub.sale"
    )

    private val legacyDefaultAnimeHosts = setOf(
        "https://animevietsub.vip",
        "https://animevietsub.lol",
        "https://animevietsub.cam",
        "https://animevietsub.tv",
        "https://animevietsub.cc",
        "https://animevietsub.show"
    )

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var resolvedAnimeBase: String? = null

    @Volatile
    private var resolvedAnimeAtMs: Long = 0L

    @Volatile
    private var cachedNguonCCategories: List<AnimeCategory>? = null

    @Volatile
    private var cachedKkCategories: List<AnimeCategory>? = null

    @Volatile
    private var cachedAetherCategories: List<AnimeCategory>? = null

    private val ioPool = Executors.newFixedThreadPool(6)

    private val syncedCookieJar = object : okhttp3.CookieJar {
        override fun saveFromResponse(url: okhttp3.HttpUrl, cookies: List<okhttp3.Cookie>) {
            if (cookies.isEmpty()) return
            runCatching {
                val urlStr = url.toString()
                for (c in cookies) {
                    WebViewHelper.persistOkHttpCookie(urlStr, "${c.name}=${c.value}")
                }
            }
        }

        override fun loadForRequest(url: okhttp3.HttpUrl): List<okhttp3.Cookie> {
            val header = runCatching { WebViewHelper.getCookieHeader(url.toString()) }.getOrNull()
                ?.takeIf { it.isNotBlank() } ?: return emptyList()
            val out = ArrayList<okhttp3.Cookie>()
            for (part in header.split(';')) {
                val kv = part.trim()
                val eq = kv.indexOf('=')
                if (eq <= 0) continue
                val name = kv.substring(0, eq).trim()
                val value = kv.substring(eq + 1).trim()
                if (name.isEmpty()) continue
                okhttp3.Cookie.Builder()
                    .name(name)
                    .value(value)
                    .domain(url.host)
                    .path("/")
                    .build()
                    .let(out::add)
            }
            return out
        }
    }

    val http: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(syncedCookieJar)
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    fun init(context: Context) {
        val app = context.applicationContext
        appContext = app
        WebViewHelper.init(app)
    }

    // --- Custom Anime Domain Management ---

    fun getCustomAnimeDomain(context: Context? = appContext): String? {
        val ctx = context ?: return null
        val prefs = ctx.getSharedPreferences(PREF_ANIME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_CUSTOM_ANIME_URL, null)?.trim()?.trimEnd('/') ?: return null
        if (raw.isEmpty() || legacyDefaultAnimeHosts.contains(raw.lowercase())) {
            prefs.edit().remove(KEY_CUSTOM_ANIME_URL).apply()
            return null
        }
        return raw
    }

    fun setCustomAnimeDomain(context: Context? = appContext, rawUrl: String?) {
        val ctx = context ?: return
        val prefs = ctx.getSharedPreferences(PREF_ANIME, Context.MODE_PRIVATE)
        val cleaned = rawUrl?.trim()?.trimEnd('/').orEmpty()
        if (cleaned.isEmpty() || legacyDefaultAnimeHosts.contains(cleaned.lowercase())) {
            prefs.edit().remove(KEY_CUSTOM_ANIME_URL).apply()
        } else {
            val normalized = if (cleaned.startsWith("http")) cleaned else "https://$cleaned"
            prefs.edit().putString(KEY_CUSTOM_ANIME_URL, normalized).apply()
        }
        resolvedAnimeBase = null
        resolvedAnimeAtMs = 0L
    }

    fun currentAnimeBaseUrl(): String {
        return getCustomAnimeDomain() ?: resolvedAnimeBase ?: animeFallbackHosts.first()
    }

    fun resolveAnimeDomain(forceRefresh: Boolean = false): String {
        getCustomAnimeDomain()?.let { return it }

        val now = System.currentTimeMillis()
        if (!forceRefresh) {
            resolvedAnimeBase?.let { cached ->
                if ((now - resolvedAnimeAtMs) < ANIME_DOMAIN_TTL_MS) return cached
            }
            appContext?.let { ctx ->
                val prefs = ctx.getSharedPreferences(PREF_ANIME, Context.MODE_PRIVATE)
                val savedUrl = prefs.getString(KEY_VERIFIED_ANIME_URL, null)?.trimEnd('/')
                val savedAt = prefs.getLong(KEY_VERIFIED_ANIME_AT, 0L)
                if (!savedUrl.isNullOrEmpty() && (now - savedAt) < ANIME_DOMAIN_TTL_MS) {
                    resolvedAnimeBase = savedUrl
                    resolvedAnimeAtMs = savedAt
                    return savedUrl
                }
            }
            // Fast cold-start path (matches DomainResolver.homeCandidate): resolve bit.ly redirects immediately
            // without stalling 12s on Bleach probes that may 403 until WebView solves Cloudflare on home load.
            val quickHost = resolveShortlinkCandidate()?.trimEnd('/')
                ?: animeFallbackHosts.first().trimEnd('/')
            resolvedAnimeBase = quickHost
            resolvedAnimeAtMs = now
            return quickHost
        }

        val candidates = LinkedHashSet<String>()
        resolveShortlinkCandidate()?.let { candidates.add(it.trimEnd('/')) }
        animeFallbackHosts.forEach { candidates.add(it.trimEnd('/')) }

        val candidateList = candidates.toList()
        val tasks = candidateList.map { host ->
            Callable {
                if (verifyAnimeCandidate(host)) host else null
            }
        }
        try {
            val futures = ioPool.invokeAll(tasks, 8, TimeUnit.SECONDS)
            for (i in futures.indices) {
                val verified = try {
                    futures[i].get()
                } catch (_: Throwable) {
                    null
                }
                if (!verified.isNullOrEmpty()) {
                    saveVerifiedAnimeDomain(verified)
                    return verified
                }
            }
        } catch (_: Throwable) {
        }

        val fallback = candidateList.firstOrNull() ?: animeFallbackHosts.first()
        resolvedAnimeBase = fallback
        resolvedAnimeAtMs = now
        return fallback
    }

    private fun resolveShortlinkCandidate(): String? {
        var current = ANIME_SHORTLINK
        val ua = animeUserAgent()
        try {
            for (hop in 0 until 6) {
                val conn = (URL(current).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    connectTimeout = 3500
                    readTimeout = 3500
                    setRequestProperty("User-Agent", ua)
                }
                val code = conn.responseCode
                val loc = conn.getHeaderField("Location")
                conn.disconnect()
                if (code in 300..399 && !loc.isNullOrBlank()) {
                    val nextUrl = URL(URL(current), loc).toString()
                    current = nextUrl
                } else {
                    break
                }
            }
            val uri = Uri.parse(current)
            val scheme = uri.scheme ?: "https"
            val host = uri.host
            if (!host.isNullOrBlank() && !host.contains("bit.ly", ignoreCase = true)) {
                return "$scheme://$host"
            }
        } catch (_: Throwable) {
        }
        return null
    }

    private fun verifyAnimeCandidate(baseHost: String): Boolean {
        val testUrl = "${baseHost.trimEnd('/')}$ANIME_VERIFY_PATH"
        val ua = animeUserAgent()
        val html = try {
            val req = Request.Builder()
                .url(testUrl)
                .header("User-Agent", ua)
                .header("Referer", "${baseHost.trimEnd('/')}/")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return false
                resp.body?.string().orEmpty()
            }
        } catch (_: Throwable) {
            return false
        }
        return HTMLParser.isVerifiedAnimeDetail(html, testUrl, baseHost)
    }

    private fun saveVerifiedAnimeDomain(host: String) {
        val now = System.currentTimeMillis()
        resolvedAnimeBase = host
        resolvedAnimeAtMs = now
        appContext?.getSharedPreferences(PREF_ANIME, Context.MODE_PRIVATE)
            ?.edit()
            ?.putString(KEY_VERIFIED_ANIME_URL, host)
            ?.putLong(KEY_VERIFIED_ANIME_AT, now)
            ?.apply()
    }

    // --- Categories ---

    fun getCategories(source: ContentSource): List<AnimeCategory> = when (source) {
        ContentSource.FILM4K -> {
            cachedNguonCCategories?.let { return it }
            val html = try {
                httpGet("${MovieSourceDirectory.DEFAULT_NGUONC_BASE_URL}/")
            } catch (_: Throwable) {
                ""
            }
            val parsed = MovieBrowseCatalog.nguoncCategoriesFromHtml(html)
            if (parsed.isNotEmpty()) cachedNguonCCategories = parsed
            parsed
        }

        ContentSource.AETHER -> {
            cachedAetherCategories?.let { return it }
            refreshAetherDomains()
            val cats = AetherCatalog.defaultCategories()
            cachedAetherCategories = cats
            cats
        }

        ContentSource.KKPHIM -> {
            cachedKkCategories?.let { return it }
            val base = MovieSourceDirectory.DEFAULT_KKPHIM_BASE_URL
            val genresFuture = ioPool.submit(Callable {
                try { httpGet("$base/the-loai") } catch (_: Throwable) { null }
            })
            val countriesFuture = ioPool.submit(Callable {
                try { httpGet("$base/quoc-gia") } catch (_: Throwable) { null }
            })
            val genresRaw = try { genresFuture.get(8, TimeUnit.SECONDS) } catch (_: Throwable) { null }
            val countriesRaw = try { countriesFuture.get(8, TimeUnit.SECONDS) } catch (_: Throwable) { null }
            val parsed = MovieBrowseCatalog.kkCategoriesFromApi(genresRaw, countriesRaw)
            if (parsed.isNotEmpty()) cachedKkCategories = parsed
            parsed
        }

        ContentSource.ANIME -> {
            val base = resolveAnimeDomain()
            val html = try {
                fetchAnimeHtml("$base/", base, allowWebFallback = false)
            } catch (_: Throwable) {
                ""
            }
            val parsed = HTMLParser.categories(html, base)
            if (parsed.size > 4) parsed else defaultAnimeCategories()
        }
    }

    // --- Home Sections ---

    fun loadHome(source: ContentSource): List<AnimeSection> {
        val raw = when (source) {
            ContentSource.FILM4K -> loadNguonCHome()
            ContentSource.AETHER -> loadAetherHome()
            ContentSource.KKPHIM -> loadKkPhimHome()
            ContentSource.ANIME -> loadAnimeHome()
        }
        return SourceIsolation.sections(source, raw)
    }

    // --- Category / List ---

    fun loadList(source: ContentSource, categoryPath: String, page: Int = 1): List<Anime> {
        val raw = when (source) {
            ContentSource.FILM4K -> loadNguonCList(categoryPath, page)
            ContentSource.AETHER -> loadAetherList(categoryPath, page)
            ContentSource.KKPHIM -> loadKkPhimList(categoryPath, page)
            ContentSource.ANIME -> loadAnimeList(categoryPath, page)
        }
        return SourceIsolation.items(source, raw)
    }

    // --- Search ---

    fun search(source: ContentSource, query: String, page: Int = 1): List<Anime> {
        val raw = when (source) {
            ContentSource.FILM4K -> searchNguonC(query, page)
            ContentSource.AETHER -> searchAether(query, page)
            ContentSource.KKPHIM -> searchKkPhim(query, page)
            ContentSource.ANIME -> searchAnime(query, page)
        }
        return SourceIsolation.items(source, raw)
    }

    // --- Detail ---

    fun loadDetail(anime: Anime): AnimeDetail = when (anime.source) {
        ContentSource.FILM4K -> loadNguonCDetail(anime)
        ContentSource.AETHER -> loadAetherDetail(anime)
        ContentSource.KKPHIM -> loadKkPhimDetail(anime)
        ContentSource.ANIME -> loadAnimeDetail(anime)
    }

    // --- Anime Schedule ---

    fun loadSchedule(): List<AnimeScheduleItem> {
        val base = resolveAnimeDomain()
        val url = "$base/lich-chieu-phim.html"
        val html = try {
            fetchAnimeHtml(url, base, allowWebFallback = false)
        } catch (_: Throwable) {
            ""
        }
        val parsed = HTMLParser.schedule(html, base)
        if (parsed.isNotEmpty()) return parsed
        return loadAnimeApiSchedule()
    }

    // --- Playback Resolution ---

    fun resolvePlayback(
        episode: Episode,
        preferredServerId: String = "auto",
        onProgress: ((sourceName: String, current: Int, total: Int) -> Unit)? = null
    ): ResolvedPlayback {
        return when (episode.source) {
            ContentSource.FILM4K -> resolveCatalogEpisodePlayback(
                episode = episode,
                defaultReferer = "${MovieSourceDirectory.DEFAULT_NGUONC_BASE_URL}/",
                format = MovieCatalogFormat.FREE2
            )
            ContentSource.KKPHIM -> resolveCatalogEpisodePlayback(
                episode = episode,
                defaultReferer = "${MovieSourceDirectory.DEFAULT_KKPHIM_BASE_URL}/",
                format = MovieCatalogFormat.FREE1
            )
            ContentSource.AETHER -> resolveAetherPlayback(episode, preferredServerId, onProgress)
            ContentSource.ANIME -> resolveAnimePlayback(episode)
        }
    }

    // =========================================================================
    // 1. NGUỒN 1: NGUONC (https://phim.nguonc.com) — No "Phim AI"
    // =========================================================================

    private fun loadNguonCHome(): List<AnimeSection> {
        val base = MovieSourceDirectory.DEFAULT_NGUONC_BASE_URL
        val configs = MovieCatalog.homeSectionsConfig(MovieCatalogFormat.FREE2)
        return fetchCatalogSectionsParallel(
            configs = configs,
            baseUrl = base,
            format = MovieCatalogFormat.FREE2,
            source = ContentSource.FILM4K,
            catalogLabel = null
        )
    }

    private fun loadNguonCList(path: String, page: Int): List<Anime> {
        val base = MovieSourceDirectory.DEFAULT_NGUONC_BASE_URL
        val cleanPath = path.trimStart('/')
        if (cleanPath.endsWith("phim-ai")) return emptyList()
        val sep = if (cleanPath.contains("?")) "&" else "?"
        val url = "$base/$cleanPath${sep}page=$page"
        val raw = try {
            httpGet(url, referer = "$base/")
        } catch (_: Throwable) {
            return emptyList()
        }
        return MovieCatalog.parseItems(
            rawJson = raw,
            baseUrl = base,
            format = MovieCatalogFormat.FREE2,
            source = ContentSource.FILM4K
        )
    }

    private fun searchNguonC(query: String, page: Int): List<Anime> {
        val base = MovieSourceDirectory.DEFAULT_NGUONC_BASE_URL
        val encoded = Uri.encode(query.trim())
        val url = "$base/api/films/search?keyword=$encoded&page=$page"
        val raw = try {
            httpGet(url, referer = "$base/")
        } catch (_: Throwable) {
            return emptyList()
        }
        return MovieCatalog.parseItems(
            rawJson = raw,
            baseUrl = base,
            format = MovieCatalogFormat.FREE2,
            source = ContentSource.FILM4K
        )
    }

    private fun loadNguonCDetail(anime: Anime): AnimeDetail {
        val base = MovieSourceDirectory.DEFAULT_NGUONC_BASE_URL
        val detailUrl = normalizeNguonCDetailUrl(anime.url, base)
        val raw = try {
            httpGet(detailUrl, referer = "$base/")
        } catch (_: Throwable) {
            ""
        }
        MovieCatalog.parseDetail(
            rawJson = raw,
            detailUrl = detailUrl,
            baseUrl = base,
            format = MovieCatalogFormat.FREE2,
            source = ContentSource.FILM4K
        )?.let { return it }

        return AnimeDetail(
            title = anime.title,
            posterUrl = anime.imageUrl,
            bannerUrl = anime.bannerUrl ?: anime.imageUrl,
            summary = anime.summary.ifEmpty { "Không tải được chi tiết phim từ Tú Nam." },
            info = anime.info,
            episodes = emptyList()
        )
    }

    private fun normalizeNguonCDetailUrl(rawUrl: String, baseUrl: String): String {
        val trimmed = rawUrl.trim()
        if (trimmed.contains("/api/film/")) return trimmed
        val slug = trimmed.trimEnd('/').substringAfterLast('/')
            .removeSuffix(".html")
            .ifEmpty { trimmed }
        return "${baseUrl.trimEnd('/')}/api/film/$slug"
    }

    // =========================================================================
    // 2. NGUỒN 2: AETHER (TMDB Catalog + Aether Official Domain Auto-Updater)
    // =========================================================================

    fun refreshAetherDomains(force: Boolean = false): List<String> {
        return AetherCatalog.refreshDomainsIfNeeded(
            context = appContext,
            force = force,
            fetcher = { url ->
                try {
                    httpGet(url)
                } catch (_: Throwable) {
                    null
                }
            },
            reachabilityProbe = { domain ->
                try {
                    val req = Request.Builder()
                        .url("$domain/")
                        .header("User-Agent", USER_AGENT)
                        .header("Accept", "text/html,application/xhtml+xml,*/*;q=0.8")
                        .build()
                    http.newCall(req).execute().use { resp ->
                        resp.isSuccessful && resp.request.url.isHttps
                    }
                } catch (_: Throwable) {
                    false
                }
            }
        )
    }

    private fun httpGetTmdb(fullPrimaryUrl: String): String {
        val headers = AetherCatalog.tmdbHeaders()
        fun executeUrl(targetUrl: String): String {
            val builder = Request.Builder()
                .url(targetUrl)
                .header("User-Agent", USER_AGENT)
            for ((k, v) in headers) {
                builder.header(k, v)
            }
            return http.newCall(builder.build()).execute().use { resp ->
                if (!resp.isSuccessful) throw RuntimeException("TMDB HTTP ${resp.code} for $targetUrl")
                resp.body?.string().orEmpty()
            }
        }
        return try {
            executeUrl(fullPrimaryUrl)
        } catch (_: Throwable) {
            val fallbackUrl = fullPrimaryUrl.replace(
                AetherCatalog.TMDB_PRIMARY_BASE_URL,
                AetherCatalog.TMDB_FALLBACK_BASE_URL
            )
            executeUrl(fallbackUrl)
        }
    }

    private fun loadAetherHome(): List<AnimeSection> {
        ioPool.submit {
            try { refreshAetherDomains(false) } catch (_: Throwable) {}
        }

        val futures = AetherCatalog.homeSectionsConfig.map { (title, path) ->
            Triple(title, path, ioPool.submit(Callable {
                try {
                    val url = AetherCatalog.buildTmdbEndpointUrl(path, page = 1, language = "vi-VN")
                    val raw = httpGetTmdb(url)
                    val defaultType = when {
                        path.contains("/tv") -> "tv"
                        path.contains("/movie") -> "movie"
                        else -> null
                    }
                    AetherCatalog.parseItems(raw, defaultType, ContentSource.AETHER)
                } catch (_: Throwable) {
                    emptyList()
                }
            }))
        }

        val sections = ArrayList<AnimeSection>()
        for ((title, path, fut) in futures) {
            val items = try { fut.get(10, TimeUnit.SECONDS) } catch (_: Throwable) { emptyList() }
            if (items.isNotEmpty()) {
                sections.add(AnimeSection(title = title, items = items, categoryPath = path))
            }
        }
        return sections
    }

    private fun loadAetherList(categoryPath: String, page: Int): List<Anime> {
        val clean = categoryPath.trim()
        val defaultType = when {
            clean.contains("/tv") -> "tv"
            clean.contains("/movie") -> "movie"
            else -> null
        }
        return try {
            val url = AetherCatalog.buildTmdbEndpointUrl(clean, page = page, language = "vi-VN")
            val raw = httpGetTmdb(url)
            AetherCatalog.parseItems(raw, defaultType, ContentSource.AETHER)
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun searchAether(query: String, page: Int): List<Anime> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()

        val primaryUrl = AetherCatalog.buildTmdbSearchUrl(trimmed, page = page, language = "vi-VN")
        val primaryItems = try {
            val raw = httpGetTmdb(primaryUrl)
            AetherCatalog.parseItems(raw, null, ContentSource.AETHER)
        } catch (_: Throwable) {
            emptyList()
        }
        if (primaryItems.isNotEmpty()) return primaryItems

        val asciiQuery = stripDiacritics(trimmed)
        if (asciiQuery.isNotEmpty() && !asciiQuery.equals(trimmed, ignoreCase = true)) {
            return try {
                val fallbackUrl = AetherCatalog.buildTmdbSearchUrl(asciiQuery, page = page, language = "en-US")
                val raw = httpGetTmdb(fallbackUrl)
                AetherCatalog.parseItems(raw, null, ContentSource.AETHER)
            } catch (_: Throwable) {
                emptyList()
            }
        }
        return emptyList()
    }

    private fun loadAetherDetail(anime: Anime): AnimeDetail {
        val domains = refreshAetherDomains(false)
        val primaryDomain = domains.firstOrNull() ?: AetherCatalog.FALLBACK_DOMAINS.first()
        val ref = AetherCatalog.parseMediaRef(anime.url)

        if (ref != null) {
            if (ref.mediaType == "tv") {
                val showViFuture = ioPool.submit(Callable {
                    try {
                        val url = AetherCatalog.buildTmdbEndpointUrl(
                            "tv/${ref.tmdbId}?append_to_response=credits,external_ids",
                            page = 1,
                            language = "vi-VN"
                        )
                        httpGetTmdb(url)
                    } catch (_: Throwable) {
                        ""
                    }
                })
                val showEnFuture = ioPool.submit(Callable {
                    try {
                        val url = AetherCatalog.buildTmdbEndpointUrl(
                            "tv/${ref.tmdbId}",
                            page = 1,
                            language = "en-US"
                        )
                        httpGetTmdb(url)
                    } catch (_: Throwable) {
                        ""
                    }
                })

                val rawVi = try { showViFuture.get(10, TimeUnit.SECONDS) } catch (_: Throwable) { "" }
                val rawEn = try { showEnFuture.get(8, TimeUnit.SECONDS) } catch (_: Throwable) { "" }
                val baseShowRaw = rawVi.ifBlank { rawEn }

                if (baseShowRaw.isNotBlank()) {
                    val enOverview = try {
                        if (rawEn.isNotBlank()) JSONObject(rawEn).optString("overview") else ""
                    } catch (_: Throwable) {
                        ""
                    }
                    val seasons = AetherCatalog.parseTvSeasonsList(baseShowRaw)
                    val seasonFutures = seasons.take(15).associate { sInfo ->
                        sInfo.seasonNumber to ioPool.submit(Callable {
                            try {
                                val sUrl = AetherCatalog.buildTmdbEndpointUrl(
                                    "tv/${ref.tmdbId}/season/${sInfo.seasonNumber}",
                                    page = 1,
                                    language = "vi-VN"
                                )
                                httpGetTmdb(sUrl)
                            } catch (_: Throwable) {
                                ""
                            }
                        })
                    }
                    val seasonPayloads = LinkedHashMap<Int, String>()
                    for ((sNum, fut) in seasonFutures) {
                        val sRaw = try { fut.get(8, TimeUnit.SECONDS) } catch (_: Throwable) { "" }
                        if (sRaw.isNotBlank()) {
                            seasonPayloads[sNum] = sRaw
                        }
                    }

                    AetherCatalog.parseTvDetail(
                        rawShowJson = baseShowRaw,
                        seasonPayloads = seasonPayloads,
                        fallbackOverview = enOverview.ifBlank { anime.summary },
                        primaryDomain = primaryDomain,
                        source = ContentSource.AETHER
                    )?.let { return it }
                }
            } else {
                val movieViFuture = ioPool.submit(Callable {
                    try {
                        val url = AetherCatalog.buildTmdbEndpointUrl(
                            "movie/${ref.tmdbId}?append_to_response=credits,external_ids",
                            page = 1,
                            language = "vi-VN"
                        )
                        httpGetTmdb(url)
                    } catch (_: Throwable) {
                        ""
                    }
                })
                val movieEnFuture = ioPool.submit(Callable {
                    try {
                        val url = AetherCatalog.buildTmdbEndpointUrl(
                            "movie/${ref.tmdbId}",
                            page = 1,
                            language = "en-US"
                        )
                        httpGetTmdb(url)
                    } catch (_: Throwable) {
                        ""
                    }
                })

                val rawVi = try { movieViFuture.get(10, TimeUnit.SECONDS) } catch (_: Throwable) { "" }
                val rawEn = try { movieEnFuture.get(8, TimeUnit.SECONDS) } catch (_: Throwable) { "" }
                val baseMovieRaw = rawVi.ifBlank { rawEn }

                if (baseMovieRaw.isNotBlank()) {
                    val enOverview = try {
                        if (rawEn.isNotBlank()) JSONObject(rawEn).optString("overview") else ""
                    } catch (_: Throwable) {
                        ""
                    }
                    AetherCatalog.parseMovieDetail(
                        rawJson = baseMovieRaw,
                        fallbackOverview = enOverview.ifBlank { anime.summary },
                        domains = domains,
                        source = ContentSource.AETHER
                    )?.let { return it }
                }
            }
        }

        return AnimeDetail(
            title = anime.title,
            posterUrl = anime.imageUrl,
            bannerUrl = anime.bannerUrl ?: anime.imageUrl,
            summary = anime.summary.ifEmpty { "Không tải được chi tiết phim từ nguồn Thúy An." },
            info = anime.info,
            episodes = emptyList()
        )
    }

    private fun resolveAetherPlayback(
        episode: Episode,
        preferredServerId: String = "auto",
        onProgress: ((sourceName: String, current: Int, total: Int) -> Unit)? = null
    ): ResolvedPlayback {
        return AetherSourceResolver.resolve(
            episode = episode,
            preferredServerId = preferredServerId,
            onProgress = onProgress
        )
    }

    // =========================================================================
    // 3. NGUỒN 3: KKPHIM (https://phimapi.com + https://kkphim.com)
    // =========================================================================

    private fun loadKkPhimHome(): List<AnimeSection> {
        val base = MovieSourceDirectory.DEFAULT_KKPHIM_BASE_URL
        val configs = MovieCatalog.homeSectionsConfig(MovieCatalogFormat.FREE1)
        return fetchCatalogSectionsParallel(
            configs = configs,
            baseUrl = base,
            format = MovieCatalogFormat.FREE1,
            source = ContentSource.KKPHIM,
            catalogLabel = null
        )
    }

    private fun loadKkPhimList(path: String, page: Int): List<Anime> {
        val apiBase = MovieSourceDirectory.DEFAULT_KKPHIM_BASE_URL
        val siteBase = MovieSourceDirectory.DEFAULT_KKPHIM_SITE_URL
        val cleanPath = path.trimStart('/')

        // Website-only lists (ongoing / completed series scraped from kkphim.com)
        if (cleanPath.startsWith("site/")) {
            val sitePath = cleanPath.removePrefix("site/").trimStart('/')
            val sep = if (sitePath.contains("?")) "&" else "?"
            val url = "$siteBase/$sitePath${sep}page=$page"
            val html = try {
                httpGet(url, referer = "$siteBase/")
            } catch (_: Throwable) {
                return emptyList()
            }
            return MovieBrowseCatalog.kkWebsiteItems(
                html = html,
                apiBaseUrl = apiBase,
                siteBaseUrl = siteBase,
                source = ContentSource.KKPHIM
            )
        }

        val sep = if (cleanPath.contains("?")) "&" else "?"
        val url = "$apiBase/$cleanPath${sep}page=$page&limit=24"
        val raw = try {
            httpGet(url, referer = "$apiBase/")
        } catch (_: Throwable) {
            return emptyList()
        }
        return MovieCatalog.parseItems(
            rawJson = raw,
            baseUrl = apiBase,
            format = MovieCatalogFormat.FREE1,
            source = ContentSource.KKPHIM
        )
    }

    private fun searchKkPhim(query: String, page: Int): List<Anime> {
        val base = MovieSourceDirectory.DEFAULT_KKPHIM_BASE_URL
        val encoded = Uri.encode(query.trim())
        val url = "$base/v1/api/tim-kiem?keyword=$encoded&page=$page&limit=24"
        val raw = try {
            httpGet(url, referer = "$base/")
        } catch (_: Throwable) {
            return emptyList()
        }
        return MovieCatalog.parseItems(
            rawJson = raw,
            baseUrl = base,
            format = MovieCatalogFormat.FREE1,
            source = ContentSource.KKPHIM
        )
    }

    private fun loadKkPhimDetail(anime: Anime): AnimeDetail {
        val base = MovieSourceDirectory.DEFAULT_KKPHIM_BASE_URL
        val detailUrl = normalizeKkPhimDetailUrl(anime.url, base)
        val raw = try {
            httpGet(detailUrl, referer = "$base/")
        } catch (_: Throwable) {
            ""
        }
        MovieCatalog.parseDetail(
            rawJson = raw,
            detailUrl = detailUrl,
            baseUrl = base,
            format = MovieCatalogFormat.FREE1,
            source = ContentSource.KKPHIM
        )?.let { return it }

        return AnimeDetail(
            title = anime.title,
            posterUrl = anime.imageUrl,
            bannerUrl = anime.bannerUrl ?: anime.imageUrl,
            summary = anime.summary.ifEmpty { "Không tải được chi tiết phim từ Cốm." },
            info = anime.info,
            episodes = emptyList()
        )
    }

    private fun normalizeKkPhimDetailUrl(rawUrl: String, baseUrl: String): String {
        val trimmed = rawUrl.trim()
        if (trimmed.startsWith("http") && trimmed.contains("/phim/")) return trimmed
        val slug = trimmed.trimEnd('/').substringAfterLast('/').removeSuffix(".html").ifEmpty { trimmed }
        return "${baseUrl.trimEnd('/')}/phim/$slug"
    }

    // =========================================================================
    // Shared Catalog Episode Stream Resolver (NguonC / KKPhim / Free1 / Free2)
    // =========================================================================

    private fun resolveCatalogEpisodePlayback(
        episode: Episode,
        defaultReferer: String,
        format: MovieCatalogFormat
    ): ResolvedPlayback {
        var primaryUrl = episode.directUrl.trim()
        var embedFallback = episode.refererUrl.trim()

        val detailUrl = episode.detailUrl.ifEmpty { episode.url }.trim()
        if (detailUrl.startsWith("http")) {
            val rawDetail = try {
                httpGet(detailUrl, referer = defaultReferer)
            } catch (_: Throwable) {
                ""
            }
            if (rawDetail.isNotBlank()) {
                val match = MovieCatalog.extractStreamFromDetail(rawDetail, episode)
                if (match != null) {
                    primaryUrl = match.primaryUrl
                    if (match.embedFallbackUrl.isNotEmpty()) {
                        embedFallback = match.embedFallbackUrl
                    }
                }
            }
        }

        if (primaryUrl.isEmpty()) {
            throw IllegalStateException("Không tìm thấy luồng phát cho tập này.")
        }

        val isDirectMedia = primaryUrl.contains(".m3u8", ignoreCase = true) ||
            primaryUrl.contains(".mp4", ignoreCase = true) ||
            primaryUrl.contains(".mkv", ignoreCase = true)

        if (isDirectMedia) {
            val origin = defaultReferer.trimEnd('/')
            val headers = LinkedHashMap<String, String>()
            headers["User-Agent"] = USER_AGENT
            headers["Referer"] = defaultReferer
            headers["Origin"] = origin
            return ResolvedPlayback(
                url = primaryUrl,
                headers = headers,
                isF4kWrapped = primaryUrl.contains(".f4k", ignoreCase = true) ||
                    primaryUrl.contains("cryboiz.workers.dev", ignoreCase = true),
                isWeb = false,
                webUrl = embedFallback,
                mediaKind = Phim4KMediaProbe.mediaKind(primaryUrl)
            )
        }

        // Embed URL (e.g. embed*.streamc.xyz/embed.php?hash=... or player embed):
        // Try StreamC bootstrap/issue API first (works even without HTML via hash in URL), then literal .m3u8 extraction.
        try {
            if (StreamCResolver.isStreamCEmbed(primaryUrl)) {
                StreamCResolver.extractFromHtml(
                    embedUrl = primaryUrl,
                    html = "",
                    referer = defaultReferer,
                    http = http,
                    userAgent = USER_AGENT
                )?.let { return it }
            }

            val embedHtml = httpGet(primaryUrl, referer = defaultReferer)
            StreamCResolver.extractFromHtml(
                embedUrl = primaryUrl,
                html = embedHtml,
                referer = defaultReferer,
                http = http,
                userAgent = USER_AGENT
            )?.let { return it }

            val candidates = PlaybackSourceParser.extractStreamCandidates(embedHtml, primaryUrl)
            val bestDirect = candidates.firstOrNull {
                it.contains(".m3u8", ignoreCase = true) || it.contains(".mp4", ignoreCase = true)
            }
            if (!bestDirect.isNullOrEmpty()) {
                val embedOrigin = try {
                    val u = Uri.parse(primaryUrl)
                    "${u.scheme}://${u.host}"
                } catch (_: Throwable) {
                    defaultReferer.trimEnd('/')
                }
                return ResolvedPlayback(
                    url = bestDirect,
                    headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Referer" to primaryUrl,
                        "Origin" to embedOrigin
                    ),
                    isWeb = false,
                    webUrl = primaryUrl,
                    mediaKind = Phim4KMediaProbe.mediaKind(bestDirect)
                )
            }
        } catch (_: Throwable) {
        }

        return ResolvedPlayback(
            url = primaryUrl,
            headers = mapOf("User-Agent" to USER_AGENT, "Referer" to defaultReferer),
            isWeb = true,
            webUrl = primaryUrl
        )
    }

    private fun fetchCatalogSectionsParallel(
        configs: List<Pair<String, String>>,
        baseUrl: String,
        format: MovieCatalogFormat,
        source: ContentSource,
        catalogLabel: String?,
        categoryPrefix: String = ""
    ): List<AnimeSection> {
        val cleanBase = baseUrl.trimEnd('/')
        val futures = configs.map { (title, path) ->
            Triple(title, path, ioPool.submit(Callable {
                try {
                    val cleanPath = path.trimStart('/')
                    val sep = if (cleanPath.contains("?")) "&" else "?"
                    val url = if (format == MovieCatalogFormat.FREE1) {
                        "$cleanBase/$cleanPath${sep}page=1&limit=18"
                    } else {
                        "$cleanBase/$cleanPath${sep}page=1"
                    }
                    val raw = httpGet(url, referer = "$cleanBase/")
                    MovieCatalog.parseItems(raw, cleanBase, format, source, catalogLabel)
                } catch (_: Throwable) {
                    emptyList()
                }
            }))
        }

        val sections = ArrayList<AnimeSection>()
        for ((title, path, fut) in futures) {
            val items = try { fut.get(10, TimeUnit.SECONDS) } catch (_: Throwable) { emptyList() }
            if (items.isNotEmpty()) {
                sections.add(AnimeSection(title, items, "$categoryPrefix$path"))
            }
        }
        return sections
    }

    // =========================================================================
    // 4. ANIMEVIETSUB (DomainResolver + HTMLParser + Next.js + AJAX Decoder)
    // =========================================================================

    fun animeUserAgent(): String {
        return runCatching { WebViewHelper.userAgent.takeIf { it.isNotBlank() } }.getOrNull() ?: USER_AGENT
    }

    private fun fetchAnimeHtml(
        url: String,
        base: String,
        allowWebFallback: Boolean = true,
        waitForEpisodes: Boolean = false
    ): String {
        val ua = animeUserAgent()
        val html = try {
            httpGet(url, referer = "$base/", userAgent = ua)
        } catch (_: Throwable) {
            ""
        }
        val needsChallenge = html.length <= 600 || runCatching { WebViewHelper.containsVerification(html) }.getOrDefault(false) ||
            html.contains("Just a moment", ignoreCase = true) ||
            html.contains("Xác Minh An Toàn", ignoreCase = true) ||
            html.contains("Đang Kiểm Tra", ignoreCase = true) ||
            html.contains("_cf_chl_opt", ignoreCase = true) ||
            html.contains("cf-browser-verification", ignoreCase = true) ||
            html.contains("Attention Required!", ignoreCase = true)
        if (!needsChallenge) return html
        if (!allowWebFallback) return html
        val webHtml = try {
            kotlinx.coroutines.runBlocking {
                WebViewHelper.fetchHtml(url, waitForEpisodes = waitForEpisodes).first
            }
        } catch (_: Throwable) {
            ""
        }
        return if (webHtml.length > html.length) webHtml else html
    }

    private fun defaultAnimeCategories(): List<AnimeCategory> = listOf(
        // Danh mục chính
        AnimeCategory("Anime Nhật Bản", "v1/api/danh-sach/hoat-hinh?country=nhat-ban&sort_field=modified.time&sort_type=desc", "Danh mục"),
        AnimeCategory("Anime Mới Cập Nhật", "v1/api/danh-sach/hoat-hinh?sort_field=modified.time&sort_type=desc", "Danh mục"),
        AnimeCategory("Anime Server Tú Nam", "nguonc:api/films/the-loai/hoat-hinh", "Danh mục"),
        AnimeCategory("Anime Thuyết Minh", "v1/api/danh-sach/phim-thuyet-minh?category=hoat-hinh&sort_field=modified.time&sort_type=desc", "Danh mục"),
        AnimeCategory("Anime Lồng Tiếng", "v1/api/danh-sach/phim-long-tieng?category=hoat-hinh&sort_field=modified.time&sort_type=desc", "Danh mục"),
        AnimeCategory("Hoạt Hình 3D / Trung Quốc", "v1/api/danh-sach/hoat-hinh?country=trung-quoc&sort_field=modified.time&sort_type=desc", "Danh mục"),
        AnimeCategory("Anime Điện Ảnh (Movie)", "v1/api/danh-sach/phim-le?category=hoat-hinh&sort_field=modified.time&sort_type=desc", "Danh mục"),
        AnimeCategory("Anime Bộ Dài Tập", "v1/api/danh-sach/phim-bo?category=hoat-hinh&sort_field=modified.time&sort_type=desc", "Danh mục"),
        // Thể loại Anime
        AnimeCategory("Hành Động", "v1/api/danh-sach/hoat-hinh?category=hanh-dong&sort_field=modified.time&sort_type=desc", "Thể loại"),
        AnimeCategory("Phiêu Lưu", "v1/api/danh-sach/hoat-hinh?category=phieu-luu&sort_field=modified.time&sort_type=desc", "Thể loại"),
        AnimeCategory("Viễn Tưởng / Phép Thuật", "v1/api/danh-sach/hoat-hinh?category=vien-tuong&sort_field=modified.time&sort_type=desc", "Thể loại"),
        AnimeCategory("Bí Ẩn / Siêu Nhiên", "v1/api/danh-sach/hoat-hinh?category=bi-an&sort_field=modified.time&sort_type=desc", "Thể loại"),
        AnimeCategory("Hài Hước", "v1/api/danh-sach/hoat-hinh?category=hai-huoc&sort_field=modified.time&sort_type=desc", "Thể loại"),
        AnimeCategory("Tình Cảm / Học Đường", "v1/api/danh-sach/hoat-hinh?category=tinh-cam&sort_field=modified.time&sort_type=desc", "Thể loại"),
        AnimeCategory("Cổ Trang / Tu Tiên", "v1/api/danh-sach/hoat-hinh?category=co-trang&sort_field=modified.time&sort_type=desc", "Thể loại"),
        AnimeCategory("Chính Kịch", "v1/api/danh-sach/hoat-hinh?category=chinh-kich&sort_field=modified.time&sort_type=desc", "Thể loại"),
        AnimeCategory("Kinh Dị", "v1/api/danh-sach/hoat-hinh?category=kinh-di&sort_field=modified.time&sort_type=desc", "Thể loại"),
        // Năm phát hành
        AnimeCategory("Anime 2026", "v1/api/danh-sach/hoat-hinh?year=2026&sort_field=modified.time&sort_type=desc", "Năm"),
        AnimeCategory("Anime 2025", "v1/api/danh-sach/hoat-hinh?year=2025&sort_field=modified.time&sort_type=desc", "Năm"),
        AnimeCategory("Anime 2024", "v1/api/danh-sach/hoat-hinh?year=2024&sort_field=modified.time&sort_type=desc", "Năm"),
        AnimeCategory("Anime 2023", "v1/api/danh-sach/hoat-hinh?year=2023&sort_field=modified.time&sort_type=desc", "Năm"),
        AnimeCategory("Anime 2022", "v1/api/danh-sach/hoat-hinh?year=2022&sort_field=modified.time&sort_type=desc", "Năm")
    )

    private fun loadAnimeHome(): List<AnimeSection> {
        val sections = ArrayList<AnimeSection>()

        // 1. Primary multi-server Anime API sections (fast <1s, Cloudflare-free, FHD streams)
        val kkBase = MovieSourceDirectory.DEFAULT_KKPHIM_BASE_URL
        val nguonBase = MovieSourceDirectory.DEFAULT_NGUONC_BASE_URL

        val apiConfigs = listOf(
            "🔥 Anime Nhật Bản Mới" to ("kk" to "v1/api/danh-sach/hoat-hinh?country=nhat-ban&sort_field=modified.time&sort_type=desc"),
            "✨ Anime Mới Cập Nhật" to ("kk" to "v1/api/danh-sach/hoat-hinh?sort_field=modified.time&sort_type=desc"),
            "⚡ Anime Server Tú Nam" to ("nguonc" to "api/films/the-loai/hoat-hinh"),
            "🎙️ Anime Thuyết Minh" to ("kk" to "v1/api/danh-sach/phim-thuyet-minh?category=hoat-hinh&sort_field=modified.time&sort_type=desc"),
            "🔊 Anime Lồng Tiếng" to ("kk" to "v1/api/danh-sach/phim-long-tieng?category=hoat-hinh&sort_field=modified.time&sort_type=desc"),
            "🐉 Hoạt Hình 3D / Trung Quốc" to ("kk" to "v1/api/danh-sach/hoat-hinh?country=trung-quoc&sort_field=modified.time&sort_type=desc"),
            "🎬 Anime Điện Ảnh (Movie)" to ("kk" to "v1/api/danh-sach/phim-le?category=hoat-hinh&sort_field=modified.time&sort_type=desc")
        )

        val futures = apiConfigs.map { (title, spec) ->
            val (provider, path) = spec
            val catPath = if (provider == "nguonc") "nguonc:$path" else path
            Triple(title, catPath, ioPool.submit(Callable {
                try {
                    if (provider == "nguonc") {
                        val url = "$nguonBase/${path.trimStart('/')}?page=1"
                        val raw = httpGet(url, referer = "$nguonBase/")
                        MovieCatalog.parseItems(raw, nguonBase, MovieCatalogFormat.FREE2, ContentSource.ANIME)
                    } else {
                        val cleanPath = path.trimStart('/')
                        val sep = if (cleanPath.contains("?")) "&" else "?"
                        val url = "$kkBase/$cleanPath${sep}page=1&limit=18"
                        val raw = httpGet(url, referer = "$kkBase/")
                        MovieCatalog.parseItems(raw, kkBase, MovieCatalogFormat.FREE1, ContentSource.ANIME)
                    }
                } catch (_: Throwable) {
                    emptyList()
                }
            }))
        }

        // Also check AnimeVietsub non-blockingly in case user has an active cf_clearance cookie
        val avFuture = ioPool.submit(Callable {
            try {
                val base = resolveAnimeDomain()
                val html = fetchAnimeHtml("$base/", base, allowWebFallback = false)
                HTMLParser.sections(html, base)
            } catch (_: Throwable) {
                emptyList()
            }
        })

        for ((title, catPath, fut) in futures) {
            val items = try { fut.get(10, TimeUnit.SECONDS) } catch (_: Throwable) { emptyList() }
            if (items.isNotEmpty()) {
                sections.add(AnimeSection(title, items, catPath))
            }
        }

        val avSections = try { avFuture.get(3, TimeUnit.SECONDS) } catch (_: Throwable) { emptyList() }
        if (avSections.isNotEmpty()) {
            sections.addAll(avSections)
        }

        return sections
    }

    private fun loadAnimeList(path: String, page: Int): List<Anime> {
        val trimmed = path.trim()
        val kkBase = MovieSourceDirectory.DEFAULT_KKPHIM_BASE_URL
        val nguonBase = MovieSourceDirectory.DEFAULT_NGUONC_BASE_URL

        if (trimmed.startsWith("nguonc:", ignoreCase = true)) {
            val subPath = trimmed.substringAfter(':').trimStart('/')
            val sep = if (subPath.contains("?")) "&" else "?"
            val url = "$nguonBase/$subPath${sep}page=$page"
            val raw = try { httpGet(url, referer = "$nguonBase/") } catch (_: Throwable) { return emptyList() }
            return MovieCatalog.parseItems(raw, nguonBase, MovieCatalogFormat.FREE2, ContentSource.ANIME)
        }

        val isApiPath = trimmed.isEmpty() ||
            trimmed.startsWith("v1/api/", ignoreCase = true) ||
            trimmed.startsWith("/v1/api/", ignoreCase = true) ||
            trimmed.contains("danh-sach/hoat-hinh", ignoreCase = true)

        if (isApiPath) {
            val cleanPath = trimmed.trimStart('/').ifEmpty { "v1/api/danh-sach/hoat-hinh?country=nhat-ban&sort_field=modified.time&sort_type=desc" }
            val sep = if (cleanPath.contains("?")) "&" else "?"
            val url = "$kkBase/$cleanPath${sep}page=$page&limit=24"
            val raw = try { httpGet(url, referer = "$kkBase/") } catch (_: Throwable) { return emptyList() }
            return MovieCatalog.parseItems(raw, kkBase, MovieCatalogFormat.FREE1, ContentSource.ANIME)
        }

        val base = resolveAnimeDomain()
        val cleanPath = if (trimmed.startsWith("http")) {
            try { Uri.parse(trimmed).path ?: "/anime-moi/" } catch (_: Throwable) { "/anime-moi/" }
        } else {
            trimmed
        }
        val url = if (page <= 1) {
            "$base${cleanPath}"
        } else if (cleanPath.endsWith(".html")) {
            "$base${cleanPath}?page=$page"
        } else {
            "$base${cleanPath.trimEnd('/')}/trang-$page.html"
        }
        val html = fetchAnimeHtml(url, base, allowWebFallback = false)
        val parsed = HTMLParser.home(html, base)
        if (parsed.isNotEmpty()) return parsed

        // Fallback to Japanese Anime catalog if AnimeVietsub path is blocked
        val fallbackUrl = "$kkBase/v1/api/danh-sach/hoat-hinh?country=nhat-ban&page=$page&limit=24"
        val raw = try { httpGet(fallbackUrl, referer = "$kkBase/") } catch (_: Throwable) { return emptyList() }
        return MovieCatalog.parseItems(raw, kkBase, MovieCatalogFormat.FREE1, ContentSource.ANIME)
    }

    private fun searchAnime(query: String, page: Int): List<Anime> {
        val cleanQuery = query.trim()
        if (cleanQuery.isEmpty()) return emptyList()
        val encoded = Uri.encode(cleanQuery)
        val kkBase = MovieSourceDirectory.DEFAULT_KKPHIM_BASE_URL
        val nguonBase = MovieSourceDirectory.DEFAULT_NGUONC_BASE_URL

        val kkFuture = ioPool.submit(Callable {
            try {
                val raw = httpGet("$kkBase/v1/api/tim-kiem?keyword=$encoded&page=$page&limit=24", referer = "$kkBase/")
                MovieCatalog.parseItems(raw, kkBase, MovieCatalogFormat.FREE1, ContentSource.ANIME)
            } catch (_: Throwable) {
                emptyList()
            }
        })
        val nguonFuture = ioPool.submit(Callable {
            try {
                val raw = httpGet("$nguonBase/api/films/search?keyword=$encoded&page=$page", referer = "$nguonBase/")
                MovieCatalog.parseItems(raw, nguonBase, MovieCatalogFormat.FREE2, ContentSource.ANIME)
            } catch (_: Throwable) {
                emptyList()
            }
        })
        val avFuture = ioPool.submit(Callable {
            try {
                val base = resolveAnimeDomain()
                val slug = cleanQuery.lowercase().replace(Regex("\\s+"), "+")
                val url = if (page <= 1) "$base/tim-kiem/$slug/" else "$base/tim-kiem/$slug/trang-$page.html"
                val html = fetchAnimeHtml(url, base, allowWebFallback = false)
                HTMLParser.search(html, base)
            } catch (_: Throwable) {
                emptyList()
            }
        })

        val kkItems = try { kkFuture.get(8, TimeUnit.SECONDS) } catch (_: Throwable) { emptyList() }
        val nguonItems = try { nguonFuture.get(8, TimeUnit.SECONDS) } catch (_: Throwable) { emptyList() }
        val avItems = try { avFuture.get(3, TimeUnit.SECONDS) } catch (_: Throwable) { emptyList() }

        val merged = ArrayList<Anime>()
        val seenKeys = HashSet<String>()
        for (item in (avItems + kkItems + nguonItems)) {
            val slugKey = item.url.trimEnd('/').substringAfterLast('/').removeSuffix(".html").lowercase()
            val key = slugKey.ifBlank { item.url.lowercase() }
            if (seenKeys.add(key)) {
                merged.add(item.copy(source = ContentSource.ANIME))
            }
        }
        return merged
    }

    private fun loadAnimeApiSchedule(): List<AnimeScheduleItem> {
        val kkBase = MovieSourceDirectory.DEFAULT_KKPHIM_BASE_URL
        val url = "$kkBase/v1/api/danh-sach/hoat-hinh?country=nhat-ban&sort_field=modified.time&sort_type=desc&page=1&limit=36"
        val items = try {
            val raw = httpGet(url, referer = "$kkBase/")
            MovieCatalog.parseItems(raw, kkBase, MovieCatalogFormat.FREE1, ContentSource.ANIME)
        } catch (_: Throwable) {
            emptyList()
        }
        if (items.isEmpty()) return emptyList()
        val days = DayOfWeek.entries
        return items.mapIndexed { idx, anime ->
            val day = days[idx % days.size]
            val hour = 18 + (idx % 6)
            val min = if (idx % 2 == 0) "00" else "30"
            AnimeScheduleItem(
                id = "${day.rawValue}-${anime.url}",
                title = anime.title,
                url = anime.url,
                episodeText = anime.episodeText.ifBlank { "Mới cập nhật" },
                timeText = "$hour:$min",
                dayOfWeek = day
            )
        }
    }

    private fun loadAnimeDetail(anime: Anime): AnimeDetail {
        val rawUrl = anime.url.trim()
        val kkBase = MovieSourceDirectory.DEFAULT_KKPHIM_BASE_URL
        val nguonBase = MovieSourceDirectory.DEFAULT_NGUONC_BASE_URL

        val isKkUrl = rawUrl.contains("phimapi.com", ignoreCase = true) ||
            rawUrl.contains("kkphim", ignoreCase = true)
        val isNguonUrl = rawUrl.contains("nguonc.com", ignoreCase = true)

        if (isKkUrl || isNguonUrl) {
            val slug = rawUrl.trimEnd('/').substringAfterLast('/').removeSuffix(".html")

            val kkFuture = ioPool.submit(Callable {
                try {
                    val detailUrl = if (isKkUrl) normalizeKkPhimDetailUrl(rawUrl, kkBase) else "$kkBase/phim/$slug"
                    val raw = httpGet(detailUrl, referer = "$kkBase/")
                    MovieCatalog.parseDetail(raw, detailUrl, kkBase, MovieCatalogFormat.FREE1, ContentSource.ANIME)
                } catch (_: Throwable) {
                    null
                }
            })

            val nguonFuture = ioPool.submit(Callable {
                try {
                    val detailUrl = if (isNguonUrl) normalizeNguonCDetailUrl(rawUrl, nguonBase) else "$nguonBase/api/film/$slug"
                    val raw = httpGet(detailUrl, referer = "$nguonBase/")
                    MovieCatalog.parseDetail(raw, detailUrl, nguonBase, MovieCatalogFormat.FREE2, ContentSource.ANIME)
                } catch (_: Throwable) {
                    null
                }
            })

            val kkDetail = try { kkFuture.get(8, TimeUnit.SECONDS) } catch (_: Throwable) { null }
            val nguonDetail = try { nguonFuture.get(8, TimeUnit.SECONDS) } catch (_: Throwable) { null }

            val primary = if (isKkUrl) (kkDetail ?: nguonDetail) else (nguonDetail ?: kkDetail)
            if (primary != null) {
                val combinedEps = ArrayList<Episode>()
                if (kkDetail != null && nguonDetail != null) {
                    val kkEps = kkDetail.episodes.map { ep ->
                        val sv = if (ep.season.startsWith("Cốm", true)) ep.season else "Cốm · ${ep.season}"
                        ep.copy(season = sv, serverName = sv, source = ContentSource.ANIME)
                    }
                    val nguonEps = nguonDetail.episodes.map { ep ->
                        val sv = if (ep.season.startsWith("Tú Nam", true)) ep.season else "Tú Nam · ${ep.season}"
                        ep.copy(season = sv, serverName = sv, source = ContentSource.ANIME)
                    }
                    if (isKkUrl) {
                        combinedEps.addAll(kkEps)
                        combinedEps.addAll(nguonEps)
                    } else {
                        combinedEps.addAll(nguonEps)
                        combinedEps.addAll(kkEps)
                    }
                } else {
                    combinedEps.addAll(primary.episodes.map { it.copy(source = ContentSource.ANIME) })
                }
                return primary.copy(
                    title = primary.title.ifEmpty { anime.title },
                    posterUrl = primary.posterUrl ?: anime.imageUrl,
                    bannerUrl = primary.bannerUrl ?: anime.bannerUrl ?: anime.imageUrl,
                    episodes = combinedEps
                )
            }
        }

        val base = resolveAnimeDomain()
        val pageUrl = rewriteAnimeHostIfNeeded(rawUrl, base)
        val detailHtml = fetchAnimeHtml(pageUrl, base, allowWebFallback = true, waitForEpisodes = true)
        val parsed = HTMLParser.detail(detailHtml, pageUrl, base)

        var episodes = parsed.episodes
        val watchUrl = parsed.watchUrl ?: episodes.minByOrNull { if (it.number > 0) it.number else Int.MAX_VALUE }?.url

        // On AnimeVietsub detail pages, full episode list + data-hash/filmID often live on watchUrl
        val needsWatchFetch = episodes.isEmpty() ||
            episodes.all { it.animeHash.isEmpty() } ||
            !HTMLParser.hasCompleteStreamedEpisodes(detailHtml, pageUrl, base, episodes)
        if (needsWatchFetch && !watchUrl.isNullOrBlank() && !watchUrl.equals(pageUrl, ignoreCase = true)) {
            try {
                val watchHtml = fetchAnimeHtml(watchUrl, base, allowWebFallback = true, waitForEpisodes = true)
                val watchEpisodes = HTMLParser.episodes(watchHtml, pageUrl, base)
                episodes = HTMLParser.mergedEpisodes(primary = watchEpisodes, fallback = episodes)
            } catch (_: Throwable) {
            }
        }
        if (episodes.isNotEmpty() || parsed.title.isNotEmpty()) {
            saveVerifiedAnimeDomain(base)
        }

        // If AnimeVietsub page was blocked by Cloudflare, automatically match by title on the Anime API Engine!
        if (episodes.isEmpty() && anime.title.isNotBlank()) {
            val match = searchAnime(anime.title, 1).firstOrNull {
                !it.url.contains("animevietsub", ignoreCase = true)
            }
            if (match != null) {
                return loadAnimeDetail(match)
            }
        }

        if (episodes.isEmpty() && !watchUrl.isNullOrBlank()) {
            episodes = listOf(
                Episode(
                    title = "Tập 1",
                    url = watchUrl,
                    number = 1,
                    season = "Vietsub",
                    qualityBadge = "HD · Vietsub",
                    source = ContentSource.ANIME,
                    playbackKind = "anime",
                    refererUrl = pageUrl,
                    detailUrl = pageUrl,
                    serverName = "Vietsub"
                )
            )
        }

        return parsed.copy(
            title = parsed.title.ifEmpty { anime.title },
            posterUrl = parsed.posterUrl ?: anime.imageUrl,
            bannerUrl = parsed.bannerUrl ?: anime.bannerUrl ?: anime.imageUrl,
            episodes = episodes
        )
    }

    private fun resolveAnimePlayback(episode: Episode): ResolvedPlayback {
        val rawUrl = episode.url.trim()
        val directUrl = episode.directUrl.trim()
        val detailUrl = episode.detailUrl.trim()

        if (episode.playbackKind == "catalog_episode" ||
            rawUrl.contains("phimapi.com", ignoreCase = true) ||
            rawUrl.contains("nguonc.com", ignoreCase = true) ||
            detailUrl.contains("phimapi.com", ignoreCase = true) ||
            detailUrl.contains("nguonc.com", ignoreCase = true) ||
            directUrl.contains("streamc.xyz", ignoreCase = true) ||
            directUrl.contains(".m3u8", ignoreCase = true)
        ) {
            val isNguon = rawUrl.contains("nguonc.com", ignoreCase = true) ||
                detailUrl.contains("nguonc.com", ignoreCase = true) ||
                directUrl.contains("streamc.xyz", ignoreCase = true)
            return resolveCatalogEpisodePlayback(
                episode = episode,
                defaultReferer = if (isNguon) {
                    "${MovieSourceDirectory.DEFAULT_NGUONC_BASE_URL}/"
                } else {
                    "${MovieSourceDirectory.DEFAULT_KKPHIM_BASE_URL}/"
                },
                format = if (isNguon) MovieCatalogFormat.FREE2 else MovieCatalogFormat.FREE1
            )
        }

        val base = resolveAnimeDomain()
        val epUrl = rewriteAnimeHostIfNeeded(episode.url, base)
        val ua = animeUserAgent()

        if (episode.directUrl.startsWith("http")) {
            return ResolvedPlayback(
                url = episode.directUrl,
                headers = mapOf("User-Agent" to ua, "Referer" to epUrl),
                isWeb = false,
                webUrl = epUrl,
                mediaKind = Phim4KMediaProbe.mediaKind(episode.directUrl)
            )
        }

        try {
            val html = fetchAnimeHtml(epUrl, base)
            var hash = episode.animeHash
            var filmId = episode.animeFilmId

            if (hash.isEmpty()) {
                hash = Regex("""AnimeVsub\s*\(\s*["']([a-zA-Z0-9+/=_-]{8,})["']""")
                    .find(html)?.groupValues?.get(1)
                    ?: Regex("""class=["'][^"']*\bactive\b[^"']*["'][^>]*data-(?:hash|play|id)=["']([a-zA-Z0-9+/=_-]{8,})["']""")
                        .find(html)?.groupValues?.get(1)
                    ?: Regex("""data-(?:hash|play)=["']([a-zA-Z0-9+/=_-]{8,})["']""")
                        .find(html)?.groupValues?.get(1)
                    ?: Regex("""data-id=["']([a-zA-Z0-9+/=_-]{10,})["']""")
                        .find(html)?.groupValues?.get(1).orEmpty()
            }
            if (filmId.isEmpty()) {
                filmId = Regex(
                    """(?:filmInfo\s*\.\s*filmID|filmId|movie_id|id_film)\s*[:=]\s*(?:parseInt\s*\(\s*)?["']?(\d+)["']?""",
                    RegexOption.IGNORE_CASE
                ).find(html)?.groupValues?.get(1)
                    ?: Regex("""-a(\d+)(?:/|\.html|$)""", RegexOption.IGNORE_CASE).find(epUrl)?.groupValues?.get(1)
                    ?: ""
            }
            val effectiveFilmId = filmId.ifEmpty { "1" }

            if (hash.isNotEmpty()) {
                val ajaxUrl = "$base/ajax/player?v=2019a"
                val form = FormBody.Builder()
                    .add("link", hash)
                    .add("id", effectiveFilmId)
                    .build()
                val req = Request.Builder()
                    .url(ajaxUrl)
                    .post(form)
                    .header("User-Agent", ua)
                    .header("Referer", epUrl)
                    .header("Origin", base.trimEnd('/'))
                    .header("X-Requested-With", "XMLHttpRequest")
                    .build()
                val resolvedAjax: ResolvedPlayback? = try {
                    http.newCall(req).execute().use { resp ->
                        if (resp.isSuccessful) {
                            val body = resp.body?.string().orEmpty()
                            if (body.isNotEmpty()) {
                                decryptAnimePlayerPayload(body, base.trimEnd('/'), epUrl)
                            } else null
                        } else null
                    }
                } catch (_: Throwable) {
                    null
                }
                if (resolvedAjax != null) return resolvedAjax

                // Crucial fallback when Cloudflare blocks OkHttp POST: execute AJAX inside cleared WebView origin
                val webAjax = try {
                    kotlinx.coroutines.runBlocking {
                        WebViewHelper.resolveAnimeAjaxInWebView(epUrl, effectiveFilmId, hash)
                    }
                } catch (_: Throwable) {
                    null
                }
                if (webAjax != null) return webAjax
            }

            val directInHtml = PlaybackSourceParser.extractStreamCandidates(html, base)
                .firstOrNull { !it.contains("trailer", ignoreCase = true) }
            if (!directInHtml.isNullOrEmpty()) {
                return ResolvedPlayback(
                    url = directInHtml,
                    headers = mapOf("User-Agent" to ua, "Referer" to epUrl),
                    isWeb = false,
                    webUrl = epUrl,
                    mediaKind = Phim4KMediaProbe.mediaKind(directInHtml)
                )
            }
        } catch (_: Throwable) {
        }

        return ResolvedPlayback(
            url = epUrl,
            headers = mapOf("User-Agent" to ua, "Referer" to "$base/"),
            isWeb = true,
            webUrl = epUrl
        )
    }

    private fun rewriteAnimeHostIfNeeded(url: String, currentBase: String): String {
        val trimmed = url.trim()
        if (!trimmed.startsWith("http")) {
            return "${currentBase.trimEnd('/')}/${trimmed.trimStart('/')}"
        }
        return try {
            val uri = Uri.parse(trimmed)
            val host = uri.host?.lowercase().orEmpty()
            if (host.contains("animevietsub")) {
                val baseUri = Uri.parse(currentBase)
                uri.buildUpon()
                    .scheme(baseUri.scheme ?: "https")
                    .authority(baseUri.authority)
                    .build()
                    .toString()
            } else {
                trimmed
            }
        } catch (_: Throwable) {
            trimmed
        }
    }

    // --- HTTP Helpers ---

    private fun httpGet(url: String, referer: String? = null, userAgent: String = USER_AGENT): String {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Accept", "application/json, text/html, */*")
            .header("Accept-Language", "vi-VN,vi;q=0.9,en-US;q=0.8")
        if (!referer.isNullOrEmpty()) {
            builder.header("Referer", referer)
        }
        return http.newCall(builder.build()).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code} for $url")
            resp.body?.string().orEmpty()
        }
    }

    private fun stripDiacritics(input: String): String {
        val normalized = Normalizer.normalize(input, Normalizer.Form.NFD)
        return normalized
            .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
            .replace('đ', 'd')
            .replace('Đ', 'D')
    }

    fun decryptAnimePlayerPayload(
        responseText: String,
        origin: String,
        epPageUrl: String
    ): ResolvedPlayback? {
        val ua = animeUserAgent()
        fun resolved(text: String): ResolvedPlayback? = PlaybackSourceParser.parse(text, epPageUrl)?.let {
            ResolvedPlayback(
                url = it.url,
                headers = mapOf("Referer" to epPageUrl, "Origin" to origin, "User-Agent" to ua),
                isWeb = it.isWeb,
                webUrl = epPageUrl,
                mediaKind = Phim4KMediaProbe.mediaKind(it.url)
            )
        }
        resolved(responseText)?.let { return it }
        var encrypted = responseText.trim()
        var passphrase = "dm_thang_suc_vat_get_link_an_dbt"
        try {
            val j = JSONObject(encrypted)
            val linkAny = j.opt("link")
            if (linkAny is JSONArray) {
                for (i in 0 until linkAny.length()) {
                    val file = linkAny.optJSONObject(i)?.optString("file").orEmpty()
                    if (file.startsWith("http")) {
                        return ResolvedPlayback(
                            url = file,
                            headers = mapOf("Referer" to epPageUrl, "Origin" to origin, "User-Agent" to ua),
                            isWeb = false,
                            webUrl = epPageUrl,
                            mediaKind = Phim4KMediaProbe.mediaKind(file)
                        )
                    }
                }
            }
            passphrase = j.optString("playTech").ifBlank { passphrase }
            encrypted = j.optString("link").ifBlank { j.optString("data").ifBlank { j.optString("html").ifBlank { encrypted } } }
        } catch (_: Throwable) {}

        val raw = decodeBase64Url(encrypted)
        if (raw.size <= 16) return null
        val clearText = AnimePayloadDecoder.decode(raw, passphrase)
        return resolved(clearText)
    }

    private fun decodeBase64Url(input: String): ByteArray {
        val clean = input.trim().replace('-', '+').replace('_', '/')
        val padded = clean + "=".repeat((4 - clean.length % 4) % 4)
        return try {
            java.util.Base64.getDecoder().decode(padded)
        } catch (_: Throwable) {
            runCatching { android.util.Base64.decode(padded, android.util.Base64.DEFAULT) }.getOrDefault(ByteArray(0))
        }
    }
}
