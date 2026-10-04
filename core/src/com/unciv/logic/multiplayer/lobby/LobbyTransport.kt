package com.unciv.logic.multiplayer.lobby

import com.unciv.logic.multiplayer.lobby.protocol.LobbyProtocol
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.url
import io.ktor.websocket.*
import kotlinx.coroutines.isActive

interface LobbyTransport {
    suspend fun connect(url: String): LobbyConnection
}

interface LobbyConnection {
    val isActive: Boolean
    suspend fun send(text: String)
    suspend fun receive(): String
    suspend fun close()
}

class KtorLobbyTransport : LobbyTransport {
    override suspend fun connect(url: String): LobbyConnection {
        val client = HttpClient(CIO) { install(WebSockets) { maxFrameSize = LobbyProtocol.MAX_MESSAGE_BYTES.toLong() } }
        val session = try { client.webSocketSession { url(url) } }
        catch (e: Exception) { client.close(); throw e }
        return object : LobbyConnection {
            override val isActive get() = session.isActive
            override suspend fun send(text: String) { session.send(Frame.Text(text)) }
            override suspend fun receive(): String = (session.incoming.receive() as Frame.Text).readText()
            override suspend fun close() { try { session.close() } finally { client.close() } }
        }
    }
}
