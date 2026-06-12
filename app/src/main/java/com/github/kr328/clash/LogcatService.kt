package com.github.kr328.clash

import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.IBinder
import android.os.IInterface
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.github.kr328.clash.common.compat.getColorCompat
import com.github.kr328.clash.common.compat.pendingIntentFlags
import com.github.kr328.clash.common.compat.startForegroundCompat
import com.github.kr328.clash.util.AppLog
import com.github.kr328.clash.common.util.intent
import com.github.kr328.clash.core.model.LogMessage
import com.github.kr328.clash.log.LogcatCache
import com.github.kr328.clash.log.LogcatWriter
import com.github.kr328.clash.service.RemoteService
import com.github.kr328.clash.service.remote.IClashManager
import com.github.kr328.clash.service.remote.ILogObserver
import com.github.kr328.clash.service.remote.IRemoteService
import com.github.kr328.clash.service.remote.unwrap
import com.github.kr328.clash.util.logsDir
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.io.IOException
import java.util.*

class LogcatService : Service(), CoroutineScope by CoroutineScope(SupervisorJob() + Dispatchers.Default +
        CoroutineExceptionHandler { _, e ->
            android.util.Log.e("LogcatService", "unhandled exception: ${e.message}", e)
        }), IInterface {
    private val cache = LogcatCache()

    private val connection = object : ServiceConnection {
        override fun onServiceDisconnected(name: ComponentName?) {
            stopSelf()
        }

        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            startObserver(service ?: return stopSelf())
        }
    }

    override fun onCreate() {
        super.onCreate()

        running = true

        createNotificationChannel()

        showNotification()

        bindService(RemoteService::class.intent, connection, Context.BIND_AUTO_CREATE)
    }

    override fun onDestroy() {
        cancel()

        unbindService(connection)

        stopForeground(true)

        running = false

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder {
        return this.asBinder()
    }

    override fun asBinder(): IBinder {
        return object : Binder() {
            override fun queryLocalInterface(descriptor: String): IInterface {
                return this@LogcatService
            }
        }
    }

    fun snapshot(full: Boolean): LogcatCache.Snapshot? {
        return cache.snapshot(full)
    }

    private fun startObserver(binder: IBinder) {
        if (!binder.isBinderAlive)
            return stopSelf()

        launch(Dispatchers.IO) {
            val clash: com.github.kr328.clash.service.remote.IClashManager
            try {
                clash = binder.unwrap(IRemoteService::class).clash()
            } catch (e: Exception) {
                AppLog.e("Logcat", "Failed to bind log observer: ${e.message}", e)
                stopSelf()
                return@launch
            }
            val channel = Channel<LogMessage>(CACHE_CAPACITY)

            try {
                logsDir.mkdirs()

                LogcatWriter(this@LogcatService).use {
                    val observer = object : ILogObserver {
                        override fun newItem(log: LogMessage) {
                            if (isActive) {
                                channel.trySend(log)
                            }
                        }
                    }

                    clash.setLogObserver(observer)

                    while (isActive) {
                        val msg = channel.receive()

                        it.appendMessage(msg)

                        cache.append(msg)
                    }
                }
            } catch (e: IOException) {
                AppLog.e("Logcat", "Write log file: $e", e)
            } catch (e: Exception) {
                AppLog.e("Logcat", "Unexpected error in log observer: ${e.message}", e)
            } finally {
                withContext(NonCancellable) {
                    try {
                        if (binder.isBinderAlive) {
                            clash.setLogObserver(null)
                        }
                    } catch (_: Exception) {}

                    stopSelf()
                }
            }
        }
    }

    private fun createNotificationChannel() {
        NotificationManagerCompat.from(this)
            .createNotificationChannel(
                NotificationChannelCompat.Builder(
                    CHANNEL_ID,
                    NotificationManagerCompat.IMPORTANCE_DEFAULT
                ).setName(getString(com.github.kr328.clash.design.R.string.clash_logcat)).build()
            )
    }

    private fun showNotification() {
        val notification = NotificationCompat
            .Builder(this, CHANNEL_ID)
            .setSmallIcon(com.github.kr328.clash.service.R.drawable.ic_logo_service)
            .setColor(getColorCompat(com.github.kr328.clash.design.R.color.color_clash_light))
            .setContentTitle(getString(com.github.kr328.clash.design.R.string.clash_logcat))
            .setContentText(getString(com.github.kr328.clash.design.R.string.running))
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    R.id.nf_logcat_status,
                    LogcatActivity::class.intent
                        .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                    pendingIntentFlags(PendingIntent.FLAG_UPDATE_CURRENT)
                )
            )
            .build()

        startForegroundCompat(R.id.nf_logcat_status, notification)
    }

    companion object {
        private const val CHANNEL_ID = "clash_logcat_channel"
        private const val CACHE_CAPACITY = 128

        var running: Boolean = false
    }
}