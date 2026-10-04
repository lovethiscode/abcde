package com.unciv.app.server

import kotlinx.coroutines.channels.Channel
import java.util.concurrent.atomic.AtomicLong

/** Includes the frame currently being sent, so a stalled writer cannot grow the queue. */
internal class LobbyOutbox {
    val messages = Channel<String>(32)
    private val bytes = AtomicLong()
    fun offer(text: String): Boolean {
        val size = text.toByteArray(Charsets.UTF_8).size.toLong()
        if (bytes.addAndGet(size) > 2 * 1024 * 1024L || messages.trySend(text).isFailure) {
            bytes.addAndGet(-size)
            return false
        }
        return true
    }
    fun sent(text: String) { bytes.addAndGet(-text.toByteArray(Charsets.UTF_8).size.toLong()) }
    fun close() = messages.close()
}
