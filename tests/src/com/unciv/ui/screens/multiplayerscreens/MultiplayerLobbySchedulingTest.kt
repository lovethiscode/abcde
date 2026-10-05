package com.unciv.ui.screens.multiplayerscreens

import com.badlogic.gdx.Application
import com.badlogic.gdx.Gdx
import kotlinx.coroutines.Job
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.mockito.Mockito
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class MultiplayerLobbySchedulingTest {
    private lateinit var screen: MultiplayerLobbyScreen
    private lateinit var requests: ArrayDeque<() -> Unit>
    private var previousApp: Application? = null

    @Before fun setup() {
        previousApp = Gdx.app
        Gdx.app = Mockito.mock(Application::class.java).also { app ->
            // Deliver GL callbacks, but never render a frame: reproduce an idle iOS screen.
            Mockito.doAnswer { it.getArgument<Runnable>(0).run(); null }
                .`when`(app).postRunnable(Mockito.any(Runnable::class.java))
        }
        // Exercise the screen's scheduling without constructing its graphics/widgets.
        screen = Mockito.mock(MultiplayerLobbyScreen::class.java, Mockito.CALLS_REAL_METHODS)
        requests = ArrayDeque()
        set("pendingRequests", requests)
        set("connected", true)
        set("renderedState", "true|false|true|null|null|null|null")
    }

    @After fun cleanup() {
        screen.javaClass.getDeclaredField("configurationJob").apply { isAccessible = true }
            .get(screen).let { (it as? Job)?.cancel() }
        Gdx.app = previousApp
    }

    @Test fun delayedWorkRunsWithoutAnotherRenderFrame() {
        val sent = CountDownLatch(1)
        requests.add { sent.countDown() }
        mark()
        assertTrue("Pending work must run even when no frame is rendered", sent.await(2, TimeUnit.SECONDS))
    }

    @Test fun discreteChangeDispatchesBeforeReturning() {
        val sent = CountDownLatch(1)
        requests.add { sent.countDown() }
        mark(immediate = true)
        assertEquals(0L, sent.count)
    }

    @Test fun continuousChangesRestartTheDelay() {
        val sent = CountDownLatch(1)
        requests.add { sent.countDown() }
        mark()
        Thread.sleep(200)
        mark()
        assertFalse(sent.await(500, TimeUnit.MILLISECONDS))
        assertTrue(sent.await(2, TimeUnit.SECONDS))
    }

    @Test fun disconnectCancelsTheScheduledCallback() {
        val sent = CountDownLatch(1)
        requests.add { sent.countDown() }
        mark()
        invoke("cancelConfigurationSync")
        assertFalse(sent.await(800, TimeUnit.MILLISECONDS))
    }

    @Test fun busyRequestDefersWorkUntilCompletionPumpsTheQueue() {
        val sent = CountDownLatch(1)
        requests.add { sent.countDown() }
        set("busy", true)
        set("renderedState", "true|true|true|null|null|null|null")
        mark(immediate = true)
        assertEquals(1L, sent.count)
        set("busy", false)
        invoke("processPendingRequests")
        assertEquals(0L, sent.count)
    }

    @Test fun reconnectDispatchesWorkThatWasPendingWhileOffline() {
        val sent = CountDownLatch(1)
        requests.add { sent.countDown() }
        set("connected", false)
        set("renderedState", "false|false|true|null|null|null|null")
        mark(immediate = true)
        assertEquals(1L, sent.count)
        set("connected", true)
        invoke("processPendingRequests")
        assertEquals(0L, sent.count)
    }

    private fun set(name: String, value: Any) {
        MultiplayerLobbyScreen::class.java.getDeclaredField(name).apply { isAccessible = true }.set(screen, value)
    }

    private fun mark(immediate: Boolean = false) {
        MultiplayerLobbyScreen::class.java.getDeclaredMethod("markConfigurationChanged", Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(screen, immediate)
    }

    private fun invoke(name: String) {
        MultiplayerLobbyScreen::class.java.getDeclaredMethod(name).apply { isAccessible = true }.invoke(screen)
    }
}
