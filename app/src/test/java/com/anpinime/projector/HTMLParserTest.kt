package com.anpinime.projector

import org.junit.Assert.*
import org.junit.Test

class HTMLParserTest {

    private val base = "https://animevietsub.vip"

    @Test
    fun resolveURLHandlesVariousFormats() {
        assertEquals("https://animevietsub.vip/phim/bleach-a123", HTMLParser.resolveURL("/phim/bleach-a123", base))
        assertEquals("https://cdn.example.com/stream.m3u8", HTMLParser.resolveURL("//cdn.example.com/stream.m3u8", base))
        assertEquals("https://external.test/video.mp4", HTMLParser.resolveURL("https://external.test/video.mp4", base))
        assertNull(HTMLParser.resolveURL("javascript:void(0)", base))
        assertNull(HTMLParser.resolveURL("#anchor", base))
    }

    @Test
    fun homeExtractsAnimeCards() {
        val html = """
            <div class="MovieList">
                <article class="TPost">
                    <a href="/phim/naruto-shippuden-a456/">
                        <div class="Image"><img src="/posters/naruto.jpg" alt="Naruto Shippuden"/></div>
                        <h2 class="Title">Naruto Shippuden</h2>
                        <span class="mli-eps">Tập 500 Vietsub</span>
                    </a>
                </article>
            </div>
        """.trimIndent()

        val list = HTMLParser.home(html, base)
        assertEquals(1, list.size)
        val anime = list[0]
        assertEquals("Naruto Shippuden", anime.title)
        assertEquals("https://animevietsub.vip/phim/naruto-shippuden-a456/", anime.url)
        assertEquals("https://animevietsub.vip/posters/naruto.jpg", anime.imageUrl)
        assertEquals("Tập 500 Vietsub", anime.episodeText)
    }

    @Test
    fun scheduleParsesDaysAndItems() {
        val html = """
            <div class="schedule-container">
                <h2>Thứ 2</h2>
                <div class="schedule-day">
                    <div class="TPostMv">
                        <a href="/phim/bleach-a123/">
                            <span class="Title">Bleach: Thousand-Year Blood War</span>
                            <span class="time">23:00</span>
                            <span class="episode">Tập 12</span>
                        </a>
                    </div>
                </div>
            </div>
        """.trimIndent()

        val items = HTMLParser.schedule(html, base)
        assertTrue(items.isNotEmpty())
        val item = items[0]
        assertEquals(DayOfWeek.MONDAY, item.dayOfWeek)
        assertEquals("Bleach: Thousand-Year Blood War", item.title)
        assertEquals("23:00", item.timeText)
        assertEquals("Tập 12", item.episodeText)
    }

    @Test
    fun episodesRejectsFalseStreamedJsonAndSiblingAnimeIds() {
        val html = """
            <html><head>
              <link rel="canonical" href="https://animevietsub.best/phim/bleach-a59/" />
              <title>Bleach | AnimeVietsub</title>
            </head><body>
              <a class="watch_button" href="/phim/bleach-a59/xem-phim.html">Xem phim</a>
              <div class="list-server">
                <div class="server-item">
                  <span class="server-name">Vietsub #1</span>
                  <ul class="list-episode">
                    <li><a class="btn-episode" href="/phim/bleach-a59/tap-01.html" data-hash="HASH1" data-film="59">01</a></li>
                    <li><a class="btn-episode" href="/phim/bleach-a59/tap-02.html" data-hash="HASH2" data-film="59">02</a></li>
                    <li><a class="btn-episode" href="/phim/bleach-a59/tap-ova-98765.html" data-hash="HASHOVA" data-film="59">OVA</a></li>
                  </ul>
                </div>
                <div class="server-item">
                  <span class="server-name">Server Dự Phòng</span>
                  <ul class="list-episode">
                    <li><a class="btn-episode" href="/phim/bleach-a59/tap-01-sv2.html" data-hash="BK1" data-film="59">01</a></li>
                  </ul>
                </div>
              </div>
              <div class="related">
                <article class="TPost">
                  <a href="/phim/bleach-huyet-chien-a5985/tap-40.html">Tập 40</a>
                </article>
              </div>
              <script>
                self.__next_f.push([1,"{\"episodes\":[{\"name\":\"Tập 999\",\"slug\":\"wrong-show-tap-999\"},{\"name\":\"Tập 888\",\"url\":\"/phim/other-show-a5985/tap-888.html\"}]}"])
              </script>
            </body></html>
        """.trimIndent()

        val eps = HTMLParser.episodes(html, "https://animevietsub.best/phim/bleach-a59/", "https://animevietsub.best")
        assertEquals(4, eps.size)
        // Ensure a5985 sibling episode and streamed JSON fake episodes are excluded
        assertFalse(eps.any { it.number == 40 || it.number == 999 || it.number == 888 || it.number == 98765 })
        // Distinct servers both preserved for episode 1
        assertEquals(2, eps.count { it.number == 1 })
        assertEquals("Server Dự Phòng", eps.first { it.animeHash == "BK1" }.season)
        assertEquals("HASH1", eps.first { it.number == 1 && it.season == "Vietsub #1" }.animeHash)
        assertEquals("59", eps.first().animeFilmId)
        assertEquals("https://animevietsub.best/phim/bleach-a59/xem-phim.html", HTMLParser.watchURL(html, "https://animevietsub.best/phim/bleach-a59/", "https://animevietsub.best"))
    }

    @Test
    fun sectionsParsesHomeSectionBlocks() {
        val html = """
            <html><body>
              <section>
                <div class="Top"><h2 class="Title">Mới cập nhật</h2></div>
                <ul class="MovieList">
                  <li class="TPostMv"><article class="TPost"><a href="/phim/one-piece-a1/">
                    <div class="Image"><img src="/img/op.jpg" /></div>
                    <h3 class="Title">One Piece</h3>
                    <span class="mli-eps">TẬP 1122</span>
                  </a></article></li>
                  <li class="TPostMv"><article class="TPost"><a href="/phim/dandadan-a2/">
                    <div class="Image"><img src="/img/ddd.jpg" /></div>
                    <h3 class="Title">DanDaDan</h3>
                    <span class="mli-eps">TẬP 12</span>
                  </a></article></li>
                </ul>
              </section>
              <section>
                <div class="Top"><h2 class="Title">Bảng xếp hạng</h2></div>
                <ul class="MovieList">
                  <li class="TPostMv"><article class="TPost"><a href="/phim/solo-a3/"><h3 class="Title">Solo Leveling</h3></a></article></li>
                  <li class="TPostMv"><article class="TPost"><a href="/phim/frieren-a4/"><h3 class="Title">Frieren</h3></a></article></li>
                </ul>
              </section>
            </body></html>
        """.trimIndent()

        val sections = HTMLParser.sections(html, "https://animevietsub.best")
        assertEquals(2, sections.size)
        assertEquals("Mới cập nhật", sections[0].title)
        assertEquals("/anime-moi/", sections[0].categoryPath)
        assertEquals(2, sections[0].items.size)
        assertEquals("Bảng xếp hạng", sections[1].title)
        assertEquals("/bang-xep-hang.html", sections[1].categoryPath)
    }

    @Test
    fun rejectsCloudflareVietnameseTitlesCommentUsernamesAndExtractsRealEpisodeNumbers() {
        val cfVi = """<html><head><title>Xác Minh An Toàn - AnimeVietsub</title></head><body><div class="captcha-placeholder"></div></body></html>"""
        val cfCheck = """<html><head><title>Đang Kiểm Tra Trình Duyệt...</title></head><body></body></html>"""
        assertFalse(HTMLParser.isVerifiedAnimeDetail(cfVi, "https://animevietsub.best/phim/bleach-a1/", "https://animevietsub.best"))
        assertFalse(HTMLParser.isVerifiedAnimeDetail(cfCheck, "https://animevietsub.best/phim/bleach-a1/", "https://animevietsub.best"))

        val html = """
            <html>
            <head><title>Solo Leveling - AnimeVietsub</title></head>
            <body>
              <div class="sidebar">
                <a class="watch_button" href="/phim/other-show-a999/xem-phim.html">Xem phim</a>
              </div>
              <a class="watch_button" href="/phim/solo-leveling-a101/xem-phim.html">Xem phim</a>
              <ul class="list-episode">
                <li><a class="btn-episode" href="/phim/solo-leveling-a101/tap-1.html" data-hash="H1">4K - Tập 1</a></li>
                <li><a class="btn-episode" href="/phim/solo-leveling-a101/tap-5.html" data-hash="H5">1080p Tập 5</a></li>
                <li><a class="btn-episode" href="/phim/solo-leveling-a101/tap-3.html" data-hash="H3">Bản 2026 Tập 3</a></li>
              </ul>
              <div id="comments">
                <blockquote>
                  <a class="btn-episode" href="/phim/other-show-a999/tap-99.html" data-id="comment99">18Cuong</a>
                  <a href="/phim/solo-leveling-a101/tap-99.html">18Cuong</a>
                </blockquote>
              </div>
              <script>{"name":"2026","label":"YearOnly"}</script>
            </body>
            </html>
        """.trimIndent()

        val canonical = "https://animevietsub.best/phim/solo-leveling-a101/"
        assertEquals(
            "https://animevietsub.best/phim/solo-leveling-a101/xem-phim.html",
            HTMLParser.watchURL(html, canonical, "https://animevietsub.best")
        )
        val eps = HTMLParser.episodes(html, canonical, "https://animevietsub.best")
        assertEquals(listOf(1, 3, 5), eps.map { it.number }.sorted())
        assertFalse(eps.any { it.number == 4 || it.number == 1080 || it.number == 2026 || it.number == 99 || it.number == 18 })
        assertFalse(eps.any { it.url.contains("other-show-a999") })
    }

    @Test
    fun handlesLargeNextPushPayloadsWithoutStackOverflow() {
        val padding = "x".repeat(130_000)
        val escapedEpJson = """{\"episodes\":[{\"hash\":\"H_LARGE\",\"name\":\"Tập 12\",\"url\":\"/phim/frieren-a77/tap-12.html\"}]}"""
        val bigPayload = padding + escapedEpJson
        val html = """
            <html><head><title>Frieren - AnimeVietsub</title></head><body>
            <script>self.__next_f.push([1,"$bigPayload"])</script>
            </body></html>
        """.trimIndent()

        val canonical = "https://animevietsub.xyz/phim/frieren-a77/"
        val eps = HTMLParser.episodes(html, canonical, "https://animevietsub.xyz")
        assertTrue("Expected extracted episode from 130KB+ __next_f payload", eps.any { it.number == 12 && it.animeHash == "H_LARGE" })
    }

    @Test
    fun animeSourceMigratesCleanlyToMiruroAndVerificationGuardWorks() {
        assertEquals("Anime Miruro", ContentSource.MIRURO.title)
        assertFalse(ContentSource.MIRURO.isMovie)
        assertEquals(ContentSource.MIRURO, ContentSource.fromId("anime"))

        val detailHtml = """
            <html><head><title>Bleach</title></head><body>
              <ul class="list-episode">
                <li><a class="btn-episode" href="/phim/bleach-a5985/tap-1.html" data-hash="BLEACH_H1">Tập 1</a></li>
              </ul>
            </body></html>
        """.trimIndent()
        val eps = HTMLParser.episodes(detailHtml, "https://animevietsub.xyz/phim/bleach-a5985/", "https://animevietsub.xyz")
        assertEquals(1, eps.size)
        assertEquals(ContentSource.MIRURO, eps[0].source)
        assertEquals("anime", eps[0].playbackKind)

        // Ensure comment-box captcha placeholders do not trigger false verification blocks
        val realPageWithCommentCaptcha = """
            <html><head><title>Xem phim Bleach Tập 1</title></head><body>
              <script>var filmInfo = { filmID: parseInt('5985') };</script>
              <ul class="list-episode"><li><a class="btn-episode" href="/phim/bleach-a5985/tap-1.html" data-hash="H1">Tập 1</a></li></ul>
              <div id="comments"><div class="captcha-placeholder"></div><div class="cf-turnstile"></div></div>
            </body></html>
        """.trimIndent()
        assertFalse(WebViewHelper.containsVerification(realPageWithCommentCaptcha))

        val genuineCloudflareChallenge = """
            <html><head><title>Just a moment...</title></head><body>
              <form id="challenge-form" action="/cdn-cgi/challenge-platform/h/b/orchestrate/chl_page/v1"></form>
            </body></html>
        """.trimIndent()
        assertTrue(WebViewHelper.containsVerification(genuineCloudflareChallenge))
    }
}
