package com.anpinime.projector

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AetherCatalogTest {

    @Test
    fun contentSourceMigratesPhim4KToAetherAndPreservesOthers() {
        assertEquals(ContentSource.AETHER, ContentSource.fromId("aether"))
        assertEquals(ContentSource.AETHER, ContentSource.fromId("phim4k"))
        assertEquals(ContentSource.AETHER, ContentSource.fromId("PHIM4K"))
        assertEquals("Thúy An", ContentSource.AETHER.title)
        assertEquals("Nguồn 2 · Thúy An", ContentSource.AETHER.subtitle)
        assertEquals("Tú Nam", ContentSource.FILM4K.title)
        assertEquals("Cốm", ContentSource.KKPHIM.title)
        assertEquals(ContentSource.FILM4K, ContentSource.fromId("film4k"))
        assertEquals(ContentSource.KKPHIM, ContentSource.fromId("kkphim"))
        assertEquals(ContentSource.MIRURO, ContentSource.fromId("anime"))
        assertEquals(ContentSource.MIRURO, ContentSource.fromId("miruro"))
        assertEquals("Nguồn 4 · Anime Miruro", ContentSource.MIRURO.subtitle)
    }

    @Test
    fun episodeJsonDeserializationMigratesPhim4KPlaybackKind() {
        val legacyJson = JSONObject()
            .put("url", "https://aether.st/media/tmdb-movie-27205")
            .put("number", 1)
            .put("title", "Full HD")
            .put("season", "Server 1 · aether.st")
            .put("source", "phim4k")
            .put("playbackKind", "phim4k_core")
            .put("slug", "movie:27205")

        val ep = Episode.fromJsonObject(legacyJson)
        assertEquals(ContentSource.AETHER, ep.source)
        assertEquals("aether_web", ep.playbackKind)
        assertEquals("https://aether.st/media/tmdb-movie-27205", ep.url)
    }

    @Test
    fun parseDomainsFromRentryHtmlPrioritizesActiveAndSkipsDeadDomains() {
        val sampleHtml = """
            <html>
            <body>
            <table>
                <thead>
                    <tr><th>URL</th><th>Status</th></tr>
                </thead>
                <tbody>
                    <tr><td><a href="https://aether.mom">aether.mom</a></td><td>❌</td></tr>
                    <tr><td><a href="https://aether.cx">aether.cx</a></td><td>🟡</td></tr>
                    <tr><td><a href="https://aether.st">aether.st</a></td><td>✅</td></tr>
                    <tr><td><a href="https://aether.ist">aether.ist</a></td><td>✅</td></tr>
                </tbody>
            </table>
            </body>
            </html>
        """.trimIndent()

        val domains = AetherCatalog.parseDomainsFromRentryHtml(sampleHtml)
        assertEquals(listOf("https://aether.st", "https://aether.ist"), domains)
        assertFalse(domains.contains("https://aether.cx"))
        assertFalse(domains.contains("https://aether.mom"))

        val onlyYellowHtml = """
            <table><tbody>
                <tr><td><a href="https://aether.mom">aether.mom</a></td><td>❌</td></tr>
                <tr><td><a href="https://aether.cx">aether.cx</a></td><td>🟡</td></tr>
            </tbody></table>
        """.trimIndent()
        assertEquals(listOf("https://aether.cx"), AetherCatalog.parseDomainsFromRentryHtml(onlyYellowHtml))
    }

    @Test
    fun urlBuildersAndDomainRewritingUseStableTmdbIds() {
        val movieUrl = AetherCatalog.buildMovieUrl("27205", "https://aether.st")
        assertEquals("https://aether.st/media/tmdb-movie-27205", movieUrl)

        val tvEpUrl = AetherCatalog.buildTvEpisodeUrl("1399", "3624", "63056", "https://aether.st")
        assertEquals("https://aether.st/media/tmdb-tv-1399/3624/63056", tvEpUrl)

        val rewritten = AetherCatalog.rewriteUrlToDomain(tvEpUrl, "https://aether.ist")
        assertEquals("https://aether.ist/media/tmdb-tv-1399/3624/63056", rewritten)

        assertEquals(
            AetherCatalog.MediaRef("movie", "27205"),
            AetherCatalog.parseMediaRef("aether://movie/27205")
        )
        assertEquals(
            AetherCatalog.MediaRef("tv", "1399"),
            AetherCatalog.parseMediaRef("aether://tv/1399")
        )
        assertEquals(
            AetherCatalog.MediaRef("movie", "27205"),
            AetherCatalog.parseMediaRef(movieUrl)
        )
        assertEquals(
            AetherCatalog.MediaRef("tv", "1399", "3624", "63056"),
            AetherCatalog.parseMediaRef(tvEpUrl)
        )
    }

    @Test
    fun parseTmdbItemsAndMovieAndTvDetailsProduceValidModels() {
        val listJson = """
            {
                "results": [
                    {
                        "id": 27205,
                        "media_type": "movie",
                        "title": "Kẻ Đánh Cắp Giấc Mơ",
                        "original_title": "Inception",
                        "poster_path": "/inception.jpg",
                        "backdrop_path": "/inception_bg.jpg",
                        "overview": "Giấc mơ trong mơ",
                        "release_date": "2010-07-15",
                        "vote_average": 8.4
                    }
                ]
            }
        """.trimIndent()

        val items = AetherCatalog.parseItems(listJson, defaultMediaType = "movie")
        assertEquals(1, items.size)
        assertEquals("aether://movie/27205", items[0].url)
        assertEquals(ContentSource.AETHER, items[0].source)

        val movieDetailJson = """
            {
                "id": 27205,
                "title": "Kẻ Đánh Cắp Giấc Mơ",
                "original_title": "Inception",
                "poster_path": "/inception.jpg",
                "backdrop_path": "/inception_bg.jpg",
                "overview": "Giấc mơ trong mơ",
                "release_date": "2010-07-15",
                "runtime": 148,
                "vote_average": 8.4,
                "genres": [{"id": 28, "name": "Hành Động"}]
            }
        """.trimIndent()

        val movieDetail = AetherCatalog.parseMovieDetail(
            rawJson = movieDetailJson,
            domains = listOf("https://aether.st", "https://aether.ist")
        )
        assertNotNull(movieDetail)
        assertTrue(movieDetail!!.episodes.isNotEmpty())
        assertEquals("https://aether.st/media/tmdb-movie-27205", movieDetail.episodes[0].url)
        assertTrue(movieDetail.episodes[0].season.contains("Tự động"))
        assertEquals("aether_stream", movieDetail.episodes[0].playbackKind)

        val tvShowJson = """
            {
                "id": 1399,
                "name": "Trò Chơi Vương Quyền",
                "original_name": "Game of Thrones",
                "poster_path": "/got.jpg",
                "backdrop_path": "/got_bg.jpg",
                "overview": "Winter is coming",
                "first_air_date": "2011-04-17",
                "vote_average": 8.5,
                "seasons": [
                    {"id": 3624, "season_number": 1, "episode_count": 2, "name": "Season 1"}
                ]
            }
        """.trimIndent()

        val season1Json = """
            {
                "id": 3624,
                "season_number": 1,
                "episodes": [
                    {"id": 63056, "episode_number": 1, "name": "Winter Is Coming"},
                    {"id": 63057, "episode_number": 2, "name": "The Kingsroad"}
                ]
            }
        """.trimIndent()

        val seasons = AetherCatalog.parseTvSeasonsList(tvShowJson)
        assertEquals(1, seasons.size)
        assertEquals("3624", seasons[0].seasonTmdbId)
        assertEquals(1, seasons[0].seasonNumber)

        val tvDetail = AetherCatalog.parseTvDetail(
            rawShowJson = tvShowJson,
            seasonPayloads = mapOf(1 to season1Json),
            primaryDomain = "https://aether.st"
        )
        assertNotNull(tvDetail)
        assertEquals(2, tvDetail!!.episodes.size)
        assertEquals("https://aether.st/media/tmdb-tv-1399/3624/63056", tvDetail.episodes[0].url)
        assertEquals("aether_stream", tvDetail.episodes[0].playbackKind)
        assertEquals("3624", tvDetail.episodes[0].seasonId)
        assertEquals("63056", tvDetail.episodes[0].episodeId)
        assertEquals(1, tvDetail.episodes[0].seasonNum)
        assertEquals(1, tvDetail.episodes[0].episodeNum)
    }

    @Test
    fun parseChromeMajorVersionExtractsVersionCorrectly() {
        assertEquals(
            124,
            WebViewHelper.parseChromeMajorVersion(
                "Mozilla/5.0 (Linux; Android 11; K670Pro Build/RP1A) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/124.0.6367.82 Safari/537.36"
            )
        )
        assertEquals(
            69,
            WebViewHelper.parseChromeMajorVersion(
                "Mozilla/5.0 (Linux; Android 7.1.2) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/69.0.3497.100 Mobile Safari/537.36"
            )
        )
        assertEquals(0, WebViewHelper.parseChromeMajorVersion("Mozilla/5.0 (Custom UA without Chrome token)"))
    }

    @Test
    fun aetherProvidersModelAndResolutionIntegrity() {
        val providers = listOf(
            PlayerActivity.AetherProvider("auto", "Tự động", "Tự động quét tìm nguồn tốt nhất"),
            PlayerActivity.AetherProvider("febbox", "Febbox", "4K / Siêu nét (Nhanh nhất)"),
            PlayerActivity.AetherProvider("showbox", "Showbox", "Full HD / Ổn định"),
            PlayerActivity.AetherProvider("vidsrc", "VidSrc", "Kho phim phong phú")
        )
        assertEquals(4, providers.size)
        assertEquals("auto", providers[0].id)
        assertEquals("febbox", providers[1].id)
        assertTrue(providers.any { it.name == "Showbox" })
        assertTrue(providers.any { it.name == "VidSrc" })
    }
}
