package com.unciv.logic.multiplayer.lobby.protocol

import com.badlogic.gdx.utils.Json
import com.badlogic.gdx.utils.JsonReader
import com.badlogic.gdx.utils.JsonWriter
import java.security.MessageDigest

// Plain wire objects shared with the standalone server, without the game engine dependency.
class LobbySeat {
    var seatId = 0
    var human = true
    var civilization = "Random"
    var playerId = ""
    var occupancy = ""
    var ready = false
    var revision = 0L
    var lastSeen = 0L
}

class LobbyRoom {
    var gameId = ""
    var hostId = ""
    var createKey = ""
    var state = "WAITING"
    var version = 0L
    var setup = ""
    var rulesHash = ""
    var baseRuleset = ""
    var seats = arrayListOf<LobbySeat>()
    var startOperation = ""
    var frozenVersion = 0L
    var saveHash = ""
    var writing = false
    var reason = ""
}

class LobbyCommand {
    var playerId = ""
    var occupancy = ""
    var createKey = ""
    var rulesHash = ""
    var setup = ""
    var baseRuleset = ""
    var seats = arrayListOf<LobbySeat>()
    var seatId = -1
    var targetOccupancy = ""
    var seatRevision = 0L
    var civilization = "Random"
    var ready = false
    var startOperation = ""
    var frozenVersion = 0L
    var saveHash = ""
}

object LobbyProtocol {
    fun json() = Json().apply {
        setOutputType(JsonWriter.OutputType.json)
        setUsePrototypes(false)
        setIgnoreUnknownFields(true)
        setElementType(LobbyRoom::class.java, "seats", LobbySeat::class.java)
        setElementType(LobbyCommand::class.java, "seats", LobbySeat::class.java)
    }
    fun sha256(value: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(value).joinToString("") { "%02x".format(it.toInt() and 255) }
    fun sha256(value: String) = sha256(value.toByteArray(Charsets.UTF_8))
    // Both built-in rulesets use the same versioned catalog. Changes to any gameplay JSON
    // invalidate the fingerprint, independently of app build numbers.
    val rulesets = listOf("Civ V - Vanilla", "Civ V - Gods & Kings")
    val ruleFiles = listOf("Beliefs", "Buildings", "CityStateTypes", "Difficulties", "Eras", "ModOptions",
        "Nations", "Policies", "Quests", "Ruins", "Speeds", "Specialists", "Techs", "Terrains",
        "TileImprovements", "TileResources", "UnitPromotions", "Units", "UnitTypes", "VictoryTypes", "GlobalUniques", "Events", "Personalities", "Religions", "UnitNameGroups")
    fun fingerprint(read: (String) -> ByteArray?): String = sha256(buildString {
        for (base in rulesets) for (name in ruleFiles) {
            val path = "jsons/$base/$name.json"
            append(path).append(':').append(read(path)?.let { sha256(it) } ?: "absent").append('\n')
        }
    })
    fun civilizations(base: String, read: (String) -> ByteArray?): Set<String> {
        val names = linkedSetOf<String>()
        for (ruleset in rulesets.take(if (base == rulesets[0]) 1 else 2)) {
            val bytes = read("jsons/$ruleset/Nations.json") ?: continue
            for (nation in JsonReader().parse(bytes.toString(Charsets.UTF_8))) {
                val name = nation.getString("name")
                if (!nation.has("cityStateType") && name !in listOf("Barbarians", "Spectator")) names.add(name)
            }
        }
        return names
    }
}
