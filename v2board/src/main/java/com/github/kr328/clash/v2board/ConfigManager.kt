package com.github.kr328.clash.v2board

import android.content.Context
import java.util.Properties

object ConfigManager {
    private lateinit var properties: Properties
    private var initialized = false

    private const val DEFAULT_SERVER_URL = "https://jc.lxseek.com"
    private const val DEFAULT_DOMAINS = "https://jc.lxseek.com,https://go.lxkjzh.top,https://cdn.lxkjzh.top"
    private const val DEFAULT_SYNC_INTERVAL = "1440"
    private const val DEFAULT_APP_NAME = "蓝星网络"

    fun init(context: Context) {
        properties = Properties()
        try {
            context.assets.open("v2board.properties").use { inputStream ->
                properties.load(inputStream)
            }
        } catch (e: Exception) {
            // properties file not found, will use hardcoded defaults
        }
        initialized = true
    }

    fun getServerUrl(): String = if (initialized) properties.getProperty("v2board.server.url", DEFAULT_SERVER_URL) else DEFAULT_SERVER_URL
    fun getDomains(): List<String> = (if (initialized) properties.getProperty("v2board.server.domains", DEFAULT_DOMAINS) else DEFAULT_DOMAINS)
        .split(",")
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinct()
    fun getSyncIntervalMinutes(): Long = (if (initialized) properties.getProperty("v2board.sync.interval", DEFAULT_SYNC_INTERVAL) else DEFAULT_SYNC_INTERVAL).toLongOrNull() ?: 1440L
    fun getAppName(): String = if (initialized) properties.getProperty("v2board.app.name", DEFAULT_APP_NAME) else DEFAULT_APP_NAME
}
