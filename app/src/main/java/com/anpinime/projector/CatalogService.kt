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
        "https://animevietsub.xyz",
        "https://animevietsub.best",
        "https://animevietsub.vip",
        "https://animevietsub.news",
        "https://animevietsub.fan",
        "https://animevietsub.bio",
        "https://animevietsub.sale",
        "https://animevietsub.cam",
        "https://animevietsub.tv"
    )

    private val legacyDefaultAnimeHosts = setOf(
        "https://animevietsub.lol",
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

    @Volatile
    private var cachedAnimeCategories: List<AnimeCategory>? = null

    @Volatile
    private var cachedMiruroBase: String = MiruroCatalog.DEFAULT_BASE_URL

    @Volatile
    private var miruroWarmupAtMs: Long = 0L

    @Volatile
    private var miruroPreferWebViewUntilMs: Long = 0L

    @Volatile
    private var cachedMiruroHome: List<AnimeSection>? = null

    @Volatile
    private var cachedMiruroHomeAtMs: Long = 0L

    private val miruroDetailCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, AnimeDetail>>()

    @Volatile
    private var cachedAnimeHomeBase: String? = null

    @Volatile
    private var cachedAnimeHomeHtml: String? = null

    @Volatile
    private var cachedAnimeHomeAtMs: Long = 0L

    private val animeHtmlCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, String>>()

    private val ioPool = Executors.newCachedThreadPool()

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
        cachedAnimeCategories = null
        cachedAnimeHomeBase = null
        cachedAnimeHomeHtml = null
        cachedAnimeHomeAtMs = 0L
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

        ContentSource.MIRURO -> MiruroCatalog.defaultCategories()
    }

    // --- Home Sections ---

    fun loadHome(source: ContentSource): List<AnimeSection> {
        val raw = when (source) {
            ContentSource.FILM4K -> loadNguonCHome()
            ContentSource.AETHER -> loadAetherHome()
            ContentSource.KKPHIM -> loadKkPhimHome()
            ContentSource.MIRURO -> loadMiruroHome()
        }
        return SourceIsolation.sections(source, raw)
    }

    // --- Category / List ---

    fun loadList(source: ContentSource, categoryPath: String, page: Int = 1): List<Anime> {
        val raw = when (source) {
            ContentSource.FILM4K -> loadNguonCList(categoryPath, page)
            ContentSource.AETHER -> loadAetherList(categoryPath, page)
            ContentSource.KKPHIM -> loadKkPhimList(categoryPath, page)
            ContentSource.MIRURO -> loadMiruroList(categoryPath, page)
        }
        return SourceIsolation.items(source, raw)
    }

    // --- Search ---

    fun search(source: ContentSource, query: String, page: Int = 1): List<Anime> {
        val raw = when (source) {
            ContentSource.FILM4K -> searchNguonC(query, page)
            ContentSource.AETHER -> searchAether(query, page)
            ContentSource.KKPHIM -> searchKkPhim(query, page)
            ContentSource.MIRURO -> searchMiruro(query, page)
        }
        return SourceIsolation.items(source, raw)
    }

    // --- Detail ---

    fun loadDetail(anime: Anime): AnimeDetail = when (anime.source) {
        ContentSource.FILM4K -> loadNguonCDetail(anime)
        ContentSource.AETHER -> loadAetherDetail(anime)
        ContentSource.KKPHIM -> loadKkPhimDetail(anime)
        ContentSource.MIRURO -> loadMiruroDetail(anime)
    }

    // --- Playback Resolution ---

    fun resolvePlayback(
        episode: Episode,
        preferredServerId: String = "auto",
        onProgress: ((sourceName: String, current: Int, total: Int) -> Unit)? = null,
        excludeServerIds: Set<String> = emptySet()
    ): ResolvedPlayback {
        return when (episode.source) {
            ContentSource.FILM4K -> resolveCatalogEpisodePlayback(
                episode = episode,
                defaultReferer = "${MovieSourceDirectory.DEFAULT_NGUONC_BASE_URL}/"
            )
            ContentSource.KKPHIM -> resolveCatalogEpisodePlayback(
                episode = episode,
                defaultReferer = "${MovieSourceDirectory.DEFAULT_KKPHIM_BASE_URL}/"
            )
            ContentSource.AETHER -> resolveAetherPlayback(episode, preferredServerId, onProgress)
            ContentSource.MIRURO -> resolveMiruroPlayback(episode, preferredServerId, excludeServerIds, onProgress)
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
        defaultReferer: String
    ): ResolvedPlayback {
        var primaryUrl = episode.directUrl.trim()
        var embedFallback = episode.refererUrl.trim().takeUnless {
            it == episode.detailUrl || it == episode.url
        }.orEmpty()
        var declaredHls = false

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
                    declaredHls = match.isDirectM3u8
                    if (match.embedFallbackUrl.isNotEmpty()) {
                        embedFallback = match.embedFallbackUrl
                    }
                }
            }
        }

        if (primaryUrl.isEmpty()) {
            throw IllegalStateException("Không tìm thấy luồng phát cho tập này.")
        }

        val detectedKind = Phim4KMediaProbe.mediaKind(primaryUrl)
        val directKind = if (declaredHls && detectedKind == StreamMediaKind.UNKNOWN) StreamMediaKind.HLS else detectedKind
        val isDirectMedia = directKind != StreamMediaKind.UNKNOWN

        if (isDirectMedia) {
            val origin = defaultReferer.trimEnd('/')
            val headers = LinkedHashMap<String, String>()
            headers["User-Agent"] = USER_AGENT
            headers["Referer"] = defaultReferer
            headers["Origin"] = origin
            // KKPhim sometimes publishes an expired playlist alongside its working embed.
            if (episode.source == ContentSource.KKPHIM && directKind == StreamMediaKind.HLS &&
                embedFallback.isNotBlank() && embedFallback != primaryUrl
            ) {
                val probe = Request.Builder().url(primaryUrl)
                headers.forEach { (key, value) -> probe.header(key, value) }
                val missing = runCatching {
                    http.newCall(probe.build()).apply { timeout().timeout(3, TimeUnit.SECONDS) }
                        .execute().use { it.code in listOf(403, 404, 410) }
                }.getOrDefault(false)
                if (missing) {
                    return ResolvedPlayback(
                        url = embedFallback, headers = mapOf("Referer" to defaultReferer),
                        isWeb = true, webUrl = embedFallback
                    )
                }
            }
            // Match iOS MovieProvider.swift: play direct HLS/MP4 streams natively in ExoPlayer first;
            // PlayerActivity automatically falls back to webUrl (link_embed) via onPlayerError if CDN 404s.
            return ResolvedPlayback(
                url = primaryUrl,
                headers = headers,
                isF4kWrapped = primaryUrl.contains(".f4k", ignoreCase = true) ||
                    primaryUrl.contains("cryboiz.workers.dev", ignoreCase = true) ||
                    primaryUrl.substringBefore('?').lowercase().endsWith(".png"),
                isWeb = false,
                webUrl = embedFallback,
                mediaKind = directKind
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

    private fun isDirectStreamReachable(url: String, headers: Map<String, String>): Boolean {
        val cleanLower = url.substringBefore('?').lowercase()
        val isM3u8 = cleanLower.endsWith(".m3u8") || url.contains(".m3u8", ignoreCase = true)
        return try {
            val reqBuilder = Request.Builder().url(url)
            headers.forEach { (k, v) -> reqBuilder.header(k, v) }
            if (!isM3u8) {
                reqBuilder.header("Range", "bytes=0-1023")
            }
            val probeHttp = http.newBuilder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(6, TimeUnit.SECONDS)
                .build()
            probeHttp.newCall(reqBuilder.build()).execute().use { resp ->
                if (!resp.isSuccessful) return false
                val ct = resp.header("Content-Type").orEmpty().lowercase()
                if (ct.contains("text/html")) return false
                val src = resp.body?.source() ?: return false
                val buf = okio.Buffer()
                while (buf.size < 2048) {
                    val read = src.read(buf, 2048 - buf.size)
                    if (read == -1L) break
                }
                if (isM3u8) {
                    val head = buf.readUtf8().trimStart('\uFEFF', ' ', '\t', '\r', '\n')
                    head.startsWith("#EXTM3U") || head.startsWith("#ENC-AESGCM")
                } else {
                    buf.size > 0
                }
            }
        } catch (_: Throwable) {
            false
        }
    }

    private fun isChallengeHtml(html: String): Boolean {
        return html.length <= 600 ||
            runCatching { WebViewHelper.containsVerification(html) }.getOrDefault(false) ||
            html.contains("Just a moment", ignoreCase = true) ||
            html.contains("Xác Minh An Toàn", ignoreCase = true) ||
            html.contains("Đang Kiểm Tra", ignoreCase = true) ||
            html.contains("_cf_chl_opt", ignoreCase = true) ||
            html.contains("cf-browser-verification", ignoreCase = true) ||
            html.contains("Attention Required!", ignoreCase = true)
    }

    private fun fetchAnimeHtml(
        url: String,
        base: String,
        allowWebFallback: Boolean = true,
        waitForEpisodes: Boolean = false
    ): String {
        val now = System.currentTimeMillis()
        animeHtmlCache[url]?.let { (savedAt, cachedHtml) ->
            if ((now - savedAt) < 120_000L && !isChallengeHtml(cachedHtml)) {
                return cachedHtml
            }
        }
        val ua = animeUserAgent()
        val html = try {
            httpGet(url, referer = "$base/", userAgent = ua)
        } catch (_: Throwable) {
            ""
        }
        if (!isChallengeHtml(html)) {
            if (animeHtmlCache.size >= 60) animeHtmlCache.clear()
            animeHtmlCache[url] = System.currentTimeMillis() to html
            return html
        }
        if (!allowWebFallback) return html
        val (webHtml, finalUrl) = try {
            kotlinx.coroutines.runBlocking {
                WebViewHelper.fetchHtml(url, waitForEpisodes = waitForEpisodes)
            }
        } catch (_: Throwable) {
            "" to url
        }
        if (webHtml.isNotBlank() && !isChallengeHtml(webHtml)) {
            if (animeHtmlCache.size >= 60) animeHtmlCache.clear()
            animeHtmlCache[url] = System.currentTimeMillis() to webHtml
            if (finalUrl != url) {
                animeHtmlCache[finalUrl] = System.currentTimeMillis() to webHtml
                runCatching {
                    val u = URL(finalUrl)
                    val hostBase = "${u.protocol}://${u.host}"
                    if (u.host.contains("animevietsub", ignoreCase = true) && getCustomAnimeDomain() == null) {
                        saveVerifiedAnimeDomain(hostBase)
                    }
                }
            }
            return webHtml
        }
        return if (webHtml.length > html.length) webHtml else html
    }

    private fun loadAnimeHome(): List<AnimeSection> {
        var base = resolveAnimeDomain()
        val cachedValid = cachedAnimeHomeHtml?.takeIf {
            cachedAnimeHomeBase == base &&
                (System.currentTimeMillis() - cachedAnimeHomeAtMs) < 45_000L &&
                !isChallengeHtml(it)
        }
        var homeHtml = cachedValid ?: try {
            fetchAnimeHtml("$base/", base, allowWebFallback = true)
        } catch (_: Throwable) {
            ""
        }
        var sections = ArrayList(HTMLParser.sections(homeHtml, base))
        if (sections.isEmpty()) {
            val refreshed = resolveAnimeDomain(forceRefresh = true)
            if (refreshed != base) {
                base = refreshed
                homeHtml = try {
                    fetchAnimeHtml("$base/", base, allowWebFallback = true)
                } catch (_: Throwable) {
                    ""
                }
                sections = ArrayList(HTMLParser.sections(homeHtml, base))
            }
        }
        if (sections.isNotEmpty()) {
            saveVerifiedAnimeDomain(base)
            cachedAnimeHomeBase = base
            cachedAnimeHomeHtml = homeHtml
            cachedAnimeHomeAtMs = System.currentTimeMillis()
            val cats = HTMLParser.categories(homeHtml, base)
            if (cats.size > 4) {
                cachedAnimeCategories = cats
            }
        }
        val havePaths = sections.mapNotNull { it.categoryPath?.lowercase() }.toHashSet()
        val haveTitles = sections.map { it.title.lowercase() }.toSet()

        val extraPaths = listOf(
            "📺 Anime Bộ" to "/anime-bo/",
            "🎬 Anime Lẻ / Movie" to "/anime-le/",
            "🏆 Bảng Xếp Hạng" to "/bang-xep-hang.html"
        ).filter { (title, path) ->
            !havePaths.contains(path.lowercase()) &&
                haveTitles.none { it.contains(title.substringAfter(' ').lowercase()) }
        }

        if (extraPaths.isNotEmpty() && sections.isNotEmpty()) {
            val futures = extraPaths.map { (title, path) ->
                Triple(title, path, ioPool.submit(Callable {
                    try {
                        // Use fast OkHttp with synced Cloudflare cookies first to avoid WebView mutex queueing
                        val h = fetchAnimeHtml("$base$path", base, allowWebFallback = false)
                        HTMLParser.home(h, base)
                    } catch (_: Throwable) {
                        emptyList()
                    }
                }))
            }
            for ((title, path, fut) in futures) {
                val items = try { fut.get(10, TimeUnit.SECONDS) } catch (_: Throwable) { emptyList() }
                if (items.isNotEmpty()) {
                    sections.add(AnimeSection(title, items, path))
                }
            }
        }
        return sections
    }

    private fun loadAnimeList(path: String, page: Int): List<Anime> {
        val base = resolveAnimeDomain()
        val cleanPath = if (path.startsWith("http")) {
            try { Uri.parse(path).path ?: "/anime-moi/" } catch (_: Throwable) { "/anime-moi/" }
        } else {
            path
        }
        val url = if (page <= 1) {
            "$base${cleanPath}"
        } else if (cleanPath.endsWith(".html")) {
            "$base${cleanPath}?page=$page"
        } else {
            "$base${cleanPath.trimEnd('/')}/trang-$page.html"
        }
        val html = fetchAnimeHtml(url, base, allowWebFallback = true)
        return HTMLParser.home(html, base)
    }

    private fun searchAnime(query: String, page: Int): List<Anime> {
        val cleanQuery = query.trim()
        if (cleanQuery.isEmpty()) return emptyList()
        val base = resolveAnimeDomain()
        val slug = cleanQuery.lowercase().replace(Regex("\\s+"), "+")
        val url = if (page <= 1) {
            "$base/tim-kiem/$slug/"
        } else {
            "$base/tim-kiem/$slug/trang-$page.html"
        }
        val html = fetchAnimeHtml(url, base, allowWebFallback = true)
        return HTMLParser.search(html, base)
    }

    private fun loadAnimeDetail(anime: Anime): AnimeDetail {
        val base = resolveAnimeDomain()
        val pageUrl = rewriteAnimeHostIfNeeded(anime.url, base)
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

        if (episodes.isEmpty() && !watchUrl.isNullOrBlank()) {
            episodes = listOf(
                Episode(
                    title = "Tập 1",
                    url = watchUrl,
                    number = 1,
                    season = "Vietsub",
                    qualityBadge = "HD · Vietsub",
                    source = ContentSource.MIRURO,
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

    // =========================================================================
    // 4. NGUỒN 4: ANIME MIRURO (https://miruro.cx) — Warm WebView Batch + Cache
    // =========================================================================

    fun currentMiruroBaseUrl(): String = cachedMiruroBase

    fun clearMiruroHomeCache() {
        cachedMiruroHome = null
        cachedMiruroHomeAtMs = 0L
    }

    private fun tryMiruroOkHttp(targetUrl: String, refBase: String): String? {
        val ua = animeUserAgent()
        return try {
            val req = Request.Builder()
                .url(targetUrl)
                .header("User-Agent", ua)
                .header("Referer", "$refBase/")
                .header("Accept", "application/json, text/sveltekit-data, */*")
                .header("Accept-Language", "vi-VN,vi;q=0.9,en-US;q=0.8")
                .build()
            http.newCall(req).execute().use { resp ->
                if (resp.code == 403 || resp.code == 503) {
                    miruroPreferWebViewUntilMs = System.currentTimeMillis() + 5 * 60_000L
                    return null
                }
                if (!resp.isSuccessful) return null
                val body = resp.body?.string().orEmpty()
                if (body.isBlank() || isChallengeHtml(body)) {
                    miruroPreferWebViewUntilMs = System.currentTimeMillis() + 5 * 60_000L
                    null
                } else {
                    body
                }
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun fetchMiruroApiBatch(
        paths: List<String>,
        allowWebWarmup: Boolean = true
    ): Map<String, String> {
        if (paths.isEmpty()) return emptyMap()
        val cleanPaths = paths.map { if (it.startsWith("/")) it else "/$it" }
        val base = cachedMiruroBase.trimEnd('/')
        val now = System.currentTimeMillis()
        val skipOkHttp = now < miruroPreferWebViewUntilMs

        // 1. Try native parallel OkHttp first when Cloudflare hasn't rejected OkHttp TLS
        val okResults = LinkedHashMap<String, String>()
        if (!skipOkHttp) {
            if (cleanPaths.size == 1) {
                val p = cleanPaths.first()
                tryMiruroOkHttp("$base$p", base)?.let { okResults[p] = it }
            } else {
                val tasks = cleanPaths.map { p ->
                    Callable { p to tryMiruroOkHttp("$base$p", base) }
                }
                try {
                    val futures = ioPool.invokeAll(tasks, 8, TimeUnit.SECONDS)
                    for (f in futures) {
                        val pair = try { if (!f.isCancelled) f.get() else null } catch (_: Throwable) { null }
                        if (pair != null && !pair.second.isNullOrBlank()) {
                            okResults[pair.first] = pair.second!!
                        }
                    }
                } catch (_: Throwable) {}
            }
            if (okResults.size == cleanPaths.size) return okResults
        }

        // Preserve good results and ask for only the missing paths.
        val missingPaths = cleanPaths.filterNot(okResults::containsKey)
        val webQuick = try {
            kotlinx.coroutines.runBlocking {
                WebViewHelper.fetchTextsBatchInWebView(base, missingPaths, timeoutMs = 9_000L)
            }
        } catch (_: Throwable) {
            emptyMap()
        }
        val validQuick = webQuick.filterValues { it.isNotBlank() && !isChallengeHtml(it) }
        if (validQuick.isNotEmpty()) {
            return okResults + validQuick
        }

        if (okResults.isNotEmpty() || !allowWebWarmup) return okResults

        // 3. Warm Cloudflare clearance once on Miruro mirrors in hidden WebView, then run full batch in parallel
        val hostsToTry = listOf(base) + MiruroCatalog.MIRROR_HOSTS.filterNot { it.equals(base, ignoreCase = true) }
        for (mirror in hostsToTry) {
            val cleanMirror = mirror.trimEnd('/')
            val shouldWarm = cleanMirror != base || (System.currentTimeMillis() - miruroWarmupAtMs) > 12_000L
            if (shouldWarm) {
                val (warmHtml, finalUrl) = try {
                    kotlinx.coroutines.runBlocking {
                        WebViewHelper.fetchHtml("$cleanMirror/", timeoutMs = 20_000L)
                    }
                } catch (_: Throwable) {
                    "" to cleanMirror
                }
                if (warmHtml.isNotBlank() && !isChallengeHtml(warmHtml)) {
                    miruroWarmupAtMs = System.currentTimeMillis()
                    val resolvedOrigin = try {
                        val u = URL(finalUrl)
                        "${u.protocol}://${u.host}"
                    } catch (_: Throwable) {
                        cleanMirror
                    }
                    cachedMiruroBase = resolvedOrigin
                }
            }

            val activeMirror = cachedMiruroBase.trimEnd('/')
            val webAfterWarm = try {
                kotlinx.coroutines.runBlocking {
                    WebViewHelper.fetchTextsBatchInWebView(activeMirror, cleanPaths, timeoutMs = 13_000L)
                }
            } catch (_: Throwable) {
                emptyMap()
            }
            val validAfterWarm = webAfterWarm.filterValues { it.isNotBlank() && !isChallengeHtml(it) }
            if (validAfterWarm.isNotEmpty()) {
                return validAfterWarm
            }
        }
        return emptyMap()
    }

    private fun fetchMiruroApiText(path: String, allowWebWarmup: Boolean = true): String? {
        val cleanPath = if (path.startsWith("/")) path else "/$path"
        return fetchMiruroApiBatch(listOf(cleanPath), allowWebWarmup = allowWebWarmup)[cleanPath]
    }

    private fun loadMiruroHome(): List<AnimeSection> {
        val now = System.currentTimeMillis()
        cachedMiruroHome?.let { cached ->
            if (cached.isNotEmpty() && (now - cachedMiruroHomeAtMs) < 3 * 60_000L) {
                return cached
            }
        }

        val homePath = "/__data.json"
        val trendingPath = MiruroCatalog.buildBrowseApiPath("sort=TRENDING_DESC", 1, 18)
        val popularPath = MiruroCatalog.buildBrowseApiPath("sort=POPULARITY_DESC", 1, 18)
        val moviePath = MiruroCatalog.buildBrowseApiPath("format=MOVIE&sort=POPULARITY_DESC", 1, 18)

        // Fetch all home data in ONE parallel HTTP/2 batch instead of 5 sequential round-trips!
        val batch = fetchMiruroApiBatch(
            listOf(homePath, trendingPath, popularPath, moviePath),
            allowWebWarmup = true
        )
        val activeBase = currentMiruroBaseUrl()
        val sections = ArrayList<AnimeSection>()

        batch[homePath]?.let { homeNdjson ->
            sections.addAll(MiruroCatalog.parseHomeDataJson(homeNdjson, activeBase))
        }

        val extraSpecs = listOf(
            Triple("🔥 Thịnh hành", "sort=TRENDING_DESC", trendingPath),
            Triple("⭐ Phổ biến nhất", "sort=POPULARITY_DESC", popularPath),
            Triple("🎬 Anime Movie", "format=MOVIE&sort=POPULARITY_DESC", moviePath)
        )
        for ((title, catPath, apiPath) in extraSpecs) {
            val raw = batch[apiPath] ?: continue
            val items = MiruroCatalog.parseBrowseItems(raw, activeBase)
            if (items.isNotEmpty() && sections.none { it.title == title }) {
                sections.add(AnimeSection(title, items, catPath))
            }
        }

        if (sections.isEmpty()) {
            throw RuntimeException("Không thể kết nối tới Anime Miruro ($activeBase). Vui lòng thử làm mới.")
        }
        cachedMiruroHome = sections
        cachedMiruroHomeAtMs = System.currentTimeMillis()
        return sections
    }

    private fun loadMiruroList(categoryPath: String, page: Int): List<Anime> {
        val path = MiruroCatalog.buildBrowseApiPath(categoryPath, page = page, limit = 24)
        val raw = fetchMiruroApiText(path, allowWebWarmup = true) ?: return emptyList()
        return MiruroCatalog.parseBrowseItems(raw, currentMiruroBaseUrl())
    }

    private fun searchMiruro(query: String, page: Int): List<Anime> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()
        val path = MiruroCatalog.buildSearchApiPath(trimmed, page = page, limit = 24)
        val raw = fetchMiruroApiText(path, allowWebWarmup = true) ?: return emptyList()
        return MiruroCatalog.parseBrowseItems(raw, currentMiruroBaseUrl())
    }

    private fun loadMiruroDetail(anime: Anime): AnimeDetail {
        val (mediaId, slug) = MiruroCatalog.extractWatchIdAndSlug(anime.url)
        if (mediaId.isBlank()) {
            throw RuntimeException("Thiếu mã anime Miruro.")
        }
        miruroDetailCache[mediaId]?.let { (savedAt, cached) ->
            if ((System.currentTimeMillis() - savedAt) < 5 * 60_000L) {
                return cached
            }
        }
        val watchDataPath = MiruroCatalog.buildWatchDataPath(mediaId, slug, 1)
        val ndjson = fetchMiruroApiText(watchDataPath, allowWebWarmup = true)
            ?: throw RuntimeException("Không tải được chi tiết Anime Miruro.")
        val parsed = MiruroCatalog.parseWatchDetail(ndjson, anime, currentMiruroBaseUrl())
        parsed.sourcesData?.let { srcJson ->
            MiruroSourceResolver.cacheSourcesData(parsed.mediaId, parsed.episodeNumber, srcJson)
        }
        miruroDetailCache[mediaId] = System.currentTimeMillis() to parsed.detail
        return parsed.detail
    }

    private fun resolveMiruroPlayback(
        episode: Episode,
        preferredServerId: String = "auto",
        excludeServerIds: Set<String> = emptySet(),
        onProgress: ((sourceName: String, current: Int, total: Int) -> Unit)? = null
    ): ResolvedPlayback {
        val (urlId, urlSlug) = MiruroCatalog.extractWatchIdAndSlug(episode.detailUrl.ifBlank { episode.url })
        val mediaId = episode.slug.trim().ifBlank { urlId }
        val epNum = episode.episodeNum.takeIf { it > 0 } ?: episode.number.coerceAtLeast(1)
        if (mediaId.isBlank()) {
            throw RuntimeException("Thiếu thông tin tập Anime Miruro.")
        }

        var candidates = MiruroSourceResolver.getCachedCandidates(mediaId, epNum)
        if (candidates.isEmpty()) {
            onProgress?.invoke("Đang tải sourcesData Miruro…", 1, 3)
            val sourcesRaw = fetchMiruroApiText(MiruroCatalog.buildSourcesApiPath(mediaId, epNum), allowWebWarmup = true)
            if (!sourcesRaw.isNullOrBlank()) {
                val jsonObj = try { JSONObject(sourcesRaw) } catch (_: Throwable) { null }
                if (jsonObj != null) {
                    candidates = MiruroSourceResolver.cacheSourcesData(mediaId, epNum, jsonObj)
                }
            }
        }
        if (candidates.isEmpty()) {
            onProgress?.invoke("Đang lấy nguồn từ trang xem Miruro…", 2, 3)
            val watchDataPath = MiruroCatalog.buildWatchDataPath(mediaId, urlSlug, epNum)
            val ndjson = fetchMiruroApiText(watchDataPath, allowWebWarmup = true)
            if (!ndjson.isNullOrBlank()) {
                val fallbackAnime = Anime(title = episode.title, url = episode.url, source = ContentSource.MIRURO)
                val watchResult = MiruroCatalog.parseWatchDetail(ndjson, fallbackAnime, currentMiruroBaseUrl())
                watchResult.sourcesData?.let { srcJson ->
                    candidates = MiruroSourceResolver.cacheSourcesData(mediaId, epNum, srcJson)
                }
            }
        }

        if (candidates.isEmpty()) {
            throw RuntimeException("Không tìm thấy luồng phát khả dụng cho tập $epNum trên Miruro.")
        }

        return MiruroSourceResolver.resolveWithAutoFailover(
            candidates = candidates,
            client = http,
            preferredKey = preferredServerId,
            excludeKeys = excludeServerIds,
            onProgress = onProgress
        ) ?: throw RuntimeException("Tất cả máy chủ trong sourcesData đều đang gián đoạn.")
    }

    // --- HTTP Helpers ---

    private fun httpGet(url: String, referer: String? = null, userAgent: String = USER_AGENT): String {
        var lastError: Throwable? = null
        for (attempt in 1..2) {
            try {
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
            } catch (t: Throwable) {
                lastError = t
                val msg = t.message.orEmpty()
                // Do not retry 403/404 client errors
                if (msg.contains("HTTP 403") || msg.contains("HTTP 404")) throw t
                if (attempt < 2) {
                    try { Thread.sleep(180) } catch (_: Throwable) {}
                }
            }
        }
        throw (lastError ?: RuntimeException("HTTP error for $url"))
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
