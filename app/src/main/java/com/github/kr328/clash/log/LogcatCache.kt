package com.github.kr328.clash.log

import com.github.kr328.clash.core.model.LogMessage
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

class LogcatCache {
    data class Snapshot(val messages: List<LogMessage>, val removed: Int, val appended: Int)

    private val queue = ConcurrentLinkedQueue<LogMessage>()
    private val removedCount = AtomicInteger(0)
    private val appendedCount = AtomicInteger(0)

    fun append(msg: LogMessage) {
        if (queue.size >= CAPACITY) {
            if (queue.poll() != null) {
                removedCount.incrementAndGet()
            }
        }
        queue.offer(msg)
        appendedCount.incrementAndGet()
    }

    fun snapshot(full: Boolean): Snapshot? {
        val removed = removedCount.get()
        val appended = appendedCount.get()

        if (!full && removed == 0 && appended == 0) {
            return null
        }

        val messages = ArrayList<LogMessage>(queue.size)
        val iterator = queue.iterator()
        while (iterator.hasNext()) {
            messages.add(iterator.next())
        }

        removedCount.addAndGet(-removed)
        appendedCount.addAndGet(-appended)

        return Snapshot(
            messages,
            removed,
            if (full) messages.size + appended else appended
        )
    }

    companion object {
        const val CAPACITY = 128
    }
}
