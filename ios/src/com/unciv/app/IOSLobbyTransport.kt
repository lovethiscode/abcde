package com.unciv.app

import com.unciv.logic.multiplayer.lobby.LobbyConnection
import com.unciv.logic.multiplayer.lobby.LobbyTransport
import com.unciv.logic.multiplayer.lobby.protocol.LobbyProtocol
import com.unciv.logic.multiplayer.storage.MultiplayerServer
import com.unciv.utils.PlatformCapabilities
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicBoolean

/** Reuses native socket IO only; no chat protocol, credentials or chat keepalive. */
class IOSLobbyTransport internal constructor(private val openSocket: (String, String, String) -> IOSChatSocket) : LobbyTransport {
    constructor() : this(::NativeIOSChatSocket)

    override suspend fun connect(url: String): LobbyConnection {
        require(url.startsWith("wss://"))
        MultiplayerServer.validateServerUrl("https://" + url.removePrefix("wss://"),
            PlatformCapabilities(multiplayerServerRequiresHttps = true))
        val socket = openSocket(url, "", "UncivLobby/1")
        socket.limitMessageSize(LobbyProtocol.MAX_MESSAGE_BYTES.toLong())
        val connection = Connection(socket)
        try { connection.open(); return connection }
        catch (e: Exception) { connection.close(); throw e }
    }

    private class Connection(private val socket: IOSChatSocket) : LobbyConnection {
        private val closed = AtomicBoolean()
        override val isActive get() = !closed.get()
        suspend fun open() = await<Unit>(socket::open)
        override suspend fun send(text: String) {
            require(text.toByteArray(Charsets.UTF_8).size <= LobbyProtocol.MAX_MESSAGE_BYTES)
            await<Unit> { socket.send(text, it) }
        }
        override suspend fun receive(): String = await<String>(socket::receive).also {
            require(it.toByteArray(Charsets.UTF_8).size <= LobbyProtocol.MAX_MESSAGE_BYTES)
        }
        private suspend fun <T> await(action: ((Result<T>) -> Unit) -> Unit): T = suspendCancellableCoroutine { continuation ->
            val completed = AtomicBoolean()
            continuation.invokeOnCancellation { closeSocket() }
            if (continuation.isActive && isActive) action { result ->
                if (completed.compareAndSet(false,true) && continuation.isActive) continuation.resumeWith(result)
            }
            else continuation.cancel()
        }
        override suspend fun close() = closeSocket()
        private fun closeSocket() { if (closed.compareAndSet(false, true)) socket.close() }
    }
}
