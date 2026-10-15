package com.unciv.ui.screens.multiplayerscreens

import com.unciv.logic.GameInfoPreview
import com.unciv.logic.civilization.CivilizationInfoPreview
import org.junit.Assert.*
import org.junit.Test
import org.threeten.bp.Instant

class MultiplayerScreenLayoutTest {
    @Test fun approvedPhoneCompositionUsesItsSafeAreaWithoutAddingAnotherNotchInset() {
        val layout = MultiplayerScreenLayout(838f, 419f, true)
        assertTrue(layout.twoColumns)
        assertEquals(1f, layout.horizontalPadding, .001f)
        assertEquals(320f, layout.gamesWidth, .001f)
        assertEquals(500f, layout.detailsWidth, .001f)
        assertEquals(836f, layout.gamesWidth + layout.columnGap + layout.detailsWidth, .001f)
    }

    @Test fun narrowPortraitStacksPanelsWhileTabletPreservesBothColumns() {
        val portrait = MultiplayerScreenLayout(440f, 850f, false)
        assertFalse(portrait.twoColumns)
        assertEquals(408f, portrait.gamesWidth, .001f)
        val tablet = MultiplayerScreenLayout(1194f, 810f, false)
        assertTrue(tablet.twoColumns)
        assertEquals(tablet.contentWidth, tablet.gamesWidth + tablet.columnGap + tablet.detailsWidth, .001f)
    }

    @Test fun filtersUseTurnOwnershipEvenWhileRefreshingOrShowingCachedFailure() {
        for (refreshing in listOf(false, true)) for (failed in listOf(false, true)) {
            val own = model(true, failed, refreshing)
            val waiting = model(false, failed, refreshing)
            assertTrue(MultiplayerGameFilter.All.matches(own))
            assertTrue(MultiplayerGameFilter.YourTurn.matches(own))
            assertFalse(MultiplayerGameFilter.Waiting.matches(own))
            assertTrue(MultiplayerGameFilter.Waiting.matches(waiting))
            assertFalse(MultiplayerGameFilter.YourTurn.matches(waiting))
        }
    }

    @Test fun unavailableRecordsRemainReachableUnderAllRatherThanMasqueradingAsWaiting() {
        val missing = buildMultiplayerGameUiModel("Broken preview", null, true, Instant.now(), "me")
        val malformed = buildMultiplayerGameUiModel("Malformed", GameInfoPreview(), false, Instant.now(), "me")
        for (model in listOf(missing, malformed)) {
            assertTrue(MultiplayerGameFilter.All.matches(model))
            assertFalse(MultiplayerGameFilter.Waiting.matches(model))
            assertFalse(MultiplayerGameFilter.YourTurn.matches(model))
        }
    }

    private fun model(ours: Boolean, failed: Boolean, refreshing: Boolean): MultiplayerGameUiModel {
        val preview = GameInfoPreview().apply {
            currentPlayer = if (ours) "own-id" else "opponent-id"
            civilizations = mutableListOf(
                CivilizationInfoPreview().apply { civID = "own-id"; civName = "Rome"; playerId = "me" },
                CivilizationInfoPreview().apply { civID = "opponent-id"; civName = "Greece"; playerId = "opponent" },
            )
        }
        return buildMultiplayerGameUiModel("Game", preview, failed, Instant.now(), "me", refreshing)
    }
}
