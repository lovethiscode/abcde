package com.unciv.app.server

import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assert.*

class LobbyOutboxTest {
    @Test fun stalledWriterHasAFixedMessageLimitAndOtherOutboxesRemainUsable() = runBlocking<Unit> {
        val slow = LobbyOutbox(); val healthy = LobbyOutbox()
        repeat(32) { assertTrue(slow.offer("queued")) }
        assertFalse(slow.offer("overflow"))
        assertTrue(healthy.offer("independent")); assertEquals("independent",healthy.messages.receive())
        val text = slow.messages.receive(); slow.sent(text)
        assertTrue(slow.offer("next"))
        slow.close(); healthy.close()
    }
    @Test fun byteLimitIncludesTheBlockedInFlightFrameAndCountsUtf8() = runBlocking<Unit> {
        val queue = LobbyOutbox()
        val text = "中".repeat(170_000)
        repeat(4) { assertTrue(queue.offer(text)) }
        val inFlight = queue.messages.receive()
        assertFalse(queue.offer(text))
        queue.sent(inFlight)
        assertTrue(queue.offer(text))
        queue.close(); assertFalse(queue.offer("closed"))
    }
}
