package com.unciv.ui.screens.worldscreen

import com.unciv.logic.GameInfo
import com.unciv.logic.campaign.CampaignState
import com.unciv.logic.endless.EndlessState
import com.unciv.models.metadata.GameSettings
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import com.unciv.ui.screens.worldscreen.unit.AutoPlay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*

@RunWith(BaseTestRunner::class)
class AutoPlayModeTest {
    private fun checkJob(gameInfo: GameInfo, allowed: Boolean, endTurn: Boolean) {
        val autoPlay = AutoPlay(GameSettings.GameSettingsAutoPlay())
        autoPlay.turnsToAutoPlay = 10
        val screen = mock(WorldScreen::class.java)
        `when`(screen.gameInfo).thenReturn(gameInfo)
        `when`(screen.isPlayersTurn).thenReturn(true)

        autoPlay.runAutoPlayJobInNewThread("autoplay-mode-test", screen, !endTurn) {
            gameInfo.turns++
        }
        runBlocking { withTimeout(10_000) { autoPlay.autoPlayJob?.join() } }

        assertEquals(if (allowed) 1 else 0, gameInfo.turns)
        assertFalse(autoPlay.autoPlayTurnInProgress)
        if (allowed) {
            assertNotNull(autoPlay.autoPlayJob)
            assertFalse(autoPlay.autoPlayJob!!.isCancelled)
        } else {
            assertNull(autoPlay.autoPlayJob)
            assertFalse(autoPlay.shouldContinueAutoPlaying())
        }
    }

    @Test fun campaignRejectsQueuedAndSingleTurnJobs() {
        for (endTurn in listOf(false, true))
            checkJob(GameInfo().apply { campaign = CampaignState() }, false, endTurn)
    }

    @Test fun endlessRejectsQueuedAndSingleTurnJobs() {
        for (endTurn in listOf(false, true))
            checkJob(GameInfo().apply { endless = EndlessState() }, false, endTurn)
    }

    @Test fun ordinarySinglePlayerStillRunsJobs() {
        for (endTurn in listOf(false, true)) checkJob(GameInfo(), true, endTurn)
    }

    @Test fun ordinaryMultiplayerStillRunsJobs() {
        for (endTurn in listOf(false, true))
            checkJob(GameInfo().apply { gameParameters.isOnlineMultiplayer = true }, true, endTurn)
    }

    @Test fun enteringChallengeStopsPreviouslyQueuedAutoPlay() {
        TestGame()
        val settings = GameSettings.GameSettingsAutoPlay().apply { autoPlayMaxTurns = 10 }
        for (gameInfo in listOf(
            GameInfo().apply { campaign = CampaignState() },
            GameInfo().apply { endless = EndlessState() }
        )) {
            val autoPlay = AutoPlay(settings)
            autoPlay.startMultiturnAutoPlay(GameInfo())
            assertTrue(autoPlay.shouldContinueAutoPlaying())

            autoPlay.startMultiturnAutoPlay(gameInfo)

            assertFalse(autoPlay.isAutoPlaying())
            assertEquals(10, settings.autoPlayMaxTurns)
        }
    }
}
