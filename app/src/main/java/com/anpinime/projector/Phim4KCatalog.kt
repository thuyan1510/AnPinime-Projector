package com.anpinime.projector

import org.json.JSONArray
import org.json.JSONObject

object Phim4KCatalog {
    const val LABEL_VIP = "Phim4K · VIP/Premium"
    const val LABEL_FREE1 = "Phim4K · Free1"
    const val LABEL_FREE2 = "Phim4K · Free2"

    val homeExtraRows = listOf(
        "Hàn Quốc (VIP)" to "/rest-api/v130/content_by_country_id?id=89&page=1",
        "Trung Quốc (VIP)" to "/rest-api/v130/content_by_country_id?id=2&page=1",
        "Việt Nam (VIP)" to "/rest-api/v130/content_by_country_id?id=108&page=1",
        "Hoạt Hình (VIP)" to "/rest-api/v130/content_by_genre_id?id=74&page=1"
    )

    fun defaultCoreCategories(): List<AnimeCategory> = listOf(
        AnimeCategory("Phim lẻ VIP/Premium", "core:/rest-api/v130/movies", "VIP / Premium"),
        AnimeCategory("Phim bộ VIP/Premium", "core:/rest-api/v130/tvseries", "VIP / Premium"),
        AnimeCategory("Hàn Quốc (VIP)", "core:/rest-api/v130/content_by_country_id?id=89", "VIP / Premium"),
        AnimeCategory("Trung Quốc (VIP)", "core:/rest-api/v130/content_by_country_id?id=2", "VIP / Premium"),
        AnimeCategory("Việt Nam (VIP)", "core:/rest-api/v130/content_by_country_id?id=108", "VIP / Premium"),
        AnimeCategory("Hoạt hình (VIP)", "core:/rest-api/v130/content_by_genre_id?id=74", "VIP / Premium"),
        AnimeCategory("Phim mới (Free1)", "free1:danh-sach/phim-moi-cap-nhat-v3", "Phim4K · Free1"),
        AnimeCategory("Phim lẻ (Free1)", "free1:v1/api/danh-sach/phim-le", "Phim4K · Free1"),
        AnimeCategory("Phim bộ (Free1)", "free1:v1/api/danh-sach/phim-bo", "Phim4K · Free1"),
        AnimeCategory("Hoạt hình (Free1)", "free1:v1/api/danh-sach/hoat-hinh", "Phim4K · Free1"),
        AnimeCategory("TV Shows (Free1)", "free1:v1/api/danh-sach/tv-shows", "Phim4K · Free1"),
        AnimeCategory("Phim Vietsub (Free1)", "free1:v1/api/danh-sach/phim-vietsub", "Phim4K · Free1"),
        AnimeCategory("Phim Thuyết Minh (Free1)", "free1:v1/api/danh-sach/phim-thuyet-minh", "Phim4K · Free1"),
        AnimeCategory("Phim Lồng Tiếng (Free1)", "free1:v1/api/danh-sach/phim-long-tieng", "Phim4K · Free1")
    )

    fun parseCategoriesFromHome(rawJson: String, includeFree2: Boolean = false): List<AnimeCategory> {
        val out = ArrayList<AnimeCategory>()
        val seen = HashSet<String>()
        fun addCat(c: AnimeCategory) {
            if (seen.add(c.path)) out.add(c)
        }

        defaultCoreCategories().filter { it.group == "VIP / Premium" }.forEach(::addCat)

        try {
            val root = JSONObject(rawJson)
            for (arrKey in listOf("all_genre", "features_genre_and_movie")) {
                val arr = root.optJSONArray(arrKey) ?: continue
                for (i in 0 until arr.length()) {
                    val g = arr.optJSONObject(i) ?: continue
                    val id = g.optString("genre_id").trim()
                    val name = g.optString("name").trim()
                    if (id.isNotEmpty() && name.isNotEmpty()) {
                        addCat(
                            AnimeCategory(
                                title = "$name (VIP)",
                                path = "core:/rest-api/v130/content_by_genre_id?id=$id",
                                group = "Thể loại VIP"
                            )
                        )
                    }
                }
            }
            val countryArr = root.optJSONArray("all_country")
            if (countryArr != null) {
                for (i in 0 until countryArr.length()) {
                    val c = countryArr.optJSONObject(i) ?: continue
                    val id = c.optString("country_id").trim()
                    val name = c.optString("name").trim()
                    if (id.isNotEmpty() && name.isNotEmpty()) {
                        addCat(
                            AnimeCategory(
                                title = "$name (VIP)",
                                path = "core:/rest-api/v130/content_by_country_id?id=$id",
                                group = "Quốc gia VIP"
                            )
                        )
                    }
                }
            }
        } catch (_: Throwable) {
        }

        defaultCoreCategories().filter { it.group != "VIP / Premium" }.forEach(::addCat)

        if (includeFree2) {
            addCat(AnimeCategory("Phim mới (Free2)", "free2:api/films/phim-moi-cap-nhat", "Phim4K · Free2"))
            addCat(AnimeCategory("Phim lẻ (Free2)", "free2:api/films/danh-sach/phim-le", "Phim4K · Free2"))
            addCat(AnimeCategory("Phim bộ (Free2)", "free2:api/films/danh-sach/phim-bo", "Phim4K · Free2"))
            addCat(AnimeCategory("TV Shows (Free2)", "free2:api/films/danh-sach/tv-shows", "Phim4K · Free2"))
        }

        return out
    }

    fun parseHomeSections(
        rawJson: String,
        baseUrl: String,
        source: ContentSource = ContentSource.PHIM4K
    ): List<AnimeSection> {
        val sections = ArrayList<AnimeSection>()
        try {
            val root = JSONObject(rawJson)

            root.optJSONObject("slider")?.optJSONArray("slide")?.let { slideArr ->
                val items = parseVideoArray(slideArr, baseUrl, source)
                if (items.isNotEmpty()) {
                    sections.add(AnimeSection("🔥 Nổi bật · VIP/Premium", items))
                }
            }

            root.optJSONArray("latest_movies")?.let { arr ->
                val items = parseVideoArray(arr, baseUrl, source, defaultType = "movie")
                if (items.isNotEmpty()) {
                    sections.add(
                        AnimeSection(
                            "🎬 Phim lẻ mới · VIP/Premium",
                            items,
                            "core:/rest-api/v130/movies"
                        )
                    )
                }
            }

            root.optJSONArray("latest_tvseries")?.let { arr ->
                val items = parseVideoArray(arr, baseUrl, source, defaultType = "tvseries")
                if (items.isNotEmpty()) {
                    sections.add(
                        AnimeSection(
                            "📺 Phim bộ mới · VIP/Premium",
                            items,
                            "core:/rest-api/v130/tvseries"
                        )
                    )
                }
            }

            root.optJSONArray("features_genre_and_movie")?.let { genres ->
                for (i in 0 until genres.length()) {
                    val g = genres.optJSONObject(i) ?: continue
                    val name = g.optString("name").trim()
                    val genreId = g.optString("genre_id").trim()
                    val vids = g.optJSONArray("videos") ?: continue
                    val items = parseVideoArray(vids, baseUrl, source)
                    if (name.isNotEmpty() && items.isNotEmpty()) {
                        val path = if (genreId.isNotEmpty()) "core:/rest-api/v130/content_by_genre_id?id=$genreId" else null
                        sections.add(AnimeSection("$name · VIP", items, path))
                    }
                }
            }
        } catch (_: Throwable) {
        }
        return sections
    }

    fun parseItems(
        rawJson: String,
        baseUrl: String,
        source: ContentSource = ContentSource.PHIM4K
    ): List<Anime> {
        val trimmed = rawJson.trim()
        if (trimmed.isEmpty()) return emptyList()
        return try {
            if (trimmed.startsWith("[")) {
                parseVideoArray(JSONArray(trimmed), baseUrl, source)
            } else {
                val root = JSONObject(trimmed)
                val out = ArrayList<Anime>()
                val seen = HashSet<String>()
                fun appendFrom(arr: JSONArray?, defaultType: String? = null) {
                    if (arr == null) return
                    for (item in parseVideoArray(arr, baseUrl, source, defaultType)) {
                        if (seen.add(item.url)) out.add(item)
                    }
                }
                appendFrom(root.optJSONArray("movie"), "movie")
                appendFrom(root.optJSONArray("tvseries"), "tvseries")
                appendFrom(root.optJSONArray("videos"))
                appendFrom(root.optJSONArray("items"))
                appendFrom(root.optJSONArray("results"))
                appendFrom(root.optJSONArray("data"))
                if (out.isEmpty()) {
                    root.optJSONObject("slider")?.optJSONArray("slide")?.let { appendFrom(it) }
                }
                out
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun parseVideoArray(
        arr: JSONArray,
        baseUrl: String,
        source: ContentSource,
        defaultType: String? = null
    ): List<Anime> {
        val out = ArrayList<Anime>()
        val seen = HashSet<String>()
        val cleanBase = baseUrl.trimEnd('/')

        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val videosId = obj.optString("videos_id")
                .ifEmpty { obj.optString("id") }
                .ifEmpty { obj.optString("action_id") }
                .trim()
            if (videosId.isEmpty()) continue

            val title = HTMLDocument.decodeEntities(
                obj.optString("title").ifEmpty { obj.optString("name") }.trim()
            )
            if (title.isEmpty()) continue

            val isTv = obj.optString("is_tvseries").trim()
            val rawType = obj.optString("type").ifEmpty { obj.optString("action_type") }.trim().lowercase()
            val detailType = when {
                rawType == "tvseries" || rawType == "tv" || isTv == "1" || isTv.equals("true", ignoreCase = true) -> "tvseries"
                rawType == "movie" || isTv == "0" || isTv.equals("false", ignoreCase = true) -> "movie"
                !defaultType.isNullOrEmpty() -> defaultType
                else -> "movie"
            }

            val detailUrl = "$cleanBase/rest-api/v130/single_details?type=$detailType&id=$videosId"
            if (!seen.add(detailUrl)) continue

            val poster = normalizeImage(obj.optString("thumbnail_url").ifEmpty { obj.optString("poster_url") }, cleanBase)
            val banner = normalizeImage(obj.optString("poster_url").ifEmpty { obj.optString("image_link") }, cleanBase) ?: poster
            val quality = obj.optString("video_quality").trim().ifEmpty { "4K / FHD" }
            val release = obj.optString("release").trim()
            val desc = HTMLDocument.textContent(obj.optString("description"))
            val runtime = obj.optString("runtime").trim()

            val subParts = listOf(quality, release.take(4)).filter { it.isNotEmpty() }

            out.add(
                Anime(
                    title = title,
                    subtitle = subParts.joinToString(" • "),
                    url = detailUrl,
                    imageUrl = poster,
                    bannerUrl = banner,
                    episodeText = if (detailType == "tvseries") "Phim bộ VIP" else quality,
                    quality = quality,
                    summary = desc,
                    info = listOf(release.take(4), runtime).filter { it.isNotEmpty() }.joinToString(" • "),
                    source = source,
                    catalogLabel = LABEL_VIP
                )
            )
        }
        return out
    }

    fun parseDetail(
        rawJson: String,
        detailUrl: String,
        baseUrl: String,
        source: ContentSource = ContentSource.PHIM4K
    ): AnimeDetail? {
        return try {
            val root = JSONObject(rawJson)
            val title = HTMLDocument.decodeEntities(root.optString("title").trim())
            if (title.isEmpty()) return null

            val cleanBase = baseUrl.trimEnd('/')
            val poster = normalizeImage(root.optString("thumbnail_url").ifEmpty { root.optString("poster_url") }, cleanBase)
            val banner = normalizeImage(root.optString("poster_url").ifEmpty { root.optString("thumbnail_url") }, cleanBase) ?: poster
            val desc = HTMLDocument.textContent(root.optString("description"))
            val release = root.optString("release").trim()
            val runtime = root.optString("runtime").trim()
            val quality = root.optString("video_quality").trim()

            val genres = extractNames(root.optJSONArray("genre"))
            val countries = extractNames(root.optJSONArray("country"))
            val directors = extractNames(root.optJSONArray("director"))
            val cast = extractNames(root.optJSONArray("cast"))

            val infoParts = ArrayList<String>()
            if (quality.isNotEmpty()) infoParts.add("Chất lượng: $quality")
            if (release.isNotEmpty()) infoParts.add("Phát hành: $release")
            if (runtime.isNotEmpty()) infoParts.add("Thời lượng: $runtime")
            if (countries.isNotEmpty()) infoParts.add("Quốc gia: ${countries.joinToString(", ")}")
            if (genres.isNotEmpty()) infoParts.add("Thể loại: ${genres.joinToString(", ")}")
            if (directors.isNotEmpty()) infoParts.add("Đạo diễn: ${directors.take(3).joinToString(", ")}")
            if (cast.isNotEmpty()) infoParts.add("Diễn viên: ${cast.take(5).joinToString(", ")}")

            val episodes = ArrayList<Episode>()

            // 1. Movie `videos` array
            val videosArr = root.optJSONArray("videos")
            if (videosArr != null) {
                for (i in 0 until videosArr.length()) {
                    val v = videosArr.optJSONObject(i) ?: continue
                    val fileUrl = v.optString("file_url").trim()
                    if (fileUrl.isEmpty()) continue
                    val videoFileId = v.optString("video_file_id").ifEmpty { "${i + 1}" }.trim()
                    val label = HTMLDocument.decodeEntities(v.optString("label").trim()).ifEmpty { "VIP Server ${i + 1}" }
                    val badge = buildQualityBadge(label, fileUrl, quality)
                    val audioLabel = detectAudioLabel(label, fileUrl)
                    val subs = parseSubtitles(v.optJSONArray("subtitle"))
                    val subLabel = if (subs.isNotEmpty()) "Sub rời (${subs.size})" else "Multi-Sub / Embedded"

                    episodes.add(
                        Episode(
                            title = label,
                            url = detailUrl,
                            number = i + 1,
                            season = "VIP / Premium ($label)",
                            qualityBadge = badge,
                            source = source,
                            playbackKind = "phim4k_core",
                            serverIndex = i,
                            episodeIndex = 0,
                            directUrl = fileUrl,
                            detailUrl = detailUrl,
                            serverName = "VIP / Premium ($label)",
                            episodeKey = videoFileId,
                            videoFileId = videoFileId,
                            audioLabel = audioLabel,
                            subtitleLabel = subLabel
                        )
                    )
                }
            }

            // 2. TV Series `season` array
            val seasonArr = root.optJSONArray("season")
            if (seasonArr != null) {
                for (sIdx in 0 until seasonArr.length()) {
                    val sObj = seasonArr.optJSONObject(sIdx) ?: continue
                    val seasonId = sObj.optString("seasons_id").ifEmpty { "${sIdx + 1}" }.trim()
                    val seasonName = HTMLDocument.decodeEntities(
                        sObj.optString("seasons_name").trim().ifEmpty { "Season ${sIdx + 1}" }
                    )
                    val epArr = sObj.optJSONArray("episodes") ?: continue
                    for (eIdx in 0 until epArr.length()) {
                        val eObj = epArr.optJSONObject(eIdx) ?: continue
                        val fileUrl = eObj.optString("file_url").trim()
                        if (fileUrl.isEmpty()) continue
                        val episodeId = eObj.optString("episodes_id").ifEmpty { "${eIdx + 1}" }.trim()
                        val epName = HTMLDocument.decodeEntities(
                            eObj.optString("episodes_name").trim().ifEmpty { "Tập ${eIdx + 1}" }
                        )
                        val epNum = Regex("""\d+""").find(epName)?.value?.toIntOrNull() ?: (eIdx + 1)
                        val badge = buildQualityBadge("$seasonName $epName", fileUrl, quality)
                        val audioLabel = detectAudioLabel("$seasonName $epName", fileUrl)
                        val subs = parseSubtitles(eObj.optJSONArray("subtitle"))
                        val subLabel = if (subs.isNotEmpty()) "Sub rời (${subs.size})" else "Multi-Sub / Embedded"

                        episodes.add(
                            Episode(
                                title = epName,
                                url = detailUrl,
                                number = epNum,
                                season = "VIP · $seasonName",
                                qualityBadge = badge,
                                source = source,
                                playbackKind = "phim4k_core",
                                seasonNum = sIdx,
                                episodeNum = eIdx,
                                serverIndex = sIdx,
                                episodeIndex = eIdx,
                                directUrl = fileUrl,
                                detailUrl = detailUrl,
                                serverName = "VIP · $seasonName",
                                episodeKey = episodeId,
                                seasonId = seasonId,
                                episodeId = episodeId,
                                audioLabel = audioLabel,
                                subtitleLabel = subLabel
                            )
                        )
                    }
                }
            }

            AnimeDetail(
                title = title,
                posterUrl = poster,
                bannerUrl = banner,
                summary = desc.ifEmpty { "Chưa có nội dung mô tả." },
                info = infoParts.joinToString("  •  "),
                episodes = episodes
            )
        } catch (_: Throwable) {
            null
        }
    }

    data class CoreStreamMatch(
        val fileUrl: String,
        val subtitles: List<ExternalSubtitleTrack>
    )

    fun extractStreamFromDetail(rawJson: String, episode: Episode): CoreStreamMatch? {
        return try {
            val root = JSONObject(rawJson)
            val topSubs = parseSubtitles(root.optJSONArray("subtitle"))

            // 1. Match exact video_file_id in `videos`
            val videosArr = root.optJSONArray("videos")
            if (videosArr != null && videosArr.length() > 0) {
                if (episode.videoFileId.isNotEmpty()) {
                    for (i in 0 until videosArr.length()) {
                        val v = videosArr.optJSONObject(i) ?: continue
                        if (v.optString("video_file_id").trim() == episode.videoFileId) {
                            val u = v.optString("file_url").trim()
                            if (u.isNotEmpty()) {
                                return CoreStreamMatch(u, parseSubtitles(v.optJSONArray("subtitle")) + topSubs)
                            }
                        }
                    }
                }
                // Match by label/title
                for (i in 0 until videosArr.length()) {
                    val v = videosArr.optJSONObject(i) ?: continue
                    val label = HTMLDocument.decodeEntities(v.optString("label").trim())
                    if (label.isNotEmpty() && label.equals(episode.title, ignoreCase = true)) {
                        val u = v.optString("file_url").trim()
                        if (u.isNotEmpty()) {
                            return CoreStreamMatch(u, parseSubtitles(v.optJSONArray("subtitle")) + topSubs)
                        }
                    }
                }
                // Match by serverIndex
                if (episode.serverIndex in 0 until videosArr.length() && episode.seasonId.isEmpty()) {
                    val v = videosArr.optJSONObject(episode.serverIndex)
                    val u = v?.optString("file_url")?.trim().orEmpty()
                    if (u.isNotEmpty()) {
                        return CoreStreamMatch(u, parseSubtitles(v?.optJSONArray("subtitle")) + topSubs)
                    }
                }
            }

            // 2. Match exact seasonId + episodeId in `season`
            val seasonArr = root.optJSONArray("season")
            if (seasonArr != null && seasonArr.length() > 0) {
                if (episode.episodeId.isNotEmpty()) {
                    for (sIdx in 0 until seasonArr.length()) {
                        val sObj = seasonArr.optJSONObject(sIdx) ?: continue
                        val sId = sObj.optString("seasons_id").trim()
                        if (episode.seasonId.isNotEmpty() && sId.isNotEmpty() && sId != episode.seasonId) continue
                        val epArr = sObj.optJSONArray("episodes") ?: continue
                        for (eIdx in 0 until epArr.length()) {
                            val eObj = epArr.optJSONObject(eIdx) ?: continue
                            if (eObj.optString("episodes_id").trim() == episode.episodeId) {
                                val u = eObj.optString("file_url").trim()
                                if (u.isNotEmpty()) {
                                    return CoreStreamMatch(u, parseSubtitles(eObj.optJSONArray("subtitle")) + topSubs)
                                }
                            }
                        }
                    }
                }
                if (episode.seasonNum in 0 until seasonArr.length()) {
                    val epArr = seasonArr.optJSONObject(episode.seasonNum)?.optJSONArray("episodes")
                    if (epArr != null && episode.episodeNum in 0 until epArr.length()) {
                        val eObj = epArr.optJSONObject(episode.episodeNum)
                        val u = eObj?.optString("file_url")?.trim().orEmpty()
                        if (u.isNotEmpty()) {
                            return CoreStreamMatch(u, parseSubtitles(eObj?.optJSONArray("subtitle")) + topSubs)
                        }
                    }
                }
            }

            // 3. Fallback to episode.directUrl if present
            if (episode.directUrl.isNotEmpty()) {
                return CoreStreamMatch(episode.directUrl, topSubs)
            }

            // 4. First available video or episode
            if (videosArr != null && videosArr.length() > 0) {
                val v = videosArr.optJSONObject(0)
                val u = v?.optString("file_url")?.trim().orEmpty()
                if (u.isNotEmpty()) {
                    return CoreStreamMatch(u, parseSubtitles(v?.optJSONArray("subtitle")) + topSubs)
                }
            }
            if (seasonArr != null && seasonArr.length() > 0) {
                val epArr = seasonArr.optJSONObject(0)?.optJSONArray("episodes")
                val eObj = epArr?.optJSONObject(0)
                val u = eObj?.optString("file_url")?.trim().orEmpty()
                if (u.isNotEmpty()) {
                    return CoreStreamMatch(u, parseSubtitles(eObj?.optJSONArray("subtitle")) + topSubs)
                }
            }
            null
        } catch (_: Throwable) {
            if (episode.directUrl.isNotEmpty()) CoreStreamMatch(episode.directUrl, emptyList()) else null
        }
    }

    private fun parseSubtitles(arr: JSONArray?): List<ExternalSubtitleTrack> {
        if (arr == null) return emptyList()
        val out = ArrayList<ExternalSubtitleTrack>()
        for (i in 0 until arr.length()) {
            val s = arr.optJSONObject(i) ?: continue
            val url = s.optString("url")
                .ifEmpty { s.optString("file") }
                .ifEmpty { s.optString("src") }
                .trim()
            if (!url.startsWith("http://") && !url.startsWith("https://")) continue
            val lang = s.optString("language")
                .ifEmpty { s.optString("srclang") }
                .ifEmpty { s.optString("lang") }
                .ifEmpty { "vi" }
                .trim()
            val label = s.optString("label")
                .ifEmpty { s.optString("language") }
                .ifEmpty { "Subtitle ${i + 1}" }
                .trim()
            val kind = s.optString("kind").lowercase()
            val mime = when {
                url.lowercase().contains(".srt") || kind.contains("srt") -> "application/x-subrip"
                url.lowercase().contains(".vtt") || kind.contains("vtt") -> "text/vtt"
                url.lowercase().contains(".ass") || url.lowercase().contains(".ssa") -> "text/x-ssa"
                else -> null
            }
            out.add(ExternalSubtitleTrack(url = url, language = lang, label = label, mimeType = mime))
        }
        return out
    }

    internal fun buildQualityBadge(label: String, fileUrl: String, defaultQuality: String = ""): String {
        val combined = "$label $fileUrl $defaultQuality".lowercase()
        val badges = ArrayList<String>()
        when {
            combined.contains("2160p") || combined.contains("4k") || combined.contains("uhd") -> badges.add("4K UHD")
            combined.contains("remux") -> badges.add("Blu-ray Remux")
            combined.contains("1080p") || combined.contains("fhd") || combined.contains("fullhd") -> badges.add("1080p FHD")
            combined.contains("720p") || combined.contains("hd") -> badges.add("HD")
            else -> badges.add("VIP HD")
        }
        if (combined.contains("hdr") || combined.contains("dv") || combined.contains("dolby")) {
            badges.add("HDR")
        }
        if (fileUrl.lowercase().contains(".mkv")) {
            badges.add("MKV")
        }
        val audio = detectAudioLabel(label, fileUrl)
        if (audio.isNotEmpty()) badges.add(audio)
        return badges.distinct().joinToString(" · ")
    }

    private fun detectAudioLabel(label: String, fileUrl: String): String {
        val lower = "$label $fileUrl".lowercase()
        return when {
            lower.contains("thuyết minh") || lower.contains("thuyet minh") || lower.contains("tm") -> "Thuyết minh"
            lower.contains("lồng tiếng") || lower.contains("long tieng") || lower.contains("lt") -> "Lồng tiếng"
            lower.contains("multi") || lower.contains("dual") -> "Multi-Audio"
            else -> "Vietsub"
        }
    }

    private fun extractNames(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        val out = ArrayList<String>()
        for (i in 0 until arr.length()) {
            val name = arr.optJSONObject(i)?.optString("name")?.trim().orEmpty()
            if (name.isNotEmpty() && !name.equals("N/A", ignoreCase = true)) {
                out.add(HTMLDocument.decodeEntities(name))
            }
        }
        return out
    }

    private fun normalizeImage(raw: String?, baseUrl: String): String? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty() || trimmed.endsWith("/null")) return null
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) return trimmed
        if (trimmed.startsWith("//")) return "https:$trimmed"
        return "${baseUrl.trimEnd('/')}/${trimmed.trimStart('/')}"
    }
}
