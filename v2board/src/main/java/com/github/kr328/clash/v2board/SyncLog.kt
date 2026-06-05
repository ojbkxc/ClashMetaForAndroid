package com.github.kr328.clash.v2board

import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.CopyOnWriteArrayList

object SyncLog {
    data class Entry(val time: Long, val message: String)

    private val entries = CopyOnWriteArrayList<Entry>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private const val MAX_ENTRIES = 100

    fun add(message: String) {
        val entry = Entry(System.currentTimeMillis(), message)
        entries.add(entry)
        // 超过上限时移除最旧的
        while (entries.size > MAX_ENTRIES) {
            entries.removeAt(0)
        }
        listeners.forEach { it() }
    }

    fun getAll(): List<Entry> = entries.toList()

    fun getFormatted(): String {
        if (entries.isEmpty()) return "暂无日志"
        return entries.joinToString("\n") { entry ->
            "[${timeFormat.format(Date(entry.time))}] ${entry.message}"
        }
    }

    fun clear() {
        entries.clear()
        listeners.forEach { it() }
    }

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }
}
