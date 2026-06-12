package com.github.kr328.clash.util

import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.core.app.NotificationManagerCompat
import com.github.kr328.clash.common.compat.startForegroundServiceCompat
import com.github.kr328.clash.common.constants.Intents
import com.github.kr328.clash.common.util.intent
import com.github.kr328.clash.design.store.UiStore
import com.github.kr328.clash.service.ClashService
import com.github.kr328.clash.service.TunService
import com.github.kr328.clash.service.util.sendBroadcastSelf

fun Context.startClashService(): Intent? {
    val startTun = UiStore(this).enableVpn

    if (startTun) {
        val vpnRequest = VpnService.prepare(this)
        if (vpnRequest != null)
            return vpnRequest

        startForegroundServiceCompat(TunService::class.intent)
    } else {
        startForegroundServiceCompat(ClashService::class.intent)
    }

    // Check if battery optimization is enabled and warn user
    if (shouldWarnBatteryOptimization(this)) {
        AppLog.w("Clash", "Battery optimization is still enabled - service may be killed in background")
    }

    return null
}

fun Context.stopClashService() {
    sendBroadcastSelf(Intent(Intents.ACTION_CLASH_REQUEST_STOP))
}

/**
 * Check if we should warn the user about battery optimization.
 * Only show warning if battery optimization is enabled AND notifications are allowed.
 */
fun shouldWarnBatteryOptimization(context: Context): Boolean {
    val notBatteryOptimized = !BatteryOptimization.isIgnoringBatteryOptimizations(context)
    val notificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
    return notBatteryOptimized && notificationsEnabled
}