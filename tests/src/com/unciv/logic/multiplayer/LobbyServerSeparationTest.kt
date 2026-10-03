package com.unciv.logic.multiplayer

import com.badlogic.gdx.Gdx
import com.unciv.UncivGame
import com.unciv.json.json
import com.unciv.logic.UncivShowableException
import com.unciv.logic.files.UncivFiles
import com.unciv.logic.multiplayer.lobby.*
import com.unciv.logic.multiplayer.lobby.protocol.*
import com.unciv.logic.multiplayer.storage.*
import com.unciv.models.metadata.GameSettings
import com.unciv.models.metadata.GameSetupInfo
import com.unciv.models.ruleset.RulesetCache
import com.unciv.testing.BaseTestRunner
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.nio.file.Files
import java.util.UUID

@RunWith(BaseTestRunner::class)
class LobbyServerSeparationTest {
    private val directory=Files.createTempDirectory("unciv-dual-server").toFile()
    private val api="https://rooms.example/unciv-lobby"
    private val saves="https://saves.example"
    @Before fun setup() {
        RulesetCache.loadRulesets(noMods=true)
        UncivGame.Current=UncivGame().apply {
            files=UncivFiles(Gdx.files,directory.path)
            settings=GameSettings().apply { multiplayer.setServer("https://global.example") }
        }
    }
    @After fun cleanup() { directory.deleteRecursively() }
    @Test fun failedUploadCannotCompleteAndRetryUsesTheIdenticalSaveOnTheGameServer() = runBlocking {
        val host=UncivGame.Current.settings.multiplayer.getUserId()
        val room=LobbyRoom().apply {
            gameId=UUID.randomUUID().toString(); gameServerUrl=saves
            state="STARTING"; startOperation=UUID.randomUUID().toString(); frozenVersion=5
            setup=json().toJson(GameSetupInfo().apply {
                gameParameters.isOnlineMultiplayer=true; gameParameters.multiplayerServerUrl=saves
                gameParameters.numberOfCityStates=0
                gameParameters.victoryTypes.add("Domination")
                mapParameters.seed=42
            })
            seats=arrayListOf(LobbySeat().apply { playerId=host; civilization="Rome" },LobbySeat().apply { human=false; civilization="Greece" })
        }
        val session=LobbySession().apply { roomApiUrl=api; gameServerUrl=saves; gameId=room.gameId }
        var failUpload=true
        val requests=mutableListOf<MultiplayerV1Request>()
        val transport=object : MultiplayerV1Transport {
            override suspend fun execute(request: MultiplayerV1Request): MultiplayerV1Response {
                requests+=request
                if (request.url.startsWith(api)) {
                    assertFalse(request.url.contains("/files/"))
                    when {
                        request.url.endsWith("/start/save") -> room.saveHash=LobbyProtocol.json().fromJson(LobbyCommand::class.java,request.body).saveHash
                        request.url.endsWith("/start/complete") -> room.state="STARTED"
                    }
                    return MultiplayerV1Response(200,LobbyProtocol.json().toJson(room),emptyMap(),request.url)
                }
                assertTrue(request.url.startsWith("$saves/files/"))
                if (failUpload) throw MultiplayerNetworkException(MultiplayerNetworkError.CONNECTION)
                return MultiplayerV1Response(200,"",emptyMap(),request.url)
            }
        }
        val starter=LobbyGameStarter(session,LobbyClient(api,transport),MultiplayerServer(saves,transport=transport))
        val command=LobbyCommand().apply { playerId=host }
        try { starter.start(command); fail("Upload must fail") } catch (_: MultiplayerNetworkException) { }
        assertTrue(requests.none { it.url.endsWith("/start/complete") })
        val first=requests.single { it.url=="$saves/files/${room.gameId}" }.body
        failUpload=false
        assertEquals("STARTED",starter.start(command).state)
        assertEquals(listOf(first,first),requests.filter { it.url=="$saves/files/${room.gameId}" }.map { it.body })
        assertEquals(room.saveHash,LobbyProtocol.sha256(first!!))
        val count=requests.size
        starter.start(command)
        assertEquals(count+1,requests.size)
        assertEquals("https://global.example",UncivGame.Current.settings.multiplayer.getServer())
    }
    @Test fun sessionPersistsBothAddressesAndArchivesLegacySingleServerRooms() {
        val old=UncivGame.Current.files.getLocalFile("multiplayer-lobby.json")
        old.writeString("""{"server":"https://old.example","gameId":"old"}""",false)
        assertNull(LobbySession.current())
        assertEquals(1,directory.listFiles()!!.count { it.name.startsWith("multiplayer-lobby.single-server-") })
        LobbySession().apply { roomApiUrl=api; gameServerUrl=saves; gameId="new" }.save()
        assertEquals(api,LobbySession.current()!!.roomApiUrl)
        assertEquals(saves,LobbySession.current()!!.gameServerUrl)
        assertEquals("https://global.example",UncivGame.Current.settings.multiplayer.getServer())
        assertThrows(UncivShowableException::class.java) { LobbySession.roomApiUrl() }
    }
}
