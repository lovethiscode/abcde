package com.unciv.ui.screens.newgamescreen

import com.unciv.ui.components.fonts.UiTypography
import com.unciv.ui.components.fonts.TextRole
import com.unciv.ui.components.widgets.UiNavigation
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.actions.TemporalAction
import com.badlogic.gdx.scenes.scene2d.ui.Container
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.unciv.models.skins.ExpeditionBlueTheme
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.extensions.pad
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.WidgetGroup
import com.badlogic.gdx.utils.Align
import com.unciv.Constants
import com.unciv.GUI
import com.unciv.UncivGame
import com.unciv.logic.civilization.PlayerType
import com.unciv.models.metadata.GameSettings.NationPickerListMode
import com.unciv.models.metadata.Player
import com.unciv.models.ruleset.nation.Nation
import com.unciv.models.translations.tr
import com.unciv.ui.audio.MusicMood
import com.unciv.ui.audio.MusicTrackChooserFlags
import com.unciv.ui.components.InputDisabling
import com.unciv.ui.components.UncivTooltip.Companion.addTooltip
import com.unciv.ui.components.extensions.getCloseButton
import com.unciv.ui.components.extensions.isNarrowerThan4to3
import com.unciv.ui.components.extensions.toImageButton
import com.unciv.ui.components.input.KeyCharAndCode
import com.unciv.ui.components.input.keyShortcuts
import com.unciv.ui.components.input.onActivation
import com.unciv.ui.components.input.onClick
import com.unciv.ui.components.input.onDoubleClick
import com.unciv.ui.components.widgets.AutoScrollPane
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.images.Portrait
import com.unciv.ui.popups.Popup
import com.unciv.ui.screens.basescreen.BaseScreen
import kotlin.math.PI
import kotlin.math.cos

internal class NationPickerPopup(
    private val playerPicker: PlayerPickerTable?,
    private val player: Player,
    private val noRandom: Boolean,
    screen: BaseScreen = playerPicker!!.previousScreen as BaseScreen,
    private val ruleset: com.unciv.models.ruleset.Ruleset = playerPicker!!.previousScreen.ruleset,
    private val availableCivs: List<Nation>? = null,
    private val submit: ((String) -> Unit)? = null,
    private val initialCiv: String = player.chosenCiv,
) : Popup(screen, if (UiNavigation.isIos) Scrollability.WithoutButtons else Scrollability.None) {
    companion object {
        // Note - innerTable has pad(20f) and defaults().pad(5f), so content bottomLeft is at x=25/y=25
        // These are used for the Close/OK buttons in the lower left/right corners:
        const val buttonsCircleSize = 70f
        const val buttonsIconSize = 50f
        const val buttonsOffsetFromEdge = 5f
        val buttonsBackColor: Color = ImageGetter.CHARCOAL.cpy().apply { a = 0.67f }
        // Icon view sizing
        const val iconViewIconSize = 50f  // Portrait lies and will be bigger than asked for (55f)
        const val iconViewCellSize = 60f  // Difference to the above is used for selection highlight
        const val iconViewSpacing = 5f    // Extra spacing between icons
        const val iconViewPadTop = 18f    // align top row with nation icon in detail pane - empiric
        // Allow scrolling the bottom left icons _out_ from under the close/toggle view buttons
        const val iconViewPadBottom = buttonsCircleSize + buttonsOffsetFromEdge - 25f + iconViewSpacing
        const val iconViewPadHorz = iconViewSpacing / 2  // a little empiric
    }

    private val councilScreen = (playerPicker?.previousScreen as? NewGameScreen)?.takeIf { UiNavigation.isIos }
    private val councilLobby = (screen as? com.unciv.ui.screens.multiplayerscreens.MultiplayerLobbyScreen)?.takeIf { UiNavigation.isIos }
    private val councilStyle = councilScreen != null || councilLobby != null
    private val councilFont get() = councilScreen?.formFont ?: councilLobby!!.formFont
    private fun councilHeading(text: String, role: TextRole) =
        councilScreen?.councilHeading(text,role) ?: councilLobby!!.councilHeading(text,role)
    private val councilWidth = (stageToShowOn.width * .76f - 50f).coerceAtMost(1000f)
    private val settings = GUI.getSettings()

    // This Popup's body has two halves of same size, either side by side or arranged vertically
    // depending on screen proportions - determine height for one of those
    private val partHeight = if (councilStyle) (stageToShowOn.height * .84f - 140f).coerceAtLeast(140f)
        else stageToShowOn.height * (if (stageToShowOn.isNarrowerThan4to3()) 0.45f else 0.8f)
    private val stackedLayout = stageToShowOn.isNarrowerThan4to3()
    private val civBlocksWidth = if (councilStyle) councilWidth * (if (stackedLayout) 1f else .34f)
        else playerPicker?.civBlocksWidth ?: minOf(300f, stageToShowOn.width * 0.43f)
    private val detailsWidth = if (councilStyle) councilWidth * (if (stackedLayout) 1f else .66f) else civBlocksWidth

    private val nationListTable = Table()
    private val nationListScroll = AutoScrollPane(nationListTable)
    private val nationDetailsTable = Table()
    private val nationDetailsScroll = AutoScrollPane(nationDetailsTable)

    private class SelectInfo(
        val nation: Nation,
        val scrollY: Float,
        val widget: Container<Portrait>? = null,  // null = unused in List mode
        val row: Table? = null
    )
    private var listMode: NationPickerListMode = if (councilStyle) NationPickerListMode.List else settings.nationPickerListMode
    private var selection: SelectInfo? = null
    private val keySelectMap = mutableMapOf<Char, MutableList<SelectInfo>>()
    private var lastKeyPressed = Char.MIN_VALUE
    private var keyRoundRobin = 0

    init {
        nationListScroll.setOverscroll(false, false)
        if (councilStyle) {
            innerTable.background = SinglePlayerSetup.surface(ExpeditionBlueTheme.panel, ExpeditionBlueTheme.controlBorder)
            addTitleWithCloseButton(Table().apply {
                add(ImageGetter.getImage("OtherIcons/Cities", ExpeditionBlueTheme.gold)).size(24f).padRight(10f)
                add(councilHeading("Choose civilization", TextRole.PageTitle))
            })
        }
        add(nationListScroll).size(civBlocksWidth + 10f, partHeight).padRight(if (councilStyle && !stackedLayout) 12f else 0f)
        // +10, because the nation table has a 5f pad, for a total of +10f
        if (stackedLayout) row()
        nationDetailsScroll.setOverscroll(false, false)
        add(nationDetailsScroll).size(detailsWidth + 10f, partHeight) // Same here, see above

        updateNationListTable()

        clickBehindToClose = true
        addActionIcons()

        nationDetailsTable.touchable = Touchable.enabled
        if (!councilStyle) nationDetailsTable.onClick { returnSelected() }
    }

    /** Note - [newMode]==null toggles, but this is prepared for key shortcuts _setting_ a mode.
     *  Unused due to our key input stack not supporting Ctrl-Numbers yet, postponed.
     */
    private fun toggleListMode(newMode: NationPickerListMode? = null) {
        fun NationPickerListMode.toggle() = when (this) {
            NationPickerListMode.Icons -> NationPickerListMode.List
            NationPickerListMode.List -> NationPickerListMode.Icons
        }
        listMode = newMode ?: listMode.toggle()
        settings.nationPickerListMode = listMode
        updateNationListTable()
        nationListScroll.updateVisualScroll()
    }

    private fun String.toImageButton(overColor: Color) =
        toImageButton(buttonsIconSize, buttonsCircleSize, buttonsBackColor, overColor)

    private fun addActionIcons() {
        if (councilStyle) {
            bottomTable.add().growX()
            val cancel = addCloseButton("Cancel").width(140f).minHeight(44f).actor
            cancel.name = "setup-civ-cancel"
            UiNavigation.styleTextButton(cancel)
            val confirm = addButton("Confirm selection", KeyCharAndCode.RETURN) { returnSelected() }
                .width(160f).minHeight(44f).actor
            confirm.name = "setup-civ-confirm"
            SinglePlayerSetup.style(confirm, councilFont)
            confirm.style = TextButton.TextButtonStyle(confirm.style).apply {
                up = SinglePlayerSetup.surface(ExpeditionBlueTheme.gold, ExpeditionBlueTheme.gold)
                fontColor = ExpeditionBlueTheme.primaryInk
            }
            return
        }
        // Despite being a Popup we use our own buttons - floating circular ones
        if (!UiNavigation.isIos) {
            val closeButton = getCloseButton(buttonsCircleSize, buttonsIconSize, buttonsBackColor, Color.FIREBRICK) { close() }
            closeButton.setPosition(buttonsOffsetFromEdge, buttonsOffsetFromEdge, Align.bottomLeft)
            innerTable.addActor(closeButton)
        }

        val okButton = "OtherIcons/Checkmark".toImageButton(Color.LIME)
        okButton.onActivation { returnSelected() }
        okButton.keyShortcuts.add(KeyCharAndCode.RETURN)

        val switchViewButton = "OtherIcons/NationSwap".toImageButton(Color.ROYAL)
        switchViewButton.onActivation { toggleListMode() }
        // No keyboard support yet - file manager conventions: Ctrl-1 Icons, Ctrl-2 List
        if (UiNavigation.isIos) {
            addCloseButton()
            val touchSize = maxOf(buttonsCircleSize, UiNavigation.minimumTouchSize())
            bottomTable.add(switchViewButton).size(touchSize)
            bottomTable.add(okButton).size(touchSize)
        } else {
            okButton.setPosition(innerTable.width - buttonsOffsetFromEdge, buttonsOffsetFromEdge, Align.bottomRight)
            innerTable.addActor(okButton)
            switchViewButton.setPosition(2 * buttonsOffsetFromEdge + buttonsCircleSize, buttonsOffsetFromEdge, Align.bottomLeft)
            innerTable.addActor(switchViewButton)
        }
    }

    private fun returnSelected() {
        val selectedNation = selection?.nation
            ?: return

        UncivGame.Current.musicController.chooseTrack(selectedNation.name, MusicMood.themeOrPeace, MusicTrackChooserFlags.setSelectNation)

        if (submit != null) {
            close()
            submit.invoke(selectedNation.name)
            return
        }
        player.chosenCiv = selectedNation.name
        player.chosenNation = selectedNation
        
        close()
        playerPicker!!.update()
    }

    private data class NationIterationElement(
        val nation: Nation,
        val translatedName: String = nation.name.tr(hideIcons = true)
    )

    private fun updateNationListTable() {
        nationListTable.clear()
        keySelectMap.clear()
        nationListTable.keyShortcuts.clear()

        // As for background... In List mode, the NationTable blocks come with a 5f horizontal padding,
        // so the Icon mode background "jumps" to 5f wider - haven't found a fix!
        if (listMode == NationPickerListMode.List) {
            nationListTable.background = null
            nationListTable.defaults().space(0f)
            nationListTable.pad(0f)
        } else {
            nationListTable.background = BaseScreen.skinStrings.getUiBackground(
                "NewGameScreen/NationTable/Background",
                tintColor = Color.DARK_GRAY.cpy().apply { a = 0.75f }
            )
            nationListTable.defaults().space(iconViewSpacing)
            nationListTable.pad(iconViewPadTop, iconViewPadHorz, iconViewPadBottom, iconViewPadHorz)
        }

        // These are available as closures to the factories below
        var currentX = 0f
        var currentY = 0f

        // Decide by listMode how each block is built -
        // for each a factory producing an Actor and info on how to select it
        fun getListModeNationActor(element: NationIterationElement): Pair<WidgetGroup, SelectInfo> {
            val nationTable = if (!councilStyle) NationTable(element.nation, civBlocksWidth, 0f)
                else Table().apply {
                    background = SinglePlayerSetup.surface()
                    pad(8f, 10f, 8f, 10f)
                    add(ImageGetter.getNationPortrait(element.nation, 34f)).size(38f).padRight(10f)
                    add(element.translatedName.toLabel(fontSize = UiTypography.size(TextRole.Body, legacySize = 18)).apply { wrap = true }).minWidth(0f).growX().left()
                    add(ImageGetter.getImage("OtherIcons/Checkmark", ExpeditionBlueTheme.gold).apply {
                        name = "nation-selected"
                        isVisible = false
                    }).size(20f)
                }
            nationTable.name = "setup-nation-${element.nation.name}"
            val currentSelectInfo = SelectInfo(element.nation, currentY, row = if (!councilStyle) null else nationTable)
            val cell = nationListTable.add(nationTable).width(civBlocksWidth)
            if (councilStyle) cell.minHeight(54f).padBottom(8f)
            currentY += cell.padBottom + cell.prefHeight + cell.padTop
            cell.row()
            return nationTable to currentSelectInfo
        }

        fun getIconsModeNationActor(element: NationIterationElement): Pair<WidgetGroup, SelectInfo> {
            val nationIcon = ImageGetter.getNationPortrait(element.nation, iconViewIconSize)
            nationIcon.addTooltip(element.translatedName, tipAlign = Align.center, hideIcons = true)
            val nationGroup = Container(nationIcon).apply {
                isTransform = false
                touchable = Touchable.enabled
                setRound(false)
                center()
            }
            val currentSelectInfo = SelectInfo(element.nation, currentY, nationGroup)
            if (currentX + iconViewCellSize > civBlocksWidth) {
                nationListTable.row()
                currentX = 0f
                currentY += iconViewCellSize
            }
            nationListTable.add(nationGroup).size(iconViewCellSize)
            currentX += iconViewCellSize + iconViewSpacing
            return nationGroup to currentSelectInfo
        }

        val nationActorFactory = when (listMode) {
            NationPickerListMode.Icons -> ::getIconsModeNationActor
            NationPickerListMode.List -> ::getListModeNationActor
        }

        selection = null
        var selectInfo: SelectInfo? = null

        for (element in getSortedNations()) {
            val (nationActor, currentSelectInfo) = nationActorFactory(element)

            nationActor.onClick {
                InputDisabling.withInputDisabled {
                    highlightNation(currentSelectInfo)
                }
            }
            if (!councilStyle) nationActor.onDoubleClick {
                selection = currentSelectInfo
                returnSelected()
            }

            if (initialCiv == element.nation.name) {
                selectInfo = currentSelectInfo
            }

            // Keyboard: Fist letter of each "word" - "The Ottomans" get T _and_ O
            val keys = element.translatedName.split(' ').mapNotNull { it.firstOrNull() }.toSet()
            for (key in keys) {
                if (key in keySelectMap) {
                    keySelectMap[key]!! += currentSelectInfo
                } else {
                    keySelectMap[key] = mutableListOf(currentSelectInfo)
                    nationListTable.keyShortcuts.add(key) { onKeyPress(key) }
                }
            }
        }

        nationListScroll.layout()
        pack()
        if (selectInfo != null) highlightNation(selectInfo)
    }

    private fun getSortedNations(): Sequence<NationIterationElement> {
        // Random and Spectator come first, both optional
        val part1 = sequence {
            if (!noRandom) {
                val random = Nation().apply {
                    name = Constants.random
                    innerColor = listOf(255, 255, 255)
                    outerColor = listOf(0, 0, 0)
                    setTransients()
                }
                yield(NationIterationElement(random))
            }
            val spectator = ruleset.nations[Constants.spectator]
            if (!councilStyle && submit == null && spectator != null && player.playerType != PlayerType.AI)  // only humans can spectate, sorry robots
                yield(NationIterationElement(spectator))
        }
        // Then what PlayerPickerTable says we should display - see its doc
        val part2 = (availableCivs ?: playerPicker!!.getAvailablePlayerCivs(player.chosenCiv).toList())
            .map { NationIterationElement(it) }
        // Combine and Sort
        val sorted = part2.sortedWith(compareBy<NationIterationElement> {
            if (councilStyle && it.nation.name == initialCiv) 0 else 1
        }.thenBy(UncivGame.Current.settings.getCollatorFromLocale()) { it.translatedName })
        val spectator = ruleset.nations[Constants.spectator]
        return part1 + sorted + if (councilStyle && submit == null && spectator != null && player.playerType != PlayerType.AI)
            sequenceOf(NationIterationElement(spectator)) else emptySequence()
    }

    private fun onKeyPress(key: Char) {
        // Keyboard is handled for the entire Table, not per Nation Actor to allow round-robin
        // That is, "Germany, Greece, Gremlins" -> press "G" repeatedly to cycle through them.
        val entries = keySelectMap[key] ?: return
        keyRoundRobin = if (key != lastKeyPressed) 0 else (keyRoundRobin + 1) % entries.size
        lastKeyPressed = key
        highlightNation(entries[keyRoundRobin])
    }

    private fun highlightNation(selectInfo: SelectInfo) {
        selection?.widget?.run {
            clearActions()
            background = null
        }

        nationDetailsTable.clearChildren()  // .clear() also clears listeners!
        if (!councilStyle) nationDetailsTable.add(NationTable(selectInfo.nation, civBlocksWidth, partHeight, ruleset))
        else {
            selection?.row?.apply {
                background = SinglePlayerSetup.surface()
                findActor<com.badlogic.gdx.scenes.scene2d.Actor>("nation-selected").isVisible = false
            }
            selectInfo.row?.apply {
                background = SinglePlayerSetup.surface(ExpeditionBlueTheme.raised, ExpeditionBlueTheme.gold)
                findActor<com.badlogic.gdx.scenes.scene2d.Actor>("nation-selected").isVisible = true
            }
            nationDetailsScroll.actor = councilDetails(selectInfo.nation)
            nationDetailsScroll.scrollY = 0f
        }
        selection = selectInfo

        nationListScroll.scrollY = selectInfo.scrollY -
            (nationListScroll.height - nationListTable.getRowHeight(0)) / 2

        // Because in Icons mode it's much less clear _where_ the selected Nation is in the Grid -
        // the scrollY centering is enough in List mode - the selection gets a thin border
        // oscillating between the Nation's colours:
        selectInfo.widget?.addAction(HighlightAction(selectInfo))
    }

    private fun councilDetails(nation: Nation) = Table().apply {
        val textWidth = detailsWidth - 16f
        top()
        padBottom(44f)
        defaults().left().growX().padBottom(8f)
        fun heading(caption: String, icon: String) = Table().apply {
            add(ImageGetter.getImage(icon, ExpeditionBlueTheme.gold)).size(20f).padRight(8f)
            add(caption.toLabel(ExpeditionBlueTheme.gold, UiTypography.size(TextRole.Body, legacySize = 18), hideIcons = true)).left().growX()
        }
        fun text(value: String, width: Float = textWidth) = value.toLabel(fontSize = UiTypography.size(TextRole.Body, legacySize = 18), hideIcons = true).apply {
            wrap = true
        }.also { it.setSize(width, it.prefHeight) }
        add(Table().apply {
            val banner = Table().apply {
                background = ImageGetter.getWhiteDotDrawable().tint(nation.getOuterColor())
                if (nation.name == Constants.random || nation.name == Constants.spectator)
                    add(ImageGetter.getNationPortrait(nation, 64f)).size(72f)
                else add(ImageGetter.getImage("NationIcons/${nation.name}", nation.getInnerColor())).size(72f)
            }
            add(banner).size(128f, 104f).top().padRight(18f)
            add(Table().apply {
                val introWidth = textWidth - 146f
                add(councilHeading(nation.name, TextRole.PageTitle).apply { color = ExpeditionBlueTheme.gold }).left().growX().row()
                if (nation.leaderName.isNotEmpty())
                    add(text("{Leader}: {${nation.leaderName}}", introWidth)).width(introWidth).left().padTop(4f).row()
                if (nation.startIntroPart1.isNotEmpty()) {
                    add(heading("Civilization introduction", "OtherIcons/Scenarios")).padTop(8f).growX().row()
                    val intro = if (nation.name == "Rome") "Rome is known for city building, law and military strength. Lead your empire from a single city to the world."
                        else nation.startIntroPart1
                    add(text(intro, introWidth)).width(introWidth).left().padTop(4f)
                }
            }).minWidth(0f).growX()
        }).width(textWidth).padBottom(10f).row()
        if (nation.uniqueName.isNotEmpty()) add(heading(nation.uniqueName, "OtherIcons/Star")).row()
        val ability = nation.uniqueText.ifEmpty {
            nation.uniqueObjects.filterNot { it.isHiddenToUsers() }.joinToString("\n") { it.getDisplayText() }
        }
        if (ability.isNotEmpty()) add(text(ability)).width(textWidth).row()
        val units = ruleset.units.values.filter { it.uniqueTo != null && nation.matchesFilter(it.uniqueTo!!) && !it.isHiddenFromCivilopedia(ruleset) }
        if (units.isNotEmpty()) {
            add(heading("Unique units", "OtherIcons/UnitNameGroups")).padTop(4f).row()
            add(Table().apply {
                for ((index, unit) in units.withIndex()) {
                    val cardWidth = textWidth / 2f
                    add(Table().apply {
                        add(ImageGetter.getConstructionPortrait(unit.name, 42f)).size(46f).padRight(8f)
                        add(Table().apply {
                            add(text(unit.name, cardWidth - 58f)).width(cardWidth - 58f).left().row()
                            unit.replaces?.let { add(text("Replaces [$it]", cardWidth - 58f)).width(cardWidth - 58f).left().padTop(4f) }
                        }).minWidth(0f).growX()
                    }).width(cardWidth).padBottom(4f)
                    if (index % 2 == 1) row()
                }
            }).width(textWidth).row()
        }
        val bias = nation.getStartBias(ruleset)
        if (bias.isNotEmpty()) {
            add(heading("Start bias:", "OtherIcons/MapEditor")).padTop(4f).row()
            add(text(bias.joinToString(" · ") { it.tr() })).width(textWidth).row()
        }
        add(SinglePlayerSetup.group("Civilopedia", textWidth, "NationDetails") { content ->
            content.add(com.unciv.ui.screens.civilopediascreen.MarkupRenderer.render(nation.getCivilopediaTextLines(ruleset), textWidth - 24f,
                iconDisplay = com.unciv.ui.screens.civilopediascreen.FormattedLine.IconDisplay.NoLink)).growX()
        }).width(textWidth).padTop(4f).row()
    }

    @Suppress("UsePropertyAccessSyntax")  // setColor _is_ a field-by-field copy not a reference set
    private class HighlightAction(selectInfo: SelectInfo) : TemporalAction(1.5f) {
        private val innerColor = selectInfo.nation.getInnerColor()
        private val outerColor = selectInfo.nation.getOuterColor()
        private val widget = selectInfo.widget!!
        private val tempColor = Color()

        override fun begin() {
            widget.background = ImageGetter.getCircleDrawable()
                .apply { setMinSize(iconViewCellSize, iconViewCellSize) }
        }
        override fun update(percent: Float) {
            val t = (1.0 - cos(percent * PI * 2)) / 2
            tempColor.set(outerColor).lerp(innerColor, t.toFloat())
            widget.setColor(tempColor)  // Luckily only affects background
        }
        override fun end() {
            restart()
        }
    }
}
