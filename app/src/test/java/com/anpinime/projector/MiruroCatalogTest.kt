package com.anpinime.projector

import androidx.media3.common.MimeTypes
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MiruroCatalogTest {

    @Test
    fun parseBrowseItemsMapsMiruroSnakeCaseSchema() {
        val rawJson = """
            {
              "data": [
                {
                  "id": "o8FU9Se7l1zWPEIo28kel7S1tthb6ldF",
                  "title": { "romaji": "ONE PIECE", "english": "ONE PIECE", "native": "ワンピース" },
                  "cover_url": "https://s4.anilist.co/file/anilistcdn/media/anime/cover/large/bx21.jpg",
                  "banner_url": "https://s4.anilist.co/file/anilistcdn/media/anime/banner/21.jpg",
                  "format": "TV",
                  "status": "RELEASING",
                  "season_year": 1999,
                  "episode_count": null,
                  "episode_counts": { "raw": 1180, "sub": 1180, "dub": 1160 },
                  "average_score": 87,
                  "genres": ["Action", "Adventure", "Fantasy"],
                  "description": "<b>Gold Roger</b> was known as the Pirate King."
                }
              ]
            }
        """.trimIndent()

        val items = MiruroCatalog.parseBrowseItems(rawJson, "https://miruro.cx")
        assertEquals(1, items.size)
        val op = items.first()
        assertEquals(ContentSource.MIRURO, op.source)
        assertEquals("ONE PIECE", op.title)
        assertEquals("https://miruro.cx/watch/o8FU9Se7l1zWPEIo28kel7S1tthb6ldF/one-piece", op.url)
        assertEquals("https://s4.anilist.co/file/anilistcdn/media/anime/cover/large/bx21.jpg", op.imageUrl)
        assertEquals("Tập 1180", op.episodeText)
        assertEquals("8.7", op.score)
        assertEquals("1080p", op.quality)
        assertEquals("Gold Roger was known as the Pirate King.", op.summary)
    }

    @Test
    fun parseHomeDataJsonDecodesDevaluePromisesAndChunks() {
        val ndjson = listOf(
            """{"type":"data","nodes":[{"type":"skip"},{"type":"data","data":[{"spotlight":1,"rails":9,"sideLists":10},[2],{"id":3,"title":4,"coverUrl":6,"format":7,"averageScore":8},"abc123",{"english":5},"Black Clover Season 2","https://cdn.example/bc.jpg","TV",82,["Promise",1],["Promise",2]]}]}""",
            """{"type":"chunk","id":1,"data":[{"raw":1},{"latest":2},[3],{"animeId":4,"title":5,"coverUrl":7,"episode":8,"format":9},"ep999",{"romaji":6},"Dandadan","https://cdn.example/dd.jpg",7,"TV"]}""",
            """{"type":"chunk","id":2,"data":[{"airing":1},[2],{"id":3,"title":4,"cover_url":6,"episode_counts":7},"air1",{"english":5},"Frieren","https://cdn.example/fr.jpg",{"sub":8},28]}"""
        ).joinToString("\n")

        val sections = MiruroCatalog.parseHomeDataJson(ndjson, "https://miruro.cx")
        assertEquals(3, sections.size)
        assertEquals("Black Clover Season 2", sections[0].items.first().title)
        assertEquals("Dandadan", sections[1].items.first().title)
        assertEquals("Tập 7", sections[1].items.first().episodeText)
        assertEquals("Frieren", sections[2].items.first().title)
        assertEquals("Tập 28", sections[2].items.first().episodeText)
        assertTrue(sections.all { sec -> sec.items.all { it.source == ContentSource.MIRURO } })
    }

    @Test
    fun parseWatchDetailDecodesMediaEpisodesAndSourcesChunk() {
        val ndjson = listOf(
            """{"type":"data","nodes":[{"type":"skip"},{"type":"data","data":[{"media":1,"episodesData":10,"sourcesData":11,"episodeNumber":12},{"id":2,"title":3,"format":5,"status":6,"coverImage":7,"averageScore":9},"vid_bc2",{"english":4},"Black Clover Season 2","TV","RELEASING",{"extraLarge":8},"https://cdn.example/cover.jpg",85,["Promise",4],["Promise",5],1]}""",
            """{"type":"chunk","id":4,"data":[{"episodes":1},[2,7],{"id":3,"number":4,"title":5,"filler":6},"1",1,"The Battle Begins",false,{"id":8,"number":9,"title":10,"filler":6},"2",2,"Spade Raid"]}""",
            """{"type":"chunk","id":5,"data":[{"episodeNumber":1,"providerOrder":2,"tracks":4},1,[3],"sun",[5],{"track":6,"providers":7},"ssub",[8],{"provider":3,"servers":9,"subtitles":15},[10],{"server":11,"headers":12,"streams":14},"icarus-1",{"Origin":13},"https://ultracloud.cc",[17],[16],{"language":18,"label":19,"file":20},{"url":21,"format":22},"vi","Vietnamese","https://emberhollowtrail89.pearlwinter.top/a/sub.jpeg","https://emberhollowtrail89.pearlwinter.top/m/master.m3u8","hls"]}"""
        ).joinToString("\n")

        val fallback = Anime(
            title = "Black Clover Season 2",
            url = "https://miruro.cx/watch/vid_bc2/black-clover-season-2",
            source = ContentSource.MIRURO
        )
        val result = MiruroCatalog.parseWatchDetail(ndjson, fallback, "https://miruro.cx")
        assertEquals("vid_bc2", result.mediaId)
        assertEquals("Black Clover Season 2", result.detail.title)
        assertEquals(2, result.detail.episodes.size)
        assertEquals("Tập 1 · The Battle Begins", result.detail.episodes[0].title)
        assertEquals("miruro", result.detail.episodes[0].playbackKind)
        assertEquals(ContentSource.MIRURO, result.detail.episodes[0].source)
        assertNotNull(result.sourcesData)

        val candidates = MiruroSourceResolver.parseSourcesData(result.sourcesData!!)
        assertEquals(1, candidates.size)
        assertEquals("sun:ssub:icarus-1", candidates[0].key)
        assertEquals("https://emberhollowtrail89.pearlwinter.top/m/master.m3u8", candidates[0].streamUrl)
        assertEquals("https://ultracloud.cc", candidates[0].headers["Origin"])
        assertTrue(MiruroSourceResolver.hasVietnameseSubtitle(candidates[0].subtitles))
    }

    @Test
    fun parseSourcesDataOrdersProvidersAndMergesVietnameseSubtitlesAcrossServers() {
        val sourcesJson = JSONObject(
            """
            {
              "episodeNumber": 1,
              "providerOrder": ["sun", "bee", "hop"],
              "config": {
                "sun": { "visible": true },
                "bee": { "visible": true },
                "hop": { "visible": true },
                "hidden_prov": { "visible": false }
              },
              "tracks": [
                {
                  "track": "dub",
                  "providers": [
                    {
                      "provider": "sun",
                      "servers": [{ "server": "dub-1", "streams": [{ "url": "https://cdn.example/dub.m3u8", "format": "hls" }] }],
                      "subtitles": []
                    }
                  ]
                },
                {
                  "track": "ssub",
                  "providers": [
                    {
                      "provider": "hidden_prov",
                      "servers": [{ "server": "h1", "streams": [{ "url": "https://cdn.example/hidden.m3u8", "format": "hls" }] }],
                      "subtitles": []
                    },
                    {
                      "provider": "hop",
                      "servers": [
                        {
                          "server": "Vid",
                          "headers": { "Origin": "https://krussdomi.com", "Referer": "https://krussdomi.com/" },
                          "streams": [{ "url": "https://hls.krussdomi.com/hop.m3u8", "format": "hls" }]
                        }
                      ],
                      "subtitles": [
                        { "language": "eng", "label": "English", "file": "https://subst.krussdomi.com/en.vtt" },
                        { "language": "vie", "label": "Vietnamese", "file": "https://subst.krussdomi.com/vi.vtt" }
                      ]
                    },
                    {
                      "provider": "bee",
                      "servers": [
                        {
                          "server": "Vidstream-1",
                          "headers": { "Referer": "https://megaplay.buzz/" },
                          "streams": [{ "url": "https://fetch.nexabloom.top/bee.m3u8", "format": "hls" }]
                        }
                      ],
                      "subtitles": []
                    }
                  ]
                }
              ]
            }
            """.trimIndent()
        )

        val candidates = MiruroSourceResolver.parseSourcesData(sourcesJson)
        // hidden_prov excluded; ssub (bee before hop per providerOrder) comes before dub (sun)
        assertEquals(3, candidates.size)
        assertEquals("bee:ssub:Vidstream-1", candidates[0].key)
        assertEquals("hop:ssub:Vid", candidates[1].key)
        assertEquals("sun:dub:dub-1", candidates[2].key)

        // bee had no subtitles of its own, so it inherits hop's subtitles with Vietnamese sorted first!
        val beeSubs = candidates[0].subtitles
        assertEquals(2, beeSubs.size)
        assertEquals("vi", beeSubs[0].language)
        assertEquals("en", beeSubs[1].language)
        assertEquals("https://krussdomi.com", beeSubs[0].headers["Origin"])
        assertEquals(MimeTypes.TEXT_VTT, beeSubs[0].mimeType)
    }

    @Test
    fun hasVietnameseSubtitleDetectsAbsenceForAutoAiTranslation() {
        val engOnly = listOf(
            ExternalSubtitleTrack(
                url = "https://subst.krussdomi.com/en.vtt",
                language = "en",
                label = "English",
                mimeType = MimeTypes.TEXT_VTT
            )
        )
        assertFalse(MiruroSourceResolver.hasVietnameseSubtitle(engOnly))

        val withVi = engOnly + ExternalSubtitleTrack(
            url = "https://subst.krussdomi.com/vi.vtt",
            language = "vi",
            label = "🇻🇳 Vietnamese",
            mimeType = MimeTypes.TEXT_VTT
        )
        assertTrue(MiruroSourceResolver.hasVietnameseSubtitle(withVi))
    }

    @Test
    fun resolveWithAutoFailoverSwitchesToHealthyServerWhenPrimaryFails() {
        val mockClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request()
                val urlStr = req.url.toString()
                val (code, body) = when {
                    urlStr.endsWith("/broken.m3u8") -> 502 to "<html>502 Bad Gateway</html>"
                    urlStr.endsWith("/healthy.m3u8") -> 200 to "#EXTM3U\n#EXT-X-VERSION:3\n#EXTINF:4.0,\nseg0.jpg\n#EXT-X-ENDLIST\n"
                    urlStr.endsWith("/backup.m3u8") -> 200 to "#EXTM3U\n#EXT-X-VERSION:3\n#EXTINF:4.0,\nseg0.jpg\n#EXT-X-ENDLIST\n"
                    else -> 404 to "not found"
                }
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(code)
                    .message(if (code == 200) "OK" else "ERR")
                    .body(body.toResponseBody("application/vnd.apple.mpegurl".toMediaType()))
                    .build()
            }
            .build()

        val candidates = listOf(
            MiruroStreamCandidate(
                key = "sun:ssub:broken",
                providerId = "sun",
                serverName = "broken",
                trackType = "ssub",
                streamUrl = "https://mock.miruro.local/broken.m3u8",
                format = "hls",
                headers = emptyMap(),
                subtitles = emptyList(),
                displayLabel = "SUN · broken (SoftSub)"
            ),
            MiruroStreamCandidate(
                key = "bee:ssub:healthy",
                providerId = "bee",
                serverName = "healthy",
                trackType = "ssub",
                streamUrl = "https://mock.miruro.local/healthy.m3u8",
                format = "hls",
                headers = mapOf("Referer" to "https://megaplay.buzz/"),
                subtitles = emptyList(),
                displayLabel = "BEE · healthy (SoftSub)"
            ),
            MiruroStreamCandidate(
                key = "hop:ssub:backup",
                providerId = "hop",
                serverName = "backup",
                trackType = "ssub",
                streamUrl = "https://mock.miruro.local/backup.m3u8",
                format = "hls",
                headers = emptyMap(),
                subtitles = emptyList(),
                displayLabel = "HOP · backup (SoftSub)"
            )
        )

        val resolved = MiruroSourceResolver.resolveWithAutoFailover(candidates, mockClient)
        assertNotNull(resolved)
        assertEquals("bee:ssub:healthy", resolved!!.providerId)
        assertEquals("https://mock.miruro.local/healthy.m3u8", resolved.url)
        assertTrue(resolved.isF4kWrapped)
        assertEquals(StreamMediaKind.HLS, resolved.mediaKind)

        // Simulate mid-playback error on bee -> exclude broken + bee, should fail over to hop!
        val backupResolved = MiruroSourceResolver.resolveWithAutoFailover(
            candidates = candidates,
            client = mockClient,
            excludeKeys = setOf("sun:ssub:broken", "bee:ssub:healthy")
        )
        assertNotNull(backupResolved)
        assertEquals("hop:ssub:backup", backupResolved!!.providerId)
    }

    @Test
    fun assSubtitleDisguisedAsJpegNormalizesToCleanWebVttAndRoundTripsHeaders() {
        val assPayload = """
            [Script Info]
            ScriptType: v4.00+
            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            Dialogue: 0,0:00:01.20,0:00:04.50,Default,,0,0,0,,{\i1}Xin chào{\i0}\NThế giới!
        """.trimIndent().toByteArray(Charsets.UTF_8)

        val normalizedVtt = String(
            SubtitleSupport.normalizeSubtitleForMime(
                assPayload,
                MimeTypes.TEXT_VTT,
                "https://emberhollowtrail89.pearlwinter.top/a/sub.jpeg"
            ),
            Charsets.UTF_8
        )
        assertTrue(normalizedVtt.startsWith("WEBVTT"))
        assertTrue(normalizedVtt.contains("00:00:01.200 --> 00:00:04.500"))
        assertTrue(normalizedVtt.contains("Xin chào"))
        assertTrue(normalizedVtt.contains("Thế giới!"))

        val track = ExternalSubtitleTrack(
            url = "https://emberhollowtrail89.pearlwinter.top/a/sub.jpeg",
            language = "vi",
            label = "🇻🇳 Vietnamese",
            mimeType = MimeTypes.TEXT_VTT,
            headers = mapOf("Origin" to "https://strm.cx", "Referer" to "https://strm.cx/")
        )
        val roundTripped = ExternalSubtitleTrack.fromJsonObject(track.toJsonObject())
        assertEquals(track, roundTripped)
    }

    @Test
    fun sanitizeSourceBrandingReplacesLegacyNamesExactly() {
        assertEquals("Server Tú Nam #1", MovieCatalog.sanitizeSourceBranding("Server NguonC #1"))
        assertEquals("Server Cốm VIP", MovieCatalog.sanitizeSourceBranding("Server KKPhim VIP"))
        assertEquals("Nguồn Thúy An FHD", MovieCatalog.sanitizeSourceBranding("Nguồn Aether FHD"))
        assertEquals("Tú Nam / Thúy An / Cốm", MovieCatalog.sanitizeSourceBranding("nguonC / aether / kkphim"))
    }
}
