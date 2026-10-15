package com.unciv.ui.screens.worldscreen

import com.unciv.ui.components.input.onActivation
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.extensions.setFontSize
import com.badlogic.gdx.Application
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input
import com.badlogic.gdx.utils.Align
import com.unciv.Constants
import com.unciv.UncivGame
import com.unciv.logic.GameInfo
import com.unciv.view.GameView
import com.unciv.logic.UncivShowableException
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.PlayerType
import com.unciv.logic.civilization.diplomacy.DiplomaticStatus
import com.unciv.logic.event.EventBus
import com.unciv.logic.map.HexCoord
import com.unciv.logic.multiplayer.observeMultiplayerTurnSubmission
import com.unciv.logic.multiplayer.MultiplayerGameUpdated
import com.unciv.logic.multiplayer.PendingTurnPersistenceException
import com.unciv.logic.multiplayer.PendingTurnUploadResolution
import com.unciv.logic.multiplayer.PendingTurnUploadState
import com.unciv.logic.multiplayer.TurnUploadUnconfirmedReason
import com.unciv.logic.multiplayer.storage.FileStorageRateLimitReached
import com.unciv.logic.multiplayer.storage.MultiplayerAuthException
import com.unciv.logic.multiplayer.storage.MultiplayerFileNotFoundException
import com.unciv.logic.multiplayer.storage.MultiplayerServer
import com.unciv.logic.trade.TradeEvaluation
import com.unciv.models.TutorialTrigger
import com.unciv.models.metadata.GameSetupInfo
import com.unciv.models.ruleset.tile.ResourceType
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.translations.tr
import com.unciv.ui.components.input.KeyShortcutDispatcherVeto
import com.unciv.ui.components.input.KeyboardBinding
import com.unciv.ui.components.input.KeyboardPanningListener
import com.unciv.ui.popups.AuthPopup
import com.unciv.ui.popups.Popup
import com.unciv.ui.popups.ToastPopup
import com.unciv.ui.popups.hasOpenPopups
import com.unciv.ui.popups.options.OptionsPopupPages
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.cityscreen.CityScreen
import com.unciv.ui.screens.devconsole.DevConsolePopup
import com.unciv.ui.screens.mainmenuscreen.MainMenuScreen
import com.unciv.ui.screens.newgamescreen.NewGameScreen
import com.unciv.ui.screens.overviewscreen.EmpireOverviewCategories
import com.unciv.ui.screens.overviewscreen.EmpireOverviewScreen
import com.unciv.ui.screens.pickerscreens.DiplomaticVoteResultScreen
import com.unciv.ui.screens.pickerscreens.GreatPersonPickerScreen
import com.unciv.ui.screens.savescreens.LoadGameScreen
import com.unciv.ui.screens.savescreens.QuickSave
import com.unciv.ui.screens.savescreens.SaveGameScreen
import com.unciv.ui.screens.victoryscreen.VictoryScreen
import com.unciv.ui.screens.worldscreen.bottombar.BattleTable
import com.unciv.ui.screens.worldscreen.bottombar.TileInfoTable
import com.unciv.ui.screens.worldscreen.chat.ChatButton
import com.unciv.ui.screens.worldscreen.mainmenu.WorldScreenMusicPopup
import com.unciv.ui.screens.worldscreen.minimap.MinimapHolder
import com.unciv.ui.screens.worldscreen.status.AutoPlayStatusButton
import com.unciv.ui.screens.worldscreen.status.MultiplayerStatusButton
import com.unciv.ui.screens.worldscreen.status.NextTurnButton
import com.unciv.ui.screens.worldscreen.status.NextTurnProgress
import com.unciv.ui.screens.worldscreen.status.SmallUnitButton
import com.unciv.ui.screens.worldscreen.status.StatusButtons
import com.unciv.ui.screens.worldscreen.topbar.WorldScreenTopBar
import com.unciv.ui.screens.worldscreen.unit.AutoPlay
import com.unciv.ui.screens.worldscreen.unit.UnitTable
import com.unciv.ui.screens.worldscreen.unit.actions.UnitActionsTable
import com.unciv.ui.screens.worldscreen.worldmap.WorldMapHolder
import com.unciv.ui.screens.worldscreen.worldmap.WorldMapTileUpdater.updateTiles
import com.unciv.utils.Concurrency
import com.unciv.utils.debug
import com.unciv.utils.launchOnGLThread
import com.unciv.utils.launchOnThreadPool
import com.unciv.utils.withGLContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import org.jetbrains.annotations.VisibleForTesting
import yairm210.purity.annotations.Readonly
import java.util.Timer
import kotlin.concurrent.timer

@VisibleForTesting
fun multiplayerUploadFailureReason(exception: Exception): String =
    if (exception is UncivShowableException) exception.localizedMessage else "Unknown".tr()

/**
 * Do not create this screen without seriously thinking about the implications: this is the single most memory-intensive class in the application.
 * There really should ever be only one in memory at the same time, likely managed by [UncivGame].
 *
 * @param gameInfo The game state the screen should represent
 * @param viewingCiv The currently active [civilization][Civilization]
 * @param restoreState
 */
class WorldScreen(
    val gameInfo: GameInfo,
    val autoPlay: AutoPlay,
    internal val viewingCiv: Civilization,
    restoreState: RestoreState? = null
) : BaseScreen(useFullScreenLayout = true) {
    /** When set, causes the screen to update in the next [render][render] event */
    var shouldUpdate = false

    /** Indicates it's the player's ([viewingCiv]) turn */
    var isPlayersTurn = viewingCiv.isCurrentPlayer()
        internal set     // only this class is allowed to make changes
    
    /** Indicates that a game failed to upload, and needs to be uploaded */
    var failedUpload = false
        private set
    private val pendingTurnUploadState = if (gameInfo.gameParameters.isOnlineMultiplayer) {
        game.onlineMultiplayer.pendingTurnUploadStateFor(gameInfo)
    } else PendingTurnUploadState()

    /** Defers reopening an unfulfilled free-Great-Person choice until the player requests it. */
    internal var deferFreeGreatPersonPicker = false

    /** Selected civilization, used in spectator and replay mode, equals viewingCiv in ordinary games */
    var selectedCiv = viewingCiv
        internal set
    /** The [selectedCiv]'s perspective. For non-spectators this this equals the viewingGameView.
     *  Only ever differs from [spectatorGameView] for spectators. */
    var selectedGameView = GameView(gameInfo, selectedCiv, spectatorMode = viewingCiv.isSpectator())
        internal set

    /** The [viewingCiv]'s own perspective.
     * This should only ever be used A. for spectators B. when forOfWar is false, for specific UI elements */
    private val spectatorGameView by lazy {
        if (!viewingCiv.isSpectator()) throw Exception("Managed to disable fog of war without being spectator?!")
        GameView(gameInfo, viewingCiv, spectatorMode = true)
    }  
    fun getGameViewConsideringForOfWar() = if (fogOfWar) selectedGameView else spectatorGameView    

    /** UI toggle only (spectator mode): whether the map/tile panel should show [selectedGameView]'s
     *  (fogged) vision or [spectatorGameView]'s (the spectator's own, unrestricted) vision. */
    var fogOfWar = true

    /** `true` when it's the player's turn unless he is a spectator */
    val canChangeState
        get() = isPlayersTurn && !viewingCiv.isSpectator() && !com.unciv.logic.endless.EndlessRules.blocked(gameInfo) &&
            gameInfo.campaign?.let { it.resultStars > 0 || it.failed } != true

    val mapHolder = WorldMapHolder(this, gameInfo.tileMap)

    internal var waitingForAutosave = false

    // Floating Widgets going counter-clockwise
    internal val topBar = WorldScreenTopBar(this)
    internal val techPolicyAndDiplomacy = TechPolicyDiplomacyButtons(this)
    internal val chatButton = ChatButton(this)
    internal val unitActionsTable = UnitActionsTable(this)
    /** Bottom left widget holding information about a selected unit or city */
    internal val bottomUnitTable = UnitTable(this)
    internal val battleTable = BattleTable(this)
    internal val zoomController = ZoomButtonPair(mapHolder)
    internal val minimapWrapper = MinimapHolder(mapHolder)
    internal val bottomTileInfoTable = TileInfoTable(this)
    internal val notificationsScroll = NotificationsScroll(this)
    internal val nextTurnButton = NextTurnButton(this)
    internal val statusButtons = StatusButtons(nextTurnButton)
    internal val smallUnitButton = SmallUnitButton(this, statusButtons)
    private var endlessPrompt = ""
    private val endlessButton = "Endless survival".toTextButton().apply {
        name = "ui-endless-status"
        label.setFontSize(15)
        label.wrap = true
        onActivation { com.unciv.ui.screens.endless.EndlessPopup(this@WorldScreen) }
    }
    internal var campaignResultShown = false
    private val campaignButton = "Campaign objectives".toTextButton().apply {
        name = "ui-campaign-objectives"
        onActivation { com.unciv.ui.screens.campaign.CampaignTasksPopup(this@WorldScreen) }
    }
    internal val tutorialTaskTable = TutorialTaskTable(this).apply { name = "ui-tutorial-task" }

    private var nextTurnUpdateJob: Job? = null

    private val events = EventBus.EventReceiver()

    private var uiEnabled = true
    private var battlefieldHud: BattlefieldHud? = null
    internal fun layoutBattlefieldHud() { battlefieldHud?.layout() }
    internal fun dismissBattlefieldTileDetails() { battlefieldHud?.dismissTileDetails() }
    internal fun noteBattlefieldMapTap() { battlefieldHud?.onMapTap() }

    internal val undoHandler = UndoHandler(this)


    init {
        if (!gameInfo.allowsPlayerAutoPlay()) autoPlay.stopAutoPlay()

        // notifications are right-aligned, they take up only as much space as necessary.
        notificationsScroll.width = stage.width / 2

        minimapWrapper.x = stage.width - minimapWrapper.width

        // This is the most memory-intensive operation we have currently, most OutOfMemory errors will occur here
        mapHolder.addTiles()
        mapHolder.reloadMaxZoom()

        // resume music (in case choices from the menu lead to instantiation of a new WorldScreen)
        UncivGame.Current.musicController.resume()

        stage.addActor(mapHolder)
        stage.scrollFocus = mapHolder
        stage.addActor(notificationsScroll)  // very low in z-order, so we're free to let it extend _below_ tile info and minimap if we want
        stage.addActor(campaignButton)
        stage.addActor(endlessButton)
        stage.addActor(tutorialTaskTable)    // behind topBar!
        stage.addActor(topBar)
        stage.addActor(statusButtons)
        stage.addActor(techPolicyAndDiplomacy)
        stage.addActor(chatButton)

        stage.addActor(zoomController)
        zoomController.isVisible = UncivGame.Current.settings.showZoomButtons

        stage.addActor(bottomUnitTable)
        stage.addActor(unitActionsTable)
        stage.addActor(bottomTileInfoTable)
        stage.addActor(minimapWrapper)
        battleTable.width = stage.width / 3
        battleTable.x = stage.width / 3
        stage.addActor(battleTable)
        if (BattlefieldHud.enabled) battlefieldHud = BattlefieldHud(this)

        val tileToCenterOn: HexCoord =
                when {
                    viewingCiv.getCapital() != null -> viewingCiv.getCapital()!!.location.toHexCoord()
                    viewingCiv.units.getCivUnits().any() -> viewingCiv.units.getCivUnits().first().getTile().position
                    else -> HexCoord.Zero
                }

        mapHolder.isAutoScrollEnabled = Gdx.app.type == Application.ApplicationType.Desktop && game.settings.mapAutoScroll
        mapHolder.mapPanningSpeed = game.settings.mapPanningSpeed

        // Don't select unit and change selectedCiv when centering as spectator
        mapHolder.setCenterPosition(tileToCenterOn, immediately = true, selectUnit = !viewingCiv.isSpectator())

        tutorialController.allTutorialsShowedCallback = { shouldUpdate = true }

        addKeyboardListener() // for map panning by W,S,A,D
        addKeyboardPresses()  // shortcut keys like F1


        if (gameInfo.gameParameters.isOnlineMultiplayer && !gameInfo.isUpToDate)
            isPlayersTurn = false // until we're up to date, don't let the player do anything

        if (pendingTurnUploadState.requiresRecovery()) {
            failedUpload = true
            isPlayersTurn = false
        }

        if (gameInfo.gameParameters.isOnlineMultiplayer) {
            val gameId = gameInfo.gameId
            val serverUrl = gameInfo.gameParameters.multiplayerServerUrl?.trimEnd('/')
            events.receive(MultiplayerGameUpdated::class, {
                it.preview.gameId == gameId
                    && it.preview.gameParameters.multiplayerServerUrl?.trimEnd('/') == serverUrl
            }) {
                if (isNextTurnUpdateRunning() || game.onlineMultiplayer.hasLatestGameState(gameInfo, it.preview)) {
                    return@receive
                }
                Concurrency.run("Load latest multiplayer state") {
                    loadLatestMultiplayerState()
                }
            }
        }

        if (restoreState != null) restore(restoreState)

        // don't run update() directly, because the UncivGame.worldScreen should be set so that the city buttons and tile groups
        //  know what the viewing civ is.
        shouldUpdate = true
    }

    override fun dispose() {
        resizeDeferTimer?.cancel()
        events.stopReceiving()
        statusButtons.dispose()
        super.dispose()
    }

    override fun getCivilopediaRuleset() = gameInfo.ruleset

    // Handle disabling and re-enabling WASD listener while Options are open
    override fun openOptionsPopup(startingPage: OptionsPopupPages, withDebug: Boolean, onClose: () -> Unit) {
        val oldListener = stage.root.listeners.filterIsInstance<KeyboardPanningListener>().firstOrNull()
        if (oldListener != null) {
            stage.removeListener(oldListener)
            oldListener.dispose()
        }
        super.openOptionsPopup(startingPage, withDebug) {
            addKeyboardListener()
            onClose()
        }
    }

    fun openEmpireOverview(category: EmpireOverviewCategories? = null, selection: String = "") {
        game.pushScreen{ EmpireOverviewScreen(selectedGameView.civView, category, selection) }
    }

    fun openNewGameScreen() {
        if (gameInfo.endless != null) { com.unciv.ui.screens.endless.EndlessPopup(this); return }
        val newGameSetupInfo = GameSetupInfo(gameInfo)
        newGameSetupInfo.mapParameters.reseed()
        val newGameScreen = NewGameScreen(newGameSetupInfo)
        game.pushScreen{ newGameScreen }
    }

    fun openSaveGameScreen() {
        // See #10353 - we don't support locally saving an online multiplayer game
        if (gameInfo.gameParameters.isOnlineMultiplayer) return
        if (gameInfo.campaign != null || gameInfo.endless != null) {
            QuickSave.save(gameInfo, this)
            return
        }
        game.pushScreen{ SaveGameScreen(gameInfo) }
    }

    private fun addKeyboardPresses() {
        globalShortcuts.add(KeyboardBinding.DeselectOrQuit) { backButtonAndESCHandler() }

        // Space and N are assigned in NextTurnButton constructor
        // Functions that have a big button are assigned there (WorldScreenTopBar, TechPolicyDiplomacyButtons..)
        globalShortcuts.add(KeyboardBinding.Civilopedia) { openCivilopedia() }
        globalShortcuts.add(KeyboardBinding.EmpireOverviewTrades) { openEmpireOverview(EmpireOverviewCategories.Trades) }
        globalShortcuts.add(KeyboardBinding.EmpireOverviewUnits) { openEmpireOverview(EmpireOverviewCategories.Units) }
        globalShortcuts.add(KeyboardBinding.EmpireOverviewPolitics) { openEmpireOverview(EmpireOverviewCategories.Politics) }
        globalShortcuts.add(KeyboardBinding.EmpireOverviewNotifications) { openEmpireOverview(EmpireOverviewCategories.Notifications) }
        globalShortcuts.add(KeyboardBinding.VictoryScreen) {
            if (gameInfo.endless != null) com.unciv.ui.screens.endless.EndlessPopup(this)
            else game.pushScreen{ VictoryScreen(this) }
        }
        globalShortcuts.add(KeyboardBinding.EmpireOverviewStats) { openEmpireOverview(EmpireOverviewCategories.Stats) }
        globalShortcuts.add(KeyboardBinding.EmpireOverviewResources) { openEmpireOverview(EmpireOverviewCategories.Resources) }
        globalShortcuts.add(KeyboardBinding.QuickSave) { QuickSave.save(gameInfo, this) }
        globalShortcuts.add(KeyboardBinding.QuickLoad) { QuickSave.load(this) }
        globalShortcuts.add(KeyboardBinding.ViewCapitalCity) {
            val capital = gameInfo.getCurrentPlayerCivilization().getCapital()
            if (capital != null && !mapHolder.setCenterPosition(capital.location.toHexCoord()))
                game.pushScreen{ CityScreen(selectedGameView.getCityView(capital)) }
        }
        globalShortcuts.add(KeyboardBinding.Options) { // Game Options
            openOptionsPopup { nextTurnButton.update() }
        }
        globalShortcuts.add(KeyboardBinding.SaveGame) { openSaveGameScreen() }    //   Save
        globalShortcuts.add(KeyboardBinding.LoadGame) {
            if (gameInfo.endless != null) com.unciv.ui.screens.endless.EndlessPopup(this)
            else game.pushScreen{ LoadGameScreen() }
        }    //   Load
        globalShortcuts.add(KeyboardBinding.QuitGame) { game.popScreen() }    //   WorldScreen is the last screen, so this quits
        globalShortcuts.add(KeyboardBinding.NewGame) { openNewGameScreen() }
        globalShortcuts.add(KeyboardBinding.MusicPlayer) {
            WorldScreenMusicPopup(this).open(force = true)
        }
        globalShortcuts.add(Input.Keys.NUMPAD_ADD) { this.mapHolder.zoomIn() }    //   '+' Zoom
        globalShortcuts.add(Input.Keys.NUMPAD_SUBTRACT) { this.mapHolder.zoomOut() }    //   '-' Zoom
        globalShortcuts.add(KeyboardBinding.ToggleUI) { toggleUI() }
        globalShortcuts.add(KeyboardBinding.ToggleYieldDisplay) { minimapWrapper.yieldImageButton.toggle() }
        globalShortcuts.add(KeyboardBinding.ToggleWorkedTilesDisplay) { minimapWrapper.populationImageButton.toggle() }
        globalShortcuts.add(KeyboardBinding.ToggleMovementDisplay) { minimapWrapper.movementsImageButton.toggle() }
        globalShortcuts.add(KeyboardBinding.ToggleResourceDisplay) { minimapWrapper.resourceImageButton.toggle() }
        globalShortcuts.add(KeyboardBinding.ToggleImprovementDisplay) { minimapWrapper.improvementsImageButton.toggle() }

        globalShortcuts.add(KeyboardBinding.DeveloperConsole, action = ::openDeveloperConsole)
    }

    @Readonly
    fun openDeveloperConsole() {
        // No cheating unless you're by yourself, ignoring a possible spectator
        if (gameInfo.civilizations.count { it.isHuman() && !it.isSpectator() } > 1) return
        DevConsolePopup(this)
    }

    private fun toggleUI() {
        uiEnabled = !uiEnabled
        topBar.isVisible = uiEnabled
        statusButtons.isVisible = uiEnabled
        techPolicyAndDiplomacy.isVisible = uiEnabled
        tutorialTaskTable.isVisible = uiEnabled && gameInfo.campaign == null && gameInfo.endless == null
        campaignButton.isVisible = uiEnabled && gameInfo.campaign != null
        bottomTileInfoTable.isVisible = uiEnabled
        unitActionsTable.isVisible = uiEnabled
        notificationsScroll.isVisible = uiEnabled
        minimapWrapper.isVisible = uiEnabled
        bottomUnitTable.isVisible = uiEnabled
        if (uiEnabled) battleTable.update() else battleTable.isVisible = false
        battlefieldHud?.setVisible(uiEnabled)
    }

    private fun addKeyboardListener() {
        stage.addListener(KeyboardPanningListener(mapHolder, allowWASD = true))
    }

    // We contain a map...
    override fun getShortcutDispatcherVetoer() = KeyShortcutDispatcherVeto.createTileGroupMapDispatcherVetoer()

    private suspend fun loadLatestMultiplayerState(
        replacePendingRecovery: Boolean = false,
    ): Unit = coroutineScope {
        if (game.screen != this@WorldScreen) return@coroutineScope // User already went somewhere else

        val loadingGamePopup = withGLContext {
            Popup(this@WorldScreen).apply {
                addGoodSizedLabel("Loading latest game state...")
                open()
            }
        }

        try {
            debug("loadLatestMultiplayerState current game: gameId: %s, turn: %s, curCiv: %s",
                gameInfo.gameId, gameInfo.turns, gameInfo.currentPlayer)
            val multiplayerServer = game.onlineMultiplayer
                .serverFor(gameInfo.gameParameters.multiplayerServerUrl)
            val latestGame = if (replacePendingRecovery) {
                multiplayerServer.tryDownloadVerifiedGame(gameInfo.gameId).apply {
                    isUpToDate = true
                }
            } else multiplayerServer.downloadGame(gameInfo.gameId)
            debug("loadLatestMultiplayerState downloaded game: gameId: %s, turn: %s, curCiv: %s",
                latestGame.gameId, latestGame.turns, latestGame.currentPlayer)
            if (replacePendingRecovery) {
                game.onlineMultiplayer.persistGamePreviewLocally(latestGame)
                if (!game.files.autosaves.autoSave(latestGame)) {
                    throw UncivShowableException("Could not persist the latest server state locally.")
                }
                pendingTurnUploadState.clear()
            }
            if (viewingCiv.civID == latestGame.currentPlayer || viewingCiv.civID == Constants.spectator) {
                game.notifyTurnStarted()
            }
            launchOnGLThread {
                loadingGamePopup.close()
            }
            startNewScreenJob(latestGame, autoPlay)
        } catch (ex: CancellationException) {
            Concurrency.runOnGLThread { loadingGamePopup.close() }
            throw ex
        } catch (ex: Throwable) {
            launchOnGLThread {
                val (message) = LoadGameScreen.getLoadExceptionMessage(ex, "Couldn't download the latest game state!")
                loadingGamePopup.clear()
                loadingGamePopup.addGoodSizedLabel(message).colspan(2).row()
                loadingGamePopup.addButton("Retry") {
                    launchOnThreadPool("Load latest multiplayer state after error") {
                        loadLatestMultiplayerState(replacePendingRecovery)
                    }
                }.right()
                loadingGamePopup.addButton("Main menu") {
                    game.pushScreen{ MainMenuScreen() }
                }.left()
            }
        }
    }

    // This is private so that we will set the shouldUpdate to true instead.
    // That way, not only do we save a lot of unnecessary updates, we also ensure that all updates are called from the main GL thread
    // and we don't get any silly concurrency problems!
    private fun update() {

        if (uiEnabled) {
            val safeArea = safeAreaBoundsInWorld()
            bottomUnitTable.x = safeArea.x
            unitActionsTable.x = safeArea.x

            displayTutorialsOnUpdate()

            updateSelectedCiv()

            bottomUnitTable.update()

            minimapWrapper.update(getGameViewConsideringForOfWar().civView.getCiv())
            minimapWrapper.x = stage.width - minimapWrapper.width
            bottomTileInfoTable.civView = getGameViewConsideringForOfWar().civView
            bottomTileInfoTable.updateTileTable(mapHolder.selectedTile)
            if (battlefieldHud == null) {
                bottomTileInfoTable.x = stage.width - bottomTileInfoTable.width
                bottomTileInfoTable.y = if (game.settings.showMinimap) minimapWrapper.height + 5f else 0f
            }

            battleTable.update()
        }

        mapHolder.resetArrows()
        if (UncivGame.Current.settings.showUnitMovements) {
            mapHolder.updateMovementOverlay(
                getGameViewConsideringForOfWar(),
                selectedGameView.civView.getUnits().asSequence(),
            )
        }

        zoomController.isVisible = UncivGame.Current.settings.showZoomButtons

        // if we use the clone, then when we update viewable tiles
        // it doesn't update the explored tiles of the civ... need to think about that harder
        // it causes a bug when we move a unit to an unexplored tile (for instance a cavalry unit which can move far)

        mapHolder.updateTiles(getGameViewConsideringForOfWar().civView)

        topBar.update(selectedCiv)
        campaignButton.isVisible = uiEnabled && gameInfo.campaign != null
        if (campaignButton.isVisible) {
            com.unciv.logic.campaign.CampaignRules.actionSettled(gameInfo)
            val state = gameInfo.campaign!!
            val result = com.unciv.logic.campaign.CampaignRules.evaluate(gameInfo)
            val progress = "${listOf(result.main, result.worker, result.population).count { it }} / 3"
            campaignButton.setText(when {
                state.resultStars > 0 || state.failed -> "查看战役结果"
                result.main && !state.definition.automatic -> "战役可结算 · [$progress]"
                else -> "战役任务 · [$progress]"
            }.tr())
            campaignButton.pack()
            campaignButton.setPosition((stage.width - campaignButton.width) / 2, stage.height - topBar.height - campaignButton.height - 6f)
        }

        endlessButton.isVisible = uiEnabled && gameInfo.endless != null
        if (endlessButton.isVisible) {
            com.unciv.logic.endless.EndlessRules.actionSettled(gameInfo)
            endlessButton.setText(com.unciv.ui.screens.endless.endlessStatus(gameInfo.endless!!).tr())
            val s = gameInfo.endless!!
            val prompt = "${s.status}:${s.offer?.stage ?: 0}:${s.saveBlocked}"
            if ((s.ended || s.offer != null || s.saveBlocked) && prompt != endlessPrompt && isPlayersTurn && !hasOpenPopups()) {
                autoPlay.stopAutoPlay()
                if (!s.saveBlocked) try { com.unciv.logic.endless.EndlessStore(game.files).save(gameInfo) }
                catch (ex: Exception) { com.unciv.utils.Log.error("Endless settlement save failed", ex) }
                endlessPrompt = "${s.status}:${s.offer?.stage ?: 0}:${s.saveBlocked}"
                com.unciv.ui.screens.endless.EndlessPopup(this)
            }
        }

        if (techPolicyAndDiplomacy.update())
            displayTutorial(TutorialTrigger.OtherCivEncountered)

        if (uiEnabled) {
            // UnitActionsTable measures geometry (its own y, techPolicyAndDiplomacy and fogOfWarButton), so call update this late
            if (battlefieldHud == null) {
                unitActionsTable.y = bottomUnitTable.height
                unitActionsTable.update(bottomUnitTable.selectedUnit?.getUnit())
            }
        }

        // If the game has ended, lets stop AutoPlay
        if (autoPlay.isAutoPlaying() && !gameInfo.oneMoreTurnMode && (viewingCiv.isDefeated() || gameInfo.checkForVictory())) {
            autoPlay.stopAutoPlay()
        }

        if (!hasOpenPopups() && !autoPlay.isAutoPlaying() && isPlayersTurn) {
            when {
                gameInfo.campaign?.let { it.resultStars > 0 || it.failed } == true && !campaignResultShown -> {
                    campaignResultShown = true
                    autoPlay.stopAutoPlay()
                    Concurrency.runOnGLThread {
                        try {
                            game.files.autosaves.autoSaveJob?.join()
                            com.unciv.logic.campaign.CampaignStore(game.files).save(gameInfo)
                            com.unciv.ui.screens.campaign.CampaignTasksPopup(this@WorldScreen)
                        } catch (ex: Exception) {
                            com.unciv.utils.Log.error("Campaign result save failed", ex)
                            com.unciv.ui.popups.ToastPopup("战役成绩尚未保存，请通过任务面板重试保存。", this@WorldScreen)
                        }
                    }
                }
                gameInfo.campaign?.let { !it.briefingSeen && it.resultStars == 0 && !it.failed } == true -> {
                    gameInfo.campaign!!.briefingSeen = true
                    com.unciv.ui.screens.campaign.CampaignTasksPopup(this)
                }
                viewingCiv.shouldShowDiplomaticVotingResults() ->
                    UncivGame.Current.pushScreen{ DiplomaticVoteResultScreen(gameInfo.diplomaticVictoryVotesCast, viewingCiv) }
                gameInfo.campaign == null && gameInfo.endless == null && !gameInfo.oneMoreTurnMode && (viewingCiv.isDefeated() || gameInfo.checkForVictory()) ->
                    game.pushScreen{ VictoryScreen(this) }
                hasPendingFreeGreatPerson() && !deferFreeGreatPersonPicker ->
                    openGreatPersonPicker()
                !com.unciv.logic.endless.EndlessRules.blocked(gameInfo) && gameInfo.campaign?.let { it.resultStars > 0 || it.failed } != true && viewingCiv.popupAlerts.any() -> AlertPopup(this, viewingCiv.popupAlerts.first())
                viewingCiv.tradeRequests.isNotEmpty() -> {
                    // In the meantime this became invalid, perhaps because we accepted previous trades
                    for (tradeRequest in viewingCiv.tradeRequests.toList())
                        if (!TradeEvaluation().isTradeValid(tradeRequest.trade, viewingCiv,
                                gameInfo.getCivilization(tradeRequest.requestingCiv)))
                            viewingCiv.tradeRequests.remove(tradeRequest)

                    if (viewingCiv.tradeRequests.isNotEmpty()) // if a valid one still exists
                        TradePopup(this).open()
                }
            }
        }

        updateGameplayButtons()

        if (endlessButton.isVisible) {
            val targetWidth = (stage.width * .5f).coerceAtMost(560f)
            val gap = statusButtons.x - techPolicyAndDiplomacy.x - techPolicyAndDiplomacy.width - 20f
            val stacked = gap < targetWidth
            endlessButton.width = if (stacked) stage.width - 20f else targetWidth
            if (stacked) {
                val state = gameInfo.endless!!
                endlessButton.setText("R ${state.round} · C ${state.completedRounds}\n" +
                    com.unciv.ui.screens.endless.endlessStatus(state).tr())
            }
            endlessButton.validate()
            endlessButton.height = maxOf(42f, endlessButton.prefHeight)
            val top = if (stacked) minOf(techPolicyAndDiplomacy.y, statusButtons.y) else topBar.y
            endlessButton.setPosition((stage.width - endlessButton.width) / 2, top - endlessButton.height - 6f)
        }

        // Late: the task card is fitted into the room left by the widgets around it, so they must have their final size
        if (uiEnabled && gameInfo.campaign == null && gameInfo.endless == null) tutorialTaskTable.update()
        else tutorialTaskTable.isVisible = false

        if (battlefieldHud != null && uiEnabled) {
            battlefieldHud!!.layout()
            return
        }
        val coveredNotificationsTop = stage.height - statusButtons.y
        val coveredNotificationsBottom = (bottomTileInfoTable.height + bottomTileInfoTable.y)
//                (if (game.settings.showMinimap) minimapWrapper.height else 0f)
        notificationsScroll.update(viewingCiv.notifications, coveredNotificationsTop, coveredNotificationsBottom)

        val posZoomFromRight = if (game.settings.showMinimap) minimapWrapper.width
        else bottomTileInfoTable.width
        zoomController.setPosition(
            stage.width - posZoomFromRight - 10f,
            10f,
            Align.bottomRight
        )
    }

    @Readonly
    internal fun hasPendingFreeGreatPerson() = viewingCiv.greatPeople.freeGreatPeople > 0

    internal fun openGreatPersonPicker() {
        deferFreeGreatPersonPicker = false
        game.pushScreen { GreatPersonPickerScreen(this, viewingCiv) }
    }

    private fun displayTutorialsOnUpdate() {

        displayTutorial(TutorialTrigger.Introduction)

        displayTutorial(TutorialTrigger.EnemyCityNeedsConqueringWithMeleeUnit) {
            viewingCiv.diplomacy.values.asSequence()
                    .filter { it.diplomaticStatus == DiplomaticStatus.War }
                    .map { it.otherCiv } // we're now lazily enumerating over CivilizationInfo's we're at war with
                    .flatMap { it.cities.asSequence() } // ... all *their* cities
                    .filter { it.health == 1 } // ... those ripe for conquering
                    .flatMap { it.getCenterTile().getTilesInDistance(2) }
                    // ... all tiles around those in range of an average melee unit
                    // -> and now we look for a unit that could do the conquering because it's ours
                    //    no matter whether civilian, air or ranged, tell user he needs melee
                    .any { it.getUnits().any { unit -> unit.civ == viewingCiv } }
        }
        displayTutorial(TutorialTrigger.AfterConquering) { viewingCiv.cities.any { it.hasJustBeenConquered } }

        displayTutorial(TutorialTrigger.InjuredUnits) { gameInfo.getCurrentPlayerCivilization().units.getCivUnits().any { it.health < 100 } }

        displayTutorial(TutorialTrigger.Workers) {
            gameInfo.getCurrentPlayerCivilization().units.getCivUnits().any {
                it.cache.hasUniqueToBuildImprovements && it.isCivilian() && !it.isGreatPerson()
            }
        }
    }

    fun setSelectedCiv(civ: Civilization) {
        selectedCiv = civ
        selectedGameView = GameView(gameInfo, civ, viewingCiv.isSpectator())
    }

    private fun updateSelectedCiv() {
        // Only spectators/replays follow the selection's owner. For ordinary players selectedCiv must stay viewingCiv:
        // otherwise a selected unit that just changed owner (gifted to a city-state, captured) would switch the
        // whole view to its new owner, defeat UnitPresenter's "no longer ours" deselection, and leave a stale selection.
        setSelectedCiv(when {
            !viewingCiv.isSpectator() -> viewingCiv
            bottomUnitTable.selectedUnit != null -> bottomUnitTable.selectedUnit!!.civ().getCiv()
            bottomUnitTable.selectedCity != null -> bottomUnitTable.selectedCity!!.owningCiv().getCiv()
            else -> viewingCiv
        })
    }

    class RestoreState(
        mapHolder: WorldMapHolder,
        val selectedCivName: String,
        val viewingCivName: String,
        val fogOfWar: Boolean
    ) {
        val zoom = mapHolder.scaleX
        val scrollX = mapHolder.scrollX
        val scrollY = mapHolder.scrollY
    }
    
    @Readonly
    fun getRestoreState(): RestoreState {
        return RestoreState(mapHolder, selectedCiv.civID, viewingCiv.civID, fogOfWar)
    }

    private fun restore(restoreState: RestoreState) {

        // This is not the case if you have a multiplayer game where you play as 2 civs
        if (viewingCiv.civID == restoreState.viewingCivName) {
            mapHolder.zoom(restoreState.zoom)
            mapHolder.scrollX = restoreState.scrollX
            mapHolder.scrollY = restoreState.scrollY
            mapHolder.updateVisualScroll()
        }

        setSelectedCiv(gameInfo.getCivilization(restoreState.selectedCivName))
        fogOfWar = restoreState.fogOfWar
    }

    private suspend fun requestMultiplayerAuthentication(
        multiplayerServer: MultiplayerServer,
    ): Boolean {
        val authResult = CompletableDeferred<Boolean>()
        withGLContext {
            AuthPopup(this@WorldScreen, multiplayerServer, authResult::complete).open(true)
        }
        return authResult.await()
    }

    private suspend fun markTurnUploadUnconfirmed(
        message: String,
        exception: Exception? = null,
        reason: TurnUploadUnconfirmedReason = TurnUploadUnconfirmedReason.UploadFailed,
    ) {
        withGLContext {
            pendingTurnUploadState.markUnconfirmed(reason)
            failedUpload = true
            shouldUpdate = true
            Popup(this@WorldScreen).apply {
                addGoodSizedLabel(message).row()
                if (exception != null) {
                    addButton("Copy to clipboard") {
                        Gdx.app.clipboard.contents = exception.stackTraceToString()
                    }
                }
                addCloseButton()
                open()
            }
        }
    }

    /** Called on the GL thread when the app returns to the foreground. */
    internal fun resumeMultiplayerUploadState() {
        if (pendingTurnUploadState.unconfirmedReason == null) return
        failedUpload = true
        isPlayersTurn = false
        shouldUpdate = true
    }

    private suspend fun uploadPendingTurn(gameInfo: GameInfo, previewOnly: Boolean = false): Boolean {
        if (!isActiveWorldScreen()) {
            pendingTurnUploadState.ensureUnconfirmed(TurnUploadUnconfirmedReason.UploadFailed)
            return false
        }
        val multiplayerServer = game.onlineMultiplayer
            .serverFor(gameInfo.gameParameters.multiplayerServerUrl)
        while (true) {
            if (!isActiveWorldScreen()) {
                pendingTurnUploadState.ensureUnconfirmed(TurnUploadUnconfirmedReason.UploadFailed)
                return false
            }
            var backgroundTaskExpired = false
            val needsAuthentication = try {
                val backgroundTask = game.beginMultiplayerUploadBackgroundTask {
                    pendingTurnUploadState.markBackgroundTaskExpired()
                }
                try {
                    if (backgroundTask?.isExpired == true) {
                        backgroundTaskExpired = true
                        false
                    } else try {
                        game.onlineMultiplayer.withTurnUploadContinuation {
                            if (previewOnly) game.onlineMultiplayer.updateGamePreview(gameInfo)
                            else game.onlineMultiplayer.updateGame(gameInfo)
                        }
                        false
                    } catch (_: MultiplayerAuthException) {
                        true
                    }
                } finally {
                    backgroundTaskExpired = backgroundTask?.isExpired == true
                    backgroundTask?.finish()
                }
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Exception) {
                val message = if (backgroundTaskExpired) {
                    "Turn upload is unconfirmed because its background time expired. Retry after returning to the game."
                } else if (ex is FileStorageRateLimitReached) {
                    "Server limit reached! Please wait for [${ex.limitRemainingSeconds}] seconds before retrying."
                } else {
                    "Turn upload is unconfirmed. Reason: [${multiplayerUploadFailureReason(ex)}]"
                }
                markTurnUploadUnconfirmed(
                    message,
                    ex,
                    if (backgroundTaskExpired) TurnUploadUnconfirmedReason.BackgroundTaskExpired
                    else TurnUploadUnconfirmedReason.UploadFailed,
                )
                return false
            }

            if (backgroundTaskExpired) {
                markTurnUploadUnconfirmed(
                    "Turn upload is unconfirmed because its background time expired. Retry after returning to the game.",
                    reason = TurnUploadUnconfirmedReason.BackgroundTaskExpired,
                )
                return false
            }
            if (!needsAuthentication) return true

            val authenticationSucceeded = try {
                requestMultiplayerAuthentication(multiplayerServer)
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Exception) {
                markTurnUploadUnconfirmed("Could not open authentication. The turn upload is unconfirmed.", ex)
                return false
            }
            if (!authenticationSucceeded) {
                markTurnUploadUnconfirmed(
                    "Authentication was cancelled. The turn upload is unconfirmed.",
                    reason = TurnUploadUnconfirmedReason.AuthenticationCancelled,
                )
                return false
            }
        }
    }

    private suspend fun resolvePendingTurnBeforeRetry(): PendingTurnUploadResolution? {
        val pendingTurn = pendingTurnUploadState.get() ?: return null
        val multiplayerServer = game.onlineMultiplayer
            .serverFor(pendingTurn.pendingGame.gameParameters.multiplayerServerUrl)
        while (true) {
            try {
                val serverGame = multiplayerServer
                    .tryDownloadVerifiedGame(pendingTurn.pendingGame.gameId)
                val serverPreview = multiplayerServer
                    .let { server ->
                        try {
                            server.tryDownloadGamePreview(pendingTurn.pendingGame.gameId)
                        } catch (_: MultiplayerFileNotFoundException) {
                            null
                        } catch (ex: MultiplayerAuthException) {
                            throw ex
                        } catch (ex: CancellationException) {
                            throw ex
                        } catch (_: Exception) {
                            // The strictly verified full game is authoritative. A missing, malformed,
                            // or unreadable preview can be repaired idempotently from that full game.
                            null
                        }
                    }
                return pendingTurn.resolve(
                    serverGame,
                    serverPreview,
                    checksumVerifiedBeforeSourceStamping = true,
                )
            } catch (_: MultiplayerAuthException) {
                val authenticationSucceeded = try {
                    requestMultiplayerAuthentication(multiplayerServer)
                } catch (ex: CancellationException) {
                    throw ex
                } catch (ex: Exception) {
                    markTurnUploadUnconfirmed("Could not open authentication. The turn upload is unconfirmed.", ex)
                    return null
                }
                if (!authenticationSucceeded) {
                    markTurnUploadUnconfirmed(
                        "Authentication was cancelled. The turn upload is unconfirmed.",
                        reason = TurnUploadUnconfirmedReason.AuthenticationCancelled,
                    )
                    return null
                }
            } catch (_: MultiplayerFileNotFoundException) {
                if (pendingTurn.isCreationIntent) return PendingTurnUploadResolution.RetryUpload
                markTurnUploadUnconfirmed(
                    "The server no longer has the game. The pending turn was kept and was not uploaded."
                )
                return null
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Exception) {
                markTurnUploadUnconfirmed(
                    "Could not confirm the previous turn upload. Reason: [${multiplayerUploadFailureReason(ex)}]",
                    ex,
                )
                return null
            }
        }
    }

    fun nextTurn(fromAutoPlay: Boolean = false) {
        if (fromAutoPlay && !gameInfo.allowsPlayerAutoPlay()) return
        if (com.unciv.logic.endless.EndlessRules.blocked(gameInfo)) {
            com.unciv.ui.screens.endless.EndlessPopup(this)
            return
        }
        if (gameInfo.campaign?.let { it.resultStars > 0 || it.failed } == true) {
            com.unciv.ui.screens.campaign.CampaignTasksPopup(this)
            return
        }
        if (!isActiveWorldScreen()) return
        val autoPlayAuthorized = fromAutoPlay && autoPlay.consumeTurnEndAuthorization()
        if (!isPlayersTurn && !failedUpload && !autoPlayAuthorized) return
        if (!pendingTurnUploadState.tryStartTurnProcessing()) return
        isPlayersTurn = false
        shouldUpdate = true
        val progressBar = try {
            NextTurnProgress(nextTurnButton).also { it.start(this) }
        } catch (ex: Throwable) {
            pendingTurnUploadState.finishTurnProcessing()
            throw ex
        }

        // on a separate thread so the user can explore their world while we're passing the turn
        val updateJob = try {
            Concurrency.runOnNonDaemonThreadPool("NextTurn") {
                debug("Next turn starting")
                val startTime = System.currentTimeMillis()
                val originalGameInfo = gameInfo
                observeMultiplayerTurnSubmission(originalGameInfo.gameParameters.isOnlineMultiplayer) {
                    val existingPendingTurn = pendingTurnUploadState.get()

                    progressBar.increment()

                    if (!isActiveWorldScreen()) return@observeMultiplayerTurnSubmission null

                    if (pendingTurnUploadState.hasRecoveryFailure()) {
                        loadLatestMultiplayerState(replacePendingRecovery = true)
                        return@observeMultiplayerTurnSubmission false
                    }

                    val pendingTurn = try {
                        pendingTurnUploadState.getOrCreate(originalGameInfo) {
                            originalGameInfo.clone().apply {
                                com.unciv.logic.PlayerDecisionControl.followNextTurn(originalGameInfo, this)
                                setTransients()  // this can get expensive on large games, not the clone itself
                                nextTurn(progressBar, true)
                            }
                        }
                    } catch (ex: PendingTurnPersistenceException) {
                        markTurnUploadUnconfirmed(ex.message, ex)
                        return@observeMultiplayerTurnSubmission false
                    }
                    if (!isActiveWorldScreen()) {
                        pendingTurnUploadState.ensureUnconfirmed(TurnUploadUnconfirmedReason.UploadFailed)
                        return@observeMultiplayerTurnSubmission null
                    }
                    val gameInfoClone = pendingTurn.pendingGame

                    if (originalGameInfo.gameParameters.isOnlineMultiplayer) {
                        if (existingPendingTurn != null) {
                            when (resolvePendingTurnBeforeRetry() ?: return@observeMultiplayerTurnSubmission false) {
                                PendingTurnUploadResolution.Confirmed -> Unit
                                PendingTurnUploadResolution.RetryUpload ->
                                    if (!uploadPendingTurn(gameInfoClone)) return@observeMultiplayerTurnSubmission if (isActiveWorldScreen()) false else null
                                PendingTurnUploadResolution.RepairPreview ->
                                    if (!uploadPendingTurn(gameInfoClone, previewOnly = true)) return@observeMultiplayerTurnSubmission if (isActiveWorldScreen()) false else null
                                PendingTurnUploadResolution.ServerChanged -> {
                                    loadLatestMultiplayerState(replacePendingRecovery = true)
                                    return@observeMultiplayerTurnSubmission false
                                }
                                PendingTurnUploadResolution.InvalidServerGame -> {
                                    markTurnUploadUnconfirmed(
                                        "The server's full game could not be verified. " +
                                            "The pending turn was kept and was not uploaded."
                                    )
                                    return@observeMultiplayerTurnSubmission false
                                }
                            }
                        } else if (!uploadPendingTurn(gameInfoClone)) {
                            return@observeMultiplayerTurnSubmission if (isActiveWorldScreen()) false else null
                        }
                    }

                    if (game.gameInfo != originalGameInfo) // while this was turning we loaded another game
                        return@observeMultiplayerTurnSubmission null

                    if (gameInfo.gameParameters.isOnlineMultiplayer) {
                        try {
                            game.onlineMultiplayer.persistGamePreviewLocally(gameInfoClone)
                            if (!game.files.autosaves.autoSave(gameInfoClone)) {
                                throw UncivShowableException("Could not persist the confirmed turn locally.")
                            }
                        } catch (ex: Exception) {
                            markTurnUploadUnconfirmed(
                                "The server confirmed the turn, but it could not be saved locally. Retry to recover safely.",
                                ex,
                            )
                            return@observeMultiplayerTurnSubmission false
                        }
                    }

                    pendingTurnUploadState.clear()
                    failedUpload = false
                    debug("Next turn took %sms", System.currentTimeMillis() - startTime)

                    // Special case: when you are the only alive human player, the game will always be up to date
                    if (gameInfo.gameParameters.isOnlineMultiplayer
                            && gameInfoClone.civilizations.count { it.isAlive() && it.playerType == PlayerType.Human } == 1) {
                        gameInfoClone.isUpToDate = true
                    }

                    progressBar.increment()

                    if (gameInfoClone.endless != null) {
                        val saveStarted = System.currentTimeMillis()
                        try { com.unciv.logic.endless.EndlessStore(game.files).save(gameInfoClone) }
                        catch (ex: Exception) { com.unciv.utils.Log.error("Endless boundary save failed", ex) }
                        debug("Endless boundary R=%s saved in %sms", gameInfoClone.endless!!.round, System.currentTimeMillis() - saveStarted)
                    }
                    startNewScreenJob(gameInfoClone, autoPlay)
                    true
                }
            }
        } catch (ex: Throwable) {
            pendingTurnUploadState.finishTurnProcessing()
            throw ex
        }
        nextTurnUpdateJob = updateJob
        updateJob.invokeOnCompletion {
            // Crash-handling launchers may consume CancellationException and report normal
            // completion. A remaining pending turn is therefore itself the failure signal.
            pendingTurnUploadState.ensureUnconfirmed(TurnUploadUnconfirmedReason.UploadFailed)
            Concurrency.runOnGLThread("NextTurnFinished") {
                pendingTurnUploadState.finishTurnProcessing()
                // Expiration can race with resume(), and a failure repaint can race with this
                // completion. Re-read the durable state only after releasing the single-flight gate.
                val visibleWorldScreen = game.worldScreen ?: this@WorldScreen
                visibleWorldScreen.resumeMultiplayerUploadState()
                visibleWorldScreen.shouldUpdate = true
            }
        }
    }

    fun switchToNextUnit(resetDue: Boolean = true) {
        // Try to select something new if we already have the next pending unit selected.
        if (bottomUnitTable.selectedUnit != null && resetDue)
            bottomUnitTable.selectedUnit!!.getUnit().due = false
        val nextDueUnit = viewingCiv.units.cycleThroughDueUnits(bottomUnitTable.selectedUnit?.getUnit())
        if (nextDueUnit != null) {
            mapHolder.setCenterPosition(
                nextDueUnit.currentTile.position,
                immediately = false,
                selectUnit = false
            )
            bottomUnitTable.selectUnit(selectedGameView.getForeignMapUnitView(nextDueUnit).tryGetMapUnitView()!!)
        } else {
            mapHolder.removeAction(mapHolder.blinkAction)
            mapHolder.selectedTile = null
            bottomUnitTable.selectUnit()
        }
        shouldUpdate = true
    }
    
    @Readonly
    fun isNextTurnUpdateRunning(): Boolean {
        return pendingTurnUploadState.isTurnProcessing()
    }

    @Readonly
    private fun isActiveWorldScreen() = game.gameInfo === gameInfo && game.worldScreen === this

    private fun updateGameplayButtons() {
        nextTurnButton.update()

        updateAutoPlayStatusButton()
        updateMultiplayerStatusButton()

        statusButtons.update(false)
        val safeArea = safeAreaBoundsInWorld()
        val maxWidth = stage.width - safeArea.x - techPolicyAndDiplomacy.width - 25f
        if(statusButtons.width > maxWidth) {
            statusButtons.update(true)
        }
        statusButtons.setPosition(stage.width - statusButtons.width - 10f,
            topBar.y - statusButtons.height - 10f)

        // Update chat button position to always be below techPolicyAndDiplomacy
        chatButton.updatePosition()
    }

    private fun updateAutoPlayStatusButton() {
        val showAutoPlay = gameInfo.allowsPlayerAutoPlay() && game.settings.autoPlay.showAutoPlayButton
        if (statusButtons.autoPlayStatusButton == null) {
            if (showAutoPlay)
                statusButtons.autoPlayStatusButton = AutoPlayStatusButton(this, nextTurnButton)
        } else {
            if (!showAutoPlay) {
                statusButtons.autoPlayStatusButton = null
                autoPlay.stopAutoPlay()
            }
        }
    }

    private fun updateMultiplayerStatusButton() {
        val multiplayer = game.onlineMultiplayerOrNull
        if (multiplayer == null) {
            statusButtons.multiplayerStatusButton = null
            return
        }

        if (gameInfo.gameParameters.isOnlineMultiplayer || game.settings.multiplayer.statusButtonInSinglePlayer) {
            if (statusButtons.multiplayerStatusButton != null) return
            statusButtons.multiplayerStatusButton = MultiplayerStatusButton(this,
                multiplayer.multiplayerFiles.getGameByGameId(
                    gameInfo.gameId,
                    gameInfo.gameParameters.multiplayerServerUrl,
                ))
        } else {
            if (statusButtons.multiplayerStatusButton == null) return
            statusButtons.multiplayerStatusButton = null
        }
    }


    private var resizeDeferTimer: Timer? = null

    override fun resize(width: Int, height: Int) {
        if (Gdx.app.type == com.badlogic.gdx.Application.ApplicationType.iOS) {
            if (!hasSafeAreaChanged(width, height)) return
            super.resize(width, height)
            mapHolder.resizeViewport((stage.viewport as com.unciv.ui.screens.basescreen.SafeAreaViewport).drawingBounds)
            mapHolder.reloadMaxZoom()
            mapHolder.zoom(mapHolder.scaleX)
            notificationsScroll.width = stage.width / 2
            minimapWrapper.x = stage.width - minimapWrapper.width
            battleTable.width = stage.width / 3
            battleTable.x = stage.width / 3
            bottomUnitTable.shouldUpdate = true
            shouldUpdate = true
            return
        }
        resizeDeferTimer?.cancel()
        if (resizeDeferTimer == null && !hasSafeAreaChanged(width, height)) return
        resizeDeferTimer = timer("Resize", daemon = true, 500L, Long.MAX_VALUE) {
            resizeDeferTimer?.cancel()
            resizeDeferTimer = null
            startNewScreenJob(gameInfo, autoPlay, true) // start over
        }
    }

    override fun render(delta: Float) {
        //  This is so that updates happen in the MAIN THREAD, where there is a GL Context,
        //    otherwise images will not load properly!
        if (shouldUpdate && resizeDeferTimer == null) {
            shouldUpdate = false

            // Since updating the worldscreen can take a long time, *especially* the first time, we disable input processing to avoid ANRs
            Gdx.input.inputProcessor = null
            update()
            showTutorialsOnNextTurn()
            if (Gdx.input.inputProcessor == null) // Update may have replaced the worldscreen with a GreatPersonPickerScreen etc, so the input would already be set
                Gdx.input.inputProcessor = stage
        }

        super.render(delta)
    }


    private fun showTutorialsOnNextTurn() {
        if (!game.settings.showTutorials || autoPlay.isAutoPlaying()) return
        displayTutorial(TutorialTrigger.SlowStart)
        displayTutorial(TutorialTrigger.CityExpansion) { viewingCiv.cities.any { it.expansion.tilesClaimed() > 0 } }
        displayTutorial(TutorialTrigger.BarbarianEncountered) { viewingCiv.viewableTiles.any { it.getUnits().any { unit -> unit.civ.isBarbarian } } }
        displayTutorial(TutorialTrigger.RoadsAndRailroads) { viewingCiv.cities.size > 2 }
        displayTutorial(TutorialTrigger.Happiness) { viewingCiv.getHappiness() < 5 }
        displayTutorial(TutorialTrigger.Unhappiness) { viewingCiv.getHappiness() < 0 }
        displayTutorial(TutorialTrigger.GoldenAge) { viewingCiv.goldenAges.isGoldenAge() }
        displayTutorial(TutorialTrigger.IdleUnits) { gameInfo.turns >= 50 && game.settings.checkForDueUnits }
        displayTutorial(TutorialTrigger.ContactMe) { gameInfo.turns >= 100 }
        val resources = viewingCiv.detailedCivResources.asSequence().filter { it.origin == "All" }  // Avoid full list copy
        displayTutorial(TutorialTrigger.LuxuryResource) { resources.any { it.resource.resourceType == ResourceType.Luxury } }
        displayTutorial(TutorialTrigger.StrategicResource) { resources.any { it.resource.resourceType == ResourceType.Strategic } }
        displayTutorial(TutorialTrigger.EnemyCity) {
            viewingCiv.getKnownCivs().filter { viewingCiv.isAtWarWith(it) }
                    .flatMap { it.cities.asSequence() }.any { viewingCiv.hasExplored(it.getCenterTile()) }
        }
        displayTutorial(TutorialTrigger.Embarking) { viewingCiv.hasUnique(UniqueType.LandUnitEmbarkation) }
        displayTutorial(TutorialTrigger.NaturalWonders) { viewingCiv.naturalWonders.size > 0 }
        displayTutorial(TutorialTrigger.WeLoveTheKingDay) { viewingCiv.cities.any { it.demandedResource != "" } }
    }

    private fun backButtonAndESCHandler() {

        // Deselect Unit
        if (bottomUnitTable.selectedUnit != null) {
            bottomUnitTable.selectUnit()
            shouldUpdate = true
            return
        }

        // Deselect city
        if (bottomUnitTable.selectedCity != null) {
            bottomUnitTable.selectUnit()
            shouldUpdate = true
            return
        }

        if (bottomUnitTable.selectedSpy != null) {
            bottomUnitTable.selectSpy(null)
            shouldUpdate = true
            return
        }

        game.popScreen()
    }

    fun autoSave() {
        waitingForAutosave = true
        shouldUpdate = true
        UncivGame.Current.files.autosaves.requestAutoSave(gameInfo, true).invokeOnCompletion {
            // only enable the user to next turn once we've saved the current one
            waitingForAutosave = false
            shouldUpdate = true
        }
    }
}

/** This exists so that no reference to the current world screen remains, so the old world screen can get garbage collected during [UncivGame.loadGame]. */
private fun startNewScreenJob(gameInfo: GameInfo, autoPlay: AutoPlay, autosaveDisabled: Boolean = false) {
    Concurrency.run {
        val newWorldScreen = try {
            UncivGame.Current.loadGame(gameInfo, autoPlay)
        } catch (notAPlayer: UncivShowableException) {
            val (message) = LoadGameScreen.getLoadExceptionMessage(notAPlayer)
            withGLContext {
                UncivGame.Current.goToMainMenu { mainMenu -> ToastPopup(message, mainMenu) }
            }
            return@run
        } catch (_: OutOfMemoryError) {
            withGLContext {
                UncivGame.Current.goToMainMenu { mainMenu -> ToastPopup("Not enough memory on phone to load game!", mainMenu) }
            }
            return@run
        }

        val shouldAutoSave = gameInfo.endless == null && !autosaveDisabled
                && gameInfo.turns % UncivGame.Current.settings.turnsBetweenAutosaves == 0
        if (shouldAutoSave) {
            newWorldScreen.autoSave()
        }
    }
}
