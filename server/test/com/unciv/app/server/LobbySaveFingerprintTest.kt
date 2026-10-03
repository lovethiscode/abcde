package com.unciv.app.server

import com.badlogic.gdx.utils.JsonReader
import com.badlogic.gdx.utils.JsonValue
import com.badlogic.gdx.utils.JsonWriter
import com.unciv.logic.multiplayer.lobby.protocol.LobbyProtocol
import org.junit.Test
import org.junit.Assert.*

class LobbySaveFingerprintTest {
    @Test fun onlyCivilizationNotificationsAreExcludedFromTheStartFingerprint() {
        val original="""{"gameId":"same","checksum":"original-checksum","turns":0,"gameParameters":{"multiplayerServerUrl":"https://saves.example","players":[{"playerId":"host","chosenCiv":"Rome"}]},"tileMap":{"tileList":[{"terrain":"Grassland"}]},"civilizations":[{"playerId":"host","civName":"Rome"}]}"""
        val notified=JsonReader().parse(original).apply {
            get("civilizations")[0].addChild("notifications",JsonReader().parse("""[{"text":"Welcome to UncivServer.xyz!"}]"""))
        }
        val hash=LobbyProtocol.saveFingerprint(original)
        assertEquals(hash,LobbyProtocol.saveFingerprint(notified.toJson(JsonWriter.OutputType.json)))
        fun rejectsEdit(edit: (JsonValue)->Unit) {
            val data=JsonReader().parse(original); edit(data)
            assertNotEquals(hash,LobbyProtocol.saveFingerprint(data.toJson(JsonWriter.OutputType.json)))
        }
        rejectsEdit { it.get("turns").set(1L,"1") }
        rejectsEdit { it.get("gameId").set("other") }
        rejectsEdit { it.get("checksum").set("changed") }
        rejectsEdit { it.get("gameParameters").get("players")[0].get("playerId").set("other") }
        rejectsEdit { it.get("gameParameters").get("players")[0].get("chosenCiv").set("Greece") }
        rejectsEdit { it.get("tileMap").get("tileList")[0].get("terrain").set("Desert") }
        rejectsEdit { it.get("civilizations")[0].get("civName").set("Greece") }
    }
}
