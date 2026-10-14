package com.unciv.app.server

import com.unciv.logic.multiplayer.lobby.protocol.*
import com.badlogic.gdx.utils.JsonReader
import com.mongodb.MongoWriteException
import com.mongodb.client.MongoClients
import com.mongodb.client.model.Filters.*
import com.mongodb.client.model.Indexes
import org.bson.Document
import java.util.UUID
import java.util.Base64
import java.util.zip.GZIPInputStream

class LobbyFailure(val status: Int, override val message: String) : RuntimeException(message)

/** One document per game ID. Saves remain on the independently deployed game server. */
class LobbyService(uri: String, database: String, val gameServerUrl: String,
                   private val loadGameSave: (LobbyRoom) -> String? = RemoteGameSave(gameServerUrl)::read,
                   private val clock: () -> Long = System::currentTimeMillis) : AutoCloseable {
    private val mongo = MongoClients.create(uri)
    private val rooms = mongo.getDatabase(database).getCollection("lobbies")
    private val readAsset: (String) -> ByteArray? = { javaClass.classLoader.getResourceAsStream(it)?.use { s -> s.readBytes() } }
    val rulesHash = LobbyProtocol.fingerprint(readAsset)
    init {
        mongo.getDatabase(database).runCommand(Document("ping", 1))
        rooms.createIndex(Indexes.compoundIndex(Indexes.ascending("state"), Indexes.ascending("nextMaintenanceAt")))
        rooms.find(exists("state", false)).batchSize(100).iterator().use { cursor ->
            for (doc in cursor) {
                val r = decode(doc)
                rooms.replaceOne(and(eq("_id", r.gameId), eq("version", r.version)), document(r))
            }
        }
        // All old sockets died with the process. Do not carry their ready state into a new one.
        rooms.find(eq("state", "WAITING")).batchSize(100).iterator().use { cursor ->
            for (doc in cursor) mutate(doc.getString("_id")) { r ->
                expire(r)
                r.seats.filter { !it.manual && it.playerId != r.hostId && it.ready }.forEach { it.ready = false; it.revision++ }
            }
        }
    }
    override fun close() = mongo.close()
    private fun fail(message: String, status: Int = 409): Nothing = throw LobbyFailure(status, message)
    private fun uuid(id: String) = try { UUID.fromString(id).toString() == id } catch (_: Exception) { false }
    private fun decode(doc: Document) = LobbyProtocol.json().fromJson(LobbyRoom::class.java, doc.getString("payload"))
    private fun document(room: LobbyRoom): Document {
        val host = room.seats.firstOrNull { it.playerId == room.hostId }
        val deadline = (host?.lastSeen ?: 0L) + 30 * 60_000L
        val next = when (room.state) {
            "WAITING" -> minOf(deadline, room.seats.filter { !it.manual && it.playerId.isNotEmpty() && it.playerId != room.hostId }
                .minOfOrNull { it.lastSeen + 5 * 60_000L } ?: deadline)
            "STARTING" -> if (room.saveHash.isEmpty() || deadline > clock()) deadline
                else maxOf(room.nextMaintenanceAt, clock() + 30_000L)
            else -> Long.MAX_VALUE
        }
        return Document("_id", room.gameId).append("version", room.version)
            .append("state", room.state).append("nextMaintenanceAt", next)
            .append("payload", LobbyProtocol.json().toJson(room))
    }
    fun find(id: String): LobbyRoom? = rooms.find(eq("_id", id)).first()?.let { decode(it) }
    private fun checkRules(c: LobbyCommand) { if (c.rulesHash != rulesHash) fail("The room rules differ from this app. Update the app or server.") }
    private fun validateConfiguration(c: LobbyCommand): com.badlogic.gdx.utils.JsonValue {
        if (c.baseRuleset !in LobbyProtocol.rulesets) fail("Rooms support built-in rules only", 400)
        val setup = try { JsonReader().parse(c.setup) } catch (_: Exception) { fail("Invalid game setup", 400) }
        val parameters = setup.get("gameParameters") ?: fail("Missing game parameters", 400)
        val map = setup.get("mapParameters") ?: fail("Missing map parameters", 400)
        if (c.gameServerUrl != gameServerUrl || parameters.getString("multiplayerServerUrl", "") != gameServerUrl)
            fail("The game server differs from the room API configuration.", 400)
        if ((parameters.get("mods")?.size ?: 0) != 0 || map.getString("name", "").isNotEmpty()) fail("Rooms require generated maps without mods", 400)
        if (!parameters.getBoolean("isOnlineMultiplayer", false) || parameters.getString("baseRuleset", LobbyProtocol.rulesets[1]) != c.baseRuleset)
            fail("Invalid room configuration", 400)
        val players = parameters.get("players") ?: fail("Missing players", 400)
        if (players.size != c.seats.size || c.seats.size !in 2..64 || !c.seats.first().human) fail("Invalid room capacity", 400)
        for ((index, seat) in c.seats.withIndex())
            if (seat.human != (players[index].getString("playerType", "AI") == "Human")) fail("Seat type differs from setup", 400)
        if (parameters.getBoolean("randomNumberOfPlayers", false) || players.any { it.getString("chosenCiv", "Random") == "Spectator" })
            fail("Rooms require a fixed player count without spectators", 400)
        return setup
    }
    fun create(c: LobbyCommand): LobbyRoom {
        if (!uuid(c.playerId) || !uuid(c.createKey)) fail("Invalid player or creation ID", 400)
        checkRules(c)
        val setup = validateConfiguration(c)
        val players = setup.get("gameParameters").get("players")
        val gameId = UUID.nameUUIDFromBytes((c.playerId + ":" + c.createKey).toByteArray()).toString()
        find(gameId)?.let { return it }
        val room = LobbyRoom().apply {
            this.gameId = gameId; hostId = c.playerId; createKey = c.createKey
            gameServerUrl = this@LobbyService.gameServerUrl
            this.setup = c.setup; baseRuleset = c.baseRuleset; rulesHash = this@LobbyService.rulesHash
            seats = c.seats
        }
        var hostBound = false
        for ((index, seat) in room.seats.withIndex()) {
            val player = players[index]
            if (seat.human != (player.getString("playerType", "AI") == "Human")) fail("Seat type differs from setup", 400)
            seat.seatId = index; seat.manual = false; seat.playerId = ""; seat.occupancy = ""; seat.ready = false; seat.lastSeen = 0
            if (seat.human && !hostBound) {
                seat.playerId = c.playerId; seat.occupancy = UUID.randomUUID().toString(); seat.lastSeen = clock(); hostBound = true
                seat.ready = true
            } else if (seat.human) seat.civilization = "Random"
            if (!seat.human || seat.playerId.isNotEmpty()) {
                if (seat.civilization != player.getString("chosenCiv", "Random")) fail("Civilization differs from setup", 400)
            }
        }
        room.seats.forEach { checkCiv(room,it,it.civilization) }
        try { rooms.insertOne(document(room)) } catch (e: MongoWriteException) { if (e.error.code != 11000) throw e }
        return find(gameId)!!
    }
    private fun mutate(id: String, action: (LobbyRoom) -> Unit): LobbyRoom {
        repeat(100) {
            val room = find(id) ?: fail("Room not found", 404)
            val before = room.version
            val beforePayload = LobbyProtocol.json().toJson(room)
            action(room)
            room.seats.first { it.playerId == room.hostId }.ready = true
            if (LobbyProtocol.json().toJson(room) == beforePayload) return room
            room.version++
            if (rooms.replaceOne(and(eq("_id", id), eq("version", before)), document(room)).matchedCount == 1L) return room
        }
        fail("Room is busy. Please retry.")
    }
    private fun lastSeen(seat: LobbySeat, presence: Map<Pair<String,String>,Long>) =
        maxOf(seat.lastSeen, presence[seat.playerId to seat.occupancy] ?: 0L)
    private fun recordPresence(room: LobbyRoom, presence: Map<Pair<String,String>,Long>) {
        if (room.state !in listOf("WAITING","STARTING")) return
        room.seats.filter { !it.manual && it.playerId.isNotEmpty() }.forEach { it.lastSeen = lastSeen(it,presence) }
    }
    private fun expire(room: LobbyRoom, presence: Map<Pair<String,String>,Long> = emptyMap()) {
        if (room.state == "WAITING") {
            val host = room.seats.first { it.playerId == room.hostId }
            if (clock() - lastSeen(host,presence) >= 30 * 60_000) { room.state = "CLOSED"; room.reason = "Host timed out"; return }
            room.seats.filter { it.human && !it.manual && it.playerId.isNotEmpty() && it.playerId != room.hostId && clock() - lastSeen(it,presence) >= 5 * 60_000 }
                .forEach { clear(it) }
        } else if (room.state == "STARTING") {
            val host = room.seats.first { it.playerId == room.hostId }
            if (clock() - lastSeen(host,presence) >= 30 * 60_000) {
                if (room.saveHash.isEmpty()) { room.state = "CLOSED"; room.reason = "Start timed out before upload was registered" }
            }
        }
    }
    fun maintain(id: String, presence: Map<Pair<String,String>,Long> = emptyMap()): LobbyRoom = mutate(id) {
        recordPresence(it,presence); expire(it)
    }
    fun dueRooms(limit: Int = 100): List<String> = listOf("WAITING","STARTING").flatMap { state ->
        rooms.find(and(eq("state", state),lte("nextMaintenanceAt", clock()))).limit(limit).map { it.getString("_id") }.toList()
    }
    fun verifyStart(room: LobbyRoom): Boolean = try {
        val body = loadGameSave(room)
        body != null && validSave(room, body)
    } catch (_: Exception) { false }
    fun completionRoom(id: String, c: LobbyCommand): LobbyRoom {
        val r = find(id) ?: fail("Room not found",404)
        host(r,c)
        if (r.state !in listOf("STARTING","STARTED") || c.startOperation != r.startOperation || c.frozenVersion != r.frozenVersion)
            fail("Start operation no longer matches this room")
        if (r.saveHash.isEmpty() || c.saveHash != r.saveHash) fail("Start save fingerprint does not match")
        return r
    }
    fun maintainStart(id: String, original: LobbyRoom, verified: Boolean): LobbyRoom = mutate(id) { r ->
        if (r.state != "STARTING" || r.startOperation != original.startOperation || r.saveHash != original.saveHash) return@mutate
        if (verified) { r.state = "STARTED"; r.reason = "" }
        else {
            r.reason = "Waiting for host to recover the original save"
            r.nextMaintenanceAt = clock() + r.maintenanceDelayMillis
            r.maintenanceDelayMillis = (r.maintenanceDelayMillis * 2).coerceAtMost(10 * 60_000L)
        }
    }
    fun disconnected(id: String, player: String, occupancy: String, lastSignal: Long? = null): LobbyRoom = mutate(id) { r ->
        if (r.state in listOf("WAITING","STARTING"))
            r.seats.firstOrNull { !it.manual && it.playerId == player && it.occupancy == occupancy }?.let {
                if (lastSignal != null) it.lastSeen = maxOf(it.lastSeen,lastSignal)
                if (r.state == "WAITING" && player != r.hostId && it.ready) { it.ready = false; it.revision++ }
            }
    }
    private fun syncSeats(r: LobbyRoom) {
        val setup = JsonReader().parse(r.setup)
        val players = setup.get("gameParameters").get("players")
        for (s in r.seats) for ((key, value) in mapOf("playerType" to if (s.human) "Human" else "AI",
            "playerId" to s.playerId, "chosenCiv" to s.civilization)) {
            players[s.seatId].remove(key); players[s.seatId].addChild(key, com.badlogic.gdx.utils.JsonValue(value))
        }
        r.setup = setup.toJson(com.badlogic.gdx.utils.JsonWriter.OutputType.json)
    }
    private fun clear(s: LobbySeat) { s.manual = false; s.playerId = ""; s.occupancy = ""; s.ready = false; s.civilization = "Random"; s.lastSeen = 0; s.revision++ }
    private fun waiting(r: LobbyRoom) { if (r.state != "WAITING") fail("Room is no longer waiting") }
    private fun member(r: LobbyRoom, c: LobbyCommand) = r.seats.firstOrNull { !it.manual && it.playerId == c.playerId && it.occupancy == c.occupancy && c.occupancy.isNotEmpty() }
        ?: fail("You have left this room or were removed. Open the invitation to rejoin.", 410)
    private fun host(r: LobbyRoom, c: LobbyCommand) { member(r, c); if (r.hostId != c.playerId) fail("Only the host can do this", 403) }
    private fun controlledSeat(r: LobbyRoom, c: LobbyCommand): LobbySeat {
        val own = member(r,c)
        if (c.seatId < 0 || c.seatId == own.seatId) return own
        host(r,c)
        val seat = r.seats.firstOrNull { it.seatId == c.seatId && it.manual && it.human }
            ?: fail("You can choose only your own civilization",403)
        if (seat.occupancy != c.targetOccupancy || c.configurationVersion != r.configurationVersion)
            fail("This seat has changed. Refresh and retry.")
        return seat
    }
    private fun checkCiv(r: LobbyRoom, s: LobbySeat, civ: String) {
        if (civ == "Random") return
        if (civ !in LobbyProtocol.civilizations(r.baseRuleset, readAsset)) fail("Civilization is unavailable", 400)
        val allowDuplicates = JsonReader().parse(r.setup).get("gameParameters").getBoolean("allowDuplicateCivilizations", false)
        if (!allowDuplicates && r.seats.any { it !== s && it.civilization == civ }) fail("Civilization is already selected")
    }
    private fun updateConfiguration(r: LobbyRoom, c: LobbyCommand) {
        waiting(r); host(r,c); checkRules(c)
        if (c.configurationVersion != r.configurationVersion) fail("Room settings changed. Refresh and retry.")
        val setup = validateConfiguration(c)
        val existingIds = c.seats.filter { it.seatId >= 0 }.map { it.seatId }
        if (existingIds.distinct().size != existingIds.size || existingIds.any { id -> r.seats.none { it.seatId == id } })
            fail("Invalid room seats", 400)
        val hostSeat = r.seats.first { it.playerId == r.hostId }
        if (c.seats.first().seatId != hostSeat.seatId || !c.seats.first().human) fail("Cannot remove or replace the host")
        for (occupied in r.seats.filter { it.playerId.isNotEmpty() })
            if (c.seats.none { it.seatId == occupied.seatId && it.human })
                fail("Remove the player before changing an occupied seat")
        val nations = LobbyProtocol.civilizations(c.baseRuleset, readAsset)
        val players = setup.get("gameParameters").get("players")
        val next = ArrayList(c.seats.mapIndexed { index, requested ->
            val old = r.seats.firstOrNull { it.seatId == requested.seatId }
            LobbySeat().apply {
                seatId = index; human = requested.human
                if (human && old?.playerId?.isNotEmpty() == true) {
                    playerId = old.playerId; occupancy = old.occupancy; lastSeen = old.lastSeen
                    manual = old.manual
                    civilization = old.civilization.takeIf { it == "Random" || it in nations } ?: "Random"
                } else civilization = if (human) "Random" else requested.civilization
                revision = (old?.revision ?: 0L) + 1L
                val player = players[index]
                for ((key,value) in mapOf("playerId" to playerId, "chosenCiv" to civilization, "playerType" to if(human) "Human" else "AI")) {
                    player.remove(key); player.addChild(key,com.badlogic.gdx.utils.JsonValue(value))
                }
            }
        })
        r.seats = next; r.baseRuleset = c.baseRuleset
        r.setup = setup.toJson(com.badlogic.gdx.utils.JsonWriter.OutputType.json)
        next.forEach { checkCiv(r,it,it.civilization) }
        r.configurationVersion++
    }
    fun command(id: String, verb: String, c: LobbyCommand, verifiedSave: Boolean? = null,
                presence: Map<Pair<String,String>,Long> = emptyMap()): LobbyRoom {
        // Persist expiry before rejecting the requested action.
        mutate(id) { expire(it,presence) }
        val verified = if (verb == "complete" && verifiedSave == null) {
            val r = completionRoom(id,c)
            r.state == "STARTED" || verifyStart(r)
        } else verifiedSave == true
        return mutate(id) { r ->
            when (verb) {
                "configuration" -> updateConfiguration(r,c)
                "resume" -> if (r.state != "CLOSED") member(r,c).lastSeen = clock()
                "recoverJoin" -> {
                    checkRules(c)
                    if (c.gameServerUrl != r.gameServerUrl) fail("The invitation game server differs from this room.",400)
                    val receipt = r.joinReceipts.firstOrNull { it.requestId == c.originalRequestId && it.playerId == c.playerId }
                        ?: fail("The join result is unavailable. Open the invitation to join again.",410)
                    if (r.state == "CLOSED" || r.seats.none { it.playerId == c.playerId && it.occupancy == receipt.occupancy })
                        fail("You have left this room or were removed. Open the invitation to rejoin.",410)
                    r.seats.first { it.occupancy == receipt.occupancy }.lastSeen = clock()
                }
                "join" -> {
                    checkRules(c)
                    if (c.gameServerUrl != r.gameServerUrl) fail("The invitation game server differs from this room.",400)
                    val receipt = r.joinReceipts.firstOrNull { it.requestId == c.requestId && c.requestId.isNotEmpty() }
                    if (receipt != null) {
                        if (receipt.playerId != c.playerId || receipt.requestHash != LobbyProtocol.sha256(LobbyProtocol.json().toJson(c)))
                            fail("This room request conflicts with a previous request. Please retry.",400)
                        if (r.state == "CLOSED" || r.seats.none { it.playerId == c.playerId && it.occupancy == receipt.occupancy })
                            fail("You have left this room or were removed. Open the invitation to rejoin.",410)
                        return@mutate
                    }
                    val existing = r.seats.firstOrNull { it.playerId == c.playerId && it.human }
                    if (existing?.manual == true) fail("This player was added manually. Ask the host to remove them before joining by invitation.")
                    if (existing != null && r.state != "CLOSED") existing.lastSeen = clock()
                    else {
                        waiting(r); if (!uuid(c.playerId)) fail("Invalid player ID", 400)
                        val s = r.seats.firstOrNull { it.human && it.playerId.isEmpty() } ?: fail("Room is full")
                        s.playerId = c.playerId; s.occupancy = UUID.randomUUID().toString(); s.lastSeen = clock()
                    }
                    if (c.requestId.isNotEmpty()) {
                        r.joinReceipts.removeAll { clock() - it.createdAt >= 30 * 60_000L }
                        while (r.joinReceipts.size >= 64) r.joinReceipts.removeAt(0)
                        r.joinReceipts.add(LobbyJoinReceipt().apply {
                            requestId = c.requestId; playerId = c.playerId; requestHash = LobbyProtocol.sha256(LobbyProtocol.json().toJson(c))
                            occupancy = r.seats.first { it.playerId == c.playerId }.occupancy; createdAt = clock()
                        })
                    }
                }
                "leave", "close" -> {
                    if (verb == "leave" && r.state == "CLOSED") return@mutate
                    waiting(r); val s = member(r,c)
                    if (verb == "close" || c.playerId == r.hostId) { host(r,c); r.state = "CLOSED"; r.reason = "Host closed the room" }
                    else clear(s)
                }
                "kick" -> {
                    waiting(r); host(r,c)
                    val s = r.seats.firstOrNull { it.seatId == c.seatId && it.occupancy == c.targetOccupancy && it.playerId.isNotEmpty() }
                        ?: fail("This seat has changed. Refresh and retry.")
                    if (s.playerId == r.hostId) fail("Cannot remove the host")
                    clear(s); s.human = true; r.configurationVersion++
                    syncSeats(r)
                }
                "manualPlayer" -> {
                    waiting(r); host(r,c)
                    val s = r.seats.firstOrNull { it.seatId == c.seatId && it.human && it.playerId != r.hostId }
                        ?: fail("This seat has changed. Refresh and retry.")
                    if (c.configurationVersion != r.configurationVersion || s.revision != c.seatRevision || s.occupancy != c.targetOccupancy ||
                        (s.playerId.isNotEmpty() && !s.manual)) fail("This seat has changed. Refresh and retry.")
                    if (!uuid(c.manualPlayerId)) fail("Invalid player ID",400)
                    if (r.seats.any { it !== s && it.playerId == c.manualPlayerId }) fail("Player ID already used!",400)
                    if (s.playerId != c.manualPlayerId) {
                        s.playerId = c.manualPlayerId; s.manual = true; s.occupancy = UUID.randomUUID().toString()
                        s.ready = false; s.lastSeen = 0; s.revision++; r.configurationVersion++
                        syncSeats(r)
                    }
                }
                "civilization" -> {
                    waiting(r); val s = controlledSeat(r,c); if (s.seatId != c.seatId) fail("You can choose only your own civilization",403)
                    if (s.revision != c.seatRevision) fail("Your seat changed. Refresh and retry.")
                    checkCiv(r,s,c.civilization)
                    if (s.civilization != c.civilization) { s.civilization = c.civilization; s.ready = false; s.revision++; syncSeats(r) }
                }
                "ready" -> {
                    waiting(r); val s = controlledSeat(r,c)
                    if (c.configurationVersion != r.configurationVersion) fail("Room settings changed. Refresh and retry.")
                    if (s.revision != c.seatRevision) fail("Your seat changed. Refresh and retry.")
                    val ready = s.playerId == r.hostId || c.ready
                    if (s.ready != ready) { s.ready = ready; s.revision++ }
                }
                "start" -> {
                    host(r,c); checkRules(c)
                    if (r.state == "STARTING" || r.state == "STARTED") return@mutate
                    waiting(r)
                    if (c.configurationVersion >= 0L && c.configurationVersion != r.configurationVersion)
                        fail("Room settings changed. Refresh and retry.")
                    if (r.seats.any { it.human && (it.playerId.isEmpty() || !it.ready) }) fail("All human seats must be occupied and ready")
                    val setup = JsonReader().parse(r.setup)
                    val map = setup.get("mapParameters")
                    if (map.getLong("seed",0L) == 0L) {
                        map.remove("seed"); map.addChild("seed", com.badlogic.gdx.utils.JsonValue(clock().coerceAtLeast(1L)))
                        r.setup = setup.toJson(com.badlogic.gdx.utils.JsonWriter.OutputType.json)
                    }
                    r.state = "STARTING"; r.startOperation = UUID.randomUUID().toString(); r.frozenVersion = r.version + 1
                }
                "save", "complete" -> {
                    host(r,c)
                    if (r.state !in listOf("STARTING", "STARTED") || c.startOperation != r.startOperation || c.frozenVersion != r.frozenVersion)
                        fail("Start operation no longer matches this room")
                    if (verb == "save") {
                        if (!c.saveHash.matches(Regex("[a-f0-9]{64}"))) fail("Invalid save fingerprint",400)
                        if (r.saveHash.isNotEmpty() && r.saveHash != c.saveHash) fail("Recover the original save instead of generating another one")
                        r.saveHash = c.saveHash
                    } else {
                        if (c.saveHash != r.saveHash || r.saveHash.isEmpty()) fail("Start save fingerprint does not match")
                        if (r.state == "STARTED") return@mutate
                        if (!verified) fail("The full game save has not been verified on the game server")
                        r.state = "STARTED"; r.reason = ""
                    }
                }
                else -> fail("Unknown room operation",400)
            }
            recordPresence(r,presence)
            if (r.state != "CLOSED") r.seats.firstOrNull { it.playerId == c.playerId && it.occupancy == c.occupancy }?.lastSeen = clock()
        }
    }
    private fun validSave(r: LobbyRoom, body: String): Boolean = try {
        run {
            val plain = GZIPInputStream(Base64.getDecoder().decode(body).inputStream()).use { it.readNBytes(128 * 1024 * 1024) }.toString(Charsets.UTF_8)
            val data = JsonReader().parse(plain)
            val checksum = data.getString("checksum", "")
            val parameters = data.get("gameParameters")
            val players = parameters.get("players")
            LobbyProtocol.saveFingerprint(plain) == r.saveHash &&
                parameters.getBoolean("isOnlineMultiplayer",false) && parameters.getString("multiplayerServerUrl", "") == r.gameServerUrl &&
                parameters.getString("baseRuleset", LobbyProtocol.rulesets[1]) == r.baseRuleset &&
                checksum.isNotEmpty() && data.getString("gameId") == r.gameId && players.size == r.seats.size && r.seats.all { s ->
                val p = players[s.seatId]
                p.getString("playerId", "") == s.playerId && (p.getString("playerType", "AI") == "Human") == s.human &&
                    (s.civilization == "Random" || p.getString("chosenCiv", "Random") == s.civilization)
            } && (data.get("tileMap")?.get("tileList")?.size ?: 0) > 0 &&
                r.seats.filter { it.human }.all { seat -> data.get("civilizations").any { civ -> civ.getString("playerId", "") == seat.playerId } }
        }
    } catch (_: Exception) { false }
}
