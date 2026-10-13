package com.unciv.ui.screens.newgamescreen

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.HorizontalGroup
import com.unciv.models.skins.ExpeditionBlueTheme
import com.badlogic.gdx.scenes.scene2d.ui.TextButton.TextButtonStyle
import com.unciv.Constants
import com.unciv.UncivGame
import com.unciv.logic.GameInfo
import com.unciv.logic.GameStarter
import com.unciv.logic.IdChecker
import com.unciv.logic.UncivShowableException
import com.unciv.logic.civilization.AlertType
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.PlayerType
import com.unciv.logic.files.MapSaver
import com.unciv.logic.map.MapGeneratedMainType
import com.unciv.logic.multiplayer.Multiplayer
import com.unciv.logic.multiplayer.rethrowCancellationAfterCleanup
import com.unciv.logic.multiplayer.storage.FileStorageRateLimitReached
import com.unciv.logic.multiplayer.storage.MultiplayerGameCreationPartialException
import com.unciv.logic.multiplayer.storage.MultiplayerGameCreationCancelledException
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.metadata.GameSetupInfo
import com.unciv.models.metadata.GameSetupPreset
import com.unciv.models.metadata.Player
import com.unciv.models.ruleset.Ruleset
import com.unciv.models.ruleset.RulesetCache
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.addSeparator
import com.unciv.ui.components.extensions.addSeparatorVertical
import com.unciv.ui.components.extensions.disable
import com.unciv.ui.components.extensions.enable
import com.unciv.ui.components.extensions.isEnabled
import com.unciv.ui.components.extensions.pad
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.input.KeyCharAndCode
import com.unciv.ui.components.input.keyShortcuts
import com.unciv.ui.components.input.onActivation
import com.unciv.ui.components.input.onClick
import com.unciv.ui.components.widgets.ExpanderTab
import com.unciv.ui.components.widgets.UiNavigation
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.popups.ConfirmPopup
import com.unciv.ui.popups.Popup
import com.unciv.ui.popups.ToastPopup
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.basescreen.RecreateOnResize
import com.unciv.ui.screens.pickerscreens.PickerScreen
import com.unciv.utils.Concurrency
import com.unciv.utils.Log
import com.unciv.utils.isUUID
import com.unciv.utils.launchOnGLThread
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlin.math.floor
import com.unciv.ui.components.widgets.AutoScrollPane as ScrollPane

class NewGameScreen(
    defaultGameSetupInfo: GameSetupInfo? = null,
    private var setupSection: GameSetupSection = GameSetupSection.Overview
): IPreviousScreen, PickerScreen(), RecreateOnResize {

    override val gameSetupInfo = com.unciv.models.metadata.NewGameMode.SinglePlayer.prepare(
        defaultGameSetupInfo ?: GameSetupInfo.fromSettings()
    )
    override val ruleset = Ruleset()  // updateRuleset will clear and add
    private val newGameOptionsTable: GameOptionsTable
    internal val playerPickerTable: PlayerPickerTable
    private val mapOptionsTable: MapOptionsTable
    private var mapOptionsTableInitialized = false
    private var overviewReady = false
    private var setupStatus = ""

    val useInviteRoom = false
    private val stackedLayout = isNarrowerThan4to3() ||
        (game.platformCapabilities.multiplayerInviteLinks && gameSetupInfo.gameParameters.isOnlineMultiplayer && stage.width < 1000f)

    init {
        val isPortrait = stackedLayout

        // The mods loaded here may come from the last-started game (see GameSetupInfo.fromSettings) -
        // if that combination is now broken (e.g. a mod was updated/removed), silently fall back to
        // defaults instead of opening straight into an unusable, error-flagged mod selection.
        if (defaultGameSetupInfo == null) resetIfInitialModsAreBroken()

        tryUpdateRuleset(updateUI = false)  // must come before playerPickerTable so mod nations from fromSettings

        // remove the victory types which are not in the rule set (e.g. were in the recently disabled mod)
        gameSetupInfo.gameParameters.victoryTypes.removeAll { it !in ruleset.victories.keys }

        if (gameSetupInfo.gameParameters.victoryTypes.isEmpty())
            gameSetupInfo.gameParameters.victoryTypes.addAll(ruleset.selectableVictories().map { it.name })

        rightSideButton.enable()  // now because PlayerPickerTable init might disable it again
        playerPickerTable = PlayerPickerTable(
            this, gameSetupInfo.gameParameters,
            if (UiNavigation.isIos) getColumnWidth() else if (isPortrait) stage.width - 20f else 0f
        )
        newGameOptionsTable = GameOptionsTable(
            this, UiNavigation.isIos || isPortrait,
            updatePlayerPickerTable = { desiredCiv -> playerPickerTable.update(desiredCiv) },
            updatePlayerPickerRandomLabel = { playerPickerTable.updateRandomNumberLabel() },
            allowOnlineToggle = false
        )
        mapOptionsTable = MapOptionsTable(this)
        mapOptionsTableInitialized = true
        closeButton.onActivation {
            mapOptionsTable.cancelBackgroundJobs()
            game.popScreen()
        }
        closeButton.keyShortcuts.add(KeyCharAndCode.BACK)

        val heading = "Singleplayer".toLabel(fontSize = 28)
        val roomHint = "Choose the rules, map and civilizations for your game.".toLabel(Color.LIGHT_GRAY,18).apply { setWrap(true) }
        val banner = Table().apply {
            if (!UiNavigation.isIos) {
                background = skinStrings.getUiBackground("NewGameScreen/RoomHeader",skinStrings.roundedEdgeRectangleSmallShape,Color.valueOf("0d2140"))
                pad(14f)
            }
            add(heading).left().growX().row()
            add(roomHint).left().growX().padTop(6f)
        }
        if (UiNavigation.isIos)
            pickerPane.setNavigationContent(banner)
        else if (game.platformCapabilities.multiplayerInviteLinks)
            topTable.add(banner).colspan(if (isPortrait) 1 else 5).width(stage.width-20f).padBottom(12f).row()
        if (UiNavigation.isIos) {
            overviewReady = true
            showSetupSection(setupSection)
        } else if (isPortrait) initPortrait()
        else initLandscape()
        bottomTable.background = skinStrings.getUiBackground("NewGameScreen/BottomTable", tintColor = skinStrings.skinConfig.clearColor)
        topTable.background = skinStrings.getUiBackground("NewGameScreen/TopTable", tintColor = skinStrings.skinConfig.clearColor)

        val horizontalGroup = HorizontalGroup().padBottom(5f).space(10f)
        rightSideGroup.addActorAt(0, horizontalGroup)

        if (UncivGame.Current.settings.lastGameSetup != null) {
            val resetToDefaultsButton = "Reset to defaults".toTextButton()
            resetToDefaultsButton.onClick {
                ConfirmPopup(
                    this,
                    "Are you sure you want to reset all game options to defaults?",
                    "Reset to defaults",
                ) {
                    val gameSetupInfo = GameSetupInfo().apply {
                        gameParameters.espionageEnabled = true
                    }
                    game.replaceCurrentScreen{ NewGameScreen(gameSetupInfo) }
                }.open(true)
            }
            horizontalGroup.addActor(resetToDefaultsButton)
        }

        val startGameButton = (if (UiNavigation.isIos) rightSideButton else "Start game!".toTextButton()).apply {
            setText("Start game!".tr())
            if (ExpeditionBlueTheme.enabled) style = skin.get("positive", TextButtonStyle::class.java)
            if (!ExpeditionBlueTheme.enabled) color = Color.GREEN
            name = "setup-start"
        }
        startGameButton.onClick(this::startGameAvoidANRs)
        startGameButton.keyShortcuts.add(KeyCharAndCode.RETURN)
        if (!UiNavigation.isIos) {
            horizontalGroup.addActor(startGameButton)
            pickerPane.rightSideButton.remove()
        }
    }

    private fun showSetupSection(section: GameSetupSection) {
        setupSection = section
        topTable.clear()
        scrollPane.setScrollingDisabled(true, true)
        val width = stage.width - 24f
        val contentWidth = GameSetupOverview.contentWidth(width, stackedLayout)
        val page = Table().apply { top() }
        val editor = when (section) {
            GameSetupSection.Overview -> createOverview(contentWidth)
            GameSetupSection.World -> mapOptionsTable
            GameSetupSection.Civilizations -> playerPickerTable
            GameSetupSection.Rules -> Table().apply {
                top()
                add(newGameOptionsTable).row()
                add(newGameOptionsTable.modCheckboxes).growX().padTop(12f)
            }
        }
        if (section != GameSetupSection.Overview) {
            page.add(GameSetupOverview.heading(section.caption, when (section) {
                GameSetupSection.World -> "Choose the map and terrain for your game."
                GameSetupSection.Civilizations -> "Choose your civilization and opponents."
                else -> "Set the pace and victory conditions."
            })).growX().padBottom(16f).row()
        }
        if (section == GameSetupSection.Civilizations) page.add(editor).grow()
        else page.add(ScrollPane(editor).apply {
            setScrollingDisabled(true, false)
            setOverscroll(false, false)
        }).grow()
        topTable.add(GameSetupOverview.layout(width, stackedLayout, section, page, select = ::showSetupSection))
            .width(width).growY().pad(0f, 12f, 0f, 12f)
        topTable.invalidateHierarchy()
    }

    private fun createOverview(width: Float) = Table().apply {
        top()
        add(GameSetupOverview.heading("A new civilization begins with you.", "Choose a setup, then shape your world."))
            .width(width).growX().left().padBottom(18f).row()
        val presets = Table()
        for (preset in GameSetupPreset.entries) {
            val option = GameSetupOverview.card().apply {
                add(preset.caption.toLabel(fontSize = 20).apply { setWrap(true) }).minWidth(0f).growX().left().row()
                add("${preset.size.tr()} · ${preset.speed.tr()}".toLabel(Color.LIGHT_GRAY, 16).apply { setWrap(true) })
                    .minWidth(0f).growX().left().padTop(5f)
                onClick {
                    if (!preset.isAvailable(ruleset)) return@onClick
                    ConfirmPopup(this@NewGameScreen,
                        "This changes the map, pace and opponents. Other rules are kept.", preset.caption) {
                        mapOptionsTable.cancelBackgroundJobs()
                        game.replaceCurrentScreen { NewGameScreen(preset.prepare(gameSetupInfo, ruleset)) }
                    }.open()
                }
                if (!preset.isAvailable(ruleset)) color.a = 0.45f
            }
            presets.add(option).width((width - 24f) / 3f).growY().padRight(12f)
        }
        presets.add(GameSetupOverview.card().apply {
            add("Custom setup".toLabel(fontSize = 20).apply { setWrap(true) }).minWidth(0f).growX().left().row()
            add("Advanced Settings".toLabel(Color.LIGHT_GRAY, 16).apply { setWrap(true) }).minWidth(0f).growX().left().padTop(5f)
            onClick { showSetupSection(GameSetupSection.Rules) }
        }).width((width - 24f) / 3f).growY()
        add(presets).growX().padBottom(18f).row()

        val narrow = stackedLayout
        val mapWidth = if (narrow) width else (width - 20f) * 0.48f
        val summaryWidth = if (narrow) width else width - mapWidth - 20f
        val map = GameSetupOverview.card().apply {
            mapOptionsTable.createOverviewPreview(mapWidth - 36f, if (narrow) 180f else 210f)?.let {
                add(it).width(mapWidth - 36f).padBottom(10f).row()
            }
            add(GameSetupOverview.summaryRow("Map Options", GameSetupOverview.mapDescription(gameSetupInfo)) {
                showSetupSection(GameSetupSection.World)
            }).width(mapWidth - 36f).growX().row()
            add("Example map".toLabel(Color.LIGHT_GRAY, 16)).left()
        }
        val summary = Table().apply {
            top()
            add(GameSetupOverview.summaryRow("Civilizations", GameSetupOverview.playerDescription(gameSetupInfo)) {
                showSetupSection(GameSetupSection.Civilizations)
            }).width(summaryWidth).growX().row()
            val parameters = gameSetupInfo.gameParameters
            add(GameSetupOverview.summaryRow("Game pace", "${parameters.difficulty.tr()} · ${parameters.speed.tr()}") {
                showSetupSection(GameSetupSection.Rules)
            }).width(summaryWidth).growX().row()
            add(GameSetupOverview.summaryRow("Victory Conditions", GameSetupOverview.victoryDescription(gameSetupInfo)) {
                showSetupSection(GameSetupSection.Rules)
            }).width(summaryWidth).growX()
        }
        add(Table().apply {
            add(map).width(mapWidth).top().padRight(if (narrow) 0f else 20f)
            if (narrow) row()
            add(summary).width(summaryWidth).top().padTop(if (narrow) 12f else 0f)
        }).growX()
    }

    override fun render(delta: Float) {
        if (overviewReady) {
            val parameters = gameSetupInfo.gameParameters
            val hasHuman = parameters.players.any { it.playerType == PlayerType.Human }
            rightSideButton.isEnabled = hasHuman && parameters.victoryTypes.isNotEmpty()
                && (parameters.randomNumberOfPlayers || parameters.players.size >= 2)
            val status = when {
                parameters.victoryTypes.isEmpty() -> "No victory conditions were selected!".tr()
                !hasHuman -> "No human players selected!".tr()
                else -> "${GameSetupOverview.mapDescription(gameSetupInfo)} · ${parameters.speed.tr()}"
            }
            if (status != setupStatus) {
                setupStatus = status
                descriptionLabel.setText(status)
            }
        }
        super.render(delta)
    }

    private fun startGameAvoidANRs(){
        // Don't allow players to click the game while we're checking if it's ok
        Gdx.input.inputProcessor = null
        mapOptionsTable.cancelBackgroundJobs()
        Concurrency.run {  // even just *checking* can take time
            try {
                val errorMessage = getErrorMessage()
                if (errorMessage != null){
                    Concurrency.runOnGLThread {
                        val errorPopup = Popup(this@NewGameScreen)
                        errorPopup.addGoodSizedLabel(errorMessage).row()
                        errorPopup.addCloseButton()
                        errorPopup.open()
                        Gdx.input.inputProcessor = stage
                    }
                    return@run
                }

                // Requires a custom popup so can't be folded into getErrorMessage
                val modCheckResult = newGameOptionsTable.modCheckboxes.savedModcheckResult
                newGameOptionsTable.modCheckboxes.savedModcheckResult = null
                if (modCheckResult != null) {
                    Concurrency.runOnGLThread {
                        AcceptModErrorsPopup(
                            this@NewGameScreen, modCheckResult,
                            action = {
                                gameSetupInfo.gameParameters.acceptedModCheckErrors = modCheckResult
                                startGameAvoidANRs()
                            }
                        )
                        Gdx.input.inputProcessor = stage
                    }
                    return@run
                }
                startGame()
            } catch (ex: Exception) {
                if (ex !is CancellationException) {
                    Concurrency.runOnGLThread {
                        Gdx.input.inputProcessor = stage
                        Popup(this@NewGameScreen).apply { addGoodSizedLabel(ex.message ?: "Could not connect to multiplayer server.").row(); addCloseButton(); open() }
                    }
                    return@run
                }
                rethrowCancellationAfterCleanup(ex) {
                    Concurrency.runOnGLThread { Gdx.input.inputProcessor = stage }
                }
            }
        }
    }
    
    // Should be run NOT on main thread because it contacts MP server and loads maps etc
    suspend fun getErrorMessage(validatePlayerIds: Boolean = true): String? {
        if (gameSetupInfo.gameParameters.isOnlineMultiplayer) {
            if (!checkConnectionToMultiplayerServer())
                return if (Multiplayer.usesCustomServer()) "Couldn't connect to Multiplayer Server!"
                    else "Couldn't connect to Dropbox!"

            if (validatePlayerIds) for (player in gameSetupInfo.gameParameters.players.filter { it.playerType == PlayerType.Human }) {
                if (!(IdChecker.checkAndReturnPlayerUuid(player.playerId)?.playerID?.isUUID() ?: false)) {
                    return "Invalid player ID!"
                }
            }

            if (validatePlayerIds && !gameSetupInfo.gameParameters.anyoneCanSpectate) {
                if (gameSetupInfo.gameParameters.players.none { it.playerId == UncivGame.Current.settings.multiplayer.getUserId() })
                    return "You are not allowed to spectate!"
            }
        }

        if (gameSetupInfo.gameParameters.players.none {
                it.playerType == PlayerType.Human &&
                        // do not allow multiplayer with only spectator(s) and AI(s) - non-MP that works
                        !(it.chosenCiv == Constants.spectator && gameSetupInfo.gameParameters.isOnlineMultiplayer)
            }) return "No human players selected!"

        if (gameSetupInfo.gameParameters.victoryTypes.isEmpty()) return "No victory conditions were selected!"
        
        if (mapOptionsTable.mapTypeSelectBox.selected.value == MapGeneratedMainType.custom) {
            val map = try {
                MapSaver.loadMap(gameSetupInfo.mapFile!!)
            } catch (ex: Throwable) {
                return "Could not load map"
            }

            val rulesetIncompatibilities = map.getRulesetIncompatibility(ruleset)
            if (rulesetIncompatibilities.isNotEmpty())
                return "Map is incompatible with the chosen ruleset!".tr() + "\n" + rulesetIncompatibilities.joinToString("\n"){it.tr()}
        } else {
            // Generated map - check for sensible dimensions and if exceeded correct them and notify user
            val mapSize = gameSetupInfo.mapParameters.mapSize
            val message = mapSize.fixUndesiredSizes(gameSetupInfo.mapParameters.worldWrap)
            if (message != null) {
                with (mapOptionsTable.generatedMapOptionsTable) {
                    customMapSizeRadius.intValue = mapSize.radius
                    customMapWidth.intValue = mapSize.width
                    customMapHeight.intValue = mapSize.height
                }
                return message
            }
        }
        return null
    }
    
    private fun startGame() {

        Concurrency.runOnGLThread {
            rightSideButton.disable()
            rightSideButton.setText(Constants.working.tr())
            setSkin()
            
            // Creating a new game can take a while and we don't want ANRs
            Concurrency.runOnNonDaemonThreadPool("NewGame") {
                startNewGame()
            }
        }
    }

    /** Subtables may need an upper limit to their width - they can ask this function. */
    // In sync with isPortrait in init, here so UI details need not know about 3-column vs 1-column layout
    internal fun getColumnWidth() = floor(
        if (UiNavigation.isIos) minOf(720f, GameSetupOverview.contentWidth(stage.width - 24f, stackedLayout) - 36f)
        else if (stackedLayout) stage.width
        else (stage.width - 2f) / 3f
    )

    internal fun refreshExampleMap() {
        if (mapOptionsTableInitialized)
            mapOptionsTable.refreshExampleMap()
    }

    private fun initLandscape() {
        scrollPane.setScrollingDisabled(true,true)
        val columnWidth = getColumnWidth()

        topTable.add("Game Options".toLabel(fontSize = Constants.headingFontSize)).pad(20f, 0f)
        topTable.addSeparatorVertical(ImageGetter.CHARCOAL, 1f)
        topTable.add("Map Options".toLabel(fontSize = Constants.headingFontSize)).pad(20f,0f)
        topTable.addSeparatorVertical(ImageGetter.CHARCOAL, 1f)
        topTable.add("Civilizations".toLabel(fontSize = Constants.headingFontSize)).pad(20f,0f)
        topTable.addSeparator(Color.CLEAR, height = 1f)

        topTable.add(ScrollPane(newGameOptionsTable)
                .apply { setOverscroll(false, false) })
                .width(columnWidth).top()
        topTable.addSeparatorVertical(Color.CLEAR, 1f)
        topTable.add(ScrollPane(mapOptionsTable)
                .apply { setOverscroll(false, false) })
                .width(columnWidth).top()
        topTable.addSeparatorVertical(Color.CLEAR, 1f)
        topTable.add(playerPickerTable)  // No ScrollPane, PlayerPickerTable has its own
                .width(columnWidth).top()
    }

    private fun initPortrait() {
        scrollPane.setScrollingDisabled(false,false)

        topTable.add(ExpanderTab("Game Options") {
            it.add(newGameOptionsTable).row()
        }).expandX().fillX().row()
        topTable.addSeparator(Color.DARK_GRAY, height = 1f)

        topTable.add(newGameOptionsTable.modCheckboxes).expandX().fillX().row()
        topTable.addSeparator(Color.DARK_GRAY, height = 1f)

        topTable.add(ExpanderTab("Map Options") {
            it.add(mapOptionsTable).row()
        }).expandX().fillX().row()
        topTable.addSeparator(Color.DARK_GRAY, height = 1f)

        (playerPickerTable.playerListTable.parent as ScrollPane).setScrollingDisabled(true,true)
        topTable.add(ExpanderTab("Civilizations") {
            it.add(playerPickerTable).row()
        }).expandX().fillX().row()
    }

    private suspend fun checkConnectionToMultiplayerServer(): Boolean {
        return try {
            game.onlineMultiplayer.multiplayerServer.checkServerStatus()
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun startNewGame() = coroutineScope {
        val popup = Popup(this@NewGameScreen)
        launchOnGLThread {
            popup.addGoodSizedLabel(Constants.working).row()
            popup.open()
            ImageGetter.setNewRuleset(ruleset) // To build the temp atlases
        }

        val newGame:GameInfo
        try {
            val selectedScenario = mapOptionsTable.getSelectedScenario()
            newGame = if (selectedScenario == null)
                GameStarter.startNewGame(gameSetupInfo)
            else {
                val gameInfo = game.files.loadGameFromFile(selectedScenario.file)
                // Remove the Spectator - it was recommended by the wiki as Scenario builder
                gameInfo.civilizations.removeAll { it.civID == Constants.spectator }
                for (civ in gameInfo.civilizations) {
                    civ.playerType = PlayerType.AI
                    civ.diplomacy.remove(Constants.spectator)
                    civ.popupAlerts.removeAll { it.type == AlertType.FirstContact && it.value == Constants.spectator }
                }
                // Ergo the Spectator can't be chosen from NewGameScreen - make sure
                gameSetupInfo.gameParameters.players.removeAll { it.chosenCiv == Constants.spectator }
                // Now assign player types to explicit player Nation choices that exist in the game,
                // remembering which are already "used".
                // (at the moment NewGameScreen forbids such choices for scenarios, but let's support it here in case someone goes and does) 
                val randomPool = gameInfo.civilizations.filter { it.isMajorCiv() }.map { it.civID }.toMutableSet()
                fun Civilization.assign(playerInfo: Player) {
                    playerType = playerInfo.playerType
                    randomPool.remove(civID)
                }
                for (playerInfo in gameSetupInfo.gameParameters.players) {
                    if (playerInfo.chosenCiv == Constants.random) continue
                    gameInfo.getCivilizationOrNull(playerInfo.chosenCiv)?.assign(playerInfo)
                }
                // Now assign player types for "Random" entries
                for (playerInfo in gameSetupInfo.gameParameters.players) {
                    if (playerInfo.chosenCiv != Constants.random) continue
                    val civID = randomPool.randomOrNull() ?: continue
                    gameInfo.getCivilizationOrNull(civID)?.assign(playerInfo)
                }
                // If the Spectator was active when saved, skip it
                if (gameInfo.currentPlayer == Constants.spectator) {
                    gameInfo.currentPlayer = ""
                    gameInfo.nextTurn() // TODO Risky - triggers?
                    gameInfo.turns--
                }
                gameInfo
            }
        } catch (exception: Exception) {
            exception.printStackTrace()
            launchOnGLThread {
                popup.apply {
                    reuseWith("It looks like we can't make a map with the parameters you requested!")
                    row()
                    addGoodSizedLabel("Maybe you put too many players into too small a map?").row()
                    addButton("Copy to clipboard"){
                        Gdx.app.clipboard.contents = exception.stackTraceToString()
                    }
                    addCloseButton()
                }
                Gdx.input.inputProcessor = stage
                rightSideButton.enable()
                rightSideButton.setText("Start game!".tr())
            }
            return@coroutineScope
        }

        var previewUploadPending = false
        if (gameSetupInfo.gameParameters.isOnlineMultiplayer) {
            newGame.isUpToDate = true // So we don't try to download it from dropbox the second after we upload it - the file is not yet ready for loading!
            try {
                game.onlineMultiplayer.createGame(newGame)
            } catch (exception: MultiplayerGameCreationPartialException) {
                if (!exception.localRecoveryAvailable) {
                    launchOnGLThread {
                        Gdx.app.clipboard.contents = exception.gameId
                        popup.reuseWith(
                            "Creation stopped before upload because a local recovery copy " +
                                "could not be saved.",
                            true,
                        )
                        Gdx.input.inputProcessor = stage
                        rightSideButton.enable()
                        rightSideButton.setText("Start game!".tr())
                    }
                    return@coroutineScope
                }
                if (!exception.fullUploadConfirmed) {
                    launchOnGLThread {
                        Gdx.app.clipboard.contents = exception.gameId
                        popup.reuseWith(
                            "Remote creation could not be confirmed. The Game ID was copied " +
                                "and a local recovery copy was saved. Open that Multiplayer " +
                                "entry to resume this exact game; do not start another one.",
                            true,
                        )
                        Gdx.input.inputProcessor = stage
                        rightSideButton.disable()
                        rightSideButton.setText("Recovery saved".tr())
                    }
                    return@coroutineScope
                }
                // The full game and a local recovery copy both exist. Continue into that exact
                // game instead of generating a new UUID and abandoning the remote full save.
                previewUploadPending = !exception.previewConfirmed
            } catch (ex: FileStorageRateLimitReached) {
                launchOnGLThread {
                    Gdx.app.clipboard.contents = newGame.gameId
                    popup.reuseWith(
                        "Server limit reached! Please wait for [${ex.limitRemainingSeconds}] seconds. " +
                            "The Game ID was copied; open its saved Multiplayer entry to retry.",
                        true,
                    )
                    Gdx.input.inputProcessor = stage
                    rightSideButton.disable()
                    rightSideButton.setText("Recovery saved".tr())
                }
                return@coroutineScope
            } catch (ex: MultiplayerGameCreationCancelledException) {
                rethrowCancellationAfterCleanup(ex) {
                    Concurrency.runOnGLThread {
                        Gdx.app.clipboard.contents = ex.gameId
                        val message = when {
                            !ex.localRecoveryAvailable ->
                                "Creation was interrupted and the Game ID was copied, but a local recovery copy could not be saved."
                            ex.fullUploadConfirmed ->
                                "The full game was uploaded before creation was interrupted. The Game ID was copied and a local recovery copy was saved."
                            else ->
                                "Remote creation could not be confirmed. The Game ID was copied and a local recovery copy was saved. " +
                                    "Open that Multiplayer entry to resume this exact game."
                        }
                        popup.reuseWith(message, true)
                        Gdx.input.inputProcessor = stage
                        if (ex.localRecoveryAvailable) {
                            rightSideButton.disable()
                            rightSideButton.setText("Recovery saved".tr())
                        } else {
                            rightSideButton.enable()
                            rightSideButton.setText("Start game!".tr())
                        }
                    }
                }
            } catch (ex: CancellationException) {
                rethrowCancellationAfterCleanup(ex) {
                    Concurrency.runOnGLThread {
                        popup.close()
                        Gdx.input.inputProcessor = stage
                        rightSideButton.enable()
                        rightSideButton.setText("Start game!".tr())
                    }
                }
            } catch (ex: Exception) {
                Log.error("Error while creating game", ex)
                launchOnGLThread {
                    Gdx.app.clipboard.contents = newGame.gameId
                    popup.reuseWith(
                        "Could not upload game. The Game ID was copied; open its saved " +
                            "Multiplayer entry to retry this exact game.",
                        true,
                    )
                    Gdx.input.inputProcessor = stage
                    rightSideButton.disable()
                    rightSideButton.setText("Recovery saved".tr())
                }
                return@coroutineScope
            }
        }

        val worldScreen = game.loadGame(newGame)
        
        worldScreen.autoSave()

        if (newGame.gameParameters.isOnlineMultiplayer) {
            launchOnGLThread {
                    // Save gameId to clipboard because you have to do it anyway.
                    Gdx.app.clipboard.contents = newGame.gameId
                    // Popup to notify the User that the gameID got copied to the clipboard
                    val message = if (previewUploadPending) {
                        "Game created and ID copied, but its turn preview is not confirmed. " +
                            "Submitting the first turn will retry it."
                    } else "Game ID copied to clipboard!"
                    ToastPopup(message.tr(), worldScreen, if (previewUploadPending) 6000 else 2500)
            }
        }
    }

    /** If the mod/baseRuleset combination inherited from [gameSetupInfo] is broken (Error severity),
     *  reset it to the default base ruleset with no mods, so we never build the UI around an
     *  unusable selection. */
    private fun resetIfInitialModsAreBroken() {
        val gameParameters = gameSetupInfo.gameParameters
        if (gameParameters.mods.isEmpty()) return
        val (_, errors) = RulesetCache.checkCombinedModLinks(gameParameters.mods, gameParameters.baseRuleset)
        if (!errors.isError()) return
        gameParameters.mods.clear()
        gameParameters.baseRuleset = BaseRuleset.Civ_V_GnK.fullName
    }

    /** Updates our local [ruleset] from [gameSetupInfo], guarding against exceptions.
     *
     *  Note: The options reset on failure is not propagated automatically to the Widgets -
     *  the caller must ensure that.
     *
     *  @return Success - failure means gameSetupInfo was reset to defaults and the Ruleset was reverted to G&K
     */
    fun tryUpdateRuleset(updateUI: Boolean): Boolean {
        var success = true
        fun handleFailure(message: String): Ruleset {
            success = false
            ToastPopup(message, this, 5000)
            gameSetupInfo.gameParameters.mods.clear()
            gameSetupInfo.gameParameters.baseRuleset = BaseRuleset.Civ_V_GnK.fullName
            return RulesetCache[BaseRuleset.Civ_V_GnK.fullName]!!
        }

        val newRuleset = try {
            // this can throw with non-default gameSetupInfo, e.g. when Mods change or we change the impact of Mod errors
            RulesetCache.getComplexRuleset(gameSetupInfo.gameParameters)
        } catch (ex: UncivShowableException) {
            handleFailure("«YELLOW»{Your previous options needed to be reset to defaults.}«»\n\n${ex.localizedMessage}")
        } catch (ex: Throwable) {
            Log.debug("updateRuleset failed", ex)
            handleFailure("«RED»{Your previous options needed to be reset to defaults.}«»")
        }

        ruleset.clear()
        ruleset.add(newRuleset)
        // Activate restored mod translations before constructing or updating the options tables.
        game.translations.translationActiveMods = gameSetupInfo.gameParameters.getModsAndBaseRuleset()
        ImageGetter.setNewRuleset(ruleset)
        game.musicController.setModList(gameSetupInfo.gameParameters.getModsAndBaseRuleset())

        if (updateUI) newGameOptionsTable.updateRuleset(ruleset)
        return success
    }

    fun lockTables() {
        playerPickerTable.locked = true
        newGameOptionsTable.locked = true
    }

    fun unlockTables() {
        playerPickerTable.locked = false
        newGameOptionsTable.locked = false
    }

    fun updateTables() {
        playerPickerTable.gameParameters = gameSetupInfo.gameParameters
        playerPickerTable.update()
        newGameOptionsTable.changeGameParameters(gameSetupInfo.gameParameters)
        newGameOptionsTable.update()
    }

    override fun recreate(): BaseScreen = NewGameScreen(gameSetupInfo, setupSection)
}
