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
}
