package com.github.kr328.clash

import android.app.Application
import android.content.Context
import com.github.kr328.clash.common.Global
import com.github.kr328.clash.common.compat.currentProcessName
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.remote.Remote
import com.github.kr328.clash.service.util.sendServiceRecreated
import com.github.kr328.clash.util.clashDir
import com.github.kr328.clash.v2board.V2BoardConfig
import java.io.File
import java.io.FileOutputStream

@Suppress("unused")
class MainApplication : Application() {

    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)

        Global.init(this)
    }

    override fun onCreate() {
        super.onCreate()

        val processName = currentProcessName
        extractGeoFiles()
        initV2BoardConfig()

        Log.d("Process $processName started")

        if (processName == packageName) {
            Remote.launch()
        } else {
            sendServiceRecreated()
        }
    }

    private fun initV2BoardConfig() {
        val config = V2BoardConfig(this)

        if (BuildConfig.V2BOARD_URL.isNotBlank()) {
            if (config.serverUrl.isBlank()) {
                config.serverUrl = BuildConfig.V2BOARD_URL
            }
        }

        if (BuildConfig.V2BOARD_SYNC_INTERVAL > 0) {
            config.syncInterval = BuildConfig.V2BOARD_SYNC_INTERVAL
        }

        if (BuildConfig.V2BOARD_DOMAINS.isNotBlank()) {
            val domains = BuildConfig.V2BOARD_DOMAINS.split(",").map { it.trim() }.filter { it.isNotBlank() }
            if (domains.isNotEmpty() && config.domains.isBlank()) {
                config.setDomainList(domains)
            }
        }

        if (BuildConfig.V2BOARD_UPDATE_URL.isNotBlank()) {
            if (config.updateUrl.isBlank()) {
                config.updateUrl = BuildConfig.V2BOARD_UPDATE_URL
            }
        }

        if (config.serverUrl.isBlank() && config.activeDomain.isBlank()) {
            val firstDomain = config.getDomainList().firstOrNull()
            if (firstDomain != null) {
                config.serverUrl = firstDomain
                config.activeDomain = firstDomain
            }
        }
    }

    private fun extractGeoFiles() {
        clashDir.mkdirs()

        val updateDate = packageManager.getPackageInfo(packageName, 0).lastUpdateTime
        val geoipFile = File(clashDir, "geoip.metadb")
        if (geoipFile.exists() && geoipFile.lastModified() < updateDate) {
            geoipFile.delete()
        }
        if (!geoipFile.exists()) {
            FileOutputStream(geoipFile).use {
                assets.open("geoip.metadb").copyTo(it)
            }
        }

        val geositeFile = File(clashDir, "geosite.dat")
        if (geositeFile.exists() && geositeFile.lastModified() < updateDate) {
            geositeFile.delete()
        }
        if (!geositeFile.exists()) {
            FileOutputStream(geositeFile).use {
                assets.open("geosite.dat").copyTo(it)
            }
        }

        val asnFile = File(clashDir, "ASN.mmdb")
        if (asnFile.exists() && asnFile.lastModified() < updateDate) {
            asnFile.delete()
        }
        if (!asnFile.exists()) {
            FileOutputStream(asnFile).use {
                assets.open("ASN.mmdb").copyTo(it)
            }
        }
    }

    fun finalize() {
        Global.destroy()
    }
}
