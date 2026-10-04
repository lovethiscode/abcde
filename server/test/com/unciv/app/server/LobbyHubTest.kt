package com.unciv.app.server

import com.unciv.logic.multiplayer.lobby.protocol.*
import com.mongodb.client.MongoClients
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.junit.*
import org.junit.Assert.*
import java.util.UUID

/** Real MongoDB, isolated per test; controlled peers exercise races without network timing luck. */
class LobbyHubTest {
    private val uri = System.getenv("UNCIV_LOBBY_TEST_MONGO") ?: ""
    private val database = "uws_" + UUID.randomUUID().toString().replace("-", "")
    private val saves = "https://saves.example"
    private var now = 1_000_000L
    private lateinit var service: LobbyService
    private lateinit var hub: LobbyHub
    private val host = UUID.randomUUID().toString()
    private class Peer {
        val messages = Channel<LobbyMessage>(Channel.UNLIMITED)
        val history = java.util.concurrent.CopyOnWriteArrayList<LobbyMessage>()
        var stopped = false
        var publicationFailure = false
        val socket = LobbyPeer({
            check(!publicationFailure) { "Injected publication failure" }
            history.add(it); messages.trySend(it).isSuccess
        }, { stopped = true })
    }
    @Before fun setup() {
        Assume.assumeTrue(uri.isNotEmpty())
        service = LobbyService(uri,database,saves,{ null }) { now }
        hub = LobbyHub(service,{ now },1_000)
    }
    @After fun cleanup() {
        if (::hub.isInitialized) hub.close()
        if (::service.isInitialized) { service.close(); MongoClients.create(uri).use { it.getDatabase(database).drop() } }
    }
    private fun create(humans: Int = 2) = service.create(LobbyCommand().apply {
        playerId = host; createKey = UUID.randomUUID().toString(); gameServerUrl = saves
        rulesHash = service.rulesHash; baseRuleset = LobbyProtocol.rulesets[1]
        seats = ArrayList(List(humans) { LobbySeat() })
        setup = """{"gameParameters":{"isOnlineMultiplayer":true,"multiplayerServerUrl":"$saves","baseRuleset":"$baseRuleset","players":[${List(humans) { "{\"playerType\":\"Human\"}" }.joinToString()}]},"mapParameters":{"seed":0}}"""
    })
    private fun command(room: LobbyRoom, player: String) = LobbyCommand().apply {
        playerId = player; occupancy = room.seats.firstOrNull { it.playerId == player }?.occupancy.orEmpty()
        gameServerUrl = saves; rulesHash = service.rulesHash; configurationVersion = room.configurationVersion
        seatRevision = room.seats.firstOrNull { it.playerId == player }?.revision ?: 0L
        startOperation = room.startOperation; frozenVersion = room.frozenVersion; saveHash = room.saveHash
    }
    private fun message(room: LobbyRoom, player: String, action: String) = LobbyMessage().apply {
        type = "command"; requestId = UUID.randomUUID().toString(); gameId = room.gameId; this.action = action
        command = command(room,player)
    }
    private suspend fun next(peer: Peer, accept: (LobbyMessage) -> Boolean): LobbyMessage = withTimeout(3_000) {
        while (true) { val m = peer.messages.receive(); if (accept(m)) return@withTimeout m }
        error("unreachable")
    }
    private suspend fun issue(peer: Peer, m: LobbyMessage): LobbyMessage {
        val offset = peer.history.size
        hub.handle(peer.socket,m)
        return withTimeout(3_000) {
            while (true) {
                peer.history.drop(offset).firstOrNull { it.type == "result" && it.requestId == m.requestId }?.let { return@withTimeout it }
                delay(5)
            }
            error("unreachable")
        }
    }
    private suspend fun connect(room: LobbyRoom, player: String, join: Boolean = false): Peer = Peer().also {
        assertEquals("",issue(it,message(room,player,if (join) "join" else "resume")).code)
    }
    private suspend fun ready(peer: Peer) {
        val room = service.find(peer.socket.gameId)!!
        val result = issue(peer,message(room,peer.socket.playerId,"ready").apply { command!!.ready = true })
        assertEquals("",result.code)
    }
    private suspend fun reply(peer: Peer) {
        val check = next(peer) { it.type == "check" }
        hub.handle(peer.socket,LobbyMessage().apply { type = "checkReply"; gameId = peer.socket.gameId; checkId = check.checkId })
    }
    @Test fun disconnectKeepsCivilizationButCancelsReadyAndBlocksStart() = runBlocking {
        var room = create(); val h = connect(room,host)
        val guest = UUID.randomUUID().toString(); val g = connect(room,guest,true)
        room = service.find(room.gameId)!!
        issue(g,message(room,guest,"civilization").apply { command!!.seatId = 1; command!!.civilization = "Rome" })
        ready(g); hub.detach(g.socket)
        val disconnected = service.find(room.gameId)!!
        assertFalse(disconnected.seats[1].ready); assertEquals("Rome",disconnected.seats[1].civilization)
        assertEquals(guest,disconnected.seats[1].playerId)
        assertEquals("COMMAND_FAILED",issue(h,message(disconnected,host,"start")).code)
        val restored = connect(disconnected,guest)
        assertEquals(disconnected.seats[1].occupancy,restored.socket.occupancy)
        assertFalse(service.find(room.gameId)!!.seats[1].ready)
    }
    @Test fun kickChangesSeatToAiPreservesOtherReadyAndHostCanStart() = runBlocking {
        val room = create(3); val h = connect(room,host)
        val ids = List(2) { UUID.randomUUID().toString() }
        val guests = ids.map { connect(room,it,true) }; guests.forEach { ready(it) }
        val before = service.find(room.gameId)!!
        val result = issue(h,message(before,host,"kick").apply { command!!.seatId = 1; command!!.targetOccupancy = before.seats[1].occupancy })
        assertEquals("",result.code)
        val kicked = result.room!!
        assertFalse(kicked.seats[1].human); assertEquals("",kicked.seats[1].playerId); assertTrue(kicked.seats[2].ready)
        assertEquals("AI",com.badlogic.gdx.utils.JsonReader().parse(kicked.setup).get("gameParameters").get("players")[1].getString("playerType"))
        val starting = async { issue(h,message(kicked,host,"start")) }
        reply(h); reply(guests[1])
        val frozen = starting.await().room!!
        assertEquals("STARTING",frozen.state)
        assertNotEquals(0L,com.badlogic.gdx.utils.JsonReader().parse(frozen.setup).get("mapParameters").getLong("seed"))
    }
    @Test fun latestConnectionWinsAndOldCloseOrReadyCannotChangeIt() = runBlocking {
        val room = create(); connect(room,host)
        val guest = UUID.randomUUID().toString(); val old = connect(room,guest,true)
        ready(old)
        val before = service.find(room.gameId)!!
        val replacement = connect(before,guest)
        assertFalse(service.find(room.gameId)!!.seats[1].ready)
        ready(replacement); hub.detach(old.socket)
        assertTrue(service.find(room.gameId)!!.seats[1].ready)
        assertEquals("MEMBERSHIP_EXPIRED",issue(old,message(service.find(room.gameId)!!,guest,"ready")).code)
        assertTrue(service.find(room.gameId)!!.seats[1].ready)
    }
    @Test fun heartbeatDoesNotBroadcastSnapshotsAndLastPeerDetachReleasesMemory() = runBlocking {
        val room = create(); val h = connect(room,host)
        while (h.messages.tryReceive().isSuccess) { }
        repeat(3) {
            val m = message(service.find(room.gameId)!!,host,"heartbeat")
            hub.handle(h.socket,m)
            assertEquals("pong",next(h) { it.requestId == m.requestId }.type)
        }
        assertTrue(h.messages.tryReceive().isFailure)
        hub.detach(h.socket)
        assertEquals(0,hub.activeRoomCount)
        assertNotNull(service.find(room.gameId))
    }
    @Test fun probeWaitReleasesRoomLockAndConfigurationChangePreventsLateFreeze() = runBlocking {
        val room = create(); val h = connect(room,host)
        val guest = UUID.randomUUID().toString(); val g = connect(room,guest,true); ready(g)
        val before = service.find(room.gameId)!!
        val starting = async { issue(h,message(before,host,"start")) }
        reply(h)
        val check = next(g) { it.type == "check" }
        val update = message(before,host,"configuration").apply { command!!.apply {
            setup = before.setup; baseRuleset = before.baseRuleset
            seats = ArrayList(before.seats.map { s -> LobbySeat().apply { seatId = s.seatId; human = s.human; civilization = s.civilization } })
        } }
        assertEquals("",issue(h,update).code)
        hub.handle(g.socket,LobbyMessage().apply { type = "checkReply"; gameId = before.gameId; checkId = check.checkId })
        assertEquals("COMMAND_FAILED",starting.await().code)
        assertEquals("WAITING",service.find(room.gameId)!!.state)
    }
    @Test fun missingProbeResponseUnreadiesGuestWithoutReleasingSeat() = runBlocking {
        val room = create(); val h = connect(room,host)
        val guest = UUID.randomUUID().toString(); val g = connect(room,guest,true); ready(g)
        val starting = async { issue(h,message(service.find(room.gameId)!!,host,"start")) }
        reply(h)
        assertEquals("COMMAND_FAILED",starting.await().code)
        val after = service.find(room.gameId)!!
        assertFalse(after.seats[1].ready); assertEquals(guest,after.seats[1].playerId); assertTrue(g.stopped)
        assertEquals("WAITING",after.state)
    }
    @Test fun firstJoinReceiptSurvivesRestartAndCannotRejoinAfterKick() = runBlocking {
        val room = create(); val h = connect(room,host)
        val guest = UUID.randomUUID().toString(); val g = Peer()
        val join = message(room,guest,"join")
        val joined = issue(g,join).room!!
        assertTrue(joined.joinReceipts.isEmpty())
        assertEquals(1,service.find(room.gameId)!!.joinReceipts.size)
        val kick = message(service.find(room.gameId)!!,host,"kick").apply { command!!.seatId = 1; command!!.targetOccupancy = g.socket.occupancy }
        issue(h,kick)
        hub.close(); service.close()
        service = LobbyService(uri,database,saves,{ null }) { now }; hub = LobbyHub(service,{ now },1_000)
        val recovery = message(service.find(room.gameId)!!,guest,"recoverJoin").apply { command!!.originalRequestId = join.requestId }
        assertEquals("MEMBERSHIP_EXPIRED",issue(Peer(),recovery).code)
        assertFalse(service.find(room.gameId)!!.seats[1].human)
    }
    @Test fun expiryRunsWithoutSubscribersAndDoesNotRenewOldTimestamps() = runBlocking {
        val room = create(); val h = connect(room,host)
        val guest = UUID.randomUUID().toString(); val g = connect(room,guest,true)
        hub.detach(g.socket); hub.detach(h.socket)
        now += 5 * 60_000L; hub.maintain()
        val expired = service.find(room.gameId)!!
        assertEquals("",expired.seats[1].playerId); assertTrue(expired.seats[1].human)
        assertEquals(1_000_000L,expired.seats[0].lastSeen)
        now += 25 * 60_000L; hub.maintain()
        assertEquals("CLOSED",service.find(room.gameId)!!.state)
        assertEquals(0,hub.activeRoomCount)
    }
    @Test fun wrongServerIsRejectedBeforeAllocatingASeat() = runBlocking {
        val room = create(); connect(room,host)
        val join = message(room,UUID.randomUUID().toString(),"join").apply { command!!.gameServerUrl = "https://other.example" }
        assertEquals("SERVER_MISMATCH",issue(Peer(),join).code)
        assertEquals("",service.find(room.gameId)!!.seats[1].playerId)
    }
    @Test fun boundPeerCannotJoinAnotherRoomOrChangeIdentity() = runBlocking {
        val first = create(); val second = create()
        val guest = UUID.randomUUID().toString(); val peer = connect(first,guest,true)
        assertEquals("COMMAND_FAILED",issue(peer,message(second,guest,"join")).code)
        assertEquals("",service.find(second.gameId)!!.seats[1].playerId)
        assertEquals("COMMAND_FAILED",issue(peer,message(first,UUID.randomUUID().toString(),"join")).code)
        assertEquals(guest,service.find(first.gameId)!!.seats[1].playerId)
    }
    @Test fun joinReceiptRejectsDifferentPayloadWithTheSameRequestId() = runBlocking {
        val room = create(); val guest = UUID.randomUUID().toString()
        val original = message(room,guest,"join")
        assertEquals("",issue(Peer(),original).code)
        val changed = message(room,guest,"join").apply {
            requestId = original.requestId; command!!.civilization = "Rome"
        }
        assertEquals("COMMAND_FAILED",issue(Peer(),changed).code)
        assertEquals(1,service.find(room.gameId)!!.seats.count { it.playerId == guest })
    }
    @Test fun committedWriteWithBrokenPublicationClosesEveryAffectedConnection() = runBlocking {
        val room = create(); val hostPeer = connect(room,host)
        val guest = UUID.randomUUID().toString(); val guestPeer = connect(room,guest,true)
        guestPeer.publicationFailure = true
        assertTrue(runCatching { hub.handle(guestPeer.socket,message(service.find(room.gameId)!!,guest,"ready").apply {
            command!!.ready = true
        }) }.isFailure)
        assertTrue(service.find(room.gameId)!!.seats[1].ready)
        assertTrue(hostPeer.stopped); assertTrue(guestPeer.stopped)
        // Route cleanup follows socket cancellation, independently of the failed writer.
        hub.detach(guestPeer.socket); hub.detach(hostPeer.socket)
        val restored = connect(service.find(room.gameId)!!,guest)
        assertEquals(guestPeer.socket.occupancy,restored.socket.occupancy)
        assertFalse(service.find(room.gameId)!!.seats[1].ready)
    }
}
