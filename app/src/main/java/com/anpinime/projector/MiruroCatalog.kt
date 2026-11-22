package com.anpinime.projector

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale

data class MiruroDevaluePromise(val id: Int)

data class MiruroNdjsonPayload(
    val rootData: JSONObject,
    val chunks: Map<Int, Any?>
)

data class MiruroWatchDetailResult(
    val detail: AnimeDetail,
    val mediaId: String,
    val episodeNumber: Int,
    val sourcesData: JSONObject?
)

/**
 * Pure parser and catalog builder for Miruro (https://miruro.cx).
 * Supports SvelteKit streaming NDJSON (__data.json), devalue flat pools,
 * /api/search/browse JSON, and /api/sources payloads.
 */
object MiruroCatalog {
    const val DEFAULT_BASE_URL = "https://miruro.cx"

    val MIRROR_HOSTS = listOf(
        "https://miruro.cx",
        "https://miruro.tv",
        "https://miruro.to"
    )

    private val HTML_TAG_REGEX = Regex("""<[^>]+>""")
    private val MULTI_SPACE_REGEX = Regex("""\s+""")
    private val SLUG_NON_ALNUM_REGEX = Regex("""[^a-z0-9]+""")
    private val WATCH_PATH_REGEX = Regex("""/(?:watch|info)/([^/?#]+)(?:/([^/?#]+))?""", RegexOption.IGNORE_CASE)

    fun defaultCategories(): List<AnimeCategory> = listOf(
        // Main lists
        AnimeCategory("🔥 Thịnh hành", "sort=TRENDING_DESC", "Danh mục"),
        AnimeCategory("⭐ Phổ biến nhất", "sort=POPULARITY_DESC", "Danh mục"),
        AnimeCategory("📺 Đang phát sóng", "status=RELEASING&sort=POPULARITY_DESC", "Danh mục"),
        AnimeCategory("🏆 Điểm cao nhất", "sort=SCORE_DESC", "Danh mục"),
        AnimeCategory("✅ Đã hoàn thành", "status=FINISHED&sort=POPULARITY_DESC", "Danh mục"),
        AnimeCategory("🎬 Anime Movie", "format=MOVIE&sort=POPULARITY_DESC", "Danh mục"),
        AnimeCategory("📺 Anime TV Series", "format=TV&sort=POPULARITY_DESC", "Danh mục"),
        AnimeCategory("🎞️ OVA / ONA", "format=OVA&sort=POPULARITY_DESC", "Danh mục"),
        // Genres
        AnimeCategory("Hành động (Action)", "genre=Action&sort=POPULARITY_DESC", "Thể loại"),
        AnimeCategory("Phiêu lưu (Adventure)", "genre=Adventure&sort=POPULARITY_DESC", "Thể loại"),
        AnimeCategory("Hài hước (Comedy)", "genre=Comedy&sort=POPULARITY_DESC", "Thể loại"),
        AnimeCategory("Chính kịch (Drama)", "genre=Drama&sort=POPULARITY_DESC", "Thể loại"),
        AnimeCategory("Giả tưởng (Fantasy)", "genre=Fantasy&sort=POPULARITY_DESC", "Thể loại"),
        AnimeCategory("Kinh dị (Horror)", "genre=Horror&sort=POPULARITY_DESC", "Thể loại"),
        AnimeCategory("Mecha", "genre=Mecha&sort=POPULARITY_DESC", "Thể loại"),
        AnimeCategory("Bí ẩn (Mystery)", "genre=Mystery&sort=POPULARITY_DESC", "Thể loại"),
        AnimeCategory("Tâm lý (Psychological)", "genre=Psychological&sort=POPULARITY_DESC", "Thể loại"),
        AnimeCategory("Lãng mạn (Romance)", "genre=Romance&sort=POPULARITY_DESC", "Thể loại"),
        AnimeCategory("Viễn tưởng (Sci-Fi)", "genre=Sci-Fi&sort=POPULARITY_DESC", "Thể loại"),
        AnimeCategory("Đời thường (Slice of Life)", "genre=Slice+of+Life&sort=POPULARITY_DESC", "Thể loại"),
        AnimeCategory("Thể thao (Sports)", "genre=Sports&sort=POPULARITY_DESC", "Thể loại"),
        AnimeCategory("Siêu nhiên (Supernatural)", "genre=Supernatural&sort=POPULARITY_DESC", "Thể loại"),
        AnimeCategory("Giật gân (Thriller)", "genre=Thriller&sort=POPULARITY_DESC", "Thể loại")
    )

    fun buildBrowseApiPath(categoryPath: String, page: Int = 1, limit: Int = 24): String {
        val clean = categoryPath.trim().trimStart('?', '&')
        val params = LinkedHashMap<String, String>()
        if (clean.isNotEmpty()) {
            for (part in clean.split('&')) {
                val eq = part.indexOf('=')
                if (eq > 0) {
                    val k = part.substring(0, eq).trim()
                    val v = part.substring(eq + 1).trim()
                    if (k.isNotEmpty() && v.isNotEmpty()) params[k] = v
                }
            }
        }
        params.putIfAbsent("type", "ANIME")
        params.putIfAbsent("limit", limit.toString())
        val safePage = page.coerceAtLeast(1)
        params["page"] = safePage.toString()
        if (safePage > 1) {
            params["offset"] = ((safePage - 1) * limit).toString()
        }
        val query = params.entries.joinToString("&") { (k, v) -> "$k=$v" }
        return "/api/search/browse?$query"
    }

    fun buildSearchApiPath(query: String, page: Int = 1, limit: Int = 24): String {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val safePage = page.coerceAtLeast(1)
        val offsetPart = if (safePage > 1) "&offset=${(safePage - 1) * limit}" else ""
        return "/api/search/browse?q=$encoded&type=ANIME&limit=$limit&page=$safePage$offsetPart"
    }

    fun buildSourcesApiPath(mediaId: String, episodeNumber: Int): String {
        val encodedId = URLEncoder.encode(mediaId.trim(), "UTF-8")
        return "/api/sources?id=$encodedId&n=${episodeNumber.coerceAtLeast(1)}"
    }

    fun buildWatchDataPath(mediaId: String, slug: String, episodeNumber: Int = 1): String {
        val safeSlug = slug.trim().ifBlank { "anime" }
        return "/watch/${mediaId.trim()}/$safeSlug/__data.json?ep=${episodeNumber.coerceAtLeast(1)}&x-sveltekit-invalidated=01"
    }

    fun slugify(title: String): String {
        val slug = SLUG_NON_ALNUM_REGEX.replace(title.lowercase(Locale.US), "-").trim('-')
        return slug.ifBlank { "anime" }
    }

    fun extractWatchIdAndSlug(urlOrId: String): Pair<String, String> {
        val trimmed = urlOrId.trim()
        val m = WATCH_PATH_REGEX.find(trimmed)
        if (m != null) {
            val id = m.groupValues[1].trim()
            val slug = m.groupValues.getOrNull(2)?.trim().orEmpty().ifBlank { "anime" }
            return id to slug
        }
        val clean = trimmed.substringBefore('?').substringBefore('#').trimEnd('/').substringAfterLast('/')
        return clean to "anime"
    }

    /**
     * Decodes a SvelteKit `devalue` flat pool array starting at [rootIndex].
     * Converts indexed objects/arrays back into standard JSONObject/JSONArray trees,
     * preserving `["Promise", chunkId]` entries as [MiruroDevaluePromise].
     */
    fun decodeDevaluePool(pool: JSONArray, rootIndex: Int = 0): Any? {
        if (rootIndex < 0 || rootIndex >= pool.length()) return null
        return decodeDevalueAt(pool, rootIndex, HashSet(), 0)
    }

    private fun decodeDevalueAt(
        pool: JSONArray,
        idx: Int,
        visiting: HashSet<Int>,
        depth: Int
    ): Any? {
        if (idx < 0 || idx >= pool.length() || depth > 40) return null
        if (!visiting.add(idx)) return null
        try {
            val raw = pool.opt(idx) ?: return null
            if (raw === JSONObject.NULL) return null
            return when (raw) {
                is JSONObject -> {
                    val out = JSONObject()
                    val keys = raw.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        val ref = raw.opt(key)
                        val resolved = if (ref is Number) {
                            decodeDevalueAt(pool, ref.toInt(), visiting, depth + 1)
                        } else {
                            ref
                        }
                        if (resolved != null) {
                            out.put(key, resolved)
                        } else {
                            out.put(key, JSONObject.NULL)
                        }
                    }
                    out
                }
                is JSONArray -> {
                    // Typed devalue wrapper like ["Promise", 4] or ["Date", "2026-..."]
                    if (raw.length() >= 2 && raw.opt(0) is String) {
                        val tag = raw.optString(0)
                        if (tag == "Promise") {
                            val chunkId = raw.optInt(1, -1)
                            return if (chunkId >= 0) MiruroDevaluePromise(chunkId) else null
                        }
                        if (tag == "Date" || tag == "URL" || tag == "BigInt") {
                            val inner = raw.opt(1)
                            return if (inner is Number) {
                                decodeDevalueAt(pool, inner.toInt(), visiting, depth + 1)
                            } else {
                                inner
                            }
                        }
                    }
                    val out = JSONArray()
                    for (i in 0 until raw.length()) {
                        val itemRef = raw.opt(i)
                        val resolved = if (itemRef is Number) {
                            decodeDevalueAt(pool, itemRef.toInt(), visiting, depth + 1)
                        } else {
                            itemRef
                        }
                        out.put(resolved ?: JSONObject.NULL)
                    }
                    out
                }
                else -> raw
            }
        } finally {
            visiting.remove(idx)
        }
    }

    /**
     * Parses a SvelteKit streaming NDJSON response (`text/sveltekit-data` from `/__data.json`
     * or `/watch/<id>/<slug>/__data.json?...`), merging decoded `data` nodes and resolving
     * deferred `chunk` lines into [MiruroNdjsonPayload].
     */
    fun parseSvelteKitNdjson(rawNdjson: String): MiruroNdjsonPayload {
        val mergedRoot = JSONObject()
        val chunks = LinkedHashMap<Int, Any?>()

        for (rawLine in rawNdjson.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty() || !line.startsWith("{")) continue
            val obj = try {
                JSONObject(line)
            } catch (_: Throwable) {
                continue
            }
            when (obj.optString("type")) {
                "data" -> {
                    val nodes = obj.optJSONArray("nodes") ?: continue
                    for (i in 0 until nodes.length()) {
                        val node = nodes.optJSONObject(i) ?: continue
                        if (node.optString("type") != "data") continue
                        val pool = node.optJSONArray("data") ?: continue
                        val decoded = decodeDevaluePool(pool, 0) as? JSONObject ?: continue
                        val keys = decoded.keys()
                        while (keys.hasNext()) {
                            val k = keys.next()
                            mergedRoot.put(k, decoded.opt(k))
                        }
                    }
                }
                "chunk" -> {
                    val chunkId = obj.optInt("id", -1)
                    val pool = obj.optJSONArray("data") ?: continue
                    val decoded = decodeDevaluePool(pool, 0)
                    if (chunkId >= 0) {
                        chunks[chunkId] = decoded
                    }
                }
            }
        }

        // Resolve MiruroDevaluePromise placeholders inside mergedRoot using chunks
        val rootKeys = mergedRoot.keys()
        val toReplace = ArrayList<Pair<String, Any>>()
        while (rootKeys.hasNext()) {
            val k = rootKeys.next()
            val v = mergedRoot.opt(k)
            if (v is MiruroDevaluePromise) {
                val resolved = chunks[v.id]
                if (resolved != null) {
                    toReplace.add(k to resolved)
                }
            }
        }
        for ((k, v) in toReplace) {
            mergedRoot.put(k, v)
        }

        return MiruroNdjsonPayload(mergedRoot, chunks)
    }

    /**
     * Parses Miruro's `/__data.json` home NDJSON into homepage sections.
     */
    fun parseHomeDataJson(
        rawNdjson: String,
        baseUrl: String = DEFAULT_BASE_URL
    ): List<AnimeSection> {
        val payload = parseSvelteKitNdjson(rawNdjson)
        val root = payload.rootData
        val sections = ArrayList<AnimeSection>()

        val spotlightArr = root.optJSONArray("spotlight")
        if (spotlightArr != null) {
            val items = parseAnimeArray(spotlightArr, baseUrl)
            if (items.isNotEmpty()) {
                sections.add(AnimeSection("🔥 Nổi bật trên Miruro", items, "sort=TRENDING_DESC"))
            }
        }

        val railsObj = root.optJSONObject("rails")
            ?: payload.chunks.values.filterIsInstance<JSONObject>().firstOrNull {
                it.has("raw") || it.has("dub")
            }
        if (railsObj != null) {
            val rawLatest = railsObj.optJSONObject("raw")?.optJSONArray("latest")
            if (rawLatest != null) {
                val items = parseAnimeArray(rawLatest, baseUrl)
                if (items.isNotEmpty()) {
                    sections.add(AnimeSection("🆕 Tập mới cập nhật (Sub)", items, "status=RELEASING&sort=POPULARITY_DESC"))
                }
            }
            val dubLatest = railsObj.optJSONObject("dub")?.optJSONArray("latest")
            if (dubLatest != null) {
                val items = parseAnimeArray(dubLatest, baseUrl)
                if (items.isNotEmpty()) {
                    sections.add(AnimeSection("🎙️ Tập mới cập nhật (Dub)", items, "status=RELEASING&sort=TRENDING_DESC"))
                }
            }
        }

        val sideListsObj = root.optJSONObject("sideLists")
            ?: payload.chunks.values.filterIsInstance<JSONObject>().firstOrNull {
                it.has("airing") || it.has("upcoming")
            }
        if (sideListsObj != null) {
            val airingArr = sideListsObj.optJSONArray("airing")
            if (airingArr != null) {
                val items = parseAnimeArray(airingArr, baseUrl)
                if (items.isNotEmpty()) {
                    sections.add(AnimeSection("📺 Đang phát sóng", items, "status=RELEASING&sort=POPULARITY_DESC"))
                }
            }
            val upcomingArr = sideListsObj.optJSONArray("upcoming")
            if (upcomingArr != null) {
                val items = parseAnimeArray(upcomingArr, baseUrl)
                if (items.isNotEmpty()) {
                    sections.add(AnimeSection("🗓️ Sắp ra mắt", items, "status=NOT_YET_RELEASED&sort=POPULARITY_DESC"))
                }
            }
        }

        return sections
    }

    /**
     * Parses `/api/search/browse` JSON response (`{"data": [...]}`) into [List<Anime>].
     */
    fun parseBrowseItems(
        rawJson: String,
        baseUrl: String = DEFAULT_BASE_URL
    ): List<Anime> {
        val trimmed = rawJson.trim()
        if (trimmed.isEmpty()) return emptyList()
        val arr: JSONArray? = try {
            if (trimmed.startsWith("[")) {
                JSONArray(trimmed)
            } else {
                val obj = JSONObject(trimmed)
                obj.optJSONArray("data")
                    ?: obj.optJSONArray("items")
                    ?: obj.optJSONArray("results")
                    ?: obj.optJSONArray("media")
                    ?: obj.optJSONObject("Page")?.optJSONArray("media")
            }
        } catch (_: Throwable) {
            null
        }
        return if (arr != null) parseAnimeArray(arr, baseUrl) else emptyList()
    }

    fun parseAnimeArray(arr: JSONArray, baseUrl: String = DEFAULT_BASE_URL): List<Anime> {
        val out = ArrayList<Anime>(arr.length())
        val seen = HashSet<String>()
        for (i in 0 until arr.length()) {
            val itemObj = arr.optJSONObject(i) ?: continue
            val anime = parseAnimeObject(itemObj, baseUrl) ?: continue
            if (seen.add(anime.url)) {
                out.add(anime)
            }
        }
        return out
    }

    fun parseAnimeObject(obj: JSONObject, baseUrl: String = DEFAULT_BASE_URL): Anime? {
        val id = obj.optString("id").ifBlank { obj.optString("animeId") }.trim()
        if (id.isEmpty()) return null

        val (primaryTitle, altTitle) = extractTitles(obj.opt("title"), id)
        if (primaryTitle.isBlank()) return null

        val cleanBase = baseUrl.trimEnd('/')
        val urlSlug = slugify(primaryTitle)
        val watchUrl = "$cleanBase/watch/$id/$urlSlug"

        val coverUrl = extractCoverUrl(obj)
        val bannerUrl = extractBannerUrl(obj)

        val format = obj.optString("format").trim().uppercase(Locale.US)
        val year = obj.optInt("season_year", 0).takeIf { it > 0 }
            ?: obj.optInt("seasonYear", 0).takeIf { it > 0 }
            ?: 0

        val epObj = obj.optJSONObject("episode_counts") ?: obj.optJSONObject("episodeCounts")
        val latestEp = obj.optInt("episode", 0).takeIf { it > 0 }
            ?: epObj?.optInt("sub", 0)?.takeIf { it > 0 }
            ?: epObj?.optInt("raw", 0)?.takeIf { it > 0 }
            ?: epObj?.optInt("dub", 0)?.takeIf { it > 0 }
            ?: 0
        val totalEps = obj.optInt("episode_count", 0).takeIf { it > 0 }
            ?: obj.optInt("episodeCount", 0).takeIf { it > 0 }
            ?: obj.optInt("episodes", 0).takeIf { it > 0 }
            ?: 0

        val episodeText = when {
            format == "MOVIE" && totalEps <= 1 && latestEp <= 1 -> "Movie"
            latestEp > 0 && totalEps > 0 -> "Tập $latestEp/$totalEps"
            latestEp > 0 -> "Tập $latestEp"
            totalEps > 0 -> "$totalEps tập"
            else -> format.ifBlank { "Anime" }
        }

        val rawScore = obj.optDouble("average_score", Double.NaN).takeIf { !it.isNaN() && it > 0 }
            ?: obj.optDouble("averageScore", Double.NaN).takeIf { !it.isNaN() && it > 0 }
        val scoreStr = rawScore?.let { s ->
            val scaled = if (s > 10.0) s / 10.0 else s
            String.format(Locale.US, "%.1f", scaled)
        }.orEmpty()

        val quality = when (format) {
            "MOVIE" -> "FHD Movie"
            "OVA", "ONA", "SPECIAL" -> "HD • $format"
            else -> "1080p"
        }

        val genres = extractGenres(obj)
        val infoParts = ArrayList<String>()
        if (format.isNotEmpty()) infoParts.add(format)
        if (year > 0) infoParts.add(year.toString())
        if (genres.isNotEmpty()) infoParts.add(genres.take(3).joinToString(", "))

        val summary = cleanDescription(obj.optString("description"))

        return Anime(
            title = primaryTitle,
            subtitle = altTitle.ifBlank { infoParts.joinToString(" • ") },
            url = watchUrl,
            imageUrl = coverUrl,
            bannerUrl = bannerUrl ?: coverUrl,
            episodeText = episodeText,
            quality = quality,
            score = scoreStr,
            summary = summary,
            info = infoParts.joinToString(" • "),
            source = ContentSource.MIRURO,
            catalogLabel = "Miruro"
        )
    }

    /**
     * Parses `/watch/<id>/<slug>/__data.json?ep=...&x-sveltekit-invalidated=01`
     * into [MiruroWatchDetailResult] containing metadata, episode list, and optional `sourcesData`.
     */
    fun parseWatchDetail(
        rawNdjson: String,
        fallbackAnime: Anime,
        baseUrl: String = DEFAULT_BASE_URL
    ): MiruroWatchDetailResult {
        val payload = parseSvelteKitNdjson(rawNdjson)
        val root = payload.rootData
        val cleanBase = baseUrl.trimEnd('/')

        val mediaObj = root.optJSONObject("media") ?: JSONObject()
        val (fallbackId, fallbackSlug) = extractWatchIdAndSlug(fallbackAnime.url)
        val mediaId = mediaObj.optString("id").trim().ifBlank { fallbackId }
        val (parsedTitle, _) = extractTitles(mediaObj.opt("title"), fallbackAnime.title)
        val title = parsedTitle.ifBlank { fallbackAnime.title }
        val urlSlug = slugify(title).ifBlank { fallbackSlug }
        val watchBase = "$cleanBase/watch/$mediaId/$urlSlug"

        val posterUrl = extractCoverUrl(mediaObj) ?: fallbackAnime.imageUrl
        val bannerUrl = extractBannerUrl(mediaObj) ?: fallbackAnime.bannerUrl ?: posterUrl
        val summary = cleanDescription(mediaObj.optString("description")).ifBlank { fallbackAnime.summary }

        val infoParts = ArrayList<String>()
        val format = mediaObj.optString("format").trim()
        if (format.isNotEmpty()) infoParts.add(format)
        val seasonYear = mediaObj.optInt("seasonYear", 0).takeIf { it > 0 }
            ?: mediaObj.optInt("season_year", 0).takeIf { it > 0 }
            ?: 0
        val season = mediaObj.optString("season").trim()
        if (season.isNotEmpty() && seasonYear > 0) {
            infoParts.add("$season $seasonYear")
        } else if (seasonYear > 0) {
            infoParts.add(seasonYear.toString())
        }
        val status = mediaObj.optString("status").trim()
        if (status.isNotEmpty()) {
            infoParts.add(
                when (status.uppercase(Locale.US)) {
                    "RELEASING" -> "Đang chiếu"
                    "FINISHED" -> "Hoàn thành"
                    "NOT_YET_RELEASED" -> "Sắp chiếu"
                    else -> status
                }
            )
        }
        val score = mediaObj.optDouble("averageScore", Double.NaN).takeIf { !it.isNaN() && it > 0 }
            ?: mediaObj.optDouble("average_score", Double.NaN).takeIf { !it.isNaN() && it > 0 }
        if (score != null) {
            val scaled = if (score > 10.0) score / 10.0 else score
            infoParts.add("★ ${String.format(Locale.US, "%.1f", scaled)}")
        }
        val duration = mediaObj.optInt("duration", 0).takeIf { it > 0 }
            ?: mediaObj.optInt("episode_duration_minutes", 0).takeIf { it > 0 }
            ?: 0
        if (duration > 0) infoParts.add("${duration}p/tập")
        val genres = extractGenres(mediaObj)
        if (genres.isNotEmpty()) infoParts.add(genres.joinToString(", "))

        val episodesDataObj = root.optJSONObject("episodesData")
            ?: payload.chunks.values.filterIsInstance<JSONObject>().firstOrNull { it.has("episodes") }

        val episodes = ArrayList<Episode>()
        val epArr = episodesDataObj?.optJSONArray("episodes")
        if (epArr != null) {
            for (i in 0 until epArr.length()) {
                val epObj = epArr.optJSONObject(i) ?: continue
                val num = epObj.optInt("number", i + 1).coerceAtLeast(1)
                val epTitleRaw = epObj.optString("title").trim()
                val filler = epObj.optBoolean("filler", false)
                val epLabel = buildString {
                    append("Tập ")
                    append(num)
                    if (epTitleRaw.isNotEmpty() && !epTitleRaw.equals("Episode $num", ignoreCase = true)) {
                        append(" · ")
                        append(epTitleRaw)
                    }
                    if (filler) append(" [Filler]")
                }
                episodes.add(
                    Episode(
                        title = epLabel,
                        url = "$watchBase?ep=$num",
                        number = num,
                        season = "Miruro · Auto Server",
                        qualityBadge = "1080p",
                        source = ContentSource.MIRURO,
                        playbackKind = "miruro",
                        slug = mediaId,
                        episodeNum = num,
                        detailUrl = watchBase,
                        serverName = "Auto",
                        episodeKey = "auto",
                        episodeId = epObj.optString("id").ifBlank { num.toString() }
                    )
                )
            }
        }

        // Fallback if episodesData chunk wasn't present (e.g. single Movie or partial response)
        if (episodes.isEmpty()) {
            val epCounts = mediaObj.optJSONObject("episodeCounts") ?: mediaObj.optJSONObject("episode_counts")
            val count = maxOf(
                epCounts?.optInt("sub", 0) ?: 0,
                epCounts?.optInt("raw", 0) ?: 0,
                epCounts?.optInt("dub", 0) ?: 0,
                mediaObj.optInt("episodes", 0),
                mediaObj.optInt("episodeCount", 0),
                1
            ).coerceAtMost(2000)
            for (n in 1..count) {
                episodes.add(
                    Episode(
                        title = if (count == 1 && format.equals("MOVIE", ignoreCase = true)) "Full Movie" else "Tập $n",
                        url = "$watchBase?ep=$n",
                        number = n,
                        season = "Miruro · Auto Server",
                        qualityBadge = "1080p",
                        source = ContentSource.MIRURO,
                        playbackKind = "miruro",
                        slug = mediaId,
                        episodeNum = n,
                        detailUrl = watchBase,
                        serverName = "Auto",
                        episodeKey = "auto",
                        episodeId = n.toString()
                    )
                )
            }
        }

        val sourcesDataObj = root.optJSONObject("sourcesData")
            ?: payload.chunks.values.filterIsInstance<JSONObject>().firstOrNull {
                it.has("tracks") && (it.has("providerOrder") || it.has("episodeNumber"))
            }
        val activeEpNum = sourcesDataObj?.optInt("episodeNumber", 0)?.takeIf { it > 0 }
            ?: root.optInt("episodeNumber", 1).coerceAtLeast(1)

        val detail = AnimeDetail(
            title = title,
            posterUrl = posterUrl,
            bannerUrl = bannerUrl,
            summary = summary,
            info = infoParts.joinToString(" • ").ifBlank { fallbackAnime.info },
            episodes = episodes,
            watchUrl = watchBase
        )
        return MiruroWatchDetailResult(
            detail = detail,
            mediaId = mediaId,
            episodeNumber = activeEpNum,
            sourcesData = sourcesDataObj
        )
    }

    private fun extractTitles(titleNode: Any?, fallback: String): Pair<String, String> {
        return when (titleNode) {
            is JSONObject -> {
                val en = titleNode.optString("english").takeIf { it.isNotBlank() && it != "null" }
                val ro = titleNode.optString("romaji").takeIf { it.isNotBlank() && it != "null" }
                val na = titleNode.optString("native").takeIf { it.isNotBlank() && it != "null" }
                val primary = en ?: ro ?: na ?: fallback
                val alt = listOfNotNull(ro, en, na).firstOrNull { !it.equals(primary, ignoreCase = true) }.orEmpty()
                primary to alt
            }
            is String -> titleNode.trim().ifBlank { fallback } to ""
            else -> fallback to ""
        }
    }

    private fun extractCoverUrl(obj: JSONObject): String? {
        val direct = listOf("cover_url", "coverUrl", "poster", "image")
            .firstNotNullOfOrNull { key ->
                obj.optString(key).trim().takeIf { it.startsWith("http", ignoreCase = true) }
            }
        if (direct != null) return direct
        val imgObj = obj.optJSONObject("coverImage") ?: obj.optJSONObject("cover_image")
        if (imgObj != null) {
            for (k in listOf("extraLarge", "extra_large", "large", "medium")) {
                val u = imgObj.optString(k).trim()
                if (u.startsWith("http", ignoreCase = true)) return u
            }
        }
        return null
    }

    private fun extractBannerUrl(obj: JSONObject): String? {
        for (key in listOf("bannerImage", "banner_url", "backgroundImage", "background_url", "backdropUrl", "backdrop_url")) {
            val u = obj.optString(key).trim()
            if (u.startsWith("http", ignoreCase = true)) return u
        }
        val arr = obj.optJSONArray("backdropUrls")
        if (arr != null && arr.length() > 0) {
            val first = arr.optString(0).trim()
            if (first.startsWith("http", ignoreCase = true)) return first
        }
        return null
    }

    private fun extractGenres(obj: JSONObject): List<String> {
        val arr = obj.optJSONArray("genres") ?: return emptyList()
        val out = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) {
            val g = arr.optString(i).trim()
            if (g.isNotEmpty() && g != "null") out.add(g)
        }
        return out
    }

    private fun cleanDescription(raw: String): String {
        if (raw.isBlank() || raw == "null") return ""
        return HTML_TAG_REGEX.replace(raw, " ")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("\\n", "\n")
            .let { MULTI_SPACE_REGEX.replace(it, " ") }
            .trim()
    }
}
