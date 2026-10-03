package com.unciv.logic.multiplayer.lobby

import com.unciv.UncivGame
import com.unciv.logic.files.SaveFileWriter
import com.unciv.logic.multiplayer.lobby.protocol.LobbyProtocol
import com.unciv.logic.UncivShowableException

class LobbySession {
    var roomApiUrl = ""
    var gameServerUrl = ""
    var gameId = ""
    var occupancy = ""
    var createKey = ""
    var setup = ""
    companion object {
        private fun file() = UncivGame.Current.files.getLocalFile("multiplayer-lobby.json")
        fun current(): LobbySession? {
            val f = file(); SaveFileWriter.recover(f)
            if (!f.exists()) return null
            val session = LobbyProtocol.json().fromJson(LobbySession::class.java, f)
            if (session.roomApiUrl.isEmpty() || session.gameServerUrl.isEmpty()) {
                f.moveTo(f.sibling("multiplayer-lobby.single-server-${System.currentTimeMillis()}.json"))
                return null
            }
            return session
        }
        fun roomApiUrl() = UncivGame.Current.multiplayerLobbyServerUrl
            ?.takeIf { it.isNotBlank() }
            ?: throw UncivShowableException("The room API is not configured in this build.")
        fun clear() { file().delete() }
    }
    fun save() = SaveFileWriter.write(file()) { it.writeString(LobbyProtocol.json().toJson(this),false,"UTF-8") }
}
