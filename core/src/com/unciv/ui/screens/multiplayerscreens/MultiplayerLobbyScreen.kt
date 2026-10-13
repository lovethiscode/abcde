package com.unciv.ui.screens.multiplayerscreens

import com.unciv.ui.components.widgets.UiNavigation
import com.unciv.models.skins.ExpeditionBlueTheme
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.Slider
import com.badlogic.gdx.scenes.scene2d.ui.TextField
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener
import com.badlogic.gdx.scenes.scene2d.Actor
import com.unciv.Constants
import com.unciv.UncivGame
import com.unciv.json.json
import com.unciv.logic.UncivShowableException
import com.unciv.logic.civilization.PlayerType
import com.unciv.logic.IdChecker
import com.unciv.logic.multiplayer.MultiplayerGameInvite
import com.unciv.logic.multiplayer.lobby.*
import com.unciv.logic.multiplayer.lobby.protocol.*
import com.unciv.models.metadata.GameSetupInfo
import com.unciv.models.metadata.Player
import com.unciv.models.metadata.NewGameMode
import com.unciv.models.ruleset.Ruleset
import com.unciv.models.ruleset.RulesetCache
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.*
import com.unciv.ui.components.input.onClick
import com.unciv.ui.popups.ConfirmPopup
import com.unciv.ui.popups.Popup
import com.unciv.ui.popups.ToastPopup
import com.unciv.ui.components.widgets.UncivTextField
import com.unciv.ui.screens.civilopediascreen.MarkupRenderer
import com.unciv.ui.screens.civilopediascreen.FormattedLine.IconDisplay
import com.unciv.ui.screens.newgamescreen.NationPickerPopup
import com.unciv.ui.screens.newgamescreen.IPreviousScreen
import com.unciv.ui.screens.newgamescreen.GameOptionsTable
import com.unciv.ui.screens.newgamescreen.GameSetupOverview
import com.unciv.ui.screens.newgamescreen.GameSetupSection
import com.unciv.ui.screens.newgamescreen.MapParametersTable
import com.unciv.logic.map.MapGeneratedMainType
import com.unciv.logic.map.MapType
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
import kotlinx.coroutines.delay

fun lobbyStartButtonText(roomState: String, busy: Boolean): String = when {
    roomState == "STARTED" || (roomState == "STARTING" && busy) -> "Starting game..."
    roomState == "STARTING" -> "Retry start"
    else -> "Start game!"
}

/** Server-confirmed lobby. Back navigation preserves membership; explicit leave removes it. */
class MultiplayerLobbyScreen(private val session: LobbySession, initial: LobbyRoom? = null,
                             private val joinOnOpen: Boolean = false) : BaseScreen(), IPreviousScreen {
    private val client = LobbyClient(session.roomApiUrl)
    private val userId = session.playerId.ifEmpty { game.settings.multiplayer.getUserId() }
    private var room: LobbyRoom? = initial
    private var busy = false
    private var paused = false
    private var disposed = false
    private var enteringGame = false
    private val pendingRequests = java.util.ArrayDeque<() -> Unit>()
    private var connected = false
    private var connectionGeneration = 0
    private var job: Job? = null
    private val layout = Table()
    private val body = Table()
    private val content = Table()
    private val scroll = AutoScrollPane(content)
    private val gameOptionsScroll = AutoScrollPane(null)
    private val mapOptionsScroll = AutoScrollPane(null)
    private val playersScroll = AutoScrollPane(null)
    private val scrollPanes = listOf(scroll, gameOptionsScroll, mapOptionsScroll, playersScroll)
    private val footer = Table()
    private val startButton = "Start game!".toTextButton(skin.get("positive", com.badlogic.gdx.scenes.scene2d.ui.TextButton.TextButtonStyle::class.java))
    private val inviteButton = "Invite friends".toTextButton()
    private val copyInvitationButton = "Copy invitation".toTextButton()
    private var detailsOpen = false
    private var renderedState = ""
    private var setup: GameSetupInfo? = null
    override val gameSetupInfo get() = checkNotNull(setup)
    override val ruleset = Ruleset()
    private var configurationSupported = false
    private var configurationTable: Table? = null
    private var gameSettings: GameOptionsTable? = null
    private var mapSettings: MapParametersTable? = null
    private var selectedSeatId = -1
    private var previewCivilization = ""
    private var setupSection = GameSetupSection.Overview
    private var configurationPending = false
    private var draftRevision = 0L
    private var draftBaseConfigurationVersion = -1L
    private var configurationDue = 0L
    private var configurationJob: Job? = null
    private var seatSources = arrayListOf<Int>()
    private val status = "Connecting to room...".toLabel()

    companion object {
        fun openRoom() {
            val game = UncivGame.Current
            val session = LobbySession.current() ?: LobbySession().apply {
                roomApiUrl = LobbySession.roomApiUrl()
                gameServerUrl = com.unciv.logic.multiplayer.storage.MultiplayerServer.validateServerUrl(
                    game.settings.multiplayer.getServer(), game.platformCapabilities)
                createKey = java.util.UUID.randomUUID().toString()
                val previous = GameSetupInfo.fromSettings()
                val source = if (previous.gameParameters.mods.isEmpty() && previous.gameParameters.baseRuleset in LobbyProtocol.rulesets)
                    previous else GameSetupInfo()
                val draft = NewGameMode.MultiplayerRoom.prepare(source, game.settings.multiplayer.getUserId())
                draft.gameParameters.multiplayerServerUrl = gameServerUrl
                draft.mapFile = null; draft.mapParameters.name = ""
                if (draft.mapParameters.type !in MapType.allValues) draft.mapParameters.type = MapType.pangaea
                if (draft.mapParameters.seed == 0L) draft.mapParameters.reseed()
                val rules = RulesetCache.getComplexRuleset(draft.gameParameters)
                draft.gameParameters.players.forEach { if (it.chosenCiv !in rules.nations) it.chosenCiv = Constants.random }
                draft.gameParameters.victoryTypes.removeAll { it !in rules.victories }
                if (draft.gameParameters.victoryTypes.isEmpty())
                    draft.gameParameters.victoryTypes.addAll(rules.selectableVictories().map { it.name })
                setup = json().toJson(draft)
                save()
            }
            session.save()
            game.pushScreen { MultiplayerLobbyScreen(session) }
        }
        fun openInvite(invite: MultiplayerGameInvite) {
            val game = UncivGame.Current
            val current = LobbySession.current()
            val gameServer = invite.serverUrl ?: game.settings.multiplayer.getServer()
            val roomApi = LobbySession.roomApiUrl()
            (game.getScreen() as? MultiplayerLobbyScreen)?.let { shown ->
                if (shown.session.gameId == invite.gameId && shown.session.gameServerUrl == gameServer && shown.session.roomApiUrl == roomApi && shown.session.occupancy.isNotEmpty()) return
            }
            fun open() {
                val session = if (current?.gameId == invite.gameId && current.gameServerUrl == gameServer && current.roomApiUrl == roomApi) current else LobbySession().apply {
                    gameServerUrl = gameServer; roomApiUrl = roomApi; gameId = invite.gameId
                }
                session.save()
                game.pushScreen { MultiplayerLobbyScreen(session, joinOnOpen = true) }
            }
            if (current != null && (current.gameId != invite.gameId || current.gameServerUrl != gameServer || current.roomApiUrl != roomApi)) {
                ConfirmPopup(game.getScreen()!!, "Leave the current waiting room and join this invitation?", "Leave and join") {
                    Concurrency.run {
                        try {
                            val oldClient = LobbyClient(current.roomApiUrl)
                            try {
                                val resolved = oldClient.open(current)
                                if (resolved.state != "CLOSED" && current.occupancy.isNotEmpty()) oldClient.command(current.gameId,"leave",LobbyCommand().apply {
                                    playerId = current.playerId; occupancy = current.occupancy
                                })
                                current.clearIfCurrent()
                            } catch (e: LobbyConnectionException) {
                                if (e.code !in setOf("ROOM_NOT_FOUND","MEMBERSHIP_EXPIRED","ROOM_CLOSED")) throw e
                                current.clearIfCurrent()
                            } finally { oldClient.close() }
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
        val back = UiNavigation.backButton().apply {
            onClick { if (configurationPending && connected && !busy) saveConfiguration(backAfter = true) else game.popScreen() }
            keyShortcuts.add(KeyCharAndCode.BACK)
        }
        val header = Table().apply {
            add(back).minHeight(UiNavigation.minimumTouchSize()).padRight(16f)
            add("Multiplayer room".toLabel(fontSize = 28)).left().growX()
            if (!UiNavigation.isIos) {
                if (this@MultiplayerLobbyScreen.stage.width < 850f) row()
                add(inviteButton).height(44f).padRight(8f)
                add(copyInvitationButton).height(44f)
            }
        }
        inviteButton.setText("Share invitation".tr())
        inviteButton.label.setWrap(true)
        copyInvitationButton.label.setWrap(true)
        inviteButton.onClick { shareInvitation() }
        copyInvitationButton.onClick { copyInvitation() }
        startButton.onClick { requestStart() }
        status.setWrap(true)
        content.top()
        scrollPanes.forEach {
            it.setScrollingDisabled(true, false)
            it.setOverscroll(false, false)
        }
        layout.background = skinStrings.getUiBackground("MultiplayerLobbyScreen/Background", tintColor = Color.valueOf("08172d"))
        layout.setFillParent(true)
        layout.pad(16f)
        layout.add(header).growX().padBottom(16f).row()
        layout.add(body).grow().top().row()
        layout.add(footer).growX().padTop(12f)
        stage.addActor(layout)
        drawMembers(force = true)
        if (initial != null) { accept(initial); drawMembers(force = true) }
    }
    private fun connectRoom(join: Boolean = joinOnOpen) {
        val generation = ++connectionGeneration
        client.onRoom = { latest -> Concurrency.runOnGLThread {
            if (!disposed && !paused && generation == connectionGeneration) {
                session.newRoomAfterHostTimeout(latest)?.let { replacement ->
                    replacement.save()
                    disconnectRoom()
                    game.replaceCurrentScreen { MultiplayerLobbyScreen(replacement) }
                    return@runOnGLThread
                }
                configurationSupported = true
                accept(latest); drawMembers()
                if (latest.state == "STARTED" && !busy && !enteringGame && session.occupancy.isNotEmpty()) enterGame()
            }
        } }
        client.onConnection = { available, message -> Concurrency.runOnGLThread {
            if (!disposed && !paused && generation == connectionGeneration) {
                connected = available
                if (!available) cancelConfigurationSync()
                drawMembers()
                if (!available) { status.setText(message.tr()); renderedState = "" }
                else processPendingRequests()
            }
        } }
        client.watch(session,join)
    }
    private fun shareInvitation() {
        try { game.shareMultiplayerGame("Multiplayer room".tr(),session.gameId,MultiplayerGameInvite.createLobbyLink(session.gameId,session.gameServerUrl)) }
        catch (e: Exception) { errorPopup(this,e) }
    }
    private fun copyInvitation() {
        try {
            Gdx.app.clipboard.contents = MultiplayerGameInvite.createLobbyLink(session.gameId,session.gameServerUrl)
            com.unciv.ui.popups.ToastPopup("Invitation copied. Send it to your friends.",this)
        } catch (e: Exception) { errorPopup(this,e) }
    }
    private fun leaveRoom() {
        if (busy) return
        ConfirmPopup(this,
            if (room?.hostId == userId) "Leaving closes the room for everyone." else "Leave this waiting room?","Leave room") {
            runRequest(leaveAfter = true) { client.command(session.gameId,"leave",command()) }
        }.open()
    }
    private fun command() = LobbyCommand().apply {
        playerId = userId; occupancy = session.occupancy; rulesHash = LobbyClient.rulesHash()
        gameServerUrl = session.gameServerUrl
        room?.let { r ->
            startOperation = r.startOperation; frozenVersion = r.frozenVersion; saveHash = r.saveHash
            configurationVersion = r.configurationVersion
            seatRevision = r.seats.firstOrNull { it.playerId == userId && it.occupancy == session.occupancy }?.revision ?: 0L
        }
    }
    private fun seatCommand(s: LobbySeat) = command().apply {
        seatId = s.seatId; seatRevision = s.revision; targetOccupancy = s.occupancy
    }
    private fun accept(latest: LobbyRoom) {
        if (latest.version < (room?.version ?: -1)) return
        val settingsChanged = room?.configurationVersion != latest.configurationVersion || configurationTable == null || latest.state != "WAITING"
        if (latest.state != "WAITING") { configurationPending = false; cancelConfigurationSync() }
        if (settingsChanged && !configurationPending) {
            mapSettings?.cancelBackgroundJobs()
            configurationTable = null
            setup = json().fromJson(GameSetupInfo::class.java,latest.setup)
            ruleset.clear(); ruleset.add(RulesetCache.getComplexRuleset(gameSetupInfo.gameParameters))
            seatSources = ArrayList(latest.seats.map { it.seatId })
        }
        if (!configurationPending) setup?.gameParameters?.players = ArrayList(latest.seats.map {
            Player(it.civilization,if (it.human) PlayerType.Human else PlayerType.AI,it.playerId)
        })
        room = latest
        session.gameId = latest.gameId
        session.setup = latest.setup
        session.occupancy = latest.seats.firstOrNull { it.playerId == userId }?.occupancy.orEmpty()
    }
    private fun runRequest(refreshOnly: Boolean = false, configurationWrite: Boolean = false,
                           backAfter: Boolean = false, leaveAfter: Boolean = false, action: suspend () -> LobbyRoom) {
        if (disposed || paused || !connected) return
        if (busy) {
            if (!refreshOnly) pendingRequests.add { runRequest(refreshOnly,configurationWrite,backAfter,leaveAfter,action) }
            return
        }
        busy = true; if (!refreshOnly) drawMembers()
        val sentRevision = draftRevision
        val generation = connectionGeneration
        job = Concurrency.run("Lobby request") {
            try {
                val result = action()
                if (result.gameServerUrl != session.gameServerUrl)
                    throw UncivShowableException("The invitation game server differs from this room.")
                launchOnGLThread {
                    if (disposed || paused || generation != connectionGeneration) return@launchOnGLThread
                    busy = false
                    if (leaveAfter) { session.clearIfCurrent(); game.popScreen(); return@launchOnGLThread }
                    if (configurationWrite && sentRevision == draftRevision) {
                        configurationPending = false; configurationTable = null; cancelConfigurationSync()
                    }
                    if (configurationWrite && configurationPending) draftBaseConfigurationVersion = result.configurationVersion
                    accept(result); drawMembers()
                    if (backAfter) {
                        if (configurationPending) saveConfiguration(backAfter = true) else game.popScreen()
                        return@launchOnGLThread
                    }
                    if (result.state == "STARTED" && result.seats.any { it.playerId == userId && it.occupancy == session.occupancy }) enterGame()
                    processPendingRequests()
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                launchOnGLThread {
                    if (disposed || paused || generation != connectionGeneration) return@launchOnGLThread
                    busy = false
                    if (configurationWrite && sentRevision == draftRevision && e is LobbyConnectionException && e.code == "COMMAND_FAILED") {
                        configurationPending = false; configurationTable = null; cancelConfigurationSync()
                        room?.let { accept(it) }
                        errorPopup(this@MultiplayerLobbyScreen,e)
                    }
                    status.setText((e.message ?: "Connection failed. Retrying...").tr()); drawMembers(updateStatus = false); renderedState = ""
                    if (e is LobbyConnectionException && e.code == "COMMAND_FAILED") processPendingRequests()
                }
            }
        }
    }
    private fun drawMembers(updateStatus: Boolean = true, force: Boolean = false) {
        val r = room
        // Heartbeats change version/lastSeen but should not replace actors under a player's finger.
        val signature = "$connected|$busy|$configurationPending|${r?.configurationVersion}|${r?.state}|${r?.reason}|${r?.seats?.map { listOf(it.seatId,it.playerId,it.occupancy,it.manual,it.civilization,it.ready) }}"
        if (!force && updateStatus && renderedState == signature) return
        renderedState = signature
        val scrollPositions = scrollPanes.map { it.scrollY }
        content.clear()
        body.clear()
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
            !connected -> "Connection failed. Retrying...".tr()
            busy -> Constants.working.tr()
            configurationPending -> "Syncing room settings...".tr()
            setup?.gameParameters?.victoryTypes?.isEmpty() == true -> "No victory conditions were selected!".tr()
            joined < humans.size -> "Invite friends to fill the empty seats.".tr()
            ready < humans.size && host -> "Waiting for other players to get ready.".tr()
            ready < humans.size -> "Choose your civilization, then get ready.".tr()
            host -> "Everyone is ready. You can start the game.".tr()
            else -> "Everyone is ready. Waiting for the host to start.".tr()
        })
        inviteButton.isEnabled = connected && r?.state == "WAITING" && own != null && !busy && !configurationPending
        copyInvitationButton.isEnabled = inviteButton.isEnabled
        val width = stage.width - 32f
        val landscape = r != null && !isNarrowerThan4to3()
        if (landscape || UiNavigation.isIos) {
            scroll.actor = null
            body.add(content).grow()
        } else {
            scroll.actor = content
            body.add(scroll).grow()
        }
        if (r != null) {
            val gameSetup = setup ?: json().fromJson(GameSetupInfo::class.java,r.setup).also { setup = it }
            val editable = host && own != null && configurationSupported && r.state == "WAITING"
            val overviewWidth = if (UiNavigation.isIos) width else GameSetupOverview.contentWidth(width, !landscape)
            val settingsWidth = if (UiNavigation.isIos) overviewWidth else if (landscape) (width - 32f) * 2f / 3f + 16f else width
            val cardsWidth = if (UiNavigation.isIos) {
                if (landscape && setupSection == GameSetupSection.Overview) (overviewWidth - 20f) * 0.58f else overviewWidth
            } else if (landscape) width - settingsWidth - 16f else width
            fun summary(values: List<String>) = Table().apply {
                background = skinStrings.getUiBackground("MultiplayerLobbyScreen/GameSummary", skinStrings.roundedEdgeRectangleSmallShape, Color.valueOf("0d2140"))
                pad(18f)
                defaults().left().growX().padBottom(10f)
                for (value in values)
                    add(value.toLabel(fontSize = 18).apply { setWrap(true) }).row()
            }
            val gameValues = listOf(gameSetup.gameParameters.baseRuleset, gameSetup.gameParameters.speed,
                gameSetup.gameParameters.difficulty, gameSetup.gameParameters.startingEra)
            val mapValues = listOf(gameSetup.mapParameters.type, gameSetup.mapParameters.mapSize.name)
            val playersHeading = Table().apply {
                left()
                add("Players".toLabel(fontSize = 24)).left().row()
                val counts = if (humans.any { it.manual }) "[$joined]/[${humans.size}] seats filled · [$ready]/[${humans.size}] ready"
                    else "[$joined]/[${humans.size}] joined · [$ready]/[${humans.size}] ready"
                add(counts.toLabel(Color.LIGHT_GRAY,18)).left().padTop(4f).padBottom(12f).row()
            }
            val players = Table()
            if (!landscape && !UiNavigation.isIos) players.add(playersHeading).growX().row()
            if (own == null && r.state == "WAITING") players.add("Rejoin room".toTextButton().apply {
                onClick {
                    session.occupancy = ""; session.pendingAction = ""; session.pendingRequestId = ""; session.pendingCommand = ""
                    session.save(); client.stop(); connectRoom(join = true)
                }
                isEnabled = !busy
            }).height(44f).padBottom(12f).row()
            val grid = Table()
            val columns = if (!UiNavigation.isIos && cardsWidth >= 640f) 2 else 1
            val cardWidth = (cardsWidth - (columns - 1) * 12f) / columns
            for ((index,s) in r.seats.withIndex()) {
                grid.add(if (UiNavigation.isIos) compactPlayerRow(r,s,own,cardWidth) else playerCard(r,s,own,cardWidth)).width(cardWidth).growY().padBottom(12f)
                    .padRight(if (index % columns == 0 && columns == 2) 12f else 0f)
                if (index % columns == columns-1) grid.row()
            }
            players.add(grid).growX().row()
            if (editable) players.add("Add player".toTextButton().apply {
                isEnabled = connected && !busy && !configurationPending && r.seats.size < minOf(64,ruleset.nations.values.count { it.isMajorCiv })
                onClick {
                    gameSetupInfo.gameParameters.players.add(Player(playerType = PlayerType.Human))
                    seatSources.add(-1); markConfigurationChanged(immediate = true)
                }
            }).height(44f).padBottom(12f).row()
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
            val settings = if (editable || UiNavigation.isIos) configurationTable ?: buildConfiguration(settingsWidth).also { configurationTable = it }
                else if (landscape) Table().apply {
                    add(scrollColumn("Game Options".toLabel(fontSize = 24), summary(gameValues), gameOptionsScroll)).width(cardsWidth).growY().padRight(16f)
                    add(scrollColumn("Map Options".toLabel(fontSize = 24), summary(mapValues), mapOptionsScroll)).width(cardsWidth).growY()
                } else Table().apply {
                    add("Game setup".toLabel(Color.GOLD,22)).left().row()
                    add(summary(mapValues + gameValues)).growX()
                }
            settings.touchable = if (!connected || busy || r.state != "WAITING") Touchable.disabled else Touchable.childrenOnly
            if (UiNavigation.isIos) {
                drawOverviewPage(width, !landscape, cardsWidth, playersHeading, players, editable)
            } else if (landscape) {
                content.add(settings).width(settingsWidth).growY().padRight(16f)
                content.add(scrollColumn(playersHeading, players, playersScroll)).width(cardsWidth).growY()
            } else if (editable) {
                content.add(settings).width(width).top().row()
                content.add(players).width(width).padTop(16f)
            } else {
                content.add(players).width(width).top().row()
                content.add(settings).width(width).padTop(12f)
            }
        }
        val leave = (if(r == null) "Cancel" else if(host) "Close room" else "Leave room").toTextButton().apply {
            onClick {
                if (r == null || r.state == "CLOSED" || own == null) game.popScreen() else leaveRoom()
            }; isEnabled = !busy
        }
        footer.add(status).growX().left().padRight(12f).colspan(if (UiNavigation.isIos && stage.width < 850f) 2 else 1)
        if (stage.width < 850f) footer.row()
        footer.add(leave).height(44f).padRight(if (host) 10f else 0f)
        if (host) {
            startButton.setText(lobbyStartButtonText(r.state,busy).tr())
            startButton.isEnabled = connected && !busy && !configurationPending && own != null && (r.state == "STARTING" || (r.state == "WAITING" && joined == humans.size && ready == humans.size && gameSetupInfo.gameParameters.victoryTypes.isNotEmpty()))
            footer.add(startButton).height(44f)
        }
        if (UiNavigation.isIos && !host && own != null && r.state == "WAITING") {
            footer.add((if (own.ready) "Cancel ready" else "Ready").toTextButton().apply {
                isEnabled = connected && !busy && !configurationPending
                onClick { runRequest { client.command(session.gameId,"ready",command().apply { this.ready = !own.ready }) } }
            }).height(44f).padLeft(10f)
        }
        if (r == null && !busy) footer.add("Retry".toTextButton().apply { onClick {
            if (session.pendingAction == "join") { session.pendingAction = ""; session.pendingRequestId = ""; session.pendingCommand = "" }
            session.save(); client.stop(); connectRoom(join = session.gameId.isNotEmpty())
        } }).height(44f)
        if (r?.state == "STARTED" && !busy && !enteringGame && own != null)
            footer.add("Retry".toTextButton().apply { onClick { enterGame() } }).height(44f)
        layout.invalidateHierarchy()
        layout.validate()
        scrollPanes.forEachIndexed { index, pane ->
            pane.validate()
            pane.scrollY = scrollPositions[index]
            pane.updateVisualScroll()
        }
    }

    private fun selectSetupSection(section: GameSetupSection) {
        setupSection = section
        scrollPanes.forEach { it.scrollY = 0f }
        drawMembers(force = true)
    }

    private fun drawOverviewPage(width: Float, portrait: Boolean, cardsWidth: Float,
                                 playersHeading: Table, players: Table, editable: Boolean) {
        val page = Table().apply { top() }
        val settings = configurationTable!!
        val section = setupSection
        if (section == GameSetupSection.World || section == GameSetupSection.Rules) {
            val selectedContent = if (section == GameSetupSection.World) mapSettings!! else gameSettings!!
            if (selectedContent.parent !== settings) {
                settings.clearChildren()
                settings.add(selectedContent).width(width).top()
            }
            settings.touchable = if (editable && connected && !busy) Touchable.childrenOnly else Touchable.disabled
            page.add((if (editable) "Changes are shared with everyone in the room."
                else "Only the host can change room rules.").toLabel(Color.LIGHT_GRAY, 16).apply { setWrap(true) })
                .width(width).left().padBottom(12f).row()
            val pane = if (section == GameSetupSection.World) mapOptionsScroll else gameOptionsScroll
            pane.actor = settings
            page.add(pane).grow().minHeight(0f)
        } else if (section == GameSetupSection.Civilizations) {
            drawCivilizationsPage(page, width, portrait)
        } else {
            val roster = Table().apply {
                top()
                add(playersHeading).growX().row()
                add(Table().apply {
                    add(inviteButton).width(((if (portrait) width else cardsWidth) - 10f) / 2f).minHeight(44f).padRight(10f)
                    add(copyInvitationButton).width(((if (portrait) width else cardsWidth) - 10f) / 2f).minHeight(44f)
                }).left().padBottom(14f).row()
                if (portrait) add(players).growX().top()
                else {
                    playersScroll.actor = players
                    add(playersScroll).grow().minHeight(0f)
                }
            }
            val summaryWidth = if (portrait) width else width - cardsWidth - 20f
            val summary = GameSetupOverview.card().apply {
                val innerWidth = summaryWidth - 36f
                defaults().width(innerWidth).left()
                add("Room rules".toLabel(fontSize = 24)).padBottom(12f).row()
                add(mapSettings!!.createOverviewPreview(innerWidth, 130f)).padBottom(10f).row()
                add(GameSetupOverview.summaryRow("World", GameSetupOverview.mapDescription(gameSetupInfo)) {
                    selectSetupSection(GameSetupSection.World)
                }).row()
                add(GameSetupOverview.summaryRow("Game pace", "${gameSetupInfo.gameParameters.difficulty.tr()} · ${gameSetupInfo.gameParameters.speed.tr()}") {
                    selectSetupSection(GameSetupSection.Rules)
                }).row()
                add(GameSetupOverview.summaryRow("Victory Conditions", GameSetupOverview.victoryDescription(gameSetupInfo)) {
                    selectSetupSection(GameSetupSection.Rules)
                })
            }
            if (portrait) {
                scroll.actor = Table().apply {
                    top()
                    add(roster).width(width).row()
                    add(summary).width(width).padTop(18f)
                }
                page.add(scroll).grow().minHeight(0f)
            } else {
                page.add(roster).width(cardsWidth).growY().top().padRight(20f)
                gameOptionsScroll.actor = summary
                page.add(gameOptionsScroll).width(summaryWidth).growY().top().minHeight(0f)
            }
        }
        // The four pages share a compact horizontal navigation bar in both orientations.
        content.add(GameSetupOverview.layout(width, true, section, page,
            multiplayer = true, select = ::selectSetupSection)).width(width).growY()
    }

    private fun compactPlayerRow(r: LobbyRoom, s: LobbySeat, own: LobbySeat?, width: Float): Table {
        val empty = s.human && s.playerId.isEmpty()
        val title = when {
            empty -> "Waiting for player"
            !s.human -> "AI"
            s === own && r.hostId == userId -> "Host · You"
            s === own -> "You"
            s.playerId == r.hostId -> "Host"
            else -> "Player [${s.seatId + 1}]"
        }
        return GameSetupOverview.card().apply {
            pad(12f)
            add("${s.seatId + 1}".toLabel(Color.LIGHT_GRAY, 16)).width(24f).padRight(8f)
            val nation = ruleset.nations[s.civilization]
            val portrait = if (nation == null) ImageGetter.getRandomNationPortrait(38f)
                else ImageGetter.getNationPortrait(nation, 38f)
            add(portrait).size(38f).padRight(12f)
            add(Table().apply {
                add(title.toLabel(fontSize = 18).apply { setWrap(true) }).growX().left().row()
                add((if (empty) "Human" else s.civilization).toLabel(Color.LIGHT_GRAY, 16, hideIcons = true)
                    .apply { setWrap(true) }).growX().left().padTop(5f)
            }).minWidth(0f).growX()
            if (s.human && !empty) add((if (s.ready) "Ready" else "Not ready")
                .toLabel(if (s.ready) ExpeditionBlueTheme.gold else Color.LIGHT_GRAY, 14)).padLeft(8f)
            add("…".toTextButton().apply {
                onClick {
                    Popup(this@MultiplayerLobbyScreen).apply {
                        add(playerCard(r,s,own,minOf(520f,this@MultiplayerLobbyScreen.stage.width - 80f))).row()
                        addCloseButton()
                        open()
                    }
                }
            }).size(44f).padLeft(8f)
            portrait.touchable = Touchable.enabled
            portrait.onClick {
                selectedSeatId = s.seatId
                previewCivilization = s.civilization
                selectSetupSection(GameSetupSection.Civilizations)
            }
        }
    }

    private fun canChooseCivilization(r: LobbyRoom, s: LobbySeat, own: LobbySeat?): Boolean =
        own != null && connected && !busy && !configurationPending && r.state == "WAITING" &&
            (s === own || (r.hostId == userId && (s.manual || (!s.human && configurationSupported))))

    private fun drawCivilizationsPage(page: Table, width: Float, portrait: Boolean) {
        val r = room!!
        val own = r.seats.firstOrNull { it.playerId == userId && it.occupancy == session.occupancy }
        val selectedSeat = r.seats.firstOrNull { it.seatId == selectedSeatId } ?: own ?: r.seats.first()
        selectedSeatId = selectedSeat.seatId
        if (previewCivilization.isEmpty() || (previewCivilization != Constants.random &&
                ruleset.nations[previewCivilization]?.isMajorCiv != true))
            previewCivilization = selectedSeat.civilization
        val seatPicker = Table().apply {
            val columns = if (portrait) 2 else 4
            for ((index, seat) in r.seats.withIndex()) {
                add(GameSetupOverview.choice("Player [${seat.seatId + 1}]", seat === selectedSeat).apply {
                    onClick {
                        selectedSeatId = seat.seatId
                        previewCivilization = seat.civilization
                        drawMembers(force = true)
                    }
                }).width((width - (columns - 1) * 8f) / columns).minHeight(44f)
                    .padRight(if (index % columns == columns - 1) 0f else 8f).padBottom(8f)
                if (index % columns == columns - 1) row()
            }
        }
        page.add(seatPicker).width(width).padBottom(12f).row()
        val gridWidth = if (portrait) width else (width - 18f) * 0.55f
        val detailWidth = if (portrait) width else width - gridWidth - 18f
        val grid = Table().apply {
            top()
            val choices = listOf(Constants.random) + ruleset.nations.values.filter { it.isMajorCiv }.map { it.name }.sortedBy { it.tr() }
            val columns = if (portrait) 3 else 4
            val tileWidth = (gridWidth - (columns - 1) * 10f) / columns
            for ((index, name) in choices.withIndex()) {
                val available = name == Constants.random || r.seats.none { it !== selectedSeat && it.civilization == name }
                val nation = ruleset.nations[name]
                val tile = GameSetupOverview.card().apply {
                    pad(10f)
                    if (name == previewCivilization) background = skinStrings.getUiBackground("GameSetup/SelectedNation",
                        skinStrings.roundedEdgeRectangleSmallShape, ExpeditionBlueTheme.raised)
                    add(if (nation == null) ImageGetter.getRandomNationPortrait(38f) else ImageGetter.getNationPortrait(nation,38f))
                        .size(38f).padBottom(8f).row()
                    add(name.toLabel(if (available) Color.WHITE else Color.GRAY,16,hideIcons = true).apply { setWrap(true) })
                        .width(tileWidth - 20f)
                    onClick { previewCivilization = name; drawMembers(force = true) }
                }
                add(tile).width(tileWidth).minHeight(105f).fillY().padRight(if (index % columns == columns - 1) 0f else 10f).padBottom(10f)
                if (index % columns == columns - 1) row()
            }
        }
        val detail = GameSetupOverview.card().apply {
            val innerWidth = detailWidth - 36f
            defaults().width(innerWidth).left().padBottom(16f)
            val nation = ruleset.nations[previewCivilization]
            add(previewCivilization.toLabel(ExpeditionBlueTheme.gold,26,hideIcons = true).apply { setWrap(true) }).row()
            val available = previewCivilization == Constants.random || r.seats.none { it !== selectedSeat && it.civilization == previewCivilization }
            add((if (previewCivilization == selectedSeat.civilization) "Selected civilization" else "OK").toTextButton().apply {
                isEnabled = available && canChooseCivilization(r,selectedSeat,own) && previewCivilization != selectedSeat.civilization
                onClick {
                    val civilization = previewCivilization
                    if (!selectedSeat.human) {
                        gameSetupInfo.gameParameters.players[selectedSeat.seatId].chosenCiv = civilization
                        markConfigurationChanged(immediate = true)
                    } else runRequest { client.command(session.gameId,"civilization",seatCommand(selectedSeat).apply { this.civilization = civilization }) }
                }
            }).minHeight(44f).row()
            if (nation != null) {
                fun description() = MarkupRenderer.render(nation.getCivilopediaTextLines(ruleset)
                    .filter { it.header != 3 }, innerWidth - if (portrait) 20f else 0f, iconDisplay = IconDisplay.NoLink)
                if (portrait) add(ExpanderTab("Civilopedia", startsOutOpened = false, expanderWidth = innerWidth) {
                    it.add(description()).growX()
                }).row()
                else add(description()).row()
            }
        }
        if (portrait) {
            // Keep the selected civilization's action reachable before the full collection.
            scroll.actor = Table().apply {
                top()
                add(detail).width(width).row()
                add(grid).width(width).padTop(18f)
            }
            page.add(scroll).grow().minHeight(0f)
        } else {
            playersScroll.actor = grid
            gameOptionsScroll.actor = detail
            page.add(Table().apply {
                add(playersScroll).width(gridWidth).growY().minHeight(0f).padRight(18f)
                add(gameOptionsScroll).width(detailWidth).growY().minHeight(0f)
            }).grow().minHeight(0f)
        }
    }

    private fun scrollColumn(heading: Actor, options: Table, pane: AutoScrollPane) = Table().apply {
        top()
        add(heading).growX().left().padBottom(12f).row()
        pane.actor = options
        add(pane).grow().minHeight(0f)
    }
    private fun buildConfiguration(width: Float): Table {
        ImageGetter.setNewRuleset(ruleset)
        val gameOptions = GameOptionsTable(this, updatePlayerPickerTable = {
            mapSettings?.generateExampleMap()
        }, updatePlayerPickerRandomLabel = {}, allowOnlineToggle = false, forInviteRoom = true,
            onConfigurationChanged = { markConfigurationChanged(immediate = true) },
            roomLayoutWidth = if (UiNavigation.isIos) width else null)
        gameSettings = gameOptions
        mapSettings = MapParametersTable(this,gameSetupInfo.mapParameters,MapGeneratedMainType.generated,
            roomLayoutWidth = if (UiNavigation.isIos) width else null)
        val twoColumns = !isNarrowerThan4to3()
        val columnWidth = if(twoColumns) (width-16f)/2f else width
        fun column(title: String, options: Table, pane: AutoScrollPane) = if (twoColumns)
            scrollColumn(title.toLabel(fontSize = 24), options, pane) else Table().apply {
            add(title.toLabel(fontSize = 24)).left().padBottom(12f).row()
            add(options).width(columnWidth).top()
        }
        return Table().apply {
            top()
            if (!UiNavigation.isIos) {
                add(column("Game Options",gameOptions,gameOptionsScroll)).width(columnWidth).growY().padRight(if(twoColumns) 16f else 0f)
                if(!twoColumns) row()
                add(column("Map Options",mapSettings!!,mapOptionsScroll)).width(columnWidth).growY().padTop(if(twoColumns) 0f else 16f)
            }
            addListener(object : ChangeListener() {
                override fun changed(event: ChangeEvent, actor: Actor) {
                    markConfigurationChanged(immediate = actor !is Slider && actor !is TextField)
                }
            })
        }
    }
    private fun markConfigurationChanged(immediate: Boolean = false) {
        if (!configurationPending) draftBaseConfigurationVersion = room?.configurationVersion ?: -1L
        draftRevision++
        configurationPending = true
        configurationDue = System.currentTimeMillis() + if (immediate) 0L else 600L
        drawMembers()
        scheduleConfigurationSync()
    }
    private fun cancelConfigurationSync() {
        configurationJob?.cancel()
        configurationJob = null
    }
    private fun scheduleConfigurationSync() {
        cancelConfigurationSync()
        if (paused || disposed || !connected || !configurationPending) return
        val remaining = configurationDue - System.currentTimeMillis()
        if (remaining <= 0L) { processPendingRequests(); return }
        // A delayed GL callback wakes non-continuous rendering; render() is not a timer.
        configurationJob = Concurrency.runOnGLThread("Sync lobby configuration") {
            delay(remaining)
            configurationJob = null
            processPendingRequests()
        }
    }
    private fun processPendingRequests() {
        if (paused || busy || disposed || !connected) return
        if (pendingRequests.isNotEmpty()) pendingRequests.removeFirst().invoke()
        else if (configurationPending) {
            if (System.currentTimeMillis() >= configurationDue) saveConfiguration()
            else scheduleConfigurationSync()
        }
    }
    private fun saveConfiguration(backAfter: Boolean = false) {
        if (!connected || busy || !configurationPending || room?.state != "WAITING") return
        val parameters = gameSetupInfo.gameParameters
        parameters.multiplayerServerUrl = session.gameServerUrl
        val update = command().apply {
            configurationVersion = draftBaseConfigurationVersion
            setup = json().toJson(gameSetupInfo)
            baseRuleset = parameters.baseRuleset
            seats = ArrayList(parameters.players.mapIndexed { index, player -> LobbySeat().apply {
                seatId = seatSources[index]; human = player.playerType == PlayerType.Human; civilization = player.chosenCiv
            } })
        }
        runRequest(configurationWrite = true, backAfter = backAfter) { client.command(session.gameId,"configuration",update) }
    }
    private fun playerCard(r: LobbyRoom, s: LobbySeat, own: LobbySeat?, width: Float): Table {
        val mine = s === own
        val empty = s.human && s.playerId.isEmpty()
        val manualControl = s.manual && r.hostId == userId && own != null && r.state == "WAITING"
        val card = BorderedTable("MultiplayerLobbyScreen/PlayerCard",skinStrings.roundedEdgeRectangleSmallShape,skinStrings.roundedEdgeRectangleSmallShape).apply {
            bgColor = ExpeditionBlueTheme.color(Color.valueOf(if (mine) "182c46" else "0d2140"), if (mine) ExpeditionBlueTheme.raised else ExpeditionBlueTheme.panel)
            bgBorderColor = ExpeditionBlueTheme.color(if (mine) Color.GOLD else Color.valueOf("294568"), if (mine) ExpeditionBlueTheme.gold else ExpeditionBlueTheme.border)
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
            if (r.hostId == userId && own != null && configurationSupported && r.state == "WAITING" && !mine && s.playerId.isEmpty()) {
                add((if(s.human) "Human" else "AI").toTextButton().apply {
                    isEnabled = connected && !busy && !configurationPending
                    onClick {
                        gameSetupInfo.gameParameters.players[s.seatId].apply {
                            playerType = if(s.human) PlayerType.AI else PlayerType.Human
                            chosenCiv = Constants.random
                        }
                        markConfigurationChanged(immediate = true)
                    }
                }).height(44f).padLeft(8f)
            }
            if (s.human && !empty) add((if (s.ready) "Ready" else "Not ready").toLabel(if (s.ready) Color.GREEN else Color.LIGHT_GRAY,16)).padLeft(8f)
        }
        card.add(heading).growX().row()
        if (s.manual) card.add(Table().apply {
            add("Manually added".toLabel(Color.LIGHT_GRAY,16)).growX().left()
            if (manualControl) add("Player ID".toTextButton().apply {
                isEnabled = connected && !busy && !configurationPending
                onClick { editManualPlayer(s) }
            }).height(36f)
        }).growX().padTop(4f).row()
        val controls = Table()
        if (manualControl) controls.add((if (s.ready) "Cancel ready" else "Mark ready").toTextButton().apply {
            isEnabled = connected && !busy && !configurationPending
            onClick {
                val update = seatCommand(s).apply { ready = !s.ready }
                runRequest { client.command(session.gameId,"ready",update) }
            }
        }).height(44f).padRight(8f)
        when {
            mine && r.state == "WAITING" -> {
                if (r.hostId != userId) controls.add((if (s.ready) "Cancel ready" else "Ready").toTextButton().apply { onClick {
                    runRequest { client.command(session.gameId,"ready",command().apply { ready = !s.ready }) }
                }; isEnabled = connected && !busy && !configurationPending }).height(44f)
            }
            r.hostId == userId && s.human && !empty && r.state == "WAITING" -> controls.add("Kick".toTextButton().apply { onClick {
                ConfirmPopup(this@MultiplayerLobbyScreen,"Remove this player from the room?","Kick") {
                    runRequest { client.command(session.gameId,"kick",command().apply { seatId = s.seatId; targetOccupancy = s.occupancy }) }
                }.open()
            }; isEnabled = connected && !busy && !configurationPending }).height(44f).right()
        }
        if (r.hostId == userId && own != null && configurationSupported && r.state == "WAITING" && s.playerId.isEmpty() && r.seats.size > 2)
            controls.add("−".toTextButton().apply {
                isEnabled = connected && !busy && !configurationPending
                onClick {
                    gameSetupInfo.gameParameters.players.removeAt(s.seatId)
                    seatSources.removeAt(s.seatId); markConfigurationChanged(immediate = true)
                }
            }).height(44f).padLeft(8f)
        if (empty) {
            if (controls.hasChildren()) heading.add(controls).padLeft(8f)
            if (r.hostId == userId && own != null && configurationSupported && r.state == "WAITING")
                card.add("Add player manually".toTextButton().apply {
                    isEnabled = connected && !busy && !configurationPending
                    onClick { editManualPlayer(s) }
                }).height(44f).padTop(12f).left()
        } else {
            val civilization = Table().apply {
                val ruleset = RulesetCache.getComplexRuleset(setup!!.gameParameters)
                val nation = ruleset.nations[s.civilization]
                val portrait = if (nation == null) ImageGetter.getRandomNationPortrait(44f) else ImageGetter.getNationPortrait(nation,44f)
                if (r.state == "WAITING" && (mine || manualControl || (!s.human && r.hostId == userId && own != null && configurationSupported))) {
                    portrait.touchable = Touchable.enabled
                    portrait.onClick {
                        if (busy || configurationPending) return@onClick
                        if (mine || manualControl) chooseCivilization(s) else chooseAiCivilization(s)
                    }
                }
                add(portrait).size(44f).padRight(12f)
                add(s.civilization.toLabel(fontSize = 22, hideIcons = true).apply { setWrap(true) }).minWidth(0f).growX().left()
            }
            if (controls.hasChildren()) civilization.add(controls).padLeft(8f)
            card.add(civilization).width(width-32f).padTop(12f)
        }
        return card
    }
    private fun editManualPlayer(s: LobbySeat) {
        val popup = Popup(this)
        val field = UncivTextField("Please input Player ID!",s.playerId)
        val update = seatCommand(s)
        popup.addGoodSizedLabel(if (s.manual) "Edit manual player" else "Add player manually").row()
        popup.addGoodSizedLabel("Paste the Player ID sent by the Android player. After starting, send them the Game ID.").row()
        popup.add(field).width(stage.width * 0.6f).height(44f).row()
        popup.add("Paste player ID from clipboard".toTextButton().apply {
            onClick { field.text = Gdx.app.clipboard.contents }
        }).height(44f).row()
        popup.addCloseButton("Cancel")
        popup.addButton("Save",KeyCharAndCode.RETURN) {
            val id = IdChecker.checkAndReturnPlayerUuid(field.text)?.playerID
            if (id == null) { ToastPopup("Player ID is incorrect",this); return@addButton }
            if (room?.seats?.any { it.seatId != s.seatId && it.playerId == id } == true) {
                ToastPopup("Player ID already used!",this); return@addButton
            }
            update.manualPlayerId = id
            popup.close()
            runRequest { client.command(session.gameId,"manualPlayer",update) }
        }
        popup.open()
    }
    private fun chooseAiCivilization(s: LobbySeat) {
        val player = gameSetupInfo.gameParameters.players[s.seatId]
        val available = ruleset.nations.values.filter { it.isMajorCiv && (it.name == s.civilization || room!!.seats.none { seat -> seat.civilization == it.name }) }
        NationPickerPopup(null,Player(s.civilization,PlayerType.AI),false,screen = this,ruleset = ruleset,availableCivs = available,
            submit = { civ -> player.chosenCiv = civ; markConfigurationChanged(immediate = true) }).open()
    }
    override fun resize(width: Int, height: Int) {
        super.resize(width,height)
        mapSettings?.cancelBackgroundJobs()
        configurationTable = null
        drawMembers(force = true)
    }
    private fun chooseCivilization(s: LobbySeat) {
        val r = room ?: return
        val ruleset = RulesetCache.getComplexRuleset(json().fromJson(GameSetupInfo::class.java,r.setup).gameParameters)
        val available = ruleset.nations.values.filter { it.isMajorCiv && (it.name == s.civilization || r.seats.none { seat -> seat.civilization == it.name }) }
        NationPickerPopup(null,Player(s.civilization,PlayerType.Human),false,screen = this,ruleset = ruleset,availableCivs = available,
            submit = { civ -> runRequest { client.command(session.gameId,"civilization",seatCommand(s).apply { civilization = civ }) } }).open()
    }
    private fun requestStart() {
        if (room?.state == "WAITING") {
            if (gameSetupInfo.gameParameters.victoryTypes.isEmpty()) {
                errorPopup(this,UncivShowableException("No victory conditions were selected!"))
                return
            }
            val map = gameSetupInfo.mapParameters
            val message = map.mapSize.fixUndesiredSizes(map.worldWrap)
            if (message != null) {
                mapSettings?.update()
                markConfigurationChanged(immediate = true)
                errorPopup(this,UncivShowableException(message))
                return
            }
        }
        runRequest { start() }
    }
    private suspend fun start(): LobbyRoom = LobbyGameStarter(session,client).start(command())
    private fun enterGame() {
        if (busy || enteringGame) return
        busy = true; enteringGame = true
        drawMembers()
        job = Concurrency.run("Enter lobby game") {
            try {
                val latest = game.onlineMultiplayer.downloadInvitedGame(MultiplayerGameInvite(session.gameId,session.gameServerUrl))
                val worldScreen = game.loadGame(latest)
                session.clearIfCurrent()
                launchOnGLThread {
                    Gdx.app.clipboard.contents = latest.gameId
                    ToastPopup("Game ID copied to clipboard!".tr(),worldScreen,2500)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { launchOnGLThread { if (!disposed) { busy = false; enteringGame = false; drawMembers(); errorPopup(this@MultiplayerLobbyScreen,e) } } }
        }
    }
    private fun disconnectRoom() {
        cancelConfigurationSync()
        connectionGeneration++; connected = false; client.stop()
    }
    override fun pause() { paused = true; disconnectRoom(); if (!enteringGame) job?.cancel(); busy = false }
    override fun resume() { if (!paused) return; paused = false; connectRoom() }
    override fun hide() {
        paused = true; disconnectRoom(); mapSettings?.cancelBackgroundJobs(); if (!enteringGame) job?.cancel()
        busy = false; pendingRequests.clear(); configurationPending = false; configurationTable = null
        room?.let { setup = json().fromJson(GameSetupInfo::class.java,it.setup) }
    }
    override fun show() { super.show(); paused = false; connectRoom() }
    override fun dispose() { disposed = true; disconnectRoom(); mapSettings?.cancelBackgroundJobs(); if (!enteringGame) job?.cancel(); super.dispose() }
}
