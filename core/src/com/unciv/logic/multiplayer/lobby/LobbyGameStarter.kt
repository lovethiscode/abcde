package com.unciv.logic.multiplayer.lobby

import com.unciv.UncivGame
import com.unciv.json.json
import com.unciv.logic.GameStarter
import com.unciv.logic.UncivShowableException
import com.unciv.logic.civilization.PlayerType
import com.unciv.logic.files.SaveFileWriter
import com.unciv.logic.files.UncivFiles
import com.unciv.logic.multiplayer.lobby.protocol.*
import com.unciv.logic.multiplayer.storage.MultiplayerServer
import com.unciv.models.metadata.GameSetupInfo
import com.unciv.models.metadata.Player
import kotlinx.coroutines.CancellationException

/** Room commands use the API; the complete save and preview use only the pinned game server. */
class LobbyGameStarter(private val session: LobbySession, private val client: LobbyClient,
                       private val server: MultiplayerServer = UncivGame.Current.onlineMultiplayer.serverFor(session.gameServerUrl)) {
    suspend fun start(command: LobbyCommand): LobbyRoom {
        val frozen = client.command(session.gameId,"start",command)
        if (frozen.gameServerUrl != session.gameServerUrl)
            throw UncivShowableException("The invitation game server differs from this room.")
        if (frozen.state == "STARTED") return frozen
        val save = UncivGame.Current.files.getLocalFile("lobby-start-${frozen.gameId}.json.gz")
        SaveFileWriter.recover(save)
        val body: String
        if (save.exists()) body = save.readString("UTF-8")
        else if (frozen.saveHash.isNotEmpty()) {
            body = server.fileStorage().loadFileData(frozen.gameId)
            if (LobbyProtocol.sha256(body) != frozen.saveHash)
                throw UncivShowableException("The original start save is unavailable. Recover the previous upload before continuing.")
            SaveFileWriter.write(save) { it.writeString(body,false,"UTF-8") }
        } else {
            val setup = json().fromJson(GameSetupInfo::class.java,frozen.setup)
            setup.gameParameters.players = ArrayList(frozen.seats.map { Player(it.civilization,if (it.human) PlayerType.Human else PlayerType.AI,it.playerId) })
            setup.gameParameters.multiplayerServerUrl = session.gameServerUrl
            body = UncivFiles.gameInfoToString(GameStarter.startNewGame(setup,frozen.gameId),forceZip = true,updateChecksum = true)
            SaveFileWriter.write(save) { it.writeString(body,false,"UTF-8") }
        }
        command.startOperation = frozen.startOperation; command.frozenVersion = frozen.frozenVersion
        command.saveHash = LobbyProtocol.sha256(body)
        client.command(session.gameId,"start/save",command)
        val original = UncivFiles.gameInfoFromString(body,validateChecksumBeforeMigrations = true)
        server.fileStorage().saveFileData(session.gameId,body)
        try { server.tryUploadGamePreview(original.asPreview()) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { /* The verified full save is sufficient; ordinary sync repairs previews. */ }
        return client.command(session.gameId,"start/complete",command)
    }
}
