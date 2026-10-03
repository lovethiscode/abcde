package com.unciv.app.server

import com.unciv.logic.multiplayer.lobby.protocol.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

fun Route.lobbyRoutes(service: LobbyService) {
    suspend fun ApplicationCall.command(): LobbyCommand {
        val body = receiveText()
        if (body.length > 512 * 1024) throw LobbyFailure(413, "Room request is too large")
        return try { LobbyProtocol.json().fromJson(LobbyCommand::class.java, body) }
        catch (_: Exception) { throw LobbyFailure(400, "Invalid room request") }
    }
    suspend fun ApplicationCall.respondRoom(action: () -> LobbyRoom) {
        val room = withContext(Dispatchers.IO) { action() }
        respondText(LobbyProtocol.json().toJson(room), ContentType.Application.Json)
    }
    post("/lobbies") {
        val c = call.command()
        call.respondRoom { service.create(c) }
    }
    get("/lobbies/{gameId}") {
        val c = LobbyCommand().apply {
            playerId = call.request.queryParameters["playerId"].orEmpty()
            occupancy = call.request.queryParameters["occupancy"].orEmpty()
        }
        call.respondRoom { service.command(call.parameters["gameId"]!!, "read", c) }
    }
    put("/lobbies/{gameId}/configuration") {
        val c = call.command()
        call.respondRoom { service.command(call.parameters["gameId"]!!, "configuration", c) }
    }
    for (verb in listOf("join", "leave", "kick", "ready", "start", "close")) {
        post("/lobbies/{gameId}/$verb") {
            val c = call.command()
            call.respondRoom { service.command(call.parameters["gameId"]!!,verb,c) }
        }
    }
    for (verb in listOf("save", "complete")) {
        post("/lobbies/{gameId}/start/$verb") {
            val c = call.command()
            call.respondRoom { service.command(call.parameters["gameId"]!!,verb,c) }
        }
    }
    put("/lobbies/{gameId}/seats/{seatId}/civilization") {
        val c = call.command()
        c.seatId = call.parameters["seatId"]?.toIntOrNull() ?: throw LobbyFailure(400,"Invalid seat")
        call.respondRoom { service.command(call.parameters["gameId"]!!,"civilization",c) }
    }
}
