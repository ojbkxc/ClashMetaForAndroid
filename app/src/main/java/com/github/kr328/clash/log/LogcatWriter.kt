package com.github.kr328.clash.log

import android.content.Context
import android.util.Log
import com.github.kr328.clash.core.model.LogMessage
import com.github.kr328.clash.design.model.LogFile
import com.github.kr328.clash.util.logsDir
import java.io.BufferedWriter
import java.io.FileWriter

class LogcatWriter(context: Context) : AutoCloseable {
    private val file = LogFile.generate()
    private val writer = BufferedWriter(FileWriter(context.logsDir.resolve(file.fileName)))

    override fun close() {
        try {
            writer.flush()
        } catch (e: Exception) {
            Log.e("LogcatWriter", "Failed to flush writer", e)
        } finally {
            try {
                writer.close()
            } catch (e: Exception) {
                Log.e("LogcatWriter", "Failed to close writer", e)
            }
        }
    }

    /**
     * Append a log message to file
     */
    fun appendMessage(message: LogMessage) {
        writer.appendLine(FORMAT.format(message.time.time, message.level.name, message.message))
    }

    /**
     * Flush the writer to ensure data is written to disk
     */
    fun flush() {
        writer.flush()
    }

    companion object {
        private const val FORMAT = "%d:%s:%s"
    }
}