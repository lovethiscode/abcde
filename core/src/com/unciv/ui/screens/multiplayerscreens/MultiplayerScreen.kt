package com.unciv.ui.screens.multiplayerscreens

import com.unciv.ui.components.fonts.UiTypography
import com.unciv.ui.components.fonts.TextRole
import com.unciv.ui.components.widgets.UiNavigation
import com.unciv.models.skins.ExpeditionBlueTheme
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.Container
import com.badlogic.gdx.scenes.scene2d.ui.Image
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener
import com.unciv.ui.components.input.activate
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.popups.AnimatedMenuPopup
import com.unciv.ui.screens.basescreen.RecreateOnResize
import com.unciv.ui.screens.basescreen.SafeAreaViewport
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.badlogic.gdx.utils.Align
import com.unciv.Constants
import com.unciv.logic.event.EventBus
import com.unciv.logic.GameInfoPreview
import com.unciv.logic.files.UncivFiles
import com.unciv.logic.multiplayer.MultiplayerGameUpdateEnded
import com.unciv.logic.multiplayer.MultiplayerGameUpdateStarted
import com.unciv.logic.multiplayer.MultiplayerGameInvite
import com.unciv.logic.multiplayer.MultiplayerGamePreview
import com.unciv.logic.multiplayer.MultiplayerPlayerAction
import com.unciv.logic.multiplayer.performPlayerAction
import com.unciv.logic.multiplayer.playerActionError
import com.unciv.logic.multiplayer.retryPlayerAction
import com.unciv.logic.multiplayer.storage.MultiplayerAuthException
import com.unciv.models.ruleset.RulesetCache
import com.unciv.models.translations.tr
import com.unciv.ui.components.widgets.UncivTextField
import com.unciv.ui.components.extensions.darken
import com.unciv.ui.components.extensions.disable
import com.unciv.ui.components.extensions.enable
import com.unciv.ui.components.extensions.formatShort
import com.unciv.ui.components.extensions.isEnabled
import com.unciv.ui.components.extensions.setFontColor
import com.unciv.ui.components.extensions.setFontSize
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.input.KeyCharAndCode
import com.unciv.ui.components.input.keyShortcuts
import com.unciv.ui.components.input.onActivation
import com.unciv.ui.components.input.onClick
import com.unciv.ui.popups.AuthPopup
import com.unciv.ui.popups.ConfirmPopup
import com.unciv.ui.popups.Popup
import com.unciv.ui.popups.ToastPopup
import com.unciv.ui.screens.pickerscreens.PickerScreen
import com.unciv.ui.screens.savescreens.LoadGameScreen
import com.unciv.utils.Concurrency
import com.unciv.utils.Log
import com.unciv.utils.launchOnGLThread
import kotlinx.coroutines.CancellationException
import org.threeten.bp.Duration
import com.unciv.ui.components.widgets.AutoScrollPane as ScrollPane

internal val multiplayerTitleFontSize get() = UiTypography.size(TextRole.PageTitle, legacySize = 28)
internal val multiplayerEmphasisFontSize get() = UiTypography.size(TextRole.SectionTitle, legacySize = 24)
internal val multiplayerContentFontSize get() = UiTypography.size(TextRole.Body, legacySize = 22)
internal val multiplayerSecondaryFontSize get() = UiTypography.size(TextRole.Supporting, legacySize = 18)
internal val multiplayerDenseFontSize get() = UiTypography.size(TextRole.Control, legacySize = 16)

class MultiplayerScreen : PickerScreen(disableScroll = true), RecreateOnResize {
    private val appearance = MultiplayerScreenStyle(UiNavigation.minimumTouchSize() / 44f)
    override val screenClearColor: Color get() = appearance.background
    private val layout = MultiplayerScreenLayout(stage.width / appearance.unit, stage.height / appearance.unit,
        UiNavigation.isIos && stage.height / appearance.unit < 600f)
    private val filterButtons = linkedMapOf<MultiplayerGameFilter, TextButton>()
    private val detailsPanel = Table()
    private val gamesPanelContent = Table()
    private val gameActionsButton = appearance.button("", MultiplayerIcon.More, outlined = true).apply {
        name = "multiplayer-game-actions"
        onClick { showGameActions() }
    }
    private val playerMenuButton = appearance.button("", MultiplayerIcon.More, ghost = true).apply {
        name = "multiplayer-player-menu"
        onClick { showMenu(this, listOf(copyUserIdButton, helpButton) +
            if (game.platformCapabilities.multiplayerFriends) listOf(friendsListButton) else emptyList()) }
    }

    private var selectedGame: MultiplayerGamePreview? = null
    private val events = EventBus.EventReceiver()
    private val refreshingGames = mutableSetOf<String>()
    private var refreshBatchInProgress = false
    private var lastRefreshHadFailures = false
    private var hasCompletedRefresh = false
    private var isDisposed = false
    private var isDownloadingMods = false
    private var playerActionInProgress = false

    private val copyGameIdButton = createCopyGameIdButton()
    private val copyInviteButton = createCopyInviteButton()
    private val shareGameButton = createShareGameButton()
    private val resignButton = createPlayerActionButton(MultiplayerPlayerAction.Resign)
    private val forceResignButton = createPlayerActionButton(MultiplayerPlayerAction.ForceResign)
    private val skipTurnButton = createPlayerActionButton(MultiplayerPlayerAction.SkipTurn)
    private val retryPlayerActionButton = "Retry pending action".toTextButton().apply {
        onClick { selectedGame?.let { runPlayerAction(it) } }
    }
    private val deleteButton = createDeleteButton()
    private val renameButton = createRenameButton()

    private val gameSpecificButtons = listOf(copyGameIdButton, copyInviteButton, shareGameButton, resignButton, deleteButton, renameButton)

    private val addGameButton = createAddGameButton()
    private val createRoomButton = "Create room".toTextButton().apply {
        isEnabled = !game.multiplayerLobbyServerUrl.isNullOrBlank()
        onClick { MultiplayerLobbyScreen.openRoom() }
    }
    private val copyUserIdButton = createCopyUserIdButton()
    private val friendsListButton = createFriendsListButton()
    private val refreshButton = createRefreshButton()
    private val helpButton = createHelpButton()

    private val gameCountLabel = "".toLabel(Color.LIGHT_GRAY, fontSize = multiplayerSecondaryFontSize)
    private val gamesHeader = Table()
    private val gamesHeadingLabel = "My multiplayer games".toLabel(appearance.text, fontSize = appearance.font(TextRole.Control)).apply {
        setEllipsis(true)
    }
    private val serverStatusDot = "●".toLabel(Color.LIGHT_GRAY, fontSize = multiplayerSecondaryFontSize)
    private val serverUrlLabel = "".toLabel(fontSize = multiplayerSecondaryFontSize).apply { setEllipsis(true) }
    private val serverStateLabel = "".toLabel(Color.LIGHT_GRAY, fontSize = multiplayerSecondaryFontSize).apply {
        setEllipsis(true)
    }

    private val detailTitleLabel = "".toLabel(fontSize = multiplayerEmphasisFontSize).apply { setEllipsis(true) }
    private val detailRulesetLabel = "".toLabel(Color.LIGHT_GRAY, fontSize = multiplayerSecondaryFontSize).apply {
        setEllipsis(true)
    }
    private val detailStatusLabel = "".toLabel(fontSize = multiplayerSecondaryFontSize, alignment = Align.center).apply {
        setEllipsis(true)
    }
    private val detailStatusBadge = Table().apply {
        add(detailStatusLabel).minWidth(0f).pad(5f, 9f, 5f, 9f)
    }
    private val currentTurnValue = "".toLabel(fontSize = multiplayerContentFontSize).apply { setEllipsis(true) }
    private val turnDurationValue = "".toLabel(fontSize = multiplayerContentFontSize).apply { setEllipsis(true) }
    private val ownCivilizationValue = "".toLabel(fontSize = multiplayerContentFontSize).apply { setEllipsis(true) }
    private val timeRemainingValue = "".toLabel(fontSize = multiplayerContentFontSize).apply { setEllipsis(true) }
    private val detailNoteLabel = "".toLabel(Color.LIGHT_GRAY, fontSize = multiplayerSecondaryFontSize).apply {
        setEllipsis(true)
    }
    private val advancedActionsTable = Table()
    private var detailsActionWidth = 0f

    internal val gameList = GameList(::selectGame, appearance)

    init {
        setDefaultCloseAction()
        initLandscapeLayout()
        gameList.onContentChanged = ::updateGameCount
        setupRightSideButton()
        setupRefreshEvents()
        unselectGame()

        if (game.settings.multiplayer.securePasswordMigrationFailed) {
            Popup(this).apply {
                addGoodSizedLabel(
                    "Some saved multiplayer passwords could not be moved to secure storage. " +
                        "They were removed from settings; authenticate again when prompted."
                ).row()
                addCloseButton {
                    game.settings.multiplayer.dismissSecurePasswordMigrationWarning()
                    game.settings.save()
                }
                open()
            }
        }

        game.onlineMultiplayer.games
            .sortedWith(compareByDescending<MultiplayerGamePreview> { it.preview?.let { preview ->
                buildMultiplayerGameUiModel(
                    it.name,
                    preview,
                    it.error != null,
                    it.getLastUpdate(),
                    game.settings.multiplayer.getUserId(),
                ).isUsersTurn
            } == true }.thenBy { it.name })
            .firstOrNull()
            ?.let { selectGame(it.name) }

        refreshAllGames()
    }

    private fun onGameDeleted(gameName: String) {
        if (selectedGame?.name == gameName) unselectGame()
        gameList.update()
        updateGameCount()
    }

    private fun setupRightSideButton() {
        rightSideButton.setText("Download latest save and enter".tr())
        appearance.decorate(rightSideButton, MultiplayerIcon.Download, primary = true, fontSize = TextRole.Control)
        rightSideButton.onClick {
            if (isDownloadingMods) return@onClick
            val targetGame = selectedGame ?: return@onClick
            val targetPreview = targetGame.preview ?: return@onClick
            val missingMods = targetPreview.gameParameters.getModsAndBaseRuleset()
                .filter { !RulesetCache.containsKey(it) }
            if (missingMods.isEmpty()) {
                return@onClick MultiplayerHelpers.loadMultiplayerGame(this, targetGame)
            }
            if (!game.platformCapabilities.onlineModManagement) {
                ToastPopup(
                    "Install the same version of every required ruleset and mod before joining. " +
                        "Automatic mod downloads are unavailable in this build.",
                    this,
                    6000L,
                )
                return@onClick
            }

            isDownloadingMods = true
            selectGame(targetGame.name)

            // Download missing mods
            Concurrency.runOnNonDaemonThreadPool(LoadGameScreen.downloadMissingMods) {
                try {
                    LoadGameScreen.loadMissingMods(missingMods, onModDownloaded = {
                        Concurrency.runOnGLThread {
                            if (!isDisposed && game.screen === this@MultiplayerScreen) {
                                ToastPopup("[$it] Downloaded!", this@MultiplayerScreen)
                            }
                        }
                    },
                    onCompleted = {
                        RulesetCache.loadRulesets()
                        Concurrency.runOnGLThread {
                            if (isDisposed || game.screen !== this@MultiplayerScreen) {
                                return@runOnGLThread
                            }
                            MultiplayerHelpers.loadMultiplayerGame(this@MultiplayerScreen, targetGame)
                        }
                    })
                } catch (ex: Exception) {
                    val (message) = LoadGameScreen.getLoadExceptionMessage(ex)
                    launchOnGLThread {
                        if (!isDisposed && game.screen === this@MultiplayerScreen) {
                            ToastPopup(message, this@MultiplayerScreen)
                        }
                    }
                } finally {
                    launchOnGLThread {
                        isDownloadingMods = false
                        if (!isDisposed) refreshSelectedGame()
                    }
                }
            }
        }
    }

    private fun createRefreshButton(): TextButton {
        val btn = "Refresh all games".toTextButton()
        btn.onClick { refreshAllGames() }
        return btn
    }

    private fun createAddGameButton(): TextButton {
        val positiveButtonStyle = skin.get("positive", TextButton.TextButtonStyle::class.java)
        val btn = "Join by invitation".toTextButton(positiveButtonStyle)
        btn.onClick {
            AddMultiplayerGamePopup(this).open()
        }
        return btn
    }

    private fun createHelpButton(): TextButton {
        val btn = "How multiplayer works".toTextButton()
        btn.onClick { showHelpPopup() }
        return btn
    }

    private fun initLandscapeLayout() {
        topTable.clear()
        bottomTable.clear()
        pickerPane.remove()
        val u = appearance::px
        if (UiNavigation.isIos) UiNavigation.styleBackButton(closeButton)
        else { closeButton.setText(""); appearance.decorate(closeButton, MultiplayerIcon.Back, ghost = true) }
        closeButton.name = "multiplayer-back"
        appearance.decorate(createRoomButton, MultiplayerIcon.Plus, outlined = true)
        createRoomButton.name = "multiplayer-create"
        appearance.decorate(addGameButton, MultiplayerIcon.Link, primary = true)
        addGameButton.name = "multiplayer-join"
        appearance.decorate(refreshButton, MultiplayerIcon.Refresh, ghost = true, fontSize = TextRole.Control)
        refreshButton.name = "multiplayer-refresh"
        appearance.decorate(shareGameButton, MultiplayerIcon.Share, outlined = true, fontSize = TextRole.Control)
        shareGameButton.name = "multiplayer-share"
        copyInviteButton.setText("Copy invite".tr())
        appearance.decorate(copyInviteButton, MultiplayerIcon.Link, outlined = true, fontSize = TextRole.Control)
        copyInviteButton.name = "multiplayer-copy-invite"
        rightSideButton.name = "multiplayer-enter"
        listOf(gameCountLabel, serverUrlLabel, serverStateLabel).forEach {
            it.setFontSize(appearance.font(TextRole.Supporting)); it.setFontColor(appearance.muted)
        }
        serverStatusDot.setFontSize(appearance.font(TextRole.Metadata))
        detailTitleLabel.setFontSize(appearance.font(TextRole.SectionTitle)); detailTitleLabel.setFontColor(appearance.text)
        detailRulesetLabel.setFontSize(appearance.font(TextRole.Supporting)); detailRulesetLabel.setFontColor(appearance.muted)
        detailStatusLabel.setFontSize(appearance.font(TextRole.Supporting))
        detailStatusBadge.clearChildren()
        detailStatusBadge.add(detailStatusLabel).minWidth(0f).maxWidth(u(120f)).pad(u(4f), u(9f), u(4f), u(9f))
        listOf(currentTurnValue, turnDurationValue, ownCivilizationValue, timeRemainingValue).forEach {
            it.setFontSize(appearance.font(TextRole.Control)); it.setFontColor(appearance.text)
        }
        timeRemainingValue.setFontColor(appearance.positive)
        detailNoteLabel.setFontSize(appearance.font(TextRole.Metadata)); detailNoteLabel.setFontColor(appearance.muted)
        detailsActionWidth = u(layout.detailsWidth - 32f)
        val drawingBounds = (stage.viewport as SafeAreaViewport).drawingBounds
        stage.addActor(Image(ImageGetter.getWhiteDotDrawable()).apply {
            color = appearance.background
            setBounds(drawingBounds.x, drawingBounds.y, drawingBounds.width, drawingBounds.height)
            touchable = Touchable.disabled
        })
        val root = Table().apply {
            name = "multiplayer-screen"
            setFillParent(true)
            pad(u(layout.topPadding), u(layout.horizontalPadding), u(layout.bottomPadding), u(layout.horizontalPadding))
            add(createHeader()).growX().minWidth(0f).minHeight(u(if (layout.twoColumns) 50f else 102f)).padBottom(u(10f)).row()
            val body = Table()
            body.add(createGamesPanel()).grow().minWidth(0f).minHeight(0f).width(u(layout.gamesWidth))
            if (layout.twoColumns) body.add(createDetailsPanel()).grow().minWidth(0f).minHeight(0f)
                .width(u(layout.detailsWidth)).padLeft(u(layout.columnGap))
            else {
                body.row()
                body.add(createDetailsPanel()).grow().minWidth(0f).minHeight(0f).padTop(u(16f))
            }
            add(body).grow().minWidth(0f).minHeight(0f).row()
            add(createServerBar()).growX().minWidth(0f).height(u(if (layout.phone && layout.twoColumns) 28f else 44f))
                .padTop(u(10f))
        }
        stage.addActor(root)
        updateServerStatus()
        updateGameCount()
    }

    private fun createHeader(): Table {
        val u = appearance::px
        val title = "Multiplayer".toLabel(appearance.text, fontSize = appearance.font(TextRole.PageTitle))
        val subtitle = "Take your turn when you are ready".toLabel(appearance.muted, fontSize = appearance.font(TextRole.Supporting)).apply {
            setEllipsis(true)
        }
        val heading = Table().apply {
            add(title).growX().minWidth(0f).left().row()
            add(subtitle).growX().minWidth(0f).left().padTop(u(4f))
        }
        val actions = Table().apply {
            val widths = listOf(createRoomButton.prefWidth / appearance.unit, addGameButton.prefWidth / appearance.unit)
            if (layout.twoColumns || fitsInOneRow(layout.contentWidth, widths, 8f)) {
                add(createRoomButton).height(u(44f)).minWidth(u(112f))
                add(addGameButton).height(u(44f)).minWidth(u(140f)).padLeft(u(8f))
            } else {
                add(createRoomButton).growX().minWidth(0f).height(u(44f)).row()
                add(addGameButton).growX().minWidth(0f).height(u(44f)).padTop(u(8f))
            }
        }
        return Table().apply {
            add(closeButton).width(if (UiNavigation.isIos) closeButton.prefWidth else u(44f)).height(u(44f))
            add(heading).growX().minWidth(0f).left().padLeft(u(12f))
            if (layout.twoColumns) add(actions).padLeft(u(12f))
            add(playerMenuButton).size(u(44f)).padLeft(u(8f))
            if (!layout.twoColumns) {
                row(); add(actions).colspan(3).growX().minWidth(0f).padTop(u(8f))
            }
        }
    }

    private fun createServerBar(): Table {
        val u = appearance::px
        val identity = Table().apply {
            add(serverStatusDot).padRight(u(6f))
            add(serverStateLabel).minWidth(0f).maxWidth(u(layout.contentWidth * .23f)).left()
            add(serverUrlLabel).growX().minWidth(0f).left().padLeft(u(10f))
        }
        return Table().apply {
            val identityContainer = Container(identity).fillX()
            if (layout.phone && layout.twoColumns) identityContainer.padTop(-u(16f))
            add(identityContainer).growX().minWidth(0f)
            add(Container(refreshButton).height(u(44f)).bottom()).height(u(if (layout.phone && layout.twoColumns) 28f else 44f))
                .padLeft(u(12f))
            refreshButton.setSize(refreshButton.prefWidth, u(44f))
        }
    }

    private fun createGamesPanel(): Table {
        val u = appearance::px
        val filters = Table().apply {
            for (filter in MultiplayerGameFilter.entries) {
                val button = appearance.button("", fontSize = TextRole.Control).apply {
                    name = "multiplayer-filter-${filter.name}"
                    onClick { changeFilter(filter) }
                }
                filterButtons[filter] = button
                add(button).height(u(44f)).minWidth(0f).padRight(u(4f))
            }
            add().growX()
        }
        gameList.name = "multiplayer-list"
        val listScroll = ScrollPane(gameList).apply {
            setScrollingDisabled(true, false); setOverscroll(false, false)
        }
        gamesPanelContent.name = "multiplayer-games-content"
        gamesPanelContent.add(filters).growX().minWidth(0f).row()
        gamesPanelContent.add(listScroll).grow().minWidth(0f).minHeight(0f).padTop(u(4f))
        return Table().apply {
            name = "multiplayer-games-panel"
            background = appearance.surface(appearance.panel, 12f)
            pad(u(16f))
            add(gamesHeader).growX().minWidth(0f).padBottom(u(10f)).row()
            add(gamesPanelContent).grow().minWidth(0f).minHeight(0f)
        }
    }

    private fun createDetailsPanel(): Table {
        detailsPanel.name = "multiplayer-details-content"
        detailsPanel.top()
        detailsPanel.pad(appearance.px(16f))
        val scroll = ScrollPane(detailsPanel).apply {
            setScrollingDisabled(true, false); setOverscroll(false, false)
        }
        return Table().apply {
            name = "multiplayer-details-panel"
            background = appearance.surface(appearance.panel, 12f)
            add(scroll).grow().minWidth(0f).minHeight(0f)
        }
    }

    private fun showSelectedDetails() {
        val u = appearance::px
        detailsPanel.clearChildren()
        detailsPanel.pad(u(16f))
        val header = Table().apply {
            val titleCopy = Table().apply {
                add(detailTitleLabel).growX().minWidth(0f).left().row()
                add(detailRulesetLabel).growX().minWidth(0f).left().padTop(u(5f))
            }
            add(titleCopy).growX().minWidth(0f)
            add(detailStatusBadge).minWidth(0f).top().padLeft(u(8f))
        }
        val grid = Table().apply {
            defaults().growX().minWidth(0f)
            add(createInfoItem("Current turn", currentTurnValue)).padRight(u(12f))
            add(createInfoItem("Time remaining", timeRemainingValue)).row()
            add(createInfoItem("My civilization", ownCivilizationValue)).padRight(u(12f)).padTop(u(8f))
            add(createInfoItem("This turn has lasted", turnDurationValue)).padTop(u(8f))
        }
        detailsPanel.add(header).growX().minWidth(0f).padBottom(u(8f)).row()
        detailsPanel.add(grid).growX().minWidth(0f).padBottom(u(8f)).row()
        detailsPanel.add(detailNoteLabel).growX().minWidth(0f).left().padBottom(u(6f)).row()
        rightSideButton.label.setWrap(false)
        rightSideButton.labelCell.maxWidth(appearance.px(layout.detailsWidth - 88f))
        detailsPanel.add(rightSideButton).growX().minWidth(0f).height(u(44f)).padBottom(u(6f)).row()
        val actions = Table().apply {
            if (game.platformCapabilities.multiplayerInviteLinks) {
                add(shareGameButton).growX().minWidth(0f).height(u(44f)).padRight(u(8f))
                add(copyInviteButton).growX().minWidth(0f).height(u(44f)).padRight(u(8f))
            }
            add(gameActionsButton).size(u(44f))
        }
        detailsPanel.add(actions).growX().minWidth(0f)
    }

    private fun createInfoItem(title: String, value: Label): Table = Table().apply {
        add(title.toLabel(appearance.muted, fontSize = appearance.font(TextRole.Metadata))).growX().minWidth(0f).left().row()
        add(value).growX().minWidth(0f).left().padTop(appearance.px(4f))
    }

    private fun showEmptyDetails(noGames: Boolean) {
        val u = appearance::px
        detailsPanel.clearChildren()
        detailsPanel.pad(u(24f))
        detailsPanel.center()
        if (noGames) {
            detailsPanel.add(appearance.image(MultiplayerIcon.Users, appearance.gold)).size(u(36f)).left().padBottom(u(12f)).row()
            detailsPanel.add("Start a civilization with friends".toLabel(appearance.text, fontSize = appearance.font(TextRole.PageTitle)).apply {
                wrap = true
            }).growX().minWidth(0f).left().padBottom(u(8f)).row()
            detailsPanel.add("Create a room and share its invitation, or join a friend's game.".toLabel(
                appearance.muted, fontSize = appearance.font(TextRole.Supporting)).apply { wrap = true })
                .growX().minWidth(0f).maxWidth(u(330f)).left().padBottom(u(16f)).row()
            val actions = Table().apply {
                val create = appearance.button("Create room", MultiplayerIcon.Plus, primary = true).apply {
                    name = "multiplayer-empty-create"; isEnabled = createRoomButton.isEnabled
                    onClick { createRoomButton.activate() }
                }
                val join = appearance.button("Join by invitation", MultiplayerIcon.Link, outlined = true).apply {
                    name = "multiplayer-empty-join"; onClick { addGameButton.activate() }
                }
                if (fitsInOneRow(layout.detailsWidth - 32f, listOf(create.prefWidth / appearance.unit, join.prefWidth / appearance.unit), 8f)) {
                    add(create).height(u(44f)); add(join).height(u(44f)).padLeft(u(8f))
                } else {
                    add(create).growX().height(u(44f)).row(); add(join).growX().height(u(44f)).padTop(u(8f))
                }
            }
            detailsPanel.add(actions).left().row()
            detailsPanel.add("Asynchronous turns, no need to be online together".toLabel(appearance.muted,
                fontSize = appearance.font(TextRole.Metadata))).growX().minWidth(0f).left().padTop(u(10f))
        } else {
            detailsPanel.add("No game selected".toLabel(appearance.text, fontSize = appearance.font(TextRole.SectionTitle))).left().row()
            detailsPanel.add("Select a game from the list to see its details.".toLabel(appearance.muted,
                fontSize = appearance.font(TextRole.Supporting)).apply { wrap = true }).growX().minWidth(0f).left().padTop(u(8f))
        }
    }

    private fun changeFilter(filter: MultiplayerGameFilter) {
        gameList.setFilter(filter)
        val visible = gameList.visibleNames()
        if (selectedGame?.name !in visible) {
            visible.firstOrNull()?.let(::selectGame) ?: unselectGame()
        }
        updateGameCount()
    }

    private fun showGameActions() {
        if (selectedGame == null) return
        val actions = mutableListOf(copyGameIdButton, renameButton, deleteButton)
        if (game.platformCapabilities.multiplayerAdvancedActions) {
            actions += resignButton
            if (skipTurnButton.isVisible) actions += skipTurnButton
            if (forceResignButton.isVisible) actions += forceResignButton
            if (retryPlayerActionButton.isVisible) actions += retryPlayerActionButton
        }
        showMenu(gameActionsButton, actions)
    }

    private fun showMenu(anchor: Actor, actions: List<TextButton>) {
        object : AnimatedMenuPopup(stage, anchor) {
            override fun createContentTable(): Table = Table().apply {
                name = "multiplayer-action-menu"
                background = appearance.surface(appearance.control)
                pad(appearance.px(8f))
                for (source in actions) {
                    val destructive = source in listOf(deleteButton, resignButton, forceResignButton)
                    val item = appearance.button(source.text.toString(), ghost = true, fontSize = TextRole.Control).apply {
                        name = "multiplayer-menu-${source.text}"
                        isEnabled = source.isEnabled
                        if (destructive) {
                            style.fontColor = appearance.danger
                            style.downFontColor = appearance.danger
                            style.overFontColor = appearance.danger
                        }
                        onClick { close(); source.activate() }
                    }
                    add(item).growX().minWidth(appearance.px(180f)).minHeight(appearance.px(44f)).row()
                }
            }
        }
    }

    private fun setupRefreshEvents() {
        events.receive(MultiplayerGameUpdateStarted::class) {
            refreshingGames += it.name
            gameList.refreshingNames += it.name
            updateServerStatus()
            onGameChanged(it.name)
        }
        events.receive(MultiplayerGameUpdateEnded::class) {
            refreshingGames -= it.name
            gameList.refreshingNames -= it.name
            gameList.update()
            if (selectedGame?.name !in gameList.visibleNames()) changeFilter(gameList.filter)
            if (refreshingGames.isEmpty() && !refreshBatchInProgress) {
                hasCompletedRefresh = true
                lastRefreshHadFailures = game.onlineMultiplayer.games.any { game -> game.error != null }
            }
            updateServerStatus()
            onGameChanged(it.name)
        }
    }

    private fun refreshAllGames() {
        if (refreshBatchInProgress) return
        refreshBatchInProgress = true
        refreshButton.disable()
        updateServerStatus()
        refreshSelectedGame()
        Concurrency.run("Update all multiplayer games") {
            var requestFailed = false
            try {
                game.onlineMultiplayer.requestUpdate()
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Exception) {
                requestFailed = true
                Log.error("Could not refresh multiplayer games", ex)
            } finally {
                launchOnGLThread {
                    if (isDisposed) return@launchOnGLThread
                    refreshBatchInProgress = false
                    refreshingGames.clear()
                    gameList.refreshingNames.clear()
                    hasCompletedRefresh = true
                    lastRefreshHadFailures = requestFailed ||
                        game.onlineMultiplayer.games.any { it.error != null }
                    refreshButton.enable()
                    gameList.update()
                    changeFilter(gameList.filter)
                    updateGameCount()
                    refreshSelectedGame()
                    updateServerStatus()
                }
            }
        }
    }

    private fun updateServerStatus() {
        serverUrlLabel.setText(game.onlineMultiplayer.multiplayerServer.getServerUrl())
        val isRefreshing = refreshBatchInProgress || refreshingGames.isNotEmpty()
        val state = when {
            isRefreshing -> "Refreshing..."
            lastRefreshHadFailures -> "Some games failed to refresh"
            hasCompletedRefresh -> "Refresh complete"
            else -> "Connection not checked"
        }
        serverStateLabel.setText(state.tr())
        val stateColor = when {
            isRefreshing -> appearance.warning
            lastRefreshHadFailures -> appearance.danger
            hasCompletedRefresh -> appearance.positive
            else -> appearance.muted
        }
        serverStateLabel.setFontColor(stateColor)
        serverStatusDot.setFontColor(stateColor)
    }

    private fun updateGameCount() {
        val models = gameList.models()
        gameCountLabel.setText("[${models.size}] multiplayer matches".tr())
        updateGamesHeader()
        for ((filter, button) in filterButtons) {
            val count = models.count(filter::matches)
            val caption = when (filter) {
                MultiplayerGameFilter.All -> "All [$count]"
                MultiplayerGameFilter.YourTurn -> "Your turn [$count]"
                MultiplayerGameFilter.Waiting -> "Waiting [$count]"
            }
            button.setText(caption.tr())
            button.style = appearance.buttonStyle(ghost = gameList.filter != filter)
        }
        val noGames = models.isEmpty()
        val showsEmpty = gamesPanelContent.name == "multiplayer-empty-list"
        if (noGames != showsEmpty) {
            gamesPanelContent.clearChildren()
            if (noGames) {
                gamesPanelContent.name = "multiplayer-empty-list"
                gamesPanelContent.add(appearance.image(MultiplayerIcon.Games, appearance.muted)).size(appearance.px(38f)).padBottom(appearance.px(12f)).row()
                gamesPanelContent.add("No multiplayer games yet".toLabel(appearance.text, fontSize = appearance.font(TextRole.Control))).padBottom(appearance.px(8f)).row()
                gamesPanelContent.add("Your games will appear here".toLabel(appearance.muted, fontSize = appearance.font(TextRole.Supporting)))
            } else {
                gamesPanelContent.name = "multiplayer-games-content"
                val filters = Table()
                filterButtons.values.forEach { filters.add(it).height(appearance.px(44f)).minWidth(0f).padRight(appearance.px(4f)) }
                filters.add().growX()
                gamesPanelContent.add(filters).growX().minWidth(0f).row()
                gamesPanelContent.add(ScrollPane(gameList).apply {
                    setScrollingDisabled(true, false); setOverscroll(false, false)
                }).grow().minWidth(0f).minHeight(0f).padTop(appearance.px(4f))
            }
        }
        if (selectedGame == null) showEmptyDetails(noGames)
    }

    private fun updateGamesHeader() {
        val availableWidth = appearance.px(layout.gamesWidth - 32f).coerceAtLeast(1f)
        val gap = appearance.px(8f)
        gamesHeader.clearChildren()
        if (gamesHeadingLabel.prefWidth + gameCountLabel.prefWidth + gap <= availableWidth) {
            gamesHeader.add(gamesHeadingLabel).growX().minWidth(0f).left()
            gamesHeader.add(gameCountLabel).minWidth(0f).right().padLeft(gap)
        } else {
            gamesHeader.add(gamesHeadingLabel).growX().minWidth(0f).left().row()
            gamesHeader.add(gameCountLabel).maxWidth(availableWidth).right().padTop(appearance.px(4f))
        }
    }

    internal fun onMultiplayerGameAdded() {
        gameList.update()
        updateGameCount()
        if (selectedGame == null) {
            game.onlineMultiplayer.games.firstOrNull()?.let { selectGame(it.name) }
        }
    }

    private fun onGameChanged(name: String) {
        if (selectedGame?.name == name) refreshSelectedGame()
        updateGameCount()
    }

    private fun createPlayerActionButton(action: MultiplayerPlayerAction): TextButton {
        val label = when (action) {
            MultiplayerPlayerAction.Resign -> "Resign"
            MultiplayerPlayerAction.SkipTurn -> "Skip turn of current player"
            MultiplayerPlayerAction.ForceResign -> "Force current player to resign"
        }
        return label.toTextButton(skin.get("negative", TextButton.TextButtonStyle::class.java)).apply {
            disable()
            onClick {
                val targetGame = selectedGame ?: return@onClick
                val expected = targetGame.preview ?: return@onClick
                val civName = expected.civilizations.firstOrNull { it.civID == expected.currentPlayer }?.civName
                    ?: return@onClick
                val question = when (action) {
                    MultiplayerPlayerAction.Resign -> "Are you sure you ([$civName]) want to resign?"
                    MultiplayerPlayerAction.SkipTurn -> "Are you sure you want to skip the turn of [$civName]?"
                    MultiplayerPlayerAction.ForceResign -> "Are you sure you want to force the current player ([$civName]) to resign?"
                }
                val prompt = if (action == MultiplayerPlayerAction.SkipTurn) question else
                    question.tr() + "\n\n" + "After resigning, this civilization will be permanently controlled by AI.".tr()
                ConfirmPopup(this@MultiplayerScreen, prompt, "Yes") {
                    runPlayerAction(targetGame, expected, action)
                }.open()
            }
        }
    }

    private fun runPlayerAction(
        targetGame: MultiplayerGamePreview,
        expected: GameInfoPreview? = null,
        action: MultiplayerPlayerAction? = null,
    ) {
        if (playerActionInProgress || isDisposed) return
        playerActionInProgress = true
        refreshSelectedGame()
        val popup = Popup(this).apply {
            addGoodSizedLabel(Constants.working).row()
            open()
        }
        val preview = expected ?: targetGame.preview!!
        Concurrency.runOnNonDaemonThreadPool("Multiplayer player action") {
            var failure: Exception? = null
            try {
                val multiplayer = game.onlineMultiplayer
                val pending = multiplayer.pendingTurnUploadStateFor(
                    preview.gameId, preview.gameParameters.multiplayerServerUrl,
                ).get()
                if (action == null || pending?.playerAction != null) multiplayer.retryPlayerAction(targetGame)
                else multiplayer.performPlayerAction(targetGame, preview, action)
            } catch (ex: Exception) {
                failure = ex
            } finally {
                launchOnGLThread {
                    playerActionInProgress = false
                    if (isDisposed) return@launchOnGLThread
                    gameList.update()
                    refreshSelectedGame()
                    val ex = failure
                    when (ex) {
                        null, is CancellationException -> popup.close()
                        is MultiplayerAuthException -> {
                            popup.close()
                            AuthPopup(
                                this@MultiplayerScreen,
                                game.onlineMultiplayer.serverFor(preview.gameParameters.multiplayerServerUrl),
                                { success -> if (success) runPlayerAction(targetGame, expected, action) },
                            ).open(true)
                        }
                        else -> {
                            val (message) = LoadGameScreen.getLoadExceptionMessage(ex)
                            popup.reuseWith(message, true)
                        }
                    }
                }
            }
        }
    }

    private fun createDeleteButton(): TextButton {
        val negativeButtonStyle = skin.get("negative", TextButton.TextButtonStyle::class.java)
        val deleteButton = "Delete local record".toTextButton(negativeButtonStyle).apply { disable() }
        deleteButton.onClick {
            val targetGame = selectedGame ?: return@onClick
            val askPopup = ConfirmPopup(
                    this,
                    "Are you sure you want to delete this local multiplayer record?",
                    "Delete local record",
            ) {
                try {
                    game.onlineMultiplayer.multiplayerFiles.deleteGame(targetGame)
                    onGameDeleted(targetGame.name)
                } catch (ex: Exception) {
                    Log.error("Could not delete game!", ex)
                    ToastPopup("Could not delete game!", this)
                }
            }
            askPopup.open()
        }
        return deleteButton
    }

    private fun createRenameButton(): TextButton {
        val btn = "Rename".toTextButton().apply { disable() }
        btn.onClick {
            val targetGame = selectedGame ?: return@onClick
            Popup(this).apply {
                val textField = UncivTextField("Game name", targetGame.name)
                // slashes in mp names are interpreted as directory separators, so we don't allow them
                textField.textFieldFilter = UncivFiles.fileNameTextFieldFilter()
                add(textField).width(stageToShowOn.width / 2).row()
                val saveButton = "Save".toTextButton()
                fun validName(): Boolean = textField.text.trim().let {
                    it.isNotEmpty() && it != "." && it != ".." && '/' !in it && '\\' !in it
                }
                textField.addListener(object : ChangeListener() {
                    override fun changed(event: ChangeEvent?, actor: Actor?) { saveButton.isEnabled = validName() }
                })

                val saveNewNameFunction = save@{
                    if (!validName()) return@save
                    val newName = textField.text.trim()
                    val renamed = game.onlineMultiplayer.multiplayerFiles.changeGameName(targetGame, newName) {
                        if (it != null) reuseWith("Could not save game!", true)
                    }
                    if (renamed) {
                        gameList.update()
                        selectGame(newName)
                        close()
                    }
                }

                saveButton.onActivation(saveNewNameFunction)
                saveButton.keyShortcuts.add(KeyCharAndCode.RETURN)
                textField.cursorPosition = textField.text.length
                this@MultiplayerScreen.stage.keyboardFocus = textField
                add(saveButton)
                open()
            }
        }
        return btn
    }

    private fun createCopyGameIdButton(): TextButton {
        val btn = "Copy game ID".toTextButton().apply { disable() }
        btn.onClick {
            val gameInfo = selectedGame?.preview
            if (gameInfo != null) {
                Gdx.app.clipboard.contents = gameInfo.gameId
                ToastPopup("Game ID copied to clipboard!", this)
            }
        }
        return btn
    }

    private fun createCopyInviteButton(): TextButton {
        return "Copy invitation link".toTextButton().apply {
            disable()
            onClick {
                val preview = selectedGame?.preview ?: return@onClick
                val server = preview.gameParameters.multiplayerServerUrl ?: return@onClick
                try {
                    Gdx.app.clipboard.contents = MultiplayerGameInvite.createLink(preview.gameId, server)
                    ToastPopup("Game invitation copied. Send it to another iOS player.", this@MultiplayerScreen)
                } catch (ex: Exception) {
                    ToastPopup(LoadGameScreen.getLoadExceptionMessage(ex).first, this@MultiplayerScreen)
                }
            }
        }
    }

    private fun createShareGameButton(): TextButton = "Share game".toTextButton().apply {
        disable()
        onClick {
            val selected = selectedGame ?: return@onClick
            val preview = selected.preview ?: return@onClick
            val server = preview.gameParameters.multiplayerServerUrl ?: return@onClick
            try {
                game.shareMultiplayerGame(selected.name, preview.gameId,
                    MultiplayerGameInvite.createLink(preview.gameId, server))
            } catch (ex: Exception) {
                ToastPopup(LoadGameScreen.getLoadExceptionMessage(ex).first, this@MultiplayerScreen)
            }
        }
    }

    private fun createFriendsListButton(): TextButton {
        val btn = "Friends list".toTextButton()
        btn.onClick {
            game.pushScreen{ ViewFriendsListScreen() }
        }
        return btn
    }

    private fun createCopyUserIdButton(): TextButton {
        val btn = "Copy my Player ID".toTextButton()
        btn.onClick {
            Gdx.app.clipboard.contents = game.settings.multiplayer.getUserId()
            ToastPopup("UserID copied to clipboard", this)
        }
        return btn
    }

    private fun showHelpPopup() {
        val helpPopup = Popup(this)
        if (UiNavigation.isIos)
            helpPopup.addTitleWithCloseButton("How multiplayer works".toLabel(fontSize = multiplayerTitleFontSize))
        else helpPopup.addGoodSizedLabel("How multiplayer works", size = multiplayerTitleFontSize).row()

        fun addSection(title: String, vararg lines: String) {
            helpPopup.addGoodSizedLabel(title, size = multiplayerContentFontSize, color = Color.GOLD).apply {
                width(stage.width * 0.72f)
                actor.setAlignment(Align.left)
            }.padTop(16f).row()
            for (line in lines) {
                helpPopup.addGoodSizedLabel(line, size = multiplayerSecondaryFontSize).apply {
                    width(stage.width * 0.72f)
                    actor.setAlignment(Align.left)
                }.padTop(6f).row()
            }
        }

        addSection("Play with iOS players",
            "1. Tap Create room, choose the rules and map, then share the invitation.",
            "2. Other iOS players open the invitation or paste it in Join by invitation, tap their civilization portrait to choose, then tap Ready.",
            "3. The host is ready automatically. When everyone is ready, the host taps Start game. No Player ID or Game ID exchange is needed.",
        )
        addSection("Play with Android players",
            "1. The Android player copies their Player ID from Multiplayer and sends it to the iOS host.",
            "2. In an empty player seat, the host taps Add player manually, pastes the Player ID, taps the portrait to choose a civilization, then taps Mark ready.",
            "3. After starting, the Game ID is copied automatically. Send it to the Android player to add the game in Multiplayer.",
            "Android players join the started game by Game ID; they do not join the waiting room through its invitation link.",
            "If Android hosts, send them your Player ID, then paste their Game ID in Join by invitation.",
        )
        addSection("After the game starts",
            "Multiplayer is asynchronous and turn-based. When it is your turn, download the latest save, play, then end your turn to upload it.",
            "All players must use the same game server and compatible game versions and rulesets.",
            "For chat, set or check your server password in Options → Multiplayer. Saving it reconnects chat automatically without leaving the game.",
        )
        if (!UiNavigation.isIos) helpPopup.addCloseButton("Got it")
        helpPopup.open()
    }

    private fun updateAdvancedActions() {
        if (!game.platformCapabilities.multiplayerAdvancedActions) return
        advancedActionsTable.clearChildren()
        friendsListButton.label.setFontSize(multiplayerDenseFontSize)
        resignButton.label.setFontSize(multiplayerDenseFontSize)
        skipTurnButton.label.setFontSize(multiplayerDenseFontSize)
        forceResignButton.label.setFontSize(multiplayerDenseFontSize)
        listOf(friendsListButton, resignButton, skipTurnButton, forceResignButton, retryPlayerActionButton).forEach {
            it.label.setEllipsis(false)
            it.labelCell.minWidth(0f)
        }

        retryPlayerActionButton.label.setFontSize(multiplayerDenseFontSize)
        val actions = mutableListOf<TextButton>()
        if (game.platformCapabilities.multiplayerFriends) actions += friendsListButton
        if (selectedGame != null) {
            actions += resignButton
        }
        if (skipTurnButton.isVisible) actions += skipTurnButton
        if (forceResignButton.isVisible) actions += forceResignButton
        if (retryPlayerActionButton.isVisible) actions += retryPlayerActionButton
        advancedActionsTable.addResponsiveActions(actions, detailsActionWidth, 40f)
    }

    private fun unselectGame() {
        selectedGame = null
        retryPlayerActionButton.isVisible = false
        gameList.select(null)
        rightSideButton.disable()
        for (button in gameSpecificButtons)
            button.disable()
        skipTurnButton.isVisible = false
        forceResignButton.isVisible = false
        updateAdvancedActions()
        showEmptyDetails(game.onlineMultiplayer.games.isEmpty())

        detailTitleLabel.setText("No game selected".tr())
        detailRulesetLabel.setText("Select a game from the list to see its details.".tr())
        detailStatusBadge.isVisible = false
        currentTurnValue.setText("—")
        turnDurationValue.setText("—")
        ownCivilizationValue.setText("—")
        timeRemainingValue.setText("—")
        detailNoteLabel.setText("")
        rightSideButton.setText("Download latest save and enter".tr())
    }

    private fun selectGame(name: String) {
        val multiplayerGame = game.onlineMultiplayer.multiplayerFiles.getGameByName(name)
        if (multiplayerGame == null) {
            // Should never happen
            unselectGame()
            return
        }

        selectedGame = multiplayerGame
        gameList.select(name)

        val preview = multiplayerGame.preview
        val actionsBlocked = refreshBatchInProgress ||
            name in refreshingGames || isDownloadingMods || playerActionInProgress
        copyGameIdButton.isEnabled = preview != null
        copyInviteButton.isEnabled = preview?.gameParameters?.multiplayerServerUrl != null
        shareGameButton.isEnabled = copyInviteButton.isEnabled
        renameButton.isEnabled = preview != null && !actionsBlocked
        deleteButton.isEnabled = !actionsBlocked
        rightSideButton.isEnabled = preview != null && !actionsBlocked

        val pendingState = preview?.let {
            game.onlineMultiplayer.pendingTurnUploadStateFor(it.gameId, it.gameParameters.multiplayerServerUrl)
        }
        val userId = game.settings.multiplayer.getUserId()
        val canAct = game.platformCapabilities.multiplayerAdvancedActions && !actionsBlocked
            && pendingState?.requiresRecovery() != true
        resignButton.isEnabled = canAct && preview != null &&
            playerActionError(MultiplayerPlayerAction.Resign, preview, preview, userId) == null
        skipTurnButton.isVisible = canAct && preview != null &&
            playerActionError(MultiplayerPlayerAction.SkipTurn, preview, preview, userId) == null
        skipTurnButton.isEnabled = skipTurnButton.isVisible
        forceResignButton.isVisible = canAct && preview != null &&
            playerActionError(MultiplayerPlayerAction.ForceResign, preview, preview, userId) == null
        forceResignButton.isEnabled = forceResignButton.isVisible
        retryPlayerActionButton.isVisible = game.platformCapabilities.multiplayerAdvancedActions &&
            pendingState?.get()?.let { it.playerAction != null && it.initiatingPlayerId == userId } == true
        retryPlayerActionButton.isEnabled = !actionsBlocked

        updateAdvancedActions()
        detailsPanel.top()
        showSelectedDetails()
        updateSelectedGameDetails()
    }

    private fun refreshSelectedGame() {
        val name = selectedGame?.name ?: return
        val refreshedGame = game.onlineMultiplayer.multiplayerFiles.getGameByName(name)
        if (refreshedGame == null) {
            unselectGame()
            return
        }
        selectedGame = refreshedGame
        selectGame(name)
    }

    private fun updateSelectedGameDetails() {
        val multiplayerGame = selectedGame ?: return
        val model = buildMultiplayerGameUiModel(
            multiplayerGame.name,
            multiplayerGame.preview,
            multiplayerGame.error != null,
            multiplayerGame.getLastUpdate(),
            game.settings.multiplayer.getUserId(),
            refreshBatchInProgress || multiplayerGame.name in refreshingGames,
        )

        detailTitleLabel.setText(model.name)
        val ruleset = model.baseRuleset?.let { "{$it}".tr() } ?: "Unknown".tr()
        val mods = if (model.mods.isEmpty()) "No mods".tr()
            else "${"Mods:".tr()} ${model.mods.joinToString { "{$it}".tr() }}"
        detailRulesetLabel.setText("$ruleset  ·  $mods")

        detailStatusBadge.isVisible = true
        val (statusText, statusColor) = when (model.status) {
            MultiplayerGameUiStatus.Refreshing -> "Refreshing game" to appearance.warning
            MultiplayerGameUiStatus.YourTurn -> "Your turn to play" to appearance.positive
            MultiplayerGameUiStatus.WaitingForOpponent -> "Waiting for another player" to appearance.muted
            MultiplayerGameUiStatus.RefreshFailed -> "Refresh failed" to appearance.danger
            MultiplayerGameUiStatus.Unavailable -> "Unavailable" to appearance.muted
        }
        detailStatusLabel.setText(statusText.tr())
        (detailStatusBadge.parent as? Table)?.getCell(detailStatusBadge)?.width(
            (detailStatusLabel.prefWidth + appearance.px(18f)).coerceAtMost(appearance.px(120f)))
        detailStatusLabel.setFontColor(statusColor)
        detailStatusBadge.background = appearance.surface(when (model.status) {
            MultiplayerGameUiStatus.YourTurn -> appearance.positiveSurface
            MultiplayerGameUiStatus.Refreshing -> appearance.warningSurface
            MultiplayerGameUiStatus.RefreshFailed -> appearance.dangerSurface
            else -> appearance.control
        })

        val currentPlayer = model.currentPlayer?.let { "{$it}".tr(hideIcons = true) } ?: "Unknown".tr()
        currentTurnValue.setText(
            model.turn?.let { "$currentPlayer  ·  ${"Turn [$it]".tr()}" } ?: currentPlayer
        )
        turnDurationValue.setText(model.currentTurnDuration?.let(::formatDuration) ?: "Unknown".tr())
        val ourCivilization = model.ownCivilization?.let { "{$it}".tr(hideIcons = true) } ?: "Unknown".tr()
        val difficulty = model.difficulty?.tr() ?: "Unknown".tr()
        ownCivilizationValue.setText("$ourCivilization  ·  $difficulty")
        timeRemainingValue.setText(
            model.turnTimeRemaining?.let(::formatDuration)
                ?: "Unknown".tr()
        )
        detailNoteLabel.setText(
            when {
                model.status == MultiplayerGameUiStatus.Unavailable ->
                    "Game details are unavailable.".tr()
                multiplayerGame.error != null ->
                    "Refresh failed. Showing the latest saved details.".tr()
                else -> "Last updated [${model.updatedAgo.formatShort()}] ago".tr()
            }
        )
        rightSideButton.setText(
            (if (model.isUsersTurn) "Download latest save and enter" else "View latest game state").tr()
        )
    }

    override fun recreate(): MultiplayerScreen = MultiplayerScreen().also { replacement ->
        replacement.changeFilter(gameList.filter)
        selectedGame?.name?.takeIf { it in replacement.gameList.visibleNames() }?.let(replacement::selectGame)
    }

    private fun formatDuration(duration: Duration): String {
        val seconds = duration.seconds.coerceAtLeast(0)
        return listOf(Duration.ofDays(seconds / 86400), Duration.ofHours(seconds % 86400 / 3600),
            Duration.ofMinutes(seconds % 3600 / 60), Duration.ofSeconds(seconds % 60))
            .filter { !it.isZero }.take(2).joinToString(" ") { it.formatShort() }
            .ifEmpty { Duration.ZERO.formatShort() }
    }

    override fun dispose() {
        isDisposed = true
        gameList.dispose()
        events.stopReceiving()
        super.dispose()
    }

    companion object {
        private val PanelBackground get() = ExpeditionBlueTheme.color(Color.valueOf("0d2140"), ExpeditionBlueTheme.panel)
        private val Positive get() = ExpeditionBlueTheme.color(Color.valueOf("66dfbd"), ExpeditionBlueTheme.success)
        private val Negative get() = ExpeditionBlueTheme.color(Color.valueOf("ff7c8b"), ExpeditionBlueTheme.danger)
    }
}

fun fitsInOneRow(
    availableWidth: Float,
    itemPrefWidths: List<Float>,
    horizontalGap: Float = 0f,
): Boolean = itemPrefWidths.sum() + horizontalGap * (itemPrefWidths.size - 1).coerceAtLeast(0) <= availableWidth

fun responsiveActionColumns(
    availableWidth: Float,
    itemPrefWidths: List<Float>,
    horizontalGap: Float = 6f,
): Int {
    if (itemPrefWidths.isEmpty()) return 0
    for (columns in itemPrefWidths.size downTo 1) {
        val everyRowFits = itemPrefWidths.chunked(columns).all { rowWidths ->
            fitsInOneRow(availableWidth, rowWidths, horizontalGap)
        }
        if (everyRowFits) return columns
    }
    return 1
}

private fun Table.addResponsiveActions(
    buttons: List<TextButton>,
    availableWidth: Float,
    buttonHeight: Float,
    horizontalGap: Float = 6f,
): Float {
    if (buttons.isEmpty()) return 0f
    val requiredWidths = buttons.map { maxOf(it.prefWidth, it.label.prefWidth + 30f) }
    val columns = responsiveActionColumns(availableWidth, requiredWidths, horizontalGap)
    for ((rowIndex, rowButtons) in buttons.chunked(columns).withIndex()) {
        if (rowButtons.size == 1 && columns > 1) {
            add(rowButtons.single()).growX().minWidth(0f).height(buttonHeight).colspan(columns)
                .padTop(if (rowIndex == 0) 0f else 4f).row()
            continue
        }
        rowButtons.forEachIndexed { index, button ->
            val cell = add(button).growX().minWidth(0f).height(buttonHeight)
                .padTop(if (rowIndex == 0) 0f else 4f)
            if (index < rowButtons.lastIndex) cell.padRight(horizontalGap)
        }
        row()
    }
    return requiredWidths.chunked(columns).maxOf { rowWidths ->
        rowWidths.sum() + horizontalGap * (rowWidths.size - 1)
    }
}
