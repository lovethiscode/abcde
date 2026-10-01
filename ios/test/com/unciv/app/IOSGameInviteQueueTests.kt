package com.unciv.app

import com.unciv.logic.multiplayer.MultiplayerGameInvite
import org.junit.Assert.*
import org.junit.Test

class IOSGameInviteQueueTests {
    private val first = MultiplayerGameInvite("first", "https://a.example")
    private val second = MultiplayerGameInvite("second", "https://b.example")

    @Test fun coldLaunchRetainsInvitationUntilConsumerIsReady() {
        val queue = IOSGameInviteQueue()
        queue.offer(first)
        assertEquals(first, queue.take())
        assertNull(queue.take())
    }

    @Test fun duplicateSceneDeliveryDoesNotOpenTwice() {
        val queue = IOSGameInviteQueue()
        queue.offer(first)
        queue.offer(first)
        assertEquals(first, queue.take())
        queue.offer(first)
        queue.finish()
        assertNull(queue.take())
    }

    @Test fun newInvitationWaitsUntilCurrentLoadFinishes() {
        val queue = IOSGameInviteQueue()
        queue.offer(first)
        assertEquals(first, queue.take())
        queue.offer(second)
        assertNull(queue.take())
        queue.finish()
        assertEquals(second, queue.take())
    }

    @Test fun latestPendingInvitationWinsBeforeStartupCompletes() {
        val queue = IOSGameInviteQueue()
        queue.offer(first)
        queue.offer(second)
        assertEquals(second, queue.take())
    }

    @Test fun backgroundCancellationRetainsExactPinnedInvitationForResume() {
        val queue = IOSGameInviteQueue()
        queue.offer(first)
        queue.take()
        queue.finish(first)
        assertEquals(first, queue.take())
    }

    @Test fun retryDoesNotReplaceANewerPendingInvitation() {
        val queue = IOSGameInviteQueue()
        queue.offer(first)
        queue.take()
        queue.offer(second)
        queue.finish(first)
        assertEquals(second, queue.take())
    }

    @Test fun authenticationCancelledDoesNotRetryOldInvitation() {
        val queue = IOSGameInviteQueue()
        queue.offer(first)
        queue.take()
        queue.finish()
        assertNull(queue.take())
        queue.offer(first)
        assertEquals(first, queue.take()) // An explicit later tap remains allowed.
    }
}
