package com.unciv.ui.screens.multiplayerscreens

import com.badlogic.gdx.Application
import com.badlogic.gdx.Gdx
import com.unciv.logic.multiplayer.lobby.LobbySession
import com.unciv.logic.multiplayer.lobby.protocol.LobbyRoom
import com.unciv.logic.multiplayer.lobby.protocol.LobbySeat
import com.unciv.models.metadata.GameSetupInfo
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

    @Test fun configurationAcknowledgementReindexesSeatsWithoutReplacingTheForm() {
        val setup = GameSetupInfo().apply { mapParameters.seed = 424242L }
        set("setup", setup)
        set("session", LobbySession())
        set("userId", "host")
        set("seatSources", arrayListOf(0, 1, 3))
        val latest = LobbyRoom().apply {
            state = "WAITING"
            seats = ArrayList((0..2).map { LobbySeat().apply { seatId = it } })
        }

        MultiplayerLobbyScreen::class.java.getDeclaredMethod("accept", LobbyRoom::class.java, Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(screen, latest, true)

        assertEquals(listOf(0, 1, 2), MultiplayerLobbyScreen::class.java.getDeclaredField("seatSources")
            .apply { isAccessible = true }.get(screen))
        assertSame(setup, screen.gameSetupInfo)
        assertEquals(424242L, screen.gameSetupInfo.mapParameters.seed)
    }

    @Test fun newDeadlineCancelsThePreviousScheduledCallback() {
        val callbacks = ArrayDeque<Runnable>()
        Mockito.doAnswer { callbacks.add(it.getArgument(0)); null }
            .`when`(Gdx.app).postRunnable(Mockito.any(Runnable::class.java))
        var sent = 0
        requests.add { sent++ }
        set("configurationPending", true)
        // Keep callbacks queued so machine load cannot consume a narrow timing margin.
        set("configurationDue", System.currentTimeMillis() + 60_000L)
        invoke("scheduleConfigurationSync")
        val jobField = MultiplayerLobbyScreen::class.java.getDeclaredField("configurationJob")
            .apply { isAccessible = true }
        val previous = jobField.get(screen) as Job
        set("configurationDue", System.currentTimeMillis() + 90_000L)
        invoke("scheduleConfigurationSync")
        val replacement = jobField.get(screen) as Job
        assertNotSame(previous, replacement)
        assertTrue(previous.isCancelled)
        assertTrue(replacement.isActive)
        callbacks.removeFirst().run()
        assertEquals(0, sent)

        mark(immediate = true)
        while (callbacks.isNotEmpty()) callbacks.removeFirst().run()
        assertTrue(replacement.isCancelled)
        assertEquals(1, sent)
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
