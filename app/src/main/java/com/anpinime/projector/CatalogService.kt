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
    private const val CORE_USER_AGENT = "okhttp/4.12.0"
    private const val CORE_PACKAGE_HEADER = "com.oxoo.spagreen"

    private const val ANIME_SHORTLINK = "https://bit.ly/animevietsubtv"
    private const val ANIME_VERIFY_PATH = "/phim/bleach-sennen-kessen-hen-kashin-tan-a5985/"
    private const val ANIME_DOMAIN_TTL_MS = 6 * 60 * 60 * 1000L // 6 hours
    private const val PREF_ANIME = "anpinime_anime_config"
    private const val KEY_CUSTOM_ANIME_URL = "custom_anime_url"
    private const val KEY_VERIFIED_ANIME_URL = "verified_anime_url"
    private const val KEY_VERIFIED_ANIME_AT = "verified_anime_at_ms"

    val animeFallbackHosts = listOf(
        "https://animevietsub.news",
        "https://animevietsub.xyz",
        "https://animevietsub.fan",
        "https://animevietsub.bio",
        "https://animevietsub.vip",
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
    private var cachedPhim4kCategories: List<AnimeCategory>? = null

    private val ioPool = Executors.newFixedThreadPool(6)

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    fun init(context: Context) {
        appContext = context.applicationContext
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
        }

        val candidates = LinkedHashSet<String>()
        resolveShortlinkCandidate()?.let { candidates.add(it.trimEnd('/')) }
        animeFallbackHosts.forEach { candidates.add(it.trimEnd('/')) }

        // Verify candidates in parallel against Bleach detail page
        val candidateList = candidates.toList()
        val tasks = candidateList.map { host ->
            Callable {
                if (verifyAnimeCandidate(host)) host else null
            }
        }
        try {
            val futures = ioPool.invokeAll(tasks, 12, TimeUnit.SECONDS)
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
        try {
            val conn = (URL(ANIME_SHORTLINK).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 6000
                readTimeout = 6000
                setRequestProperty("User-Agent", USER_AGENT)
            }
            val code = conn.responseCode
            val loc = conn.getHeaderField("Location")
            conn.disconnect()
            if (code in 300..399 && !loc.isNullOrBlank()) {
                val uri = Uri.parse(loc)
                val scheme = uri.scheme ?: "https"
                val host = uri.host
                if (!host.isNullOrBlank() && !host.contains("bit.ly", ignoreCase = true)) {
                    return "$scheme://$host"
                }
            }
        } catch (_: Throwable) {
        }
        return null
    }

    private fun verifyAnimeCandidate(baseHost: String): Boolean {
        val testUrl = "${baseHost.trimEnd('/')}$ANIME_VERIFY_PATH"
        val html = try {
            val req = Request.Builder()
                .url(testUrl)
                .header("User-Agent", USER_AGENT)
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

        ContentSource.PHIM4K -> {
            cachedPhim4kCategories?.let { return it }
            val dir = refreshSource2Directory()
            val rawHome = try {
                httpGetCore("${dir.coreBaseUrl}/rest-api/v130/home_content_for_android", dir.coreApiKey)
            } catch (_: Throwable) {
                ""
            }
            val parsed = Phim4KCatalog.parseCategoriesFromHome(
                rawJson = rawHome,
                includeFree2 = dir.free2.isNotBlank()
            )
            if (parsed.isNotEmpty()) cachedPhim4kCategories = parsed
            parsed
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
                fetchAnimeHtml("$base/", base)
            } catch (_: Throwable) {
                ""
            }
            HTMLParser.categories(html, base)
        }
    }

    // --- Home Sections ---

    fun loadHome(source: ContentSource): List<AnimeSection> {
        val raw = when (source) {
            ContentSource.FILM4K -> loadNguonCHome()
            ContentSource.PHIM4K -> loadPhim4kHome()
            ContentSource.KKPHIM -> loadKkPhimHome()
            ContentSource.ANIME -> loadAnimeHome()
        }
        return SourceIsolation.sections(source, raw)
    }

    // --- Category / List ---

    fun loadList(source: ContentSource, categoryPath: String, page: Int = 1): List<Anime> {
        val raw = when (source) {
            ContentSource.FILM4K -> loadNguonCList(categoryPath, page)
            ContentSource.PHIM4K -> loadPhim4kList(categoryPath, page)
            ContentSource.KKPHIM -> loadKkPhimList(categoryPath, page)
            ContentSource.ANIME -> loadAnimeList(categoryPath, page)
        }
        return SourceIsolation.items(source, raw)
    }

    // --- Search ---

    fun search(source: ContentSource, query: String, page: Int = 1): List<Anime> {
        val raw = when (source) {
            ContentSource.FILM4K -> searchNguonC(query, page)
            ContentSource.PHIM4K -> searchPhim4k(query, page)
            ContentSource.KKPHIM -> searchKkPhim(query, page)
            ContentSource.ANIME -> searchAnime(query, page)
        }
        return SourceIsolation.items(source, raw)
    }

    // --- Detail ---

    fun loadDetail(anime: Anime): AnimeDetail = when (anime.source) {
        ContentSource.FILM4K -> loadNguonCDetail(anime)
        ContentSource.PHIM4K -> loadPhim4kDetail(anime)
        ContentSource.KKPHIM -> loadKkPhimDetail(anime)
        ContentSource.ANIME -> loadAnimeDetail(anime)
    }

    // --- Anime Schedule ---

    fun loadSchedule(): List<AnimeScheduleItem> {
        val base = resolveAnimeDomain()
        val url = "$base/lich-chieu-phim.html"
        val html = try {
            fetchAnimeHtml(url, base)
        } catch (_: Throwable) {
            ""
        }
        return HTMLParser.schedule(html, base)
    }

    // --- Playback Resolution ---

    fun resolvePlayback(episode: Episode): ResolvedPlayback {
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
            ContentSource.PHIM4K -> resolvePhim4kPlayback(episode)
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
            summary = anime.summary.ifEmpty { "Không tải được chi tiết phim từ NguonC." },
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
    // 2. NGUỒN 2: PHIM4K (VIP/Premium Core + Free1 + Free2)
    // =========================================================================

    private fun refreshSource2Directory(): MovieSourceDirectory.Config {
        return MovieSourceDirectory.refreshIfNeeded(appContext) { url ->
            try {
                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", "okhttp/4.12.0")
                    .build()
                http.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) resp.body?.string() else null
                }
            } catch (_: Throwable) {
                null
            }
        }
    }

    private fun loadPhim4kHome(): List<AnimeSection> {
        val dir = refreshSource2Directory()
        val sections = ArrayList<AnimeSection>()

        // 1. Core VIP/Premium sections
        val coreHomeFuture = ioPool.submit(Callable {
            try {
                httpGetCore("${dir.coreBaseUrl}/rest-api/v130/home_content_for_android", dir.coreApiKey)
            } catch (_: Throwable) {
                ""
            }
        })

        val extraCoreFutures = Phim4KCatalog.homeExtraRows.map { (title, endpoint) ->
            title to ioPool.submit(Callable {
                try {
                    val raw = httpGetCore("${dir.coreBaseUrl}$endpoint", dir.coreApiKey)
                    Phim4KCatalog.parseItems(raw, dir.coreBaseUrl, ContentSource.PHIM4K)
                } catch (_: Throwable) {
                    emptyList()
                }
            })
        }

        // 2. Free1 sections in parallel
        val free1SectionsFuture = ioPool.submit(Callable {
            if (dir.free1.isNotBlank()) {
                fetchCatalogSectionsParallel(
                    configs = MovieCatalog.homeSectionsConfig(MovieCatalogFormat.FREE1).take(4).map { (t, p) ->
                        "$t · Free1" to p
                    },
                    baseUrl = dir.free1,
                    format = MovieCatalogFormat.FREE1,
                    source = ContentSource.PHIM4K,
                    catalogLabel = Phim4KCatalog.LABEL_FREE1,
                    categoryPrefix = "free1:"
                )
            } else {
                emptyList()
            }
        })

        // 3. Free2 sections if announced in directory
        val free2SectionsFuture = ioPool.submit(Callable {
            if (dir.free2.isNotBlank()) {
                val fmt = MovieCatalog.inferFormat(dir.free2)
                fetchCatalogSectionsParallel(
                    configs = MovieCatalog.homeSectionsConfig(fmt).take(3).map { (t, p) ->
                        "$t · Free2" to p
                    },
                    baseUrl = dir.free2,
                    format = fmt,
                    source = ContentSource.PHIM4K,
                    catalogLabel = Phim4KCatalog.LABEL_FREE2,
                    categoryPrefix = "free2:"
                )
            } else {
                emptyList()
            }
        })

        val coreRaw = try { coreHomeFuture.get(10, TimeUnit.SECONDS) } catch (_: Throwable) { "" }
        if (coreRaw.isNotBlank()) {
            sections.addAll(Phim4KCatalog.parseHomeSections(coreRaw, dir.coreBaseUrl, ContentSource.PHIM4K))
        }

        for ((title, fut) in extraCoreFutures) {
            val items = try { fut.get(6, TimeUnit.SECONDS) } catch (_: Throwable) { emptyList() }
            if (items.isNotEmpty() && sections.none { it.title.equals(title, ignoreCase = true) }) {
                sections.add(AnimeSection(title, items))
            }
        }

        val free1Sections = try { free1SectionsFuture.get(10, TimeUnit.SECONDS) } catch (_: Throwable) { emptyList() }
        sections.addAll(free1Sections)

        val free2Sections = try { free2SectionsFuture.get(8, TimeUnit.SECONDS) } catch (_: Throwable) { emptyList() }
        sections.addAll(free2Sections)

        return sections
    }

    private fun loadPhim4kList(categoryPath: String, page: Int): List<Anime> {
        val dir = refreshSource2Directory()
        return when {
            categoryPath.startsWith("core:") -> {
                val endpoint = categoryPath.removePrefix("core:")
                val sep = if (endpoint.contains("?")) "&" else "?"
                val url = "${dir.coreBaseUrl}/${endpoint.trimStart('/')}${sep}page=$page"
                val raw = try {
                    httpGetCore(url, dir.coreApiKey)
                } catch (_: Throwable) {
                    return emptyList()
                }
                Phim4KCatalog.parseItems(raw, dir.coreBaseUrl, ContentSource.PHIM4K)
            }
            categoryPath.startsWith("free2:") -> {
                val base = dir.free2.takeIf { it.isNotBlank() } ?: return emptyList()
                val fmt = MovieCatalog.inferFormat(base)
                val endpoint = categoryPath.removePrefix("free2:").trimStart('/')
                val sep = if (endpoint.contains("?")) "&" else "?"
                val url = "$base/$endpoint${sep}page=$page"
                val raw = try {
                    httpGet(url, referer = "$base/")
                } catch (_: Throwable) {
                    return emptyList()
                }
                MovieCatalog.parseItems(raw, base, fmt, ContentSource.PHIM4K, Phim4KCatalog.LABEL_FREE2)
            }
            else -> {
                val base = dir.free1.ifBlank { MovieSourceDirectory.DEFAULT_FREE1_BASE_URL }
                val fmt = MovieCatalog.inferFormat(base)
                val endpoint = categoryPath.removePrefix("free1:").trimStart('/')
                val sep = if (endpoint.contains("?")) "&" else "?"
                val url = "$base/$endpoint${sep}page=$page&limit=24"
                val raw = try {
                    httpGet(url, referer = "$base/")
                } catch (_: Throwable) {
                    return emptyList()
                }
                MovieCatalog.parseItems(raw, base, fmt, ContentSource.PHIM4K, Phim4KCatalog.LABEL_FREE1)
            }
        }
    }

    private fun searchPhim4k(query: String, page: Int): List<Anime> {
        val dir = refreshSource2Directory()
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()

        val coreFuture = ioPool.submit(Callable {
            searchPhim4kCore(dir, trimmed, page)
        })
        val free1Future = ioPool.submit(Callable {
            if (dir.free1.isNotBlank()) {
                val base = dir.free1
                val url = "$base/v1/api/tim-kiem?keyword=${Uri.encode(trimmed)}&page=$page&limit=24"
                val raw = try { httpGet(url, referer = "$base/") } catch (_: Throwable) { "" }
                MovieCatalog.parseItems(raw, base, MovieCatalogFormat.FREE1, ContentSource.PHIM4K, Phim4KCatalog.LABEL_FREE1)
            } else {
                emptyList()
            }
        })
        val free2Future = ioPool.submit(Callable {
            if (dir.free2.isNotBlank()) {
                val base = dir.free2
                val fmt = MovieCatalog.inferFormat(base)
                val url = if (fmt == MovieCatalogFormat.FREE2) {
                    "$base/api/films/search?keyword=${Uri.encode(trimmed)}&page=$page"
                } else {
                    "$base/v1/api/tim-kiem?keyword=${Uri.encode(trimmed)}&page=$page&limit=24"
                }
                val raw = try { httpGet(url, referer = "$base/") } catch (_: Throwable) { "" }
                MovieCatalog.parseItems(raw, base, fmt, ContentSource.PHIM4K, Phim4KCatalog.LABEL_FREE2)
            } else {
                emptyList()
            }
        })

        val out = ArrayList<Anime>()
        val seen = HashSet<String>()
        for (fut in listOf(coreFuture, free1Future, free2Future)) {
            val list = try { fut.get(10, TimeUnit.SECONDS) } catch (_: Throwable) { emptyList() }
            for (item in list) {
                if (seen.add(item.url)) out.add(item)
            }
        }
        return out
    }

    private fun searchPhim4kCore(dir: MovieSourceDirectory.Config, query: String, page: Int): List<Anime> {
        val encoded = Uri.encode(query)
        val url = "${dir.coreBaseUrl}/rest-api/v130/search?q=$encoded&page=$page&type=movie,tvseries"
        val primaryRaw = try {
            httpGetCore(url, dir.coreApiKey)
        } catch (_: Throwable) {
            ""
        }
        val primary = Phim4KCatalog.parseItems(primaryRaw, dir.coreBaseUrl, ContentSource.PHIM4K)
        if (primary.isNotEmpty()) return primary

        val asciiQuery = stripDiacritics(query)
        if (asciiQuery.isNotEmpty() && !asciiQuery.equals(query, ignoreCase = true)) {
            val fallbackUrl = "${dir.coreBaseUrl}/rest-api/v130/search?q=${Uri.encode(asciiQuery)}&page=$page&type=movie,tvseries"
            val fallbackRaw = try {
                httpGetCore(fallbackUrl, dir.coreApiKey)
            } catch (_: Throwable) {
                ""
            }
            return Phim4KCatalog.parseItems(fallbackRaw, dir.coreBaseUrl, ContentSource.PHIM4K)
        }
        return emptyList()
    }

    private fun loadPhim4kDetail(anime: Anime): AnimeDetail {
        val dir = refreshSource2Directory()
        val url = anime.url.trim()

        // Case A: Phim4K Core item (/rest-api/v130/single_details)
        if (url.contains("/rest-api/v130/single_details")) {
            val raw = try {
                httpGetCore(url, dir.coreApiKey)
            } catch (_: Throwable) {
                ""
            }
            Phim4KCatalog.parseDetail(raw, url, dir.coreBaseUrl, ContentSource.PHIM4K)?.let { return it }
        }

        // Case B: Free1 or Free2 catalog item in Source 2
        val fmt = MovieCatalog.inferFormat(url)
        val baseUrl = when {
            dir.free2.isNotBlank() && url.startsWith(dir.free2) -> dir.free2
            dir.free1.isNotBlank() && url.startsWith(dir.free1) -> dir.free1
            fmt == MovieCatalogFormat.FREE2 -> MovieSourceDirectory.DEFAULT_NGUONC_BASE_URL
            else -> dir.free1.ifBlank { MovieSourceDirectory.DEFAULT_FREE1_BASE_URL }
        }
        val detailUrl = if (url.startsWith("http")) {
            url
        } else {
            val slug = url.trim('/').substringAfterLast('/')
            if (fmt == MovieCatalogFormat.FREE2) "$baseUrl/api/film/$slug" else "$baseUrl/phim/$slug"
        }

        val raw = try {
            httpGet(detailUrl, referer = "$baseUrl/")
        } catch (_: Throwable) {
            ""
        }
        MovieCatalog.parseDetail(raw, detailUrl, baseUrl, fmt, ContentSource.PHIM4K)?.let { return it }

        return AnimeDetail(
            title = anime.title,
            posterUrl = anime.imageUrl,
            bannerUrl = anime.bannerUrl ?: anime.imageUrl,
            summary = anime.summary.ifEmpty { "Không tải được chi tiết phim từ Phim4K." },
            info = anime.info,
            episodes = emptyList()
        )
    }

    private fun resolvePhim4kPlayback(episode: Episode): ResolvedPlayback {
        val dir = refreshSource2Directory()

        if (episode.playbackKind == "phim4k_core" || episode.detailUrl.contains("/rest-api/v130/single_details")) {
            var rawFileUrl = episode.directUrl.trim()
            var subtitles: List<ExternalSubtitleTrack> = emptyList()

            if (episode.detailUrl.startsWith("http")) {
                val rawDetail = try {
                    httpGetCore(episode.detailUrl, dir.coreApiKey)
                } catch (_: Throwable) {
                    ""
                }
                if (rawDetail.isNotBlank()) {
                    val match = Phim4KCatalog.extractStreamFromDetail(rawDetail, episode)
                    if (match != null) {
                        rawFileUrl = match.fileUrl
                        subtitles = match.subtitles
                    }
                }
            }

            if (rawFileUrl.isEmpty()) {
                throw IllegalStateException("Không tìm thấy luồng phát VIP cho mục này.")
            }

            val resolvedUrl = Phim4KVIPResolver.resolveStreamUrl(http, rawFileUrl)
                ?: throw IllegalStateException("Không thể giải mã token VIP cho luồng phát.")

            val headers = LinkedHashMap<String, String>()
            headers["User-Agent"] = "exoplayer"
            headers["Referer"] = "${dir.coreBaseUrl}/"

            val probedKind = Phim4KMediaProbe.probeStream(http, resolvedUrl, headers)
                ?: Phim4KMediaProbe.mediaKind(resolvedUrl)

            val isWrapped = resolvedUrl.contains(".f4k", ignoreCase = true)

            return ResolvedPlayback(
                url = resolvedUrl,
                headers = headers,
                isF4kWrapped = isWrapped && probedKind == StreamMediaKind.HLS,
                isWeb = false,
                mediaKind = probedKind,
                subtitles = subtitles
            )
        }

        // Otherwise Free1 or Free2 catalog episode inside Source 2
        val fmt = MovieCatalog.inferFormat(episode.detailUrl.ifEmpty { episode.url })
        val defaultRef = if (fmt == MovieCatalogFormat.FREE2 && dir.free2.isNotBlank()) {
            "${dir.free2}/"
        } else {
            "${dir.free1.ifBlank { MovieSourceDirectory.DEFAULT_FREE1_BASE_URL }}/"
        }
        return resolveCatalogEpisodePlayback(episode, defaultRef, fmt)
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
            summary = anime.summary.ifEmpty { "Không tải được chi tiết phim từ KKPhim." },
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

        // Embed URL (e.g. streamc.xyz/embed.php?id=... or player embed):
        // Try extracting direct .m3u8 from embed HTML first, fallback to WebView with fallbackJavaScript
        try {
            val embedHtml = httpGet(primaryUrl, referer = defaultReferer)
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

    private fun fetchAnimeHtml(url: String, base: String): String {
        val html = try {
            httpGet(url, referer = "$base/")
        } catch (_: Throwable) {
            ""
        }
        if (html.length > 600 &&
            !html.contains("Just a moment", ignoreCase = true) &&
            !html.contains("cf-browser-verification", ignoreCase = true) &&
            !html.contains("Attention Required!", ignoreCase = true)
        ) {
            return html
        }
        val webHtml = try {
            kotlinx.coroutines.runBlocking { WebViewHelper.fetchHtml(url).first }
        } catch (_: Throwable) {
            ""
        }
        return if (webHtml.length > html.length) webHtml else html
    }

    private fun loadAnimeHome(): List<AnimeSection> {
        val base = resolveAnimeDomain()
        val sections = ArrayList<AnimeSection>()

        val homeHtml = try { fetchAnimeHtml("$base/", base) } catch (_: Throwable) { "" }
        val latest = HTMLParser.home(homeHtml, base)
        if (latest.isNotEmpty()) {
            sections.add(AnimeSection("🔥 Anime Mới Cập Nhật", latest, "/anime-moi/"))
        }

        val extraPaths = listOf(
            "📺 Anime Bộ" to "/anime-bo/",
            "🎬 Anime Lẻ / Movie" to "/anime-le/",
            "🏆 Bảng Xếp Hạng" to "/bang-xep-hang.html"
        )
        val futures = extraPaths.map { (title, path) ->
            Triple(title, path, ioPool.submit(Callable {
                try {
                    val h = fetchAnimeHtml("$base$path", base)
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
        val html = fetchAnimeHtml(url, base)
        return HTMLParser.home(html, base)
    }

    private fun searchAnime(query: String, page: Int): List<Anime> {
        val base = resolveAnimeDomain()
        val slug = query.trim().lowercase().replace(Regex("\\s+"), "+")
        val url = if (page <= 1) {
            "$base/tim-kiem/$slug/"
        } else {
            "$base/tim-kiem/$slug/trang-$page.html"
        }
        val html = fetchAnimeHtml(url, base)
        return HTMLParser.search(html, base)
    }

    private fun loadAnimeDetail(anime: Anime): AnimeDetail {
        val base = resolveAnimeDomain()
        val pageUrl = rewriteAnimeHostIfNeeded(anime.url, base)
        val detailHtml = fetchAnimeHtml(pageUrl, base)
        val parsed = HTMLParser.detail(detailHtml, pageUrl, base)

        var episodes = parsed.episodes
        val watchUrl = parsed.watchUrl ?: episodes.lastOrNull()?.url

        // If the detail page only has a "Xem phim" link or incomplete streamed episodes, fetch watch page HTML
        if ((episodes.isEmpty() || !HTMLParser.hasCompleteStreamedEpisodes(detailHtml, pageUrl, base, episodes)) &&
            !watchUrl.isNullOrBlank() &&
            !watchUrl.equals(pageUrl, ignoreCase = true)
        ) {
            try {
                val watchHtml = fetchAnimeHtml(watchUrl, base)
                val watchEpisodes = HTMLParser.episodes(watchHtml, pageUrl, base)
                episodes = HTMLParser.mergedEpisodes(primary = watchEpisodes, fallback = episodes)
            } catch (_: Throwable) {
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
        val base = resolveAnimeDomain()
        val epUrl = rewriteAnimeHostIfNeeded(episode.url, base)

        if (episode.directUrl.startsWith("http")) {
            return ResolvedPlayback(
                url = episode.directUrl,
                headers = mapOf("User-Agent" to USER_AGENT, "Referer" to epUrl),
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
                hash = Regex("""data-(?:hash|play|id)=["']([a-zA-Z0-9+/=_-]{10,})["']""")
                    .find(html)?.groupValues?.get(1).orEmpty()
            }
            if (filmId.isEmpty()) {
                filmId = Regex("""(?:filmId|movie_id|id_film)\s*[:=]\s*["']?(\d+)["']?""")
                    .find(html)?.groupValues?.get(1).orEmpty()
            }

            if (hash.isNotEmpty()) {
                val ajaxUrl = "$base/ajax/player?v=2019a"
                val form = FormBody.Builder()
                    .add("link", hash)
                    .add("id", filmId.ifEmpty { "1" })
                    .build()
                val req = Request.Builder()
                    .url(ajaxUrl)
                    .post(form)
                    .header("User-Agent", USER_AGENT)
                    .header("Referer", epUrl)
                    .header("X-Requested-With", "XMLHttpRequest")
                    .build()
                val resolvedAjax: ResolvedPlayback? = http.newCall(req).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    if (body.isNotEmpty()) {
                        val json = JSONObject(body)
                        val linkVal = json.opt("link")
                        when (linkVal) {
                            is String -> {
                                if (linkVal.startsWith("http")) {
                                    return@use ResolvedPlayback(
                                        url = linkVal,
                                        headers = mapOf("User-Agent" to USER_AGENT, "Referer" to "$base/"),
                                        isWeb = false,
                                        webUrl = epUrl,
                                        mediaKind = Phim4KMediaProbe.mediaKind(linkVal)
                                    )
                                }
                                val passphrase = json.optString("playTech").ifEmpty { "dm_thang_suc_vat_get_link_an_dbt" }
                                val raw = decodeBase64Url(linkVal)
                                if (raw.size > 16) {
                                    val decrypted = AnimePayloadDecoder.decode(raw, passphrase)
                                    val stream = PlaybackSourceParser.extractStreamCandidates(decrypted, base).firstOrNull()
                                    if (!stream.isNullOrEmpty()) {
                                        return@use ResolvedPlayback(
                                            url = stream,
                                            headers = mapOf("User-Agent" to USER_AGENT, "Referer" to "$base/"),
                                            isWeb = false,
                                            webUrl = epUrl,
                                            mediaKind = Phim4KMediaProbe.mediaKind(stream)
                                        )
                                    }
                                }
                            }
                            is JSONArray -> {
                                for (i in 0 until linkVal.length()) {
                                    val file = linkVal.optJSONObject(i)?.optString("file").orEmpty()
                                    if (file.startsWith("http")) {
                                        return@use ResolvedPlayback(
                                            url = file,
                                            headers = mapOf("User-Agent" to USER_AGENT, "Referer" to "$base/"),
                                            isWeb = false,
                                            webUrl = epUrl,
                                            mediaKind = Phim4KMediaProbe.mediaKind(file)
                                        )
                                    }
                                }
                            }
                        }
                    }
                    null
                }
                if (resolvedAjax != null) return resolvedAjax
            }

            val directInHtml = PlaybackSourceParser.extractStreamCandidates(html, base)
                .firstOrNull { !it.contains("trailer", ignoreCase = true) }
            if (!directInHtml.isNullOrEmpty()) {
                return ResolvedPlayback(
                    url = directInHtml,
                    headers = mapOf("User-Agent" to USER_AGENT, "Referer" to epUrl),
                    isWeb = false,
                    webUrl = epUrl,
                    mediaKind = Phim4KMediaProbe.mediaKind(directInHtml)
                )
            }
        } catch (_: Throwable) {
        }

        return ResolvedPlayback(
            url = epUrl,
            headers = mapOf("User-Agent" to USER_AGENT, "Referer" to "$base/"),
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

    private fun httpGet(url: String, referer: String? = null): String {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json, text/html, */*")
        if (!referer.isNullOrEmpty()) {
            builder.header("Referer", referer)
        }
        return http.newCall(builder.build()).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code} for $url")
            resp.body?.string().orEmpty()
        }
    }

    private fun httpGetCore(url: String, apiKey: String): String {
        val req = Request.Builder()
            .url(url)
            .header("API-KEY", apiKey)
            .header("User-Agent", CORE_USER_AGENT)
            .header("X-Requested-With", CORE_PACKAGE_HEADER)
            .header("Accept", "application/json")
            .build()
        return http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("Core HTTP ${resp.code} for $url")
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
        fun resolved(text: String): ResolvedPlayback? = PlaybackSourceParser.parse(text, epPageUrl)?.let {
            ResolvedPlayback(
                url = it.url,
                headers = mapOf("Referer" to epPageUrl, "Origin" to origin, "User-Agent" to USER_AGENT),
                isWeb = it.isWeb,
                webUrl = epPageUrl,
                mediaKind = Phim4KMediaProbe.mediaKind(it.url)
            )
        }
        resolved(responseText)?.let { return it }
        var encrypted = responseText.trim()
        try {
            val j = JSONObject(encrypted)
            encrypted = j.optString("link").ifBlank { j.optString("data").ifBlank { j.optString("html").ifBlank { encrypted } } }
        } catch (_: Throwable) {}

        val raw = decodeBase64Url(encrypted)
        if (raw.size <= 16) return null
        val clearText = AnimePayloadDecoder.decode(raw, "dm_thang_suc_vat_get_link_an_dbt")
        return resolved(clearText)
    }

    private fun decodeBase64Url(input: String): ByteArray {
        val clean = input.trim().replace('-', '+').replace('_', '/')
        val padded = clean + "=".repeat((4 - clean.length % 4) % 4)
        return android.util.Base64.decode(padded, android.util.Base64.DEFAULT)
    }
}
