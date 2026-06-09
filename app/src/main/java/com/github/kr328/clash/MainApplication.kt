package com.github.kr328.clash

import android.app.Application
import android.content.Context
import com.github.kr328.clash.common.Global
import com.github.kr328.clash.common.compat.currentProcessName
import com.github.kr328.clash.util.AppLog
import com.github.kr328.clash.remote.Remote
import com.github.kr328.clash.service.util.sendServiceRecreated
import com.github.kr328.clash.util.clashDir
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

        AppLog.d("Process $processName started")

        if (processName == packageName) {
            Remote.launch()
        } else {
            sendServiceRecreated()
        }
    }

    private fun extractGeoFiles() {
        try {
            clashDir.mkdirs()

            val updateDate = packageManager.getPackageInfo(packageName, 0).lastUpdateTime

            // Extract geoip.metadb
            val geoipFile = File(clashDir, "geoip.metadb")
            if (geoipFile.exists() && geoipFile.lastModified() < updateDate) {
                geoipFile.delete()
            }
            if (!geoipFile.exists()) {
                FileOutputStream(geoipFile).use {
                    assets.open("geoip.metadb").copyTo(it)
                }
            }

            // Extract geosite.dat
            val geositeFile = File(clashDir, "geosite.dat")
            if (geositeFile.exists() && geositeFile.lastModified() < updateDate) {
                geositeFile.delete()
            }
            if (!geositeFile.exists()) {
                FileOutputStream(geositeFile).use {
                    assets.open("geosite.dat").copyTo(it)
                }
            }

            // ASN.mmdb will be downloaded automatically by Clash core if not present
            // so we don't extract it from assets here
        } catch (e: Exception) {
            AppLog.w("Failed to extract geo files: ${e.message}")
        }
    }

    fun finalize() {
        Global.destroy()
    }
}
