package com.unciv.ui.screens.worldscreen.topbar

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.math.Rectangle
import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Group
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.Cell
import com.badlogic.gdx.scenes.scene2d.ui.Container
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.utils.Align
import com.unciv.Constants
import com.unciv.logic.civilization.Civilization
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.center
import com.unciv.ui.components.extensions.darken
import com.unciv.ui.components.extensions.setFontSize
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.fonts.Fonts
import com.unciv.ui.components.input.ClickableCircle
import com.unciv.ui.components.input.KeyboardBinding
import com.unciv.ui.components.input.onActivation
import com.unciv.ui.components.input.onClick
import com.unciv.ui.components.input.onRightClick
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.overviewscreen.EmpireOverviewCategories
import com.unciv.ui.screens.worldscreen.BackgroundActor
import com.unciv.ui.screens.worldscreen.WorldScreen
import com.unciv.ui.screens.worldscreen.BattlefieldHud
import com.unciv.ui.screens.worldscreen.BattlefieldGeometry
import com.unciv.ui.components.widgets.UiNavigation
import com.unciv.ui.screens.worldscreen.mainmenu.WorldScreenMenuPopup
import kotlin.math.max


/**
 * Table consisting of the menu button, current civ, some stats and the overview button for the top of [WorldScreen].
 *
 * Calling [update] will refresh content and layout, and place the Table on the top edge of the stage, filling its width.
 *
 * [update] will also attempt geometry optimization:
 *  * When there's enough room, the top bar has the stats row ([WorldScreenTopBarStats]) and the resources
 *      row ([WorldScreenTopBarResources]), and the selected-civ ([SelectedCivilizationTable]) and overview
 *      ([OverviewAndSupplyTable]) button elements are overlaid (floating, not in a Cell) to the left and right.
 *  * When screen space gets cramped (low resolution or portrait mode) and one of the overlaid elements would
 *      cover parts of the stats and/or resources lines, we move them down accordingly - below the stats line
 *      if the resources still have enough room, below both otherwise.
 *  * But the elements should have a background - this is done with "filler cells". This Table is now 3x3,
 *      with the stats line as colspan(3) in the top row, resources also colspan(3) in the second row,
 *      and the third row is filler - empty - filler. These fillers do a background with just one rounded
 *      corner - bottom and to the screen center. The middle cell of that row has no actor and expands,
 *      and since the entire Table is Touchable.childrenOnly, completely transparent to the map below.
 *
 * Table layout in the "cramped" case:
 * ```
 * +----------------------------------------+
 * | WorldScreenTopBarStats      colspan(3) |
 * +----------------------------------------+
 * | WorldScreenTopBarResources  colspan(3) |
 * +----------------------------------------+
 * | Filler |    transparent!!!    | Filler |
 * +--------╝                      ╚--------+
 * ```
 * Reminder: Not the `Table`, the `Cell` actors (all except the transparent one) have the background.
 * To avoid gaps, _no_ padding except inside the cell actors, and all actors need to _fill_ their cell.
 */

//region Fields
class WorldScreenTopBar(internal val worldScreen: WorldScreen) : Table() {

    private val statsTable = WorldScreenTopBarStats(this)
    private val resourceTable = WorldScreenTopBarResources(this)
    private val selectedCivTable = SelectedCivilizationTable(worldScreen)
    private val overviewButton = OverviewAndSupplyTable(worldScreen)
    private val leftFiller: BackgroundActor
    private val rightFiller: BackgroundActor
    private var baseHeight = 0f
    private val inlineTechSlot = Table()
    private var battlefieldInlineTech = false

    internal fun refitBattlefield(): Rectangle? {
        updateLayout()
        validate()
        return if (battlefieldInlineTech)
            Rectangle(x + inlineTechSlot.x, y + inlineTechSlot.y, inlineTechSlot.width, inlineTechSlot.height)
        else null
    }

    companion object {
        /** When the "fillers" are used, this is added to the required height, alleviating the "gap" problem a little. */
        const val gapFillingExtraHeight = 1f
    }
    //endregion

    init {
        // init only prepares, the cells are created by update()

        defaults().center()
        setRound(false) // Prevent Table from doing internal rounding which would provoke gaps

        val backColor = BaseScreen.skinStrings.skinConfig.baseColor.darken(0.5f)
        statsTable.background = BaseScreen.skinStrings.getUiBackground("WorldScreen/TopBar/StatsTable", tintColor = backColor)
        resourceTable.background = BaseScreen.skinStrings.getUiBackground("WorldScreen/TopBar/ResourceTable", tintColor = backColor)

        val leftFillerBG = BaseScreen.skinStrings.getUiBackground("WorldScreen/TopBar/LeftAttachment", BaseScreen.skinStrings.roundedEdgeRectangleShape, backColor)
        leftFiller = BackgroundActor(leftFillerBG, Align.topLeft)
        val rightFillerBG = BaseScreen.skinStrings.getUiBackground("WorldScreen/TopBar/RightAttachment", BaseScreen.skinStrings.roundedEdgeRectangleShape, backColor)
        rightFiller = BackgroundActor(rightFillerBG, Align.topRight)
        if (BattlefieldHud.enabled) selectedCivTable.attachTurnLabel(resourceTable.battlefieldTurnLabel)
    }

    internal fun update(civInfo: Civilization) {
        setLayoutEnabled(false)
        statsTable.update(civInfo)
        resourceTable.update(civInfo)
        selectedCivTable.update(worldScreen)
        overviewButton.update(worldScreen)
        updateLayout()
        setLayoutEnabled(true)
    }

    internal fun getYForTutorialTask(): Float = y + height - baseHeight

    /** Performs the layout tricks mentioned in the class Kdoc */
    private fun updateLayout() {
        if (BattlefieldHud.enabled) {
            val safe = worldScreen.safeAreaBoundsInWorld()
            val gap = BattlefieldHud.points(6f)
            val availableWidth = safe.width - gap * 2f
            val portrait = safe.height > safe.width
            val touch = UiNavigation.minimumTouchSize()
            val civWidth = minOf(
                maxOf(BattlefieldHud.points(if (portrait) 132f else 150f), selectedCivTable.prefWidth),
                availableWidth * if (portrait) .72f else .32f
            )
            val techWidth = worldScreen.techPolicyAndDiplomacy.prefWidth
            battlefieldInlineTech = !portrait && techWidth > 0f &&
                civWidth + statsTable.prefWidth + techWidth + overviewButton.prefWidth + gap * 3f <= availableWidth
            clear()
            if (portrait) {
                add(selectedCivTable).width(civWidth).minHeight(touch).left()
                add().growX()
                add(overviewButton).minHeight(touch).right().padLeft(gap).row()
                add(statsTable).colspan(3).minWidth(0f).growX()
            } else {
                add(selectedCivTable).width(civWidth).minHeight(touch).left()
                add(statsTable).minWidth(0f).growX().padLeft(gap)
                if (battlefieldInlineTech) add(inlineTechSlot).width(techWidth).height(touch).padLeft(gap)
                add(overviewButton).minHeight(touch).right().padLeft(gap)
            }
            // The compact HUD keeps the turn label beside the civilization, but strategic
            // resources still need their own live row when a resource has been revealed.
            if (resourceTable.battlefieldResources.children.notEmpty()) {
                row()
                add(resourceTable.battlefieldResources)
                    .colspan(if (!portrait && battlefieldInlineTech) 4 else 3)
                    .minWidth(0f).growX().minHeight(touch)
            }
            background = BaseScreen.skinStrings.getUiBackground("WorldScreen/TopBar/StatsTable")
            setSize(availableWidth, prefHeight)
            setPosition(safe.x + gap, safe.y + safe.height - height - gap)
            baseHeight = height
            return
        }
        val targetWidth = stage.width
        val statsWidth = statsTable.prefWidth
        val resourceWidth = resourceTable.prefWidth
        val overviewWidth = overviewButton.minWidth
        val overviewHeight = overviewButton.minHeight
        val selectedCivWidth = selectedCivTable.minWidth
        val selectedCivHeight = selectedCivTable.minHeight
        // Since stats/resource lines are centered, the max decides when to snap the overlaid elements down
        val leftRightNeeded = max(selectedCivWidth, overviewWidth)
        // Height of the two "overlay" elements should be equal, but just in case:
        val overlayHeight = max(overviewHeight, selectedCivHeight)

        clear()
        // Without the explicit cell width, a 'stats' line wider than the stage can force the Table to
        // misbehave and place the filler actors out of bounds, even if Table.width is correct.
        add(statsTable).colspan(3).growX().width(targetWidth).row()
        // Probability of a too-wide resources line is low in Vanilla, but mods may have lots more...
        add(resourceTable).colspan(3).growX().width(targetWidth).row()
        layout()  // force rowHeight calculation - validate is not enough - Table quirks
        val statsRowHeight = getRowHeight(0)
        baseHeight = statsRowHeight + getRowHeight(1)

        fun addFillers(fillerHeight: Float) {
            add(leftFiller).size(selectedCivWidth, fillerHeight + gapFillingExtraHeight)
            add().growX()
            add(rightFiller).size(overviewWidth, fillerHeight + gapFillingExtraHeight)
        }

        // Check whether it gets cramped on narrow aspect ratios
        val centerButtonsToHeight = when {
            leftRightNeeded * 2f > targetWidth - resourceWidth -> {
                // Need to shift buttons down to below both stats and resources
                addFillers(overlayHeight)
                overlayHeight
            }
            leftRightNeeded * 2f > targetWidth - statsWidth -> {
                // Shifting buttons down to below stats row is enough
                addFillers(statsRowHeight)
                overlayHeight
            }
            else -> {
                // Enough space to keep buttons to the left and right of stats and resources - no fillers
                baseHeight
            }
        }

        // Don't use align with setPosition as we haven't pack()ed and element dimensions might not be final
        setSize(targetWidth, prefHeight)  // sizing to prefHeight is half a pack()
        setPosition(0f, stage.height - prefHeight)

        selectedCivTable.setPosition(0f, (centerButtonsToHeight - selectedCivHeight) / 2f)
        overviewButton.setPosition(targetWidth - overviewWidth, (centerButtonsToHeight - overviewHeight) / 2f)
        addActor(selectedCivTable) // needs to be after size
        addActor(overviewButton)
    }

    private class OverviewAndSupplyTable(worldScreen: WorldScreen) : Table(BaseScreen.skin) {
        val unitSupplyImage = ImageGetter.getImage("OtherIcons/ExclamationMark")
            .apply { color = Color.FIREBRICK }
        val unitSupplyCell: Cell<Actor?>

        init {
            unitSupplyImage.onClick {
                worldScreen.openEmpireOverview(EmpireOverviewCategories.Units)
            }

            val overviewButton = "Overview".toTextButton().apply { name = "ui-overview" }
            if (BattlefieldHud.enabled) {
                BattlefieldGeometry.fitControl(overviewButton)
                overviewButton.pad(BattlefieldHud.points(6f))
                overviewButton.clearChildren()
                overviewButton.add(ImageGetter.getImage("VictoryScreenIcons/Charts")).size(BattlefieldHud.points(20f))
            }
            overviewButton.onActivation(binding = KeyboardBinding.EmpireOverview) {
                worldScreen.openEmpireOverview()
            }

            unitSupplyCell = add()
            val cell = add(overviewButton).pad(if (BattlefieldHud.enabled) 0f else 10f)
            if (BattlefieldHud.enabled) cell.size(UiNavigation.minimumTouchSize())
            pack()
        }

        fun update(worldScreen: WorldScreen) {
            val newVisible = worldScreen.selectedCiv.stats.getUnitSupplyDeficit() > 0
            if (newVisible == unitSupplyCell.hasActor()) return
            if (newVisible) unitSupplyCell.setActor(unitSupplyImage)
                .size(50f).padLeft(10f)
            else unitSupplyCell.setActor(null).size(0f).pad(0f)
            invalidate()
            pack()
        }
    }

    private class SelectedCivilizationTable(worldScreen: WorldScreen) : Table(BaseScreen.skin) {
        private var selectedCiv = ""
        // Instead of allowing tr() to insert the nation icon - we don't want it scaled with fontSizeMultiplier
        private var selectedCivIcon = Group()
        private val selectedCivIconCell: Cell<Group>
        private val selectedCivLabel = "".toLabel()
        private val turnHolder = Container<Label>().minWidth(0f).fillX().left()

        fun attachTurnLabel(label: Label) {
            label.setFontSize(BattlefieldHud.font(11f))
            label.setEllipsis(true)
            label.setAlignment(Align.left)
            turnHolder.actor = label
        }

        private val menuButton = ImageGetter.getImage("OtherIcons/MenuIcon")
        private val menuButtonWrapper = Container(menuButton)
        private val menuButtonHitArea = ClickableCircle(if (BattlefieldHud.enabled) UiNavigation.minimumTouchSize() else Constants.headingFontSize * 2.25f)

        init {
            left()
            pad(if (BattlefieldHud.enabled) 0f else 10f)

            menuButton.touchable = Touchable.disabled
            menuButtonWrapper.size(if (BattlefieldHud.enabled) BattlefieldHud.points(22f) else Constants.headingFontSize * 1.5f)
            menuButtonWrapper.center()
            menuButtonHitArea.onActivation(binding = KeyboardBinding.Menu) { WorldScreenMenuPopup(worldScreen) }
            menuButtonHitArea.onRightClick { WorldScreenMenuPopup(worldScreen, true) }

            val onNationClick = {
                worldScreen.openCivilopedia(worldScreen.selectedCiv.nation.makeLink())
            }
            selectedCivLabel.setFontSize(if (BattlefieldHud.enabled) BattlefieldHud.font(13f) else Constants.headingFontSize)
            if (BattlefieldHud.enabled) selectedCivLabel.setEllipsis(true)
            if (!BattlefieldHud.enabled) {
                selectedCivLabel.onClick(onNationClick)
                selectedCivIcon.onClick(onNationClick)
            }

            val menuCell = add(menuButtonWrapper)
            if (BattlefieldHud.enabled) menuCell.size(UiNavigation.minimumTouchSize())
            addActor(menuButtonHitArea) // Will be positioned over menuButtonWrapper in [update]
            val nation = if (BattlefieldHud.enabled) Table().apply {
                touchable = Touchable.enabled
                onActivation { onNationClick() }
                this@SelectedCivilizationTable.add(this).minWidth(0f).growX().minHeight(UiNavigation.minimumTouchSize()).fillY()
                selectedCivLabel.touchable = Touchable.disabled
            } else this
            selectedCivIconCell = nation.add(selectedCivIcon).padLeft(if (BattlefieldHud.enabled) BattlefieldHud.points(2f) else Constants.defaultFontSize / 1.5f)
            if (BattlefieldHud.enabled) {
                val labels = Table().apply {
                    add(selectedCivLabel).minWidth(0f).growX().left()
                        .padBottom(BattlefieldHud.points(2f)).row()
                    add(turnHolder).minWidth(0f).growX().left()
                }
                nation.add(labels).minWidth(0f).growX().padLeft(BattlefieldHud.points(4f))
            } else nation.add(selectedCivLabel).padTop(10f - Fonts.getDescenderHeight(Constants.headingFontSize))
                .padLeft(Constants.defaultFontSize / 2.0f)
            pack()
        }

        fun update(worldScreen: WorldScreen) {
            val newCiv = worldScreen.selectedCiv.civID
            if (this.selectedCiv == newCiv) return
            this.selectedCiv = newCiv

            selectedCivIcon = ImageGetter.getNationPortrait(worldScreen.selectedCiv.nation,
                if (BattlefieldHud.enabled) BattlefieldHud.points(20f) else 25f)
            selectedCivIconCell.setActor(selectedCivIcon)
            if (BattlefieldHud.enabled) selectedCivIcon.touchable = Touchable.disabled
            val displayName = if (worldScreen.gameInfo.endless != null || com.unciv.logic.campaign.CivilWarCampaign.isActive(worldScreen.gameInfo)) worldScreen.selectedCiv.civName else newCiv
            selectedCivLabel.setText(displayName.tr(hideIcons = true))
            invalidate()
            pack()
            menuButtonHitArea.center(menuButtonWrapper)
            menuButtonHitArea.moveBy(menuButtonWrapper.x, menuButtonWrapper.y)
        }
    }

    override fun act(delta: Float) = super.act(delta)
    override fun draw(batch: Batch?, parentAlpha: Float) = super.draw(batch, parentAlpha)
    override fun hit(x: Float, y: Float, touchable: Boolean): Actor? = super.hit(x, y, touchable)
}
