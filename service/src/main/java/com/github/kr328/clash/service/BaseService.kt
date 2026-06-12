package com.github.kr328.clash.service

import android.app.Service
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.service.util.cancelAndJoinBlocking
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

abstract class BaseService : Service(), CoroutineScope by CoroutineScope(SupervisorJob() + Dispatchers.Default +
        CoroutineExceptionHandler { _, e ->
            Log.e("BaseService unhandled exception: ${e.message}", e)
        }) {
    override fun onDestroy() {
        super.onDestroy()

        cancelAndJoinBlocking()
    }
}