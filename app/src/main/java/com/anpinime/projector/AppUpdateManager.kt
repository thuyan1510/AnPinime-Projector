package com.anpinime.projector

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.TimeUnit

object AppUpdateManager {
    private const val TAG = "AppUpdateManager"

    // Primary: GitHub Releases REST API (direct JSON response, no redirects, includes latest release tag & assets)
    private const val GITHUB_API_LATEST_RELEASE =
        "https://api.github.com/repos/thuyan1510/anpinime-dl/releases/latest"

    // Fallbacks: Direct RAW JSON URLs and download links
    private val FALLBACK_VERSION_URLS = listOf(
        "https://raw.githubusercontent.com/thuyan1510/anpinime-dl/main/version.json",
        "https://raw.githubusercontent.com/thuyan1510/AnPinime-Projector/feat/aether-source/version.json",
        "https://raw.githubusercontent.com/thuyan1510/AnPinime-Projector/main/version.json",
        "https://github.com/thuyan1510/anpinime-dl/releases/download/latest/version.json"
    )

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    data class UpdateInfo(
        val versionCode: Int,
        val versionName: String,
        val apkUrl: String,
        val changelog: String,
        val fileSizeMb: Double = 0.0
    )

    fun getAppVersionCode(context: Context): Int {
        return try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pInfo.longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                pInfo.versionCode
            }
        } catch (_: Exception) {
            0
        }
    }

    fun getAppVersionName(context: Context): String {
        return try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
        } catch (_: Exception) {
            ""
        }
    }

    fun isNewerVersion(remoteCode: Int, remoteName: String, currentCode: Int, currentName: String): Boolean {
        if (remoteCode > 0 && currentCode > 0) {
            if (remoteCode > currentCode) return true
            if (remoteCode < currentCode) return false
        }
        // Semantic version compare (e.g. "1.7.2" vs "1.6.1")
        val cleanRemote = remoteName.trim().removePrefix("v").removePrefix("V")
        val cleanCurrent = currentName.trim().removePrefix("v").removePrefix("V")
        if (cleanRemote.isBlank() || cleanCurrent.isBlank()) return false
        val remoteParts = cleanRemote.split(".").mapNotNull { it.toIntOrNull() }
        val currentParts = cleanCurrent.split(".").mapNotNull { it.toIntOrNull() }
        for (i in 0 until maxOf(remoteParts.size, currentParts.size)) {
            val r = remoteParts.getOrElse(i) { 0 }
            val c = currentParts.getOrElse(i) { 0 }
            if (r > c) return true
            if (r < c) return false
        }
        return false
    }

    suspend fun checkUpdate(context: Context, onNewVersion: (UpdateInfo) -> Unit) {
        withContext(Dispatchers.IO) {
            val info = fetchUpdateInfo() ?: return@withContext
            val currentCode = getAppVersionCode(context)
            val currentName = getAppVersionName(context)
            Log.d(TAG, "Current: v$currentName ($currentCode), Remote: v${info.versionName} (${info.versionCode})")

            if (isNewerVersion(info.versionCode, info.versionName, currentCode, currentName) && info.apkUrl.isNotBlank()) {
                withContext(Dispatchers.Main) {
                    onNewVersion(info)
                }
            }
        }
    }

    fun checkUpdateManual(activity: Activity) {
        val loadingToast = Toast.makeText(activity, "Đang kiểm tra bản cập nhật mới…", Toast.LENGTH_SHORT)
        loadingToast.show()

        CoroutineScope(Dispatchers.IO).launch {
            val info = fetchUpdateInfo()
            val currentCode = getAppVersionCode(activity)
            val currentName = getAppVersionName(activity)

            withContext(Dispatchers.Main) {
                if (activity.isFinishing || activity.isDestroyed) return@withContext
                if (info != null && isNewerVersion(info.versionCode, info.versionName, currentCode, currentName) && info.apkUrl.isNotBlank()) {
                    showUpdateDialog(activity, info)
                } else if (info != null) {
                    val alert = AlertDialog.Builder(activity)
                        .setTitle("Bạn Đang Dùng Bản Mới Nhất")
                        .setMessage("Phiên bản hiện tại: v$currentName (Mã: $currentCode)\nỨng dụng đã là bản mới nhất và tối ưu cho máy chiếu.")
                        .setPositiveButton("Đã hiểu", null)
                        .create()
                    alert.show()
                } else {
                    val alert = AlertDialog.Builder(activity)
                        .setTitle("Kiểm Tra Cập Nhật")
                        .setMessage("Không thể kết nối máy chủ cập nhật lúc này.\nVui lòng kiểm tra kết nối mạng trên máy chiếu hoặc thử lại sau.")
                        .setPositiveButton("Đóng", null)
                        .create()
                    alert.show()
                }
            }
        }
    }

    private fun fetchUpdateInfo(): UpdateInfo? {
        // Strategy 1: GitHub API latest release
        try {
            val req = Request.Builder()
                .url(GITHUB_API_LATEST_RELEASE)
                .header("User-Agent", "AnPinime-Projector")
                .header("Accept", "application/vnd.github.v3+json")
                .build()
            val resp = httpClient.newCall(req).execute()
            if (resp.isSuccessful) {
                val body = resp.body?.string().orEmpty()
                if (body.startsWith("{")) {
                    val json = JSONObject(body)
                    val tagName = json.optString("tag_name", "").removePrefix("v").removePrefix("V")
                    val changelog = json.optString("body", "").trim()
                    val assets = json.optJSONArray("assets") ?: JSONArray()
                    var universalApkUrl = ""
                    var apkSize = 0L

                    for (i in 0 until assets.length()) {
                        val a = assets.optJSONObject(i) ?: continue
                        val name = a.optString("name", "")
                        if (name.contains("K670Pro", true) || name.contains("universal", true)) {
                            universalApkUrl = a.optString("browser_download_url", "")
                            apkSize = a.optLong("size", 0L)
                            break
                        }
                    }

                    if (universalApkUrl.isEmpty() && assets.length() > 0) {
                        for (i in 0 until assets.length()) {
                            val a = assets.optJSONObject(i) ?: continue
                            val name = a.optString("name", "")
                            if (name.endsWith(".apk", true)) {
                                universalApkUrl = a.optString("browser_download_url", "")
                                apkSize = a.optLong("size", 0L)
                                break
                            }
                        }
                    }

                    if (universalApkUrl.isNotEmpty() && tagName.isNotEmpty()) {
                        return UpdateInfo(
                            versionCode = 0,
                            versionName = tagName,
                            apkUrl = universalApkUrl,
                            changelog = changelog,
                            fileSizeMb = if (apkSize > 0) apkSize / (1024.0 * 1024.0) else 0.0
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "GitHub API update fetch failed: ${e.message}")
        }

        // Strategy 2: Direct raw version.json URLs
        for (url in FALLBACK_VERSION_URLS) {
            try {
                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", "AnPinime-Projector")
                    .build()
                val resp = httpClient.newCall(req).execute()
                if (resp.isSuccessful) {
                    val body = resp.body?.string().orEmpty()
                    if (body.startsWith("{")) {
                        val json = JSONObject(body)
                        val code = json.optInt("versionCode", 0)
                        val name = json.optString("versionName", "").removePrefix("v").removePrefix("V")
                        val apkUrl = json.optString("apkUrl", "")
                        val changelog = json.optString("changelog", "").trim()
                        if (apkUrl.isNotEmpty() && (code > 0 || name.isNotEmpty())) {
                            return UpdateInfo(
                                versionCode = code,
                                versionName = name,
                                apkUrl = apkUrl,
                                changelog = changelog
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Fallback version fetch failed for $url: ${e.message}")
            }
        }

        return null
    }

    fun showUpdateDialog(activity: Activity, info: UpdateInfo) {
        if (activity.isFinishing || activity.isDestroyed) return

        val ctx = activity
        val currentVersion = getAppVersionName(ctx)

        val dialogContainer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(UiHelper.COLOR_SIDEBAR)
            setPadding(
                UiHelper.dp(ctx, 22),
                UiHelper.dp(ctx, 20),
                UiHelper.dp(ctx, 22),
                UiHelper.dp(ctx, 20)
            )
        }

        val titleTv = TextView(ctx).apply {
            text = "🚀  CÓ BẢN CẬP NHẬT MỚI: v${info.versionName}"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17.5f)
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = UiHelper.dp(ctx, 6)
            }
        }
        dialogContainer.addView(titleTv)

        val versionRow = TextView(ctx).apply {
            text = "Phiên bản hiện tại: v$currentVersion  ➔  Bản mới: v${info.versionName}"
            setTextColor(UiHelper.COLOR_ACCENT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = UiHelper.dp(ctx, 12)
            }
        }
        dialogContainer.addView(versionRow)

        val logScroll = ScrollView(ctx).apply {
            background = UiHelper.roundedBg(UiHelper.COLOR_CARD, 8, ctx)
            setPadding(
                UiHelper.dp(ctx, 12),
                UiHelper.dp(ctx, 10),
                UiHelper.dp(ctx, 12),
                UiHelper.dp(ctx, 10)
            )
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                UiHelper.dp(ctx, 130)
            ).apply {
                bottomMargin = UiHelper.dp(ctx, 16)
            }
        }

        val logTv = TextView(ctx).apply {
            val sizeNote = if (info.fileSizeMb > 0) String.format(Locale.US, "\nDung lượng: %.1f MB", info.fileSizeMb) else ""
            val rawLog = info.changelog.ifBlank { "Bản cập nhật chứa các cải tiến hiệu năng và sửa lỗi cho máy chiếu." }
            text = "$rawLog$sizeNote"
            setTextColor(UiHelper.COLOR_TEXT_SECONDARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setLineSpacing(UiHelper.dp(ctx, 3).toFloat(), 1f)
        }
        logScroll.addView(logTv)
        dialogContainer.addView(logScroll)

        val btnRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        var updateDialogRef: AlertDialog? = null

        val laterBtn = TextView(ctx).apply {
            text = "Để Sau"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(UiHelper.dp(ctx, 18), UiHelper.dp(ctx, 10), UiHelper.dp(ctx, 18), UiHelper.dp(ctx, 10))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                rightMargin = UiHelper.dp(ctx, 12)
            }
        }

        val updateBtn = TextView(ctx).apply {
            text = "⚡  Cập Nhật Ngay"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(UiHelper.dp(ctx, 22), UiHelper.dp(ctx, 10), UiHelper.dp(ctx, 22), UiHelper.dp(ctx, 10))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        UiHelper.makeFocusableButton(
            view = laterBtn,
            normalBgColor = UiHelper.COLOR_CARD,
            focusedBgColor = Color.parseColor("#352256"),
            focusedStrokeColor = Color.WHITE,
            radiusDp = 10,
            scaleOnFocus = 1.05f
        ) {
            updateDialogRef?.dismiss()
        }

        UiHelper.makeFocusableButton(
            view = updateBtn,
            normalBgColor = UiHelper.COLOR_ACCENT,
            focusedBgColor = Color.parseColor("#C084FC"),
            focusedStrokeColor = Color.WHITE,
            radiusDp = 10,
            scaleOnFocus = 1.05f
        ) {
            updateDialogRef?.dismiss()
            startDownloadAndInstall(activity, info)
        }

        btnRow.addView(laterBtn)
        btnRow.addView(updateBtn)
        dialogContainer.addView(btnRow)

        updateDialogRef = AlertDialog.Builder(activity)
            .setView(dialogContainer)
            .setCancelable(true)
            .create()

        updateDialogRef.setOnShowListener {
            updateBtn.requestFocus()
        }

        updateDialogRef.show()
    }

    private fun startDownloadAndInstall(activity: Activity, info: UpdateInfo) {
        if (activity.isFinishing || activity.isDestroyed) return
        val ctx = activity

        val progressContainer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(UiHelper.COLOR_SIDEBAR)
            setPadding(
                UiHelper.dp(ctx, 24),
                UiHelper.dp(ctx, 22),
                UiHelper.dp(ctx, 24),
                UiHelper.dp(ctx, 20)
            )
        }

        val titleTv = TextView(ctx).apply {
            text = "Đang Tải Bản Cập Nhật v${info.versionName}…"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16.5f)
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = UiHelper.dp(ctx, 14)
            }
        }
        progressContainer.addView(titleTv)

        val progressBar = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = false
            max = 100
            progress = 0
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                UiHelper.dp(ctx, 16)
            ).apply {
                bottomMargin = UiHelper.dp(ctx, 10)
            }
        }
        progressContainer.addView(progressBar)

        val statusTv = TextView(ctx).apply {
            text = "Đang kết nối tới máy chủ…"
            setTextColor(UiHelper.COLOR_TEXT_SECONDARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        progressContainer.addView(statusTv)

        val progressDialog = AlertDialog.Builder(activity)
            .setView(progressContainer)
            .setCancelable(false)
            .create()

        progressDialog.show()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val cacheDir = ctx.externalCacheDir ?: ctx.cacheDir
                val apkFile = File(cacheDir, "AnPinime-Update.apk")
                if (apkFile.exists()) apkFile.delete()

                val req = Request.Builder()
                    .url(info.apkUrl)
                    .header("User-Agent", "AnPinime-Projector")
                    .build()

                val resp = httpClient.newCall(req).execute()
                if (!resp.isSuccessful) {
                    throw Exception("Máy chủ phản hồi mã lỗi: ${resp.code}")
                }

                val body = resp.body ?: throw Exception("Nội dung tải về rỗng")
                val totalBytes = body.contentLength()

                body.byteStream().use { input ->
                    FileOutputStream(apkFile).use { output ->
                        val buffer = ByteArray(32 * 1024)
                        var bytesRead: Int
                        var downloadedBytes = 0L

                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            downloadedBytes += bytesRead

                            if (totalBytes > 0) {
                                val pct = ((downloadedBytes * 100) / totalBytes).toInt().coerceIn(0, 100)
                                val mbDown = downloadedBytes / (1024.0 * 1024.0)
                                val mbTotal = totalBytes / (1024.0 * 1024.0)
                                withContext(Dispatchers.Main) {
                                    progressBar.progress = pct
                                    statusTv.text = String.format(Locale.US, "Đang tải: %d%% (%.1f MB / %.1f MB)", pct, mbDown, mbTotal)
                                }
                            }
                        }
                    }
                }

                withContext(Dispatchers.Main) {
                    if (!activity.isFinishing && !activity.isDestroyed) {
                        progressDialog.dismiss()
                        installApk(activity, apkFile)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Download failed", e)
                withContext(Dispatchers.Main) {
                    if (!activity.isFinishing && !activity.isDestroyed) {
                        progressDialog.dismiss()
                        AlertDialog.Builder(activity)
                            .setTitle("Tải Bản Cập Nhật Thất Bại")
                            .setMessage("Không thể tải bản cập nhật: ${e.message}\nVui lòng kiểm tra lại kết nối mạng trên máy chiếu và thử lại.")
                            .setPositiveButton("Đóng", null)
                            .show()
                    }
                }
            }
        }
    }

    fun installApk(activity: Activity, apkFile: File) {
        if (!apkFile.exists() || apkFile.length() < 100_000L) {
            Toast.makeText(activity, "File cài đặt không hợp lệ. Vui lòng tải lại.", Toast.LENGTH_LONG).show()
            return
        }

        // On Android 8.0+ (API 26+), check if unknown app install permission is granted
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!activity.packageManager.canRequestPackageInstalls()) {
                val dialog = AlertDialog.Builder(activity)
                    .setTitle("Cấp Quyền Cài Đặt Tự Động")
                    .setMessage("Để cập nhật trực tiếp trong ứng dụng, vui lòng gạt Cho Phép từ nguồn này cho AnPinime TV trong cài đặt hệ thống.")
                    .setPositiveButton("Mở Cài Đặt") { _, _ ->
                        try {
                            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                                data = Uri.parse("package:${activity.packageName}")
                            }
                            activity.startActivity(intent)
                        } catch (e: Exception) {
                            Log.e(TAG, "Cannot open unknown sources settings", e)
                        }
                    }
                    .setNegativeButton("Hủy", null)
                    .create()
                dialog.show()
                return
            }
        }

        try {
            val contentUri = FileProvider.getUriForFile(
                activity,
                "${activity.packageName}.provider",
                apkFile
            )

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            activity.startActivity(installIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch installer", e)
            AlertDialog.Builder(activity)
                .setTitle("Lỗi Mở Trình Cài Đặt")
                .setMessage("Không thể mở trình cài đặt tự động: ${e.message}\nFile đã lưu tại: ${apkFile.absolutePath}")
                .setPositiveButton("Đóng", null)
                .show()
        }
    }
}
