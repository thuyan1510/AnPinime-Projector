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
}
