package com.github.kr328.clash.v2board

import android.content.Context
import com.github.kr328.clash.common.log.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
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

    private val probeClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .followRedirects(false)
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
        val active = config.activeDomain
        if (active.isNotBlank()) return active

        val serverUrl = config.serverUrl
        if (serverUrl.isNotBlank()) return serverUrl

        return config.getDomainList().firstOrNull() ?: ""
    }

    private fun probeDomain(url: String): Boolean {
        return try {
            val request = okhttp3.Request.Builder()
                .url("$url/api/v1/guest/comm/config")
                .build()
            probeClient.newCall(request).execute().use { it.isSuccessful }
        } catch (_: Exception) {
            false
        }
    }

    suspend fun findWorkingDomain(): String? {
        val domains = config.getDomainList()

        // Try each domain directly
        for (domain in domains) {
            if (probeDomain(domain)) {
                config.activeDomain = domain
                config.serverUrl = domain
                resetApi()
                Log.d("V2BoardSync: Found working domain: $domain")
                return domain
            }
        }

        // Try fetching updated domains from update server
        val updated = fetchUpdatedDomains()
        if (updated != null) {
            for (domain in updated) {
                if (probeDomain(domain)) {
                    config.activeDomain = domain
                    config.serverUrl = domain
                    resetApi()
                    Log.d("V2BoardSync: Found working domain (updated): $domain")
                    return domain
                }
            }
        }

        return null
    }

    suspend fun fetchUpdatedDomains(): List<String>? {
        return try {
            val updateUrl = config.activeUpdateUrl
            val request = okhttp3.Request.Builder()
                .url("$updateUrl/domains.json")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string()
                    if (body != null) {
                        val domainUpdate = gson.fromJson(body, DomainUpdate::class.java)
                        if (domainUpdate.domains?.isNotEmpty() == true) {
                            config.setDomainList(domainUpdate.domains)
                            config.updateUrl = domainUpdate.updateUrl ?: config.updateUrl
                            Log.d("V2BoardSync: Updated domains: ${domainUpdate.domains}")
                            return domainUpdate.domains
                        }
                    }
                }
                null
            }
        } catch (e: Exception) {
            Log.w("V2BoardSync: fetchUpdatedDomains failed: ${e.message}")
            null
        }
    }

    suspend fun fetchSubscribeUrl(): Result<String> {
        return try {
            val auth = session.authData
            if (auth.isBlank()) return Result.failure(Exception("Not logged in"))

            val response = getApi()?.getSubscribe(auth)
                ?: return Result.failure(Exception("Server URL not configured"))

            if (response.isSuccessful && response.body()?.data != null) {
                val data = response.body()!!.data!!
                if (data.token != null && data.token != session.userToken) {
                    session.userToken = data.token
                }
                val url = data.subscribeUrl
                if (!url.isNullOrBlank()) {
                    Result.success(url)
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

    data class DomainUpdate(
        @SerializedName("domains") val domains: List<String>?,
        @SerializedName("update_url") val updateUrl: String?,
    )

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
