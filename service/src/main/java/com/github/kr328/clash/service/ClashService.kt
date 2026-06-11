package com.github.kr328.clash.service

import android.content.Intent
import android.os.Binder
import android.os.IBinder
import com.github.kr328.clash.common.RootChecker
import com.github.kr328.clash.common.compat.startForegroundServiceCompat
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.service.clash.clashRuntime
import com.github.kr328.clash.service.clash.module.*
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.service.util.cancelAndJoinBlocking
import com.github.kr328.clash.service.util.sendClashStarted
import com.github.kr328.clash.service.util.sendClashStopped
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlin.concurrent.thread

class ClashService : BaseService() {
    private val self: ClashService
        get() = this

    private var reason: String? = null
    private var watchdogJob: Job? = null

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

        // Cleanup any leftover DNS hijacking rules from previous installations on a background thread.
        // This ensures no stale iptables rules remain if app was killed unexpectedly.
        thread(isDaemon = true) {
            try {
                com.github.kr328.clash.service.root.RootHelper.cleanupLeftoverRules()
            } catch (e: Exception) {
                Log.w("cleanupLeftoverRules failed: ${e.message}")
            }
        }

        StaticNotificationModule.createNotificationChannel(this)
        StaticNotificationModule.notifyLoadingNotification(this)

        runtime.launch()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        sendClashStarted()

        // 启动看门狗：每 60 秒 AlarmManager 触发检查，如果服务被杀了会自动重启
        ProfileReceiver.scheduleWatchdog(this)
        startWatchdogKeepAlive()

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder {
        return Binder()
    }

    override fun onDestroy() {
        stopWatchdogKeepAlive()
        ProfileReceiver.cancelWatchdog(this)

        StatusProvider.serviceRunning = false

        sendClashStopped(reason)

        cancelAndJoinBlocking()

        // Clear DNS hijacking rules on a background thread to avoid blocking main thread
        thread(isDaemon = true) {
            clearDnsHijackRulesOnDestroy()
        }

        Log.i("ClashService destroyed: ${reason ?: "successfully"}")

        super.onDestroy()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)

        runtime.requestGc()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)

        // 当用户从最近任务中划掉应用时，重新启动服务以确保代理继续运行
        Log.w("ClashService task removed, restarting service")
        val restartIntent = Intent(this, ClashService::class.java)
        startForegroundServiceCompat(restartIntent)
    }

    /**
     * 每 50 秒重新安排看门狗闹钟，防止服务存活时闹钟误触发。
     * 如果服务进程被杀死，闹钟会在 60 秒后触发并重启服务。
     */
    private fun startWatchdogKeepAlive() {
        watchdogJob = launch {
            while (isActive) {
                delay(50_000L) // 50 seconds, less than the 60s watchdog interval
                ProfileReceiver.scheduleWatchdog(this@ClashService)
            }
        }
    }

    private fun stopWatchdogKeepAlive() {
        watchdogJob?.cancel()
        watchdogJob = null
    }

    /**
     * Clear DNS hijacking iptables rules on service destroy.
     * Runs synchronously (called from a daemon background thread) to ensure
     * cleanup is complete before the process exits.
     */
    private fun clearDnsHijackRulesOnDestroy() {
        try {
            if (!RootChecker.isRooted()) return

            // Batch all cleanup commands into a single shell script for performance
            val commands = mutableListOf<String>()

            for (table in listOf("nat", "mangle")) {
                // IPv4 rules - delete jumps then flush/delete chains
                commands.add("iptables -t $table -D PREROUTING -j CLASH_EXTERNAL 2>/dev/null")
                commands.add("iptables -t $table -D OUTPUT -j CLASH_LOCAL 2>/dev/null")
                commands.add("iptables -t $table -D PREROUTING -j CLASH_DNS_EXTERNAL 2>/dev/null")
                commands.add("iptables -t $table -D OUTPUT -j CLASH_DNS_LOCAL 2>/dev/null")
                commands.add("iptables -t $table -D OUTPUT -j CLASH_LOCK_BG 2>/dev/null")
                commands.add("iptables -t $table -F CLASH_EXTERNAL 2>/dev/null")
                commands.add("iptables -t $table -X CLASH_EXTERNAL 2>/dev/null")
                commands.add("iptables -t $table -F CLASH_LOCAL 2>/dev/null")
                commands.add("iptables -t $table -X CLASH_LOCAL 2>/dev/null")
                commands.add("iptables -t $table -F CLASH_DNS_EXTERNAL 2>/dev/null")
                commands.add("iptables -t $table -X CLASH_DNS_EXTERNAL 2>/dev/null")
                commands.add("iptables -t $table -F CLASH_DNS_LOCAL 2>/dev/null")
                commands.add("iptables -t $table -X CLASH_DNS_LOCAL 2>/dev/null")
                commands.add("iptables -t $table -F CLASH_LOCK_BG 2>/dev/null")
                commands.add("iptables -t $table -X CLASH_LOCK_BG 2>/dev/null")

                // IPv6 rules
                commands.add("ip6tables -t $table -D PREROUTING -j CLASH_EXTERNAL_V6 2>/dev/null")
                commands.add("ip6tables -t $table -D OUTPUT -j CLASH_LOCAL_V6 2>/dev/null")
                commands.add("ip6tables -t $table -D PREROUTING -j CLASH_DNS_EXTERNAL_V6 2>/dev/null")
                commands.add("ip6tables -t $table -D OUTPUT -j CLASH_DNS_LOCAL_V6 2>/dev/null")
                commands.add("ip6tables -t $table -F CLASH_EXTERNAL_V6 2>/dev/null")
                commands.add("ip6tables -t $table -X CLASH_EXTERNAL_V6 2>/dev/null")
                commands.add("ip6tables -t $table -F CLASH_LOCAL_V6 2>/dev/null")
                commands.add("ip6tables -t $table -X CLASH_LOCAL_V6 2>/dev/null")
                commands.add("ip6tables -t $table -F CLASH_DNS_EXTERNAL_V6 2>/dev/null")
                commands.add("ip6tables -t $table -X CLASH_DNS_EXTERNAL_V6 2>/dev/null")
                commands.add("ip6tables -t $table -F CLASH_DNS_LOCAL_V6 2>/dev/null")
                commands.add("ip6tables -t $table -X CLASH_DNS_LOCAL_V6 2>/dev/null")
            }

            // Execute as a single shell script (using ; not && to continue on errors)
            val script = commands.joinToString(";")
            RootChecker.execute(script)

            Log.d("All clash rules cleared on destroy")
        } catch (e: Exception) {
            Log.e("Failed to clear clash rules", e)
        }
    }
}