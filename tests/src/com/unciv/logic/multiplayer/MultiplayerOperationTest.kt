package com.unciv.logic.multiplayer

import com.badlogic.gdx.Application
import com.badlogic.gdx.Gdx
import com.unciv.logic.event.EventBus.EventReceiver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.mockito.Mockito.*
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

class MultiplayerOperationTest {
    private val receiver = EventReceiver()
    private val results = CopyOnWriteArrayList<MultiplayerOperationCompleted>()
    private var previousApp: Application? = null
    @Before fun setup() {
        previousApp = Gdx.app
        val app = mock(Application::class.java)
        doAnswer { it.getArgument<Runnable>(0).run(); null }.`when`(app).postRunnable(any(Runnable::class.java))
        Gdx.app = app
        receiver.receive(MultiplayerOperationCompleted::class) { results.add(it) }
    }
    @After fun cleanup() { receiver.stopReceiving(); Gdx.app = previousApp }

    @Test fun successIsReportedAfterCompletionAndReturnsOriginalResult() = runBlocking {
        val result = observeMultiplayerOperation(MultiplayerOperation.Join) {
            assertTrue(results.isEmpty())
            "persisted-preview"
        }
        assertEquals("persisted-preview", result)
        assertEquals(1, results.size)
        assertEquals(MultiplayerOperation.Join, results.single().operation)
        assertTrue(results.single().succeeded)
    }

    @Test fun failureIsReportedOnceAndRethrowsOriginalException() = runBlocking {
        val failure = IOException("fixture failure")
        try {
            observeMultiplayerOperation(MultiplayerOperation.Create) { throw failure }
            fail("Expected error")
        } catch (thrown: IOException) { assertSame(failure, thrown) }
        assertEquals(1, results.size)
        assertFalse(results.single().succeeded)
    }

    @Test fun lifecycleCancellationNeverCountsAsFailure() = runBlocking {
        val cancellation = CancellationException("paused")
        try {
            observeMultiplayerOperation(MultiplayerOperation.Join) { throw cancellation }
            fail("Expected cancellation")
        } catch (thrown: CancellationException) { assertSame(cancellation, thrown) }
        assertTrue(results.isEmpty())
    }
}
