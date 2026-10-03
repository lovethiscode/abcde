package com.unciv.app.server

import com.unciv.logic.multiplayer.lobby.protocol.*
import com.badlogic.gdx.utils.JsonReader
import com.mongodb.MongoWriteException
import com.mongodb.client.MongoClients
import com.mongodb.client.model.Filters.*
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
    init { mongo.getDatabase(database).runCommand(Document("ping", 1)) }
    override fun close() = mongo.close()
    private fun fail(message: String, status: Int = 409): Nothing = throw LobbyFailure(status, message)
    private fun uuid(id: String) = try { UUID.fromString(id).toString() == id } catch (_: Exception) { false }
    private fun decode(doc: Document) = LobbyProtocol.json().fromJson(LobbyRoom::class.java, doc.getString("payload"))
    private fun document(room: LobbyRoom) = Document("_id", room.gameId).append("version", room.version)
        .append("payload", LobbyProtocol.json().toJson(room))
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
        if (players.size != c.seats.size || c.seats.size !in 2..64 || c.seats.none { it.human }) fail("Invalid room capacity", 400)
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
            seat.seatId = index; seat.playerId = ""; seat.occupancy = ""; seat.ready = false; seat.lastSeen = 0
            if (seat.human && !hostBound) {
                seat.playerId = c.playerId; seat.occupancy = UUID.randomUUID().toString(); seat.lastSeen = clock(); hostBound = true
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
            action(room)
            room.version++
            if (rooms.replaceOne(and(eq("_id", id), eq("version", before)), document(room)).matchedCount == 1L) return room
        }
        fail("Room is busy. Please retry.")
    }
    private fun expire(room: LobbyRoom) {
        if (room.state == "WAITING") {
            val host = room.seats.first { it.playerId == room.hostId }
            if (clock() - host.lastSeen >= 30 * 60_000) { room.state = "CLOSED"; room.reason = "Host timed out"; return }
            room.seats.filter { it.human && it.playerId.isNotEmpty() && it.playerId != room.hostId && clock() - it.lastSeen >= 5 * 60_000 }
                .forEach { clear(it) }
        } else if (room.state == "STARTING") {
            val host = room.seats.first { it.playerId == room.hostId }
            if (clock() - host.lastSeen >= 30 * 60_000) {
                if (room.saveHash.isEmpty()) { room.state = "CLOSED"; room.reason = "Start timed out before upload was registered" }
                else {
                    // A 404 or failed read cannot prove that an external upload is no longer in flight.
                    val body = try { loadGameSave(room) } catch (_: LobbyFailure) { null }
                    if (body != null && validSave(room, body)) { room.state = "STARTED"; room.reason = "" }
                    else room.reason = "Waiting for host to recover the original save"
                }
            }
        }
    }
    private fun clear(s: LobbySeat) { s.playerId = ""; s.occupancy = ""; s.ready = false; s.civilization = "Random"; s.lastSeen = 0; s.revision++ }
    private fun waiting(r: LobbyRoom) { if (r.state != "WAITING") fail("Room is no longer waiting") }
    private fun member(r: LobbyRoom, c: LobbyCommand) = r.seats.firstOrNull { it.playerId == c.playerId && it.occupancy == c.occupancy && c.occupancy.isNotEmpty() }
        ?: fail("You have left this room or were removed. Open the invitation to rejoin.")
    private fun host(r: LobbyRoom, c: LobbyCommand) { member(r, c); if (r.hostId != c.playerId) fail("Only the host can do this", 403) }
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
    fun command(id: String, verb: String, c: LobbyCommand): LobbyRoom {
        // Persist expiry before rejecting the requested action.
        mutate(id) { expire(it) }
        return mutate(id) { r ->
            when (verb) {
                "configuration" -> updateConfiguration(r,c)
                "read" -> if (c.occupancy.isNotEmpty() && r.state != "CLOSED") r.seats.firstOrNull { it.playerId == c.playerId && it.occupancy == c.occupancy }?.let { it.lastSeen = clock() }
                "join" -> {
                    checkRules(c)
                    val existing = r.seats.firstOrNull { it.playerId == c.playerId && it.human }
                    if (existing != null && r.state != "CLOSED") existing.lastSeen = clock()
                    else {
                        waiting(r); if (!uuid(c.playerId)) fail("Invalid player ID", 400)
                        val s = r.seats.firstOrNull { it.human && it.playerId.isEmpty() } ?: fail("Room is full")
                        s.playerId = c.playerId; s.occupancy = UUID.randomUUID().toString(); s.lastSeen = clock()
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
                    clear(s)
                }
                "civilization" -> {
                    waiting(r); val s = member(r,c); if (s.seatId != c.seatId) fail("You can choose only your own civilization",403)
                    if (s.revision != c.seatRevision) fail("Your seat changed. Refresh and retry.")
                    checkCiv(r,s,c.civilization)
                    if (s.civilization != c.civilization) { s.civilization = c.civilization; s.ready = false; s.revision++ }
                }
                "ready" -> {
                    waiting(r); val s = member(r,c)
                    if (s.revision != c.seatRevision) fail("Your seat changed. Refresh and retry.")
                    if (s.ready != c.ready) { s.ready = c.ready; s.revision++ }
                }
                "start" -> {
                    host(r,c); checkRules(c)
                    if (r.state == "STARTING" || r.state == "STARTED") return@mutate
                    waiting(r)
                    if (c.configurationVersion >= 0L && c.configurationVersion != r.configurationVersion)
                        fail("Room settings changed. Refresh and retry.")
                    if (r.seats.any { it.human && (it.playerId.isEmpty() || !it.ready) }) fail("All human seats must be occupied and ready")
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
                        val body = loadGameSave(r)
                        if (body == null || !validSave(r,body)) fail("The full game save has not been verified on the game server")
                        r.state = "STARTED"; r.reason = ""
                    }
                }
                else -> fail("Unknown room operation",400)
            }
            if (r.state != "CLOSED") r.seats.firstOrNull { it.playerId == c.playerId && it.occupancy == c.occupancy }?.lastSeen = clock()
        }
    }
    private fun validSave(r: LobbyRoom, body: String): Boolean = try {
        if (LobbyProtocol.sha256(body) != r.saveHash) false
        else {
            val plain = GZIPInputStream(Base64.getDecoder().decode(body).inputStream()).use { it.readNBytes(128 * 1024 * 1024) }.toString(Charsets.UTF_8)
            val data = JsonReader().parse(plain)
            val checksum = data.getString("checksum", "")
            val withoutChecksum = JsonReader().parse(plain).apply { remove("checksum") }.toJson(com.badlogic.gdx.utils.JsonWriter.OutputType.json)
            val actualChecksum = Base64.getEncoder().encodeToString(java.security.MessageDigest.getInstance("SHA-1").digest(withoutChecksum.toByteArray(Charsets.UTF_8)))
            val parameters = data.get("gameParameters")
            val players = parameters.get("players")
            parameters.getBoolean("isOnlineMultiplayer",false) && parameters.getString("multiplayerServerUrl", "") == r.gameServerUrl &&
                parameters.getString("baseRuleset", LobbyProtocol.rulesets[1]) == r.baseRuleset &&
                checksum.isNotEmpty() && actualChecksum == checksum && data.getString("gameId") == r.gameId && players.size == r.seats.size && r.seats.all { s ->
                val p = players[s.seatId]
                p.getString("playerId", "") == s.playerId && (p.getString("playerType", "AI") == "Human") == s.human &&
                    (s.civilization == "Random" || p.getString("chosenCiv", "Random") == s.civilization)
            } && (data.get("tileMap")?.get("tileList")?.size ?: 0) > 0 &&
                r.seats.filter { it.human }.all { seat -> data.get("civilizations").any { civ -> civ.getString("playerId", "") == seat.playerId } }
        }
    } catch (_: Exception) { false }
}
