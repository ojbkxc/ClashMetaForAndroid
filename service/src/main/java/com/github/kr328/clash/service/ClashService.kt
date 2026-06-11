package com.github.kr328.clash.service

import android.content.Intent
import android.os.Binder
import android.os.IBinder
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.service.clash.clashRuntime
import com.github.kr328.clash.service.clash.module.*
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.service.util.cancelAndJoinBlocking
import com.github.kr328.clash.service.util.sendClashStarted
import com.github.kr328.clash.service.util.sendClashStopped
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.isActive
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext

class ClashService : BaseService() {
    private val self: ClashService
        get() = this

    private var reason: String? = null

    private val runtime = clashRuntime {
        val store = ServiceStore(self)

        val close = install(CloseModule(self))
        val config = install(ConfigurationModule(self))
        val network = install(NetworkObserveModule(self))

        if (store.dynamicNotification)
            install(DynamicNotificationModule(self))
        else
            install(StaticNotificationModule(self))

        install(AppListCacheModule(self))
        install(TimeZoneModule(self))
        install(SuspendModule(self))

        try {
            while (isActive) {
                val quit = select<Boolean> {
                    close.onEvent {
                        true
                    }
                    config.onEvent {
                        reason = it.message

                        true
                    }
                    network.onEvent {
                        false
                    }
                }

                if (quit) break
            }
        } catch (e: Exception) {
            Log.e("Create clash runtime: ${e.message}", e)

            reason = e.message
        } finally {
            withContext(NonCancellable) {
                stopSelf()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()

        if (StatusProvider.serviceRunning)
            return stopSelf()

        StatusProvider.serviceRunning = true

        // Cleanup any leftover rules from previous installations on startup
        // This ensures no stale iptables rules remain if app was killed unexpectedly
        com.github.kr328.clash.service.root.RootHelper.cleanupLeftoverRules()

        StaticNotificationModule.createNotificationChannel(this)
        StaticNotificationModule.notifyLoadingNotification(this)

        runtime.launch()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        sendClashStarted()

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder {
        return Binder()
    }

    override fun onDestroy() {
        StatusProvider.serviceRunning = false

        sendClashStopped(reason)

        cancelAndJoinBlocking()

        clearDnsHijackRulesOnDestroy()

        Log.i("ClashService destroyed: ${reason ?: "successfully"}")

        super.onDestroy()
    }

    private fun clearDnsHijackRulesOnDestroy() {
        try {
            // Batch all cleanup commands into a single shell script for performance
            val commands = mutableListOf<String>()

            for (table in listOf("nat", "mangle")) {
                // IPv4 rules - delete jumps then flush/delete chains
                commands.add("iptables -t $table -D PREROUTING -j $CHAIN_EXTERNAL 2>/dev/null")
                commands.add("iptables -t $table -D OUTPUT -j $CHAIN_LOCAL 2>/dev/null")
                commands.add("iptables -t $table -D PREROUTING -j $CHAIN_DNS_EXTERNAL 2>/dev/null")
                commands.add("iptables -t $table -D OUTPUT -j $CHAIN_DNS_LOCAL 2>/dev/null")
                commands.add("iptables -t $table -D OUTPUT -j $CHAIN_LOCK_BG 2>/dev/null")
                commands.add("iptables -t $table -F $CHAIN_EXTERNAL 2>/dev/null")
                commands.add("iptables -t $table -X $CHAIN_EXTERNAL 2>/dev/null")
                commands.add("iptables -t $table -F $CHAIN_LOCAL 2>/dev/null")
                commands.add("iptables -t $table -X $CHAIN_LOCAL 2>/dev/null")
                commands.add("iptables -t $table -F $CHAIN_DNS_EXTERNAL 2>/dev/null")
                commands.add("iptables -t $table -X $CHAIN_DNS_EXTERNAL 2>/dev/null")
                commands.add("iptables -t $table -F $CHAIN_DNS_LOCAL 2>/dev/null")
                commands.add("iptables -t $table -X $CHAIN_DNS_LOCAL 2>/dev/null")
                commands.add("iptables -t $table -F $CHAIN_LOCK_BG 2>/dev/null")
                commands.add("iptables -t $table -X $CHAIN_LOCK_BG 2>/dev/null")

                // IPv6 rules
                commands.add("ip6tables -t $table -D PREROUTING -j $CHAIN_EXTERNAL_V6 2>/dev/null")
                commands.add("ip6tables -t $table -D OUTPUT -j $CHAIN_LOCAL_V6 2>/dev/null")
                commands.add("ip6tables -t $table -D PREROUTING -j $CHAIN_DNS_EXTERNAL_V6 2>/dev/null")
                commands.add("ip6tables -t $table -D OUTPUT -j $CHAIN_DNS_LOCAL_V6 2>/dev/null")
                commands.add("ip6tables -t $table -F $CHAIN_EXTERNAL_V6 2>/dev/null")
                commands.add("ip6tables -t $table -X $CHAIN_EXTERNAL_V6 2>/dev/null")
                commands.add("ip6tables -t $table -F $CHAIN_LOCAL_V6 2>/dev/null")
                commands.add("ip6tables -t $table -X $CHAIN_LOCAL_V6 2>/dev/null")
                commands.add("ip6tables -t $table -F $CHAIN_DNS_EXTERNAL_V6 2>/dev/null")
                commands.add("ip6tables -t $table -X $CHAIN_DNS_EXTERNAL_V6 2>/dev/null")
                commands.add("ip6tables -t $table -F $CHAIN_DNS_LOCAL_V6 2>/dev/null")
                commands.add("ip6tables -t $table -X $CHAIN_DNS_LOCAL_V6 2>/dev/null")
            }

            // Execute as a single shell script (using ; not && to continue on errors)
            val script = commands.joinToString(";")
            com.github.kr328.clash.service.root.RootChecker.execute(script)

            Log.d("ClashService", "All clash rules cleared on destroy")
        } catch (e: Exception) {
            Log.e("ClashService", "Failed to clear clash rules", e)
        }
    }

    companion object {
        // Chain name constants matching RootHelper
        private const val CHAIN_EXTERNAL = "CLASH_EXTERNAL"
        private const val CHAIN_LOCAL = "CLASH_LOCAL"
        private const val CHAIN_LOCK_BG = "CLASH_LOCK_BG"
        private const val CHAIN_DNS_EXTERNAL = "CLASH_DNS_EXTERNAL"
        private const val CHAIN_DNS_LOCAL = "CLASH_DNS_LOCAL"
        private const val CHAIN_EXTERNAL_V6 = "CLASH_EXTERNAL_V6"
        private const val CHAIN_LOCAL_V6 = "CLASH_LOCAL_V6"
        private const val CHAIN_DNS_EXTERNAL_V6 = "CLASH_DNS_EXTERNAL_V6"
        private const val CHAIN_DNS_LOCAL_V6 = "CLASH_DNS_LOCAL_V6"
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)

        runtime.requestGc()
    }
}