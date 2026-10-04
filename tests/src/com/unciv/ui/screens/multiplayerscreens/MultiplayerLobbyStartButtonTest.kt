package com.unciv.ui.screens.multiplayerscreens

import org.junit.Assert.assertEquals
import org.junit.Test

class MultiplayerLobbyStartButtonTest {
    @Test
    fun successfulStartKeepsProgressTextThroughUploadAndGameEntry() {
        assertEquals("Start game!", lobbyStartButtonText("WAITING", false))
        assertEquals("Start game!", lobbyStartButtonText("WAITING", true))
        assertEquals("Starting game...", lobbyStartButtonText("STARTING", true))
        assertEquals("Starting game...", lobbyStartButtonText("STARTED", false))
        assertEquals("Starting game...", lobbyStartButtonText("STARTED", true))
    }

    @Test
    fun interruptedStartOffersRetryUntilTheNewAttemptIsRunning() {
        assertEquals("Retry start", lobbyStartButtonText("STARTING", false))
        assertEquals("Starting game...", lobbyStartButtonText("STARTING", true))
        assertEquals("Retry start", lobbyStartButtonText("STARTING", false))
        assertEquals("Start game!", lobbyStartButtonText("WAITING", false))
    }
}
