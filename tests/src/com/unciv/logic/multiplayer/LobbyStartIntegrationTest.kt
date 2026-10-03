package com.unciv.logic.multiplayer

import com.badlogic.gdx.Gdx
import com.unciv.UncivGame
import com.unciv.json.json
import com.unciv.logic.GameStarter
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
        Assume.assumeNotNull(source)
        RulesetCache.loadRulesets(noMods=true)
        val host=UUID.randomUUID().toString(); val guest=UUID.randomUUID().toString(); val third=UUID.randomUUID().toString()
        UncivGame.Current=UncivGame().apply { files=UncivFiles(Gdx.files); settings=GameSettings().apply { multiplayer.setUserId(host) } }
        val setup=GameSetupInfo().apply {
            gameParameters.isOnlineMultiplayer=true
            gameParameters.players=arrayListOf(Player("Rome",PlayerType.Human),Player("Egypt",PlayerType.Human),Player("India",PlayerType.Human),Player("England",PlayerType.AI))
            gameParameters.victoryTypes.add("Domination")
            gameParameters.numberOfCityStates=0
            mapParameters.mapSize=MapSize.Tiny; mapParameters.seed=42L
        }
        val client=LobbyClient(source!!,SimpleHttp())
        assertTrue(client.supported())
        var room=client.create(setup,UUID.randomUUID().toString())
        val reservedId=room.gameId
        assertEquals("Random",room.seats[1].civilization)
        assertEquals("Random",room.seats[2].civilization)
        fun command(id: String) = LobbyCommand().apply {
            playerId=id; occupancy=room.seats.firstOrNull { it.playerId==id }?.occupancy.orEmpty(); rulesHash=LobbyClient.rulesHash()
            startOperation=room.startOperation; frozenVersion=room.frozenVersion; saveHash=room.saveHash; seatRevision=room.seats.firstOrNull { it.playerId==id }?.revision ?: 0L
        }
        room=client.command(reservedId,"join",command(guest))
        room=client.command(reservedId,"join",command(third))
        room=client.command(reservedId,"civilization",command(guest).apply { seatId=1; civilization="Greece" })
        room=client.command(reservedId,"civilization",command(third).apply { seatId=2; civilization="Russia" })
        for(id in listOf(host,guest,third)) room=client.command(reservedId,"ready",command(id).apply { ready=true })
        room=client.command(reservedId,"start",command(host))
        val frozen=json().fromJson(GameSetupInfo::class.java,room.setup)
        frozen.gameParameters.players=ArrayList(room.seats.map { Player(it.civilization,if(it.human) PlayerType.Human else PlayerType.AI,it.playerId) })
        frozen.gameParameters.multiplayerServerUrl=source
        val generated=GameStarter.startNewGame(frozen,reservedId)
        assertEquals(reservedId,generated.gameId)
        val body=UncivFiles.gameInfoToString(generated,forceZip=true,updateChecksum=true)
        room=client.command(reservedId,"start/save",command(host).apply { saveHash=LobbyProtocol.sha256(body) })
        val server=MultiplayerServer(fileStorageIdentifier=source,transport=SimpleHttp())
        server.fileStorage().saveFileData(reservedId,body)
        room=client.command(reservedId,"start/complete",command(host))
        assertEquals("STARTED",room.state)
        assertEquals(reservedId,client.command(reservedId,"start/complete",command(host)).gameId)
        val loaded=server.tryDownloadVerifiedGame(reservedId)
        assertEquals(reservedId,loaded.gameId)
        assertEquals(source,loaded.gameParameters.multiplayerServerUrl)
        assertEquals(listOf(host,guest,third,""),loaded.gameParameters.players.map { it.playerId })
        assertEquals(listOf("Rome","Greece","Russia","England"),loaded.gameParameters.players.map { it.chosenCiv })
        assertEquals(setOf(host,guest,third),loaded.civilizations.filter { it.isHuman() }.map { it.playerId }.toSet())
        val evidence=System.getenv("UNCIV_LOBBY_EVIDENCE")
        if(evidence!=null) File(evidence).apply { mkdirs(); resolve("integration-room.json").writeText(LobbyProtocol.json().toJson(room)); resolve("integration-save.gz").writeText(body) }
    }
}
