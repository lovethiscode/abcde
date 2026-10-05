package com.unciv.logic.multiplayer

import com.badlogic.gdx.Application
import com.badlogic.gdx.Gdx
import com.unciv.logic.event.EventBus.EventReceiver
import org.mockito.Mockito.*
import java.util.concurrent.CopyOnWriteArrayList
import com.unciv.UncivGame
import com.unciv.json.json
import com.unciv.logic.UncivShowableException
import com.unciv.logic.files.UncivFiles
import com.unciv.logic.files.FileConversions
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
    private var previousApp: Application? = null
    private val events = EventReceiver()
    private val results = CopyOnWriteArrayList<MultiplayerOperationCompleted>()
    @Before fun setup() {
        previousApp = Gdx.app
        val app = mock(Application::class.java)
        doAnswer { it.getArgument<Runnable>(0).run(); null }.`when`(app).postRunnable(any(Runnable::class.java))
        Gdx.app = app
        events.receive(MultiplayerOperationCompleted::class) { results.add(it) }
        RulesetCache.loadRulesets(noMods=true)
        UncivGame.Current=UncivGame().apply {
            files=UncivFiles(Gdx.files,directory.path)
            settings=GameSettings().apply { multiplayer.setServer("https://global.example") }
        }
    }
    @After fun cleanup() { events.stopReceiving(); Gdx.app = previousApp; directory.deleteRecursively() }
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
            seats=arrayListOf(LobbySeat().apply { playerId=host; civilization="Rome" },LobbySeat().apply { seatId=1; human=false; civilization="Greece" })
        }
        val session=LobbySession().apply { roomApiUrl=api; gameServerUrl=saves; gameId=room.gameId }
        var failUpload=true
        var interruptPreview=true
        var uploaded=""
        val requests=mutableListOf<MultiplayerV1Request>()
        val transport=object : MultiplayerV1Transport {
            override suspend fun execute(request: MultiplayerV1Request): MultiplayerV1Response {
                requests+=request
                assertTrue(request.url.startsWith("$saves/files/"))
                if (failUpload) throw MultiplayerNetworkException(MultiplayerNetworkError.CONNECTION)
                if (request.url.endsWith("_Preview") && interruptPreview) throw kotlinx.coroutines.CancellationException("Interrupted before complete")
                if (request.method == MultiplayerV1HttpMethod.GET) {
                    val notified=com.badlogic.gdx.utils.JsonReader().parse(FileConversions.unzip(uploaded))
                    notified.get("civilizations").first { it.getString("playerId","")==host }
                        .addChild("notifications",com.badlogic.gdx.utils.JsonReader().parse("""[{"text":"Welcome to UncivServer.xyz!"}]"""))
                    return MultiplayerV1Response(200,FileConversions.zip(notified.toJson(com.badlogic.gdx.utils.JsonWriter.OutputType.json)),emptyMap(),request.url)
                }
                if (!request.url.endsWith("_Preview")) uploaded=request.body!!
                return MultiplayerV1Response(200,"",emptyMap(),request.url)
            }
        }
        val socket = com.unciv.testing.TestLobbyTransport(saves) { request ->
            when (request.action) {
                "save" -> room.saveHash = request.command!!.saveHash
                "complete" -> room.state = "STARTED"
            }
            LobbyProtocol.snapshot(room)
        }
        val client = LobbyClient(api,socket)
        client.open(session)
        val starter=LobbyGameStarter(session,client,MultiplayerServer(saves,transport=transport))
        val command=LobbyCommand().apply { playerId=host }
        try { starter.start(command); fail("Upload must fail") } catch (_: MultiplayerNetworkException) { }
        assertTrue(socket.requests.none { it.action == "complete" })
        val first=requests.single { it.url=="$saves/files/${room.gameId}" }.body
        val metadata=UncivGame.Current.files.getLocalFile("lobby-start-${room.gameId}-${room.startOperation}.json.gz.meta.json")
        val originalMetadata=metadata.readString()
        val wrong=com.badlogic.gdx.utils.JsonReader().parse(originalMetadata)
        wrong.remove("frozenVersion"); wrong.addChild("frozenVersion",com.badlogic.gdx.utils.JsonValue(room.frozenVersion+1))
        metadata.writeString(wrong.toJson(com.badlogic.gdx.utils.JsonWriter.OutputType.json),false)
        try { starter.start(command); fail("Wrong recovery-file binding must be rejected") } catch (_: UncivShowableException) { }
        assertEquals(1,requests.size)
        metadata.writeString(originalMetadata,false)
        failUpload=false
        try { starter.start(command); fail("Completion must be interrupted") } catch (_: kotlinx.coroutines.CancellationException) { }
        assertTrue(socket.requests.none { it.action == "complete" })
        assertEquals(listOf(first,first),requests.filter { it.url=="$saves/files/${room.gameId}" }.map { it.body })
        assertEquals(room.saveHash,LobbyProtocol.saveFingerprint(FileConversions.unzip(first!!)))
        // Lost local recovery file: accept the same stored game with server-added notifications.
        UncivGame.Current.files.getLocalFile("lobby-start-${room.gameId}-${room.startOperation}.json.gz").delete()
        interruptPreview=false
        assertEquals("STARTED",starter.start(command).state)
        assertEquals(room.saveHash,LobbyProtocol.saveFingerprint(FileConversions.unzip(uploaded)))
        assertNotEquals(first,uploaded)
        assertEquals(listOf(false,false,true),results.filter { it.operation == MultiplayerOperation.Start }.map { it.succeeded })
        val count=requests.size
        starter.start(command)
        assertEquals(3,results.count { it.operation == MultiplayerOperation.Start })
        assertEquals(count,requests.size)
        assertEquals("wss://rooms.example/unciv-lobby/ws",socket.urls.single())
        assertTrue(requests.all { it.url.startsWith("$saves/files/") })
        assertEquals("https://global.example",UncivGame.Current.settings.multiplayer.getServer())
        client.close()
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
