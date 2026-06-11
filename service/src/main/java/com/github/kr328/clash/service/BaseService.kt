package com.github.kr328.clash.service

import android.app.Service
import android.os.PowerManager
import androidx.core.content.getSystemService
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.service.util.cancelAndJoinBlocking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

abstract class BaseService : Service(), CoroutineScope by CoroutineScope(Dispatchers.Default) {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()

        acquireWakeLock()
    }

    override fun onDestroy() {
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
}