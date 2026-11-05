package com.anpinime.projector

import org.json.JSONArray
import org.json.JSONObject

enum class MovieCatalogFormat {
    FREE1, // KKPhim / PhimAPI / free.cryboiz.workers.dev
    FREE2  // NguonC (phim.nguonc.com)
}

object MovieCatalog {

    fun homeSectionsConfig(format: MovieCatalogFormat): List<Pair<String, String>> = when (format) {
        MovieCatalogFormat.FREE1 -> listOf(
            "🔥 Phim Mới Cập Nhật" to "danh-sach/phim-moi-cap-nhat-v3",
            "📺 Phim Bộ Mới" to "v1/api/danh-sach/phim-bo",
            "🎬 Phim Lẻ Mới" to "v1/api/danh-sach/phim-le",
            "✨ Hoạt Hình" to "v1/api/danh-sach/hoat-hinh",
            "🎭 TV Shows" to "v1/api/danh-sach/tv-shows",
            "🎙️ Phim Thuyết Minh" to "v1/api/danh-sach/phim-thuyet-minh"
        )
        MovieCatalogFormat.FREE2 -> listOf(
            "🔥 Phim Mới Cập Nhật" to "api/films/phim-moi-cap-nhat",
            "🎬 Phim Lẻ Mới" to "api/films/danh-sach/phim-le",
            "📺 Phim Bộ Mới" to "api/films/danh-sach/phim-bo",
            "🎭 TV Shows" to "api/films/danh-sach/tv-shows",
            "✨ Hoạt Hình" to "api/films/the-loai/hoat-hinh",
            "💥 Hành Động" to "api/films/the-loai/hanh-dong"
        )
    }

    fun inferFormat(urlOrBase: String): MovieCatalogFormat {
        val lower = urlOrBase.lowercase()
        return if (lower.contains("nguonc") || lower.contains("/api/film")) {
            MovieCatalogFormat.FREE2
        } else {
            MovieCatalogFormat.FREE1
        }
    }

    fun parseItems(
        rawJson: String,
        baseUrl: String,
        format: MovieCatalogFormat = inferFormat(baseUrl),
        source: ContentSource,
        catalogLabel: String? = null
    ): List<Anime> {
        if (rawJson.isBlank()) return emptyList()
        return try {
            val root = JSONObject(rawJson)
            val dataObj = root.optJSONObject("data")
            val itemsArr = root.optJSONArray("items")
                ?: dataObj?.optJSONArray("items")
                ?: root.optJSONArray("results")
                ?: return emptyList()

            val cdnDomain = dataObj?.optString("APP_DOMAIN_CDN_IMAGE")
                ?.takeIf { it.isNotBlank() }
                ?.trimEnd('/')
                ?: if (format == MovieCatalogFormat.FREE1) "https://phimimg.com" else baseUrl.trimEnd('/')

            val cleanBase = baseUrl.trimEnd('/')
            val out = ArrayList<Anime>(itemsArr.length())
            val seen = HashSet<String>()

            for (i in 0 until itemsArr.length()) {
                val obj = itemsArr.optJSONObject(i) ?: continue
                val slug = obj.optString("slug").trim()
                val title = HTMLDocument.decodeEntities(
                    obj.optString("name").ifEmpty { obj.optString("title") }.trim()
                )
                if (slug.isEmpty() || title.isEmpty()) continue

                val detailUrl = when (format) {
                    MovieCatalogFormat.FREE1 -> "$cleanBase/phim/$slug"
                    MovieCatalogFormat.FREE2 -> "$cleanBase/api/film/$slug"
                }
                if (!seen.add(detailUrl)) continue

                val rawPoster = obj.optString("poster_url").ifEmpty { obj.optString("thumb_url") }
                val rawThumb = obj.optString("thumb_url").ifEmpty { obj.optString("poster_url") }
                val poster = resolveImage(rawPoster, cdnDomain, cleanBase)
                val banner = resolveImage(rawThumb, cdnDomain, cleanBase) ?: poster

                val epCurrent = HTMLDocument.decodeEntities(obj.optString("episode_current").trim())
                val quality = HTMLDocument.decodeEntities(obj.optString("quality").trim())
                val lang = HTMLDocument.decodeEntities(obj.optString("lang").ifEmpty { obj.optString("language") }.trim())
                val year = obj.optString("year").takeIf { it != "0" && it != "null" }.orEmpty().trim()
                val originName = HTMLDocument.decodeEntities(
                    obj.optString("origin_name").ifEmpty { obj.optString("original_name") }.trim()
                )

                val badgeParts = listOf(quality, lang).filter { it.isNotEmpty() }
                val badge = badgeParts.joinToString(" · ").ifEmpty { epCurrent.ifEmpty { "HD" } }
                val subtitle = listOf(epCurrent, year).filter { it.isNotEmpty() }.joinToString(" • ")

                out.add(
                    Anime(
                        title = title,
                        subtitle = subtitle.ifEmpty { originName },
                        url = detailUrl,
                        imageUrl = poster,
                        bannerUrl = banner,
                        episodeText = epCurrent.ifEmpty { badge },
                        quality = badge,
                        summary = originName,
                        info = listOf(year, lang).filter { it.isNotEmpty() }.joinToString(" • "),
                        source = source,
                        catalogLabel = catalogLabel
                    )
                )
            }
            out
        } catch (_: Throwable) {
            emptyList()
        }
    }

    fun parseDetail(
        rawJson: String,
        detailUrl: String,
        baseUrl: String,
        format: MovieCatalogFormat = inferFormat(detailUrl.ifEmpty { baseUrl }),
        source: ContentSource
    ): AnimeDetail? {
        if (rawJson.isBlank()) return null
        return try {
            val root = JSONObject(rawJson)
            val dataObj = root.optJSONObject("data")
            val movie = root.optJSONObject("movie")
                ?: dataObj?.optJSONObject("item")
                ?: root

            val title = HTMLDocument.decodeEntities(
                movie.optString("name").ifEmpty { movie.optString("title") }.trim()
            )
            if (title.isEmpty()) return null

            val cleanBase = baseUrl.trimEnd('/')
            val cdnDomain = dataObj?.optString("APP_DOMAIN_CDN_IMAGE")
                ?.takeIf { it.isNotBlank() }
                ?.trimEnd('/')
                ?: if (format == MovieCatalogFormat.FREE1) "https://phimimg.com" else cleanBase

            val poster = resolveImage(
                movie.optString("poster_url").ifEmpty { movie.optString("thumb_url") },
                cdnDomain,
                cleanBase
            )
            val banner = resolveImage(
                movie.optString("thumb_url").ifEmpty { movie.optString("poster_url") },
                cdnDomain,
                cleanBase
            ) ?: poster

            val rawDesc = movie.optString("content").ifEmpty { movie.optString("description") }
            val summary = HTMLDocument.textContent(rawDesc).ifEmpty { "Chưa có nội dung mô tả." }

            val quality = HTMLDocument.decodeEntities(movie.optString("quality").trim())
            val lang = HTMLDocument.decodeEntities(movie.optString("lang").ifEmpty { movie.optString("language") }.trim())
            val time = HTMLDocument.decodeEntities(movie.optString("time").trim())
            val year = movie.optString("year").takeIf { it != "0" && it != "null" }.orEmpty().trim()
            val epCurrent = HTMLDocument.decodeEntities(movie.optString("episode_current").trim())

            val categories = extractMetadataNames(movie, "category")
            val countries = extractMetadataNames(movie, "country")
            val infoParts = ArrayList<String>()
            if (epCurrent.isNotEmpty()) infoParts.add(epCurrent)
            if (quality.isNotEmpty()) infoParts.add("Chất lượng: $quality")
            if (lang.isNotEmpty()) infoParts.add("Ngôn ngữ: $lang")
            if (time.isNotEmpty()) infoParts.add("Thời lượng: $time")
            if (year.isNotEmpty()) infoParts.add("Năm: $year")
            if (countries.isNotEmpty()) infoParts.add("Quốc gia: ${countries.joinToString(", ")}")
            if (categories.isNotEmpty()) infoParts.add("Thể loại: ${categories.joinToString(", ")}")

            val episodesArr = root.optJSONArray("episodes")
                ?: dataObj?.optJSONObject("item")?.optJSONArray("episodes")
                ?: movie.optJSONArray("episodes")
                ?: JSONArray()

            val episodes = ArrayList<Episode>()
            for (sIdx in 0 until episodesArr.length()) {
                val serverObj = episodesArr.optJSONObject(sIdx) ?: continue
                val serverName = HTMLDocument.decodeEntities(
                    serverObj.optString("server_name").trim().ifEmpty { "Server ${sIdx + 1}" }
                )
                val dataArr = serverObj.optJSONArray("server_data")
                    ?: serverObj.optJSONArray("items")
                    ?: continue

                val audioLabel = detectAudioLabel(serverName, lang)
                val subLabel = detectSubtitleLabel(serverName, lang)
                val badge = buildServerBadge(quality, serverName, audioLabel)

                for (eIdx in 0 until dataArr.length()) {
                    val epObj = dataArr.optJSONObject(eIdx) ?: continue
                    val epName = HTMLDocument.decodeEntities(
                        epObj.optString("name").ifEmpty { epObj.optString("slug") }.trim()
                    ).ifEmpty { "${eIdx + 1}" }
                    val epSlug = epObj.optString("slug").trim()
                    val m3u8 = epObj.optString("link_m3u8").ifEmpty { epObj.optString("m3u8") }.trim()
                    val embed = epObj.optString("link_embed").ifEmpty { epObj.optString("embed") }.trim()
                    val streamLink = m3u8.ifEmpty { embed }
                    if (streamLink.isEmpty()) continue

                    val epNum = Regex("""\d+""").find(epName)?.value?.toIntOrNull() ?: (eIdx + 1)
                    val displayTitle = if (epName.all { it.isDigit() }) "Tập $epName" else epName
                    val epKey = epSlug.ifEmpty { epName }

                    episodes.add(
                        Episode(
                            title = displayTitle,
                            url = detailUrl,
                            number = epNum,
                            season = serverName,
                            qualityBadge = badge,
                            source = source,
                            playbackKind = "catalog_episode",
                            serverIndex = sIdx,
                            episodeIndex = eIdx,
                            directUrl = streamLink,
                            refererUrl = embed.ifEmpty { detailUrl },
                            detailUrl = detailUrl,
                            serverName = serverName,
                            episodeKey = epKey,
                            audioLabel = audioLabel,
                            subtitleLabel = subLabel
                        )
                    )
                }
            }

            AnimeDetail(
                title = title,
                posterUrl = poster,
                bannerUrl = banner,
                summary = summary,
                info = infoParts.joinToString("  •  "),
                episodes = episodes
            )
        } catch (_: Throwable) {
            null
        }
    }

    data class CatalogStreamMatch(
        val primaryUrl: String,
        val isDirectM3u8: Boolean,
        val embedFallbackUrl: String = ""
    )

    fun extractStreamFromDetail(rawJson: String, episode: Episode): CatalogStreamMatch? {
        return try {
            val root = JSONObject(rawJson)
            val dataObj = root.optJSONObject("data")
            val movie = root.optJSONObject("movie")
                ?: dataObj?.optJSONObject("item")
                ?: root
            val episodesArr = root.optJSONArray("episodes")
                ?: dataObj?.optJSONObject("item")?.optJSONArray("episodes")
                ?: movie.optJSONArray("episodes")
                ?: return fallbackFromEpisode(episode)

            val targetServer = episode.serverName.ifEmpty { episode.season }.trim()
            val targetKey = episode.episodeKey.trim()

            // Pass 1: Match by exact serverName + episodeKey (slug or name)
            for (sIdx in 0 until episodesArr.length()) {
                val sObj = episodesArr.optJSONObject(sIdx) ?: continue
                val sName = HTMLDocument.decodeEntities(sObj.optString("server_name").trim())
                if (targetServer.isNotEmpty() && !sName.equals(targetServer, ignoreCase = true)) continue
                val items = sObj.optJSONArray("server_data") ?: sObj.optJSONArray("items") ?: continue
                findEpisodeInServer(items, targetKey, episode)?.let { return it }
            }

            // Pass 2: Match by serverIndex + episodeKey or episodeIndex
            if (episode.serverIndex in 0 until episodesArr.length()) {
                val sObj = episodesArr.optJSONObject(episode.serverIndex)
                val items = sObj?.optJSONArray("server_data") ?: sObj?.optJSONArray("items")
                if (items != null) {
                    findEpisodeInServer(items, targetKey, episode)?.let { return it }
                }
            }

            // Pass 3: Search across all servers by episodeKey or episode number
            for (sIdx in 0 until episodesArr.length()) {
                val sObj = episodesArr.optJSONObject(sIdx) ?: continue
                val items = sObj.optJSONArray("server_data") ?: sObj.optJSONArray("items") ?: continue
                findEpisodeInServer(items, targetKey, episode)?.let { return it }
            }

            fallbackFromEpisode(episode)
        } catch (_: Throwable) {
            fallbackFromEpisode(episode)
        }
    }

    private fun findEpisodeInServer(
        items: JSONArray,
        targetKey: String,
        episode: Episode
    ): CatalogStreamMatch? {
        if (targetKey.isNotEmpty()) {
            for (i in 0 until items.length()) {
                val ep = items.optJSONObject(i) ?: continue
                val slug = ep.optString("slug").trim()
                val name = HTMLDocument.decodeEntities(ep.optString("name").trim())
                if (slug.equals(targetKey, ignoreCase = true) || name.equals(targetKey, ignoreCase = true)) {
                    buildMatch(ep)?.let { return it }
                }
            }
        }
        if (episode.episodeIndex in 0 until items.length()) {
            val ep = items.optJSONObject(episode.episodeIndex)
            if (ep != null) {
                buildMatch(ep)?.let { return it }
            }
        }
        for (i in 0 until items.length()) {
            val ep = items.optJSONObject(i) ?: continue
            val name = HTMLDocument.decodeEntities(ep.optString("name").trim())
            val num = Regex("""\d+""").find(name)?.value?.toIntOrNull()
            if (num != null && num == episode.number) {
                buildMatch(ep)?.let { return it }
            }
        }
        return null
    }

    private fun buildMatch(epObj: JSONObject): CatalogStreamMatch? {
        val m3u8 = epObj.optString("link_m3u8").ifEmpty { epObj.optString("m3u8") }.trim()
        val embed = epObj.optString("link_embed").ifEmpty { epObj.optString("embed") }.trim()
        return when {
            m3u8.startsWith("http") -> CatalogStreamMatch(m3u8, isDirectM3u8 = true, embedFallbackUrl = embed)
            embed.startsWith("http") -> CatalogStreamMatch(embed, isDirectM3u8 = false, embedFallbackUrl = embed)
            else -> null
        }
    }

    private fun fallbackFromEpisode(episode: Episode): CatalogStreamMatch? {
        val direct = episode.directUrl.trim()
        if (direct.startsWith("http")) {
            val isM3u8 = direct.contains(".m3u8", ignoreCase = true) || direct.contains(".mp4", ignoreCase = true)
            return CatalogStreamMatch(direct, isDirectM3u8 = isM3u8, embedFallbackUrl = episode.refererUrl)
        }
        return null
    }

    private fun buildServerBadge(quality: String, serverName: String, audioLabel: String): String {
        val parts = ArrayList<String>()
        val q = quality.ifEmpty { "FHD" }
        parts.add(q)
        if (audioLabel.isNotEmpty() && !q.contains(audioLabel, ignoreCase = true)) {
            parts.add(audioLabel)
        }
        return parts.joinToString(" · ")
    }

    private fun detectAudioLabel(serverName: String, lang: String): String {
        val combined = "$serverName $lang".lowercase()
        return when {
            combined.contains("thuyết minh") || combined.contains("thuyet minh") -> "Thuyết minh"
            combined.contains("lồng tiếng") || combined.contains("long tieng") -> "Lồng tiếng"
            else -> "Vietsub"
        }
    }

    private fun detectSubtitleLabel(serverName: String, lang: String): String {
        val combined = "$serverName $lang".lowercase()
        return if (combined.contains("vietsub") || (!combined.contains("thuyết minh") && !combined.contains("lồng tiếng"))) {
            "Vietsub"
        } else {
            "Audio Việt"
        }
    }

    private fun extractMetadataNames(movie: JSONObject, key: String): List<String> {
        val out = ArrayList<String>()
        val raw = movie.opt(key) ?: return out
        when (raw) {
            is JSONArray -> {
                for (i in 0 until raw.length()) {
                    val item = raw.optJSONObject(i) ?: continue
                    val name = item.optString("name").trim()
                    if (name.isNotEmpty()) out.add(HTMLDocument.decodeEntities(name))
                }
            }
            is JSONObject -> {
                val keys = raw.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val groupObj = raw.optJSONObject(k) ?: continue
                    val listArr = groupObj.optJSONArray("list") ?: continue
                    for (i in 0 until listArr.length()) {
                        val name = listArr.optJSONObject(i)?.optString("name")?.trim().orEmpty()
                        if (name.isNotEmpty()) out.add(HTMLDocument.decodeEntities(name))
                    }
                }
            }
        }
        return out
    }

    private fun resolveImage(raw: String?, cdnDomain: String, baseUrl: String): String? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty() || trimmed == "null") return null
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) return trimmed
        if (trimmed.startsWith("//")) return "https:$trimmed"
        val host = cdnDomain.ifEmpty { baseUrl }.trimEnd('/')
        return "$host/${trimmed.trimStart('/')}"
    }
}
