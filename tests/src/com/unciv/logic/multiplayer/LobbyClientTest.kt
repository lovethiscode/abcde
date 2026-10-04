package com.unciv.logic.multiplayer

import com.badlogic.gdx.Gdx
import com.unciv.UncivGame
import com.unciv.logic.files.UncivFiles
import com.unciv.logic.multiplayer.lobby.*
import com.unciv.logic.multiplayer.lobby.protocol.*
import com.unciv.models.metadata.GameSettings
import com.unciv.testing.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.nio.file.Files
import java.util.UUID

@RunWith(BaseTestRunner::class)
class LobbyClientTest {
    private val directory = Files.createTempDirectory("unciv-ws-client").toFile()
    private val saves = "https://saves.example"
    private val api = "https://rooms.example/unciv-lobby"
    private lateinit var session: LobbySession
    private lateinit var room: LobbyRoom
    @Before fun setup() {
        UncivGame.Current = UncivGame().apply { files = UncivFiles(Gdx.files,directory.path); settings = GameSettings() }
        session = LobbySession().apply {
            roomApiUrl = api; gameServerUrl = saves; gameId = UUID.randomUUID().toString()
            playerId = UncivGame.Current.settings.multiplayer.getUserId()
        }
        session.save()
        room = LobbyRoom().apply {
            gameId = session.gameId; gameServerUrl = saves; version = 1; setup = "first"
            seats.add(LobbySeat().apply { playerId = session.playerId; occupancy = "seat" })
        }
    }
    @After fun cleanup() { directory.deleteRecursively() }
    @Test fun constructingClientDoesNotConnectAndJoiningPersistsIntentBeforeSend() = runBlocking {
        val socket = TestLobbyTransport(saves) { request ->
            assertEquals("join",LobbySession.current()!!.pendingAction)
            assertEquals(request.requestId,LobbySession.current()!!.pendingRequestId)
            room
        }
        val client = LobbyClient(api,socket)
        assertTrue(socket.urls.isEmpty())
        client.open(session,join = true)
        assertEquals("wss://rooms.example/unciv-lobby/ws",socket.urls.single())
        assertEquals("seat",LobbySession.current()!!.occupancy)
        assertEquals("",LobbySession.current()!!.pendingAction)
        client.close()
    }
    @Test fun lostJoinAckRecoversOriginalRequestWithoutAnotherJoin() = runBlocking {
        val socket = TestLobbyTransport(saves) { null }
        val first = LobbyClient(api,socket,requestTimeoutMillis = 100)
        assertTrue(runCatching { first.open(session,join = true) }.isFailure)
        first.close()
        val persisted = LobbySession.current()!!
        assertEquals("join",persisted.pendingAction)
        val original = socket.requests.single().requestId
        socket.reply = { request ->
            assertEquals("recoverJoin",request.action); assertEquals(original,request.command!!.originalRequestId); room
        }
        val second = LobbyClient(api,socket)
        second.open(persisted,join = true)
        assertEquals(1,socket.requests.count { it.action == "join" })
        assertEquals("seat",persisted.occupancy)
        second.close()
    }
    @Test fun receiveAndHeartbeatContinueWhileAnOperationWaitsForItsAck() = runBlocking {
        session.occupancy = "seat"
        val socket = TestLobbyTransport(saves) { request -> if (request.action == "resume") room else null }
        val client = LobbyClient(api,socket,heartbeatMillis = 20)
        client.open(session)
        val waiting = async { client.command(room.gameId,"configuration",LobbyCommand()) }
        withTimeout(2_000) { while (socket.requests.none { it.action == "configuration" }) delay(5) }
        socket.sockets.last().emit(LobbyMessage().apply { type = "check"; gameId = this@LobbyClientTest.room.gameId; checkId = "nonce" })
        withTimeout(2_000) { while (socket.requests.none { it.type == "checkReply" } || socket.requests.none { it.action == "heartbeat" }) delay(5) }
        assertFalse(waiting.isCompleted)
        waiting.cancelAndJoin(); client.close()
        val count = socket.requests.size; delay(70)
        assertEquals(count,socket.requests.size)
    }
    @Test fun staleSnapshotsAndOldSessionResultsCannotOverwriteNewAssociation() = runBlocking {
        session.occupancy = "seat"
        val socket = TestLobbyTransport(saves) { room }
        val client = LobbyClient(api,socket)
        client.open(session)
        val newer = LobbyProtocol.snapshot(room).apply { version = 3; setup = "newer" }
        socket.sockets.last().emit(LobbyMessage().apply { type = "snapshot"; this.room = newer })
        withTimeout(2_000) { while (LobbySession.current()?.setup != "newer") delay(5) }
        socket.sockets.last().emit(LobbyMessage().apply { type = "snapshot"; this.room = this@LobbyClientTest.room })
        delay(30); assertEquals("newer",LobbySession.current()!!.setup)
        val other = LobbySession().apply { roomApiUrl = api; gameServerUrl = saves; gameId = "another"; save() }
        socket.sockets.last().emit(LobbyMessage().apply { type = "snapshot"; this.room = newer.apply { version++ } })
        delay(30); assertEquals(other.sessionId,LobbySession.current()!!.sessionId)
        client.close()
    }
    @Test fun terminalEventStopsReconnectAndHeartbeat() = runBlocking {
        session.occupancy = "seat"
        val socket = TestLobbyTransport(saves) { room }
        val client = LobbyClient(api,socket,heartbeatMillis = 20)
        val ended = CompletableDeferred<Unit>()
        client.onConnection = { connected,message -> if (!connected && message == "removed") ended.complete(Unit) }
        client.watch(session)
        withTimeout(2_000) { while (!client.isConnected) delay(5) }
        socket.sockets.last().emit(LobbyMessage().apply { type = "terminated"; code = "MEMBERSHIP_EXPIRED"; message = "removed" })
        withTimeout(2_000) { ended.await() }
        delay(100); assertEquals(1,socket.urls.size); assertNull(LobbySession.current())
        client.close()
    }
    @Test fun snapshotOmitsInternalReceiptsAndBoundsUtf8Bytes() {
        room.joinReceipts.add(LobbyJoinReceipt().apply { requestId = "private receipt" })
        val snapshot = LobbyProtocol.snapshot(room)
        assertTrue(snapshot.joinReceipts.isEmpty())
        assertEquals(1,room.joinReceipts.size)
        assertThrows(IllegalArgumentException::class.java) { LobbyProtocol.decode("中".repeat(LobbyProtocol.MAX_MESSAGE_BYTES/2)) }
    }
    @Test fun terminalResultStopsWatcherAndDoesNotSendMoreCommands() = runBlocking {
        session.occupancy = "seat"
        val socket = TestLobbyTransport(saves) { room }
        val client = LobbyClient(api,socket)
        client.watch(session)
        withTimeout(2_000) { while (!client.isConnected || socket.requests.isEmpty()) delay(5) }
        socket.reply = { request ->
            socket.sockets.last().emit(LobbyMessage().apply {
                type = "result"; requestId = request.requestId; code = "MEMBERSHIP_EXPIRED"; message = "removed"
            }); null
        }
        assertTrue(runCatching { client.command(room.gameId,"ready",LobbyCommand()) }.isFailure)
        withTimeout(2_000) { while (client.isConnected) delay(5) }
        delay(50); assertNull(LobbySession.current()); assertEquals(1,socket.urls.size)
        client.close()
    }
}
