package com.unciv.logic.multiplayer.lobby

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.utils.JsonReader
import com.unciv.UncivGame
import com.unciv.json.json
import com.unciv.logic.UncivShowableException
import com.unciv.logic.civilization.PlayerType
import com.unciv.logic.multiplayer.lobby.protocol.*
import com.unciv.logic.multiplayer.storage.*
import com.unciv.models.metadata.GameSetupInfo
import java.net.URLEncoder

class LobbyClient(val serverUrl: String, private val transport: MultiplayerV1Transport = UncivGame.Current.createMultiplayerV1Transport()) {
    companion object {
        fun rulesHash() = LobbyProtocol.fingerprint { path -> Gdx.files.internal(path).takeIf { it.exists() }?.readBytes() }
    }
    private suspend fun request(path: String, method: MultiplayerV1HttpMethod, c: LobbyCommand? = null): String {
        val capabilities = UncivGame.Current.platformCapabilities
        val server = MultiplayerServer.validateServerUrl(serverUrl, capabilities)
        val response = transport.execute(MultiplayerV1Request(method, "$server$path",
            headers = mapOf("Content-Type" to "application/json"), body = c?.let { LobbyProtocol.json().toJson(it) },
            requiresPublicHttps = capabilities.multiplayerServerRequiresHttps))
        if (response.statusCode !in 200..299) throw UncivShowableException(
            if (response.body.length in 1..250 && !response.body.contains('<')) response.body else "Room request failed. Please retry.")
        return response.body
    }
    suspend fun supported(): Boolean = JsonReader().parse(request("/isalive", MultiplayerV1HttpMethod.GET)).getInt("lobbyVersion", 0) >= 1
    suspend fun create(setup: GameSetupInfo, key: String): LobbyRoom {
        val c = LobbyCommand().apply {
            playerId = UncivGame.Current.settings.multiplayer.getUserId(); createKey = key
            rulesHash = rulesHash(); baseRuleset = setup.gameParameters.baseRuleset
            this.setup = json().toJson(setup)
            seats = ArrayList(setup.gameParameters.players.map { p -> LobbySeat().apply {
                human = p.playerType == PlayerType.Human; civilization = p.chosenCiv
            } })
        }
        return LobbyProtocol.json().fromJson(LobbyRoom::class.java,request("/lobbies",MultiplayerV1HttpMethod.POST,c))
    }
    suspend fun command(gameId: String, action: String, c: LobbyCommand): LobbyRoom {
        val path = if (action == "civilization") "/seats/${c.seatId}/civilization" else "/$action"
        return LobbyProtocol.json().fromJson(LobbyRoom::class.java,request("/lobbies/$gameId$path",
            if (action == "civilization") MultiplayerV1HttpMethod.PUT else MultiplayerV1HttpMethod.POST,c))
    }
    suspend fun read(gameId: String, c: LobbyCommand): LobbyRoom = LobbyProtocol.json().fromJson(LobbyRoom::class.java,
        request("/lobbies/$gameId?playerId=${URLEncoder.encode(c.playerId,"UTF-8")}&occupancy=${URLEncoder.encode(c.occupancy,"UTF-8")}",MultiplayerV1HttpMethod.GET))
}
