package com.github.kr328.clash.util

import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

suspend fun SharedPreferences.editAsync(block: SharedPreferences.Editor.() -> Unit) {
    withContext(Dispatchers.IO) {
        edit().apply(block).apply()
    }
}

suspend fun SharedPreferences.putAsync(key: String, value: String) {
    editAsync { putString(key, value) }
}

suspend fun SharedPreferences.putAsync(key: String, value: Int) {
    editAsync { putInt(key, value) }
}

suspend fun SharedPreferences.putAsync(key: String, value: Boolean) {
    editAsync { putBoolean(key, value) }
}

suspend fun SharedPreferences.putAsync(key: String, value: Long) {
    editAsync { putLong(key, value) }
}