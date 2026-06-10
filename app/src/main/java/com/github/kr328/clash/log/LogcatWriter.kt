package com.github.kr328.clash.log

import android.content.Context
import android.util.Log
import com.github.kr328.clash.core.model.LogMessage
import com.github.kr328.clash.design.model.LogFile
import com.github.kr328.clash.util.logsDir
import java.io.BufferedWriter
import java.io.FileWriter
import java.util.concurrent.ConcurrentLinkedQueue

class LogcatWriter(context: Context) : AutoCloseable {
    private val file = LogFile.generate()
    private val writer = BufferedWriter(FileWriter(context.logsDir.resolve(file.fileName)))
    private val buffer = ConcurrentLinkedQueue<String>()
    private var lastFlushTime = System.currentTimeMillis()

    override fun close() {
        try {
            flushBuffer(true)
        } catch (e: Exception) {
            Log.e("LogcatWriter", "Failed to flush buffer", e)
        } finally {
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
    }

    /**
     * Append a log message to buffer
     */
    fun appendMessage(message: LogMessage) {
        buffer.offer(FORMAT.format(message.time.time, message.level.name, message.message))
        
        val now = System.currentTimeMillis()
        if (buffer.size >= FLUSH_THRESHOLD || now - lastFlushTime >= FLUSH_INTERVAL_MS) {
            flushBuffer(false)
        }
    }

    /**
     * Flush the writer to ensure data is written to disk
     */
    fun flush() {
        flushBuffer(true)
    }

    private fun flushBuffer(force: Boolean) {
        if (!force && buffer.isEmpty()) {
            return
        }

        try {
            val iterator = buffer.iterator()
            while (iterator.hasNext()) {
                writer.write(iterator.next())
                writer.newLine()
                iterator.remove()
            }
            writer.flush()
            lastFlushTime = System.currentTimeMillis()
        } catch (e: Exception) {
            Log.e("LogcatWriter", "Failed to flush buffer", e)
        }
    }

    companion object {
        private const val FORMAT = "%d:%s:%s"
        private const val FLUSH_THRESHOLD = 32
        private const val FLUSH_INTERVAL_MS = 5000L
    }
}