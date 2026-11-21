package com.anpinime.projector

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class V8Activity : AppCompatActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var ui: V8WebView? = null
    private val prefs by lazy { getSharedPreferences("anpinime_projector", MODE_PRIVATE) }
    private val settings by lazy { getSharedPreferences("anpinime_v8_playback", MODE_PRIVATE) }
    private var detail: Pair<Anime, AnimeDetail>? = null
    private var detailGeneration = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!V8WebView.supported()) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }
        CatalogService.init(this)
        WebViewHelper.registerActivity(this)
        ui = V8WebView(this, receive = ::handleRequest).apply {
            setContentView(this)
            open("catalog", intro = savedInstanceState == null)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !BuildConfig.IS_PHONE_FLAVOR) {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        }
    }

    override fun onResume() {
        super.onResume()
        if (ui != null) WebViewHelper.registerActivity(this)
        ui?.onResume()
    }

    override fun onPause() {
        ui?.onPause()
        super.onPause()
    }

    @Deprecated("Android Back is forwarded to the active catalog screen")
    override fun onBackPressed() {
        ui?.emit("back") ?: super.onBackPressed()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!BuildConfig.IS_PHONE_FLAVOR && event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER &&
            ui?.hasFocus() == true
        ) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) ui?.emit("activate")
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onDestroy() {
        scope.cancel()
        ui?.destroy()
        ui = null
        super.onDestroy()
    }

    private fun handleRequest(request: JSONObject, reply: (JSONObject) -> Unit) {
        val source = ContentSource.fromId(request.optString("source"))
        val page = request.optInt("page", 1).coerceAtLeast(1)
        val action = request.optString("action")
        val generation = if (action == "detail") ++detailGeneration else detailGeneration
        scope.launch {
            try {
                val data: Any? = when (action) {
                    "ready" -> JSONObject()
                        .put("source", ContentSource.fromId(prefs.getString("source", "aether")).id)
                        .put("version", BuildConfig.VERSION_NAME)
                        .put("preferences", JSONObject(settings.all))
                    "source" -> {
                        prefs.edit().putString("source", source.id).apply()
                        null
                    }
                    "home" -> withContext(Dispatchers.IO) {
                        if (request.optBoolean("refresh") && source == ContentSource.MIRURO) {
                            CatalogService.clearMiruroHomeCache()
                        }
                        JSONArray(CatalogService.loadHome(source).map { section ->
                            JSONObject().put("title", section.title).put("path", section.categoryPath.orEmpty())
                                .put("items", movieArray(section.items))
                        })
                    }
                    "categories" -> withContext(Dispatchers.IO) {
                        JSONArray(CatalogService.getCategories(source).map {
                            JSONObject().put("title", it.title).put("path", it.path)
                        })
                    }
                    "browse" -> withContext(Dispatchers.IO) {
                        movieArray(CatalogService.loadList(source, request.getString("path"), page))
                    }
                    "search" -> withContext(Dispatchers.IO) {
                        movieArray(CatalogService.search(source, request.getString("query").take(200), page))
                    }
                    "detail" -> {
                        val anime = Anime.fromJson(request.getJSONObject("item").toString())
                        val loaded = withContext(Dispatchers.IO) { CatalogService.loadDetail(anime) }
                        if (generation == detailGeneration) detail = anime to loaded
                        JSONObject().put("title", loaded.title).put("poster", loaded.posterUrl)
                            .put("backdrop", loaded.bannerUrl).put("desc", loaded.summary).put("info", loaded.info)
                            .put("episodes", JSONArray(loaded.episodes.mapIndexed { index, episode ->
                                JSONObject().put("index", index).put("title", episode.title)
                                    .put("group", episode.season).put("number", episode.number)
                            }))
                            .put("servers", JSONArray(
                                if (anime.source == ContentSource.AETHER) AetherSourceResolver.SERVERS.map {
                                    JSONObject().put("id", it.id).put("label", it.name)
                                } else emptyList()
                            ))
                    }
                    "play" -> {
                        val (anime, loaded) = requireNotNull(detail)
                        require(anime.url == request.optString("url") && anime.source == source)
                        val selected = loaded.episodes[request.getInt("index")]
                        val provider = request.optString("server", "auto")
                        val chosen = if (anime.source == ContentSource.AETHER) {
                            AetherSourceResolver.SERVERS.firstOrNull { it.id == provider }
                                ?.let { selected.copy(episodeKey = it.id, serverName = it.name) } ?: selected
                        } else selected
                        ImageLoader.trimMemory()
                        startActivity(Intent(this@V8Activity, PlayerActivity::class.java).apply {
                            putExtra("movie_title", loaded.title.ifBlank { anime.title })
                            putExtra("episode_json", chosen.toJsonObject().toString())
                            putExtra("all_episodes_json", Episode.toJsonArray(loaded.episodes))
                            putExtra("v8", true)
                        })
                        null
                    }
                    "openMiruro" -> {
                        require(source == ContentSource.MIRURO)
                        val url = MiruroCatalog.watchPageUrl(request.getString("url"))
                            ?: error("Không tìm thấy trang xem Miruro.")
                        try {
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        } catch (_: ActivityNotFoundException) {
                            error("Thiết bị chưa có trình duyệt để mở trang xem.")
                        }
                        null
                    }
                    "preference" -> {
                        val key = request.getString("key")
                        val value = request.getInt("value")
                        val ranges = mapOf("quality" to 0..2, "subtitleSize" to 0..3, "aspect" to 0..4, "speed" to 0..4)
                        require(value in requireNotNull(ranges[key]))
                        settings.edit().putInt(key, value).apply()
                        null
                    }
                    "update" -> {
                        AppUpdateManager.checkUpdateManual(this@V8Activity)
                        null
                    }
                    "exit" -> {
                        finish()
                        null
                    }
                    else -> throw IllegalArgumentException("Thao tác không hỗ trợ.")
                }
                reply(JSONObject().put("data", data ?: JSONObject.NULL))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                reply(JSONObject().put("error", error.localizedMessage ?: "Không thể tải dữ liệu."))
            }
        }
    }

    private fun movieArray(items: List<Anime>): JSONArray =
        JSONArray(items.map { JSONObject(it.toJson()) })
}
