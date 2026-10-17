package com.unciv.ui.screens.pickerscreens

import com.unciv.ui.components.fonts.TextRole
import com.badlogic.gdx.scenes.scene2d.ui.Button
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.unciv.UncivGame
import com.unciv.logic.civilization.Civilization
import com.unciv.models.UncivSound
import com.unciv.models.ruleset.unit.BaseUnit
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.skins.ExpeditionBlueTheme
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.isEnabled
import com.unciv.ui.components.extensions.setFontSize
import com.unciv.ui.components.input.KeyboardBinding
import com.unciv.ui.components.input.keyShortcuts
import com.unciv.ui.components.input.onClick
import com.unciv.ui.components.input.onDoubleClick
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.screens.basescreen.RecreateOnResize
import com.unciv.ui.screens.overviewscreen.OverviewUi
import com.unciv.ui.screens.overviewscreen.OverviewUi.font
import com.unciv.ui.screens.overviewscreen.OverviewUi.pt
import com.unciv.ui.screens.worldscreen.WorldScreen

class GreatPersonPickerScreen(val worldScreen: WorldScreen, val civInfo: Civilization) : PickerScreen(), RecreateOnResize {
    private var theChosenOne: BaseUnit? = null
    private val choices = linkedMapOf<BaseUnit, Button>()
    private val detailHeader = Table()
    private val abilities = Table().apply { top().left() }
    private val abilityScroll = OverviewUi.scroll(abilities)
    private val status = OverviewUi.plainLabel("", TextRole.Supporting, OverviewUi.danger).apply { wrap = true }
    private val closeSlot = Table()

    init {
        worldScreen.autoPlay.stopAutoPlay()
        closeButton.isVisible = false
        pickerPane.remove()
        rightSideButton.setText("Choose a Great Person".tr(hideIcons = true))
        rightSideButton.label.setFontSize(font(TextRole.Control))
        rightSideButton.label.wrap = true
        rightSideButton.style = TextButton.TextButtonStyle(rightSideButton.style).apply {
            up = OverviewUi.rounded(OverviewUi.accent)
            over = OverviewUi.rounded(OverviewUi.muted)
            down = OverviewUi.rounded(OverviewUi.selected)
            disabled = OverviewUi.rounded(OverviewUi.raised)
            fontColor = ExpeditionBlueTheme.primaryInk
            overFontColor = fontColor
            downFontColor = ExpeditionBlueTheme.text
            disabledFontColor = OverviewUi.muted
        }
        rightSideButton.pad(0f, pt(16f), 0f, pt(16f))
        rightSideButton.onClick(UncivSound.Choir) { confirmAction() }

        val availableGreatPeople = civInfo.greatPeople.getFreeGreatPersonOptions()
        val list = Table().apply { top() }
        for (unit in civInfo.greatPeople.getGreatPeople().sortedBy { it.name }) {
            val available = unit in availableGreatPeople
            val button = OverviewUi.button(flat = true) { showUnit(unit) }.apply {
                name = "ui-free-person:${unit.name}"
                isEnabled = available
                pad(pt(10f), pt(14f), pt(10f), pt(14f))
                add(ImageGetter.getUnitIcon(unit, if (available) ExpeditionBlueTheme.text else OverviewUi.muted))
                    .size(pt(24f)).padRight(pt(12f))
                add(OverviewUi.plainLabel(unit.name, TextRole.Control,
                    if (available) ExpeditionBlueTheme.text else OverviewUi.muted).apply { wrap = true })
                    .growX().minWidth(0f).left()
                if (!available) add(OverviewUi.plainLabel("Unavailable", TextRole.Supporting, OverviewUi.muted)).padLeft(pt(8f))
                add(ImageGetter.getImage("OtherIcons/Checkmark").apply {
                    color = OverviewUi.accent
                    isVisible = false
                }).size(pt(16f)).padLeft(pt(8f))
                if (available) onDoubleClick(UncivSound.Choir) { showUnit(unit); confirmAction() }
            }
            choices[unit] = button
            list.add(button).growX().minHeight(pt(54f)).row()
            list.add(ImageGetter.getWhiteDot().apply { color = OverviewUi.border }).growX().height(pt(1f)).row()
        }
        val listPanel = Table().apply {
            background = OverviewUi.rounded(OverviewUi.panel, 10f)
            add(OverviewUi.scroll(list)).grow().minHeight(0f).minWidth(0f)
        }
        val detailPanel = Table().apply {
            top()
            background = OverviewUi.rounded(OverviewUi.panel, 10f)
            pad(pt(16f))
            add(detailHeader).growX().minHeight(OverviewUi.touch).padBottom(pt(12f)).row()
            add(abilityScroll).grow().minWidth(0f).minHeight(0f)
        }
        detailHeader.add(OverviewUi.plainLabel("Select a Great Person to view its abilities", TextRole.Body, OverviewUi.muted)
            .apply { wrap = true }).growX().minWidth(0f).left()

        val screenWidth = stage.width
        val screenHeight = stage.height
        val content = Table().apply {
            if (screenWidth > screenHeight) {
                add(listPanel).width(pt(226f).coerceAtMost(screenWidth * .35f)).growY()
                add(detailPanel).grow().minWidth(0f).padLeft(pt(16f))
            } else {
                add(listPanel).growX().height(screenHeight * .3f).row()
                add(detailPanel).grow().minHeight(0f).padTop(pt(12f))
            }
        }
        val header = Table().apply {
            add(OverviewUi.plainLabel("Choose a Great Person", TextRole.SectionTitle).apply { wrap = true }).growX().minWidth(0f).left()
            add(OverviewUi.plainLabel("[${civInfo.greatPeople.freeGreatPeople}] free Great People remaining", TextRole.Supporting, OverviewUi.accent)
                .apply { wrap = true }).width(pt(180f).coerceAtMost(screenWidth * .3f)).right().padLeft(pt(16f))
        }
        val footer = Table().apply {
            add(closeSlot).left()
            add(status).growX().minWidth(0f).padRight(pt(16f))
            add(rightSideButton).width(pt(220f).coerceAtMost(screenWidth * .5f)).minHeight(OverviewUi.touch)
        }
        stage.addActor(Table().apply {
            setFillParent(true)
            background = OverviewUi.surface(OverviewUi.background)
            pad(pt(16f))
            add(header).growX().minHeight(OverviewUi.touch).row()
            add(OverviewUi.plainLabel("You have an unclaimed free Great Person. Select one to review its abilities, then confirm.", TextRole.Supporting, OverviewUi.muted)
                .apply { wrap = true }).growX().minWidth(0f).left().padBottom(pt(16f)).row()
            add(content).grow().minHeight(0f).row()
            add(footer).growX().padTop(pt(12f))
        })
        if (availableGreatPeople.isEmpty()) {
            status.setText("Unavailable".tr())
            allowClose()
        }
    }

    private fun showUnit(unit: BaseUnit) {
        theChosenOne = unit
        for ((option, button) in choices) {
            button.isChecked = option == unit
            button.cells.last().actor?.isVisible = button.isChecked
        }
        status.setText("")
        pick("Get [${unit.name}]".tr(hideIcons = true, hideStats = true))
        detailHeader.clear()
        detailHeader.add(ImageGetter.getUnitIcon(unit, OverviewUi.accent)).size(pt(32f)).padRight(pt(12f))
        detailHeader.add(OverviewUi.plainLabel(unit.name, TextRole.SectionTitle).apply { wrap = true }).growX().minWidth(0f).left()
        detailHeader.add(OverviewUi.iconButton("OtherIcons/Book", "Civilopedia") {
            openCivilopedia(unit.makeLink())
        }.apply { keyShortcuts.add(KeyboardBinding.Civilopedia) }).size(OverviewUi.touch).padLeft(pt(8f))

        abilities.clear()
        val stats = Table().apply {
            left()
            add(OverviewUi.plainLabel("{Movement} ${unit.movement}", TextRole.Supporting, OverviewUi.muted)).left()
            if (unit.strength != 0) add(OverviewUi.plainLabel("{Strength} ${unit.strength}", TextRole.Supporting, OverviewUi.muted)).padLeft(pt(16f))
            if (unit.rangedStrength != 0) add(OverviewUi.plainLabel("{Ranged strength} ${unit.rangedStrength}", TextRole.Supporting, OverviewUi.muted)).padLeft(pt(16f))
        }
        abilities.add(stats).growX().left().padBottom(pt(16f)).row()
        abilities.add(OverviewUi.plainLabel("Great Person abilities", TextRole.Supporting, OverviewUi.muted)).growX().left().padBottom(pt(8f)).row()
        if (unit.replacementTextForUniques.isNotEmpty()) addAbility(unit.replacementTextForUniques)
        else {
            val metadataTypes = setOf(UniqueType.GreatPerson, UniqueType.GPPointPool,
                UniqueType.OneTimeUnitGetsName, UniqueType.Unbuildable, UniqueType.Uncapturable)
            val visible = unit.uniqueObjects.filterNot { it.isHiddenToUsers() }
            for (unique in visible.filterNot { it.type in metadataTypes }) addAbility(unique.getDisplayText())
            val metadata = visible.filter { it.type in metadataTypes }
            if (metadata.isNotEmpty()) {
                abilities.add(OverviewUi.plainLabel("Great Person details", TextRole.Supporting, OverviewUi.muted))
                    .growX().left().padTop(pt(12f)).padBottom(pt(8f)).row()
                for (unique in metadata) addAbility(unique.getDisplayText(), secondary = true)
            }
        }
        for (promotion in unit.promotions) addAbility(promotion)
        abilityScroll.scrollY = 0f
        abilityScroll.updateVisualScroll()
    }

    private fun addAbility(text: String, secondary: Boolean = false) {
        abilities.add(OverviewUi.plainLabel(text, if (secondary) TextRole.Supporting else TextRole.Control,
            if (secondary) OverviewUi.muted else ExpeditionBlueTheme.text).apply { wrap = true })
            .growX().minWidth(0f).left().padBottom(pt(12f)).row()
    }

    private fun confirmAction() {
        val chosenUnit = theChosenOne ?: return
        val currentOptions = civInfo.greatPeople.getFreeGreatPersonOptions()
        if (currentOptions.none { it.name == chosenUnit.name }) {
            status.setText("Unavailable".tr())
            if (currentOptions.isEmpty()) {
                worldScreen.deferFreeGreatPersonPicker = worldScreen.hasPendingFreeGreatPerson()
                UncivGame.Current.popScreen()
            } else {
                theChosenOne = null
                setRightSideButtonEnabled(false)
                allowClose()
            }
            return
        }
        if (civInfo.greatPeople.chooseFreeGreatPerson(chosenUnit.name) == null) {
            status.setText("No space to place this unit".tr())
            allowClose()
            return
        }
        worldScreen.deferFreeGreatPersonPicker = false
        UncivGame.Current.popScreen()
    }

    private fun allowClose() {
        if (closeButton.isVisible) return
        worldScreen.deferFreeGreatPersonPicker = true
        closeButton.isVisible = true
        closeSlot.add(closeButton).minSize(OverviewUi.touch).padRight(pt(12f))
        setDefaultCloseAction()
    }

    override fun recreate() = GreatPersonPickerScreen(worldScreen, civInfo).also { newScreen ->
        theChosenOne?.takeIf { newScreen.choices[it]?.isEnabled == true }?.let { newScreen.showUnit(it) }
        if (status.text.isNotEmpty()) newScreen.status.setText(status.text.toString())
        if (closeButton.isVisible) newScreen.allowClose()
    }
}
