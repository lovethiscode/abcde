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
import com.unciv.ui.screens.newgamescreen.NationPickerPopup
import com.unciv.ui.screens.newgamescreen.IPreviousScreen
import com.unciv.ui.screens.newgamescreen.GameOptionsTable
import com.unciv.ui.screens.newgamescreen.GameSetupOverview
import com.unciv.ui.screens.newgamescreen.MapParametersTable
import com.unciv.ui.screens.newgamescreen.SinglePlayerSetup
import com.unciv.ui.components.fonts.Fonts
import com.unciv.ui.components.fonts.FontFamilyData
import com.unciv.ui.components.fonts.FontImplementation
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.badlogic.gdx.scenes.scene2d.ui.CheckBox
import com.badlogic.gdx.scenes.scene2d.ui.SelectBox
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
    private val header = Table()
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
    private var configurationPending = false
    private var draftRevision = 0L
    private var draftBaseConfigurationVersion = -1L
    private var configurationDue = 0L
    private var configurationJob: Job? = null
    private var seatSources = arrayListOf<Int>()
    private val status = "Connecting to room...".toLabel()
    private val roomRole = "".toLabel(fontSize = 18)
    private val setupFont = lazy {
        Fonts.fontImplementation.getBitmapFont().apply { data.setScale(18f / Fonts.ORIGINAL_FONT_SIZE) }
    }
    internal val formFont get() = setupFont.value
    private val headingFont = lazy {
        val renderer = Fonts.fontImplementation.javaClass.getDeclaredConstructor().newInstance() as FontImplementation
        renderer.setFontFamily(FontFamilyData("Hiragino Mincho ProN"), Fonts.ORIGINAL_FONT_SIZE.toInt())
        renderer.getBitmapFont()
    }
    internal fun councilHeading(text: String, size: Int = 24) = text.toLabel(ExpeditionBlueTheme.text, size, hideIcons = true).apply {
        style = Label.LabelStyle(style).apply { font = headingFont.value }
        setFontScale(size / Fonts.ORIGINAL_FONT_SIZE)
    }
    private val councilColumnWidth get() = if (isNarrowerThan4to3()) stage.width - 48f else (stage.width - 72f) * .31f
    private fun stylePrimary(button: TextButton) {
        SinglePlayerSetup.style(button, formFont)
        button.style = TextButton.TextButtonStyle(button.style).apply {
            up = SinglePlayerSetup.surface(ExpeditionBlueTheme.gold, ExpeditionBlueTheme.gold)
            down = SinglePlayerSetup.surface(ExpeditionBlueTheme.hero, ExpeditionBlueTheme.gold)
            over = up
            disabled = SinglePlayerSetup.surface(ExpeditionBlueTheme.panel)
            fontColor = ExpeditionBlueTheme.primaryInk
            disabledFontColor = ExpeditionBlueTheme.muted
        }
    }
    private fun setCouncilEnabled(button: TextButton, enabled: Boolean) {
        if (!UiNavigation.isIos) button.isEnabled = enabled
        else {
            button.isDisabled = !enabled
            button.touchable = if (enabled) Touchable.enabled else Touchable.disabled
        }
    }

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
        layoutHeader()
        inviteButton.setText("Share invitation".tr())
        inviteButton.label.setWrap(true)
        copyInvitationButton.label.setWrap(true)
        inviteButton.onClick { shareInvitation() }
        copyInvitationButton.onClick { copyInvitation() }
        startButton.onClick { requestStart() }
        status.setWrap(true)
        if (UiNavigation.isIos) {
            SinglePlayerSetup.style(inviteButton, formFont)
            SinglePlayerSetup.style(copyInvitationButton, formFont)
            stylePrimary(startButton)
            status.setFontSize(18)
            footer.background = SinglePlayerSetup.surface(ExpeditionBlueTheme.panel)
            footer.pad(6f, 10f, 6f, 10f)
        }
        content.top()
        scrollPanes.forEach {
            it.setScrollingDisabled(true, false)
            it.setOverscroll(false, false)
        }
        layout.background = skinStrings.getUiBackground("MultiplayerLobbyScreen/Background", tintColor = Color.valueOf("08172d"))
        if (UiNavigation.isIos) {
            layout.background = SinglePlayerSetup.surface(ExpeditionBlueTheme.background, ExpeditionBlueTheme.controlBorder)
            layout.setBounds(12f, 8f, stage.width - 24f, stage.height - 16f)
            layout.pad(10f, 12f, 10f, 12f)
        } else {
            layout.setFillParent(true)
            layout.pad(16f)
        }
        layout.add(header).growX().padBottom(if (UiNavigation.isIos) 10f else 16f).row()
        layout.add(body).grow().minWidth(0f).minHeight(0f).top().row()
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
            if (room?.hostId == userId) "Leaving closes the room for everyone." else "Leave this waiting room?",
            if (room?.hostId == userId) "Close room" else "Leave room") {
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
    private fun accept(latest: LobbyRoom, preserveConfiguration: Boolean = false) {
        if (latest.version < (room?.version ?: -1)) return
        val settingsChanged = room?.configurationVersion != latest.configurationVersion || configurationTable == null || latest.state != "WAITING"
        if (latest.state != "WAITING") { configurationPending = false; cancelConfigurationSync() }
        if (settingsChanged && !configurationPending && !preserveConfiguration) {
            mapSettings?.cancelBackgroundJobs()
            configurationTable = null
            setup = json().fromJson(GameSetupInfo::class.java,latest.setup)
            ruleset.clear(); ruleset.add(RulesetCache.getComplexRuleset(gameSetupInfo.gameParameters))
            seatSources = ArrayList(latest.seats.map { it.seatId })
        }
        if (!configurationPending) {
            setup?.gameParameters?.players = ArrayList(latest.seats.map {
                Player(it.civilization,if (it.human) PlayerType.Human else PlayerType.AI,it.playerId)
            })
            seatSources = ArrayList(latest.seats.map { it.seatId })
        }
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
                    val preserveConfiguration = configurationWrite && sentRevision == draftRevision
                    if (preserveConfiguration) {
                        configurationPending = false; cancelConfigurationSync()
                        mapSettings?.generateExampleMap()
                    }
                    if (configurationWrite && configurationPending) draftBaseConfigurationVersion = result.configurationVersion
                    accept(result,preserveConfiguration); drawMembers()
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
        val keyboardFocus = stage.keyboardFocus
        val scrollPositions = scrollPanes.map { it.scrollY }
        content.clear()
        body.clear()
        footer.clear()
        val own = r?.seats?.firstOrNull { it.playerId == userId && it.occupancy == session.occupancy }
        val humans = r?.seats?.filter { it.human }.orEmpty()
        val joined = humans.count { it.playerId.isNotEmpty() }
        val ready = humans.count { it.playerId.isNotEmpty() && it.ready }
        val host = r?.hostId == userId
        if (UiNavigation.isIos) {
            roomRole.setText(if (r == null) "" else (if (host) "Host" else "Member").tr())
            roomRole.color = if (host) ExpeditionBlueTheme.gold else ExpeditionBlueTheme.muted
        }
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
        setCouncilEnabled(inviteButton,connected && r?.state == "WAITING" && own != null && !busy && !configurationPending)
        setCouncilEnabled(copyInvitationButton,inviteButton.isEnabled)
        val width = stage.width - if (UiNavigation.isIos) 48f else 32f
        val landscape = r != null && !isNarrowerThan4to3()
        if (landscape || UiNavigation.isIos) {
            scroll.actor = null
            body.add(content).grow().minWidth(0f).minHeight(0f)
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
                if (landscape) (width - 24f) * .38f else width
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
                if (UiNavigation.isIos) {
                    add(ImageGetter.getImage("OtherIcons/Nations", ExpeditionBlueTheme.gold)).size(24f).padRight(8f)
                    add(councilHeading("Room players",22)).left().growX()
                } else add("Players".toLabel(fontSize = 24)).left().row()
                val counts = if (humans.any { it.manual }) "[$joined]/[${humans.size}] seats filled · [$ready]/[${humans.size}] ready"
                    else "[$joined]/[${humans.size}] joined · [$ready]/[${humans.size}] ready"
                add(counts.toLabel(if (UiNavigation.isIos) ExpeditionBlueTheme.muted else Color.LIGHT_GRAY,
                    if (UiNavigation.isIos) 14 else 18)).left().padTop(4f).padBottom(if (UiNavigation.isIos) 4f else 12f).row()
            }
            val players = Table().apply { if (UiNavigation.isIos) top() }
            if (!landscape && !UiNavigation.isIos) players.add(playersHeading).growX().row()
            if (own == null && r.state == "WAITING") players.add("Rejoin room".toTextButton().apply {
                onClick {
                    session.occupancy = ""; session.pendingAction = ""; session.pendingRequestId = ""; session.pendingCommand = ""
                    session.save(); client.stop(); connectRoom(join = true)
                }
                isEnabled = !busy
            }).height(44f).growX().padBottom(12f).row()
            val grid = Table()
            val columns = if (!UiNavigation.isIos && cardsWidth >= 640f) 2 else 1
            val cardWidth = (cardsWidth - (columns - 1) * 12f) / columns
            for ((index,s) in r.seats.withIndex()) {
                grid.add(if (UiNavigation.isIos) councilPlayerRow(r,s,own,cardWidth) else playerCard(r,s,own,cardWidth)).width(cardWidth).growY().padBottom(if (UiNavigation.isIos) 8f else 12f)
                    .padRight(if (index % columns == 0 && columns == 2) 12f else 0f)
                if (index % columns == columns-1) grid.row()
            }
            players.add(grid).growX().row()
            if (editable) players.add((if (UiNavigation.isIos) "Add seat" else "Add player").toTextButton().apply {
                if (UiNavigation.isIos) SinglePlayerSetup.style(this,formFont)
                setCouncilEnabled(this,connected && !busy && !configurationPending && r.seats.size < minOf(64,ruleset.nations.values.count { it.isMajorCiv }))
                onClick {
                    gameSetupInfo.gameParameters.players.add(Player(playerType = PlayerType.Human))
                    seatSources.add(-1); markConfigurationChanged(immediate = true)
                }
            }).height(44f).growX().padBottom(12f).row()
            val information: (Table) -> Unit = { table ->
                table.add("Game ID".toLabel(Color.LIGHT_GRAY,16)).left().row()
                table.add(r.gameId.toLabel(fontSize = 16).apply { setText(r.gameId); setWrap(true) }).growX().row()
                for (seat in r.seats.filter { it.playerId.isNotEmpty() }) {
                    table.add("Player [${seat.seatId+1}]".toLabel(Color.LIGHT_GRAY,16)).left().padTop(8f).row()
                    table.add(seat.playerId.toLabel(fontSize = 16).apply { setText(seat.playerId); setWrap(true) }).growX().row()
                }
            }
            val details = if (UiNavigation.isIos) SinglePlayerSetup.group("Room information",cardsWidth,
                "Room.Information",detailsOpen,information) else ExpanderTab("Room and player information",fontSize = 18,
                startsOutOpened = detailsOpen,expanderWidth = cardsWidth,onChange = { detailsOpen = !detailsOpen },initContent = information)
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
                drawCouncilPage(width, !landscape, cardsWidth, playersHeading, players, editable)
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
            if (UiNavigation.isIos) UiNavigation.styleTextButton(this)
            onClick {
                if (r == null || r.state == "CLOSED" || own == null) game.popScreen() else leaveRoom()
            }; setCouncilEnabled(this,!busy)
        }
        footer.add(status).growX().minWidth(0f).left().padRight(12f).colspan(if (UiNavigation.isIos && stage.width < 850f) 2 else 1)
        if (stage.width < 850f) footer.row()
        footer.add(leave).height(44f).apply { if (UiNavigation.isIos) width(126f) }.padRight(if (host) 10f else 0f)
        if (host) {
            startButton.setText(lobbyStartButtonText(r.state,busy).tr())
            setCouncilEnabled(startButton,connected && !busy && !configurationPending && own != null && (r.state == "STARTING" || (r.state == "WAITING" && joined == humans.size && ready == humans.size && gameSetupInfo.gameParameters.victoryTypes.isNotEmpty())))
            footer.add(startButton).height(44f).apply { if (UiNavigation.isIos) width(180f) }
        }
        if (UiNavigation.isIos && !host && own != null && r.state == "WAITING") {
            footer.add((if (own.ready) "Cancel ready" else "Ready").toTextButton().apply {
                stylePrimary(this)
                setCouncilEnabled(this,connected && !busy && !configurationPending)
                onClick { runRequest { client.command(session.gameId,"ready",command().apply { this.ready = !own.ready }) } }
            }).height(44f).width(164f).padLeft(10f)
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
        if (keyboardFocus is TextField && keyboardFocus.stage === stage) stage.keyboardFocus = keyboardFocus
    }

    private fun layoutHeader() {
        val back = UiNavigation.backButton().apply {
            onClick { if (configurationPending && connected && !busy) saveConfiguration(backAfter = true) else game.popScreen() }
            keyShortcuts.add(KeyCharAndCode.BACK)
        }
        header.clear()
        header.apply {
            add(back).minHeight(UiNavigation.minimumTouchSize()).padRight(16f)
            if (UiNavigation.isIos && this@MultiplayerLobbyScreen.stage.width < 850f) {
                add(councilHeading("Multiplayer room", 26).apply { wrap = true }).growX().minWidth(0f).left()
                add(roomRole).padLeft(8f)
                row()
                add(Table().apply {
                    add(inviteButton).growX().minWidth(0f).height(44f).padRight(8f)
                    add(copyInvitationButton).growX().minWidth(0f).height(44f)
                }).colspan(3).growX().padTop(8f)
            } else if (UiNavigation.isIos) {
                add(Table().apply {
                    add(ImageGetter.getWhiteDot().apply { color = ExpeditionBlueTheme.gold }).height(1f).growX().padRight(18f)
                    add(councilHeading("Multiplayer room", 28))
                    add(ImageGetter.getWhiteDot().apply { color = ExpeditionBlueTheme.gold }).height(1f).growX().padLeft(18f)
                }).growX().padRight(18f)
                add(roomRole).width(54f).padRight(10f)
                add(inviteButton).width(126f).height(44f).padRight(8f)
                add(copyInvitationButton).width(126f).height(44f)
            } else {
                add("Multiplayer room".toLabel(fontSize = 28)).left().growX()
                if (this@MultiplayerLobbyScreen.stage.width < 850f) row()
                add(inviteButton).height(44f).padRight(8f)
                add(copyInvitationButton).height(44f)
            }
        }
    }

    private fun drawCouncilPage(width: Float, portrait: Boolean, playersWidth: Float,
                                playersHeading: Table, players: Table, editable: Boolean) {
        val columnWidth = if (portrait) width else (width - 24f) * .31f
        val gameContent = if (editable) gameSettings!! else readOnlyGameSettings(columnWidth)
        val mapContent = Table().apply {
            top()
            val previewHeight = (columnWidth / 1.85f).coerceAtMost(190f)
            add(mapSettings!!.createOverviewPreview(columnWidth, previewHeight))
                .size(columnWidth,previewHeight).padBottom(6f).row()
            add("Example map".toLabel(ExpeditionBlueTheme.muted,14)).padBottom(8f).row()
            add(if (editable) mapSettings!! else readOnlyMapSettings(columnWidth)).width(columnWidth).top()
        }
        gameSettings!!.touchable = if (editable && connected && !busy) Touchable.childrenOnly else Touchable.disabled
        mapSettings!!.touchable = gameSettings!!.touchable
        fun heading(title: String, icon: String) = Table().apply {
            add(ImageGetter.getImage(icon,ExpeditionBlueTheme.gold)).size(24f).padRight(8f)
            add(councilHeading(title,22)).left().growX()
            if (!editable) {
                add(ImageGetter.getImage("OtherIcons/LockSmall",ExpeditionBlueTheme.muted)).size(14f).padLeft(8f)
                add("View only".toLabel(ExpeditionBlueTheme.muted,14)).padLeft(5f)
            }
        }
        val columns = Table().apply {
            top()
            val parts = listOf(
                Triple(heading("Game options","OtherIcons/Options"),gameContent,gameOptionsScroll),
                Triple(heading("Map options","OtherIcons/Scenarios"),mapContent,mapOptionsScroll),
                Triple(playersHeading,players,playersScroll)
            )
            for ((index,part) in parts.withIndex()) {
                if (index > 0) {
                    if (portrait) row()
                    else add(ImageGetter.getWhiteDot().apply { color = ExpeditionBlueTheme.border })
                        .width(1f).growY().pad(0f,5.5f)
                }
                val section = if (portrait) Table().apply {
                    top()
                    add(part.first).growX().minWidth(0f).left().padBottom(12f).row()
                    add(part.second).growX().minWidth(0f)
                } else scrollColumn(part.first,part.second,part.third)
                add(section).width(if (index == 2) playersWidth else columnWidth).growY().top()
                    .padBottom(if (portrait) 16f else 0f)
            }
        }
        if (portrait) {
            scroll.actor = columns
            content.add(scroll).grow().minHeight(0f)
        } else content.add(columns).width(width).growY().minHeight(0f)
    }

    private fun readOnlyRows(width: Float, values: List<Pair<String,String>>) = Table().apply {
        top()
        for ((caption,value) in values) {
            add(caption.toLabel(ExpeditionBlueTheme.text,18).apply { wrap = true })
                .width(width * .44f).left().padRight(8f).padBottom(12f)
            add(value.toLabel(ExpeditionBlueTheme.text,18,hideIcons = true).apply { wrap = true; setText(value) })
                .minWidth(0f).growX().left().padBottom(12f).row()
        }
    }

    private fun readOnlyGameSettings(width: Float) = Table().apply {
        val p = gameSetupInfo.gameParameters
        fun state(enabled: Boolean) = (if (enabled) "Yes" else "No").tr()
        top()
        add(readOnlyRows(width,listOf(
            "Difficulty" to p.difficulty.tr(), "Game Speed" to p.speed.tr(),
            "Starting Era" to p.startingEra.tr(),
            "City-State count" to if (p.randomNumberOfCityStates) "${p.minNumberOfCityStates}–${p.maxNumberOfCityStates}" else p.numberOfCityStates.toString()
        ))).width(width).row()
        add(Table().apply {
            add(ImageGetter.getImage("OtherIcons/AchievementTrophy",ExpeditionBlueTheme.gold)).size(22f).padRight(8f)
            add("Victory conditions".toLabel(ExpeditionBlueTheme.gold,20))
        }).left().padTop(8f).padBottom(8f).row()
        add(GameSetupOverview.victoryDescription(gameSetupInfo).toLabel(ExpeditionBlueTheme.text,18)
            .apply { wrap = true }).width(width).left().padBottom(12f).row()
        add(SinglePlayerSetup.group("View advanced settings",width,"Room.ReadOnlyRules") { table ->
            table.add(readOnlyRows(width - 24f,listOf(
                "Base Ruleset" to p.baseRuleset.tr(),
                "Max Turns" to p.maxTurns.toString(),
                "No City Razing" to state(p.noCityRazing),
                "No Barbarians" to state(p.noBarbarians),
                "Raging Barbarians" to state(p.ragingBarbarians),
                "One City Challenge" to state(p.oneCityChallenge),
                "Enable Nuclear Weapons" to state(p.nuclearWeaponsEnabled),
                "Enable Espionage" to state(p.espionageEnabled),
                "Disable starting bias" to state(p.noStartBias),
                "Random number of City-States" to state(p.randomNumberOfCityStates),
                "Set available nations for random pool" to state(p.enableRandomNationsPool),
                "Show Civilization Stats" to state(p.showCivilizationStats == true),
                "Show Rankings" to state(p.showRankings),
                "Show Charts" to state(p.showCharts),
                "Show Demographics" to state(p.showDemographics),
                "Restrict to own civilization" to state(p.hideOtherCivilizationStats),
                "Show additional stat types" to state(p.showAdditionalRankingTypes),
                "Allow anyone to spectate" to state(p.anyoneCanSpectate),
                "Time until skip turn:" to "[${p.minutesUntilSkipTurn}] minutes".tr(),
                "Total time to play:" to "[${p.minutesUntilForceResign}] minutes".tr(),
                "Time recovered per turn:" to "[${p.minutesRecoveredPerTurn}] minutes".tr()
            ))).growX()
            if (p.enableRandomNationsPool) {
                table.row()
                table.add(p.randomNationsPool.joinToString(" · ") { it.tr() }.toLabel().apply { wrap = true })
                    .width(width - 24f)
            }
        }).width(width).padBottom(8f).row()
        add(SinglePlayerSetup.group("View mods",width,"Room.ReadOnlyMods") {
            it.add("Rooms require generated maps without mods".toLabel().apply { wrap = true }).width(width - 24f)
        }).width(width)
    }

    private fun readOnlyMapSettings(width: Float) = Table().apply {
        val p = gameSetupInfo.mapParameters
        fun state(enabled: Boolean) = (if (enabled) "Yes" else "No").tr()
        top()
        add(readOnlyRows(width,listOf("Map type" to p.type.tr(),"Map size" to p.mapSize.name.tr()))).width(width).row()
        add(SinglePlayerSetup.group("View map parameters",width,"Room.ReadOnlyMap") {
            it.add(readOnlyRows(width - 24f,listOf(
                "Map Shape" to p.shape.tr(), "Resource Setting" to p.mapResources.tr(),
                "Width" to p.mapSize.width.toString(), "Height" to p.mapSize.height.toString(), "Radius" to p.mapSize.radius.toString(),
                "Strategic Balance" to state(p.strategicBalance), "Legendary Start" to state(p.legendaryStart),
                "No Ancient Ruins" to state(p.noRuins), "No Natural Wonders" to state(p.noNaturalWonders),
                "World Wrap" to state(p.worldWrap), "Mirroring Type" to p.mirroring.tr(),
                "RNG Seed" to p.seed.toString(), "Map Elevation" to p.elevationExponent.toString(),
                "Temperature intensity" to p.temperatureintensity.toString(), "Temperature shift" to p.temperatureShift.toString(),
                "Vegetation richness" to p.vegetationRichness.toString(), "Rare features richness" to p.rareFeaturesRichness.toString(),
                "Resource richness" to p.resourceRichness.toString(), "Water level" to p.waterThreshold.toString(),
                "Max Coast extension" to p.maxCoastExtension.toString(), "Biome size" to p.tilesPerBiomeArea.toString()
            ))).growX()
        }).width(width)
    }

    private fun councilPlayerRow(r: LobbyRoom, s: LobbySeat, own: LobbySeat?, width: Float): Table {
        val empty = s.human && s.playerId.isEmpty()
        val mine = s === own
        val title = when {
            empty -> "Vacant seat"
            !s.human -> "AI"
            mine && r.hostId == userId -> "Host · You"
            mine -> "You"
            s.playerId == r.hostId -> "Host"
            else -> "Player [${s.seatId+1}]"
        }
        return Table().apply {
            name = "room-seat-${s.seatId}"
            background = SinglePlayerSetup.surface(if (mine) ExpeditionBlueTheme.raised else ExpeditionBlueTheme.panel,
                if (mine && r.hostId == userId) ExpeditionBlueTheme.gold else ExpeditionBlueTheme.border)
            pad(8f)
            add(title.toLabel(if (mine) ExpeditionBlueTheme.gold else ExpeditionBlueTheme.text,18)
                .apply { wrap = true }).width(width * .23f).left().padRight(8f)
            val nation = ruleset.nations[s.civilization]
            val civilization = Table().apply {
                val portrait = when {
                    empty -> ImageGetter.getImage("OtherIcons/Multiplayer",ExpeditionBlueTheme.muted)
                    nation == null -> ImageGetter.getRandomNationPortrait(40f)
                    else -> ImageGetter.getNationPortrait(nation,40f)
                }
                add(portrait).size(40f).padRight(10f)
                add((if (empty) "Waiting for player" else s.civilization).toLabel(ExpeditionBlueTheme.text,18,hideIcons = true)
                    .apply { wrap = true }).minWidth(0f).growX().left()
                if (!empty && canChooseCivilization(r,s,own)) {
                    add(ImageGetter.getImage("OtherIcons/ArrowRight",ExpeditionBlueTheme.text).apply { setOrigin(7f,7f); rotation = -90f })
                        .size(14f).padLeft(6f)
                    touchable = Touchable.enabled
                    onClick { if (s.human) chooseCivilization(s) else chooseAiCivilization(s) }
                }
            }
            add(civilization).minWidth(0f).growX()
            if (!empty) add(Table().apply {
                if (s.human && s.ready)
                    add(ImageGetter.getImage("OtherIcons/Checkmark",ExpeditionBlueTheme.success)).size(18f).padRight(5f)
                add((if (!s.human) "AI" else if (s.ready) "Prepared" else "Not ready")
                    .toLabel(if (s.human && s.ready) ExpeditionBlueTheme.success else ExpeditionBlueTheme.muted,16)
                    .apply { wrap = true }).minWidth(0f).growX()
            }).width(72f).padLeft(8f)
            if (!mine && r.hostId == userId && own != null && r.state == "WAITING") {
                add("".toTextButton().apply {
                    SinglePlayerSetup.style(this,formFont)
                    clearChildren()
                    repeat(3) { add(ImageGetter.getWhiteDot().apply { color = ExpeditionBlueTheme.text }).size(3f).pad(2f) }
                    setCouncilEnabled(this,connected && !busy && !configurationPending)
                    onClick {
                        Popup(this@MultiplayerLobbyScreen).apply {
                            val card = playerCard(r,s,own,minOf(520f,this@MultiplayerLobbyScreen.stage.width - 80f)) { close() }
                            SinglePlayerSetup.style(card,formFont)
                            add(card).row()
                            addCloseButton()
                            open()
                        }
                    }
                }).size(44f).padLeft(6f)
            }
        }
    }

    private fun canChooseCivilization(r: LobbyRoom, s: LobbySeat, own: LobbySeat?): Boolean =
        own != null && connected && !busy && !configurationPending && r.state == "WAITING" &&
            (s === own || (r.hostId == userId && (s.manual || (!s.human && configurationSupported))))

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
            directLayoutWidth = if (UiNavigation.isIos) councilColumnWidth else null,
            directLayoutFont = if (UiNavigation.isIos) formFont else null)
        gameSettings = gameOptions
        mapSettings = MapParametersTable(this,gameSetupInfo.mapParameters,MapGeneratedMainType.generated,
            councilLayoutWidth = if (UiNavigation.isIos) councilColumnWidth else null,
            councilLayoutFont = if (UiNavigation.isIos) formFont else null)
        val changeListener = object : ChangeListener() {
            override fun changed(event: ChangeEvent, actor: Actor) {
                if (actor !is Slider && actor !is TextField && actor !is SelectBox<*> && actor !is CheckBox) return
                if (room?.hostId == userId && connected && !busy && room?.state == "WAITING")
                    markConfigurationChanged(immediate = actor !is Slider && actor !is TextField)
            }
        }
        if (UiNavigation.isIos) {
            gameOptions.addListener(changeListener)
            mapSettings!!.addListener(changeListener)
        }
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
            if (!UiNavigation.isIos) addListener(changeListener)
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
    private fun removeSeat(r: LobbyRoom, s: LobbySeat): Boolean {
        if (!connected || busy || configurationPending || room?.configurationVersion != r.configurationVersion) return false
        val index = seatSources.indexOf(s.seatId)
        val players = gameSetupInfo.gameParameters.players
        if (index !in players.indices) return false
        players.removeAt(index)
        seatSources.removeAt(index)
        return true
    }
    private fun playerCard(r: LobbyRoom, s: LobbySeat, own: LobbySeat?, width: Float, beforeModal: () -> Unit = {}): Table {
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
                        beforeModal()
                        if (busy || configurationPending || room?.configurationVersion != r.configurationVersion) return@onClick
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
                onClick { beforeModal(); editManualPlayer(s) }
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
                beforeModal()
                ConfirmPopup(this@MultiplayerLobbyScreen,"Remove this player from the room?","Kick") {
                    runRequest { client.command(session.gameId,"kick",command().apply { seatId = s.seatId; targetOccupancy = s.occupancy }) }
                }.open()
            }; isEnabled = connected && !busy && !configurationPending }).height(44f).right()
        }
        if (r.hostId == userId && own != null && configurationSupported && r.state == "WAITING" && s.playerId.isEmpty() && r.seats.size > 2)
            controls.add("−".toTextButton().apply {
                isEnabled = connected && !busy && !configurationPending
                onClick {
                    beforeModal()
                    if (removeSeat(r,s)) markConfigurationChanged(immediate = true)
                }
            }).height(44f).padLeft(8f)
        if (empty) {
            if (controls.hasChildren()) heading.add(controls).padLeft(8f)
            if (r.hostId == userId && own != null && configurationSupported && r.state == "WAITING")
                card.add("Add player manually".toTextButton().apply {
                    isEnabled = connected && !busy && !configurationPending
                    onClick { beforeModal(); editManualPlayer(s) }
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
                        beforeModal()
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
        if (UiNavigation.isIos) layout.setBounds(12f, 8f, stage.width - 24f, stage.height - 16f)
        layoutHeader()
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
