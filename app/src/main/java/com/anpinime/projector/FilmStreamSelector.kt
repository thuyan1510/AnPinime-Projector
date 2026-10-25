package com.anpinime.projector

import org.json.JSONArray
import java.net.URI

internal data class FilmStream(val url: String, val isWeb: Boolean)

internal object FilmStreamSelector {
    fun select(sources: JSONArray, index: Int, baseUrl: String): FilmStream {
        require(sources.length() > 0) { "Không có server cho tập này." }
        // Preserve API server indices; filtering native streams changes the selected server.
        val source = sources.getJSONObject(index.coerceIn(0, sources.length() - 1))
        val raw = source.optString("url").trim()
        require(raw.isNotEmpty() && raw != "null") { "Server không có địa chỉ phát." }
        val url = URI(baseUrl).resolve(raw)
        require(url.scheme == "https" || url.scheme == "http") { "Địa chỉ phát không hợp lệ." }
        val path = url.path.orEmpty().lowercase()
        val kind = source.optString("kind").lowercase()
        val drm = source.opt("drm")
        val protected = drm != null && drm != org.json.JSONObject.NULL && drm != false && drm != ""
        val native = !protected && (
            listOf(".m3u8", ".mp4", ".mov", ".m4v", ".mkv").any { path.endsWith(it) } ||
                path.contains("/stream/hls") || kind in setOf("hls", "mp4"))
        return FilmStream(url.toString(), !native)
    }
}
