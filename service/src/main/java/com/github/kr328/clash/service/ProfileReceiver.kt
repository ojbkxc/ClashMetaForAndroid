package com.github.kr328.clash.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.getSystemService
import com.github.kr328.clash.common.Global
import com.github.kr328.clash.common.compat.pendingIntentFlags
import com.github.kr328.clash.common.compat.startForegroundServiceCompat
import com.github.kr328.clash.common.constants.Intents
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.common.util.componentName
import com.github.kr328.clash.common.util.setUUID
import com.github.kr328.clash.service.data.Imported
import com.github.kr328.clash.service.data.ImportedDao
import com.github.kr328.clash.service.model.Profile
import com.github.kr328.clash.service.util.importedDir
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit

class ProfileReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_TIME_CHANGED -> {
                Global.launch {
                    reset()

                    val service = Intent(Intents.ACTION_PROFILE_SCHEDULE_UPDATES)
                        .setComponent(ProfileWorker::class.componentName)

                    context.startForegroundServiceCompat(service)
                }
            }
            Intents.ACTION_PROFILE_REQUEST_UPDATE -> {
                val redirect = intent.setComponent(ProfileWorker::class.componentName)

                context.startForegroundServiceCompat(redirect)
            }
            Intents.ACTION_SERVICE_WATCHDOG -> {
                // 看门狗触发：始终尝试重启服务。
                // 如果服务已在运行，startForegroundServiceCompat 仅触发 onStartCommand（即重新调度看门狗，无害）。
                // 如果服务已死，将创建新实例恢复代理。
                Log.d("ServiceWatchdog: ensuring service is alive")
                try {
                    val prefs = context.getSharedPreferences("ui", Context.MODE_PRIVATE)
                    if (prefs.getBoolean("enable_vpn", true)) {
                        val tunIntent = Intent(context, TunService::class.java)
                        context.startForegroundServiceCompat(tunIntent)
                    } else {
                        val serviceIntent = Intent(context, ClashService::class.java)
                        context.startForegroundServiceCompat(serviceIntent)
                    }
                } catch (_: Exception) {}
            }
        }
    }

    companion object {
        private val lock = Mutex()
        private var initialized: Boolean = false

        suspend fun rescheduleAll(context: Context) = lock.withLock {
            if (initialized)
                return

            initialized = true

            Log.i("Reschedule all profiles update")

            ImportedDao().queryAllUUIDs()
                .mapNotNull { ImportedDao().queryByUUID(it) }
                .filter { it.type != Profile.Type.File }
                .forEach { scheduleNext(context, it) }
        }

        fun cancelNext(context: Context, imported: Imported) {
            val intent = pendingIntentOf(context, imported)

            context.getSystemService<AlarmManager>()?.cancel(intent)
        }

        fun schedule(context: Context, imported: Imported) {
            val intent = pendingIntentOf(context, imported)

            context.getSystemService<AlarmManager>()?.cancel(intent)

            intent.send(context, 0, null)
        }

        fun scheduleNext(context: Context, imported: Imported) {
            val intent = pendingIntentOf(context, imported)

            context.getSystemService<AlarmManager>()?.cancel(intent)

            if (imported.interval < TimeUnit.MINUTES.toMillis(15))
                return

            val current = System.currentTimeMillis()
            val last = context.importedDir
                .resolve(imported.uuid.toString())
                .resolve("config.yaml")
                .lastModified()

            // file not existed
            if (last < 0)
                return

            val interval = (imported.interval - (current - last)).coerceAtLeast(0)

            context.getSystemService<AlarmManager>()
                ?.set(AlarmManager.RTC, current + interval, intent)
        }

        private suspend fun reset() = lock.withLock {
            initialized = false
        }

        private fun pendingIntentOf(context: Context, imported: Imported): PendingIntent {
            val intent = Intent(Intents.ACTION_PROFILE_REQUEST_UPDATE)
                .setComponent(ProfileReceiver::class.componentName)
                .setUUID(imported.uuid)

            return PendingIntent.getBroadcast(
                context,
                0,
                intent,
                pendingIntentFlags(PendingIntent.FLAG_UPDATE_CURRENT)
            )
        }

        /**
         * 安排看门狗闹钟，每 60 秒检查一次服务是否存活。
         * 如果服务进程被系统杀死，闹钟会触发 ProfileReceiver 重启服务。
         */
        fun scheduleWatchdog(context: Context) {
            val alarmManager = context.getSystemService<AlarmManager>() ?: return
            val intent = Intent(Intents.ACTION_SERVICE_WATCHDOG)
                .setComponent(ProfileReceiver::class.componentName)
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                WATCHDOG_REQUEST_CODE,
                intent,
                pendingIntentFlags(PendingIntent.FLAG_UPDATE_CURRENT)
            )

            try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        System.currentTimeMillis() + WATCHDOG_INTERVAL_MS,
                        pendingIntent
                    )
                } else {
                    alarmManager.setExact(
                        AlarmManager.RTC_WAKEUP,
                        System.currentTimeMillis() + WATCHDOG_INTERVAL_MS,
                        pendingIntent
                    )
                }
            } catch (e: Exception) {
                Log.w("Failed to schedule watchdog: ${e.message}")
            }
        }

        /**
         * 取消看门狗闹钟（服务正常停止时调用）。
         */
        fun cancelWatchdog(context: Context) {
            val alarmManager = context.getSystemService<AlarmManager>() ?: return
            val intent = Intent(Intents.ACTION_SERVICE_WATCHDOG)
                .setComponent(ProfileReceiver::class.componentName)
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                WATCHDOG_REQUEST_CODE,
                intent,
                pendingIntentFlags(PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_NO_CREATE)
            )

            pendingIntent?.let {
                alarmManager.cancel(it)
                it.cancel()
            }
        }

        private const val WATCHDOG_REQUEST_CODE = 0x7F00
        private const val WATCHDOG_INTERVAL_MS = 60_000L // 60 seconds
    }
}