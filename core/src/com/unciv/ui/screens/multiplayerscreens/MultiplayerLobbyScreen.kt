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
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.components.input.KeyCharAndCode
import com.unciv.ui.components.input.keyShortcuts
import com.unciv.ui.components.widgets.AutoScrollPane
import com.unciv.ui.components.widgets.BorderedTable
import com.unciv.ui.components.widgets.ExpanderTab
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.screens.worldscreen.WorldScreen
import com.unciv.utils.Concurrency
import com.unciv.utils.launchOnGLThread
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException

/** Server-confirmed lobby. Back navigation preserves membership; explicit leave removes it. */
class MultiplayerLobbyScreen(private val session: LobbySession, initial: LobbyRoom? = null,
                             private val joinOnOpen: Boolean = false) : BaseScreen() {
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
    private val layout = Table()
    private val content = Table()
    private val scroll = AutoScrollPane(content)
    private val footer = Table()
    private val startButton = "Start game!".toTextButton(skin.get("positive", com.badlogic.gdx.scenes.scene2d.ui.TextButton.TextButtonStyle::class.java))
    private val inviteButton = "Invite friends".toTextButton()
    private var detailsOpen = false
    private var renderedState = ""
    private var setup: GameSetupInfo? = null
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
        val back = "Back".toTextButton().apply {
            onClick { game.popScreen() }
            keyShortcuts.add(KeyCharAndCode.BACK)
        }
        val header = Table().apply {
            add(back).height(44f).padRight(16f)
            add("Multiplayer room".toLabel(fontSize = 28)).left().growX()
            add(inviteButton).height(44f)
        }
        inviteButton.onClick { showInvitation() }
        startButton.onClick { runRequest { start() } }
        status.setWrap(true)
        content.top()
        scroll.setScrollingDisabled(true, false)
        scroll.setOverscroll(false, false)
        layout.background = skinStrings.getUiBackground("MultiplayerLobbyScreen/Background", tintColor = Color.valueOf("08172d"))
        layout.setFillParent(true)
        layout.pad(16f)
        layout.add(header).growX().padBottom(16f).row()
        layout.add(scroll).grow().top().row()
        layout.add(footer).growX().padTop(12f)
        stage.addActor(layout)
        drawMembers(force = true)
        if (initial != null) { accept(initial); drawMembers(force = true) }
        else runRequest { if (joinOnOpen) client.command(session.gameId,"join",command()) else client.read(session.gameId,command()) }
    }
    private fun showInvitation() {
        val popup = Popup(this)
        popup.addGoodSizedLabel("Invite friends", size = 26).row()
        popup.addGoodSizedLabel("Share the link. Your friends can join and choose their own civilizations.").row()
        popup.addButton("Share invitation") {
            try {
                val link = MultiplayerGameInvite.createLobbyLink(session.gameId,session.server)
                game.shareMultiplayerGame("Multiplayer room".tr(),session.gameId,link)
            } catch (e: Exception) { errorPopup(this,e) }
        }.row()
        popup.addButton("Copy invitation") {
            try {
                Gdx.app.clipboard.contents = MultiplayerGameInvite.createLobbyLink(session.gameId,session.server)
                popup.close()
                com.unciv.ui.popups.ToastPopup("Invitation copied. Send it to your friends.",this)
            } catch (e: Exception) { errorPopup(this,e) }
        }.row()
        popup.addCloseButton()
        popup.open()
    }
    private fun leaveRoom() {
        if (busy) return
        ConfirmPopup(this,
            if (room?.hostId == userId) "Leaving closes the room for everyone." else "Leave this waiting room?","Leave room") {
            leaving = true
            runRequest { client.command(session.gameId,"leave",command()) }
        }.open()
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
    private fun runRequest(refreshOnly: Boolean = false, action: suspend () -> LobbyRoom) {
        if (busy || disposed) return
        busy = true; if (!refreshOnly) drawMembers()
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
                    status.setText((e.message ?: "Connection failed. Retrying...").tr()); drawMembers(updateStatus = false); renderedState = ""
                }
            }
        }
    }
    private fun drawMembers(updateStatus: Boolean = true, force: Boolean = false) {
        val r = room
        // Heartbeats change version/lastSeen but should not replace actors under a player's finger.
        val signature = "$busy|${r?.state}|${r?.reason}|${r?.seats?.map { listOf(it.seatId,it.playerId,it.occupancy,it.civilization,it.ready) }}"
        if (!force && updateStatus && renderedState == signature) return
        renderedState = signature
        val scrollY = scroll.scrollY
        content.clear()
        footer.clear()
        val own = r?.seats?.firstOrNull { it.playerId == userId && it.occupancy == session.occupancy }
        val humans = r?.seats?.filter { it.human }.orEmpty()
        val joined = humans.count { it.playerId.isNotEmpty() }
        val ready = humans.count { it.playerId.isNotEmpty() && it.ready }
        val host = r?.hostId == userId
        if (updateStatus) status.setText(when {
            r == null -> "Connecting to room...".tr()
            r.state == "CLOSED" -> r.reason.tr()
            own == null -> "You have left this room or were removed. Open the invitation to rejoin.".tr()
            r.state == "STARTING" -> "Starting game. Waiting for the host to finish uploading...".tr()
            r.state == "STARTED" -> "Game started".tr()
            busy -> Constants.working.tr()
            joined < humans.size -> "Invite friends to fill the empty seats.".tr()
            ready < humans.size -> "Choose your civilization, then get ready.".tr()
            host -> "Everyone is ready. You can start the game.".tr()
            else -> "Everyone is ready. Waiting for the host to start.".tr()
        })
        inviteButton.isEnabled = r?.state == "WAITING" && own != null && !busy
        val width = stage.width - 32f
        if (r != null) {
            val gameSetup = setup ?: json().fromJson(GameSetupInfo::class.java,r.setup).also { setup = it }
            val landscape = width >= 1000f && stage.width > stage.height
            val cardsWidth = if (landscape) width - 230f - 16f else width
            val summary = Table().apply {
                background = skinStrings.getUiBackground("MultiplayerLobbyScreen/GameSummary", skinStrings.roundedEdgeRectangleSmallShape, Color.valueOf("0d2140"))
                pad(18f)
                defaults().left().growX().padBottom(10f)
                add("Game setup".toLabel(Color.GOLD,22)).row()
                for (value in listOf(gameSetup.mapParameters.type, gameSetup.mapParameters.mapSize.name,
                    gameSetup.gameParameters.baseRuleset, gameSetup.gameParameters.speed,
                    gameSetup.gameParameters.difficulty, gameSetup.gameParameters.startingEra))
                    add(value.toLabel(fontSize = 18).apply { setWrap(true) }).row()
            }
            val players = Table().apply {
                add("Players".toLabel(fontSize = 24)).left().row()
                add("[$joined]/[${humans.size}] joined · [$ready]/[${humans.size}] ready".toLabel(Color.LIGHT_GRAY,18)).left().padTop(4f).padBottom(12f).row()
            }
            if (own == null && r.state == "WAITING") players.add("Rejoin room".toTextButton().apply {
                onClick { runRequest { client.command(session.gameId,"join",command()) } }
                isEnabled = !busy
            }).height(44f).padBottom(12f).row()
            val grid = Table()
            val columns = if (cardsWidth >= 640f) 2 else 1
            val cardWidth = (cardsWidth - (columns - 1) * 12f) / columns
            for ((index,s) in r.seats.withIndex()) {
                grid.add(playerCard(r,s,own,cardWidth)).width(cardWidth).growY().padBottom(12f)
                    .padRight(if (index % columns == 0 && columns == 2) 12f else 0f)
                if (index % columns == columns-1) grid.row()
            }
            players.add(grid).growX().row()
            val details = ExpanderTab("Room and player information",fontSize = 18, startsOutOpened = detailsOpen,
                expanderWidth = cardsWidth, onChange = { detailsOpen = !detailsOpen }) { table ->
                table.add("Game ID".toLabel(Color.LIGHT_GRAY,16)).left().row()
                table.add(r.gameId.toLabel(fontSize = 16).apply { setText(r.gameId); setWrap(true) }).growX().row()
                for (seat in r.seats.filter { it.playerId.isNotEmpty() }) {
                    table.add("Player [${seat.seatId+1}]".toLabel(Color.LIGHT_GRAY,16)).left().padTop(8f).row()
                    table.add(seat.playerId.toLabel(fontSize = 16).apply { setText(seat.playerId); setWrap(true) }).growX().row()
                }
            }
            players.add(details).growX()
            if (landscape) {
                content.add(summary).width(230f).top().padRight(16f)
                content.add(players).width(cardsWidth).top()
            } else {
                content.add(players).width(width).top().row()
                content.add(summary).width(width).padTop(12f)
            }
        }
        val leave = "Leave room".toTextButton().apply { onClick { leaveRoom() }; isEnabled = !busy }
        footer.add(status).growX().left().padRight(12f)
        footer.add(leave).height(44f).padRight(if (host) 10f else 0f)
        if (host) {
            startButton.setText((if (r.state == "STARTING") "Retry start" else "Start game!").tr())
            startButton.isEnabled = !busy && own != null && (r.state == "STARTING" || (r.state == "WAITING" && joined == humans.size && ready == humans.size))
            footer.add(startButton).height(44f)
        }
        layout.invalidateHierarchy()
        scroll.layout()
        scroll.scrollY = scrollY
    }
    private fun playerCard(r: LobbyRoom, s: LobbySeat, own: LobbySeat?, width: Float): Table {
        val mine = s === own
        val empty = s.human && s.playerId.isEmpty()
        val card = BorderedTable("MultiplayerLobbyScreen/PlayerCard",skinStrings.roundedEdgeRectangleSmallShape,skinStrings.roundedEdgeRectangleSmallShape).apply {
            bgColor = Color.valueOf(if (mine) "182c46" else "0d2140")
            bgBorderColor = if (mine) Color.GOLD else Color.valueOf("294568")
            borderSize = 1f
            pad(16f)
        }
        val title = when {
            empty -> "Waiting for player"
            !s.human -> "AI"
            mine && r.hostId == userId -> "Host · You"
            mine -> "You"
            s.playerId == r.hostId -> "Host"
            else -> "Player [${s.seatId+1}]"
        }
        val heading = Table().apply {
            add(title.toLabel(fontSize = 20)).growX().left()
            if (!empty) add((if (!s.human) "AI" else if (s.ready) "Ready" else "Not ready").toLabel(if (s.ready) Color.GREEN else Color.LIGHT_GRAY,16)).padLeft(8f)
        }
        card.add(heading).growX().row()
        val civilization = Table().apply {
            val ruleset = RulesetCache.getComplexRuleset(setup!!.gameParameters)
            val nation = ruleset.nations[s.civilization]
            add(if (nation == null) ImageGetter.getRandomNationPortrait(44f) else ImageGetter.getNationPortrait(nation,44f)).size(44f).padRight(12f)
            add((if (empty) "Your friend chooses their civilization" else s.civilization).toLabel(fontSize = if (empty) 18 else 22, hideIcons = true).apply { setWrap(true) }).growX().left()
        }
        card.add(civilization).width(width-32f).padTop(16f).padBottom(16f).row()
        val controls = Table()
        when {
            empty -> controls.add("Invite friends".toTextButton().apply { onClick { showInvitation() }; isEnabled = !busy && own != null && r.state == "WAITING" }).growX().height(44f)
            mine && r.state == "WAITING" -> {
                controls.add("Choose civilization".toTextButton().apply { onClick { chooseCivilization(s) }; isEnabled = !busy }).growX().height(44f).padRight(8f)
                controls.add((if (s.ready) "Cancel ready" else "Ready").toTextButton().apply { onClick {
                    runRequest { client.command(session.gameId,"ready",command().apply { ready = !s.ready }) }
                }; isEnabled = !busy }).height(44f)
            }
            r.hostId == userId && s.human && r.state == "WAITING" -> controls.add("Kick".toTextButton().apply { onClick {
                ConfirmPopup(this@MultiplayerLobbyScreen,"Remove this player from the room?","Kick") {
                    runRequest { client.command(session.gameId,"kick",command().apply { seatId = s.seatId; targetOccupancy = s.occupancy }) }
                }.open()
            }; isEnabled = !busy }).height(44f).right()
            else -> controls.add((if (!s.human) "AI" else if (s.ready) "Ready" else "Choosing civilization...").toLabel(Color.LIGHT_GRAY,18, hideIcons = true)).height(44f)
        }
        card.add(controls).growX()
        return card
    }
    override fun resize(width: Int, height: Int) {
        super.resize(width,height)
        drawMembers(force = true)
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
            runRequest(refreshOnly = true) { client.read(session.gameId,command()) }
    }
    override fun pause() { paused = true }
    override fun resume() { paused = false; nextPoll = 0L }
    override fun hide() { paused = true; if (!enteringGame) job?.cancel(); busy = false }
    override fun show() { super.show(); paused = false; nextPoll = 0L }
    override fun dispose() { disposed = true; if (!enteringGame) job?.cancel(); super.dispose() }
}
