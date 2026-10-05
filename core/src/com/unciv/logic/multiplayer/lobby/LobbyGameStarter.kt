package com.unciv.logic.multiplayer.lobby

import com.unciv.UncivGame
import com.unciv.json.json
import com.unciv.logic.GameStarter
import com.unciv.logic.UncivShowableException
import com.unciv.logic.civilization.PlayerType
import com.unciv.logic.files.SaveFileWriter
import com.unciv.logic.files.UncivFiles
import com.unciv.logic.files.FileConversions
import com.unciv.logic.multiplayer.lobby.protocol.*
import com.unciv.logic.multiplayer.storage.MultiplayerServer
import com.unciv.logic.multiplayer.MultiplayerOperation
import com.unciv.logic.multiplayer.observeMultiplayerOperation
import com.unciv.logic.multiplayer.reportMultiplayerOperation
import com.unciv.models.metadata.GameSetupInfo
import com.unciv.models.metadata.Player
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class LobbyStartSave {
    var gameId = ""
    var startOperation = ""
    var frozenVersion = 0L
    var saveHash = ""
}

/** Room commands use the API; the complete save and preview use only the pinned game server. */
class LobbyGameStarter(private val session: LobbySession, private val client: LobbyClient,
                       private val server: MultiplayerServer = UncivGame.Current.onlineMultiplayer.serverFor(session.gameServerUrl)) {
    companion object { private val startMutex = Mutex() }
    suspend fun start(command: LobbyCommand): LobbyRoom = startMutex.withLock {
        val frozen = try {
            client.command(session.gameId,"start",command)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            reportMultiplayerOperation(MultiplayerOperation.Start, false, session.playerId)
            throw e
        }
        if (frozen.state == "STARTED") return@withLock frozen
        observeMultiplayerOperation(MultiplayerOperation.Start, session.playerId) { startOnce(command, frozen) }
    }
    private suspend fun startOnce(command: LobbyCommand, frozen: LobbyRoom): LobbyRoom {
        if (frozen.gameServerUrl != session.gameServerUrl)
            throw UncivShowableException("The invitation game server differs from this room.")
        if (frozen.state == "STARTED") return frozen
        val save = UncivGame.Current.files.getLocalFile("lobby-start-${frozen.gameId}-${frozen.startOperation}.json.gz")
        val metadata = save.sibling(save.name() + ".meta.json")
        SaveFileWriter.recover(save)
        SaveFileWriter.recover(metadata)
        if (metadata.exists()) {
            val binding = LobbyProtocol.json().fromJson(LobbyStartSave::class.java,metadata)
            if (binding.gameId != frozen.gameId || binding.startOperation != frozen.startOperation || binding.frozenVersion != frozen.frozenVersion)
                throw UncivShowableException("The original start save is unavailable. Recover the previous upload before continuing.")
        }
        val body: String
        if (save.exists()) body = save.readString("UTF-8")
        else if (frozen.saveHash.isNotEmpty()) {
            body = server.fileStorage().loadFileData(frozen.gameId)
            if (LobbyProtocol.saveFingerprint(FileConversions.unzip(body)) != frozen.saveHash)
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
        command.saveHash = LobbyProtocol.saveFingerprint(FileConversions.unzip(body))
        if (frozen.saveHash.isNotEmpty() && frozen.saveHash != command.saveHash)
            throw UncivShowableException("The original start save is unavailable. Recover the previous upload before continuing.")
        // First registration validates the original checksum. Recovery is checked by the
        // pinned content fingerprint because the file server may append notifications.
        val original = UncivFiles.gameInfoFromString(body,validateChecksumBeforeMigrations = frozen.saveHash.isEmpty())
        val frozenSetup = json().fromJson(GameSetupInfo::class.java,frozen.setup)
        if (original.gameId != frozen.gameId || original.tileMap.mapParameters.seed != frozenSetup.mapParameters.seed ||
            original.gameParameters.multiplayerServerUrl != frozen.gameServerUrl || original.gameParameters.players.size != frozen.seats.size ||
            frozen.seats.any { s -> original.gameParameters.players[s.seatId].let {
                it.playerId != s.playerId || (it.playerType == PlayerType.Human) != s.human || (s.civilization != "Random" && it.chosenCiv != s.civilization)
            } } || (metadata.exists() && LobbyProtocol.json().fromJson(LobbyStartSave::class.java,metadata).saveHash != command.saveHash))
            throw UncivShowableException("The original start save is unavailable. Recover the previous upload before continuing.")
        SaveFileWriter.write(metadata) { it.writeString(LobbyProtocol.json().toJson(LobbyStartSave().apply {
            gameId = frozen.gameId; startOperation = frozen.startOperation; frozenVersion = frozen.frozenVersion; saveHash = command.saveHash
        }),false,"UTF-8") }
        client.command(session.gameId,"start/save",command)
        server.fileStorage().saveFileData(session.gameId,body)
        try { server.tryUploadGamePreview(original.asPreview()) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { /* The verified full save is sufficient; ordinary sync repairs previews. */ }
        return client.command(session.gameId,"start/complete",command)
    }
}
