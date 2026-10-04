package com.unciv.logic.multiplayer

import com.badlogic.gdx.Gdx
import com.unciv.UncivGame
import com.unciv.json.json
import com.unciv.logic.civilization.PlayerType
import com.unciv.logic.files.UncivFiles
import com.unciv.logic.files.FileConversions
import com.unciv.logic.map.MapSize
import com.unciv.logic.multiplayer.lobby.*
import com.unciv.logic.multiplayer.lobby.protocol.*
import com.unciv.logic.multiplayer.storage.*
import com.unciv.models.metadata.*
import com.unciv.models.ruleset.RulesetCache
import com.unciv.testing.BaseTestRunner
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.UUID
import java.io.File

@RunWith(BaseTestRunner::class)
class LobbyStartIntegrationTest {
    @Test fun threeRealWebSocketsFreezeAndUploadTheSameGameToTheIndependentSaveServer() = runBlocking<Unit> {
        val api = System.getenv("UNCIV_LOBBY_TEST_SERVER")
        val saves = System.getenv("UNCIV_LOBBY_TEST_GAME_SERVER")
        Assume.assumeNotNull(api,saves)
        assertNotEquals(api,saves)
        val directory = java.nio.file.Files.createTempDirectory("unciv-live-wss").toFile()
        RulesetCache.loadRulesets(noMods = true)
        val ids = List(3) { UUID.randomUUID().toString() }
        UncivGame.Current = UncivGame().apply {
            files = UncivFiles(Gdx.files,directory.path)
            settings = GameSettings().apply { multiplayer.setUserId(ids[0]); multiplayer.setServer(saves!!) }
        }
        val setup = GameSetupInfo().apply {
            gameParameters.isOnlineMultiplayer = true; gameParameters.multiplayerServerUrl = saves
            gameParameters.players = arrayListOf(Player("Rome",PlayerType.Human),Player("Random",PlayerType.Human),
                Player("Random",PlayerType.Human),Player("England",PlayerType.AI))
            gameParameters.victoryTypes.add("Domination"); gameParameters.numberOfCityStates = 0
            mapParameters.mapSize = MapSize.Tiny; mapParameters.seed = 42L
        }
        val sessions = ids.map { id -> LobbySession().apply { roomApiUrl = api!!; gameServerUrl = saves!!; playerId = id } }
        sessions[0].apply { createKey = UUID.randomUUID().toString(); this.setup = json().toJson(setup); save() }
        val clients = ids.map { LobbyClient(api!!) }
        val requests = mutableListOf<MultiplayerV1Request>()
        val http = object : MultiplayerV1Transport {
            val transport = SimpleHttp()
            override suspend fun execute(request: MultiplayerV1Request): MultiplayerV1Response {
                requests += request; return transport.execute(request)
            }
        }
        try {
            var room = clients[0].open(sessions[0])
            val reserved = room.gameId
            fun command(index: Int) = LobbyCommand().apply {
                gameServerUrl = saves!!; playerId = ids[index]; occupancy = sessions[index].occupancy; rulesHash = LobbyClient.rulesHash()
                configurationVersion = room.configurationVersion; startOperation = room.startOperation
                frozenVersion = room.frozenVersion; saveHash = room.saveHash
                seatRevision = room.seats.firstOrNull { it.playerId == playerId }?.revision ?: 0L
            }
            for (index in 1..2) { sessions[index].gameId = reserved; room = clients[index].open(sessions[index],join = true) }
            assertTrue(room.seats[0].ready)
            for ((index,civ) in listOf(1 to "Greece",2 to "Russia")) {
                room = clients[index].command(reserved,"civilization",command(index).apply { seatId = index; civilization = civ })
                room = clients[index].command(reserved,"ready",command(index).apply { ready = true })
            }
            val server = MultiplayerServer(saves,transport = http)
            room = LobbyGameStarter(sessions[0],clients[0],server).start(command(0))
            assertEquals("STARTED",room.state)
            assertEquals(reserved,clients[0].command(reserved,"complete",command(0)).gameId)
            val loaded = server.tryDownloadGame(reserved)
            assertEquals(reserved,loaded.gameId)
            assertEquals(saves,loaded.gameParameters.multiplayerServerUrl)
            assertEquals(ids + "",loaded.gameParameters.players.map { it.playerId })
            assertEquals(listOf("Rome","Greece","Russia","England"),loaded.gameParameters.players.map { it.chosenCiv })
            assertTrue(requests.isNotEmpty())
            assertTrue(requests.all { it.url.startsWith("$saves/files/") })
            assertEquals(saves,UncivGame.Current.settings.multiplayer.getServer())
            val body = server.fileStorage().loadFileData(reserved)
            assertEquals(room.saveHash,LobbyProtocol.saveFingerprint(FileConversions.unzip(body)))
            System.getenv("UNCIV_LOBBY_EVIDENCE")?.let { path -> File(path).apply {
                mkdirs(); resolve("integration-room.json").writeText(LobbyProtocol.json().toJson(room))
                resolve("integration-save.gz").writeText(body)
                resolve("integration-http-requests.txt").writeText(requests.joinToString("\n") { "${it.method} ${it.url}" })
            } }
        } finally { clients.forEach { it.close() }; directory.deleteRecursively() }
    }
}
