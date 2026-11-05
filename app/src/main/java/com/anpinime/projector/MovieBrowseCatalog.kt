package com.anpinime.projector

import org.json.JSONArray
import org.json.JSONObject

object MovieBrowseCatalog {

    // IMPORTANT: "Phim AI" ("phim-ai") is intentionally excluded per user requirement.
    private val nguoncLists = listOf(
        "Phim mới cập nhật" to "api/films/phim-moi-cap-nhat",
        "TV shows" to "api/films/danh-sach/tv-shows",
        "Phim lẻ" to "api/films/danh-sach/phim-le",
        "Phim bộ" to "api/films/danh-sach/phim-bo"
    )

    private val nguoncGenres = listOf(
        "Hành Động" to "hanh-dong",
        "Phiêu Lưu" to "phieu-luu",
        "Hoạt Hình" to "hoat-hinh",
        "Hài" to "phim-hai",
        "Hình Sự" to "hinh-su",
        "Tài Liệu" to "tai-lieu",
        "Chính Kịch" to "chinh-kich",
        "Gia Đình" to "gia-dinh",
        "Giả Tượng" to "gia-tuong",
        "Lịch Sử" to "lich-su",
        "Kinh Dị" to "kinh-di",
        "Nhạc" to "nhac",
        "Bí Ẩn" to "bi-an",
        "Lãng Mạn" to "lang-man",
        "Khoa Học Viễn Tưởng" to "khoa-hoc-vien-tuong",
        "Gây Cấn" to "gay-can",
        "Chiến Tranh" to "chien-tranh",
        "Tâm Lý" to "tam-ly",
        "Tình Cảm" to "tinh-cam",
        "Cổ Trang" to "co-trang",
        "Miền Tây" to "mien-tay",
        "Phim 18+" to "phim-18"
    )

    private val nguoncCountries = listOf(
        "Âu Mỹ" to "au-my",
        "Anh" to "anh",
        "Trung Quốc" to "trung-quoc",
        "Indonesia" to "indonesia",
        "Việt Nam" to "viet-nam",
        "Pháp" to "phap",
        "Hồng Kông" to "hong-kong",
        "Hàn Quốc" to "han-quoc",
        "Nhật Bản" to "nhat-ban",
        "Thái Lan" to "thai-lan",
        "Đài Loan" to "dai-loan",
        "Nga" to "nga",
        "Hà Lan" to "ha-lan",
        "Philippines" to "philippines",
        "Ấn Độ" to "an-do",
        "Quốc gia khác" to "quoc-gia-khac"
    )

    private val nguoncLanguages = listOf(
        "Vietsub" to "vietsub",
        "Thuyết Minh" to "thuyet-minh",
        "Lồng Tiếng" to "long-tieng"
    )

    private val nguoncYears: List<Pair<String, String>> = (2026 downTo 2004).map { "$it" to "$it" }

    val kkLists = listOf(
        "Phim Mới" to "danh-sach/phim-moi-cap-nhat-v3",
        "Phim Bộ" to "v1/api/danh-sach/phim-bo",
        "Phim Lẻ" to "v1/api/danh-sach/phim-le",
        "Shows" to "v1/api/danh-sach/tv-shows",
        "Hoạt Hình" to "v1/api/danh-sach/hoat-hinh",
        "Phim Vietsub" to "v1/api/danh-sach/phim-vietsub",
        "Phim Thuyết Minh" to "v1/api/danh-sach/phim-thuyet-minh",
        "Phim Lồng Tiếng" to "v1/api/danh-sach/phim-long-tieng",
        "Phim Bộ Đang Chiếu" to "site/danh-sach/phim-bo-dang-chieu",
        "Phim Bộ Đã Hoàn Thành" to "site/danh-sach/phim-bo-hoan-thanh",
        "Phim Sắp Chiếu" to "v1/api/danh-sach/phim-sap-chieu",
        "Subteam" to "v1/api/danh-sach/subteam"
    )

    private val kkGenres = listOf(
        "Hành Động" to "hanh-dong",
        "Miền Tây" to "mien-tay",
        "Trẻ Em" to "tre-em",
        "Lịch Sử" to "lich-su",
        "Cổ Trang" to "co-trang",
        "Chiến Tranh" to "chien-tranh",
        "Viễn Tưởng" to "vien-tuong",
        "Kinh Dị" to "kinh-di",
        "Tài Liệu" to "tai-lieu",
        "Bí Ẩn" to "bi-an",
        "Phim 18+" to "phim-18",
        "Tình Cảm" to "tinh-cam",
        "Tâm Lý" to "tam-ly",
        "Thể Thao" to "the-thao",
        "Phiêu Lưu" to "phieu-luu",
        "Âm Nhạc" to "am-nhac",
        "Gia Đình" to "gia-dinh",
        "Học Đường" to "hoc-duong",
        "Hài Hước" to "hai-huoc",
        "Hình Sự" to "hinh-su",
        "Võ Thuật" to "vo-thuat",
        "Khoa Học" to "khoa-hoc",
        "Thần Thoại" to "than-thoai",
        "Chính Kịch" to "chinh-kich",
        "Kinh Điển" to "kinh-dien",
        "Phim Ngắn" to "phim-ngan"
    )

    private val kkCountries = listOf(
        "Trung Quốc" to "trung-quoc",
        "Hàn Quốc" to "han-quoc",
        "Nhật Bản" to "nhat-ban",
        "Thái Lan" to "thai-lan",
        "Âu Mỹ" to "au-my",
        "Đài Loan" to "dai-loan",
        "Hồng Kông" to "hong-kong",
        "Ấn Độ" to "an-do",
        "Anh" to "anh",
        "Pháp" to "phap",
        "Canada" to "canada",
        "Quốc Gia Khác" to "quoc-gia-khac",
        "Đức" to "duc",
        "Tây Ban Nha" to "tay-ban-nha",
        "Thổ Nhĩ Kỳ" to "tho-nhi-ky",
        "Hà Lan" to "ha-lan",
        "Indonesia" to "indonesia",
        "Nga" to "nga",
        "Mexico" to "mexico",
        "Ba lan" to "ba-lan",
        "Úc" to "uc",
        "Thụy Điển" to "thuy-dien",
        "Malaysia" to "malaysia",
        "Brazil" to "brazil",
        "Philippines" to "philippines",
        "Bồ Đào Nha" to "bo-dao-nha",
        "Ý" to "y",
        "Đan Mạch" to "dan-mach",
        "UAE" to "uae",
        "Na Uy" to "na-uy",
        "Thụy Sĩ" to "thuy-si",
        "Châu Phi" to "chau-phi",
        "Nam Phi" to "nam-phi",
        "Ukraina" to "ukraina",
        "Ả Rập Xê Út" to "a-rap-xe-ut",
        "Việt Nam" to "viet-nam"
    )

    fun nguoncDefaultCategories(): List<AnimeCategory> {
        val out = ArrayList<AnimeCategory>()
        nguoncLists.forEach { (title, path) ->
            out.add(AnimeCategory(title = title, path = path, group = "Danh mục"))
        }
        nguoncGenres.forEach { (title, slug) ->
            out.add(AnimeCategory(title = title, path = "api/films/the-loai/$slug", group = "Thể loại"))
        }
        nguoncCountries.forEach { (title, slug) ->
            out.add(AnimeCategory(title = title, path = "api/films/quoc-gia/$slug", group = "Quốc gia"))
        }
        nguoncLanguages.forEach { (title, slug) ->
            out.add(AnimeCategory(title = title, path = "api/films/danh-sach/$slug", group = "Ngôn ngữ"))
        }
        nguoncYears.forEach { (title, slug) ->
            out.add(AnimeCategory(title = "Năm $title", path = "api/films/nam-phat-hanh/$slug", group = "Năm phát hành"))
        }
        return out
    }

    fun nguoncCategoriesFromHtml(html: String): List<AnimeCategory> {
        if (html.isBlank()) return nguoncDefaultCategories()
        val linkRegex = Regex(
            """<a\b[^>]*href=["']/(danh-sach|the-loai|quoc-gia|nam-phat-hanh)/([a-z0-9-]+)["'][^>]*>(.*?)</a>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        val lists = ArrayList<Pair<String, String>>()
        val genres = ArrayList<Pair<String, String>>()
        val countries = ArrayList<Pair<String, String>>()
        val languages = ArrayList<Pair<String, String>>()
        val years = ArrayList<Pair<String, String>>()
        val seen = HashSet<String>()

        for (m in linkRegex.findAll(html)) {
            val section = m.groupValues[1].lowercase()
            val slug = m.groupValues[2].lowercase()
            val title = HTMLDocument.textContent(m.groupValues[3])
            if (title.isEmpty()) continue
            // Explicitly skip Phim AI per user requirement
            if (slug == "phim-ai" || title.equals("Phim AI", ignoreCase = true)) continue
            val key = "$section/$slug"
            if (!seen.add(key)) continue

            when (section) {
                "danh-sach" -> {
                    if (slug in listOf("vietsub", "thuyet-minh", "long-tieng")) {
                        languages.add(title to slug)
                    } else {
                        lists.add(title to "api/films/danh-sach/$slug")
                    }
                }
                "the-loai" -> genres.add(title to slug)
                "quoc-gia" -> countries.add(title to slug)
                "nam-phat-hanh" -> years.add(title to slug)
            }
        }

        if (lists.isEmpty() && genres.isEmpty() && countries.isEmpty()) {
            return nguoncDefaultCategories()
        }

        val out = ArrayList<AnimeCategory>()
        out.add(AnimeCategory("Phim mới cập nhật", "api/films/phim-moi-cap-nhat", "Danh mục"))
        (lists.ifEmpty { nguoncLists.drop(1) }).forEach { (title, path) ->
            if (!path.endsWith("/phim-ai") && !title.equals("Phim AI", ignoreCase = true)) {
                out.add(AnimeCategory(title, path, "Danh mục"))
            }
        }
        (genres.ifEmpty { nguoncGenres }).forEach { (title, slug) ->
            out.add(AnimeCategory(title, "api/films/the-loai/$slug", "Thể loại"))
        }
        (countries.ifEmpty { nguoncCountries }).forEach { (title, slug) ->
            out.add(AnimeCategory(title, "api/films/quoc-gia/$slug", "Quốc gia"))
        }
        (languages.ifEmpty { nguoncLanguages }).forEach { (title, slug) ->
            out.add(AnimeCategory(title, "api/films/danh-sach/$slug", "Ngôn ngữ"))
        }
        (years.ifEmpty { nguoncYears }).forEach { (title, slug) ->
            val label = if (title.startsWith("Năm")) title else "Năm $title"
            out.add(AnimeCategory(label, "api/films/nam-phat-hanh/$slug", "Năm phát hành"))
        }
        return out
    }

    fun kkDefaultCategories(): List<AnimeCategory> {
        return buildKkCategories(kkGenres, kkCountries)
    }

    fun kkCategoriesFromApi(genresRaw: String?, countriesRaw: String?): List<AnimeCategory> {
        val dynamicGenres = parseTaxonomyList(genresRaw).ifEmpty { kkGenres }
        val dynamicCountries = parseTaxonomyList(countriesRaw).ifEmpty { kkCountries }
        return buildKkCategories(
            mergePairs(dynamicGenres, kkGenres),
            mergePairs(dynamicCountries, kkCountries)
        )
    }

    private fun buildKkCategories(
        genres: List<Pair<String, String>>,
        countries: List<Pair<String, String>>
    ): List<AnimeCategory> {
        val out = ArrayList<AnimeCategory>()
        kkLists.forEach { (title, path) ->
            out.add(AnimeCategory(title = title, path = path, group = "Danh mục"))
        }
        genres.forEach { (title, slug) ->
            out.add(AnimeCategory(title = title, path = "v1/api/the-loai/$slug", group = "Thể loại"))
        }
        countries.forEach { (title, slug) ->
            out.add(AnimeCategory(title = title, path = "v1/api/quoc-gia/$slug", group = "Quốc gia"))
        }
        return out
    }

    private fun parseTaxonomyList(raw: String?): List<Pair<String, String>> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val arr = if (raw.trimStart().startsWith("[")) {
                JSONArray(raw)
            } else {
                val root = JSONObject(raw)
                root.optJSONArray("items")
                    ?: root.optJSONObject("data")?.optJSONArray("items")
                    ?: JSONArray()
            }
            val out = ArrayList<Pair<String, String>>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val name = obj.optString("name").trim()
                val slug = obj.optString("slug").trim()
                if (name.isNotEmpty() && slug.isNotEmpty()) {
                    out.add(name to slug)
                }
            }
            out
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun mergePairs(
        primary: List<Pair<String, String>>,
        fallback: List<Pair<String, String>>
    ): List<Pair<String, String>> {
        val seen = HashSet<String>()
        val out = ArrayList<Pair<String, String>>()
        for (pair in primary + fallback) {
            if (seen.add(pair.second)) {
                out.add(pair)
            }
        }
        return out
    }

    fun kkWebsiteItems(
        html: String,
        apiBaseUrl: String = "https://phimapi.com",
        siteBaseUrl: String = "https://kkphim.com",
        source: ContentSource = ContentSource.KKPHIM
    ): List<Anime> {
        if (html.isBlank()) return emptyList()
        val rowRegex = Regex("""<tr\b[^>]*>(.*?)</tr>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        val slugRegex = Regex("""href=["']/phim/([a-zA-Z0-9-]+)["']""", RegexOption.IGNORE_CASE)
        val imgRegex = Regex("""<img\b[^>]*src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        val cellRegex = Regex("""<td\b[^>]*>(.*?)</td>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        val h3Regex = Regex("""<h3\b[^>]*>(.*?)</h3>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        val h4Regex = Regex("""<h4\b[^>]*>(.*?)</h4>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

        val out = ArrayList<Anime>()
        val seen = HashSet<String>()

        for (rowMatch in rowRegex.findAll(html)) {
            val row = rowMatch.groupValues[1]
            val slug = slugRegex.find(row)?.groupValues?.get(1)?.trim() ?: continue
            if (slug.isEmpty() || !seen.add(slug)) continue

            val cells = cellRegex.findAll(row).map { it.groupValues[1] }.toList()
            val firstCell = cells.firstOrNull() ?: row
            val title = h3Regex.find(firstCell)?.let { HTMLDocument.textContent(it.groupValues[1]) }
                ?.takeIf { it.isNotEmpty() }
                ?: slug.replace('-', ' ')
            val originTitle = h4Regex.find(firstCell)?.let { HTMLDocument.textContent(it.groupValues[1]) }
                ?.trim()?.trim('(', ')') ?: ""
            val rawImg = imgRegex.find(firstCell)?.groupValues?.get(1)?.let { HTMLDocument.decodeEntities(it) } ?: ""
            val imageUrl = HTMLParser.resolveURL(rawImg, siteBaseUrl)

            val statusBadge = cells.getOrNull(1)?.let { HTMLDocument.textContent(it) } ?: ""
            val yearBadge = cells.getOrNull(2)?.let { HTMLDocument.textContent(it) } ?: ""
            val countryBadge = cells.getOrNull(3)?.let { HTMLDocument.textContent(it) } ?: ""

            val detailUrl = "${apiBaseUrl.trimEnd('/')}/phim/$slug"
            val summaryParts = listOf(originTitle, statusBadge, yearBadge, countryBadge).filter { it.isNotEmpty() }

            out.add(
                Anime(
                    title = title,
                    subtitle = statusBadge.ifEmpty { originTitle },
                    url = detailUrl,
                    imageUrl = imageUrl,
                    bannerUrl = imageUrl,
                    episodeText = statusBadge.ifEmpty { yearBadge },
                    quality = statusBadge.ifEmpty { "HD" },
                    summary = summaryParts.joinToString(" • "),
                    info = listOf(yearBadge, countryBadge).filter { it.isNotEmpty() }.joinToString(" • "),
                    source = source
                )
            )
        }
        return out
    }
}
