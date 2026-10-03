package com.unciv.app.server

import com.unciv.logic.multiplayer.lobby.protocol.*
import com.mongodb.client.MongoClients
import org.junit.*
import org.junit.Assert.*
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.CountDownLatch
import com.badlogic.gdx.utils.JsonReader
import com.badlogic.gdx.utils.JsonValue
import com.badlogic.gdx.utils.JsonWriter

/** Integration tests use a separate real MongoDB database per test, never the live game database. */
class LobbyServiceTest {
    private val uri = System.getenv("UNCIV_LOBBY_TEST_MONGO") ?: "mongodb://127.0.0.1:27017"
    private val db = "unciv_lobby_test_" + UUID.randomUUID().toString().replace("-", "")
    private val folder = Files.createTempDirectory("unciv-lobby-test").toFile()
    private val gameServer = "https://saves.example"
    private var unreadable = false
    private fun newService() = LobbyService(uri,db,gameServer,{ r ->
        if (unreadable) throw LobbyFailure(503,"Save server unavailable")
        folder.resolve(r.gameId).takeIf { it.exists() }?.readText()
    }) { now }
    private var now = 1_000_000L
    private lateinit var service: LobbyService
    private val host = UUID.randomUUID().toString()
    private val guest = UUID.randomUUID().toString()
    @Before fun setup() { Assume.assumeTrue(System.getenv("UNCIV_LOBBY_TEST_MONGO") != null); service = newService() }
    @After fun cleanup() {
        if (::service.isInitialized) { service.close(); MongoClients.create(uri).use { it.getDatabase(db).drop() } }
        folder.deleteRecursively()
    }
    private fun create(humans: Int = 2): LobbyRoom {
        val c = LobbyCommand().apply {
            gameServerUrl = gameServer; playerId = host; createKey = UUID.randomUUID().toString(); rulesHash = service.rulesHash; baseRuleset = LobbyProtocol.rulesets[1]
            seats = ArrayList((0 until humans).map { LobbySeat() })
            setup = """{"gameParameters":{"isOnlineMultiplayer":true,"multiplayerServerUrl":"$gameServer","baseRuleset":"$baseRuleset","players":[${(0 until humans).joinToString { "{\"playerType\":\"Human\"}" }}]},"mapParameters":{"seed":42}}"""
        }
        val r = service.create(c)
        assertEquals(r.gameId,service.create(c).gameId)
        return r
    }
    private fun c(r: LobbyRoom, id: String) = LobbyCommand().apply {
        gameServerUrl=gameServer; playerId=id; occupancy=r.seats.firstOrNull { it.playerId==id }?.occupancy.orEmpty(); rulesHash=service.rulesHash
        startOperation=r.startOperation; frozenVersion=r.frozenVersion; saveHash=r.saveHash; seatRevision=r.seats.firstOrNull { it.playerId==id }?.revision ?: 0L
        configurationVersion=r.configurationVersion
    }
    private fun configuration(r: LobbyRoom) = c(r,host).apply {
        setup=r.setup; baseRuleset=r.baseRuleset
        seats=ArrayList(r.seats.map { old -> LobbySeat().apply { seatId=old.seatId; human=old.human; civilization=old.civilization } })
    }
    private fun JsonValue.set(key: String, value: String) { remove(key); addChild(key,JsonValue(value)) }
    @Test fun configurationUpdatesKeepMembersAndInvalidatePreviouslyPreparedRequests() {
        var r=join(create())
        for(id in listOf(host,guest)) r=service.command(r.gameId,"ready",c(r,id).apply { ready=true })
        val oldReady=c(r,guest).apply { ready=true }
        val occupancy=r.seats.map { it.occupancy }
        val update=configuration(r).apply {
            setup=JsonReader().parse(setup).apply { get("gameParameters").set("difficulty","King") }.toJson(JsonWriter.OutputType.json)
        }
        r=service.command(r.gameId,"configuration",update)
        assertEquals(1L,r.configurationVersion)
        assertEquals(occupancy,r.seats.map { it.occupancy })
        assertEquals(listOf(host,guest),r.seats.map { it.playerId })
        assertTrue(r.seats.none { it.ready })
        assertEquals("King",JsonReader().parse(r.setup).get("gameParameters").getString("difficulty"))
        assertThrows(LobbyFailure::class.java) { service.command(r.gameId,"ready",oldReady) }
        assertThrows(LobbyFailure::class.java) { service.command(r.gameId,"configuration",update) }
    }
    @Test fun removingAnEmptySeatReindexesMembersWithoutReplacingTheirOccupancy() {
        var r=join(create(3))
        val third=UUID.randomUUID().toString()
        r=join(r,third)
        r=service.command(r.gameId,"leave",c(r,guest))
        val old=r.seats[2].occupancy
        val update=configuration(r).apply {
            seats.removeAt(1)
            setup=JsonReader().parse(setup).apply { get("gameParameters").get("players").remove(1) }.toJson(JsonWriter.OutputType.json)
        }
        r=service.command(r.gameId,"configuration",update)
        assertEquals(listOf(0,1),r.seats.map { it.seatId })
        assertEquals(third,r.seats[1].playerId)
        assertEquals(old,r.seats[1].occupancy)
        assertEquals(third,JsonReader().parse(r.setup).get("gameParameters").get("players")[1].getString("playerId"))
        assertTrue(service.command(r.gameId,"ready",c(r,third).apply { ready=true }).seats[1].ready)
    }
    @Test fun configurationCannotReplaceAPlayerWhoJoinedWhileTheHostWasEditing() {
        val r=create()
        val update=configuration(r).apply {
            seats[1].human=false
            setup=JsonReader().parse(setup).apply { get("gameParameters").get("players")[1].set("playerType","AI") }.toJson(JsonWriter.OutputType.json)
        }
        val joined=join(r)
        assertThrows(LobbyFailure::class.java) { service.command(r.gameId,"configuration",update) }
        assertEquals(joined.seats[1].occupancy,service.find(r.gameId)!!.seats[1].occupancy)
        assertEquals(0L,service.find(r.gameId)!!.configurationVersion)
    }
    @Test fun onlyHostCanUpdateAndConfigurationCannotChangeFrozenStart() {
        var r=join(create())
        assertThrows(LobbyFailure::class.java) { service.command(r.gameId,"configuration",configuration(r).apply {
            playerId=guest; occupancy=r.seats[1].occupancy
        }) }
        for(id in listOf(host,guest)) r=service.command(r.gameId,"ready",c(r,id).apply { ready=true })
        val update=configuration(r)
        val frozen=service.command(r.gameId,"start",c(r,host))
        assertThrows(LobbyFailure::class.java) { service.command(r.gameId,"configuration",update) }
        assertEquals(frozen.setup,service.find(r.gameId)!!.setup)
        assertEquals(frozen.frozenVersion,service.find(r.gameId)!!.frozenVersion)
    }
    private fun join(r: LobbyRoom,id: String=guest) = service.command(r.gameId,"join",c(r,id))
    @Test fun simultaneousLastSeatHasOneWinnerAndDuplicateJoinIsIdempotent() {
        val r=create(); val start=CountDownLatch(1); val pool=Executors.newFixedThreadPool(8)
        try {
            val results=(0..7).map { pool.submit<Boolean> { start.await(); try { join(r,UUID.randomUUID().toString()); true } catch (_: LobbyFailure) { false } } }
            start.countDown(); assertEquals(1,results.count { it.get() })
            val latest=service.find(r.gameId)!!; val id=latest.seats[1].playerId
            assertEquals(latest.seats[1].occupancy,join(latest,id).seats[1].occupancy)
        } finally { pool.shutdownNow() }
    }
    @Test fun kickRejoinRejectsStaleLeaveAndStaleKick() {
        val r=join(create()); val old=c(r,guest)
        val kick=c(r,host).apply { seatId=1; targetOccupancy=r.seats[1].occupancy }
        var latest=service.command(r.gameId,"kick",kick)
        assertEquals("",latest.seats[1].playerId); assertEquals("Random",latest.seats[1].civilization)
        latest=join(latest); assertNotEquals(old.occupancy,latest.seats[1].occupancy)
        assertThrows(LobbyFailure::class.java) { service.command(r.gameId,"leave",old) }
        assertThrows(LobbyFailure::class.java) { service.command(r.gameId,"kick",kick) }
        assertEquals(guest,service.find(r.gameId)!!.seats[1].playerId)
    }
    @Test fun civilizationChangeResetsOnlyOwnReadyAndNonMemberCannotChangeIt() {
        var r=join(create())
        r=service.command(r.gameId,"ready",c(r,host).apply { ready=true })
        r=service.command(r.gameId,"ready",c(r,guest).apply { ready=true })
        r=service.command(r.gameId,"civilization",c(r,guest).apply { seatId=1; civilization="Rome" })
        assertTrue(r.seats[0].ready); assertFalse(r.seats[1].ready)
        assertThrows(LobbyFailure::class.java) { service.command(r.gameId,"civilization",c(r,host).apply { seatId=1; civilization="Greece" }) }
        r=service.command(r.gameId,"ready",c(r,guest).apply { ready=true })
        assertTrue(service.command(r.gameId,"civilization",c(r,guest).apply { seatId=1; civilization="Rome" }).seats[1].ready)
    }
    @Test fun memberTimeoutIsIndependentAndPersistsAcrossRestart() {
        var r=join(create()); now+=4*60_000
        r=service.command(r.gameId,"read",c(r,host)); service.close(); service=newService()
        assertEquals(guest,service.find(r.gameId)!!.seats[1].playerId)
        now+=60_000; r=service.command(r.gameId,"read",c(r,host))
        assertEquals("",r.seats[1].playerId); assertEquals("Random",r.seats[1].civilization)
        now+=30*60_000
        assertEquals("CLOSED",service.command(r.gameId,"read",c(r,guest)).state)
        assertThrows(LobbyFailure::class.java) { join(r) }
    }
    @Test fun leavingClosedRoomIsIdempotentAndAllowsJoiningAnotherRoom() {
        val old=join(create())
        val leave=c(old,guest)
        now+=30*60_000
        val closed=service.command(old.gameId,"read",c(old,host))
        assertEquals("CLOSED",closed.state)
        repeat(2) {
            val result=service.command(old.gameId,"leave",leave)
            assertEquals("CLOSED",result.state)
            assertEquals(closed.reason,result.reason)
            assertEquals(closed.seats[1].occupancy,result.seats[1].occupancy)
        }
        assertThrows(LobbyFailure::class.java) { join(closed) }
        assertEquals(guest,join(create()).seats[1].playerId)
    }
    @Test fun onlyFullReadyRoomCanStartAndFrozenSnapshotCannotChange() {
        var r=create()
        assertThrows(LobbyFailure::class.java) { service.command(r.gameId,"start",c(r,host)) }
        r=join(r); r=service.command(r.gameId,"ready",c(r,guest).apply { ready=true })
        assertThrows(LobbyFailure::class.java) { service.command(r.gameId,"start",c(r,host)) }
        r=service.command(r.gameId,"ready",c(r,host).apply { ready=true })
        val frozen=service.command(r.gameId,"start",c(r,host))
        assertEquals("STARTING",frozen.state)
        assertEquals(frozen.startOperation,service.command(r.gameId,"start",c(frozen,host)).startOperation)
        for (verb in listOf("leave","ready","kick","civilization")) assertThrows(LobbyFailure::class.java) { service.command(r.gameId,verb,c(frozen,guest)) }
        now+=6*60_000
        assertEquals(guest,service.command(r.gameId,"read",c(frozen,host)).seats[1].playerId)
    }
    @Test fun closeAndRulesMismatchAreRejectedWithoutReopeningRoom() {
        val r=create()
        assertThrows(LobbyFailure::class.java) { service.command(r.gameId,"join",c(r,guest).apply { rulesHash="other" }) }
        assertThrows(LobbyFailure::class.java) { service.command(r.gameId,"close",c(r,guest)) }
        assertEquals("CLOSED",service.command(r.gameId,"close",c(r,host)).state)
        assertThrows(LobbyFailure::class.java) { join(r) }
    }
    @Test fun externalUploadUncertaintyKeepsOriginalStartRecoverable() {
        var r=join(create()); for (id in listOf(host,guest)) r=service.command(r.gameId,"ready",c(r,id).apply { ready=true })
        r=service.command(r.gameId,"start",c(r,host))
        val cmd=c(r,host).apply { saveHash="a".repeat(64) }
        service.command(r.gameId,"save",cmd); now+=31*60_000
        // A remote 404 may mean a PUT is still in flight. It must never close/reopen the room.
        assertEquals("STARTING",service.command(r.gameId,"read",LobbyCommand()).state)
        unreadable=true
        assertEquals("STARTING",service.command(r.gameId,"read",LobbyCommand()).state)
        assertThrows(LobbyFailure::class.java) { service.command(r.gameId,"complete",cmd) }
        assertEquals("STARTING",service.find(r.gameId)!!.state)
        assertEquals(cmd.saveHash,service.find(r.gameId)!!.saveHash)
        unreadable=false
        assertThrows(LobbyFailure::class.java) { service.command(r.gameId,"complete",cmd) }
    }
    @Test fun unregisteredTimedOutStartCannotBeRevived() {
        var r=join(create()); for (id in listOf(host,guest)) r=service.command(r.gameId,"ready",c(r,id).apply { ready=true })
        r=service.command(r.gameId,"start",c(r,host)); now+=31*60_000
        assertEquals("CLOSED",service.command(r.gameId,"read",LobbyCommand()).state)
        assertThrows(LobbyFailure::class.java) { service.command(r.gameId,"save",c(r,host).apply { saveHash="a".repeat(64) }) }
    }
    @Test fun configurationCannotPointGameSavesAtTheRoomApi() {
        val r=create()
        val update=configuration(r).apply {
            gameServerUrl="https://rooms.example/unciv-lobby"
            setup=JsonReader().parse(setup).apply { get("gameParameters").set("multiplayerServerUrl",gameServerUrl) }.toJson(JsonWriter.OutputType.json)
        }
        assertThrows(LobbyFailure::class.java) { service.command(r.gameId,"configuration",update) }
        assertEquals(gameServer,service.find(r.gameId)!!.gameServerUrl)
        assertEquals(0L,service.find(r.gameId)!!.configurationVersion)
        update.createKey=UUID.randomUUID().toString()
        assertThrows(LobbyFailure::class.java) { service.create(update) }
    }
    @Test fun startRegistrationRejectsDifferentSaveAndIncompleteFullSave() {
        var r=join(create()); for (id in listOf(host,guest)) r=service.command(r.gameId,"ready",c(r,id).apply { ready=true })
        r=service.command(r.gameId,"start",c(r,host))
        val cmd=c(r,host).apply { saveHash=LobbyProtocol.sha256("bad save") }
        service.command(r.gameId,"save",cmd)
        assertThrows(LobbyFailure::class.java) { service.command(r.gameId,"save",c(r,host).apply { saveHash="b".repeat(64) }) }
        folder.resolve(r.gameId).writeText("bad save")
        assertThrows(LobbyFailure::class.java) { service.command(r.gameId,"complete",cmd) }
    }
}
