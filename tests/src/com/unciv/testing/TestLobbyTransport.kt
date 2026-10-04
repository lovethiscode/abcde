package com.unciv.testing

import com.unciv.logic.multiplayer.lobby.*
import com.unciv.logic.multiplayer.lobby.protocol.*
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.CopyOnWriteArrayList

/** A controllable socket, only for lost/late messages and lifecycle unit tests. */
class TestLobbyTransport(val server: String, var reply: (LobbyMessage) -> LobbyRoom?) : LobbyTransport {
    val urls = CopyOnWriteArrayList<String>()
    val requests = CopyOnWriteArrayList<LobbyMessage>()
    val sockets = CopyOnWriteArrayList<Socket>()
    override suspend fun connect(url: String): LobbyConnection {
        urls += url
        return Socket().also {
            sockets += it
            it.emit(LobbyMessage().apply { type = "hello"; gameServerUrl = server; rulesHash = LobbyClient.rulesHash() })
        }
    }
    inner class Socket : LobbyConnection {
        private val incoming = Channel<String>(Channel.UNLIMITED)
        override var isActive = true
            private set
        fun emit(message: LobbyMessage) { incoming.trySend(LobbyProtocol.encode(message)).getOrThrow() }
        override suspend fun send(text: String) {
            val request = LobbyProtocol.decode(text)
            requests += request
            if (request.type != "command") return
            if (request.action == "heartbeat") emit(LobbyMessage().apply { type = "pong"; requestId = request.requestId })
            else reply(request)?.let { room -> emit(LobbyMessage().apply { type = "result"; requestId = request.requestId; this.room = room }) }
        }
        override suspend fun receive() = incoming.receive()
        override suspend fun close() { isActive = false; incoming.close() }
    }
}
