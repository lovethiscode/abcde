package com.unciv.logic.multiplayer

import com.badlogic.gdx.Gdx
import com.unciv.UncivGame
import com.unciv.json.json
import com.unciv.logic.civilization.PlayerType
import com.unciv.logic.files.UncivFiles
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
    @Test fun threeHumansAndAiUseFrozenCivilizationsAndReservedIdThroughRealHttpSave() = runBlocking {
        val source = System.getenv("UNCIV_LOBBY_TEST_SERVER")
        val gameServer = System.getenv("UNCIV_LOBBY_TEST_GAME_SERVER")
        Assume.assumeNotNull(source,gameServer)
        assertNotEquals(source,gameServer)
        val directory=java.nio.file.Files.createTempDirectory("unciv-live-dual-server").toFile()
        RulesetCache.loadRulesets(noMods=true)
        val host=UUID.randomUUID().toString(); val guest=UUID.randomUUID().toString(); val third=UUID.randomUUID().toString()
        UncivGame.Current=UncivGame().apply { files=UncivFiles(Gdx.files,directory.path); settings=GameSettings().apply { multiplayer.setUserId(host); multiplayer.setServer(gameServer!!) } }
        val setup=GameSetupInfo().apply {
            gameParameters.isOnlineMultiplayer=true
            gameParameters.multiplayerServerUrl=gameServer
            gameParameters.players=arrayListOf(Player("Rome",PlayerType.Human),Player("Egypt",PlayerType.Human),Player("India",PlayerType.Human),Player("England",PlayerType.AI))
            gameParameters.victoryTypes.add("Domination")
            gameParameters.numberOfCityStates=0
            mapParameters.mapSize=MapSize.Tiny; mapParameters.seed=42L
        }
        val requests=mutableListOf<MultiplayerV1Request>()
        val transport=object : MultiplayerV1Transport {
            val http=SimpleHttp()
            override suspend fun execute(request: MultiplayerV1Request): MultiplayerV1Response {
                requests+=request
                return http.execute(request)
            }
        }
        val client=LobbyClient(source!!,transport)
        assertTrue(client.configurationSupported())
        var room=client.create(setup,UUID.randomUUID().toString())
        val reservedId=room.gameId
        assertEquals(room.gameId,client.read(reservedId,LobbyCommand().apply {
            playerId=host; occupancy=room.seats.first().occupancy
        }).gameId)
        assertEquals("Random",room.seats[1].civilization)
        assertEquals("Random",room.seats[2].civilization)
        fun command(id: String) = LobbyCommand().apply {
            gameServerUrl=gameServer!!; playerId=id; occupancy=room.seats.firstOrNull { it.playerId==id }?.occupancy.orEmpty(); rulesHash=LobbyClient.rulesHash()
            configurationVersion=room.configurationVersion; startOperation=room.startOperation; frozenVersion=room.frozenVersion; saveHash=room.saveHash; seatRevision=room.seats.firstOrNull { it.playerId==id }?.revision ?: 0L
        }
        room=client.command(reservedId,"join",command(guest))
        val oldGuest=command(guest)
        room=client.command(reservedId,"kick",command(host).apply { seatId=1; targetOccupancy=room.seats[1].occupancy })
        assertEquals("",room.seats[1].playerId)
        room=client.command(reservedId,"join",command(guest))
        assertNotEquals(oldGuest.occupancy,room.seats[1].occupancy)
        try { client.command(reservedId,"leave",oldGuest); fail("Stale leave must fail") } catch (_: com.unciv.logic.UncivShowableException) { }
        room=client.command(reservedId,"leave",command(guest))
        assertEquals("",room.seats[1].playerId)
        room=client.command(reservedId,"join",command(guest))
        room=client.command(reservedId,"join",command(third))
        room=client.command(reservedId,"civilization",command(guest).apply { seatId=1; civilization="Greece" })
        room=client.command(reservedId,"civilization",command(third).apply { seatId=2; civilization="Russia" })
        for(id in listOf(host,guest,third)) room=client.command(reservedId,"ready",command(id).apply { ready=true })
        val server=MultiplayerServer(fileStorageIdentifier=gameServer,transport=transport)
        val session=LobbySession().apply { roomApiUrl=source; gameServerUrl=gameServer!!; gameId=reservedId }
        room=LobbyGameStarter(session,client,server).start(command(host))
        assertEquals("STARTED",room.state)
        assertEquals(reservedId,client.command(reservedId,"start/complete",command(host)).gameId)
        val loaded=server.tryDownloadVerifiedGame(reservedId)
        assertEquals(reservedId,loaded.gameId)
        assertEquals(gameServer,loaded.gameParameters.multiplayerServerUrl)
        assertEquals(listOf(host,guest,third,""),loaded.gameParameters.players.map { it.playerId })
        assertEquals(listOf("Rome","Greece","Russia","England"),loaded.gameParameters.players.map { it.chosenCiv })
        assertEquals(setOf(host,guest,third),loaded.civilizations.filter { it.isHuman() }.map { it.playerId }.toSet())
        assertEquals(gameServer,UncivGame.Current.settings.multiplayer.getServer())
        assertEquals(gameServer,room.gameServerUrl)
        val fileRequests=requests.filter { it.url.contains("/files/") }
        assertTrue(fileRequests.isNotEmpty())
        assertTrue(fileRequests.all { it.url.startsWith("$gameServer/files/") })
        assertTrue(requests.filter { it.url.contains("/lobbies") }.all { it.url.startsWith("$source/lobbies") })
        val body=server.fileStorage().loadFileData(reservedId)
        assertEquals(room.saveHash,LobbyProtocol.sha256(body))
        val evidence=System.getenv("UNCIV_LOBBY_EVIDENCE")
        if(evidence!=null) File(evidence).apply { mkdirs(); resolve("integration-room.json").writeText(LobbyProtocol.json().toJson(room)); resolve("integration-save.gz").writeText(body)
            resolve("integration-requests.txt").writeText(requests.joinToString("\n") { "${it.method} ${it.url}" }) }
        directory.deleteRecursively()
        Unit
    }
}
