package com.unciv.logic.multiplayer

import com.badlogic.gdx.Gdx
import com.unciv.UncivGame
import com.unciv.json.json
import com.unciv.logic.civilization.PlayerType
import com.unciv.logic.files.UncivFiles
import com.unciv.logic.multiplayer.lobby.*
import com.unciv.logic.multiplayer.lobby.protocol.*
import com.unciv.models.metadata.*
import com.unciv.models.ruleset.RulesetCache
import com.unciv.testing.BaseTestRunner
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

/** Uses the production reconnecting client against a real Linux, never a local lobby. */
@RunWith(BaseTestRunner::class)
class LobbyLoadIntegrationTest {
    private class Transport : LobbyTransport {
        val sockets = CopyOnWriteArrayList<LobbyConnection>()
        val roundTrips = CopyOnWriteArrayList<Double>()
        override suspend fun connect(url: String): LobbyConnection {
            val socket = KtorLobbyTransport().connect(url)
            sockets += socket
            val sent = ConcurrentHashMap<String,Long>()
            return object : LobbyConnection by socket {
                override suspend fun send(text: String) {
                    val message = LobbyProtocol.decode(text)
                    if (message.action == "heartbeat") sent[message.requestId] = System.nanoTime()
                    socket.send(text)
                }
                override suspend fun receive(): String = socket.receive().also {
                    val message = LobbyProtocol.decode(it)
                    if (message.type == "pong") sent.remove(message.requestId)?.let { time -> roundTrips += (System.nanoTime()-time)/1e6 }
                }
            }
        }
    }
    private class Member(val session: LobbySession, api: String) {
        val transport = Transport()
        val client = LobbyClient(api,transport)
        val room = AtomicReference<LobbyRoom>()
        val observations = CopyOnWriteArrayList<Pair<Long,LobbyRoom>>()
        @Volatile var connected = false
        @Volatile var disconnected = 0
        fun watch() {
            client.onRoom = { latest ->
                if (session.gameId.isNotEmpty()) check(latest.gameId == session.gameId)
                room.set(latest); observations += System.nanoTime() to latest
            }
            client.onConnection = { available,_ -> if (!available && connected) disconnected++; connected = available }
            client.watch(session,join = true)
        }
        fun command() = LobbyCommand().apply {
            val current = room.get()
            val own = current.seats.first { it.playerId == session.playerId }
            playerId = session.playerId; occupancy = own.occupancy; seatRevision = own.revision
            configurationVersion = current.configurationVersion
            gameServerUrl = session.gameServerUrl; rulesHash = LobbyClient.rulesHash()
        }
    }
    private suspend fun awaitCondition(test: () -> Boolean) = withTimeout(30_000) { while (!test()) delay(10) }
    @Test fun fortyProductionClientsKeepTenRoomsIsolatedAndRecoverConnections() = runBlocking<Unit> {
        val api = System.getenv("UNCIV_LOBBY_LOAD_SERVER")
        Assume.assumeNotNull(api)
        val duration = System.getenv("UNCIV_LOBBY_LOAD_SECONDS")?.toLong() ?: 1800L
        val directory = java.nio.file.Files.createTempDirectory("unciv-real-wss-load").toFile()
        RulesetCache.loadRulesets(noMods = true)
        UncivGame.Current = UncivGame().apply { files = UncivFiles(Gdx.files,directory.path); settings = GameSettings() }
        val setup = GameSetupInfo().apply {
            gameParameters.isOnlineMultiplayer = true; gameParameters.multiplayerServerUrl = "https://uncivserver.xyz"
            gameParameters.players = ArrayList(List(4) { Player(playerType = PlayerType.Human) })
        }
        val groups = ArrayList<List<Member>>()
        val latencies = CopyOnWriteArrayList<Double>()
        var uncertain = 0
        try {
            repeat(10) {
                val members = List(4) { Member(LobbySession().apply {
                    roomApiUrl = api!!; gameServerUrl = "https://uncivserver.xyz"; playerId = UUID.randomUUID().toString()
                },api!!) }
                groups += members
                members[0].session.apply { createKey = UUID.randomUUID().toString(); this.setup = json().toJson(setup) }
                members[0].watch(); awaitCondition { members[0].connected }
                val id = members[0].session.gameId
                for (guest in members.drop(1)) { guest.session.gameId = id; guest.watch() }
                awaitCondition { members.all { it.connected && it.room.get().seats.count { s -> s.playerId.isNotEmpty() } == 4 } }
            }
            // The same production watch loop must recover an actual closed WSS socket by RESUME.
            val interrupted = groups[0][1]
            val originalOccupancy = interrupted.session.occupancy
            val previousConnections = interrupted.transport.sockets.size
            interrupted.transport.sockets.last().close()
            awaitCondition { interrupted.connected && interrupted.transport.sockets.size > previousConnections }
            assertEquals(originalOccupancy,interrupted.session.occupancy)
            awaitCondition { groups.flatten().all { it.transport.roundTrips.isNotEmpty() } }
            val started = System.nanoTime()
            var round = 0
            while ((System.nanoTime()-started)/1_000_000_000 < duration) {
                round++
                for (members in groups) {
                    val guest = members[1]; val observer = members[0]
                    awaitCondition { guest.connected && observer.connected }
                    val seat = guest.room.get().seats.first { it.playerId == guest.session.playerId }.seatId
                    val civ = if (round%2 == 0) "Random" else "Rome"
                    val begin = System.nanoTime()
                    val connections = guest.transport.sockets.size
                    val result = try {
                        guest.client.command(guest.session.gameId,"civilization",guest.command().apply { seatId = seat; civilization = civ })
                    } catch (e: Exception) {
                        if (e is CancellationException || e is LobbyConnectionException) throw e
                        uncertain++
                        // Reconcile the full snapshot; do not replay the uncertain operation.
                        awaitCondition { guest.connected && guest.transport.sockets.size > connections }
                        continue
                    }
                    awaitCondition { observer.observations.any { (time,r) -> time >= begin && r.version >= result.version && r.seats[seat].civilization == civ } }
                    val visible = observer.observations.first { (time,r) -> time >= begin && r.version >= result.version && r.seats[seat].civilization == civ }.first
                    latencies += (visible-begin)/1e6
                    assertEquals(members.map { it.session.playerId }.toSet(),result.seats.map { it.playerId }.toSet())
                }
                if (round%6 == 0) println("WSS soak: ${((System.nanoTime()-started)/1_000_000_000)} seconds, ${latencies.size} confirmed operations")
                delay(minOf(10_000,(duration*1000-(System.nanoTime()-started)/1_000_000).coerceAtLeast(0)))
            }
            awaitCondition { groups.flatten().all { it.connected } }
            fun percentile(values: List<Double>,ratio: Double) = values.sorted()[(ratio*(values.size-1)).toInt()]
            val rtt = groups.flatMap { g -> g.flatMap { it.transport.roundTrips } }
            val p95 = percentile(latencies,.95)
            if (percentile(rtt,.95) <= 200) assertTrue("Push P95 must be at most 1 second",p95 <= 1000)
            val report = mapOf("server" to api,"durationSeconds" to duration,"rooms" to 10,"logicalConnections" to 40,
                "confirmedOperations" to latencies.size,"unconfirmedOperationsReconciledWithoutReplay" to uncertain,
                "pushP50Ms" to percentile(latencies,.5),"pushP95Ms" to p95,
                "heartbeatRoundTripP50Ms" to percentile(rtt,.5),"heartbeatRoundTripP95Ms" to percentile(rtt,.95),
                "connectionsOpened" to groups.sumOf { g -> g.sumOf { it.transport.sockets.size } },
                "disconnects" to groups.sumOf { g -> g.sumOf { it.disconnected } },"injectedDisconnectRecovered" to true,
                "gameIds" to groups.map { it[0].session.gameId })
            System.getenv("UNCIV_LOBBY_EVIDENCE")?.let { File(it).apply {
                mkdirs();resolve("load-report.json").writeText(LobbyProtocol.json().toJson(report))
            } }
            println("WSS soak result: $report")
        } finally {
            for (members in groups) {
                val host = members[0]
                if (host.connected) runCatching { host.client.command(host.session.gameId,"close",host.command()) }
                members.forEach { it.client.close() }
            }
            directory.deleteRecursively()
        }
    }
}
