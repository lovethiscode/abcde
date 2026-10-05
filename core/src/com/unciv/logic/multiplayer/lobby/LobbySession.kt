package com.unciv.logic.multiplayer.lobby

import com.unciv.UncivGame
import com.unciv.logic.files.SaveFileWriter
import com.unciv.logic.multiplayer.lobby.protocol.LobbyProtocol
import com.unciv.logic.UncivShowableException
import com.unciv.logic.multiplayer.MultiplayerOperation
import com.unciv.logic.multiplayer.reportMultiplayerOperation

class LobbySession {
    var sessionId = java.util.UUID.randomUUID().toString()
    var playerId = ""
    var roomApiUrl = ""
    var gameServerUrl = ""
    var gameId = ""
    var occupancy = ""
    var createKey = ""
    var setup = ""
    var pendingAction = ""
    var pendingRequestId = ""
    var pendingCommand = ""
    companion object {
        private val lock = Any()
        private fun file() = UncivGame.Current.files.getLocalFile("multiplayer-lobby.json")
        fun current(): LobbySession? = synchronized(lock) {
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
        fun clear() = synchronized(lock) { file().delete(); Unit }
    }
    fun save() = synchronized(lock) { SaveFileWriter.write(file()) { it.writeString(LobbyProtocol.json().toJson(this),false,"UTF-8") } }
    fun saveIfCurrent() = synchronized(lock) {
        if (current()?.sessionId == sessionId) save()
    }
    fun clearIfCurrent() = synchronized(lock) { if (current()?.sessionId == sessionId) clear() }
    fun pending(action: String, command: com.unciv.logic.multiplayer.lobby.protocol.LobbyCommand, requestId: String) {
        pendingAction = action; pendingRequestId = requestId
        pendingCommand = LobbyProtocol.json().toJson(command)
        saveIfCurrent()
    }
    fun confirmed(room: com.unciv.logic.multiplayer.lobby.protocol.LobbyRoom) = synchronized(lock) {
        val operation = pendingAction
        gameId = room.gameId; setup = room.setup
        occupancy = room.seats.firstOrNull { it.playerId == playerId }?.occupancy.orEmpty()
        if (pendingAction != "leave" || occupancy.isEmpty() || room.state == "CLOSED") {
            pendingAction = ""; pendingRequestId = ""; pendingCommand = ""
        }
        if (room.state == "CLOSED" || occupancy.isEmpty()) clearIfCurrent() else saveIfCurrent()
        if (occupancy.isNotEmpty() && room.state != "CLOSED") reportMembership(operation, true)
    }
    fun rejectPending(requestId: String) = synchronized(lock) {
        if (pendingRequestId != requestId) return@synchronized
        reportMembership(pendingAction, false)
        pendingAction = ""; pendingRequestId = ""; pendingCommand = ""; saveIfCurrent()
    }
    private fun reportMembership(action: String, succeeded: Boolean) {
        val operation = when (action) {
            "create" -> MultiplayerOperation.RoomCreate
            "join" -> MultiplayerOperation.RoomJoin
            else -> return
        }
        reportMultiplayerOperation(operation, succeeded, playerId)
    }
}
