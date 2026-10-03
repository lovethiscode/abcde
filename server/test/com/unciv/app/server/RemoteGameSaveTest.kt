package com.unciv.app.server

import com.sun.net.httpserver.HttpServer
import com.unciv.logic.multiplayer.lobby.protocol.LobbyRoom
import org.junit.Test
import org.junit.Assert.*
import java.net.InetSocketAddress
import java.util.Base64
import java.util.UUID

class RemoteGameSaveTest {
    @Test fun readsOnlyTheFullGameFromTheConfiguredFileServerWithoutPasswords() {
        val room = LobbyRoom().apply { gameId=UUID.randomUUID().toString(); hostId=UUID.randomUUID().toString() }
        val http = HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
        val requests = mutableListOf<String>()
        var status = 200
        http.createContext("/files/${room.gameId}") { exchange ->
            requests.add(exchange.requestMethod + " " + exchange.requestURI)
            assertEquals("Basic " + Base64.getEncoder().encodeToString("${room.hostId}:".toByteArray()), exchange.requestHeaders.getFirst("Authorization"))
            val body = "full save".toByteArray()
            exchange.sendResponseHeaders(status,body.size.toLong()); exchange.responseBody.use { it.write(body) }
        }
        http.start()
        try {
            val reader=RemoteGameSave("http://127.0.0.1:${http.address.port}")
            assertEquals("full save",reader.read(room))
            status=404; assertNull(reader.read(room))
            for (error in listOf(401,500,302)) {
                status=error
                assertEquals(503,assertThrows(LobbyFailure::class.java) { reader.read(room) }.status)
            }
            assertEquals(List(5) { "GET /files/${room.gameId}" }, requests)
        } finally { http.stop(0) }
    }
}
