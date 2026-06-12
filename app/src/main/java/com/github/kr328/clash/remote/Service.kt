package com.github.kr328.clash.remote

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import com.github.kr328.clash.common.Global
import com.github.kr328.clash.util.AppLog
import com.github.kr328.clash.common.util.intent
import com.github.kr328.clash.service.RemoteService
import com.github.kr328.clash.service.remote.IRemoteService
import com.github.kr328.clash.service.remote.unwrap
import com.github.kr328.clash.util.unbindServiceSilent
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class Service(private val context: Application, val crashed: () -> Unit) {
    val remote = Resource<IRemoteService>()

    private var rebinding = false

    private val connection = object : ServiceConnection {
        private var lastCrashed: Long = -1

        override fun onServiceConnected(name: ComponentName?, service: IBinder) {
            remote.set(service.unwrap(IRemoteService::class))
            rebinding = false
            AppLog.d("Remote", "RemoteService connected")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            remote.set(null)

            AppLog.w("Remote", "RemoteService killed or crashed, attempting rebind...")

            // Try to rebind the service before crashing the UI.
            // Many Android ROMs kill background services temporarily;
            // a quick rebind often succeeds without disrupting the user.
            if (!rebinding) {
                rebinding = true
                Global.launch {
                    tryRebind()
                }
            }
        }
    }

    private suspend fun tryRebind() {
        var attempts = 0
        while (attempts < MAX_REBIND_ATTEMPTS && rebinding) {
            attempts++
            delay(REBIND_DELAY_MS)
            try {
                context.bindService(RemoteService::class.intent, connection, Context.BIND_AUTO_CREATE)
                AppLog.d("Remote", "Rebind attempt $attempts succeeded")
                return
            } catch (_: Exception) {
                AppLog.w("Remote", "Rebind attempt $attempts failed")
            }
        }

        // All rebind attempts failed — now notify the crash handler
        if (rebinding) {
            rebinding = false
            AppLog.e("Remote", "All rebind attempts failed, notifying crash handler")
            crashed()
        }
    }

    fun bind() {
        try {
            context.bindService(RemoteService::class.intent, connection, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) {
            AppLog.e("Remote", "Initial bind failed", e)
            crashed()
        }
    }

    fun unbind() {
        rebinding = false
        context.unbindServiceSilent(connection)
        remote.set(null)
    }

    companion object {
        private val REBIND_DELAY_MS = TimeUnit.SECONDS.toMillis(2)
        private const val MAX_REBIND_ATTEMPTS = 3
    }
}