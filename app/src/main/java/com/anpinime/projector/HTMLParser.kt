package com.anpinime.projector

import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URI
import java.text.Normalizer

object HTMLParser {

    fun home(html: String, baseURL: String): List<Anime> {
        val items = extractMovieCards(html, baseURL)
        return if (items.isEmpty()) fallbackAnimeLinks(html, baseURL) else items
    }

    fun search(html: String, baseURL: String): List<Anime> {
        return home(html, baseURL)
    }

    fun sections(html: String, baseURL: String): List<AnimeSection> {
        val sections = ArrayList<AnimeSection>()
        val seenTitles = HashSet<String>()
        try {
            val doc = Jsoup.parse(html, baseURL)
            val blocks = doc.select("section, .MovieListTop, .widget-area .widget, .TpRwCont > div")
            for (block in blocks) {
                val headingRaw = block.selectFirst("h1, h2, h3, .Title, .Top > .Title, .widget-title")
                    ?.text()?.trim().orEmpty()
                val title = cleanSectionHeading(headingRaw)
                if (title.isEmpty()) continue
                val blockItems = extractMovieCardsFromElement(block, baseURL)
                if (blockItems.size < 2) continue
                val key = title.lowercase()
                if (!seenTitles.add(key)) continue
                sections.add(AnimeSection(title = title, items = blockItems, categoryPath = inferCategoryPath(title)))
            }
        } catch (_: Throwable) {
        }
        if (sections.isEmpty()) {
            val fallback = home(html, baseURL)
            if (fallback.isNotEmpty()) {
                sections.add(AnimeSection("🔥 Anime Mới Cập Nhật", fallback, "/anime-moi/"))
            }
        }
        return sections
    }

    private fun cleanSectionHeading(raw: String): String {
        val cleaned = raw.replace(Regex("""\s+"""), " ").trim()
        if (cleaned.length < 3 || cleaned.length > 60) return ""
        val lower = cleaned.lowercase()
        if (lower.contains("bình luận") || lower.contains("đăng nhập") || lower.contains("quảng cáo")) return ""
        return cleaned
    }

    private fun inferCategoryPath(title: String): String? {
        val lower = title.lowercase()
        return when {
            lower.contains("xếp hạng") || lower.contains("top") -> "/bang-xep-hang.html"
            lower.contains("anime bộ") -> "/anime-bo/"
            lower.contains("anime lẻ") || lower.contains("movie") -> "/anime-le/"
            lower.contains("mới") || lower.contains("cập nhật") -> "/anime-moi/"
            else -> null
        }
    }

    fun categories(html: String, baseURL: String): List<AnimeCategory> {
        val seen = HashSet<String>()
        val result = ArrayList<AnimeCategory>()

        for (link in HTMLDocument.links(html)) {
            val resolved = resolveURL(link.href, baseURL) ?: continue
            val path = try {
                URI(resolved).path ?: ""
            } catch (_: Throwable) {
                ""
            }
            if (!isCategoryPath(path)) continue
            val normalizedPath = normalizeCategoryPath(path)
            if (!seen.add(normalizedPath)) continue
            val title = cleanCategoryTitle(link.text)
            if (title.isEmpty()) continue
            result.add(AnimeCategory(title = title, path = normalizedPath, group = "Thể loại Anime"))
        }

        if (result.isEmpty()) {
            return listOf(
                AnimeCategory("Anime mới", "/anime-moi/", "Danh mục"),
                AnimeCategory("Anime bộ", "/anime-bo/", "Danh mục"),
                AnimeCategory("Anime lẻ", "/anime-le/", "Danh mục"),
                AnimeCategory("Bảng xếp hạng", "/bang-xep-hang.html", "Danh mục"),
                AnimeCategory("Hành động", "/the-loai/hanh-dong/", "Thể loại"),
                AnimeCategory("Phiêu lưu", "/the-loai/phieu-luu/", "Thể loại"),
                AnimeCategory("Hài hước", "/the-loai/hai-huoc/", "Thể loại"),
                AnimeCategory("Tình cảm", "/the-loai/tinh-cam/", "Thể loại"),
                AnimeCategory("Học đường", "/the-loai/hoc-duong/", "Thể loại"),
                AnimeCategory("Phép thuật", "/the-loai/phep-thuat/", "Thể loại"),
                AnimeCategory("Viễn tưởng", "/the-loai/vien-tuong/", "Thể loại"),
                AnimeCategory("Đời thường", "/the-loai/doi-thuong/", "Thể loại"),
                AnimeCategory("Kinh dị", "/the-loai/kinh-di/", "Thể loại"),
                AnimeCategory("Isekai / Chuyển sinh", "/the-loai/chuyen-sinh/", "Thể loại")
            )
        }
        return result
    }

    fun detail(html: String, pageURL: String, baseURL: String): AnimeDetail {
        val canonicalURL = canonicalAnimeURL(html, pageURL, baseURL) ?: pageURL
        val rawTitle = HTMLDocument.metaContent(html, listOf("og:title", "twitter:title"))
            ?: firstMatch("""<h1[^>]*class=["'][^"']*Title[^"']*["'][^>]*>(.*?)</h1>""", html)
            ?: firstMatch("""<h1[^>]*>(.*?)</h1>""", html)
            ?: HTMLDocument.title(html)
            ?: "Anime"
        val title = cleanDetailTitle(HTMLDocument.textContent(rawTitle))

        val rawSummary = firstMatch(
            """<div[^>]*class=["'][^"']*(?:Description|Content|entry-content|desc|film-description)[^"']*["'][^>]*>(.*?)</div>""",
            html
        ) ?: HTMLDocument.metaContent(html, listOf("og:description", "description", "twitter:description"))
            ?: ""
        val summary = cleanSummary(HTMLDocument.textContent(rawSummary))

        val posterURL = resolveURL(
            HTMLDocument.metaContent(html, listOf("og:image", "twitter:image"))
                ?: firstMatch("""<div[^>]*class=["'][^"']*Image[^"']*["'][^>]*>.*?<img[^>]*src=["']([^"']+)["']""", html)
                ?: "",
            baseURL
        )
        val bannerURL = resolveURL(
            firstMatch("""<div[^>]*class=["'][^"']*TPostBg[^"']*["'][^>]*>.*?<img[^>]*src=["']([^"']+)["']""", html)
                ?: "",
            baseURL
        ) ?: posterURL

        val info = extractAnimeInfo(html)
        val eps = episodes(html, canonicalURL, baseURL)
        val resolvedWatchURL = watchURL(html, canonicalURL, baseURL) ?: eps.lastOrNull()?.url

        return AnimeDetail(
            title = title.ifEmpty { "Anime" },
            posterUrl = posterURL,
            bannerUrl = bannerURL,
            summary = summary.ifEmpty { "Chưa có nội dung tóm tắt." },
            info = info,
            episodes = eps,
            watchUrl = resolvedWatchURL
        )
    }

    fun episodes(html: String, pageURL: String, baseURL: String): List<Episode> {
        val canonicalURL = canonicalAnimeURL(html, pageURL, baseURL) ?: pageURL
        val htmlEps = htmlEpisodes(html, canonicalURL, baseURL)
        val streamed = streamedEpisodes(html, canonicalURL, baseURL)
        if (htmlEps.isNotEmpty()) {
            return mergedEpisodes(primary = htmlEps, fallback = streamed)
        }
        return streamed
    }

    fun hasCompleteStreamedEpisodes(
        html: String,
        pageURL: String,
        baseURL: String,
        precomputed: List<Episode>? = null
    ): Boolean {
        val canonicalURL = canonicalAnimeURL(html, pageURL, baseURL) ?: pageURL
        val eps = precomputed ?: episodes(html, canonicalURL, baseURL)
        return eps.size >= 2 && eps.any { it.animeHash.isNotEmpty() || it.url.contains("/xem-phim", ignoreCase = true) || it.url.contains("/tap-", ignoreCase = true) }
    }

    fun watchURL(html: String, pageURL: String, baseURL: String): String? {
        val canonicalURL = canonicalAnimeURL(html, pageURL, baseURL) ?: pageURL
        val showSlug = animeSlug(canonicalURL)
        val showId = animeIdentifier(canonicalURL)

        try {
            val doc = Jsoup.parse(html, baseURL)
            val watchCtas = doc.select("a.watch_button[href], a.btn-watch[href], a.bw_btn[href], a[href*=/xem-phim]")
            for (watchCta in watchCtas) {
                if (watchCta.closest("nav, header, footer, form, #comments, .comments, .comment-list, .comment, .disqus, blockquote, .sidebar, .related, .MovieList, .TPost") != null) {
                    continue
                }
                val resolved = resolveURL(watchCta.attr("href"), baseURL) ?: continue
                if (belongsToOtherShow(resolved, showSlug, showId)) continue
                val ownedEp = episodeURL(resolved, canonicalURL, showSlug, showId)
                if (!ownedEp.isNullOrBlank()) return ownedEp
                val lowerResolved = resolved.lowercase()
                if (lowerResolved.endsWith("/xem-phim.html") || lowerResolved.contains("/xem-phim/")) {
                    return resolved
                }
            }
        } catch (_: Throwable) {
        }

        for (link in HTMLDocument.links(html)) {
            if (isExcludedContainerLink(link)) continue
            val resolved = resolveURL(link.href, baseURL) ?: continue
            if (belongsToOtherShow(resolved, showSlug, showId)) continue
            val lowerHref = resolved.lowercase()
            val lowerText = link.text.lowercase()
            val lowerAttrs = link.rawAttributes.lowercase()

            val isWatchAction = lowerText.contains("xem phim") ||
                lowerText.contains("xem ngay") ||
                lowerAttrs.contains("watch") ||
                lowerAttrs.contains("bw_btn") ||
                lowerAttrs.contains("btn-watch")

            if (isWatchAction && (lowerHref.endsWith("/xem-phim.html") || lowerHref.contains("/xem-phim/"))) {
                return resolved
            }
            val epUrl = episodeURL(resolved, canonicalURL, showSlug, showId)
            if (epUrl != null && isWatchAction) {
                return epUrl
            }
        }

        val htmlEps = htmlEpisodes(html, canonicalURL, baseURL)
        if (htmlEps.isNotEmpty()) {
            return htmlEps.minByOrNull { if (it.number > 0) it.number else Int.MAX_VALUE }?.url
        }
        val streamed = streamedEpisodes(html, canonicalURL, baseURL)
        if (streamed.isNotEmpty()) {
            return streamed.minByOrNull { if (it.number > 0) it.number else Int.MAX_VALUE }?.url
        }
        return null
    }

    fun isVerifiedAnimeDetail(html: String, pageURL: String, baseURL: String): Boolean {
        val trimmed = html.trim()
        if (trimmed.length < 256) return false

        val pageTitle = (HTMLDocument.title(html) ?: "").lowercase()
        val blockedTitles = listOf(
            "just a moment",
            "xác minh an toàn",
            "xác minh bảo mật",
            "đang kiểm tra",
            "verify you are human",
            "checking your browser",
            "one more step",
            "attention required!",
            "access denied",
            "ddos-guard",
            "security check",
            "403 forbidden",
            "error 52"
        )
        if (blockedTitles.any { pageTitle.contains(it) }) return false

        val detail = detail(html, pageURL, baseURL)
        val validTitle = detail.title.isNotBlank() &&
            !detail.title.equals("Anime", ignoreCase = true) &&
            !blockedTitles.any { detail.title.lowercase().contains(it) }
        if (!validTitle) return false

        val hasEpisodes = detail.episodes.isNotEmpty()
        val hasWatch = !detail.watchUrl.isNullOrBlank()
        val hasMeaningfulSummary = detail.summary.length > 24 &&
            !detail.summary.startsWith("Chưa có nội dung")
        val hasPoster = !detail.posterUrl.isNullOrBlank()
        val hasCanonical = canonicalAnimeURL(html, pageURL, baseURL) != null

        val lowerHtml = html.lowercase()
        val isFullPageChallenge = (lowerHtml.contains("cf-browser-verification") ||
            lowerHtml.contains("challenge-platform") ||
            lowerHtml.contains("_cf_chl_opt")) &&
            !hasEpisodes && !hasWatch && !hasMeaningfulSummary

        if (isFullPageChallenge) return false

        var signals = 0
        if (hasEpisodes) signals++
        if (hasWatch) signals++
        if (hasMeaningfulSummary) signals++
        if (hasPoster) signals++
        if (hasCanonical) signals++

        return signals >= 2
    }

    fun mergedEpisodes(primary: List<Episode>, fallback: List<Episode>): List<Episode> {
        if (primary.isEmpty()) return fallback
        if (fallback.isEmpty()) return primary

        val byServerAndUrl = LinkedHashMap<String, Episode>()
        for (ep in primary + fallback) {
            val normalizedUrl = normalizeEpisodeCompareUrl(ep.url)
            val key = "${ep.season.trim().lowercase()}|$normalizedUrl"
            val existing = byServerAndUrl[key]
            if (existing == null) {
                byServerAndUrl[key] = ep
            } else {
                byServerAndUrl[key] = existing.copy(
                    animeHash = existing.animeHash.ifEmpty { ep.animeHash },
                    animeFilmId = existing.animeFilmId.ifEmpty { ep.animeFilmId },
                    episodeId = existing.episodeId.ifEmpty { ep.episodeId },
                    directUrl = existing.directUrl.ifEmpty { ep.directUrl }
                )
            }
        }

        return byServerAndUrl.values
            .sortedWith(compareBy<Episode>({ if (it.number > 0) it.number else Int.MAX_VALUE }, { it.season }, { it.episodeIndex }))
            .mapIndexed { idx, ep ->
                if (ep.number <= 0) ep.copy(number = idx + 1) else ep
            }
    }

    fun schedule(html: String, baseURL: String): List<AnimeScheduleItem> {
        val results = ArrayList<AnimeScheduleItem>()
        val seen = HashSet<String>()

        try {
            val doc = Jsoup.parse(html, baseURL)
            val dayMappings = listOf(
                DayOfWeek.MONDAY to listOf("thứ 2", "thứ hai", "monday", "mon"),
                DayOfWeek.TUESDAY to listOf("thứ 3", "thứ ba", "tuesday", "tue"),
                DayOfWeek.WEDNESDAY to listOf("thứ 4", "thứ tư", "wednesday", "wed"),
                DayOfWeek.THURSDAY to listOf("thứ 5", "thứ năm", "thursday", "thu"),
                DayOfWeek.FRIDAY to listOf("thứ 6", "thứ sáu", "friday", "fri"),
                DayOfWeek.SATURDAY to listOf("thứ 7", "thứ bảy", "saturday", "sat"),
                DayOfWeek.SUNDAY to listOf("chủ nhật", "cn", "sunday", "sun")
            )

            var currentDay = DayOfWeek.today()

            for (el in doc.select("h2, h3, h4, .day-title, .sched-day, article.TPost, li.TPostMv, div.TPostMv, a[href*=/phim/]")) {
                val tag = el.tagName().lowercase()
                val text = el.text().trim()
                val lower = text.lowercase()
                if (tag in listOf("h2", "h3", "h4") || el.hasClass("day-title") || el.hasClass("sched-day")) {
                    val matchedDay = dayMappings.firstOrNull { (_, keys) -> keys.any { lower.contains(it) } }?.first
                    if (matchedDay != null) {
                        currentDay = matchedDay
                        continue
                    }
                }
                val linkEl = if (tag == "a") el else el.selectFirst("a[href*=/phim/]") ?: continue
                val href = resolveURL(linkEl.attr("href"), baseURL) ?: continue
                if (!href.contains("/phim/")) continue
                val key = "${currentDay.name}|$href"
                if (!seen.add(key)) continue
                val title = (el.selectFirst(".Title, h2, h3")?.text() ?: linkEl.attr("title").ifEmpty { linkEl.text() }).trim()
                if (title.isEmpty()) continue
                val epText = el.selectFirst(".mli-eps, .ep, .episode")?.text()?.trim()
                    ?: el.selectFirst("span.time")?.text()?.trim()
                    ?: "Tập mới"
                val timeText = Regex("""\b(\d{1,2}:\d{2})\b""").find(el.text())?.groupValues?.get(1) ?: ""
                results.add(
                    AnimeScheduleItem(
                        id = key,
                        title = cleanDetailTitle(title),
                        url = href,
                        episodeText = epText,
                        timeText = timeText,
                        dayOfWeek = currentDay
                    )
                )
            }
        } catch (_: Throwable) {
        }
        return results
    }

    // --- Internal Helpers ---

    private fun extractMovieCards(html: String, baseURL: String): List<Anime> {
        return try {
            val doc = Jsoup.parse(html, baseURL)
            extractMovieCardsFromElement(doc, baseURL)
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun extractMovieCardsFromElement(root: org.jsoup.nodes.Element, baseURL: String): List<Anime> {
        val out = ArrayList<Anime>()
        val seen = HashSet<String>()
        try {
            val cards = root.select(".TPostMv, article.TPost, .MovieList li, .film-item, .movie-item")
            for (el in cards) {
                val a = el.selectFirst("a[href]") ?: continue
                val href = resolveURL(a.attr("href"), baseURL) ?: continue
                if (!isAnimeShowURL(href)) continue
                val normalized = normalizeShowUrl(href)
                if (!seen.add(normalized)) continue

                val title = (el.selectFirst(".Title, h2, h3, .title")?.text()
                    ?: a.attr("title").ifEmpty { a.text() }).trim()
                if (title.isEmpty()) continue

                val imgEl = el.selectFirst("img")
                val rawImg = imgEl?.attr("data-src")?.ifEmpty {
                    imgEl.attr("data-lazy-src").ifEmpty { imgEl.attr("src") }
                } ?: ""
                val img = resolveURL(rawImg, baseURL)
                val ep = el.selectFirst(".mli-eps, .ep-status, .Year, .Qlty")?.text()?.trim() ?: ""
                val score = el.selectFirst(".Vote, .AAIco-star, .score")?.text()?.trim() ?: ""

                out.add(
                    Anime(
                        title = cleanDetailTitle(title),
                        subtitle = ep,
                        url = href,
                        imageUrl = img,
                        bannerUrl = img,
                        episodeText = ep,
                        quality = if (ep.isNotEmpty()) ep else "HD",
                        score = score,
                        source = ContentSource.ANIME
                    )
                )
            }
        } catch (_: Throwable) {
        }
        return out
    }

    private fun fallbackAnimeLinks(html: String, baseURL: String): List<Anime> {
        val out = ArrayList<Anime>()
        val seen = HashSet<String>()
        for (link in HTMLDocument.links(html)) {
            if (isExcludedContainerLink(link)) continue
            val resolved = resolveURL(link.href, baseURL) ?: continue
            if (!isAnimeShowURL(resolved)) continue
            val normalized = normalizeShowUrl(resolved)
            if (!seen.add(normalized)) continue
            val title = cleanDetailTitle(link.text)
            if (title.length < 2) continue
            out.add(
                Anime(
                    title = title,
                    url = resolved,
                    quality = "Vietsub",
                    source = ContentSource.ANIME
                )
            )
        }
        return out
    }

    fun canonicalAnimeURL(html: String, pageURL: String, baseURL: String): String? {
        val pageId = animeIdentifier(pageURL)
        val candidates = ArrayList<String>()

        HTMLDocument.metaContent(html, listOf("og:url"))?.let { candidates.add(it) }
        firstMatch("""<link[^>]*rel=["']canonical["'][^>]*href=["']([^"']+)["']""", html)?.let { candidates.add(it) }
        firstMatch("""<link[^>]*href=["']([^"']+)["'][^>]*rel=["']canonical["']""", html)?.let { candidates.add(it) }

        for (raw in candidates) {
            val resolved = resolveURL(raw, baseURL) ?: continue
            if (!isAnimeShowURL(resolved)) continue
            val candidateId = animeIdentifier(resolved)
            if (pageId != null && candidateId != null && !pageId.equals(candidateId, ignoreCase = true)) {
                continue
            }
            return resolved
        }

        if (isAnimeShowURL(pageURL)) return pageURL
        return null
    }

    private fun htmlEpisodes(html: String, canonicalURL: String, baseURL: String): List<Episode> {
        val showSlug = animeSlug(canonicalURL)
        val showId = animeIdentifier(canonicalURL)
        val globalFilmId = Regex(
            """(?:filmInfo\s*\.\s*filmID|filmId|movie_id|id_film)\s*[:=]\s*(?:parseInt\s*\(\s*)?["']?(\d+)["']?""",
            RegexOption.IGNORE_CASE
        ).find(html)?.groupValues?.get(1)
            ?: showId?.removePrefix("a")?.removePrefix("A")
            ?: ""

        val results = ArrayList<Episode>()
        val seenKeys = HashSet<String>()

        try {
            val doc = Jsoup.parse(html, baseURL)
            val candidateAnchors = doc.select(
                ".list-episode a[href], #list-episode a[href], .episode-list a[href], " +
                    ".list-server a[href], ul.list-ep a[href], .halim-list-eps a[href], " +
                    "a.btn-episode[href], a.episode-link[href], a[data-hash][href], a[data-play][href]"
            )
            for (a in candidateAnchors) {
                if (a.closest("nav, header, footer, form, #comments, .comments, .comment-list, .comment, .disqus, blockquote, .sidebar, .related, .MovieList, .TPost") != null) continue
                val resolved = resolveURL(a.attr("href"), baseURL) ?: continue
                if (belongsToOtherShow(resolved, showSlug, showId)) continue
                val hasEpAttrs = a.hasClass("btn-episode") || a.hasAttr("data-hash") || a.hasAttr("data-play") || a.hasAttr("data-id")
                val lowerRes = resolved.lowercase()
                val epUrl = episodeURL(resolved, canonicalURL, showSlug, showId)
                    ?: (resolved.takeIf {
                        hasEpAttrs &&
                            !lowerRes.contains("#comment") &&
                            !lowerRes.contains("/the-loai/") &&
                            !lowerRes.contains("/tag/")
                    })
                    ?: continue

                val labelText = a.text().trim()
                if (labelText.equals("Xem phim", ignoreCase = true) || labelText.equals("Xem ngay", ignoreCase = true)) {
                    continue
                }
                val attrsRaw = a.attributes().joinToString(" ") { "${it.key}=\"${it.value}\"" }
                val label = episodeLabel(labelText, attrsRaw, epUrl) ?: continue
                val epNumber = extractEpisodeNumber(label, epUrl, results.size + 1)

                val serverEl = a.closest(".server-item, .list-server-item, .halim-server, .server")
                val serverName = serverEl?.selectFirst(".server-name, h3, h4, strong, .title-sv, span.name")
                    ?.text()?.trim()?.takeIf { it.isNotEmpty() && it.length <= 32 } ?: "Vietsub"

                val normKey = "${serverName.lowercase()}|${normalizeEpisodeCompareUrl(epUrl)}"
                if (!seenKeys.add(normKey)) continue

                val hash = a.attr("data-hash").ifEmpty { a.attr("data-play") }.ifEmpty { "" }
                val epId = a.attr("data-id").ifEmpty { a.attr("data-ep") }
                val filmId = a.attr("data-film").ifEmpty { a.attr("data-movie-id") }.ifEmpty { globalFilmId }

                results.add(
                    Episode(
                        title = formatEpisodeTitle(label, epNumber),
                        url = epUrl,
                        number = epNumber,
                        season = serverName,
                        qualityBadge = "HD · $serverName",
                        source = ContentSource.ANIME,
                        playbackKind = "anime",
                        episodeIndex = results.size,
                        refererUrl = canonicalURL,
                        animeHash = hash.ifEmpty { if (epId.length >= 10) epId else "" },
                        animeFilmId = filmId,
                        detailUrl = canonicalURL,
                        serverName = serverName,
                        episodeId = epId
                    )
                )
            }
        } catch (_: Throwable) {
        }

        if (results.isEmpty()) {
            for (link in HTMLDocument.links(html)) {
                if (isExcludedContainerLink(link)) continue
                val resolved = resolveURL(link.href, baseURL) ?: continue
                val epUrl = episodeURL(resolved, canonicalURL, showSlug, showId) ?: continue
                val norm = "vietsub|${normalizeEpisodeCompareUrl(epUrl)}"
                if (!seenKeys.add(norm)) continue

                val label = episodeLabel(link.text, link.rawAttributes, epUrl) ?: continue
                if (label.equals("Xem phim", ignoreCase = true) || label.equals("Xem ngay", ignoreCase = true)) continue
                val epNumber = extractEpisodeNumber(label, epUrl, results.size + 1)
                val hash = firstMatch("""data-(?:hash|play)\s*=\s*["']([^"']+)["']""", link.rawAttributes)
                    ?: firstMatch("""data-id\s*=\s*["']([A-Za-z0-9+/=_-]{10,})["']""", link.rawAttributes)
                    ?: ""
                val epId = firstMatch("""data-id\s*=\s*["']([^"']+)["']""", link.rawAttributes) ?: ""
                val filmId = firstMatch("""data-(?:film|movie-id|id-film)\s*=\s*["']([^"']+)["']""", link.rawAttributes)
                    ?: globalFilmId

                results.add(
                    Episode(
                        title = formatEpisodeTitle(label, epNumber),
                        url = epUrl,
                        number = epNumber,
                        season = "Vietsub",
                        qualityBadge = "HD · Vietsub",
                        source = ContentSource.ANIME,
                        playbackKind = "anime",
                        episodeIndex = results.size,
                        refererUrl = canonicalURL,
                        animeHash = hash,
                        animeFilmId = filmId,
                        detailUrl = canonicalURL,
                        serverName = "Vietsub",
                        episodeId = epId
                    )
                )
            }
        }

        return results.sortedWith(compareBy<Episode>({ it.season }, { if (it.number > 0) it.number else Int.MAX_VALUE }, { it.episodeIndex }))
    }

    private fun streamedEpisodes(html: String, canonicalURL: String, baseURL: String): List<Episode> {
        val showKey = animeSlugOrId(canonicalURL)
        val showSlug = animeSlug(canonicalURL)
        val objects = streamedObjects(html)
        val results = ArrayList<Episode>()
        val seen = HashSet<String>()

        for (obj in objects) {
            val epArrays = ArrayList<JSONArray>()
            for (key in listOf("episodes", "episodeList", "listEpisodes", "eps", "data")) {
                obj.optJSONArray(key)?.let { epArrays.add(it) }
            }
            if (epArrays.isEmpty() && (obj.has("episode") || obj.has("slug") || obj.has("tap") || obj.has("url") || obj.has("href"))) {
                epArrays.add(JSONArray().put(obj))
            }

            for (arr in epArrays) {
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i) ?: continue
                    val built = buildStreamedEpisode(item, canonicalURL, baseURL, showKey, showSlug, results.size + 1)
                        ?: continue
                    val norm = normalizeEpisodeCompareUrl(built.url)
                    if (seen.add(norm)) {
                        results.add(built)
                    }
                }
            }
        }
        return results.sortedBy { it.number }
    }

    private fun buildStreamedEpisode(
        item: JSONObject,
        canonicalURL: String,
        baseURL: String,
        showKey: String?,
        showSlug: String?,
        fallbackNum: Int
    ): Episode? {
        val rawUrl = item.optString("url")
            .ifEmpty { item.optString("href") }
            .ifEmpty { item.optString("link") }
        val rawSlug = item.optString("slug")
            .ifEmpty { item.optString("episodeSlug") }
            .ifEmpty { item.optString("tapSlug") }
        val rawName = item.optString("name")
            .ifEmpty { item.optString("title") }
            .ifEmpty { item.optString("episode") }
            .ifEmpty { item.optString("tap") }
            .ifEmpty { item.optString("number") }

        var resolvedUrl: String? = null
        if (rawUrl.isNotEmpty()) {
            resolvedUrl = resolveURL(rawUrl, baseURL)
        } else if (rawSlug.isNotEmpty()) {
            val cleanSlug = rawSlug.trim().removeSuffix(".html")
            val isEpisodeSlugToken = Regex(
                """^(?:(?:tap|ep|episode)-)?(?:\d+(?:\.\d+)?(?:-[a-z0-9]+)*|ova|special|full|movie|end)$""",
                RegexOption.IGNORE_CASE
            ).matches(cleanSlug)
            resolvedUrl = if (rawSlug.startsWith("http") || rawSlug.startsWith("/")) {
                resolveURL(rawSlug, baseURL)
            } else if (!showSlug.isNullOrEmpty() && isEpisodeSlugToken) {
                val slugPart = if (Regex("""^(?:tap|ep|episode)-""", RegexOption.IGNORE_CASE).containsMatchIn(cleanSlug)) {
                    cleanSlug
                } else {
                    "tap-$cleanSlug"
                }
                resolveURL("/phim/$showSlug/$slugPart.html", baseURL)
            } else {
                null
            }
        } else if (rawName.isNotEmpty() && !showSlug.isNullOrEmpty()) {
            // Only synthesize URL from episode name when object looks like a real episode entry for this show
            val itemShowId = item.optString("filmId").ifEmpty { item.optString("movieId") }.ifEmpty { item.optString("animeId") }
            val hasEpSignal = item.has("episodeId") || item.has("episode_id") || item.has("epId") ||
                item.has("id") || item.has("hash") || item.has("play") || item.has("number") || itemShowId.isNotEmpty()
            val expectedNumId = showKey?.removePrefix("a")?.removePrefix("A")
            val belongsHere = hasEpSignal && (itemShowId.isEmpty() || expectedNumId.isNullOrEmpty() || itemShowId == expectedNumId)
            val numToken = if (belongsHere) Regex("""^\s*(?:tập|tap|ep|episode)?\s*(\d{1,4})\s*$""", RegexOption.IGNORE_CASE).find(rawName)?.groupValues?.get(1) else null
            if (numToken != null) {
                resolvedUrl = resolveURL("/phim/$showSlug/tap-$numToken.html", baseURL)
            }
        }

        val finalUrl = resolvedUrl ?: return null
        val epValidUrl = episodeURL(finalUrl, canonicalURL, showSlug, showKey) ?: return null

        val num = item.optInt("number", -1).takeIf { it in 1..5000 }
            ?: extractEpisodeNumber(rawName, epValidUrl, fallbackNum)
        val label = rawName.ifEmpty { "Tập $num" }
        val directStream = item.optString("m3u8")
            .ifEmpty { item.optString("file") }
            .ifEmpty { item.optString("streamUrl") }
            .ifEmpty { item.optString("source") }
        val hash = item.optString("hash").ifEmpty { item.optString("id") }

        return Episode(
            title = formatEpisodeTitle(label, num),
            url = epValidUrl,
            number = num,
            season = "Vietsub",
            qualityBadge = "HD · Vietsub",
            source = ContentSource.ANIME,
            playbackKind = if (directStream.startsWith("http")) "direct" else "anime",
            directUrl = directStream.takeIf { it.startsWith("http") } ?: "",
            refererUrl = canonicalURL,
            animeHash = hash,
            detailUrl = canonicalURL,
            serverName = "Vietsub"
        )
    }

    private fun streamedObjects(html: String): List<JSONObject> {
        val literals = decodedNextPushLiterals(html)
        val out = ArrayList<JSONObject>()
        for (lit in literals) {
            for (jsonStr in extractJSONObjects(lit)) {
                try {
                    out.add(JSONObject(jsonStr))
                } catch (_: Throwable) {
                }
            }
        }
        return out
    }

    private fun decodedNextPushLiterals(html: String): List<String> {
        val out = ArrayList<String>()
        val pushRegex = Regex(
            """self\.__next_f\.push\(\s*\[\s*\d+\s*,\s*("(?:\\.|[^"\\])*")\s*\]\s*\)""",
            RegexOption.DOT_MATCHES_ALL
        )
        for (m in pushRegex.findAll(html)) {
            val rawLiteral = m.groupValues[1]
            try {
                val decoded = JSONArray("[$rawLiteral]").optString(0)
                if (decoded.isNotEmpty()) out.add(decoded)
            } catch (_: Throwable) {
            }
        }
        return out
    }

    private fun extractJSONObjects(text: String): List<String> {
        val results = ArrayList<String>()
        var depth = 0
        var start = -1
        var inString = false
        var escaped = false

        for (i in text.indices) {
            val c = text[i]
            if (inString) {
                if (escaped) {
                    escaped = false
                } else if (c == '\\') {
                    escaped = true
                } else if (c == '"') {
                    inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{' -> {
                    if (depth == 0) start = i
                    depth++
                }
                '}' -> {
                    if (depth > 0) {
                        depth--
                        if (depth == 0 && start >= 0) {
                            results.add(text.substring(start, i + 1))
                            start = -1
                        }
                    }
                }
            }
        }
        return results
    }

    private fun episodeURL(
        candidateURL: String,
        canonicalURL: String,
        showSlug: String?,
        showId: String?
    ): String? {
        val lower = candidateURL.lowercase()
        if (lower.contains("#comment") || lower.contains("javascript:") || lower.contains("/tag/") || lower.contains("/the-loai/")) {
            return null
        }

        val isTapRoute = Regex("""/(?:tap|ep|episode)-[^/?#]+""", RegexOption.IGNORE_CASE).containsMatchIn(lower)
        val isXemPhimRoute = lower.contains("/xem-phim")

        if (!isTapRoute && !isXemPhimRoute) return null

        val candidateId = animeIdentifier(lower)
        val candidateSlug = animeSlug(lower)
        if (!showId.isNullOrEmpty() && !candidateId.isNullOrEmpty()) {
            return if (candidateId.equals(showId, ignoreCase = true)) candidateURL else null
        }
        if (!showId.isNullOrEmpty()) {
            val idPattern = Regex("""(?:^|[-/])${Regex.escape(showId.lowercase())}(?:/|\.html|[?#]|$)""")
            if (idPattern.containsMatchIn(lower)) return candidateURL
        }
        if (!showSlug.isNullOrEmpty() && !candidateSlug.isNullOrEmpty()) {
            if (candidateSlug.equals(showSlug, ignoreCase = true)) return candidateURL
        }
        if (!showSlug.isNullOrEmpty() && candidateId.isNullOrEmpty()) {
            val slugPattern = Regex("""/(?:phim|xem-phim)/${Regex.escape(showSlug.lowercase())}(?:-a\d+)?(?:/|\.html|$)""")
            if (slugPattern.containsMatchIn(lower)) return candidateURL
        }
        return null
    }

    private fun belongsToOtherShow(candidateURL: String, showSlug: String?, showId: String?): Boolean {
        val candidateId = animeIdentifier(candidateURL)
        if (!showId.isNullOrEmpty() && !candidateId.isNullOrEmpty() && !candidateId.equals(showId, ignoreCase = true)) {
            return true
        }
        val candidateSlug = animeSlug(candidateURL)
        if (!showSlug.isNullOrEmpty() && !candidateSlug.isNullOrEmpty() && !candidateSlug.equals(showSlug, ignoreCase = true)) {
            return true
        }
        return false
    }

    private fun episodeLabel(text: String, rawAttrs: String, url: String): String? {
        val cleaned = text.trim()
        val prefixedLabelRegex = Regex(
            """^(?:(?:4k|2k|1080p|720p|480p|full\s*hd|hd|sd)\s*[-:|]?\s*)?(?:tập|tap|ep|episode)\s*\d+(?:\.\d+)?[a-zA-Z]?(?:\s*[-_]\s*\d+)?(?:\s*(?:end|final|full|ova|special|sp|preview|\(\s*(?:end|final|full|hd|4k)\s*\)))?$""",
            RegexOption.IGNORE_CASE
        )
        val bareNumLabelRegex = Regex(
            """^\d+(?:\.\d+)?[a-dA-D]?(?:\s*[-_]\s*\d+)?(?:\s*(?:end|final|full|ova|special|sp|preview|\(\s*end\s*\)))?$""",
            RegexOption.IGNORE_CASE
        )
        if (cleaned.isNotEmpty() && (prefixedLabelRegex.matches(cleaned) || bareNumLabelRegex.matches(cleaned) || cleaned.equals("Full", ignoreCase = true) || cleaned.equals("Xem phim", ignoreCase = true))) {
            return cleaned
        }
        val titleAttr = firstMatch("""\btitle\s*=\s*["']([^"']+)["']""", rawAttrs)?.trim()
        if (!titleAttr.isNullOrEmpty() && (prefixedLabelRegex.matches(titleAttr) || bareNumLabelRegex.matches(titleAttr) || titleAttr.lowercase().contains("tập") || titleAttr.lowercase().contains("tap"))) {
            return titleAttr
        }
        val numMatch = Regex("""/(?:tap|ep|episode)-(\d{1,4})(?:[-/.?#]|$)""", RegexOption.IGNORE_CASE).find(url)
        if (numMatch != null) {
            return "Tập ${numMatch.groupValues[1]}"
        }
        if (Regex("""/(?:tap|ep|episode)-(ova|special|full|movie)""", RegexOption.IGNORE_CASE).containsMatchIn(url)) {
            val special = Regex("""/(?:tap|ep|episode)-([a-zA-Z]+)""", RegexOption.IGNORE_CASE).find(url)?.groupValues?.get(1)
            return special?.uppercase() ?: "Tập 1"
        }
        return null
    }

    private fun isExcludedContainerLink(link: HTMLDocument.Link): Boolean {
        val excludedTags = setOf("nav", "header", "footer", "form", "blockquote")
        if (link.ancestorTags.any { excludedTags.contains(it) }) return true

        val excludedClassTokens = listOf(
            "comment", "disqus", "reply", "user-profile", "login", "register", "dang-nhap", "navbar", "menu", "sidebar", "widget", "related"
        )
        val combinedClasses = link.ancestorClasses.joinToString(" ").lowercase()
        if (excludedClassTokens.any { combinedClasses.contains(it) }) return true

        val lowerAttrs = link.rawAttributes.lowercase()
        if (lowerAttrs.contains("comment") || lowerAttrs.contains("reply")) return true
        return false
    }

    private fun animeIdentifier(url: String): String? {
        return Regex("""-(a\d+)(?:/|\.html|$)""", RegexOption.IGNORE_CASE).find(url)?.groupValues?.get(1)
    }

    private fun animeSlug(url: String): String? {
        val m = Regex("""/(?:phim|xem-phim)/([^/?#]+)""", RegexOption.IGNORE_CASE).find(url) ?: return null
        val seg = m.groupValues[1].removeSuffix(".html")
        return seg.replace(Regex("""-a\d+$""", RegexOption.IGNORE_CASE), "")
    }

    private fun animeSlugOrId(url: String): String? {
        return animeIdentifier(url) ?: animeSlug(url)
    }

    private fun isAnimeShowURL(url: String): Boolean {
        val lower = url.lowercase()
        if (!lower.contains("/phim/")) return false
        if (lower.contains("/tap-") || lower.contains("/xem-phim")) return false
        return true
    }

    private fun normalizeShowUrl(url: String): String {
        return url.trimEnd('/').lowercase()
    }

    private fun normalizeEpisodeCompareUrl(url: String): String {
        return url.substringBefore('#').substringBefore('?').trimEnd('/').lowercase()
    }

    private fun extractEpisodeNumber(label: String, url: String, fallback: Int): Int {
        // Explicit "Tập N" / "Ep N" in label must beat quality tags like "4K - Tập 1" or "1080p Tập 5"
        Regex("""\b(?:tập|tap|ep|episode)\s*[:.#-]?\s*(\d{1,4})(?!\d)""", RegexOption.IGNORE_CASE)
            .find(label)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        val cleanUrl = url.replace(Regex("""-a\d+(?:/|\.html|$)""", RegexOption.IGNORE_CASE), "/")
        Regex("""/(?:tap|ep|episode)-(\d{1,4})(?:[-/.?#]|$)""", RegexOption.IGNORE_CASE)
            .find(cleanUrl)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        val strippedLabel = label.replace(Regex("""\b(?:4k|2k|1080p|720p|480p|360p|x264|x265)\b""", RegexOption.IGNORE_CASE), " ")
        Regex("""^\s*(\d{1,4})\b""").find(strippedLabel)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        return fallback
    }

    private fun formatEpisodeTitle(label: String, number: Int): String {
        val trimmed = label.trim()
        if (trimmed.isEmpty() || trimmed.equals("Xem phim", ignoreCase = true)) {
            return "Tập $number"
        }
        if (trimmed.all { it.isDigit() }) {
            return "Tập $trimmed"
        }
        return trimmed
    }

    private fun cleanDetailTitle(raw: String): String {
        return raw
            .replace(Regex("""\s*\|\s*AnimeVietsub.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s*-\s*AnimeVietsub.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""^Xem phim\s+""", RegexOption.IGNORE_CASE), "")
            .trim()
    }

    private fun cleanSummary(raw: String): String {
        return raw
            .replace(Regex("""^Nội dung phim[:\s]*""", RegexOption.IGNORE_CASE), "")
            .trim()
    }

    private fun extractAnimeInfo(html: String): String {
        return try {
            val doc = Jsoup.parse(html)
            val items = doc.select(".InfoList li, .mvici-left li, .mvici-right li")
                .map { it.text().trim() }
                .filter { it.isNotEmpty() }
            items.take(8).joinToString("  •  ")
        } catch (_: Throwable) {
            ""
        }
    }

    private fun isCategoryPath(path: String): Boolean {
        val lower = path.lowercase()
        return lower.startsWith("/the-loai/") ||
            lower.startsWith("/quoc-gia/") ||
            lower.startsWith("/season/") ||
            lower.startsWith("/nam-") ||
            lower == "/anime-moi/" ||
            lower == "/anime-bo/" ||
            lower == "/anime-le/" ||
            lower == "/bang-xep-hang.html"
    }

    private fun normalizeCategoryPath(path: String): String {
        if (path.endsWith(".html") || path.endsWith("/")) return path
        return "$path/"
    }

    private fun cleanCategoryTitle(raw: String): String {
        return raw.replace(Regex("""\s+"""), " ").trim()
    }

    private fun firstMatch(pattern: String, text: String): String? {
        val m = Regex(pattern, setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).find(text)
        return m?.groupValues?.getOrNull(1)
    }

    fun resolveURL(href: String, baseURL: String): String? {
        val trimmed = href.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("javascript:") || trimmed.startsWith("#")) return null
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) return trimmed
        if (trimmed.startsWith("//")) return "https:$trimmed"
        val cleanBase = baseURL.trimEnd('/')
        return if (trimmed.startsWith("/")) "$cleanBase$trimmed" else "$cleanBase/$trimmed"
    }
}
