package com.github.kr328.clash.v2board

import android.content.Context
import com.github.kr328.clash.common.store.Store
import com.github.kr328.clash.common.store.asStoreProvider

class V2BoardConfig(context: Context) {
    private val store = Store(
        context
            .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
            .asStoreProvider()
    )

    var serverUrl: String by store.string(
        key = "server_url",
        defaultValue = "",
    )

    var syncInterval: Long by store.long(
        key = "sync_interval",
        defaultValue = DEFAULT_SYNC_INTERVAL,
    )

    fun getDomainList(): List<String> {
        // Use ConfigManager as the single source of truth for domains
        val configDomains = ConfigManager.getDomains()
        if (configDomains.isNotEmpty()) return configDomains

        // Fallback: use the serverUrl if it's set (backward compatibility)
        return listOfNotNull(serverUrl.takeIf { it.isNotBlank() })
    }

    companion object {
        private const val FILE_NAME = "v2board_config"
        const val DEFAULT_SYNC_INTERVAL = 1440L
    }
}
