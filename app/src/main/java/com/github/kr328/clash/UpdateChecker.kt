package com.github.kr328.clash

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.content.SharedPreferences
import com.github.kr328.clash.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object UpdateChecker {
    private const val GITHUB_API = "https://api.github.com/repos/ojbkxc/ClashMetaForAndroid/releases/latest"
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

        AlertDialog.Builder(context)
            .setTitle(if (needUpdate) com.github.kr328.clash.design.R.string.update_found
                      else com.github.kr328.clash.design.R.string.update_already_latest)
            .setMessage(message)
            .setPositiveButton(if (needUpdate) com.github.kr328.clash.design.R.string.btn_download
                              else com.github.kr328.clash.design.R.string.btn_ok) { _, _ ->
                if (needUpdate) {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(release.apkDownloadUrl))
                    context.startActivity(intent)
                }
            }
            .setNegativeButton(com.github.kr328.clash.design.R.string.btn_cancel, null)
            .show()
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

    private const val CACHE_DURATION_MS = 24 * 60 * 60 * 1000L
}
