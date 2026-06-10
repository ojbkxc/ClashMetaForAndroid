package com.github.kr328.clash.log

import android.content.Context
import com.github.kr328.clash.core.model.LogMessage
import com.github.kr328.clash.design.model.LogFile
import com.github.kr328.clash.util.logsDir
import java.io.BufferedReader
import java.io.FileReader
import java.util.*

class LogcatReader(context: Context, file: LogFile) : AutoCloseable {
    private val reader = BufferedReader(FileReader(context.logsDir.resolve(file.fileName)))

    override fun close() {
        reader.close()
    }

    /**
     * Read all log messages from file with proper resource management
     */
    fun readAll(): List<LogMessage> = reader.use { r ->
        var lastTime = Date(0)
        r.lineSequence()
            .map { it.trim() }
            .filter { !it.startsWith("#") }
            .map { it.split(":", limit = 3) }
            .map { parts ->
                val time = parts[0].toLongOrNull()?.let { Date(it) } ?: lastTime
                val logMessage = if (parts[0].toLongOrNull() != null && parts.size >= 3) {
                    try {
                        LogMessage(
                            time = time,
                            level = LogMessage.Level.valueOf(parts[1]),
                            message = parts[2]
                        )
                    } catch (e: IllegalArgumentException) {
                        LogMessage(
                            time = time,
                            level = LogMessage.Level.Warning,
                            message = parts.joinToString(":")
                        )
                    }
                } else {
                    LogMessage(
                        time = time,
                        level = LogMessage.Level.Warning,
                        message = parts.joinToString(":")
                    )
                }
                lastTime = time
                logMessage
            }
            .toList()
    }
}