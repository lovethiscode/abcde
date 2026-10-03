package com.unciv.models.metadata

import com.unciv.Constants
import com.unciv.logic.civilization.PlayerType

enum class NewGameMode {
    SinglePlayer, MultiplayerRoom;

    /** Detach player objects before normalizing a saved setup for its entry point. */
    fun prepare(source: GameSetupInfo, userId: String = ""): GameSetupInfo = GameSetupInfo(source).apply {
        mapFile = source.mapFile
        gameParameters.players = ArrayList(source.gameParameters.players.map {
            Player(it.chosenCiv, it.playerType, it.playerId)
        })
        val players = gameParameters.players
        val self = players.firstOrNull { it.playerType == PlayerType.Human }
            ?: players.firstOrNull() ?: Player()
        players.remove(self)
        players.add(0, self)
        self.playerType = PlayerType.Human
        val online = this@NewGameMode == MultiplayerRoom
        gameParameters.isOnlineMultiplayer = online
        gameParameters.multiplayerServerUrl = null
        self.playerId = if (online) userId else ""
        for (player in players.drop(1)) {
            player.playerId = ""
            if (!online) player.playerType = PlayerType.AI
            else if (player.playerType == PlayerType.Human) player.chosenCiv = Constants.random
        }
        if (online) {
            if (self.chosenCiv == Constants.spectator) self.chosenCiv = Constants.random
            gameParameters.randomNumberOfPlayers = false
            players.removeAll { it !== self && it.chosenCiv == Constants.spectator }
            if (players.size < 2) players.add(Player(playerType = PlayerType.Human))
            if (players.drop(1).none { it.playerType == PlayerType.Human })
                players[1].apply { playerType = PlayerType.Human; chosenCiv = Constants.random }
        }
    }
}
