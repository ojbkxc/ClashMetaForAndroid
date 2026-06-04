package com.github.kr328.clash.v2board

import android.content.Context
import java.util.Properties

object ConfigManager {
    private lateinit var properties: Properties

    fun init(context: Context) {
        properties = Properties()
        try {
            context.assets.open("v2board.properties").use { inputStream ->
                properties.load(inputStream)
            }
        } catch (e: Exception) {
            // Fallback to empty properties
            e.printStackTrace()
        }
    }

    fun getServerUrl(): String = properties.getProperty("v2board.server.url", "")
    fun getDomains(): List<String> = properties.getProperty("v2board.server.domains", "")
        .split(",")
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinct()
    fun getSyncIntervalMinutes(): Long = properties.getProperty("v2board.sync.interval", "1440").toLongOrNull() ?: 1440L
    fun getAppName(): String = properties.getProperty("v2board.app.name", "蓝星网络")
}
