package com.unciv.models.metadata

import com.unciv.Constants
import com.unciv.logic.civilization.PlayerType
import com.unciv.logic.map.MapSize
import com.unciv.logic.map.MapType
import com.unciv.models.ruleset.Ruleset
import com.unciv.models.ruleset.Speed
import com.unciv.models.ruleset.nation.Difficulty
import org.junit.Assert.*
import org.junit.Test

class GameSetupPresetTest {
    private fun ruleset() = Ruleset().apply {
        for (preset in GameSetupPreset.entries) {
            speeds[preset.speed] = Speed().apply { name = preset.speed }
            difficulties[preset.difficulty] = Difficulty().apply { name = preset.difficulty }
        }
    }

    @Test fun presetDetachesSavedRosterAndKeepsUnrelatedRules() {
        val saved = GameSetupInfo().apply {
            gameParameters.players = arrayListOf(Player("Rome", PlayerType.Human, "saved-id"), Player("Egypt"))
            gameParameters.mods.add("Custom terrain")
            gameParameters.noBarbarians = true
            gameParameters.victoryTypes.add("Cultural")
            gameParameters.randomNumberOfPlayers = true
            mapParameters.name = "Saved map"
            mapParameters.type = MapType.archipelago
            mapParameters.mapSize = MapSize.Large
        }
        val prepared = GameSetupPreset.Quick.prepare(saved, ruleset())
        assertEquals("Rome", prepared.gameParameters.players.first().chosenCiv)
        assertEquals(3, prepared.gameParameters.players.count { it.playerType == PlayerType.AI })
        assertTrue(prepared.gameParameters.players.all { it.playerId.isEmpty() })
        assertTrue(prepared.gameParameters.noBarbarians)
        assertEquals(saved.gameParameters.mods, prepared.gameParameters.mods)
        assertEquals(saved.gameParameters.victoryTypes, prepared.gameParameters.victoryTypes)
        assertFalse(prepared.gameParameters.randomNumberOfPlayers)
        assertEquals(MapType.pangaea, prepared.mapParameters.type)
        assertEquals("Small", prepared.mapParameters.mapSize.name)
        assertEquals("", prepared.mapParameters.name)
        prepared.gameParameters.players.first().chosenCiv = "Greece"
        assertEquals("Rome", saved.gameParameters.players.first().chosenCiv)
        assertEquals("saved-id", saved.gameParameters.players.first().playerId)
        assertEquals(2, saved.gameParameters.players.size)
        assertEquals("Saved map", saved.mapParameters.name)
        assertEquals("Large", saved.mapParameters.mapSize.name)
        assertTrue(saved.gameParameters.randomNumberOfPlayers)
    }

    @Test fun unsupportedRulesetCannotSilentlyReceiveInvalidSpeed() {
        val ruleset = ruleset().apply { speeds.remove("Quick") }
        assertFalse(GameSetupPreset.Quick.isAvailable(ruleset))
        assertTrue(GameSetupPreset.Standard.isAvailable(ruleset))
        assertThrows(IllegalArgumentException::class.java) { GameSetupPreset.Quick.prepare(GameSetupInfo(), ruleset) }
    }

    @Test fun spectatorPresetRestoresAPlayableHumanSeat() {
        val source = GameSetupInfo().apply { gameParameters.players.first().chosenCiv = Constants.spectator }
        val prepared = GameSetupPreset.Standard.prepare(source, ruleset())
        assertEquals(Constants.random, prepared.gameParameters.players.first().chosenCiv)
        assertEquals(PlayerType.Human, prepared.gameParameters.players.first().playerType)
        assertEquals(Constants.spectator, source.gameParameters.players.first().chosenCiv)
        assertEquals("Standard", prepared.gameParameters.speed)
        assertEquals("Medium", prepared.mapParameters.mapSize.name)
    }
}
