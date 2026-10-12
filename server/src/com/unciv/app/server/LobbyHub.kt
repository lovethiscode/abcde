package com.unciv.app.server

import com.unciv.logic.multiplayer.lobby.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.Semaphore
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** A peer's identity is its connection generation; late callbacks cannot detach a replacement. */
class LobbyPeer(private val deliver: (LobbyMessage) -> Boolean, private val disconnect: () -> Unit) {
    var gameId = ""
    var playerId = ""
    var occupancy = ""
    @Volatile var lastSignal = System.currentTimeMillis()
    fun send(message: LobbyMessage) { if (!deliver(message)) disconnect() }
    fun stop() = disconnect()
}

class LobbyHub(private val service: LobbyService, private val clock: () -> Long = System::currentTimeMillis,
               private val checkTimeoutMillis: Long = 3_000L) : AutoCloseable {
    private class RoomState(var lastPresencePersisted: Long) {
        val mutex = Mutex()
        val peers = linkedMapOf<String, LobbyPeer>()
        var references = 0
        var published = ""
        var round: StartRound? = null
    }
    private class StartRound(val room: LobbyRoom, val host: LobbyPeer, val peers: List<LobbyPeer>) {
        val id = UUID.randomUUID().toString()
        val replies = peers.associateWith { CompletableDeferred<Unit>() }
        val result = CompletableDeferred<LobbyRoom>()
    }
    private val states = ConcurrentHashMap<String, RoomState>()
    private val maintenanceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val verificationSlots = Semaphore(4)
    private val verifying = ConcurrentHashMap.newKeySet<String>()
    val activeRoomCount get() = states.size
    override fun close() { maintenanceScope.cancel(); states.values.flatMap { it.peers.values }.forEach { it.stop() } }

    private suspend fun <T> inRoom(id: String, action: suspend (RoomState) -> T): T {
        val state = states.compute(id) { _, old -> (old ?: RoomState(clock())).also { it.references++ } }!!
        try { return state.mutex.withLock { action(state) } }
        finally {
            states.computeIfPresent(id) { _, current ->
                current.references--
                if (current.references == 0 && current.peers.isEmpty() && current.round == null) null else current
            }
        }
    }
    private fun identity(player: String, occupancy: String) = "$player:$occupancy"
    private fun presence(state: RoomState) = state.peers.values.associate { (it.playerId to it.occupancy) to it.lastSignal }
    private fun active(state: RoomState, peer: LobbyPeer) = state.peers[identity(peer.playerId, peer.occupancy)] === peer
    private fun requireActive(state: RoomState, peer: LobbyPeer, request: LobbyMessage) {
        val c = request.command!!
        if (peer.gameId != request.gameId || peer.playerId != c.playerId || peer.occupancy != c.occupancy || !active(state, peer))
            throw LobbyFailure(410,"You have left this room or were removed. Open the invitation to rejoin.")
    }
    private fun visible(room: LobbyRoom) = LobbyProtocol.json().toJson(LobbyProtocol.snapshot(room)).let {
        // Room version and lastSeen are persistence metadata, not reasons to redraw/broadcast.
        val data = com.badlogic.gdx.utils.JsonReader().parse(it)
        data.remove("version")
        data.get("seats").forEach { s -> s.remove("lastSeen") }
        data.toJson(com.badlogic.gdx.utils.JsonWriter.OutputType.json)
    }
    private fun publish(state: RoomState, room: LobbyRoom) {
        val signature = visible(room)
        val changed = signature != state.published
        state.published = signature
        val snapshot = LobbyProtocol.snapshot(room)
        val iterator = state.peers.iterator()
        while (iterator.hasNext()) {
            val peer = iterator.next().value
            if (room.state == "CLOSED" || room.seats.none { it.playerId == peer.playerId && it.occupancy == peer.occupancy }) {
                iterator.remove()
                peer.send(LobbyMessage().apply {
                    type = "terminated"; gameId = room.gameId; this.room = snapshot
                    code = if (room.state == "CLOSED") "ROOM_CLOSED" else "MEMBERSHIP_EXPIRED"
                    message = if (room.state == "CLOSED") room.reason else "You have left this room or were removed. Open the invitation to rejoin."
                })
            } else if (changed) peer.send(LobbyMessage().apply { type = "snapshot"; gameId = room.gameId; this.room = snapshot })
        }
    }
    private fun bind(state: RoomState, peer: LobbyPeer, room: LobbyRoom, c: LobbyCommand): LobbyRoom {
        if (peer.gameId.isNotEmpty() && peer.gameId != room.gameId) throw LobbyFailure(409,"Leave the current waiting room before joining another.")
        val seat = room.seats.firstOrNull { it.playerId == c.playerId } ?: throw LobbyFailure(410,"You have left this room or were removed. Open the invitation to rejoin.")
        val key = identity(seat.playerId, seat.occupancy)
        if (state.peers[key] !== peer) {
            val old = state.peers.remove(key)
            old?.send(LobbyMessage().apply { type = "terminated"; code = "CONNECTION_REPLACED"; message = "This room connection was replaced. Reopen the room to continue." })
            service.disconnected(room.gameId, seat.playerId, seat.occupancy,old?.lastSignal)
        }
        peer.gameId = room.gameId; peer.playerId = seat.playerId; peer.occupancy = seat.occupancy; peer.lastSignal = clock()
        state.peers[key] = peer
        return service.command(room.gameId,"resume",LobbyCommand().apply { playerId = peer.playerId; occupancy = peer.occupancy },presence = presence(state))
    }
    private fun result(peer: LobbyPeer, request: LobbyMessage, room: LobbyRoom) = peer.send(LobbyMessage().apply {
        type = "result"; requestId = request.requestId; gameId = room.gameId; this.room = LobbyProtocol.snapshot(room)
    })
    suspend fun handle(peer: LobbyPeer, request: LobbyMessage) {
        if (request.type == "checkReply") {
            if (peer.gameId.isEmpty() || request.gameId != peer.gameId) return
            inRoom(peer.gameId) { state ->
                state.round?.takeIf { it.id == request.checkId && active(state,peer) }?.replies?.get(peer)?.complete(Unit)
            }
            return
        }
        try {
            if (request.type != "command" || request.protocolVersion != LobbyProtocol.VERSION ||
                request.requestId.length !in 1..80 || request.action !in LobbyProtocol.actions || request.command == null)
                throw LobbyFailure(400,"Invalid room request")
            val c = request.command!!
            c.requestId = request.requestId
            val id = if (request.action == "create")
                UUID.nameUUIDFromBytes((c.playerId + ":" + c.createKey).toByteArray()).toString() else request.gameId
            if (runCatching { UUID.fromString(id).toString() }.getOrNull() != id) throw LobbyFailure(400,"Invalid room request")
            if (peer.gameId.isNotEmpty() && (peer.gameId != id || peer.playerId != c.playerId))
                throw LobbyFailure(409,"Leave the current waiting room before joining another.")
            when (request.action) {
                "heartbeat" -> inRoom(id) { state ->
                    requireActive(state,peer,request)
                    peer.lastSignal = clock()
                    peer.send(LobbyMessage().apply { type = "pong"; requestId = request.requestId })
                }
                "start" -> result(peer,request,start(peer,request))
                "complete" -> {
                    val frozen = inRoom(id) { state -> requireActive(state,peer,request); service.completionRoom(id,c) }
                    val verified = frozen.state == "STARTED" || withContext(Dispatchers.IO) { service.verifyStart(frozen) }
                    inRoom(id) { state ->
                        requireActive(state,peer,request)
                        val room = service.command(id,"complete",c,verified,presence(state))
                        result(peer,request,room); publish(state,room)
                    }
                }
                else -> inRoom(id) { state ->
                    val room = if (request.action == "create") service.create(c)
                        else {
                            if (peer.gameId.isNotEmpty() && !active(state,peer))
                                throw LobbyFailure(410,"You have left this room or were removed. Open the invitation to rejoin.")
                            if (request.action !in setOf("join", "recoverJoin", "resume")) requireActive(state,peer,request)
                            service.command(id,request.action,c,presence = presence(state))
                        }
                    val bound = if (request.action in setOf("create", "join", "recoverJoin", "resume") && room.state != "CLOSED") bind(state,peer,room,c) else room
                    peer.lastSignal = clock()
                    result(peer,request,bound)
                    publish(state,bound)
                }
            }
        } catch (e: CancellationException) { throw e }
        catch (e: LobbyFailure) {
            peer.send(LobbyMessage().apply {
                type = "result"; requestId = request.requestId; message = e.message
                code = when {
                    e.message.startsWith("The join result is unavailable") -> "JOIN_RESULT_UNKNOWN"
                    e.status == 410 -> "MEMBERSHIP_EXPIRED"
                    e.status == 404 -> "ROOM_NOT_FOUND"
                    e.message.contains("game server differs") -> "SERVER_MISMATCH"
                    e.message.contains("room rules differ") -> "RULES_MISMATCH"
                    else -> "COMMAND_FAILED"
                }
            })
        } catch (_: Exception) {
            try {
                peer.send(LobbyMessage().apply { type = "result"; requestId = request.requestId; code = "RETRY"; message = "Room request failed. Please retry." })
            } finally {
                if (peer.gameId.isNotEmpty()) inRoom(peer.gameId) { state -> state.peers.values.toList().forEach { it.stop() } }
            }
        }
    }
    private fun sameStart(a: LobbyRoom, b: LobbyRoom): Boolean = a.configurationVersion == b.configurationVersion &&
        a.seats.map { listOf(it.seatId,it.human,it.manual,it.playerId,it.occupancy,it.revision,it.ready) } ==
        b.seats.map { listOf(it.seatId,it.human,it.manual,it.playerId,it.occupancy,it.revision,it.ready) }

    private suspend fun start(peer: LobbyPeer, request: LobbyMessage): LobbyRoom {
        var owner = false
        var existing: LobbyRoom? = null
        val prepared = inRoom(request.gameId) { state ->
            requireActive(state,peer,request)
            val room = service.maintain(request.gameId,presence(state))
            publish(state,room)
            if (room.hostId != peer.playerId) throw LobbyFailure(403,"Only the host can do this")
            if (room.state in listOf("STARTING","STARTED")) {
                existing = service.command(room.gameId,"start",request.command!!,presence = presence(state))
                return@inRoom null
            }
            if (room.state != "WAITING" || room.seats.any { it.human && (it.playerId.isEmpty() || !it.ready) })
                throw LobbyFailure(409,"All human seats must be occupied and ready")
            state.round ?: run {
                val peers = room.seats.filter { it.human && !it.manual }.map { s -> state.peers[identity(s.playerId,s.occupancy)]
                    ?: throw LobbyFailure(409,"A player disconnected. Wait for them to reconnect and get ready, or remove them.") }
                StartRound(room,peer,peers).also {
                    state.round = it; owner = true
                    for (target in peers) target.send(LobbyMessage().apply { type = "check"; gameId = room.gameId; checkId = it.id })
                }
            }
        }
        existing?.let { return it }
        val round = checkNotNull(prepared)
        if (!owner) return round.result.await()
        try {
            try { withTimeout(checkTimeoutMillis) { round.replies.values.awaitAll() } }
            catch (_: TimeoutCancellationException) {
                inRoom(request.gameId) { state ->
                    for ((target,reply) in round.replies) if (!reply.isCompleted && active(state,target)) {
                        state.peers.remove(identity(target.playerId,target.occupancy))
                        service.disconnected(target.gameId,target.playerId,target.occupancy,target.lastSignal); target.stop()
                    }
                    publish(state,service.find(request.gameId)!!)
                }
                throw LobbyFailure(409,"A player disconnected. Wait for them to reconnect and get ready, or remove them.")
            }
            val frozen = inRoom(request.gameId) { state ->
                requireActive(state,peer,request)
                val latest = service.maintain(request.gameId,presence(state))
                if (!sameStart(round.room,latest) || round.peers.any { !active(state,it) })
                    throw LobbyFailure(409,"Room settings changed. Refresh and retry.")
                val room = service.command(request.gameId,"start",request.command!!,presence = presence(state))
                publish(state,room)
                room
            }
            round.result.complete(frozen)
            return frozen
        } catch (e: Exception) { round.result.completeExceptionally(e); throw e }
        finally { withContext(NonCancellable) { inRoom(request.gameId) { state -> if (state.round === round) state.round = null } } }
    }
    suspend fun detach(peer: LobbyPeer) {
        if (peer.gameId.isEmpty()) return
        inRoom(peer.gameId) { state ->
            if (!active(state,peer)) return@inRoom
            state.peers.remove(identity(peer.playerId,peer.occupancy))
            publish(state,service.disconnected(peer.gameId,peer.playerId,peer.occupancy,peer.lastSignal))
        }
    }
    suspend fun maintain() {
        for (id in states.keys.toList()) inRoom(id) { state ->
            val dead = state.peers.values.filter { clock() - it.lastSignal >= LobbyProtocol.CONNECTION_TIMEOUT_MILLIS }
            for (peer in dead) {
                state.peers.remove(identity(peer.playerId,peer.occupancy))
                publish(state,service.disconnected(id,peer.playerId,peer.occupancy,peer.lastSignal)); peer.stop()
            }
            if (state.peers.isNotEmpty() && clock() - state.lastPresencePersisted >= 60_000L) {
                try {
                    publish(state,service.maintain(id,presence(state)))
                    state.lastPresencePersisted = clock()
                } catch (e: Exception) {
                    state.peers.values.toList().forEach { it.stop() }
                    throw e
                }
            }
        }
        for (id in service.dueRooms()) {
            val room = inRoom(id) { state -> service.maintain(id,presence(state)).also { publish(state,it) } }
            if (room.state == "STARTING" && room.saveHash.isNotEmpty() && verifying.add(id)) {
                if (!verificationSlots.tryAcquire()) { verifying.remove(id); continue }
                maintenanceScope.launch {
                    try {
                        val verified = service.verifyStart(room) // No room lock is held across the external save request.
                        inRoom(id) { state -> publish(state,service.maintainStart(id,room,verified)) }
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { /* The indexed room remains due for the next maintenance cycle. */ }
                    finally { verifying.remove(id); verificationSlots.release() }
                }
            }
        }
    }
}
