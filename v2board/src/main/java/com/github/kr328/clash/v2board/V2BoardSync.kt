package com.github.kr328.clash.v2board

import android.content.Context
import com.github.kr328.clash.common.log.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class V2BoardSync(private val context: Context) {
    val config = V2BoardConfig(context)
    val session = V2BoardSession(context)

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    fun getActiveUrl(): String {
        // Priority 1: Already probed working server URL (saved in config)
        if (config.serverUrl.isNotBlank()) return config.serverUrl

        // Priority 2: Primary URL from ConfigManager (assets/v2board.properties)
        val primaryUrl = ConfigManager.getServerUrl()
        if (primaryUrl.isNotBlank()) return primaryUrl

        // Priority 3: First domain from domain list (if any)
        return config.getDomainList().firstOrNull() ?: ""
    }

    fun resetApi() {
        // No-op: we no longer use Retrofit, OkHttp is stateless
    }

    suspend fun findWorkingDomain(): String? {
        return withContext(Dispatchers.IO) {
            for (domain in config.getDomainList()) {
                try {
                    val request = okhttp3.Request.Builder()
                        .url("$domain/api/v1/guest/comm/config")
                        .build()
                    httpClient.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            config.serverUrl = domain
                            Log.d("V2BoardSync: Found working domain: $domain")
                            return@withContext domain
                        }
                    }
                } catch (_: Exception) {}
            }
            null
        }
    }

    suspend fun fetchSubscribeUrl(): Result<String> {
        return withContext(Dispatchers.IO) {
            try {
                val auth = session.authData
                if (auth.isBlank()) return@withContext Result.failure(Exception("Not logged in"))

                // Ensure we have a working server URL before making the request
                var currentUrl = getActiveUrl()
                Log.d("V2BoardSync: Active URL: $currentUrl")

                if (currentUrl.isBlank()) {
                    val workingDomain = findWorkingDomain()
                    if (workingDomain == null) {
                        return@withContext Result.failure(Exception("No working server URL found"))
                    }
                    currentUrl = workingDomain
                    config.serverUrl = workingDomain
                }

                // 确保auth header格式正确，V2Board通常需要Bearer前缀
                val authHeader = if (auth.startsWith("Bearer ", ignoreCase = true) ||
                    auth.startsWith("Basic ", ignoreCase = true)) {
                    auth
                } else {
                    "Bearer $auth"
                }

                val apiUrl = "$currentUrl/api/v1/user/getSubscribe"
                Log.d("V2BoardSync: Fetching subscribe from: $apiUrl")

                // 直接用 OkHttp 请求，不用 Retrofit，避免 Gson 泛型类型解析问题
                val request = okhttp3.Request.Builder()
                    .url(apiUrl)
                    .header("Authorization", authHeader)
                    .header("Accept", "application/json")
                    .get()
                    .build()

                val response = httpClient.newCall(request).execute()
                val responseBody = response.body?.string() ?: ""

                Log.d("V2BoardSync: Response code: ${response.code}, body length: ${responseBody.length}")

                if (response.isSuccessful) {
                    val json = JSONObject(responseBody)
                    val data = json.optJSONObject("data")

                    if (data != null) {
                        val subscribeUrl = data.optString("subscribe_url", "")
                        val token = data.optString("token", "")

                        Log.d("V2BoardSync: subscribe_url=$subscribeUrl, token=$token")

                        // 优先使用 subscribe_url，如果为空则用 token 构造URL
                        val finalUrl = when {
                            subscribeUrl.isNotBlank() -> subscribeUrl
                            token.isNotBlank() -> "${getActiveUrl()}/api/v1/client/subscribe?token=$token"
                            else -> null
                        }

                        Log.d("V2BoardSync: Final subscribe URL: $finalUrl")

                        if (finalUrl != null) {
                            if (finalUrl.startsWith("http://") || finalUrl.startsWith("https://")) {
                                Result.success(finalUrl)
                            } else {
                                Result.failure(Exception("Invalid subscribe URL format"))
                            }
                        } else {
                            Result.failure(Exception("Subscribe URL is empty"))
                        }
                    } else {
                        val msg = json.optString("message", "Unknown error")
                        Log.w("V2BoardSync: API returned no data: $msg")
                        Result.failure(Exception(msg))
                    }
                } else {
                    Log.w("V2BoardSync: HTTP error: ${response.code}")

                    if (response.code == 401 || response.code == 403) {
                        session.clear()
                        Result.failure(Exception("Session expired, please login again"))
                    } else {
                        try {
                            val json = JSONObject(responseBody)
                            val msg = json.optString("message", "HTTP ${response.code}")
                            Result.failure(Exception(msg))
                        } catch (_: Exception) {
                            Result.failure(Exception("HTTP ${response.code}"))
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w("V2Board fetchSubscribe error: ${e.javaClass.simpleName}: ${e.message}")
                Result.failure(e)
            }
        }
    }

    companion object {
        @Volatile
        private var instance: V2BoardSync? = null

        fun getInstance(context: Context): V2BoardSync {
            return instance ?: synchronized(this) {
                instance ?: V2BoardSync(context.applicationContext).also { instance = it }
            }
        }
    }
}
