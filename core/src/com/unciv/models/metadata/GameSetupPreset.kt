package com.unciv.models.metadata

import com.unciv.Constants
import com.unciv.logic.civilization.PlayerType
import com.unciv.logic.map.MapSize
import com.unciv.logic.map.MapType
import com.unciv.models.ruleset.Ruleset

enum class GameSetupPreset(val caption: String, val speed: String, val difficulty: String, val size: String) {
    Quick("Quick skirmish", "Quick", "Chieftain", "Small"),
    Standard("Standard journey", "Standard", "Prince", "Medium");

    fun isAvailable(ruleset: Ruleset) = speed in ruleset.speeds && difficulty in ruleset.difficulties

    /** Presets change only the advertised map, pace and roster; unrelated custom rules are retained. */
    fun prepare(source: GameSetupInfo, ruleset: Ruleset): GameSetupInfo {
        require(isAvailable(ruleset))
        return NewGameMode.SinglePlayer.prepare(source).apply {
            mapFile = null
            mapParameters.name = ""
            mapParameters.type = MapType.pangaea
            mapParameters.mapSize = MapSize(size)
            gameParameters.speed = speed
            gameParameters.difficulty = difficulty
            gameParameters.randomNumberOfPlayers = false
            val self = gameParameters.players.first().apply {
                if (chosenCiv == Constants.spectator) chosenCiv = Constants.random
            }
            gameParameters.players = arrayListOf(self).apply { repeat(3) { add(Player(playerType = PlayerType.AI)) } }
        }
    }
}
