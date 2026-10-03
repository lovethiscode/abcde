package com.unciv.app.server

import com.unciv.logic.multiplayer.lobby.protocol.LobbyRoom
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64

/** Reads the existing file service without receiving or storing game-server passwords. */
class RemoteGameSave(val gameServerUrl: String) {
    fun read(room: LobbyRoom): String? {
        val connection = URL("${gameServerUrl.trimEnd('/')}/files/${room.gameId}").openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000; connection.readTimeout = 30_000
            connection.setRequestProperty("Authorization","Basic " + Base64.getEncoder().encodeToString("${room.hostId}:".toByteArray()))
            connection.setRequestProperty("User-Agent","Unciv-Lobby/3")
            if (connection.responseCode == 404) return null
            if (connection.responseCode != 200) throw LobbyFailure(503,"Unable to verify the save on the game server. Retry start.")
            val bytes = connection.inputStream.use { it.readNBytes(64 * 1024 * 1024 + 1) }
            if (bytes.size > 64 * 1024 * 1024) throw LobbyFailure(413,"Game save is too large")
            return bytes.toString(Charsets.UTF_8)
        } catch (e: LobbyFailure) { throw e }
        catch (_: Exception) { throw LobbyFailure(503,"Unable to verify the save on the game server. Retry start.") }
        finally { connection.disconnect() }
    }
}
