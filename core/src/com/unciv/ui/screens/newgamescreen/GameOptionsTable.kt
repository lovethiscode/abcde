package com.unciv.ui.screens.newgamescreen

import com.unciv.ui.components.fonts.UiTypography
import com.unciv.ui.components.fonts.TextRole
import com.unciv.ui.components.widgets.UiNavigation
import com.badlogic.gdx.graphics.Color
import com.unciv.models.skins.ExpeditionBlueTheme
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.ui.CheckBox
import com.badlogic.gdx.scenes.scene2d.ui.SelectBox
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.utils.Align
import com.unciv.Constants
import com.unciv.UncivGame
import com.unciv.logic.civilization.PlayerType
import com.unciv.models.metadata.GameParameters
import com.unciv.models.metadata.Player
import com.unciv.models.ruleset.Ruleset
import com.unciv.models.ruleset.RulesetCache
import com.unciv.models.ruleset.nation.Nation
import com.unciv.models.ruleset.unique.GameContext
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.translations.tr
import com.unciv.ui.audio.MusicMood
import com.unciv.ui.audio.MusicTrackChooserFlags
import com.unciv.ui.components.InputDisabling
import com.unciv.ui.components.extensions.getCloseButton
import com.unciv.ui.components.extensions.pad
import com.unciv.ui.components.extensions.toCheckBox
import com.unciv.ui.components.extensions.toImageButton
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.input.onChange
import com.unciv.ui.components.input.onClick
import com.unciv.ui.components.widgets.AutoScrollPane
import com.unciv.ui.components.widgets.ExpanderTab
import com.unciv.ui.components.widgets.TranslatedSelectBox
import com.unciv.ui.components.widgets.UncivSlider
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.popups.Popup
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.multiplayerscreens.MultiplayerHelpers
import com.unciv.utils.ONLINE_MULTIPLAYER_UNAVAILABLE
import kotlin.reflect.KMutableProperty0
import kotlin.reflect.KMutableProperty1

class GameOptionsTable(
    private val previousScreen: IPreviousScreen,
    private val isPortrait: Boolean = false,
    private val updatePlayerPickerTable: (desiredCiv: String) -> Unit,
    private val updatePlayerPickerRandomLabel: () -> Unit,
    private val allowOnlineToggle: Boolean = true,
    private val forInviteRoom: Boolean = false,
    private val onConfigurationChanged: (() -> Unit)? = null,
    private val roomLayoutWidth: Float? = null,
    private val directLayoutWidth: Float? = null,
    private val directLayoutFont: com.badlogic.gdx.graphics.g2d.BitmapFont? = null
) : Table(BaseScreen.skin) {
    private var gameParameters = previousScreen.gameSetupInfo.gameParameters
    private var ruleset = previousScreen.ruleset
    internal var locked = false

    private var baseRulesetHash = gameParameters.baseRuleset.hashCode()

    /** Holds the UI for the Extension Mods
     *
     *  Attention: This Widget is a little tricky due to the UI changes to support portrait mode:
     *  *  With `isPortrait==false`, this Table will **contain** `modCheckboxes`
     *  *  With `isPortrait==true`, this Table will **only initialize** `modCheckboxes` and [NewGameScreen] will fetch and place it.
     *
     *  The second reason this is public: [NewGameScreen] accesses [ModCheckboxTable.savedModcheckResult] for display.
     */
    internal val modCheckboxes = getModCheckboxes(isPortrait = isPortrait)

    // Remember this so we can unselect it when the pool dialog returns an empty pool
    private var randomNationsPoolCheckbox: CheckBox? = null
    // Allow resetting base ruleset from outside
    private var baseRulesetSelectBox: TranslatedSelectBox? = null

    init {
        background = BaseScreen.skinStrings.getUiBackground("NewGameScreen/GameOptionsTable", tintColor = BaseScreen.skinStrings.skinConfig.clearColor)
        top()
        defaults().pad(5f)
        update()
    }

    fun update() {
        clearChildren()

        // Mods may have changed (e.g. custom map selection)
        modCheckboxes.updateSelection()
        val newBaseRulesetHash = gameParameters.baseRuleset.hashCode()
        if (newBaseRulesetHash != baseRulesetHash) {
            baseRulesetHash = newBaseRulesetHash
            modCheckboxes.setBaseRuleset(gameParameters.baseRuleset)
        }

        if (directLayoutWidth != null) {
            buildDirectLayout(directLayoutWidth)
            pack()
            return
        }
        if (roomLayoutWidth != null) {
            buildRoomLayout(roomLayoutWidth)
            pack()
            return
        }

        add(Table().apply {
            defaults().pad(5f)
            addBaseRulesetSelectBox()
            addDifficultySelectBox()
            addGameSpeedSelectBox()
            addEraSelectBox()
            // align left and right edges with other SelectBoxes but allow independent dropdown width
            add(Table().apply {
                val turnSlider = addMaxTurnsSlider()
                if (turnSlider != null)
                    add(turnSlider).padTop(10f).row()
                if (gameParameters.randomNumberOfPlayers) {
                    addMinMaxPlayersSliders()
                }
                if (gameParameters.randomNumberOfCityStates) {
                    addMinMaxCityStatesSliders()
                } else {
                    addCityStatesSlider()
                }
            }).colspan(2).fillX().row()
        }).row()
        addVictoryTypeCheckboxes()

        val checkboxTable = Table().apply { defaults().left().pad(2.5f) }
        val selectBoxTable = Table()
        if (allowOnlineToggle) checkboxTable.addIsOnlineMultiplayerCheckbox()
        if (gameParameters.isOnlineMultiplayer){
            checkboxTable.addAnyoneCanSpectateCheckbox()
            selectBoxTable.addDurationSelectBox("Time until skip turn:", GameParameters::minutesUntilSkipTurn, 1, 0, 0)
            selectBoxTable.addDurationSelectBox("Total time to play:", GameParameters::minutesUntilForceResign, 3, 0, 0)
            selectBoxTable.addDurationSelectBox("Time recovered per turn:", GameParameters::minutesRecoveredPerTurn, 3, 0, 0)
        }
        add(checkboxTable).center().row()
        add(selectBoxTable).center().row()

        val expander = advancedSettings()
        add(expander).pad(10f).row()

        if (!isPortrait && !forInviteRoom)
            add(modCheckboxes).padTop(0f).row()

        pack()
    }

    private fun advancedSettings() = ExpanderTab(
            "Advanced Settings",
            icon = ImageGetter.getImage("OtherIcons/Settings").apply { setSize(20f, 20f) },
            startsOutOpened = gameParameters.enableRandomNationsPool,
            persistenceID = "GameOptionsTable.Advanced"
        ) {
            addAdvancedSettings(it)
        }

    internal fun createOverviewFields(): List<Pair<String, Actor>> = listOf(
        "Difficulty" to Table().addDifficultySelectBox(), "Game Speed" to Table().addGameSpeedSelectBox()
    )

    internal fun createBaseRulesetSelector(): Actor? {
        Table().addBaseRulesetSelectBox()
        return baseRulesetSelectBox
    }

    private fun buildDirectLayout(width: Float) {
        val font = directLayoutFont ?: (previousScreen as NewGameScreen).formFont
        background = null
        defaults().pad(0f)
        val fields = createOverviewFields().toMutableList()
        Table().addEraSelectBox()?.let { fields.add("Starting Era" to it) }
        if (!gameParameters.randomNumberOfCityStates && numberOfCityStates() > 0)
            fields.add("City-States" to TranslatedSelectBox((0..numberOfCityStates()).map { it.toString() },
                gameParameters.numberOfCityStates.toString()).apply {
                isDisabled = locked
                onChange { gameParameters.numberOfCityStates = selected.value.toInt() }
            })
        add(SinglePlayerSetup.form(width, fields)).growX().row()
        if (gameParameters.randomNumberOfCityStates)
            add(Table().apply { defaults().growX(); addMinMaxCityStatesSliders() }).growX().row()
        add(Table().apply {
            add(ImageGetter.getImage("OtherIcons/AchievementTrophy", ExpeditionBlueTheme.gold)).size(22f).padRight(8f)
            add("Victory conditions".toLabel(ExpeditionBlueTheme.gold, UiTypography.size(TextRole.SectionTitle, legacySize = 20)))
        }).left().padTop(4f).padBottom(4f).row()
        addVictoryTypeCheckboxes()
        fun group(title: String, id: String, opened: Boolean = false, build: Table.() -> Unit) {
            add(SinglePlayerSetup.group(title, width, "Rules.$id", opened) { table ->
                table.defaults().left().growX().minHeight(44f).padBottom(8f)
                table.build()
                SinglePlayerSetup.style(table, font)
            }).width(width).padTop(8f).row()
        }
        group("Advanced Settings", "Advanced", gameParameters.enableRandomNationsPool) {
            if (forInviteRoom) createBaseRulesetSelector()?.let {
                add(SinglePlayerSetup.field("Base Ruleset",it,width - 24f)).colspan(2).growX().row()
            }
            for (victory in ruleset.selectableVictories().filter { it.enablesMaxTurns() })
                add(victory.name.toCheckBox(victory.name in gameParameters.victoryTypes) {
                    if (it) gameParameters.victoryTypes.add(victory.name) else gameParameters.victoryTypes.remove(victory.name)
                    update()
                }.apply { name = victory.name; isDisabled = locked; align(Align.left) }).colspan(2).growX().row()
            addMaxTurnsSlider()?.let { add(it).colspan(2).minWidth(0f).growX().row() }
            addAdvancedSettings(this)
            if (gameParameters.randomNumberOfPlayers) addMinMaxPlayersSliders()
            if (forInviteRoom) add(Table().apply {
                defaults().left().growX().minHeight(44f).padBottom(8f)
                addAnyoneCanSpectateCheckbox()
                addDurationSelectBox("Time until skip turn:", GameParameters::minutesUntilSkipTurn, 1, 0, 0)
                addDurationSelectBox("Total time to play:", GameParameters::minutesUntilForceResign, 3, 0, 0)
                addDurationSelectBox("Time recovered per turn:", GameParameters::minutesRecoveredPerTurn, 3, 0, 0)
            }).colspan(2).minWidth(0f).growX().row()
        }
        if (forInviteRoom || modCheckboxes.children.size == 0)
            group("Extension mods", "Mods") {
                add((if (forInviteRoom) "Rooms require generated maps without mods" else "No mods installed")
                    .toLabel(TextRole.Body).apply { wrap = true }).growX().row()
            }
        else add(modCheckboxes).width(width).padTop(8f).row()
        SinglePlayerSetup.style(this, font)
    }

    private fun addAdvancedSettings(it: Table) {
        it.defaults().pad(5f, 0f)
        it.addNoCityRazingCheckbox()
        it.addNoBarbariansCheckbox()
        it.addRagingBarbariansCheckbox()
        it.addOneCityChallengeCheckbox()
        it.addNuclearWeaponsCheckbox()
        it.addEnableEspionageCheckbox()
        it.addNoStartBiasCheckbox()
        if (!forInviteRoom) it.addRandomPlayersCheckbox()
        it.addRandomCityStatesCheckbox()
        it.addRandomNationsPoolCheckbox()
        if (gameParameters.enableRandomNationsPool) {
            it.addNationsSelectTextButton()
        }
        it.addShowCivilizationStatsCheckbox()
        if (gameParameters.showCivilizationStats == true) {
            val statsTable = Table().apply {
                defaults().growX().left().padLeft(30f).padBottom(10f)
                addShowRankingsCheckbox()
                addShowChartsCheckbox()
                addShowDemographicsCheckbox()
                addCensorStatsCheckbox()
                addShowAdditionalRankingTypesCheckbox()
            }
            if (directLayoutWidth != null) it.add(statsTable).colspan(2).minWidth(0f).growX().left().row()
            else it.add(statsTable).left()
        }
    }

    private fun buildRoomLayout(width: Float) {
        background = null
        defaults().pad(0f)
        val stacked = width < 700f
        val leftWidth = if (stacked) width else (width - 18f) * 0.55f
        val rightWidth = if (stacked) width else width - leftWidth - 18f
        val pace = GameSetupOverview.card().apply {
            val innerWidth = leftWidth - 36f
            defaults().width(innerWidth).left().padBottom(12f)
            add("Game pace".toLabel(fontSize = UiTypography.size(TextRole.PageTitle, legacySize = 24))).row()
            add("Game Speed".toLabel(Color.LIGHT_GRAY, UiTypography.size(TextRole.Control, legacySize = 16))).padBottom(8f).row()
            add(Table().apply {
                val speeds = ruleset.speeds.keys.toList()
                val columns = minOf(4, speeds.size)
                for ((index, speed) in speeds.withIndex()) {
                    add(GameSetupOverview.choice(speed, gameParameters.speed == speed).apply {
                        onClick {
                            gameParameters.speed = speed
                            update()
                            onConfigurationChanged?.invoke()
                        }
                    }).width((innerWidth - (columns - 1) * 8f) / columns).minHeight(44f)
                        .padRight(if (index % columns == columns - 1) 0f else 8f).padBottom(8f)
                    if (index % columns == columns - 1) row()
                }
            }).row()
            add(Table().apply {
                val fieldWidth = (innerWidth - 14f) / 2f
                add(Table().apply { addDifficultySelectBox() }).width(fieldWidth).padRight(14f).top()
                add(Table().apply { addEraSelectBox() }).width(fieldWidth).top()
            }).row()
            add(Table().apply {
                defaults().growX().padTop(8f)
                if (gameParameters.randomNumberOfCityStates) addMinMaxCityStatesSliders()
                else addCityStatesSlider()
            }).row()
        }
        val victories = GameSetupOverview.card().apply {
            val innerWidth = rightWidth - 36f
            defaults().width(innerWidth).left().padBottom(6f)
            add("Victory Conditions".toLabel(fontSize = UiTypography.size(TextRole.PageTitle, legacySize = 24))).padBottom(20f).row()
            for (victory in ruleset.selectableVictories()) {
                add(victory.name.toCheckBox(victory.name in gameParameters.victoryTypes) { enabled ->
                    if (enabled) gameParameters.victoryTypes.add(victory.name)
                    else gameParameters.victoryTypes.remove(victory.name)
                    if (victory.enablesMaxTurns()) update()
                    onConfigurationChanged?.invoke()
                }.apply { name = victory.name; align(Align.left) }).minHeight(44f).row()
            }
            add(Table().apply {
                defaults().growX()
                val turnSlider = addMaxTurnsSlider()
                if (turnSlider != null) {
                    row()
                    add(turnSlider).padTop(8f).row()
                }
            }).row()
        }
        add(pace).width(leftWidth).top().padRight(if (stacked) 0f else 18f)
        if (stacked) row()
        add(victories).width(rightWidth).top().padTop(if (stacked) 18f else 0f).row()
        add(ExpanderTab("Advanced Settings", startsOutOpened = false, expanderWidth = width,
            persistenceID = "MultiplayerRoom.RulesAdvanced") { table ->
            table.defaults().left().growX().padBottom(14f)
            table.add(Table().apply { addBaseRulesetSelectBox() }).row()
            addAdvancedSettings(table)
            table.row()
            table.add(Table().apply {
                defaults().left().padBottom(12f)
                addAnyoneCanSpectateCheckbox()
                addDurationSelectBox("Time until skip turn:", GameParameters::minutesUntilSkipTurn, 1, 0, 0)
                addDurationSelectBox("Total time to play:", GameParameters::minutesUntilForceResign, 3, 0, 0)
                addDurationSelectBox("Time recovered per turn:", GameParameters::minutesRecoveredPerTurn, 3, 0, 0)
            }).row()
        }).colspan(if (stacked) 1 else 2).width(width).padTop(18f)
    }

    private fun Table.addCheckbox(
        text: String,
        initialState: Boolean,
        lockable: Boolean = true,
        onChange: (newValue: Boolean) -> Unit
    ): CheckBox {
        val checkbox = text.toCheckBox(initialState) { onChange(it) }
        checkbox.isDisabled = lockable && locked
        checkbox.align(Align.left)
        add(checkbox).colspan(2).row()
        return checkbox
    }

    private fun Table.addNoCityRazingCheckbox() =
            addCheckbox("No City Razing", gameParameters.noCityRazing)
            { gameParameters.noCityRazing = it }

    private fun Table.addNoBarbariansCheckbox() =
            addCheckbox("No Barbarians", gameParameters.noBarbarians)
            { gameParameters.noBarbarians = it }

    private fun Table.addRagingBarbariansCheckbox() =
            addCheckbox("Raging Barbarians", gameParameters.ragingBarbarians)
            { gameParameters.ragingBarbarians = it }

    private fun Table.addOneCityChallengeCheckbox() =
            addCheckbox("One City Challenge", gameParameters.oneCityChallenge)
            { gameParameters.oneCityChallenge = it }

    private fun Table.addNuclearWeaponsCheckbox() =
            addCheckbox("Enable Nuclear Weapons", gameParameters.nuclearWeaponsEnabled)
            { gameParameters.nuclearWeaponsEnabled = it }

    private fun Table.addIsOnlineMultiplayerCheckbox() {
        if (!UncivGame.Current.platformCapabilities.onlineMultiplayer) {
            gameParameters.isOnlineMultiplayer = false
            addCheckbox("Online Multiplayer", false, lockable = false) {}.isDisabled = true
            add(ONLINE_MULTIPLAYER_UNAVAILABLE.toLabel(fontColor = Color.GRAY, role = TextRole.Body)).colspan(2).row()
            return
        }

        addCheckbox("Online Multiplayer", gameParameters.isOnlineMultiplayer, lockable = false)
            { shouldUseMultiplayer ->
                gameParameters.isOnlineMultiplayer = shouldUseMultiplayer
                updatePlayerPickerTable("")
                if (shouldUseMultiplayer) {
                    MultiplayerHelpers.showMultiplayerServerWarning(previousScreen as BaseScreen)
                }
                update()
            }
    }

    private fun Table.addAnyoneCanSpectateCheckbox() =
            addCheckbox("Allow anyone to spectate", gameParameters.anyoneCanSpectate)
            {
                gameParameters.anyoneCanSpectate = it
            }

    private fun Table.addEnableEspionageCheckbox() =
        addCheckbox("Enable Espionage", gameParameters.espionageEnabled)
        { gameParameters.espionageEnabled = it }

    private fun Table.addRandomNationsPoolCheckbox() {
        randomNationsPoolCheckbox = addCheckbox(
            "Set available nations for random pool",
            gameParameters.enableRandomNationsPool
        ) {
            gameParameters.enableRandomNationsPool = it
            update()  // To show the button opening the chooser popup
        }
    }

    private fun Table.addShowCivilizationStatsCheckbox() =
        addCheckbox("Show Civilization Stats", gameParameters.showCivilizationStats == true)
        {
            gameParameters.showCivilizationStats = it
            update()  // To update checkboxTable
        }

    private fun Table.addShowDemographicsCheckbox() =
        addCheckbox("Show Demographics", gameParameters.showDemographics)
        { gameParameters.showDemographics = it }

    private fun Table.addShowRankingsCheckbox() =
        addCheckbox("Show Rankings", gameParameters.showRankings)
        { gameParameters.showRankings = it }

    private fun Table.addShowChartsCheckbox() =
        addCheckbox("Show Charts", gameParameters.showCharts)
        { gameParameters.showCharts = it }

    private fun Table.addCensorStatsCheckbox() =
        addCheckbox("Restrict to own civilization", gameParameters.hideOtherCivilizationStats)
        { gameParameters.hideOtherCivilizationStats = it }
    
    private fun Table.addShowAdditionalRankingTypesCheckbox() =
        addCheckbox("Show additional stat types", gameParameters.showAdditionalRankingTypes)
        { gameParameters.showAdditionalRankingTypes = it }

    private fun Table.addNationsSelectTextButton() {
        val button = "Select nations".toTextButton()
        button.onClick {
            val popup = RandomNationPickerPopup(previousScreen, gameParameters) {
                if (gameParameters.randomNationsPool.isEmpty()) {
                    gameParameters.enableRandomNationsPool = false
                    randomNationsPoolCheckbox?.isChecked = false
                }
                onConfigurationChanged?.invoke()
            }
            popup.open()
            popup.update()
        }
        add(button).colspan(if (directLayoutWidth != null) 2 else 1).row()
    }

    private fun numberOfMajorCivs() = ruleset.nations.values.count {
        it.isMajorCiv && !it.hasUnique(UniqueType.WillNotBeChosenForNewGames)
    }

    private fun numberOfCityStates() = ruleset.nations.values.count {
        it.isCityState && !it.hasUnique(UniqueType.WillNotBeChosenForNewGames)
    }

    private fun Table.addNoStartBiasCheckbox() =
            addCheckbox("Disable starting bias", gameParameters.noStartBias)
            { gameParameters.noStartBias = it }

    private fun Table.addRandomPlayersCheckbox() =
            addCheckbox("Random number of Civilizations", gameParameters.randomNumberOfPlayers)
            { newRandomNumberOfPlayers ->
                gameParameters.randomNumberOfPlayers = newRandomNumberOfPlayers
                if (newRandomNumberOfPlayers) {
                    // remove all random AI from player picker
                    gameParameters.players = gameParameters.players.asSequence()
                        .filterNot { it.playerType == PlayerType.AI && it.chosenCiv == Constants.random }
                        .toCollection(ArrayList(gameParameters.players.size))
                    updatePlayerPickerTable("")
                } else {
                    // Fill up player picker with random AI until previously active min reached
                    val additionalRandom = gameParameters.minNumberOfPlayers - gameParameters.players.size
                    if (additionalRandom > 0) {
                        repeat(additionalRandom) {
                            gameParameters.players.add(Player(Constants.random))
                        }
                        updatePlayerPickerTable("")
                    }
                }
                update()  // To see the new sliders
            }

    private fun Table.addRandomCityStatesCheckbox() =
            addCheckbox("Random number of City-States", gameParameters.randomNumberOfCityStates)
            {
                gameParameters.run {
                    randomNumberOfCityStates = it
                    if (it) {
                        if (numberOfCityStates > maxNumberOfCityStates)
                            maxNumberOfCityStates = numberOfCityStates
                        if (numberOfCityStates < minNumberOfCityStates)
                            minNumberOfCityStates = numberOfCityStates
                    } else {
                        if (numberOfCityStates > maxNumberOfCityStates)
                            numberOfCityStates = maxNumberOfCityStates
                        if (numberOfCityStates < minNumberOfCityStates)
                            numberOfCityStates = minNumberOfCityStates
                    }
                }
                update()  // To see the changed sliders
            }

    private fun Table.addLinkedMinMaxSliders(
        minValue: Int, maxValue: Int,
        minText: String, maxText: String,
        minField: KMutableProperty0<Int>,
        maxField: KMutableProperty0<Int>,
        onChangeCallback: (() -> Unit)? = null
    ) {
        if (maxValue < minValue) return

        lateinit var maxSlider: UncivSlider  // lateinit safe because the closure won't use it until the user operates a slider
        val minSlider = UncivSlider(minValue.toFloat(), maxValue.toFloat(), 1f, initial = minField.get().toFloat()) {
            val newMin = it.toInt()
            minField.set(newMin)
            if (newMin > maxSlider.value.toInt()) {
                maxSlider.value = it
                maxField.set(newMin)
            }
            onChangeCallback?.invoke()
        }
        minSlider.isDisabled = locked
        maxSlider = UncivSlider(minValue.toFloat(), maxValue.toFloat(), 1f, initial = maxField.get().toFloat()) {
            val newMax = it.toInt()
            maxField.set(newMax)
            if (newMax < minSlider.value.toInt()) {
                minSlider.value = it
                minField.set(newMax)
            }
            onChangeCallback?.invoke()
        }
        maxSlider.isDisabled = locked

        if (directLayoutWidth != null) {
            add(minText.toLabel(TextRole.Body)).colspan(2).left().row()
            add(minSlider).colspan(2).minWidth(0f).growX().padTop(10f).row()
            add(maxText.toLabel(TextRole.Body)).colspan(2).left().row()
            add(maxSlider).colspan(2).minWidth(0f).growX().padTop(10f).row()
        } else {
            add(minText.toLabel(TextRole.Body)).left().expandX()
            add(minSlider).padTop(10f).row()
            add(maxText.toLabel(TextRole.Body)).left().expandX()
            add(maxSlider).padTop(10f).row()
        }
    }

    private fun Table.addMinMaxPlayersSliders() {
        addLinkedMinMaxSliders(2, numberOfMajorCivs(),
            "{Min number of Civilizations}:", "{Max number of Civilizations}:",
            gameParameters::minNumberOfPlayers, gameParameters::maxNumberOfPlayers,
            updatePlayerPickerRandomLabel
        )
    }

    private fun Table.addMinMaxCityStatesSliders() {
        addLinkedMinMaxSliders( 0, numberOfCityStates(),
            "{Min number of City-States}:", "{Max number of City-States}:",
            gameParameters::minNumberOfCityStates, gameParameters::maxNumberOfCityStates
        )
    }

    private fun Table.addCityStatesSlider() {
        val cityStatesAvailable = numberOfCityStates()
        if (cityStatesAvailable == 0) return

        add("{City-States}:".toLabel(TextRole.Body)).left().expandX()
        val slider = UncivSlider(0f, cityStatesAvailable.toFloat(), 1f, initial = gameParameters.numberOfCityStates.toFloat()) {
            gameParameters.numberOfCityStates = it.toInt()
        }
        slider.isDisabled = locked
        add(slider).padTop(10f).row()
    }

    private fun Table.addMaxTurnsSlider(): UncivSlider? {
        if (gameParameters.victoryTypes.none { ruleset.victories[it]?.enablesMaxTurns() == true })
            return null

        if (directLayoutWidth != null) add("{Max Turns}:".toLabel(TextRole.Body)).colspan(2).left().row()
        else add("{Max Turns}:".toLabel(TextRole.Body)).left().expandX()
        val slider = UncivSlider(100f, 1500f, 5f, initial = gameParameters.maxTurns.toFloat()) {
            gameParameters.maxTurns = it.toInt()
        }
        slider.isDisabled = locked
        val snapValues = floatArrayOf(100f,150f,200f,250f,300f,350f,400f,450f,500f,550f,600f,650f,700f,750f,800f,900f,1000f,1250f,1500f)
        slider.setSnapToValues(threshold = 125f, *snapValues, editLabel = "{Max Turns}:")
        return slider
    }

    private fun Table.addSelectBox(text: String, values: Collection<String>, initialState: String, onChange: (newValue: String) -> String?): TranslatedSelectBox {
        add(text.toLabel(if (roomLayoutWidth != null) Color.LIGHT_GRAY else Color.WHITE,
            UiTypography.size(TextRole.Control), hideIcons = true)).left()
        if (roomLayoutWidth != null) row()
        val selectBox = TranslatedSelectBox(values, initialState)
        selectBox.isDisabled = locked
        selectBox.onChange {
            val changedValue = onChange(selectBox.selected.value)
            if (changedValue != null) selectBox.setSelected(changedValue)
        }
        onChange(selectBox.selected.value)
        add(selectBox).minWidth(0f).growX().minHeight(if (roomLayoutWidth != null) 44f else 0f).row()
        return selectBox
    }

    private fun Table.addDifficultySelectBox() =
        addSelectBox("{Difficulty}:", ruleset.difficulties.keys, gameParameters.difficulty)
        { gameParameters.difficulty = it; null }

    private fun Table.addBaseRulesetSelectBox() {
        fun onBaseRulesetSelected(newBaseRuleset: String): String? {
            val previousSelection = gameParameters.baseRuleset
            if (newBaseRuleset == previousSelection) return null

            // Check if this mod is well-defined
            val baseRulesetErrors = RulesetCache[newBaseRuleset]!!.getErrorList()
            if (baseRulesetErrors.isError()) {
                baseRulesetErrors.showWarnOrErrorToast(previousScreen as BaseScreen)
                return previousSelection
            }

            // If so, add it to the current ruleset
            gameParameters.baseRuleset = newBaseRuleset
            modCheckboxes.setBaseRuleset(newBaseRuleset)  // Treats declared incompatibility
            onChooseMod(newBaseRuleset)

            // Check if the ruleset in its entirety is still well-defined
            val modLinkErrors = ruleset.getErrorList()
            if (modLinkErrors.isError()) {
                modCheckboxes.disableAllCheckboxes()  // also clears gameParameters.mods
                reloadRuleset()
            }
            modLinkErrors.showWarnOrErrorToast(previousScreen as BaseScreen)

            return null
        }

        val sortedBaseRulesets = RulesetCache.getSortedBaseRulesets().filter {
            !forInviteRoom || it in com.unciv.logic.multiplayer.lobby.protocol.LobbyProtocol.rulesets
        }
        if (sortedBaseRulesets.size < 2) return
        baseRulesetSelectBox = addSelectBox("{Base Ruleset}:", sortedBaseRulesets, gameParameters.baseRuleset)
            { InputDisabling.withInputDisabled { onBaseRulesetSelected(it) } }
    }

    private fun Table.addGameSpeedSelectBox() =
        addSelectBox("{Game Speed}:", ruleset.speeds.values.map { it.name }, gameParameters.speed)
        { gameParameters.speed = it; null }

    private fun Table.addEraSelectBox(): TranslatedSelectBox? {
        if (ruleset.eras.isEmpty()) return null // mod with no techs
        if (ruleset.modOptions.hasUnique(UniqueType.CanOnlyStartFromStartingEra)){
            gameParameters.startingEra = ruleset.eras.keys.first()
            return null
        }
        val eras = ruleset.eras.keys
        return addSelectBox("{Starting Era}:", eras, gameParameters.startingEra)
        { gameParameters.startingEra = it; null }
    }

    private fun Table.addDurationSelectBox(
        title: String,
        param: KMutableProperty1<GameParameters, Int>,
        defaultDayValue: Int,
        defaultHourValue: Int,
        defaultMinuteValue: Int
    ) {
        add(title.toLabel(hideIcons = true, role = TextRole.Body)).left()
        if (roomLayoutWidth != null || directLayoutWidth != null) getCell(children.peek()).colspan(3).row()
        val minutes = param.get(gameParameters)
        val selector = if (roomLayoutWidth == null && directLayoutWidth == null)
            DurationSelector(gameParameters, param, defaultDayValue, defaultHourValue, defaultMinuteValue)
        else DurationSelector(gameParameters, param, minutes / 1440, minutes % 1440 / 60, minutes % 60)

        add(selector.dayBox)
        add(selector.hourBox)
        add(selector.minuteBox).row()
    }
    private class DurationSelector(
        private val gameParameters: GameParameters,
        private val param: KMutableProperty1<GameParameters, Int>,
        defaultDayValue: Int,
        defaultHourValue: Int,
        defaultMinuteValue: Int,
        private val dayValues: Array<Int> = arrayOf(0,1,2,3,4,5,6,7,8,9,10,11),
        private val hourValues: Array<Int> = arrayOf(0,1,2,3,4,5,6,8,10,12,16,20),
        private val minuteValues: Array<Int> = arrayOf(0,3,5,10,15,20,25,30,35,40,45,50)
        
    ) {
        val dayBox: SelectBox<String> = createTimeCell(dayValues, defaultDayValue, "d")
        val hourBox: SelectBox<String> = createTimeCell(hourValues, defaultHourValue, "h")
        val minuteBox: SelectBox<String> = createTimeCell(minuteValues, defaultMinuteValue, "m")
        val boxes: List<SelectBox<String>> = listOf(dayBox, hourBox, minuteBox)

        init { for (box in boxes) {box.onChange {preventNullTime(); updateGameParameter()}} }

        fun preventNullTime() {
            if (dayBox.selected == "0d" && hourBox.selected == "0h" && minuteBox.selected == "0m") {
                minuteBox.selected ="3m"
            }
        }
            
        fun updateGameParameter() {
            val value = dayValues[dayBox.selectedIndex] * 24 * 60 +
                hourValues[hourBox.selectedIndex] * 60 +
                minuteValues[minuteBox.selectedIndex]

            param.set(gameParameters, value)
        }
        
        fun createTimeCell(intValues: Array<Int>, initialValue: Int, suffix: String): SelectBox<String> {
            val timeBox = SelectBox<String>(BaseScreen.skin)
            val stringValues =  Array(intValues.size) { i -> "${intValues[i]}" + suffix }
            timeBox.setItems(*stringValues)
            timeBox.selected = "$initialValue" + suffix
            return timeBox
        }
    }
    private fun addVictoryTypeCheckboxes() {
        if (directLayoutWidth == null) add("{Victory Conditions}:".toLabel(TextRole.Body)).colspan(2).row()

        // Create a checkbox for each VictoryType existing
        val victoryConditionsTable = Table().apply { defaults().pad(if (directLayoutWidth == null) 5f else 2f) }
        val victories = if (directLayoutWidth == null) ruleset.selectableVictories()
            else ruleset.selectableVictories().filter { !it.enablesMaxTurns() }.sortedBy {
                listOf("Scientific", "Diplomatic", "Cultural", "Domination").indexOf(it.name).let { index -> if (index < 0) 4 else index }
            }
        for ((i, victoryType) in victories.withIndex()) {
            val caption = if (directLayoutWidth != null && victoryType.name == "Domination") "Military" else victoryType.name
            val victoryCheckbox = caption.toCheckBox(gameParameters.victoryTypes.contains(victoryType.name)) {
                // If the checkbox is checked, adds the victoryTypes else remove it
                if (it) {
                    gameParameters.victoryTypes.add(victoryType.name)
                } else {
                    gameParameters.victoryTypes.remove(victoryType.name)
                }
                // show or hide the max turns select box
                if (victoryType.enablesMaxTurns())
                    update()
            }
            victoryCheckbox.name = victoryType.name
            victoryCheckbox.isDisabled = locked
            victoryConditionsTable.add(victoryCheckbox).left().apply {
                if (directLayoutWidth != null) width(directLayoutWidth / 2f - 10f).minHeight(28f)
            }
            if ((i + 1) % 2 == 0) victoryConditionsTable.row()
        }
        add(victoryConditionsTable).colspan(if (directLayoutWidth == null) 2 else 1).left().row()
    }

    fun updateRuleset(ruleset: Ruleset) {
        this.ruleset = ruleset
        gameParameters.acceptedModCheckErrors = ""
        modCheckboxes.updateSelection()
        modCheckboxes.setBaseRuleset(gameParameters.baseRuleset)
    }

    private fun reloadRuleset() {
        ruleset.clear()
        val newRuleset = RulesetCache.getComplexRuleset(gameParameters)
        ruleset.add(newRuleset)
        ruleset.mods += gameParameters.baseRuleset
        ruleset.mods += gameParameters.mods
        ruleset.modOptions = newRuleset.modOptions
        gameParameters.acceptedModCheckErrors = ""

        ImageGetter.setNewRuleset(ruleset)
        UncivGame.Current.musicController.setModList(gameParameters.getModsAndBaseRuleset())

        // Remove victory types which are not in the new ruleset, then default to all if none remain
        gameParameters.victoryTypes.removeAll { it !in ruleset.victories.keys }
        if (gameParameters.victoryTypes.isEmpty())
            gameParameters.victoryTypes.addAll(ruleset.selectableVictories().map { it.name })

        // Mod choices will change the number of available civs
        val maxMajorCivs = numberOfMajorCivs()
        if (gameParameters.maxNumberOfPlayers > maxMajorCivs) gameParameters.maxNumberOfPlayers = maxMajorCivs
        if (gameParameters.minNumberOfPlayers > maxMajorCivs) gameParameters.minNumberOfPlayers = maxMajorCivs

        val maxCityStates = numberOfCityStates()
        if (gameParameters.maxNumberOfCityStates > maxCityStates) gameParameters.maxNumberOfCityStates = maxCityStates
        if (gameParameters.minNumberOfCityStates > maxCityStates) gameParameters.minNumberOfCityStates = maxCityStates
        if (gameParameters.numberOfCityStates > maxCityStates) gameParameters.numberOfCityStates = maxCityStates

        (previousScreen as? NewGameScreen)?.refreshExampleMap()
        update()
    }

    private fun getModCheckboxes(isPortrait: Boolean = false): ModCheckboxTable {
        return ModCheckboxTable(gameParameters.mods, gameParameters.baseRuleset, previousScreen as BaseScreen, isPortrait) {
            onChooseMod(it)
        }
    }

    private fun onChooseMod(mod: String) {
        val activeMods = gameParameters.getModsAndBaseRuleset()
        UncivGame.Current.translations.translationActiveMods = activeMods
        reloadRuleset()

        var desiredCiv = ""
        val rng = GameContext(gameInfo = UncivGame.Current.gameInfo).stateBasedRandom("GameOptionsTable.onChooseMod", mod.hashCode())
        if (gameParameters.mods.contains(mod)) {
            val modNations = RulesetCache[mod]?.nations?.values?.filter { it.isMajorCiv }

            if (modNations != null && modNations.any())
                desiredCiv = modNations.random(rng).name

            val music = UncivGame.Current.musicController
            if (!music.chooseTrack(mod, MusicMood.Theme, MusicTrackChooserFlags.setSelectNation) && desiredCiv.isNotEmpty())
                music.chooseTrack(desiredCiv, MusicMood.themeOrPeace, MusicTrackChooserFlags.setSelectNation)
        }

        updatePlayerPickerTable(desiredCiv)
    }

    fun changeGameParameters(newGameParameters: GameParameters) {
        gameParameters = newGameParameters
        modCheckboxes.changeGameParameters(newGameParameters)
    }
}

private class RandomNationPickerPopup(
    previousScreen: IPreviousScreen,
    private val gameParameters: GameParameters,
    private val onExit: () -> Unit
) : Popup(previousScreen as BaseScreen) {
    companion object {
        // These are used for the Close/OK buttons in the lower left/right corners:
        const val buttonsCircleSize = 70f
        const val buttonsIconSize = 50f
        const val buttonsOffsetFromEdge = 5f
        val buttonsBackColor: Color = ImageGetter.CHARCOAL.cpy().apply { a = 0.67f }
    }

    // This Popup's body has two halves of same size, either side by side or arranged vertically
    // depending on screen proportions - determine height for one of those
    private val isPortrait = (previousScreen as BaseScreen).isPortrait()
    private val unit = UiTypography.unitsPerPoint(stageToShowOn)
    private val civBlocksWidth = if (isPortrait)
        minOf(300f * unit, stageToShowOn.width * 0.76f)
    else stageToShowOn.width / 3 - 5f
    private val partHeight = if (isPortrait) {
        val reservedHeight = 220f * unit // close, two section titles, separator and fixed actions
        ((stageToShowOn.height * 0.9f - reservedHeight) / 2f)
            .coerceAtLeast(120f * unit)
    } else stageToShowOn.height * 0.8f
    /** Widget offering Nations for the Pool (those that would be excluded) goes on the left/top */
    private val availableNationsListTable = Table()
    private val availableNationsListScroll = AutoScrollPane(availableNationsListTable)
    /** Widget selecting Nations for the Pool (those that could be chosen for a random slot) goes on the right/bottom */
    private val selectedNationsListTable = Table()
    private val selectedNationsListScroll = AutoScrollPane(selectedNationsListTable)
    /** sorted list of all major nations as ready-made button */
    private val allNationTables: ArrayList<NationTable>
    /** backing for the left side - unchosen Nations */
    private var availableNations: MutableSet<String>
    /** backing for the right side - chosen Nations */
    private var selectedNations: MutableSet<String>

    init {
        val sortedNations = previousScreen.ruleset.nations.values
                .filter { it.isMajorCiv }
                .filterNot { it.hasUnique(UniqueType.WillNotBeChosenForNewGames) }
                .sortedWith(compareBy(UncivGame.Current.settings.getCollatorFromLocale()) { it.name.tr(hideIcons = true) })
        allNationTables = ArrayList(
            sortedNations.map { nation -> NationTable(nation, civBlocksWidth, 0f).apply {
                if (UiNavigation.isMobile) {
                    clearChildren()
                    background = SinglePlayerSetup.surface()
                    pad(4f * unit)
                    add(ImageGetter.getNationPortrait(nation, 28f * unit)).size(28f * unit).padRight(8f * unit)
                    add(nation.name.toLabel(TextRole.Control, unit = unit, hideIcons = true).apply { wrap = true })
                        .width(civBlocksWidth - 44f * unit).left()
                }
            } }
        )
        availableNations = sortedNations.map { it.name }.toMutableSet()
        selectedNations = gameParameters.randomNationsPool.intersect(availableNations) as MutableSet<String>
        availableNations.removeAll(selectedNations)

        availableNationsListScroll.setOverscroll(false, false)
        // size below uses civBlocksWidth +10, because the nation table has a 5f pad, for a total of +10f
        add("Banned nations".toLabel(TextRole.SectionTitle))
        if (isPortrait) {
            row()
            add(availableNationsListScroll).size( civBlocksWidth + 10f, partHeight ).row()
            addSeparator()
            add("Available nations".toLabel(TextRole.SectionTitle)).row()
        } else {
            add("Available nations".toLabel(TextRole.SectionTitle)).row()
            add(availableNationsListScroll).size( civBlocksWidth + 10f, partHeight )
        }
        selectedNationsListScroll.setOverscroll(false, false)
        add(selectedNationsListScroll).size(civBlocksWidth + 10f, partHeight)

        update()
        pack()

        if (!UiNavigation.isMobile) {
            val closeButton = getCloseButton(buttonsCircleSize, buttonsIconSize, buttonsBackColor, Color.FIREBRICK) { close() }
            closeButton.setPosition(buttonsOffsetFromEdge, buttonsOffsetFromEdge, Align.bottomLeft)
            innerTable.addActor(closeButton)
        }
        clickBehindToClose = true

        val okButton = if (UiNavigation.isMobile)
            "OtherIcons/Checkmark".toImageButton(20f * unit, 44f * unit, buttonsBackColor, Color.LIME)
        else "OtherIcons/Checkmark".toImageButton(Color.LIME)
        okButton.onClick { returnSelected() }

        val switchButton = if (UiNavigation.isMobile)
            "OtherIcons/NationSwap".toImageButton(20f * unit, 44f * unit, buttonsBackColor, Color.YELLOW)
        else "OtherIcons/NationSwap".toImageButton(Color.YELLOW)
        switchButton.onClick { switchAllNations() }
        if (UiNavigation.isMobile) {
            addCloseButton()
            val touchSize = UiNavigation.minimumTouchSize()
            bottomTable.add(switchButton).size(touchSize)
            bottomTable.add(okButton).size(touchSize)
        } else {
            okButton.setPosition(innerTable.width - buttonsOffsetFromEdge, buttonsOffsetFromEdge, Align.bottomRight)
            innerTable.addActor(okButton)
            switchButton.setPosition(innerTable.width / 2, buttonsOffsetFromEdge + 35, Align.center)
            innerTable.addActor(switchButton)
        }

        selectedNationsListTable.touchable = Touchable.enabled
    }

    fun update() {
        updateNationListTable(availableNationsListTable, availableNations) {
            nation -> { addNationToPool(nation) }
        }
        updateNationListTable(selectedNationsListTable, selectedNations) {
            nation -> { removeNationFromPool(nation) }
        }
    }

    private fun updateNationListTable(table: Table, nations: Set<String>, actionFactory: (Nation)->(()->Unit) ) {
        for (child in table.children) { child.listeners.clear() }
        table.clear()

        for (nationTable in allNationTables) {
            if (nationTable.nation.name !in nations) continue
            nationTable.onClick(actionFactory(nationTable.nation))
            table.add(nationTable).apply { if (UiNavigation.isMobile) minHeight(UiNavigation.minimumTouchSize()).fillY() }.row()
        }
    }

    private fun String.toImageButton(overColor: Color) =
            toImageButton(buttonsIconSize, buttonsCircleSize, buttonsBackColor, overColor)

    private fun addNationToPool(nation: Nation) {
        availableNations.remove(nation.name)
        selectedNations.add(nation.name)

        update()
    }

    private fun removeNationFromPool(nation: Nation) {
        availableNations.add(nation.name)
        selectedNations.remove(nation.name)

        update()
    }

    private fun returnSelected() {
        close()
        gameParameters.randomNationsPool = ArrayList(selectedNations)
        onExit()
    }

    private fun switchAllNations() {
        val tempNations = availableNations
        availableNations = selectedNations
        selectedNations = tempNations
        update()
    }
}
