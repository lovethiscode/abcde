package com.unciv.models.metadata

import com.unciv.logic.civilization.PlayerType
import org.junit.Assert.*
import org.junit.Test

class NewGameModeTest {
    @Test fun singlePlayerEntryDetachesSavedPlayersAndRemovesOnlineState() {
        val saved = GameSetupInfo().apply {
            gameParameters.isOnlineMultiplayer = true
            gameParameters.multiplayerServerUrl = "https://room.example"
            gameParameters.players = arrayListOf(Player("Rome",PlayerType.AI),Player("Greece",PlayerType.Human,"self"),Player("Egypt",PlayerType.Human,"guest"))
        }
        val solo = NewGameMode.SinglePlayer.prepare(saved)
        assertFalse(solo.gameParameters.isOnlineMultiplayer)
        assertNull(solo.gameParameters.multiplayerServerUrl)
        assertEquals(listOf(PlayerType.Human,PlayerType.AI,PlayerType.AI),solo.gameParameters.players.map { it.playerType })
        assertEquals("Greece",solo.gameParameters.players.first().chosenCiv)
        assertTrue(solo.gameParameters.players.all { it.playerId.isEmpty() })
        assertTrue(saved.gameParameters.isOnlineMultiplayer)
        assertEquals("guest",saved.gameParameters.players.last().playerId)
        assertEquals(PlayerType.Human,saved.gameParameters.players.last().playerType)
    }
    @Test fun roomEntryAlwaysProvidesAnInvitationSeatAndFixedSelf() {
        val room = NewGameMode.MultiplayerRoom.prepare(GameSetupInfo(),"host")
        assertTrue(room.gameParameters.isOnlineMultiplayer)
        assertEquals("host",room.gameParameters.players.first().playerId)
        assertEquals(PlayerType.Human,room.gameParameters.players[1].playerType)
        assertEquals("Random",room.gameParameters.players[1].chosenCiv)
        assertTrue(room.gameParameters.players.drop(1).all { it.playerId.isEmpty() })
        assertFalse(room.gameParameters.randomNumberOfPlayers)
    }
    @Test fun emptySavedRosterStillRestoresSelfForBothEntries() {
        val source = GameSetupInfo().apply { gameParameters.players.clear() }
        assertEquals(PlayerType.Human,NewGameMode.SinglePlayer.prepare(source).gameParameters.players.first().playerType)
        assertEquals(2,NewGameMode.MultiplayerRoom.prepare(source,"host").gameParameters.players.size)
        assertTrue(source.gameParameters.players.isEmpty())
    }
}
