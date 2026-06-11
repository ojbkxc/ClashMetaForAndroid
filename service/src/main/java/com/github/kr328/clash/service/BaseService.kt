package com.github.kr328.clash.service

import android.app.Service
import android.os.PowerManager
import androidx.core.content.getSystemService
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.service.util.cancelAndJoinBlocking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

abstract class BaseService : Service(), CoroutineScope by CoroutineScope(Dispatchers.Default) {
    private var wakeLock: PowerManager.WakeLock? = null
    private var wakeLockKeepAliveJob: Job? = null

    override fun onCreate() {
        super.onCreate()

        acquireWakeLock()
        startWakeLockKeepAlive()
    }

    override fun onDestroy() {
        stopWakeLockKeepAlive()
        releaseWakeLock()

        super.onDestroy()

        cancelAndJoinBlocking()
    }

    private fun acquireWakeLock() {
        try {
            val powerManager = getSystemService<PowerManager>() ?: return
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "ClashMeta:${javaClass.simpleName}"
            ).apply {
                setReferenceCounted(false)
                acquire()
            }
            Log.d("WakeLock acquired for ${javaClass.simpleName}")
        } catch (e: Exception) {
            Log.w("Failed to acquire WakeLock: ${e.message}")
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                }
            }
            wakeLock = null
            Log.d("WakeLock released for ${javaClass.simpleName}")
        } catch (e: Exception) {
            Log.w("Failed to release WakeLock: ${e.message}")
        }
    }

    /**
     * 高频检查 WakeLock，防止屏幕关闭时被系统抢夺或释放。
     * 每 30 秒检查一次，确保 CPU 不会在休眠时挂起。
     */
    private fun startWakeLockKeepAlive() {
        wakeLockKeepAliveJob = launch {
            while (isActive) {
                delay(30 * 1000L) // 30 seconds - 休眠时系统可能在几秒内抢夺 WakeLock
                try {
                    val wl = wakeLock
                    if (wl == null) {
                        // WakeLock 对象被意外置空，重新获取
                        Log.w("WakeLock object is null, re-acquiring for ${javaClass.simpleName}")
                        acquireWakeLock()
                    } else if (!wl.isHeld) {
                        Log.w("WakeLock was released by system, re-acquiring for ${javaClass.simpleName}")
                        wl.acquire()
                    }
                } catch (e: Exception) {
                    Log.w("WakeLock keep-alive check failed, trying full re-acquire: ${e.message}")
                    try {
                        wakeLock?.let { if (it.isHeld) it.release() }
                        wakeLock = null
                        acquireWakeLock()
                    } catch (_: Exception) {}
                }
            }
        }
    }

    private fun stopWakeLockKeepAlive() {
        wakeLockKeepAliveJob?.cancel()
        wakeLockKeepAliveJob = null
    }
}