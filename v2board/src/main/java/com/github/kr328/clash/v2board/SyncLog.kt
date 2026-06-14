package com.github.kr328.clash.v2board

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.CopyOnWriteArrayList

object SyncLog {
    data class Entry(val time: Long, val message: String)

    private val entries = CopyOnWriteArrayList<Entry>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private const val MAX_ENTRIES = 100
    private var logFile: File? = null

    fun init(context: Context) {
        logFile = File(context.getExternalFilesDir(null), "sync_log.txt")
    }

    fun add(message: String) {
        val entry = Entry(System.currentTimeMillis(), message)
        entries.add(entry)
        while (entries.size > MAX_ENTRIES) {
            entries.removeAt(0)
        }
        // 写入文件
        appendToFile(entry)
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

    /**
     * 域名脱敏: https://cdn.lxseek.link → https://c***.l***.***
     */
    fun maskUrl(url: String): String {
        return try {
            val uri = java.net.URI(url)
            val host = uri.host ?: return "***"
            val parts = host.split(".")
            if (parts.size >= 2) {
                val masked = parts.joinToString(".") { part ->
                    if (part.length <= 2) "***" else "${part.first()}***"
                }
                "${uri.scheme}://$masked"
            } else {
                "${uri.scheme}://***"
            }
        } catch (_: Exception) {
            "***"
        }
    }

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    private fun appendToFile(entry: Entry) {
        try {
            val file = logFile ?: return
            val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            val line = "[${dateFormat.format(Date(entry.time))}] ${entry.message}\n"
            file.appendText(line)
        } catch (_: Exception) {}
    }
}
