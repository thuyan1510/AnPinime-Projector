package com.anpinime.projector

import org.json.JSONArray
import org.json.JSONObject

enum class ContentSource(val id: String, val title: String, val subtitle: String, val isMovie: Boolean) {
    FILM4K("film4k", "Nguồn phim 1", "Nguồn phim 1 (Film4K)", true),
    PHIM4K("phim4k", "Nguồn phim 2", "Nguồn phim 2 (Phim4K)", true),
    ANIME("anime", "AnimeVietsub", "AnimeVietsub", false);

    companion object {
        fun fromId(id: String?): ContentSource = entries.firstOrNull { it.id == id } ?: FILM4K
    }
}

data class Anime(
    val title: String,
    val subtitle: String = "",
    val url: String,
    val imageUrl: String? = null,
    val bannerUrl: String? = null,
    val episodeText: String = "",
    val quality: String = "",
    val score: String = "",
    val summary: String = "",
    val info: String = "",
    val source: ContentSource = ContentSource.FILM4K
) {
    fun toJson(): String = JSONObject().apply {
        put("title", title)
        put("subtitle", subtitle)
        put("url", url)
        put("imageUrl", imageUrl ?: "")
        put("bannerUrl", bannerUrl ?: "")
        put("episodeText", episodeText)
        put("quality", quality)
        put("score", score)
        put("summary", summary)
        put("info", info)
        put("source", source.id)
    }.toString()

    companion object {
        fun fromJson(raw: String): Anime {
            val o = JSONObject(raw)
            return Anime(
                title = o.optString("title"),
                subtitle = o.optString("subtitle"),
                url = o.optString("url"),
                imageUrl = o.optString("imageUrl").takeIf { it.isNotEmpty() },
                bannerUrl = o.optString("bannerUrl").takeIf { it.isNotEmpty() },
                episodeText = o.optString("episodeText"),
                quality = o.optString("quality"),
                score = o.optString("score"),
                summary = o.optString("summary"),
                info = o.optString("info"),
                source = ContentSource.fromId(o.optString("source"))
            )
        }
    }
}

data class AnimeSection(
    val title: String,
    val items: List<Anime>,
    val categoryPath: String? = null
)

data class AnimeCategory(
    val title: String,
    val path: String
)

data class Episode(
    val title: String,
    val url: String,
    val number: Int,
    val season: String,
    val qualityBadge: String = "",
    val source: ContentSource = ContentSource.FILM4K,
    val playbackKind: String = "phim4k", // "film4k", "phim4k", "direct", "anime"
    val slug: String = "",
    val seasonNum: Int = -1,
    val episodeNum: Int = -1,
    val serverIndex: Int = 0,
    val episodeIndex: Int = 0,
    val directUrl: String = "",
    val refererUrl: String = "",
    val animeHash: String = "",
    val animeFilmId: String = ""
) {
    fun toJsonObject(): JSONObject = JSONObject().apply {
        put("title", title)
        put("url", url)
        put("number", number)
        put("season", season)
        put("qualityBadge", qualityBadge)
        put("source", source.id)
        put("playbackKind", playbackKind)
        put("slug", slug)
        put("seasonNum", seasonNum)
        put("episodeNum", episodeNum)
        put("serverIndex", serverIndex)
        put("episodeIndex", episodeIndex)
        put("directUrl", directUrl)
        put("refererUrl", refererUrl)
        put("animeHash", animeHash)
        put("animeFilmId", animeFilmId)
    }

    companion object {
        fun fromJsonObject(o: JSONObject): Episode = Episode(
            title = o.optString("title"),
            url = o.optString("url"),
            number = o.optInt("number", 1),
            season = o.optString("season"),
            qualityBadge = o.optString("qualityBadge"),
            source = ContentSource.fromId(o.optString("source")),
            playbackKind = o.optString("playbackKind", "phim4k"),
            slug = o.optString("slug"),
            seasonNum = o.optInt("seasonNum", -1),
            episodeNum = o.optInt("episodeNum", -1),
            serverIndex = o.optInt("serverIndex", 0),
            episodeIndex = o.optInt("episodeIndex", 0),
            directUrl = o.optString("directUrl"),
            refererUrl = o.optString("refererUrl"),
            animeHash = o.optString("animeHash"),
            animeFilmId = o.optString("animeFilmId")
        )

        fun toJsonArray(list: List<Episode>): String {
            val arr = JSONArray()
            list.forEach { arr.put(it.toJsonObject()) }
            return arr.toString()
        }

        fun fromJsonArray(raw: String): List<Episode> {
            if (raw.isEmpty()) return emptyList()
            val arr = JSONArray(raw)
            val out = ArrayList<Episode>(arr.length())
            for (i in 0 until arr.length()) {
                out.add(fromJsonObject(arr.getJSONObject(i)))
            }
            return out
        }
    }
}

data class AnimeDetail(
    val title: String,
    val posterUrl: String?,
    val bannerUrl: String?,
    val summary: String,
    val info: String,
    val episodes: List<Episode>
)

data class ResolvedPlayback(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val isF4kWrapped: Boolean = false
)
