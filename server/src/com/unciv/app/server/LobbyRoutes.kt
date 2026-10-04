package com.unciv.app.server

import com.unciv.logic.multiplayer.lobby.protocol.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicInteger

/** Only WebSocket business routes. /isalive remains an operational deployment probe. */
fun Route.lobbyRoutes(service: LobbyService, hub: LobbyHub) {
    webSocketRaw("/ws") {
        val session = this
        val parent = coroutineContext[Job]!!
        val outbox = LobbyOutbox()
        val pending = AtomicInteger()
        val peer = LobbyPeer(deliver = { message ->
            outbox.offer(LobbyProtocol.encode(message))
        }, disconnect = { parent.cancel() })
        val writer = launch {
            for (text in outbox.messages) {
                session.send(Frame.Text(text))
                outbox.sent(text)
                if (LobbyProtocol.decode(text).type == "terminated") { session.close(); break }
            }
        }
        val idle = launch {
            while (isActive) {
                delay(1_000)
                val timeout = if (peer.gameId.isEmpty()) 10_000L else LobbyProtocol.CONNECTION_TIMEOUT_MILLIS
                if (System.currentTimeMillis() - peer.lastSignal >= timeout) { parent.cancel(); break }
            }
        }
        peer.send(LobbyMessage().apply {
            type = "hello"; protocolVersion = LobbyProtocol.VERSION
            gameServerUrl = service.gameServerUrl; rulesHash = service.rulesHash
        })
        val fragments = ByteArrayOutputStream()
        try {
            for (frame in incoming) {
                when (frame) {
                    is Frame.Close -> break
                    is Frame.Ping -> { session.send(Frame.Pong(frame.data)); continue }
                    is Frame.Pong -> continue
                    is Frame.Binary -> throw IllegalArgumentException("Room messages must be text")
                    is Frame.Text -> {
                        require(fragments.size() + frame.data.size <= LobbyProtocol.MAX_MESSAGE_BYTES)
                        fragments.write(frame.data)
                        if (!frame.fin) continue
                        val message = LobbyProtocol.decode(fragments.toString("UTF-8"))
                        fragments.reset()
                        if (message.type == "checkReply") {
                            withContext(Dispatchers.IO) { hub.handle(peer,message) }
                        } else {
                            if (pending.incrementAndGet() > 16) {
                                pending.decrementAndGet()
                                peer.send(LobbyMessage().apply { type = "result"; requestId = message.requestId; code = "COMMAND_FAILED"; this.message = "Room is busy. Please retry." })
                                continue
                            }
                            launch(Dispatchers.IO) {
                                try { hub.handle(peer,message) } finally { pending.decrementAndGet() }
                            }
                        }
                    }
                }
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { session.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY,"Invalid room message")) }
        finally {
            writer.cancel(); idle.cancel(); outbox.close()
            withContext(NonCancellable + Dispatchers.IO) {
                try { hub.detach(peer) } catch (_: Exception) { /* Persistence can recover after MongoDB is available. */ }
            }
        }
    }
}
