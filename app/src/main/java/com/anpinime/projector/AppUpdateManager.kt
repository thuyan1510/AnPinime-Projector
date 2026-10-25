package com.anpinime.projector

import android.app.AlertDialog
import android.app.ProgressDialog
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

object AppUpdateManager {
    private const val TAG = "AppUpdateManager"
    private const val PRIMARY_UPDATE_URL =
        "https://github.com/thuyan1510/anpinime-dl/releases/download/latest/version.json"
    private const val FALLBACK_UPDATE_URL =
        "https://raw.githubusercontent.com/thuyan1510/AnPinime-Projector/main/version.json"

    data class UpdateInfo(
        val versionCode: Int,
        val versionName: String,
        val apkUrl: String,
        val changelog: String
    )

    suspend fun checkUpdate(context: Context, onNewVersion: (UpdateInfo) -> Unit) {
        withContext(Dispatchers.IO) {
            val info = fetchUpdateInfo() ?: return@withContext
            val currentCode = getAppVersionCode(context)
            Log.d(TAG, "Current versionCode: $currentCode, Server versionCode: ${info.versionCode}")

            if (info.versionCode > currentCode && info.apkUrl.isNotBlank()) {
                withContext(Dispatchers.Main) {
                    onNewVersion(info)
                }
            }
        }
    }

    private fun fetchUpdateInfo(): UpdateInfo? {
        for (urlString in listOf(PRIMARY_UPDATE_URL, FALLBACK_UPDATE_URL)) {
            try {
                val conn = URL(urlString).openConnection() as HttpURLConnection
                conn.connectTimeout = 8_000
                conn.readTimeout = 8_000
                conn.instanceFollowRedirects = true
                conn.setRequestProperty("User-Agent", "AnPinime-Projector-App")
                if (conn.responseCode == 200) {
                    val raw = conn.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(raw)
                    return UpdateInfo(
                        versionCode = json.optInt("versionCode", 0),
                        versionName = json.optString("versionName", ""),
                        apkUrl = json.optString("apkUrl", ""),
                        changelog = json.optString("changelog", "")
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed fetching update from $urlString: ${e.message}")
            }
        }
        return null
    }

    private fun getAppVersionCode(context: Context): Int {
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

    fun showUpdateDialog(context: Context, info: UpdateInfo) {
        val dialog = AlertDialog.Builder(context)
            .setTitle("🚀 Có Bản Cập Nhật Mới: v${info.versionName}")
            .setMessage(
                if (info.changelog.isNotBlank()) info.changelog
                else "Đã có phiên bản mới với nhiều cải tiến. Bạn có muốn cập nhật ngay bây giờ không?"
            )
            .setPositiveButton("Cập Nhật Ngay") { _, _ ->
                downloadAndInstallApk(context, info.apkUrl)
            }
            .setNegativeButton("Để Sau", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_POSITIVE)?.apply {
                isFocusable = true
                isFocusableInTouchMode = true
                requestFocus()
            }
        }
        dialog.show()
    }

    private fun downloadAndInstallApk(context: Context, apkUrl: String) {
        @Suppress("DEPRECATION")
        val progressDialog = ProgressDialog(context).apply {
            setTitle("Đang tải bản cập nhật...")
            setMessage("Vui lòng đợi giây lát...")
            setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
            max = 100
            progress = 0
            setCancelable(false)
            show()
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val apkFile = File(context.cacheDir, "update.apk")
                if (apkFile.exists()) apkFile.delete()

                val conn = URL(apkUrl).openConnection() as HttpURLConnection
                conn.connectTimeout = 15_000
                conn.readTimeout = 30_000
                conn.instanceFollowRedirects = true
                conn.setRequestProperty("User-Agent", "AnPinime-Projector-App")
                conn.connect()

                val totalLength = conn.contentLength
                conn.inputStream.use { input ->
                    FileOutputStream(apkFile).use { output ->
                        val buffer = ByteArray(16 * 1024)
                        var bytesRead: Int
                        var downloaded = 0L
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            downloaded += bytesRead
                            if (totalLength > 0) {
                                val pct = ((downloaded * 100) / totalLength).toInt()
                                withContext(Dispatchers.Main) {
                                    progressDialog.progress = pct
                                }
                            }
                        }
                    }
                }

                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    installApk(context, apkFile)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Download failed", e)
                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    AlertDialog.Builder(context)
                        .setTitle("Lỗi Tải Bản Cập Nhật")
                        .setMessage("Không thể tải bản cập nhật: ${e.message}\nVui lòng thử lại sau.")
                        .setPositiveButton("Đóng", null)
                        .show()
                }
            }
        }
    }

    fun installApk(context: Context, apkFile: File) {
        try {
            val contentUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.provider",
                apkFile
            )

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            context.startActivity(installIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch installer", e)
            AlertDialog.Builder(context)
                .setTitle("Lỗi Cài Đặt")
                .setMessage("Không thể mở trình cài đặt: ${e.message}")
                .setPositiveButton("Đóng", null)
                .show()
        }
    }
}
