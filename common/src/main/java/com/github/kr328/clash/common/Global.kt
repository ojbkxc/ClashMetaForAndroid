package com.github.kr328.clash.common

import android.app.Application
import com.github.kr328.clash.common.log.Log
import kotlinx.coroutines.*

object Global : CoroutineScope by CoroutineScope(SupervisorJob() + Dispatchers.IO +
        CoroutineExceptionHandler { _, e ->
            Log.e("Global scope unhandled exception: ${e.message}", e)
        }) {
    val application: Application
        get() = application_

    private lateinit var application_: Application

    fun init(application: Application) {
        this.application_ = application
    }

    fun destroy() {
        cancel()
    }
}