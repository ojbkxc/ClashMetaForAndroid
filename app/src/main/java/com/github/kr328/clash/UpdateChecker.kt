package com.github.kr328.clash

import android.app.AlertDialog
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.FileProvider
import com.github.kr328.clash.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.buffer
import okio.sink
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

object UpdateChecker {
    private const val GITHUB_API = "https://api.github.com/repos/ojbkxc/ClashMetaForAndroid/releases/latest"
    private const val CHANNEL_ID = "update_download"
    private const val NOTIFICATION_ID = 1002
    private const val CACHE_DURATION_MS = 24 * 60 * 60 * 1000L

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private var cachedResult: ReleaseInfo? = null
    private var lastCheckTime: Long = 0

    data class ReleaseInfo(
        val tagName: String,
        val apkDownloadUrl: String,
        val body: String
    )

    suspend fun checkForUpdate(context: Context): ReleaseInfo? = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        
        if (cachedResult != null && now - lastCheckTime < CACHE_DURATION_MS) {
            return@withContext cachedResult
        }

        try {
            val request = Request.Builder().url(GITHUB_API).build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return@withContext null

            val json = JSONObject(response.body?.string() ?: return@withContext null)
            val tagName = if (json.has("tag_name")) json.getString("tag_name") else ""
            val body = if (json.has("body")) json.getString("body") else ""

            val assets = if (json.has("assets")) json.getJSONArray("assets") else return@withContext null
            var apkUrl = ""
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val name = if (asset.has("name")) asset.getString("name") else ""
                if (name.endsWith(".apk")) {
                    apkUrl = if (asset.has("browser_download_url")) asset.getString("browser_download_url") else ""
                    break
                }
            }
            if (tagName.isBlank() || apkUrl.isBlank()) return@withContext null

            ReleaseInfo(tagName, apkUrl, body).also {
                cachedResult = it
                lastCheckTime = now
            }
        } catch (e: Exception) {
            AppLog.w("UpdateChecker", "Error: ${e.message}")
            null
        }
    }

    fun showUpdateDialog(context: Context, currentVersion: String, release: ReleaseInfo) {
        val needUpdate = compareVersions(currentVersion, release.tagName) < 0
        val message = if (needUpdate) {
            context.getString(com.github.kr328.clash.design.R.string.update_current_version,
                currentVersion, release.tagName, release.body.take(200))
        } else {
            context.getString(com.github.kr328.clash.design.R.string.update_is_latest, currentVersion)
        }

        val builder = AlertDialog.Builder(context)
            .setTitle(if (needUpdate) com.github.kr328.clash.design.R.string.update_found
                      else com.github.kr328.clash.design.R.string.update_already_latest)
            .setMessage(message)
            .setNegativeButton(com.github.kr328.clash.design.R.string.btn_cancel, null)

        if (needUpdate) {
            builder.setPositiveButton(com.github.kr328.clash.design.R.string.btn_download) { _, _ ->
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(release.apkDownloadUrl))
                context.startActivity(intent)
            }
            builder.setNeutralButton(com.github.kr328.clash.design.R.string.btn_background_download) { _, _ ->
                downloadAndInstall(context, release)
            }
        } else {
            builder.setPositiveButton(com.github.kr328.clash.design.R.string.btn_ok, null)
        }

        builder.show()
    }

    /**
     * 后台下载 APK 并显示通知进度，下载完成后触发安装。
     */
    private fun downloadAndInstall(context: Context, release: ReleaseInfo) {
        // 确保通知渠道已创建
        createNotificationChannel(context)

        val notificationManager = NotificationManagerCompat.from(context)
        val cancelIntent = PendingIntent.getActivity(
            context, 0, Intent(), PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(com.github.kr328.clash.service.R.drawable.ic_logo_service)
            .setContentTitle(context.getString(com.github.kr328.clash.design.R.string.downloading_update))
            .setContentText(context.getString(com.github.kr328.clash.design.R.string.download_preparing))
            .setProgress(100, 0, true)
            .setOngoing(true)
            .setAutoCancel(true)
            .setDeleteIntent(cancelIntent)

        // 显示初始通知
        notificationManager.notify(NOTIFICATION_ID, builder.build())

        Thread {
            try {
                val downloadDir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                    ?: context.filesDir, "downloads")
                if (!downloadDir.exists()) downloadDir.mkdirs()

                val apkFile = File(downloadDir, "ClashMetaForAndroid-${release.tagName}.apk")

                // 如果文件已存在，直接安装
                if (apkFile.exists() && apkFile.length() > 0) {
                    builder.setContentText(context.getString(
                        com.github.kr328.clash.design.R.string.download_complete_installing))
                        .setProgress(0, 0, false)
                        .setOngoing(false)
                    notificationManager.notify(NOTIFICATION_ID, builder.build())
                    installApk(context, apkFile)
                    return@Thread
                }

                val request = Request.Builder().url(release.apkDownloadUrl).build()
                val response = client.newCall(request).execute()
                if (!response.isSuccessful) {
                    showDownloadFailed(context, notificationManager, builder,
                        context.getString(com.github.kr328.clash.design.R.string.download_failed_network))
                    return@Thread
                }

                val body = response.body ?: run {
                    showDownloadFailed(context, notificationManager, builder,
                        context.getString(com.github.kr328.clash.design.R.string.download_failed_network))
                    return@Thread
                }

                val contentLength = body.contentLength()
                val source = body.source()
                val sink = apkFile.sink().buffer()

                var totalBytesRead = 0L
                var lastNotifyTime = 0L
                val buffer = okio.Buffer()
                val step = 8192L

                source.use { src ->
                    sink.use { dst ->
                        while (true) {
                            val read = src.read(buffer, step)
                            if (read == -1L) break
                            dst.write(buffer, read)
                            totalBytesRead += read

                            val now = System.currentTimeMillis()
                            if (now - lastNotifyTime > 200) {
                                lastNotifyTime = now
                                val progress = if (contentLength > 0) {
                                    ((totalBytesRead * 100) / contentLength).toInt().coerceIn(0, 100)
                                } else {
                                    -1
                                }
                                val progressText = if (contentLength > 0) {
                                    formatFileSize(totalBytesRead) + " / " + formatFileSize(contentLength)
                                } else {
                                    formatFileSize(totalBytesRead)
                                }
                                builder.setContentText(progressText)
                                    .setProgress(100, progress, progress < 0)
                                notificationManager.notify(NOTIFICATION_ID, builder.build())
                            }
                        }
                    }
                }

                // 下载完成
                builder.setContentTitle(context.getString(com.github.kr328.clash.design.R.string.download_complete))
                    .setContentText(context.getString(com.github.kr328.clash.design.R.string.download_complete_installing))
                    .setProgress(0, 0, false)
                    .setOngoing(false)
                notificationManager.notify(NOTIFICATION_ID, builder.build())

                installApk(context, apkFile)

            } catch (e: IOException) {
                AppLog.e("UpdateChecker", "Download failed: ${e.message}")
                showDownloadFailed(context, notificationManager, builder,
                    context.getString(com.github.kr328.clash.design.R.string.download_failed_network))
            } catch (e: Exception) {
                AppLog.e("UpdateChecker", "Download error: ${e.message}")
                showDownloadFailed(context, notificationManager, builder,
                    "${e.message}")
            }
        }.start()
    }

    private fun installApk(context: Context, apkFile: File) {
        try {
            val apkUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (!context.packageManager.canRequestPackageInstalls()) {
                    // 引导用户开启 "允许安装未知应用" 权限
                    val settingsIntent = Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                        data = Uri.parse("package:${context.packageName}")
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(settingsIntent)
                    AppLog.w("UpdateChecker", "Unknown app install permission not granted")
                    return
                }
            }

            context.startActivity(intent)
        } catch (e: Exception) {
            AppLog.e("UpdateChecker", "Install failed: ${e.message}")
        }
    }

    private fun showDownloadFailed(
        context: Context,
        notificationManager: NotificationManagerCompat,
        builder: NotificationCompat.Builder,
        error: String
    ) {
        builder.setContentTitle(context.getString(com.github.kr328.clash.design.R.string.download_failed))
            .setContentText(error)
            .setProgress(0, 0, false)
            .setOngoing(false)
        notificationManager.notify(NOTIFICATION_ID, builder.build())
    }

    private fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(com.github.kr328.clash.design.R.string.download_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = context.getString(com.github.kr328.clash.design.R.string.download_channel_desc)
            }
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    // 简单版本比较: "1.2.3" > "1.2.2"
    fun compareVersions(v1: String, v2: String): Int {
        val parts1 = v1.trimStart('v').split(".").map { it.toIntOrNull() ?: 0 }
        val parts2 = v2.trimStart('v').split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(parts1.size, parts2.size)) {
            val p1 = if (i < parts1.size) parts1[i] else 0
            val p2 = if (i < parts2.size) parts2[i] else 0
            if (p1 != p2) return p1.compareTo(p2)
        }
        return 0
    }

    private fun formatFileSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
            bytes < 1024 * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
            else -> "%.2f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
        }
    }
}
