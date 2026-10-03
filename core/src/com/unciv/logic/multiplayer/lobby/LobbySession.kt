package com.unciv.logic.multiplayer.lobby

import com.unciv.UncivGame
import com.unciv.logic.files.SaveFileWriter
import com.unciv.logic.multiplayer.lobby.protocol.LobbyProtocol

class LobbySession {
    var server = ""
    var gameId = ""
    var occupancy = ""
    var createKey = ""
    var setup = ""
    companion object {
        private fun file() = UncivGame.Current.files.getLocalFile("multiplayer-lobby.json")
        fun current(): LobbySession? {
            val f = file(); SaveFileWriter.recover(f)
            return if (f.exists()) LobbyProtocol.json().fromJson(LobbySession::class.java, f) else null
        }
        fun clear() { file().delete() }
    }
    fun save() = SaveFileWriter.write(file()) { it.writeString(LobbyProtocol.json().toJson(this),false,"UTF-8") }
}
