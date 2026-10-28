package com.unciv.ui.screens.pickerscreens

import com.unciv.ui.components.fonts.TextRole
import com.badlogic.gdx.graphics.Color
import com.unciv.models.skins.ExpeditionBlueTheme
import com.badlogic.gdx.scenes.scene2d.ui.Button
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.Value
import com.badlogic.gdx.utils.Align
import com.unciv.Constants
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.managers.ReligionState
import com.unciv.models.Counter
import com.unciv.models.Religion
import com.unciv.models.ruleset.Belief
import com.unciv.models.ruleset.BeliefType
import com.unciv.models.ruleset.unique.GameContext
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.translations.tr
import com.unciv.ui.components.widgets.AutoScrollPane
import com.unciv.ui.components.widgets.UiNavigation
import com.unciv.ui.components.extensions.disable
import com.unciv.ui.components.extensions.enable
import com.unciv.ui.components.input.onClick
import com.unciv.ui.components.extensions.packIfNeeded
import com.unciv.ui.components.extensions.surroundWithCircle
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.popups.AskTextPopup
import com.unciv.ui.screens.overviewscreen.OverviewUi
import com.unciv.ui.screens.basescreen.RecreateOnResize

class ReligiousBeliefsPickerScreen private constructor(
    choosingCiv: Civilization,
    private val numberOfBeliefsCanChoose: Counter<BeliefType>,
    private val pickIconAndName: Boolean,
    private val draft: Draft?
): ReligionPickerScreenCommon(choosingCiv, disableScroll = true), RecreateOnResize {
    constructor(choosingCiv: Civilization, numberOfBeliefsCanChoose: Counter<BeliefType>, pickIconAndName: Boolean)
        : this(choosingCiv, numberOfBeliefsCanChoose, pickIconAndName, null)

    private data class Draft(val beliefs: List<String?>, val icon: String?, val name: String?, val slot: Int)
    private val horizontalSlots = UiNavigation.isMobile && stage.height > stage.width

    private val topReligionIcons = Table() // Top of the layout, contains icons for religions
    private val leftChosenBeliefs = Table() // Left middle part, contains buttons to select the types of beliefs to choose
    private var slotToReveal: Button? = null
    private val leftScrollPane = object : AutoScrollPane(leftChosenBeliefs) {
        override fun layout() {
            super.layout()
            slotToReveal?.let { button ->
                scrollTo(button.x, button.y, button.width, button.height, true, true)
                updateVisualScroll()
                slotToReveal = null
            }
        }
    }.apply {
        setScrollingDisabled(!horizontalSlots, false)
    }
    private val rightBeliefsToChoose = Table() // Right middle part, contains the beliefs to choose
    private var beliefToReveal: Button? = null
    private val rightScrollPane = object : AutoScrollPane(rightBeliefsToChoose) {
        override fun layout() {
            super.layout()
            beliefToReveal?.let { button ->
                scrollTo(button.x, button.y, button.width, button.height, false, true)
                updateVisualScroll()
                beliefToReveal = null
            }
        }
    }.apply { if (UiNavigation.isMobile) setScrollingDisabled(true, false) }
    private val rightPaneTitle: Label = "Beliefs".toLabel(TextRole.SectionTitle)
    private val leftPane = Table().apply {
        background = OverviewUi.rounded(OverviewUi.panel, 12f)
        pad(OverviewUi.pt(if (UiNavigation.isMobile) 4f else 10f))
        if (!UiNavigation.isMobile)
            add("Beliefs".toLabel(TextRole.SectionTitle)).growX().left().padBottom(OverviewUi.pt(8f)).row()
        add(leftScrollPane).grow().minWidth(0f).minHeight(0f)
    }
    private val rightPane = Table().apply {
        background = OverviewUi.rounded(OverviewUi.panel, 12f)
        pad(OverviewUi.pt(if (UiNavigation.isMobile) 4f else 10f))
        rightPaneTitle.wrap = true
        add(rightPaneTitle).minWidth(0f).growX().left().padBottom(OverviewUi.pt(if (UiNavigation.isMobile) 4f else 8f)).row()
        add(rightScrollPane).grow().minWidth(0f).minHeight(0f)
    }
    private val middlePanes = object : Table() {
        private var stacked: Boolean? = null

        override fun layout() {
            val shouldStack = horizontalSlots || !UiNavigation.isMobile && height > width
            if (stacked != shouldStack) {
                clear()
                if (shouldStack) {
                    val slotHeight = if (UiNavigation.isMobile) Value.Fixed.valueOf(OverviewUi.pt(64f))
                        else Value.percentHeight(.36f, this)
                    add(leftPane).growX().minWidth(0f).height(slotHeight).apply {
                        if (!UiNavigation.isMobile) minHeight(OverviewUi.touch * 3f)
                    }.row()
                    add(rightPane).grow().minWidth(0f).minHeight(0f).padTop(OverviewUi.pt(if (UiNavigation.isMobile) 4f else 10f))
                } else {
                    add(leftPane).width(Value.percentWidth(if (UiNavigation.isMobile) .29f else .34f, this)).growY().minWidth(0f)
                    add(rightPane).grow().minWidth(0f).minHeight(0f).padLeft(OverviewUi.pt(if (UiNavigation.isMobile) 6f else 10f))
                }
                stacked = shouldStack
            }
            super.layout()
        }
    }

    private var iconSelection = Selection()
    private var displayName: String? = null
    private var religionName: String? = null

    // One entry per new Belief to choose - the left side will offer these below the choices from earlier in the game
    class BeliefToChoose(val type: BeliefType, var belief: Belief? = null)
    private val beliefsToChoose: Array<BeliefToChoose> =
        numberOfBeliefsCanChoose.flatMap { entry -> (0 until entry.value).map { BeliefToChoose(entry.key) } }.toTypedArray()

    private var leftSelection = Selection()
    private var leftSelectedIndex = -1
    val selectedBeliefSlot: Int get() = leftSelectedIndex
    val selectedBeliefNames: List<String> get() = beliefsToChoose.mapNotNull { it.belief?.name }
    val selectedReligionIcon: String? get() = religionName
    fun unchosenBeliefSlot(type: BeliefType): Int = beliefsToChoose.indexOfFirst {
        it.belief == null && (it.type == type || it.type == BeliefType.Any)
    }
    private var rightSelection = Selection()

    private val currentReligion = choosingCiv.religionManager.religion
        ?: Religion("None", gameInfo, choosingCiv, gameInfo.turns)

    init {
        if (UiNavigation.isMobile) UiNavigation.styleBackButton(closeButton, "Map")
        if (!UiNavigation.isMobile) pickerPane.setNavigationContent(
            (if (pickIconAndName) "Choose a Religion" else "Enhance [${currentReligion.getReligionDisplayName()}]")
                .toLabel(TextRole.PageTitle)
        )
        draft?.let { saved ->
            religionName = saved.icon
            displayName = saved.name
            beliefsToChoose.forEachIndexed { index, entry -> entry.belief = ruleset.beliefs[saved.beliefs.getOrNull(index)] }
            leftSelectedIndex = saved.slot
        }
        if (UiNavigation.isMobile && leftSelectedIndex !in beliefsToChoose.indices)
            leftSelectedIndex = beliefsToChoose.indexOfFirst { it.belief == null }
        leftChosenBeliefs.top().left()
        rightBeliefsToChoose.top().left()
        leftChosenBeliefs.defaults().left().pad(OverviewUi.pt(if (UiNavigation.isMobile) 2f else 4f)).fillX().minWidth(0f)
        rightBeliefsToChoose.defaults().left().pad(OverviewUi.pt(if (UiNavigation.isMobile) 2f else 4f)).fillX().minWidth(0f)

        if (pickIconAndName) setupChoosableReligionIcons()
        else setupVisibleReligionIcons()

        updateLeftTable()

        topReligionIcons.background = OverviewUi.rounded(OverviewUi.raised, 10f)
        topTable.pad(OverviewUi.pt(if (UiNavigation.isMobile) 6f else 12f))
        if (pickIconAndName || !UiNavigation.isMobile)
            topTable.add(topReligionIcons).growX().minWidth(0f).minHeight(topReligionIcons.prefHeight).row()
        topTable.add(middlePanes).grow().minWidth(0f).minHeight(0f).padTop(OverviewUi.pt(if (UiNavigation.isMobile) 4f else 10f))

        setOKAction(
            if (pickIconAndName) "Choose a Religion"
            else "Enhance [${currentReligion.getReligionDisplayName()}]"
        ) {
            if (civInfo.religionManager.religionState == ReligionState.FoundingReligion)
                civInfo.religionManager.foundReligion(displayName!!, religionName!!)
            chooseBeliefs(beliefsToChoose.map { it.belief!! }, usingFreeBeliefs())
        }
        if (UiNavigation.isMobile && leftSelectedIndex in beliefsToChoose.indices)
            loadRightTable(beliefsToChoose[leftSelectedIndex].type, leftSelectedIndex)
        if (pickIconAndName && displayName != null) rightSideButton.setText("Found [$displayName]".tr())
        checkAndEnableRightSideButton()
    }

    override fun recreate() = ReligiousBeliefsPickerScreen(choosingCiv, numberOfBeliefsCanChoose, pickIconAndName,
        Draft(beliefsToChoose.map { it.belief?.name }, religionName, displayName, leftSelectedIndex))

    private fun checkAndEnableRightSideButton() {
        if (religionName == null || displayName == null) return
        if (beliefsToChoose.any { it.belief == null }) return
        rightSideButton.enable()
    }

    private fun setupChoosableReligionIcons() {
        val descriptionLabel = "Choose an Icon and name for your Religion".toLabel(TextRole.Body).apply {
            name = "ui-religion-name-caption"
        }

        fun changeDisplayedReligionName(newReligionName: String) {
            displayName = newReligionName
            rightSideButton.setText("Found [$newReligionName]".tr())
            descriptionLabel.setText(newReligionName.tr())
        }

        val changeReligionNameButton = if (UiNavigation.isMobile)
            OverviewUi.iconButton("OtherIcons/Pencil", "Choose a name for your religion") {}.apply {
                name = "ui-religion-rename"
            }
        else Button(
            ImageGetter.getImage("OtherIcons/Pencil").apply { this.color = ImageGetter.CHARCOAL }.surroundWithCircle(30f), skin
        )

        addIconsScroll { button, religionName ->
            button.onClickSelect(iconSelection, null) {
                changeDisplayedReligionName(religionName)
                this.religionName = religionName
                changeReligionNameButton.enable()

                checkAndEnableRightSideButton()
            }
        }

        val labelTable = Table()
        if (UiNavigation.isMobile) {
            descriptionLabel.wrap = true
            labelTable.add(descriptionLabel).growX().minWidth(0f)
            labelTable.add(changeReligionNameButton).minSize(OverviewUi.touch).padLeft(OverviewUi.pt(4f))
            pickerPane.setNavigationContent(labelTable)
        } else {
            labelTable.add(descriptionLabel).pad(5f)
            labelTable.add(changeReligionNameButton).pad(5f).row()
            topReligionIcons.add(labelTable).center().pad(5f).row()
        }

        changeReligionNameButton.onClick {
            AskTextPopup(
                this,
                label = "Choose a name for your religion",
                icon = ImageGetter.getReligionPortrait(religionName!!, 80f),
                defaultText = displayName ?: religionName!!,
                fieldLabel = if (UiNavigation.isMobile) "" else "Choose a name for your religion",
                validate = { religionName ->
                    religionName != Constants.noReligionName
                    && ruleset.religions.none { it == religionName }
                    && gameInfo.religions.none { it.value.name == religionName }
                    && religionName != ""
                },
                actionOnOk = { changeDisplayedReligionName(it) }
            ).open()
        }
        if (religionName == null) changeReligionNameButton.disable()
        else changeDisplayedReligionName(displayName ?: religionName!!)
    }

    private fun setupVisibleReligionIcons() {
        topReligionIcons.clear()
        religionName = currentReligion.name
        displayName = currentReligion.getReligionDisplayName()
        val descriptionLabel = displayName!!.toLabel(TextRole.Body)
        if (UiNavigation.isMobile) {
            pickerPane.setNavigationContent(Table().apply {
                add(ImageGetter.getReligionPortrait(religionName!!, OverviewUi.pt(28f))).size(OverviewUi.pt(28f))
                    .padRight(OverviewUi.pt(8f))
                add(descriptionLabel.apply { wrap = true }).growX().minWidth(0f)
            })
            return
        }
        addIconsScroll { button, _ ->
            button.disable()
        }
        topReligionIcons.add(descriptionLabel).center().padBottom(15f)
    }

    private fun addIconsScroll(buttonSetup: (Button, String)->Unit) {
        var scrollTo = 0f
        val iconsTable = Table()
        iconsTable.align(Align.center)

        for (religionName in ruleset.religions) {
            if (religionName == this.religionName)
                scrollTo = iconsTable.packIfNeeded().prefWidth
            val button = Button(ImageGetter.getReligionPortrait(religionName,
                if (UiNavigation.isMobile) OverviewUi.pt(28f) else 60f), skin)
            button.name = "ui-religion-icon:$religionName"
            buttonSetup(button, religionName)
            if (religionName == this.religionName) {
                if (pickIconAndName) iconSelection.switch(button)
                else button.disable(Color(greenDisableColor))
            } else if (gameInfo.religions.keys.any { it == religionName }) button.disable(
                redDisableColor
            )
            iconsTable.add(button).apply {
                if (UiNavigation.isMobile) minSize(OverviewUi.touch)
            }.pad(OverviewUi.pt(if (UiNavigation.isMobile) 2f else 5f))
        }
        iconsTable.row()

        object : AutoScrollPane(iconsTable, skin) {
            private var positioned = false
            override fun layout() {
                super.layout()
                if (!positioned && width > 0f) {
                    scrollX = scrollTo - (width - OverviewUi.touch) / 2
                    updateVisualScroll()
                    positioned = true
                }
            }
        }.apply {
            setScrollingDisabled(false, true)
            setupFadeScrollBars(0f, 0f)  // only way to "remove" scrollbar
            setScrollbarsOnTop(true) // don't waste space on scrollbar
            topReligionIcons.add(this).growX().minWidth(0f).padBottom(if (UiNavigation.isMobile) 0f else 10f).row()
        }
    }

    private fun updateLeftTable() {
        leftChosenBeliefs.clear()
        leftSelection.clear()

        for (belief in currentReligion.getAllBeliefsOrdered()) {
            val beliefButton = getBeliefButton(belief, compact = UiNavigation.isMobile)
            addChosenButton(beliefButton)
            if (UiNavigation.isMobile) beliefButton.onClick { openCivilopedia(belief.makeLink()) }
            else beliefButton.disable(ExpeditionBlueTheme.color(Color.GREEN, ExpeditionBlueTheme.raised))
        }

        for ((index, entry) in beliefsToChoose.withIndex()) {
            addChoosableBeliefButton(entry.belief, entry.type, index)
        }

        equalizeAllButtons(leftChosenBeliefs)
    }

    private fun loadRightTable(beliefType: BeliefType, leftButtonIndex: Int) {
        beliefToReveal = null
        rightBeliefsToChoose.clear()
        rightSelection.clear()
        rightPaneTitle.setText((if (UiNavigation.isMobile) beliefType.name else "Choose a [$beliefType] belief!").tr())
        setBeliefReference(beliefsToChoose[leftButtonIndex].belief)
        val availableBeliefs = ruleset.beliefs.values
            .filter { it.type == beliefType || beliefType == BeliefType.Any }

        val civReligionManager = currentReligion.foundingCiv.religionManager

        for (belief in availableBeliefs) {
            val beliefButton = getBeliefButton(belief, withTypeLabel = !UiNavigation.isMobile)
            beliefButton.name = "ui-belief:${belief.name}"
            when {
                beliefsToChoose[leftButtonIndex].belief == belief -> {
                    beliefToReveal = beliefButton
                    rightSelection.switch(beliefButton)
                }
                beliefsToChoose.any { it.belief == belief } ||
                        currentReligion.hasBelief(belief.name) -> {
                    // The Belief button should be disabled because you already have it selected
                    beliefButton.disable(greenDisableColor)
                }
                civReligionManager.getReligionWithBelief(belief) != null
                        && civReligionManager.getReligionWithBelief(belief) != currentReligion -> {
                    // The Belief is not available because someone already has it
                    beliefButton.disable(redDisableColor)
                }
                belief.getMatchingUniques(UniqueType.OnlyAvailable, GameContext.IgnoreConditionals)
                    .any { !it.conditionalsApply(choosingCiv.state) } ->
                    // The Belief is blocked
                    beliefButton.disable(redDisableColor)
                belief.getMatchingUniques(UniqueType.Unavailable, choosingCiv.state).any() ->
                    // The Belief is blocked
                    beliefButton.disable(redDisableColor)

                else ->
                    beliefButton.onClickSelect(rightSelection, belief) {
                        beliefsToChoose[leftButtonIndex].belief = belief
                        updateLeftTable()
                        checkAndEnableRightSideButton()
                    }
            }
            rightBeliefsToChoose.add(beliefButton).minWidth(0f).growX().minHeight(OverviewUi.touch).row()
        }
        equalizeAllButtons(rightBeliefsToChoose)

        rightScrollPane.scrollY = 0f
        rightScrollPane.updateVisualScroll()
        rightScrollPane.invalidateHierarchy()
    }

    private fun equalizeAllButtons(table: Table) {
        if (UiNavigation.isMobile) return
        val minWidth = table.cells.maxOfOrNull { it.prefWidth }
            ?: return

        for (buttonCell in table.cells) {
            if (buttonCell.actor is Button)
                buttonCell.minWidth(minWidth)
        }
    }

    private fun addChosenButton(button: Button) {
        leftChosenBeliefs.add(button).minWidth(0f).minHeight(OverviewUi.touch).apply {
            if (horizontalSlots) width(OverviewUi.pt(164f)).growY()
            else growX()
        }
        if (!horizontalSlots) leftChosenBeliefs.row()
    }

    private fun addChoosableBeliefButton(belief: Belief?, beliefType: BeliefType, index: Int) {
        val newBeliefButton = getBeliefButton(belief, beliefType, compact = UiNavigation.isMobile)
        newBeliefButton.name = "ui-belief-slot:$index"

        addChosenButton(newBeliefButton)
        if (index == leftSelectedIndex) {
            leftSelection.switch(newBeliefButton)
            slotToReveal = newBeliefButton
            leftScrollPane.invalidate()
        }

        newBeliefButton.onClickSelect(leftSelection, belief) {
            leftSelectedIndex = index
            slotToReveal = newBeliefButton
            leftScrollPane.invalidate()
            loadRightTable(beliefType, index)
        }
    }
}
