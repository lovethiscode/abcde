package com.unciv.ui.screens.worldscreen.unit

import com.unciv.view.isCivilian
import com.unciv.ui.components.fonts.UiTypography
import com.unciv.ui.components.fonts.TextRole
import com.unciv.ui.components.widgets.UiNavigation
import com.unciv.view.isPreparingAirSweep
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.Image
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.utils.Align
import com.unciv.logic.map.HexCoord
import com.unciv.ui.components.extensions.addRoundCloseButton
import com.unciv.ui.components.extensions.addSeparator
import com.unciv.ui.components.extensions.center
import com.unciv.ui.components.extensions.darken
import com.unciv.ui.components.extensions.isShiftKeyPressed
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.input.KeyboardBinding
import com.unciv.ui.components.input.keyShortcuts
import com.unciv.ui.components.input.activate
import com.unciv.ui.components.input.onClick
import com.unciv.ui.images.padTopDescent
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.worldscreen.WorldScreen
import com.unciv.ui.screens.worldscreen.BattlefieldHud
import com.unciv.ui.screens.worldscreen.BattlefieldGeometry
import com.unciv.ui.components.extensions.setFontSize
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.Button
import com.badlogic.gdx.scenes.scene2d.Group
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.unciv.ui.components.input.onActivation
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.popups.Popup
import com.unciv.ui.components.fonts.Fonts
import com.unciv.models.translations.tr
import com.unciv.ui.screens.worldscreen.unit.presenter.CityPresenter
import com.unciv.ui.screens.worldscreen.unit.presenter.SpyPresenter
import com.unciv.ui.screens.worldscreen.unit.presenter.SummaryPresenter
import com.unciv.ui.screens.worldscreen.unit.presenter.UnitPresenter
import com.unciv.view.ForeignCityView
import com.unciv.view.ForeignMapUnitView
import com.unciv.view.MapUnitView
import com.unciv.view.SpyView
import com.unciv.view.TileView
import yairm210.purity.annotations.Readonly

class UnitTable(val worldScreen: WorldScreen) : Table() {
    private val prevIdleUnitButton =
        IdleUnitButton(this, worldScreen.mapHolder, true, KeyboardBinding.PrevIdleButton)
    private val nextIdleUnitButton =
        IdleUnitButton(this, worldScreen.mapHolder, false, KeyboardBinding.NextIdleButton)
    internal val battlefieldPreviousUnitButton get() = prevIdleUnitButton
    internal val hasBattlefieldSelection get() = selectedUnit != null || selectedCity != null || selectedSpy != null
    private val battlefieldSummary = "".toLabel(TextRole.Body)
    internal val unitIconHolder = Table()
    internal val unitNameLabel = "".toLabel(fontSize = UiTypography.size(TextRole.PageTitle, legacySize = 24)).apply { setAlignment(Align.center) }
    internal val unitIconNameGroup = Table()
    internal val promotionsTable = Table().apply { defaults().padRight(5f) }
    internal val descriptionTable = Table(BaseScreen.skin)
    internal val closeButton: Actor
    internal val separator: Actor

    /**
     * The unit table shows infos of selected units, cities, spies, and a summary if none of these
     * are selected. Each one of them have their own presenters to show their data.
     */
    private var presenter: Presenter

    private val unitPresenter = UnitPresenter(this, worldScreen)
    private val cityPresenter = CityPresenter(this, unitPresenter)
    private val spyPresenter = SpyPresenter(this)
    private val summaryPresenter = SummaryPresenter(this)


    // This is so that not on every update(), we will update the unit table.
    // Most of the time it's the same unit with the same stats so why waste precious time?
    var shouldUpdate = false

    private var bg = Image(
        BaseScreen.skinStrings.getUiBackground(
            "WorldScreen/UnitTable",
            BaseScreen.skinStrings.roundedEdgeRectangleMidShape,
            BaseScreen.skinStrings.skinConfig.baseColor.darken(0.5f)
        )
    )

    val selectedUnit: MapUnitView?
        get() = (presenter as? UnitPresenter)?.selectedUnit
    val selectedCity: ForeignCityView?
        get() = (presenter as? CityPresenter)?.selectedCity

    val selectedSpy: SpyView?
        get() = (presenter as? SpyPresenter)?.selectedSpy

    val selectedUnits: List<MapUnitView> by unitPresenter::selectedUnits

    var selectedUnitIsSwapping by unitPresenter::selectedUnitIsSwapping

    var selectedUnitIsConnectingRoad by unitPresenter::selectedUnitIsConnectingRoad

    var nameLabelText: String
        get() = unitNameLabel.text.toString()
        set(value) {
            if (nameLabelText != value) {
                unitNameLabel.setText(value)
                // We need to reload the health bar of the unit in the icon - happens e.g. when picking the Heal Instantly promotion
                shouldUpdate = true
            }
        }

    init {
        presenter = summaryPresenter

        pad(5f)
        touchable = Touchable.enabled
        background = BaseScreen.skinStrings.getUiBackground(
            "WorldScreen/UnitTable", BaseScreen.skinStrings.roundedEdgeRectangleMidShape
        )
        addActor(bg)

        promotionsTable.touchable = Touchable.enabled

        closeButton = addRoundCloseButton(this) {
            selectUnit()
            worldScreen.shouldUpdate = true
        }
        closeButton.name = "ui-unit-clear"
        closeButton.keyShortcuts.clear() // This is the only place we don't want the BACK keyshortcut getCloseButton assigns

        if (BattlefieldHud.enabled) {
            background = null
            bg.isVisible = false
            separator = Table()
            val nameAndSummary = Table().apply {
                add(unitNameLabel).minWidth(0f).growX().left().row()
                add(battlefieldSummary).minWidth(0f).growX().left()
                    .padTop(BattlefieldHud.points(1f))
            }
            unitIconHolder.touchable = Touchable.enabled
            unitNameLabel.touchable = Touchable.disabled
            battlefieldSummary.touchable = Touchable.disabled
            battlefieldSummary.setAlignment(Align.left)
            unitIconNameGroup.add(unitIconHolder).size(UiNavigation.minimumTouchSize())
            unitIconNameGroup.add(nameAndSummary).minWidth(0f).height(UiNavigation.minimumTouchSize()).growX().padLeft(BattlefieldHud.points(4f))
            // The name/summary is the details target; a separate button would squeeze the
            // readable identity out of B's narrow command dock. Unit portraits keep their callbacks.
            nameAndSummary.name = "ui-unit-details"
            nameAndSummary.touchable = Touchable.enabled
            nameAndSummary.onActivation { openBattlefieldDetails() }
            add(unitIconNameGroup).minWidth(0f).growX()
        } else add(Table().apply {
            val moveBetweenUnitsTable = Table().apply {
                add(prevIdleUnitButton)
                unitIconNameGroup.add(unitIconHolder)
                unitIconNameGroup.add(unitNameLabel).padTopDescent()
                unitIconHolder.touchable = Touchable.enabled
                unitNameLabel.touchable = Touchable.enabled
                add(unitIconNameGroup)
                add(nextIdleUnitButton)
            }
            add(moveBetweenUnitsTable).fill().row()

            separator = addSeparator().padBottom(5f).actor!!
            add(promotionsTable).row()
            add(descriptionTable)
            touchable = Touchable.enabled
            onClick {
                presenter.position?.let {
                    worldScreen.mapHolder.setCenterPosition(
                        it.toHexCoord(),
                        immediately = false,
                        selectUnit = false
                    )
                }
            }
        }).expand()

    }


    /** Sending no unit clears the selected units entirely */
    fun selectUnit(unitView: MapUnitView? = null, append: Boolean = false) {
        worldScreen.dismissBattlefieldTileDetails()
        presenter = if (unitView != null) unitPresenter else summaryPresenter
        unitPresenter.selectUnit(unitView, append)
        resetUnitTable()
    }

    fun selectSpy(spyView: SpyView?) {
        presenter = spyPresenter
        spyPresenter.selectSpy(spyView)
        resetUnitTable()
    }

    fun citySelected(city: ForeignCityView): Boolean {
        worldScreen.dismissBattlefieldTileDetails()
        presenter = cityPresenter
        return cityPresenter.selectCity(city).also {
            resetUnitTable()
            worldScreen.shouldUpdate = true
        }
    }


    fun update() {
        closeButton.isVisible = true

        if (!presenter.shouldBeShown()) presenter = summaryPresenter
        presenter.update()

        // With no units of our own (e.g. spectators, or a civ that lost all units), the summary
        // (idle/skipping units) is meaningless - hide the whole table instead of showing an empty round artifact.
        isVisible = presenter != summaryPresenter || worldScreen.selectedGameView.civView.getUnits().isNotEmpty()

        // more efficient to do this check once for both
        if (worldScreen.selectedGameView.civView.getUnits().any { it.isIdle() }) {
            prevIdleUnitButton.enable()
            nextIdleUnitButton.enable()
        } else {
            prevIdleUnitButton.disable()
            nextIdleUnitButton.disable()
        }
        
        if (!shouldUpdate) return

        resetUnitTable()

        presenter.updateWhenNeeded()
        
        pack()
        closeButton.setPosition(
            width - closeButton.width * (if (UiNavigation.isMobile) 1f else .75f) - (if (UiNavigation.isMobile) 8f else 0f),
            height - closeButton.height * (if (UiNavigation.isMobile) 1f else .75f) - (if (UiNavigation.isMobile) 8f else 0f)
        )
        closeButton.toFront()
        bg.setSize(width - 3f, height - 3f)
        bg.center(this)
        shouldUpdate = false
    }

    /** Keep the existing presenters and callbacks, including rename, XP and promotion icons. */
    internal fun fitBattlefield(targetWidth: Float) {
        if (!BattlefieldHud.enabled) return
        val touch = UiNavigation.minimumTouchSize()
        unitNameLabel.setAlignment(Align.left)
        unitNameLabel.setFontSize(BattlefieldHud.font(TextRole.Supporting))
        unitNameLabel.setEllipsis(true)
        battlefieldSummary.setFontSize(BattlefieldHud.font(TextRole.CompactData))
        battlefieldSummary.color = com.unciv.models.skins.ExpeditionBlueTheme.muted
        battlefieldSummary.setEllipsis(true)
        val unit = selectedUnit
        battlefieldSummary.setText(when {
            unit != null -> Fonts.movement + unit.getMovementString() +
                if (unit.isCivilian()) "" else "   XP ${unit.xp.tr()}/${unit.xpForNextPromotion().tr()}"
            selectedCity != null -> "${"Strength".tr()} ${selectedCity!!.getDefendingStrength().tr()}"
            else -> ""
        })
        for (cell in unitIconHolder.cells) {
            cell.actor?.let { cell.setActor(BattlefieldGeometry.icon(it)) }
            cell.size(BattlefieldHud.points(22f)).pad(0f)
        }
        getCell(unitIconNameGroup).minWidth(0f).growX()
        pad(0f, BattlefieldHud.points(2f), 0f, touch)
        width = targetWidth
        invalidateHierarchy()
        validate()
        height = maxOf(prefHeight, touch)
        validate()
        BattlefieldGeometry.fitClose(closeButton, touch)
        // Keep the full touch target while removing the large outlined close tile.
        (closeButton as? Group)?.children?.forEach { child ->
            if (child is Button) BattlefieldGeometry.fitControl(child, flat = true)
            if (child is TextButton) child.label.setFontSize(BattlefieldHud.font(TextRole.Control))
        }
        closeButton.setPosition(width - touch, (height - touch) / 2f)
        closeButton.toFront()
    }

    /** Full presenter-owned details retain the original XP, promotion and Civilopedia callbacks. */
    private fun openBattlefieldDetails() {
        val details = Popup(worldScreen)
        val detailWidth = minOf(BattlefieldHud.points(340f), worldScreen.safeAreaBoundsInWorld().width - BattlefieldHud.points(32f))
        details.add(nameLabelText.toLabel(fontSize = BattlefieldHud.font(TextRole.Control))).width(detailWidth).row()
        for (label in descriptionTable.children.filterIsInstance<Label>())
            label.setFontSize(BattlefieldHud.font(TextRole.Control))
        details.add(descriptionTable).minWidth(0f).width(detailWidth).pad(BattlefieldHud.points(8f)).row()
        details.add(promotionsTable).width(detailWidth).row()
        if (worldScreen.canChangeState && (selectedUnit != null || selectedCity != null)) {
            details.addButton("Rename") {
                details.close()
                unitNameLabel.activate() // Reuse the presenter-owned rename handler.
            }.minHeight(UiNavigation.minimumTouchSize()).growX().row()
        }
        details.addCloseButton()
        details.closeListeners.add {
            descriptionTable.remove()
            promotionsTable.remove()
        }
        details.open()
    }

    private fun resetUnitTable() {
        unitIconHolder.clear()
        promotionsTable.clear()
        descriptionTable.clearListeners()
        // ImageWithCustomSize remembers width and returns if when Table asks for prefWidth
        separator.width = 0f
        shouldUpdate = true
    }

    fun tileSelected(selectedTileView: TileView, forceSelectUnitView: MapUnitView? = null) {
        if (!selectedTileView.isExplored()) return // We don't know anything that exists here!

        val previouslySelectedUnit = selectedUnit
        val previousNumberOfSelectedUnits = selectedUnits.size
        val curUnit = selectedUnit

        // Do not select a different unit or city center if we click on it to swap our current unit to it
        if (selectedUnitIsSwapping && curUnit != null && curUnit.canSwapTo(selectedTileView)) return
        // Do no select a different unit while in Air Sweep mode
        if (curUnit != null && curUnit.isPreparingAirSweep()) return

        val civView = worldScreen.selectedGameView.civView

        @Readonly
        fun ForeignMapUnitView.isEligible(): Boolean = (this.isOwnedByViewer() || civView.isSpectator())
                && this !in selectedUnits

        // This is the Civ 5 Order of selection:
        // 1. City
        // 2. GP + Settlers
        // 3. Military
        // 4. Other civilian (Workers)
        // 5. None (Deselect)
        // However we deviate from it because there was a poll on Discord that clearly showed that
        // people would prefer the military unit to always be preferred over GP, so we use this:
        // 1. City
        // 2. Military
        // 3. GP + Settlers
        // 4. Other civilian (Workers)
        // 5. None (Deselect)

        val civUnit = selectedTileView.civilianUnit
        val milUnit = selectedTileView.militaryUnit

        val nextUnitView: ForeignMapUnitView?
        val priorityUnit = when {
            milUnit != null && milUnit.isEligible() -> milUnit
            civUnit != null && civUnit.isEligible() -> civUnit
            else -> null
        }

        nextUnitView = when {
            curUnit == null -> priorityUnit
            curUnit == civUnit && milUnit != null && milUnit.isEligible() -> null
            curUnit == milUnit && civUnit != null && civUnit.isEligible() -> civUnit
            else -> priorityUnit
        }

        // Cache the city once - the underlying tile is live, shared state that can be mutated by
        // the next-turn thread (e.g. city razed) between the isCityCenter() check and its use
        val selectedTileCity = selectedTileView.owningCity()
        val isCitySelected = selectedTileView.isCityCenter()
            && selectedTileCity != null
            && (selectedTileCity.isOwnedByViewer() || civView.isSpectator())
            && !selectedUnitIsConnectingRoad
        when {
            forceSelectUnitView != null -> selectUnit(forceSelectUnitView)
            isCitySelected -> citySelected(selectedTileCity)
            nextUnitView != null -> selectUnit(nextUnitView.tryGetMapUnitView()!!, Gdx.input.isShiftKeyPressed())
            // toggle selection if same unit is clicked again by player
            selectedTileView == previouslySelectedUnit?.getTile() -> {
                selectUnit()
                shouldUpdate = true
            }
        }

        if (selectedUnit != previouslySelectedUnit || selectedUnits.size != previousNumberOfSelectedUnits)
            shouldUpdate = true
    }

    interface Presenter {
        /** map position of the selected entity */
        val position: HexCoord?
        /** called every time [WorldScreen] is updated */
        fun update() {}
        /** only called when [UnitTable.shouldUpdate] is true */
        fun updateWhenNeeded() {}
        fun shouldBeShown(): Boolean { return true}
    }

    override fun act(delta: Float) = super.act(delta)
    override fun draw(batch: Batch?, parentAlpha: Float) = super.draw(batch, parentAlpha)
    override fun hit(x: Float, y: Float, touchable: Boolean): Actor? = super.hit(x, y, touchable)
}
