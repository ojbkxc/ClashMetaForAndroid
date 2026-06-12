package com.github.kr328.clash

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import com.github.kr328.clash.common.log.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object UpdateChecker {
    private const val GITHUB_API = "https://api.github.com/repos/ojbkxc/ClashMetaForAndroid/releases"
    private const val PREF_NAME = "update_checker"
    private const val KEY_SKIPPED_VERSION = "skipped_version"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    data class ReleaseInfo(
        val tagName: String,
        val apkDownloadUrl: String,
        val body: String,
        val isPreRelease: Boolean = false
    )

    suspend fun checkForUpdate(context: Context): ReleaseInfo? = withContext(Dispatchers.IO) {
        try {
            // 获取所有 releases
            val request = Request.Builder()
                .url(GITHUB_API)
                .header("Accept", "application/vnd.github.v3+json")
                .build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return@withContext null

            val releasesArray = org.json.JSONArray(response.body?.string() ?: return@withContext null)

            var latestPreRelease: ReleaseInfo? = null
            var latestStableRelease: ReleaseInfo? = null

            for (i in 0 until releasesArray.length()) {
                val json = releasesArray.getJSONObject(i)
                val tagName = if (json.has("tag_name")) json.getString("tag_name") else ""
                val body = if (json.has("body")) json.getString("body") else ""
                val isPreRelease = if (json.has("prerelease")) json.getBoolean("prerelease") else false

                val assets = if (json.has("assets")) json.getJSONArray("assets") else null
                var apkUrl = ""
                if (assets != null) {
                    for (j in 0 until assets.length()) {
                        val asset = assets.getJSONObject(j)
                        val name = if (asset.has("name")) asset.getString("name") else ""
                        if (name.endsWith(".apk")) {
                            apkUrl = if (asset.has("browser_download_url")) asset.getString("browser_download_url") else ""
                            break
                        }
                    }
                }

                if (tagName.isBlank() || apkUrl.isBlank()) continue

                val release = ReleaseInfo(tagName, apkUrl, body, isPreRelease)

                if (isPreRelease) {
                    // 预发布版本：取第一个（通常是最新的测试版）
                    if (latestPreRelease == null) {
                        latestPreRelease = release
                    }
                } else {
                    // 正式版本：取第一个（通常是最新的正式版）
                    if (latestStableRelease == null) {
                        latestStableRelease = release
                    }
                }

                // 如果同时找到了预发布和正式版，可以提前退出
                if (latestPreRelease != null && latestStableRelease != null) {
                    break
                }
            }

            // 优先返回预发布版本，其次返回正式版本
            latestPreRelease ?: latestStableRelease
        } catch (e: Exception) {
            Log.w("UpdateChecker error: ${e.message}")
            null
        }
    }

    fun showUpdateDialog(context: Context, currentVersion: String, release: ReleaseInfo) {
        val needUpdate = compareVersions(currentVersion, release.tagName) < 0
        val versionTypeLabel = if (release.isPreRelease) "【测试版】" else ""
        val message = if (needUpdate) {
            "当前版本: $currentVersion\n${versionTypeLabel}最新版本: ${release.tagName}\n\n更新内容:\n${release.body.take(200)}"
        } else {
            "当前已是最新版本 ($currentVersion)"
        }

        val title = if (needUpdate) {
            if (release.isPreRelease) "发现测试版本" else "发现新版本"
        } else {
            "已是最新"
        }

        val builder = AlertDialog.Builder(context)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(if (needUpdate) "下载更新" else "确定") { _, _ ->
                if (needUpdate) {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(release.apkDownloadUrl))
                    context.startActivity(intent)
                }
            }
            .setNegativeButton("取消", null)

        if (needUpdate) {
            builder.setNeutralButton("跳过此版本") { _, _ ->
                skipVersion(context, release.tagName)
            }
        }

        builder.show()
    }

    fun isSkipped(context: Context, tagName: String): Boolean {
        return getPrefs(context).getString(KEY_SKIPPED_VERSION, null) == tagName
    }

    private fun skipVersion(context: Context, tagName: String) {
        getPrefs(context).edit().putString(KEY_SKIPPED_VERSION, tagName).apply()
    }

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
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
}
