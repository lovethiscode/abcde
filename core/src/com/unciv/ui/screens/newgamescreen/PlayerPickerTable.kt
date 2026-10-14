package com.unciv.ui.screens.newgamescreen

import com.unciv.ui.components.widgets.UiNavigation
import com.unciv.models.skins.ExpeditionBlueTheme
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.utils.Align
import com.unciv.Constants
import com.unciv.UncivGame
import com.unciv.logic.IdChecker
import com.unciv.logic.civilization.PlayerType
import com.unciv.logic.civilization.PlayerType.AI
import com.unciv.logic.civilization.PlayerType.Human
import com.unciv.logic.multiplayer.FriendList
import com.unciv.models.metadata.GameParameters
import com.unciv.models.metadata.GameSetupInfo
import com.unciv.models.metadata.Player
import com.unciv.models.ruleset.Ruleset
import com.unciv.models.ruleset.nation.Nation
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.darken
import com.unciv.ui.components.extensions.isEnabled
import com.unciv.ui.components.extensions.setFontColor
import com.unciv.ui.components.extensions.surroundWithCircle
import com.unciv.ui.components.extensions.toCheckBox
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.input.KeyCharAndCode
import com.unciv.ui.components.input.keyShortcuts
import com.unciv.ui.components.input.onActivation
import com.unciv.ui.components.input.onClick
import com.unciv.ui.components.widgets.UncivTextField
import com.unciv.ui.components.widgets.WrappableLabel
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.images.IconTextButton
import com.unciv.ui.popups.Popup
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.multiplayerscreens.FriendPickerList
import com.unciv.ui.screens.pickerscreens.PickerPane
import com.unciv.ui.screens.pickerscreens.PickerScreen
import com.unciv.utils.isUUID
import com.unciv.utils.removeRange
import com.unciv.ui.components.widgets.AutoScrollPane as ScrollPane

/**
 * This [Table] is used to pick or edit players information for new game creation.
 * Could be inserted to [NewGameScreen], or any other [BaseScreen]
 * which provides [GameSetupInfo] and [Ruleset].
 * Upon player changes updates property [gameParameters]. Also updates available nations when mod changes.
 * @param previousScreen A [Screen][BaseScreen] where the player table is inserted, should provide [GameSetupInfo] as property, updated when a player is added/deleted/changed
 * @param gameParameters contains info about number of players.
 * @param blockWidth sets a width for the Civ "blocks". If too small a third of the stage is used.
 */
class PlayerPickerTable(
    val previousScreen: IPreviousScreen,
    var gameParameters: GameParameters,
    blockWidth: Float = 0f
): Table() {
    val playerListTable = Table()
    val civBlocksWidth = if (blockWidth <= 10f) previousScreen.stage.width / 3 - 5f else blockWidth
    private var randomNumberLabel: WrappableLabel? = null
    private val directSetup = previousScreen is NewGameScreen && UiNavigation.isIos

    /** Locks player table for editing, currently unused, was previously used for scenarios and could be useful in the future. */
    var locked = false

    /** No random civilization is available, potentially used in the future during map editing. */
    var noRandom = false

    private val friendList = FriendList()

    init {
        for (player in gameParameters.players)
            player.playerId = "" // This is to stop people from getting other users' IDs and cheating with them in multiplayer games

        top()
        if (!directSetup) {
            gameParameters.shufflePlayerOrder = false
            add("Shuffle Civ Order at Start".toCheckBox(false) { gameParameters.shufflePlayerOrder = it }).padTop(5f).padBottom(5f).row()
        }
        add(ScrollPane(playerListTable).apply { setOverscroll(false, false) })
            .width(if (directSetup) (previousScreen as NewGameScreen).directSetupWidth else civBlocksWidth)
        update()
        background = BaseScreen.skinStrings.getUiBackground("NewGameScreen/PlayerPickerTable", tintColor = BaseScreen.skinStrings.skinConfig.clearColor)
        if (directSetup) background = null
    }

    /**
     * Updates view of main player table. Used when mod picked or player changed.
     * Also sets desired civilization, that is preferable for human players.
     * @param desiredCiv desired civilization name
     */
    fun update(desiredCiv: String = "") {
        playerListTable.clear()
        val ruleset = previousScreen.ruleset // the mod picking changes this ruleset

        if (previousScreen is NewGameScreen) {
            val self = gameParameters.players.firstOrNull { it.playerType == Human }
                ?: gameParameters.players.firstOrNull()
                ?: Player(playerType = Human)
            if (gameParameters.players.firstOrNull() !== self) {
                gameParameters.players.remove(self)
                gameParameters.players.add(0, self)
            }
            self.playerType = Human
            if (!gameParameters.isOnlineMultiplayer)
                gameParameters.players.drop(1).forEach { it.playerType = AI }
            if (gameParameters.isOnlineMultiplayer)
                self.playerId = UncivGame.Current.settings.multiplayer.getUserId()
        }

        reassignRemovedModReferences()

        fun isChoosable(nation: Nation) = (nation.isMajorCiv || nation.isSpectator) && !nation.hasUnique(UniqueType.WillNotBeChosenForNewGames)
        val newRulesetPlayableCivs = ruleset.nations.values.count(::isChoosable)

        if (gameParameters.players.size > newRulesetPlayableCivs)
            gameParameters.players.removeRange(newRulesetPlayableCivs, gameParameters.players.size)

        if (desiredCiv.isNotEmpty()) assignDesiredCiv(desiredCiv)

        if (directSetup) {
            buildDirectLayout()
            val isValid = gameParameters.players.any { it.playerType == Human } &&
                (gameParameters.randomNumberOfPlayers || gameParameters.players.size >= 2)
            (previousScreen as PickerScreen).setRightSideButtonEnabled(isValid)
            return
        }

        for (player in gameParameters.players) {
            playerListTable.add(getPlayerTable(player)).width(civBlocksWidth).padBottom(10f).row()
        }

        val isRandomNumberOfPlayers = gameParameters.randomNumberOfPlayers
        if (isRandomNumberOfPlayers) {
            randomNumberLabel = WrappableLabel("", civBlocksWidth - 20f, Color.GOLD)
            playerListTable.add(randomNumberLabel).fillX().pad(0f, 10f, 20f, 10f).row()
            updateRandomNumberLabel()
        }

        if (!locked && gameParameters.players.size < newRulesetPlayableCivs) {
            val addPlayerButton = "+".toLabel(ImageGetter.CHARCOAL, 30)
                .apply { this.setAlignment(Align.center) }
                .surroundWithCircle(50f)
                .onClick {
                    // no random mode - add first not spectator civ if still available
                    val player = if (noRandom || isRandomNumberOfPlayers) {
                        val availableCiv = getAvailablePlayerCivs().firstOrNull()
                        if (availableCiv != null) Player(availableCiv)
                        // Spectators can only be Humans
                        else Player(Constants.spectator, PlayerType.Human).apply { setNationTransient(ruleset) }
                    } else Player()  // normal: add random AI
                    gameParameters.players.add(player)
                    update()
                }
            playerListTable.add(addPlayerButton).pad(10f)
        }

        // enable start game when at least one human player and they're not alone
        val humanPlayerCount = gameParameters.players.count { it.playerType == PlayerType.Human }
        val isValid = humanPlayerCount >= 1 && (isRandomNumberOfPlayers || gameParameters.players.size >= 2)
        (previousScreen as? PickerScreen)?.setRightSideButtonEnabled(isValid)
    }

    fun updateRandomNumberLabel() {
        randomNumberLabel?.run {
            val playerRange = if (gameParameters.minNumberOfPlayers == gameParameters.maxNumberOfPlayers) {
                gameParameters.minNumberOfPlayers.tr()
            } else {
                "${gameParameters.minNumberOfPlayers} - ${gameParameters.maxNumberOfPlayers}"
            }
            val numberOfExplicitPlayersText = if (gameParameters.players.size == 1) {
                "The number of players will be adjusted"
            } else {
                "These [${gameParameters.players.size}] players will be adjusted"
            }
            val text = "[$numberOfExplicitPlayersText] to [$playerRange] actual players by adding random AI's or by randomly omitting AI's."
            wrap = false
            align(Align.center)
            setText(text.tr())
            wrap = true
        }
    }

    internal fun createCivilizationSelector(player: Player = gameParameters.players.first()): IconTextButton {
        fun portrait() = previousScreen.ruleset.nations[player.chosenCiv]?.let { ImageGetter.getNationPortrait(it, 32f) }
            ?: ImageGetter.getRandomNationPortrait(32f)
        return object : IconTextButton(player.chosenCiv, portrait(), fontSize = 22) {
            private var displayedCiv = player.chosenCiv

            override fun act(delta: Float) {
                super.act(delta)
                if (displayedCiv == player.chosenCiv) return
                displayedCiv = player.chosenCiv
                label.setText(displayedCiv.tr(hideIcons = true))
                iconCell.setActor(portrait())
            }
        }.apply {
            isDisabled = locked
            pad(6f, 10f, 6f, 10f)
            iconCell.size(32f).padRight(10f)
            label.wrap = true
            labelCell.minWidth(0f).growX().left().padTop(0f)
            add("›".toLabel(Color.LIGHT_GRAY, 22)).padLeft(8f)
            onClick {
                popupNationPicker(player, noRandom || gameParameters.randomNumberOfPlayers && player.playerType == AI)
            }
        }
    }

    internal fun createOpponentCounter() = SinglePlayerSetup.counter(
        gameParameters.players.count { it.playerType == AI },
        (previousScreen.ruleset.nations.values.count { it.isMajorCiv &&
            !it.hasUnique(UniqueType.WillNotBeChosenForNewGames) } - 1).coerceAtLeast(0),
        !locked && !gameParameters.randomNumberOfPlayers
    ) { count ->
        while (gameParameters.players.size > count + 1) gameParameters.players.removeAt(gameParameters.players.lastIndex)
        while (gameParameters.players.size < count + 1) gameParameters.players.add(Player())
        update()
    }

    private fun buildDirectLayout() {
        val width = (previousScreen as NewGameScreen).directSetupWidth
        val columns = if (width >= 600f) 2 else 1
        val panelWidth = (width - (columns - 1) * 24f) / columns
        val self = gameParameters.players.first()
        val civilization = Table().apply {
            top()
            add(SinglePlayerSetup.field("Selected civilization", createCivilizationSelector(), panelWidth)).growX().padBottom(16f).row()
            val choices = (listOf(Constants.random, self.chosenCiv) + getAvailablePlayerCivs(self.chosenCiv).map { it.name }.toList())
                .distinct().take(6)
            add(Table().apply {
                for ((index, civ) in choices.withIndex()) {
                    add(GameSetupOverview.choice(civ, self.chosenCiv == civ).apply {
                        isDisabled = locked || noRandom && civ == Constants.random
                        onClick { popupNationPicker(self, noRandom, civ) }
                    }).width((panelWidth - 16f) / 3f).minHeight(44f).padRight(if (index % 3 == 2) 0f else 8f).padBottom(8f)
                    if (index % 3 == 2) row()
                }
            }).growX().row()
            add("Civilizations".toTextButton().apply {
                isDisabled = locked
                onClick { popupNationPicker(self, noRandom) }
            }).left().minHeight(44f).padTop(4f)
        }
        val opponents = Table().apply {
            top()
            add(SinglePlayerSetup.field("AI opponents", createOpponentCounter(), panelWidth)).growX().row()
            if (gameParameters.randomNumberOfPlayers) {
                randomNumberLabel = WrappableLabel("", panelWidth, Color.LIGHT_GRAY)
                add(randomNumberLabel).growX().padTop(8f).row()
                updateRandomNumberLabel()
            }
            add(SinglePlayerSetup.group("Choose opponent civilizations", panelWidth, "Civilizations.Opponents") { table ->
                for (player in gameParameters.players.drop(1))
                    table.add(SinglePlayerSetup.field("AI", createCivilizationSelector(player), panelWidth - 24f))
                        .width(panelWidth - 24f).padBottom(8f).row()
                SinglePlayerSetup.style(table, previousScreen.formFont)
            }).width(panelWidth).padTop(12f).row()
            add("Shuffle Civ Order at Start".toCheckBox(gameParameters.shufflePlayerOrder) {
                gameParameters.shufflePlayerOrder = it
            }.apply { isDisabled = locked; label.wrap = true }).growX().left().minHeight(44f).padTop(12f)
        }
        playerListTable.add(civilization).width(panelWidth).top().padRight(if (columns == 2) 24f else 0f)
        if (columns == 1) playerListTable.row()
        playerListTable.add(opponents).width(panelWidth).top().padTop(if (columns == 1) 20f else 0f)
        SinglePlayerSetup.style(playerListTable, previousScreen.formFont)
    }

    /**
     * Reassigns removed mod references to random civilization
     */
    private fun reassignRemovedModReferences() {
        for (player in gameParameters.players) {
            if (!previousScreen.ruleset.nations.containsKey(player.chosenCiv)
                || previousScreen.ruleset.nations[player.chosenCiv]!!.isCityState
                || previousScreen.ruleset.nations[player.chosenCiv]!!.hasUnique(UniqueType.WillNotBeChosenForNewGames))
                player.chosenCiv = Constants.random
        }
    }

    /**
     * Assigns desired civilization for human players with 'random' choice
     * @param desiredCiv string containing desired civilization name
     */
    private fun assignDesiredCiv(desiredCiv: String) {
        // No auto-select if desiredCiv already used
        if (gameParameters.players.any { it.chosenCiv == desiredCiv }) return
        // Do auto-select, silently no-op if no suitable slot (human with 'random' choice)
        val player = gameParameters.players.firstOrNull { it.chosenCiv == Constants.random && it.playerType == Human }
        player?.chosenCiv = desiredCiv
        player?.setNationTransient(previousScreen.ruleset)
    }

    /**
     * Creates [Table] for single player containing clickable
     * player type button ("AI" or "Human"), nation [Table]
     * and "-" remove player button.*
     * @param player for which [Table] is generated
     * @return [Table] containing the all the elements
     */
    private fun getPlayerTable(player: Player): Table {
        val playerTable = Table()
        playerTable.pad(5f)
        playerTable.background = BaseScreen.skinStrings.getUiBackground(
            "NewGameScreen/PlayerPickerTable/PlayerTable",
            tintColor = BaseScreen.skinStrings.skinConfig.baseColor.darken(0.8f)
        )

        val roomMode = gameParameters.isOnlineMultiplayer && UncivGame.Current.platformCapabilities.multiplayerInviteLinks &&
            (previousScreen as? NewGameScreen)?.useInviteRoom == true
        val singlePlayer = previousScreen is NewGameScreen && !gameParameters.isOnlineMultiplayer
        val self = previousScreen is NewGameScreen && gameParameters.players.firstOrNull() === player
        val host = gameParameters.players.firstOrNull { it.playerType == Human } === player
        val invitedHuman = roomMode && player.playerType == Human && !host
        if (roomMode || self && !singlePlayer) {
            val seatLabel = when {
                self && !roomMode -> "You"
                player.playerType != Human -> "AI"
                host -> "Host · You"
                else -> "Waiting for player"
            }
            playerTable.add(seatLabel.toLabel(if (host) Color.GOLD else Color.LIGHT_GRAY,18)).colspan(3).left().padBottom(6f).row()
        }
        val nationTable = if (invitedHuman) Table().apply {
            add(ImageGetter.getRandomNationPortrait(40f)).size(40f).pad(5f)
            add("Invite after creating".toLabel(fontSize = 18).apply { setWrap(true) }).growX()
        } else getNationTable(player)
        if (singlePlayer) nationTable.left()
        playerTable.add(nationTable).left().growX()

        val playerTypeTextButton = player.playerType.name.toTextButton()
        val canChoosePlayerType = !self && (previousScreen !is NewGameScreen || gameParameters.isOnlineMultiplayer)
        if (canChoosePlayerType)
            playerTable.add(playerTypeTextButton).width(100f).pad(5f).right()
        else if (self && singlePlayer)
            playerTable.add("You".toLabel(Color.GOLD)).width(100f).pad(5f).right()
        else if (!self)
            playerTable.add("AI".toLabel()).width(100f).pad(5f).right()
        fun updatePlayerTypeButtonEnabled() {
            // This could be written much shorter with logical operators - I think this is readable
            playerTypeTextButton.isEnabled = when {
                // Can always change AI to Human
                player.playerType == PlayerType.AI -> true
                // we cannot change Spectator player to AI type, robots not allowed to spectate :(
                player.chosenCiv == Constants.spectator -> false
                // In randomNumberOfPlayers mode, don't let the user choose random AI's
                gameParameters.randomNumberOfPlayers && player.chosenCiv == Constants.random -> false
                else -> true
            }
        }
        updatePlayerTypeButtonEnabled()

        nationTable.onClick {
            if (locked || invitedHuman) return@onClick
            val noRandom = noRandom ||
                    gameParameters.randomNumberOfPlayers && player.playerType == PlayerType.AI
            popupNationPicker(player, noRandom)
            updatePlayerTypeButtonEnabled()
        }
        if (canChoosePlayerType) {
            playerTypeTextButton.onClick {
                player.playerType = if (player.playerType == AI) Human else AI
                update()
            }
        }

        if (!locked && !self) {
            playerTable.add("-".toLabel(ImageGetter.CHARCOAL, 30, Align.center)
                .surroundWithCircle(40f)
                .onClick {
                    gameParameters.players.remove(player)
                    update()
                }
            ).pad(5f).right()
        } else if (!locked && self && singlePlayer)
            playerTable.add().size(40f).pad(5f)

        if (gameParameters.isOnlineMultiplayer && player.playerType == PlayerType.Human && !self &&
            !(UncivGame.Current.platformCapabilities.multiplayerInviteLinks && (previousScreen as? NewGameScreen)?.useInviteRoom == true))
            playerTable.addPlayerTableMultiplayerControls(player)

        return playerTable
    }

    private fun Table.addPlayerTableMultiplayerControls(player: Player) {
        row()

        val playerIdTextField = UncivTextField("Please input Player ID!", player.playerId)
        add(playerIdTextField).colspan(2).fillX().pad(5f)
        val errorLabel = "✘".toLabel(Color.RED)
        add(errorLabel).pad(5f).row()

        fun onPlayerIdTextUpdated() {
            val friend = IdChecker.checkAndReturnPlayerUuid(playerIdTextField.text)
            if (friend?.playerID?.isUUID() ?: false) {
                player.playerId = playerIdTextField.text.trim()
                errorLabel.apply {
                    setText("✔") // U+2714 heavy checkmark
                    setFontColor(ExpeditionBlueTheme.color(Color.GREEN, ExpeditionBlueTheme.success))
                }
            } else {
                errorLabel.apply {
                    setText("✘") // U+2718 heavy ballot x
                    setFontColor(Color.RED)
                }
            }
        }
        onPlayerIdTextUpdated()
        playerIdTextField.addListener { onPlayerIdTextUpdated(); true }

        val currentUserId = UncivGame.Current.settings.multiplayer.getUserId()
        val setCurrentUserButton = "Set current user".toTextButton()
        setCurrentUserButton.onClick {
            playerIdTextField.text = currentUserId
            onPlayerIdTextUpdated()
        }
        add(setCurrentUserButton).colspan(3).fillX().pad(5f).row()

        val copyFromClipboardButton = "Player ID from clipboard".toTextButton()
        copyFromClipboardButton.onClick {
            playerIdTextField.text = Gdx.app.clipboard.contents
            onPlayerIdTextUpdated()
        }
        add(copyFromClipboardButton).right().colspan(3).fillX().pad(5f).row()

        //check if friends list is empty before adding the select friend button
        if (friendList.listOfFriends.isNotEmpty()) {
            val selectPlayerFromFriendsList = "Player ID from friends list".toTextButton()
            selectPlayerFromFriendsList.onClick {
                popupFriendPicker(player)
            }
            add(selectPlayerFromFriendsList).left().colspan(3).fillX().pad(5f)
        }
    }

    /**
     * Creates clickable icon and nation name for some [Player].
     * @param player [Player] for which generated
     * @return [Table] containing nation icon and name
     */
    private fun getNationTable(player: Player): Table {
        val nationTable = Table()
        val nationImageName = previousScreen.ruleset.nations[player.chosenCiv]
        val nationImage =
            if (nationImageName == null)
                ImageGetter.getRandomNationPortrait(40f)
            else ImageGetter.getNationPortrait(nationImageName, 40f)
        nationTable.add(nationImage).pad(5f)
        nationTable.add(player.chosenCiv.toLabel(hideIcons = true)).pad(5f)
        nationTable.touchable = Touchable.enabled
        return nationTable
    }

    /**
     * Opens Friend picking popup with all friends,
     * currently available for [player] to choose, depending on current
     * friends list and if another friend is selected.
     * @param player current player
     */
    private fun popupFriendPicker(player: Player) {
        FriendSelectionPopup(this, player, previousScreen as BaseScreen).open()
        update()
    }

    /**
     * Opens Nation picking popup with all nations,
     * currently available for [player] to choose, depending on current
     * ruleset and other players nation choice.
     * @param player current player
     */
    private fun popupNationPicker(player: Player, noRandom: Boolean, initialCiv: String = player.chosenCiv) {
        NationPickerPopup(this, player, noRandom, initialCiv = initialCiv).open()
        update()
    }

    /**
     * Returns a list of available civilization for all players, according
     * to current ruleset, with exception of city states nations, spectator and barbarians.
     *
     * Skips nations already chosen by a player, unless parameter [dontSkipNation] says to keep a
     * specific one. That is used so the picker can be used to inspect and confirm the current selection.
     *
     * @return [Sequence] of available [Nation]s
     */
    internal fun getAvailablePlayerCivs(dontSkipNation: String? = null) =
        previousScreen.ruleset.nations.values.asSequence()
            .filter { it.isMajorCiv }
            .filterNot { it.hasUnique(UniqueType.WillNotBeChosenForNewGames) }
            .filter { it.name == dontSkipNation || gameParameters.players.none { player -> player.chosenCiv == it.name } }

    /**
     * Returns a list of available friends.
     * Skips friends already chosen.
     *
     * @return [Sequence] of available [FriendList.Friend]s
     */
    internal fun getAvailableFriends(): Sequence<FriendList.Friend> {
        val friendListWithRemovedFriends = friendList.listOfFriends.toMutableList()
        for (index in gameParameters.players.indices) {
            val currentFriendId = previousScreen.gameSetupInfo.gameParameters.players[index].playerId
            friendListWithRemovedFriends.remove(friendList.getFriendById(currentFriendId))
        }
        return friendListWithRemovedFriends.asSequence()
    }
}

class FriendSelectionPopup(
    private val playerPicker: PlayerPickerTable,
    player: Player,
    screen: BaseScreen,
) : Popup(screen) {

    private val pickerPane = PickerPane()
    private var selectedFriendId: String? = null

    init {
        val pickerCell = add()
            .width(700f).fillX().expandX()
            .minHeight(screen.stage.height * 0.5f)
            .maxHeight(screen.stage.height * 0.8f)

        val friendList = FriendPickerList(playerPicker, ::friendSelected)
        pickerPane.topTable.add(friendList)
        pickerPane.rightSideButton.setText("Select friend".tr())
        if (UiNavigation.isIos) {
            pickerPane.closeButton.remove()
            addCloseButton()
            clickBehindToClose = false
        } else {
            pickerPane.closeButton.onActivation(::close)
        pickerPane.closeButton.keyShortcuts.add(KeyCharAndCode.BACK)
        }
        pickerCell.setActor(pickerPane)
        pickerPane.rightSideButton.onClick {
            close()
            val friendId = selectedFriendId
            if (friendId != null) {
                player.playerId = selectedFriendId.toString()
                close()
                playerPicker.update()
            }
        }

        clickBehindToClose = true
    }

    private fun friendSelected(friendName: String) {
        val friendsList = FriendList()
        val friend = friendsList.getFriendByName(friendName)
        if (friend != null) {
            selectedFriendId = friend.playerID
        }
        pickerPane.setRightSideButtonEnabled(true)
        pickerPane.rightSideButton.setText("Select [$friendName]".tr())
    }

}
