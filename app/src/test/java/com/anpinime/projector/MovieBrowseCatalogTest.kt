package com.anpinime.projector

import org.junit.Assert.*
import org.junit.Test

class MovieBrowseCatalogTest {

    @Test
    fun nguoncDefaultCategoriesExplicitlyOmitsPhimAi() {
        val categories = MovieBrowseCatalog.nguoncDefaultCategories()
        assertTrue(categories.isNotEmpty())
        for (cat in categories) {
            assertFalse(cat.path.contains("phim-ai", ignoreCase = true))
            assertFalse(cat.title.contains("AI", ignoreCase = true))
        }
        assertTrue(categories.any { it.path.contains("phim-le") })
        assertTrue(categories.any { it.path.contains("phim-bo") })
    }

    @Test
    fun nguoncHtmlCategoryParsingFiltersPhimAi() {
        val html = """
            <html>
                <body>
                    <div class="menu">
                        <a href="https://phim.nguonc.com/danh-sach/phim-le">Phim Lẻ</a>
                        <a href="https://phim.nguonc.com/danh-sach/phim-ai">Phim AI Tự Động</a>
                        <a href="https://phim.nguonc.com/danh-sach/phim-bo">Phim Bộ</a>
                        <a href="https://phim.nguonc.com/the-loai/hoat-hinh">Hoạt Hình</a>
                    </div>
                </body>
            </html>
        """.trimIndent()
        val parsed = MovieBrowseCatalog.nguoncCategoriesFromHtml(html)
        assertTrue(parsed.isNotEmpty())
        assertTrue(parsed.none { it.path.contains("phim-ai") })
        assertTrue(parsed.none { it.title.contains("AI") })
        assertTrue(parsed.any { it.path.contains("phim-le") })
        assertTrue(parsed.any { it.path.contains("phim-bo") })
        assertTrue(parsed.any { it.path.contains("hoat-hinh") })
    }

    @Test
    fun kkDefaultCategoriesAndSiteItems() {
        val categories = MovieBrowseCatalog.kkDefaultCategories()
        assertTrue(categories.any { it.path.contains("phim-le") })
        assertTrue(categories.any { it.path.contains("phim-bo") })
        assertTrue(categories.any { it.path.contains("hoat-hinh") })

        val seriesHtml = """
            <table>
                <tr>
                    <td>
                        <a href="/phim/dau-pha-thuong-khung">
                            <img src="https://img.com/poster.jpg" alt="Đấu Phá Thương Khung"/>
                            <h3>Đấu Phá Thương Khung</h3>
                        </a>
                    </td>
                    <td>Tập 100 Vietsub</td>
                    <td>2024</td>
                    <td>Trung Quốc</td>
                </tr>
            </table>
        """.trimIndent()
        val animes = MovieBrowseCatalog.kkWebsiteItems(seriesHtml, siteBaseUrl = "https://kkphim.com")
        assertEquals(1, animes.size)
        assertEquals("https://phimapi.com/phim/dau-pha-thuong-khung", animes[0].url)
        assertEquals("Đấu Phá Thương Khung", animes[0].title)
        assertEquals(ContentSource.KKPHIM, animes[0].source)
    }
}
