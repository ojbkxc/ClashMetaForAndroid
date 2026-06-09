package com.github.kr328.clash.util

import android.util.Log
import com.github.kr328.clash.util.AppError

object AppLog {
    private const val TAG = "ClashMeta"
    
    fun d(message: String) {
        Log.d(TAG, message)
    }
    
    fun d(tag: String, message: String) {
        Log.d(tag, message)
    }
    
    fun i(message: String) {
        Log.i(TAG, message)
    }
    
    fun i(tag: String, message: String) {
        Log.i(tag, message)
    }
    
    fun w(message: String) {
        Log.w(TAG, message)
    }
    
    fun w(tag: String, message: String) {
        Log.w(tag, message)
    }
    
    fun e(message: String, throwable: Throwable? = null) {
        Log.e(TAG, message, throwable)
    }
    
    fun e(tag: String, message: String, throwable: Throwable? = null) {
        Log.e(tag, message, throwable)
    }
    
    fun e(error: AppError) {
        Log.e(TAG, error.message ?: "Unknown error", error.cause)
    }
}