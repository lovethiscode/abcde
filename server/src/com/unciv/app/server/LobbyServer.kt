package com.unciv.app.server

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import kotlinx.coroutines.*
import com.unciv.logic.multiplayer.lobby.protocol.LobbyProtocol

/** Independent room API. This process has no save-file, password or chat endpoints. */
object LobbyServer {
    @JvmStatic fun main(args: Array<String>) {
        val uri = requireNotNull(System.getenv("UncivLobbyMongoUri")) { "UncivLobbyMongoUri is required" }
        val gameServer = (System.getenv("UncivLobbyGameServer") ?: "https://uncivserver.xyz").trimEnd('/')
        require(java.net.URI(gameServer).let { it.scheme == "https" && it.host != null && it.userInfo == null && it.query == null && it.fragment == null })
        LobbyService(uri, System.getenv("UncivLobbyMongoDatabase") ?: "unciv_lobby", gameServer).use { service ->
            LobbyHub(service).use { hub ->
            embeddedServer(Netty, host = "127.0.0.1", port = (System.getenv("UncivLobbyPort") ?: "8768").toInt()) {
                install(WebSockets) { maxFrameSize = LobbyProtocol.MAX_MESSAGE_BYTES.toLong() }
                install(StatusPages) {
                    exception<LobbyFailure> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.fromValue(cause.status)) }
                }
                routing {
                    get("/isalive") {
                        call.respondText("""{"protocolVersion":1,"transport":"websocket","gameServerUrl":"$gameServer"}""", ContentType.Application.Json)
                    }
                    lobbyRoutes(service,hub)
                }
                launch(Dispatchers.IO) {
                    while (isActive) {
                        try { hub.maintain() } catch (_: Exception) { log.warn("Room maintenance failed; retrying on the next cycle") }
                        delay(30_000)
                    }
                }
            }.start(wait = true)
            }
        }
    }
}
