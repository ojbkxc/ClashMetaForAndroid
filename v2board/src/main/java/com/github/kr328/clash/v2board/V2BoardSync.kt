package com.github.kr328.clash.v2board

import android.content.Context
import android.os.Build
import com.github.kr328.clash.common.log.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.net.Proxy
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

class V2BoardSync(private val context: Context) {
    val config = V2BoardConfig(context)
    val session = V2BoardSession(context)

    companion object {
        @Volatile
        private var instance: V2BoardSync? = null

        fun getInstance(context: Context): V2BoardSync {
            return instance ?: synchronized(this) {
                instance ?: V2BoardSync(context.applicationContext).also { instance = it }
            }
        }
    }

    private val httpClient by lazy {
        val builder = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            // 禁用代理，防止 Charles/Fiddler 等抓包工具拦截
            .proxy(Proxy.NO_PROXY)

        // Android 7+ 默认不信任用户安装的 CA 证书
        // 但为了防止 root 设备或特殊环境，额外限制只信任系统 CA
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            try {
                val trustManager = object : X509TrustManager {
                    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
                    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                        // 在低版本 Android 上，使用系统默认验证
                        val defaultTrustManager = getDefaultTrustManager()
                        defaultTrustManager?.checkServerTrusted(chain, authType)
                    }
                    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
                }
                val sslContext = SSLContext.getInstance("TLS")
                sslContext.init(null, arrayOf(trustManager), SecureRandom())
                builder.sslSocketFactory(sslContext.socketFactory, trustManager)
            } catch (_: Exception) {}
        }

        builder.build()
    }

    private fun getDefaultTrustManager(): X509TrustManager? {
        return try {
            val factory = javax.net.ssl.TrustManagerFactory.getInstance(
                javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm()
            )
            factory.init(null as java.security.KeyStore?)
            factory.trustManagers.filterIsInstance<X509TrustManager>().firstOrNull()
        } catch (_: Exception) { null }
    }

    fun getActiveUrl(): String {
        if (config.serverUrl.isNotBlank()) return config.serverUrl
        val primaryUrl = ConfigManager.getServerUrl()
        if (primaryUrl.isNotBlank()) return primaryUrl
        return config.getDomainList().firstOrNull() ?: ""
    }

    fun resetApi() {}

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
                            Log.d("V2BoardSync: Found working domain")
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
                if (auth.isBlank()) {
                    SyncLog.add("获取订阅失败: 未登录")
                    return@withContext Result.failure(Exception("Not logged in"))
                }

                var currentUrl = getActiveUrl()
                SyncLog.add("后端地址: ${SyncLog.maskUrl(currentUrl)}")

                if (currentUrl.isBlank()) {
                    SyncLog.add("正在探测可用域名...")
                    val workingDomain = findWorkingDomain()
                    if (workingDomain == null) {
                        SyncLog.add("错误: 没有可用的服务器地址")
                        return@withContext Result.failure(Exception("No working server URL found"))
                    }
                    currentUrl = workingDomain
                    config.serverUrl = workingDomain
                    SyncLog.add("找到可用域名: ${SyncLog.maskUrl(workingDomain)}")
                }

                val cleanAuth = auth.trim().removeSurrounding("\"").removeSurrounding("'")
                val apiUrl = "$currentUrl/api/v1/user/getSubscribe?auth_data=${java.net.URLEncoder.encode(cleanAuth, "UTF-8")}"
                SyncLog.add("请求订阅信息: ${SyncLog.maskUrl(currentUrl)}/api/v1/user/getSubscribe")

                val request = okhttp3.Request.Builder()
                    .url(apiUrl)
                    .header("Accept", "application/json")
                    .get()
                    .build()

                val response = httpClient.newCall(request).execute()
                val responseBody = response.body?.string() ?: ""

                SyncLog.add("服务器响应: HTTP ${response.code}")

                if (response.isSuccessful) {
                    val json = JSONObject(responseBody)
                    val data = json.optJSONObject("data")

                    if (data != null) {
                        // 解析额外信息
                        val plan = data.optJSONObject("plan")
                        session.planName = plan?.optString("name", "") ?: ""
                        session.resetDay = data.optInt("reset_day", 0)
                        if (data.has("expired_at") && !data.isNull("expired_at")) {
                            session.expiredAt = data.optLong("expired_at", 0L) * 1000L
                        }

                        val subscribeUrl = data.optString("subscribe_url", "")
                        val token = data.optString("token", "")

                        Log.d("V2BoardSync: got subscribe_url, token present: ${token.isNotBlank()}, plan: ${session.planName}")
                        SyncLog.add("获取到订阅地址")

                        val finalUrl = when {
                            subscribeUrl.isNotBlank() -> subscribeUrl
                            token.isNotBlank() -> "${getActiveUrl()}/api/v1/client/subscribe?token=$token"
                            else -> null
                        }

                        SyncLog.add("最终订阅URL: ${finalUrl?.let { SyncLog.maskUrl(it) }}")

                        if (finalUrl != null) {
                            if (finalUrl.startsWith("http://") || finalUrl.startsWith("https://")) {
                                Result.success(finalUrl)
                            } else {
                                SyncLog.add("错误: 订阅URL格式无效")
                                Result.failure(Exception("Invalid subscribe URL format"))
                            }
                        } else {
                            SyncLog.add("错误: 服务器未返回订阅地址")
                            Result.failure(Exception("Subscribe URL is empty"))
                        }
                    } else {
                        val msg = json.optString("message", "Unknown error")
                        SyncLog.add("服务器返回异常: $msg")
                        Result.failure(Exception(msg))
                    }
                } else {
                    Log.w("V2BoardSync: HTTP error: ${response.code}")

                    if (response.code == 401 || response.code == 403) {
                        SyncLog.add("请求被拒绝 (HTTP ${response.code})，可能需要重新登录")
                        Result.failure(Exception("HTTP ${response.code}，可能需要重新登录"))
                    } else {
                        try {
                            val json = JSONObject(responseBody)
                            val msg = json.optString("message", "HTTP ${response.code}")
                            SyncLog.add("请求失败: $msg")
                            Result.failure(Exception(msg))
                        } catch (_: Exception) {
                            SyncLog.add("请求失败: HTTP ${response.code}")
                            Result.failure(Exception("HTTP ${response.code}"))
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w("V2BoardSync: Network error", e)
                SyncLog.add("网络错误: ${e.message}")
                Result.failure(e)
            }
        }
    }

    suspend fun fetchUserInfo(): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                val auth = session.authData
                if (auth.isBlank()) return@withContext Result.failure(Exception("Not logged in"))

                val currentUrl = getActiveUrl()
                if (currentUrl.isBlank()) return@withContext Result.failure(Exception("No server URL"))

                val cleanAuth = auth.trim().removeSurrounding("\"").removeSurrounding("'")
                val apiUrl = "$currentUrl/api/v1/user/info?auth_data=${java.net.URLEncoder.encode(cleanAuth, "UTF-8")}"

                val request = okhttp3.Request.Builder()
                    .url(apiUrl)
                    .header("Accept", "application/json")
                    .get()
                    .build()

                val response = httpClient.newCall(request).execute()
                if (response.isSuccessful) {
                    val responseBody = response.body?.string() ?: ""
                    val json = JSONObject(responseBody)
                    val data = json.optJSONObject("data")
                    if (data != null) {
                        session.balance = data.optInt("balance", 0)
                        Log.d("V2BoardSync: balance=${session.balance}")
                    }
                    Result.success(Unit)
                } else {
                    Result.failure(Exception("HTTP ${response.code}"))
                }
            } catch (e: Exception) {
                Log.w("V2BoardSync: fetchUserInfo error", e)
                Result.failure(e)
            }
        }
    }
}
