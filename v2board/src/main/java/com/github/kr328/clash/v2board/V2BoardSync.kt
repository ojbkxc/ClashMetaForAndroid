package com.github.kr328.clash.v2board

import android.content.Context
import com.github.kr328.clash.common.log.Log
import com.google.gson.Gson
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
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

    private val gson by lazy { Gson() }

    private var api: V2BoardApi? = null
    private var currentBaseUrl: String = ""

    fun getApi(): V2BoardApi? {
        val url = getActiveUrl()
        if (url.isBlank()) return null

        if (api != null && currentBaseUrl == url) return api

        synchronized(this) {
            if (api != null && currentBaseUrl == url) return api

            api = Retrofit.Builder()
                .baseUrl(url)
                .client(httpClient)
                .addConverterFactory(GsonConverterFactory.create(gson))
                .build()
                .create(V2BoardApi::class.java)

            currentBaseUrl = url
        }

        return api
    }

    fun resetApi() {
        api = null
        currentBaseUrl = ""
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

    suspend fun findWorkingDomain(): String? {
        for (domain in config.getDomainList()) {
            try {
                val request = okhttp3.Request.Builder()
                    .url("$domain/api/v1/guest/comm/config")
                    .build()
                httpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        config.serverUrl = domain
                        resetApi()
                        Log.d("V2BoardSync: Found working domain: $domain")
                        return domain
                    }
                }
            } catch (_: Exception) {}
        }
        return null
    }

    suspend fun fetchSubscribeUrl(): Result<String> {
        return try {
            val auth = session.authData
            if (auth.isBlank()) return Result.failure(Exception("Not logged in"))

            // Ensure we have a working server URL before making the request
            var currentUrl = getActiveUrl()
            if (currentUrl.isBlank()) {
                val workingDomain = findWorkingDomain()
                if (workingDomain == null) {
                    return Result.failure(Exception("No working server URL found"))
                }
                currentUrl = workingDomain
                config.serverUrl = workingDomain
                resetApi()
            }

            // 确保auth header格式正确，V2Board通常需要Bearer前缀
            val authHeader = if (auth.startsWith("Bearer ", ignoreCase = true) ||
                auth.startsWith("Basic ", ignoreCase = true)) {
                auth
            } else {
                "Bearer $auth"
            }

            val response = getApi()?.getSubscribe(authHeader)
                ?: return Result.failure(Exception("Server URL not configured"))

            if (response.isSuccessful && response.body()?.data != null) {
                val data = response.body()!!.data!!
                if (data.token != null && data.token != session.userToken) {
                    session.userToken = data.token
                }
                val url = data.subscribeUrl
                if (!url.isNullOrBlank()) {
                    // 验证URL格式
                    if (url.startsWith("http://") || url.startsWith("https://")) {
                        Result.success(url)
                    } else {
                        Result.failure(Exception("Invalid subscribe URL format"))
                    }
                } else {
                    Result.failure(Exception("Subscribe URL is empty"))
                }
            } else {
                if (response.code() == 401 || response.code() == 403) {
                    session.clear()
                    Result.failure(Exception("Session expired, please login again"))
                } else {
                    val msg = response.body()?.message ?: "Failed to fetch subscribe"
                    Result.failure(Exception(msg))
                }
            }
        } catch (e: Exception) {
            Log.w("V2Board fetchSubscribe error: ${e.message}")
            Result.failure(e)
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
