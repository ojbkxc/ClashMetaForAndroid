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

    var activeDomain: String by store.string(
        key = "active_domain",
        defaultValue = "",
    )

    var domains: String by store.string(
        key = "domains",
        defaultValue = "",
    )

    var updateUrl: String by store.string(
        key = "update_url",
        defaultValue = "",
    )
        get() = field.takeIf { it.isNotBlank() } ?: DEFAULT_UPDATE_URL

    fun getDomainList(): List<String> {
        val stored = domains.takeIf { it.isNotBlank() }
        val builtIn = DEFAULT_DOMAINS
        return (stored?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }
            ?: builtIn).distinct()
    }

    fun setDomainList(list: List<String>) {
        domains = list.joinToString(",")
    }

    companion object {
        private const val FILE_NAME = "v2board_config"

        const val DEFAULT_SYNC_INTERVAL = 1440L

        val DEFAULT_DOMAINS = listOf(
            "https://jc.lxseek.com",
            "https://go.lxkjzh.top",
            "https://cdn.lxkjzh.top",
        )

        const val DEFAULT_UPDATE_URL = "https://update.lxseek.com"
    }
}
