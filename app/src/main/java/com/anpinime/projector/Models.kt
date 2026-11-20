package com.anpinime.projector

import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

enum class ContentSource(val id: String, val title: String, val subtitle: String, val isMovie: Boolean) {
    FILM4K("film4k", "Tú Nam", "Nguồn 1 · Tú Nam", true),
    AETHER("aether", "Thúy An", "Nguồn 2 · Thúy An", true),
    KKPHIM("kkphim", "Cốm", "Nguồn 3 · Cốm", true),
    MIRURO("miruro", "Anime Miruro", "Nguồn 4 · Anime Miruro", false);

    companion object {
        fun fromId(id: String?): ContentSource {
            if (id.isNullOrBlank()) return FILM4K
            val normalized = id.trim().lowercase()
            if (normalized == "phim4k" || normalized == "aether") return AETHER
            if (normalized == "anime" || normalized == "animevietsub") return MIRURO
            return entries.firstOrNull { it.id.equals(normalized, ignoreCase = true) } ?: FILM4K
        }
    }
}

enum class DayOfWeek(val rawValue: String, val displayName: String) {
    MONDAY("Thứ 2", "Thứ Hai"),
    TUESDAY("Thứ 3", "Thứ Ba"),
    WEDNESDAY("Thứ 4", "Thứ Tư"),
    THURSDAY("Thứ 5", "Thứ Năm"),
    FRIDAY("Thứ 6", "Thứ Sáu"),
    SATURDAY("Thứ 7", "Thứ Bảy"),
    SUNDAY("Chủ Nhật", "Chủ Nhật");

    companion object {
        fun today(): DayOfWeek {
            return when (Calendar.getInstance().get(Calendar.DAY_OF_WEEK)) {
                Calendar.MONDAY -> MONDAY
                Calendar.TUESDAY -> TUESDAY
                Calendar.WEDNESDAY -> WEDNESDAY
                Calendar.THURSDAY -> THURSDAY
                Calendar.FRIDAY -> FRIDAY
                Calendar.SATURDAY -> SATURDAY
                else -> SUNDAY
            }
        }
    }
}

data class AnimeScheduleItem(
    val id: String,
    val title: String,
    val url: String,
    val episodeText: String,
    val timeText: String,
    val dayOfWeek: DayOfWeek
)

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
    val source: ContentSource = ContentSource.FILM4K,
    val catalogLabel: String? = null
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
        put("catalogLabel", catalogLabel ?: "")
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
                source = ContentSource.fromId(o.optString("source")),
                catalogLabel = o.optString("catalogLabel").takeIf { it.isNotEmpty() }
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
    val path: String,
    val group: String = "Danh mục"
)

data class Episode(
    val title: String,
    val url: String,
    val number: Int,
    val season: String,
    val qualityBadge: String = "",
    val source: ContentSource = ContentSource.FILM4K,
    val playbackKind: String = "aether_web", // "film4k", "aether_web", "catalog_episode", "direct", "anime"
    val slug: String = "",
    val seasonNum: Int = -1,
    val episodeNum: Int = -1,
    val serverIndex: Int = 0,
    val episodeIndex: Int = 0,
    val directUrl: String = "",
    val refererUrl: String = "",
    val animeHash: String = "",
    val animeFilmId: String = "",
    val detailUrl: String = "",
    val serverName: String = "",
    val episodeKey: String = "",
    val videoFileId: String = "",
    val seasonId: String = "",
    val episodeId: String = "",
    val audioLabel: String = "",
    val subtitleLabel: String = ""
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
        put("detailUrl", detailUrl)
        put("serverName", serverName)
        put("episodeKey", episodeKey)
        put("videoFileId", videoFileId)
        put("seasonId", seasonId)
        put("episodeId", episodeId)
        put("audioLabel", audioLabel)
        put("subtitleLabel", subtitleLabel)
    }

    companion object {
        fun fromJsonObject(o: JSONObject): Episode = Episode(
            title = o.optString("title"),
            url = o.optString("url"),
            number = o.optInt("number", 1),
            season = o.optString("season"),
            qualityBadge = o.optString("qualityBadge"),
            source = ContentSource.fromId(o.optString("source")),
            playbackKind = o.optString("playbackKind", "aether_web").let {
                if (it == "phim4k" || it == "phim4k_core") "aether_web" else it
            },
            slug = o.optString("slug"),
            seasonNum = o.optInt("seasonNum", -1),
            episodeNum = o.optInt("episodeNum", -1),
            serverIndex = o.optInt("serverIndex", 0),
            episodeIndex = o.optInt("episodeIndex", 0),
            directUrl = o.optString("directUrl"),
            refererUrl = o.optString("refererUrl"),
            animeHash = o.optString("animeHash"),
            animeFilmId = o.optString("animeFilmId"),
            detailUrl = o.optString("detailUrl"),
            serverName = o.optString("serverName"),
            episodeKey = o.optString("episodeKey"),
            videoFileId = o.optString("videoFileId"),
            seasonId = o.optString("seasonId"),
            episodeId = o.optString("episodeId"),
            audioLabel = o.optString("audioLabel"),
            subtitleLabel = o.optString("subtitleLabel")
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
    val episodes: List<Episode>,
    val watchUrl: String? = null
)

enum class StreamMediaKind {
    HLS,
    DASH,
    MP4,
    MKV,
    UNKNOWN
}

data class ExternalSubtitleTrack(
    val url: String,
    val language: String = "vi",
    val label: String = "Vietsub",
    val mimeType: String? = null,
    val headers: Map<String, String> = emptyMap()
) {
    fun toJsonObject(): JSONObject = JSONObject().apply {
        put("url", url)
        put("language", language)
        put("label", label)
        put("mimeType", mimeType ?: "")
        if (headers.isNotEmpty()) {
            val hObj = JSONObject()
            for ((k, v) in headers) hObj.put(k, v)
            put("headers", hObj)
        }
    }

    companion object {
        fun fromJsonObject(o: JSONObject): ExternalSubtitleTrack {
            val hdrs = LinkedHashMap<String, String>()
            val hObj = o.optJSONObject("headers")
            if (hObj != null) {
                val keys = hObj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val v = hObj.optString(k)
                    if (k.isNotBlank() && v.isNotBlank()) hdrs[k] = v
                }
            }
            return ExternalSubtitleTrack(
                url = o.optString("url"),
                language = o.optString("language", "vi"),
                label = o.optString("label", "Vietsub"),
                mimeType = o.optString("mimeType").takeIf { it.isNotEmpty() },
                headers = hdrs
            )
        }
    }
}

data class ResolvedPlayback(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val isF4kWrapped: Boolean = false,
    val isWeb: Boolean = false,
    val webUrl: String = "",
    val mediaKind: StreamMediaKind = StreamMediaKind.UNKNOWN,
    val subtitles: List<ExternalSubtitleTrack> = emptyList(),
    val providerId: String = ""
)
