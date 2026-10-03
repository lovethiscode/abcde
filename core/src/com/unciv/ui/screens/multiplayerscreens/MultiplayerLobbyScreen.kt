package com.unciv.ui.screens.multiplayerscreens

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.unciv.Constants
import com.unciv.UncivGame
import com.unciv.json.json
import com.unciv.logic.GameStarter
import com.unciv.logic.UncivShowableException
import com.unciv.logic.civilization.PlayerType
import com.unciv.logic.files.SaveFileWriter
import com.unciv.logic.files.UncivFiles
import com.unciv.logic.multiplayer.MultiplayerGameInvite
import com.unciv.logic.multiplayer.lobby.*
import com.unciv.logic.multiplayer.lobby.protocol.*
import com.unciv.models.metadata.GameSetupInfo
import com.unciv.models.metadata.Player
import com.unciv.models.ruleset.RulesetCache
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.*
import com.unciv.ui.components.input.onClick
import com.unciv.ui.popups.ConfirmPopup
import com.unciv.ui.popups.Popup
import com.unciv.ui.screens.newgamescreen.NationPickerPopup
import com.unciv.ui.screens.pickerscreens.PickerScreen
import com.unciv.ui.screens.worldscreen.WorldScreen
import com.unciv.utils.Concurrency
import com.unciv.utils.launchOnGLThread
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException

/** Server-confirmed lobby. Back navigation preserves membership; explicit leave removes it. */
class MultiplayerLobbyScreen(private val session: LobbySession, initial: LobbyRoom? = null,
                             private val joinOnOpen: Boolean = false) : PickerScreen() {
    private val client = LobbyClient(session.server)
    private val userId = game.settings.multiplayer.getUserId()
    private var room: LobbyRoom? = initial
    private var busy = false
    private var paused = false
    private var disposed = false
    private var enteringGame = false
    private var leaving = false
    private var nextPoll = 0L
    private var failures = 0
    private var job: Job? = null
    private val members = Table()
    private val status = "Connecting to room...".toLabel()

    companion object {
        fun openInvite(invite: MultiplayerGameInvite) {
            val game = UncivGame.Current
            val current = LobbySession.current()
            val server = invite.serverUrl ?: game.settings.multiplayer.getServer()
            (game.getScreen() as? MultiplayerLobbyScreen)?.let { shown ->
                if (shown.session.gameId == invite.gameId && shown.session.server == server && shown.session.occupancy.isNotEmpty()) return
            }
            fun open() {
                val session = if (current?.gameId == invite.gameId && current.server == server) current else LobbySession().apply {
                    this.server = server; gameId = invite.gameId
                }
                game.pushScreen { MultiplayerLobbyScreen(session, joinOnOpen = true) }
            }
            if (current != null && (current.gameId != invite.gameId || current.server != server)) {
                ConfirmPopup(game.getScreen()!!, "Leave the current waiting room and join this invitation?", "Leave and join") {
                    Concurrency.run {
                        try {
                            if (current.gameId.isNotEmpty()) LobbyClient(current.server).command(current.gameId,"leave",LobbyCommand().apply {
                                playerId = game.settings.multiplayer.getUserId(); occupancy = current.occupancy
                            })
                            LobbySession.clear()
                            launchOnGLThread { open() }
                        } catch (e: Exception) { launchOnGLThread { errorPopup(game.getScreen()!!,e) } }
                    }
                }.open()
            } else if (game.getScreen() is WorldScreen) {
                ConfirmPopup(game.getScreen()!!,"Save the current game and open this room invitation?","Save and open") {
                    Concurrency.run { game.gameInfo?.let { game.files.autosaves.autoSave(it) }; launchOnGLThread { open() } }
                }.open()
            } else open()
        }
        private fun errorPopup(screen: com.unciv.ui.screens.basescreen.BaseScreen, e: Exception) {
            Popup(screen).apply { addGoodSizedLabel(e.message ?: "Room request failed. Please retry.").row(); addCloseButton(); open() }
        }
    }
    init {
        setDefaultCloseAction()
        topTable.add("Multiplayer room".toLabel(fontSize = 26)).pad(12f).row()
        topTable.add(status).pad(8f).row()
        topTable.add(members).growX().row()
        val share = "Share invitation".toTextButton().apply { onClick {
            try {
                val link = MultiplayerGameInvite.createLobbyLink(session.gameId,session.server)
                game.shareMultiplayerGame("Multiplayer room".tr(),session.gameId,link)
            } catch (e: Exception) { errorPopup(this@MultiplayerLobbyScreen,e) }
        } }
        val copy = "Copy invitation".toTextButton().apply { onClick {
            try { Gdx.app.clipboard.contents = MultiplayerGameInvite.createLobbyLink(session.gameId,session.server) }
            catch (e: Exception) { errorPopup(this@MultiplayerLobbyScreen,e) }
        } }
        bottomTable.add(share).pad(5f); bottomTable.add(copy).pad(5f)
        val leave = "Leave room".toTextButton().apply { onClick {
            if (busy) return@onClick
            ConfirmPopup(this@MultiplayerLobbyScreen,
                if (room?.hostId == userId) "Leaving closes the room for everyone." else "Leave this waiting room?","Leave room") {
                leaving = true
                runRequest { client.command(session.gameId,"leave",command()) }
            }.open()
        } }
        bottomTable.add(leave).pad(5f)
        rightSideButton.onClick { runRequest { start() } }
        rightSideButton.disable()
        if (initial != null) { accept(initial); drawMembers() }
        else runRequest { if (joinOnOpen) client.command(session.gameId,"join",command()) else client.read(session.gameId,command()) }
    }
    private fun command() = LobbyCommand().apply {
        playerId = userId; occupancy = session.occupancy; rulesHash = LobbyClient.rulesHash()
        room?.let { r ->
            startOperation = r.startOperation; frozenVersion = r.frozenVersion; saveHash = r.saveHash
            seatRevision = r.seats.firstOrNull { it.playerId == userId && it.occupancy == session.occupancy }?.revision ?: 0L
        }
    }
    private fun accept(latest: LobbyRoom) {
        if (latest.version < (room?.version ?: -1)) return
        room = latest
        session.occupancy = latest.seats.firstOrNull { it.playerId == userId }?.occupancy.orEmpty()
        if (latest.state == "CLOSED") LobbySession.clear() else session.save()
    }
    private fun runRequest(action: suspend () -> LobbyRoom) {
        if (busy || disposed) return
        busy = true; drawMembers()
        job = Concurrency.run("Lobby request") {
            try {
                val result = action()
                launchOnGLThread {
                    if (disposed) return@launchOnGLThread
                    busy = false; failures = 0; nextPoll = System.currentTimeMillis() + 2000
                    if (leaving) { LobbySession.clear(); game.popScreen(); return@launchOnGLThread }
                    accept(result); drawMembers()
                    if (result.state == "STARTED" && result.seats.any { it.playerId == userId && it.occupancy == session.occupancy }) enterGame()
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                launchOnGLThread {
                    if (disposed) return@launchOnGLThread
                    busy = false; leaving = false; failures++; nextPoll = System.currentTimeMillis() + listOf(2000,4000,8000,15000)[minOf(failures-1,3)]
                    status.setText((e.message ?: "Connection failed. Retrying...").tr()); drawMembers(updateStatus = false)
                }
            }
        }
    }
    private fun drawMembers(updateStatus: Boolean = true) {
        members.clear()
        val r = room ?: return
        val own = r.seats.firstOrNull { it.playerId == userId && it.occupancy == session.occupancy }
        if (updateStatus) status.setText(when {
            r.state == "CLOSED" -> r.reason.tr()
            own == null -> "You have left this room or were removed. Open the invitation to rejoin.".tr()
            r.state == "STARTING" -> "Starting game. Waiting for the host to finish uploading...".tr()
            r.state == "STARTED" -> "Game started".tr()
            else -> "Choose your civilization, then get ready.".tr()
        })
        if (own == null && r.state == "WAITING") members.add("Rejoin room".toTextButton().apply { onClick {
            runRequest { client.command(session.gameId,"join",command()) }
        }; isDisabled = busy }).pad(8f).row()
        for (s in r.seats) {
            val row = Table()
            row.add((when {
                !s.human -> "AI"
                s.playerId.isEmpty() -> "Waiting for player"
                s.playerId == userId -> "You"
                s.playerId == r.hostId -> "Host"
                else -> "Player [${s.seatId + 1}]"
            }).toLabel()).left().pad(5f)
            row.add(s.civilization.toLabel()).pad(5f)
            row.add((if (!s.human) "AI" else if (s.ready) "Ready" else "Not ready").toLabel(if (s.ready) Color.GREEN else Color.LIGHT_GRAY)).pad(5f)
            if (s === own && r.state == "WAITING") {
                row.row()
                row.add("Choose civilization".toTextButton().apply { onClick { chooseCivilization(s) }; isDisabled = busy }).pad(5f)
                row.add((if (s.ready) "Cancel ready" else "Ready").toTextButton().apply { onClick {
                    runRequest { client.command(session.gameId,"ready",command().apply { ready = !s.ready }) }
                }; isDisabled = busy }).pad(5f)
            }
            if (r.hostId == userId && s.human && s.playerId.isNotEmpty() && s.playerId != userId && r.state == "WAITING") {
                row.add("Kick".toTextButton().apply { onClick {
                    ConfirmPopup(this@MultiplayerLobbyScreen,"Remove this player from the room?","Kick") {
                        runRequest { client.command(session.gameId,"kick",command().apply { seatId = s.seatId; targetOccupancy = s.occupancy }) }
                    }.open()
                }; isDisabled = busy }).pad(5f)
            }
            members.add(row).growX().row()
            if (s.playerId.isNotEmpty()) members.add(s.playerId.toLabel(fontSize = 14)).left().padBottom(10f).row()
        }
        rightSideButton.setText((if (r.state == "STARTING") "Retry start" else "Start game!").tr())
        setRightSideButtonEnabled(!busy && own != null && r.hostId == userId &&
            (r.state == "STARTING" || (r.state == "WAITING" && r.seats.none { it.human && (it.playerId.isEmpty() || !it.ready) })))
    }
    private fun chooseCivilization(s: LobbySeat) {
        val r = room ?: return
        val ruleset = RulesetCache.getComplexRuleset(json().fromJson(GameSetupInfo::class.java,r.setup).gameParameters)
        val available = ruleset.nations.values.filter { it.isMajorCiv && (it.name == s.civilization || r.seats.none { seat -> seat.civilization == it.name }) }
        NationPickerPopup(null,Player(s.civilization,PlayerType.Human),false,screen = this,ruleset = ruleset,availableCivs = available,
            submit = { civ -> runRequest { client.command(session.gameId,"civilization",command().apply { seatId = s.seatId; civilization = civ }) } }).open()
    }
    private suspend fun start(): LobbyRoom {
        val frozen = client.command(session.gameId,"start",command())
        room = frozen
        val save = game.files.getLocalFile("lobby-start-${frozen.gameId}.json.gz")
        SaveFileWriter.recover(save)
        var body: String
        if (save.exists()) body = save.readString("UTF-8")
        else if (frozen.saveHash.isNotEmpty()) {
            body = game.onlineMultiplayer.serverFor(session.server).fileStorage().loadFileData(frozen.gameId)
            if (LobbyProtocol.sha256(body) != frozen.saveHash) throw UncivShowableException("The original start save is unavailable. Recover the previous upload before continuing.")
            SaveFileWriter.write(save) { it.writeString(body,false,"UTF-8") }
        } else {
            val setup = json().fromJson(GameSetupInfo::class.java,frozen.setup)
            setup.gameParameters.players = ArrayList(frozen.seats.map { s -> Player(s.civilization,if (s.human) PlayerType.Human else PlayerType.AI,s.playerId) })
            setup.gameParameters.multiplayerServerUrl = session.server
            val generated = GameStarter.startNewGame(setup, frozen.gameId)
            body = UncivFiles.gameInfoToString(generated,forceZip = true,updateChecksum = true)
            SaveFileWriter.write(save) { it.writeString(body,false,"UTF-8") }
        }
        val c = command().apply { saveHash = LobbyProtocol.sha256(body) }
        client.command(session.gameId,"start/save",c)
        val original = UncivFiles.gameInfoFromString(body,validateChecksumBeforeMigrations = true)
        val server = game.onlineMultiplayer.serverFor(session.server)
        server.fileStorage().saveFileData(session.gameId,body)
        try { server.tryUploadGamePreview(original.asPreview()) } catch (e: CancellationException) { throw e } catch (_: Exception) { /* Full save is enough to enter; the next sync repairs preview. */ }
        return client.command(session.gameId,"start/complete",c)
    }
    private fun enterGame() {
        if (busy) return
        busy = true; enteringGame = true
        job = Concurrency.run("Enter lobby game") {
            try {
                val latest = game.onlineMultiplayer.downloadInvitedGame(MultiplayerGameInvite(session.gameId,session.server))
                game.loadGame(latest)
                LobbySession.clear()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { launchOnGLThread { if (!disposed) { busy = false; enteringGame = false; errorPopup(this@MultiplayerLobbyScreen,e) } } }
        }
    }
    override fun render(delta: Float) {
        super.render(delta)
        if (!paused && !busy && !disposed && room?.state != "CLOSED" && System.currentTimeMillis() >= nextPoll)
            runRequest { client.read(session.gameId,command()) }
    }
    override fun pause() { paused = true }
    override fun resume() { paused = false; nextPoll = 0L }
    override fun hide() { paused = true; if (!enteringGame) job?.cancel(); busy = false }
    override fun show() { super.show(); paused = false; nextPoll = 0L }
    override fun dispose() { disposed = true; if (!enteringGame) job?.cancel(); super.dispose() }
}
