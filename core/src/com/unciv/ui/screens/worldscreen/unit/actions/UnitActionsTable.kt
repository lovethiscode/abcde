package com.unciv.ui.screens.worldscreen.unit.actions

import com.unciv.ui.components.fonts.UiTypography
import com.unciv.ui.components.fonts.TextRole
import com.unciv.models.skins.ExpeditionBlueTheme
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.math.Interpolation
import com.badlogic.gdx.math.Vector2
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.actions.Actions
import com.badlogic.gdx.scenes.scene2d.ui.Button
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.unciv.UncivGame
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.models.UpgradeUnitAction
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.brighten
import com.unciv.ui.components.extensions.disable
import com.unciv.ui.components.input.keyShortcuts
import com.unciv.ui.components.input.KeyCharAndCode
import com.unciv.ui.components.widgets.AutoScrollPane
import com.unciv.ui.components.input.onActivation
import com.unciv.ui.images.IconTextButton
import com.unciv.ui.popups.AnimatedMenuPopup.Companion.addContextMenu
import com.unciv.ui.popups.ConfirmPopup
import com.unciv.ui.popups.UnitUpgradeMenu
import com.unciv.ui.popups.Popup
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.screens.worldscreen.WorldScreen
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.cityscreen.CityScreen
import com.unciv.ui.screens.worldscreen.BattlefieldHud
import com.unciv.ui.screens.worldscreen.BattlefieldGeometry
import com.unciv.ui.components.widgets.UiNavigation
import com.unciv.ui.components.extensions.setFontSize
import yairm210.purity.annotations.Readonly

class UnitActionsTable(val worldScreen: WorldScreen) : Table() {
    internal var battlefieldWidth = 0f
    internal var battlefieldColumns = 1
    internal var battlefieldAvailableHeight = 0f
    /** Distribute UnitActions on "pages" */
    private var currentPage = 0
    private var buttonsPerPage = Int.MAX_VALUE
    private var numPages = 2
    private var shownForUnitHash = 0
    private var animationNotFinished = false
    private var battlefieldMenu: Popup? = null
    private val battlefieldSlotAppearance = arrayOfNulls<UiUnitAction>(3)

    companion object {
        /** Maximum for how many pages there can be. ([minButtonsPerPage]-1)*[maxAllowedPages]
         *  is the upper bound for how many actions a unit can display. */
        private const val maxAllowedPages = 10
        /** Lower bound for how many buttons to distribute per page, including navigation buttons.
         *  Affects really cramped displays. */
        // less than navigation buttons + 2 makes little sense, and setting it to 4 isn't necessary.
        private const val minButtonsPerPage = 3
        /** Upper bound for how many buttons to distribute per page, including navigation buttons.
         *  Affects large displays, resulting in more map visible between the actions and tech/diplo/policy buttons. */
        private const val maxButtonsPerPage = 7
        /** Maximum number of buttons to present without paging, overriding page preferences (implementation currently limited to merging two pages) */
        private const val maxSinglePageButtons = 5
        /** Padding between and to the left of the Buttons */
        private const val padBetweenButtons = 2f
    }

    init {
        defaults().left().padLeft(padBetweenButtons).padBottom(padBetweenButtons)
    }

    fun changePage(delta: Int, unit: MapUnit) {
        if (delta == 0 || numPages <= 1) return
        currentPage = (currentPage + delta) % numPages
        update(unit)
    }

    fun update(unit: MapUnit?) {
        val newUnitHash = unit?.hashCode() ?: 0
        var unitChanged = false
        if (shownForUnitHash != newUnitHash) {
            battlefieldMenu?.close()
            battlefieldMenu = null
            unitChanged = true
            currentPage = 0
            battlefieldSlotAppearance.fill(null)
            shownForUnitHash = newUnitHash
        }

        clear()
        keyShortcuts.clear()
        if (unit == null) return
        if (!worldScreen.canChangeState) return // No actions when it's not your turn or spectator!
        if (BattlefieldHud.enabled) {
            updateBattlefield(unit)
            return
        }

        numPages = 0
        val pageActionBuckets = Array<ArrayDeque<UiUnitAction>>(maxAllowedPages) { ArrayDeque() }
        
        @Readonly
        fun freeSlotsOnPage(page: Int) = buttonsPerPage -
            pageActionBuckets[page].size -
            (if (numPages > 1) 1 else 0) // room for the navigation buttons

        val (nextPageAction, previousPageAction) = UiUnitActions.getPagingActions(unit, this)
        val nextPageButton = getUnitActionButton(unit, nextPageAction)
        val previousPageButton = getUnitActionButton(unit, previousPageAction)
        updateButtonsPerPage(nextPageButton)

        val sortedUnitActions = UiUnitActions.getUnitActions(unit).sortedByDescending { it.useFrequency }
        // Distribute sequentially into the buckets
        for (unitAction in sortedUnitActions) {
            var actionPage = UiUnitActions.getActionDefaultPage(unit, unitAction.uiType)
            while (actionPage < maxAllowedPages && freeSlotsOnPage(actionPage) <= 0)
                actionPage++
            if (actionPage >= maxAllowedPages) break
            if (actionPage >= numPages) numPages = actionPage + 1
            pageActionBuckets[actionPage].addLast(unitAction)
        }
        // Due to room reserved for paging buttons changing, buckets may now be too full
        for (page in 0 until maxAllowedPages - 1) {
            while (freeSlotsOnPage(page) < 0) {
                val element = pageActionBuckets[page].removeLast()
                pageActionBuckets[page + 1].addFirst(element)
                if (numPages < page + 2) numPages = page + 2
            }
        }
        // Special case: Only the default two pages used and all actions would fit in one
        if (numPages == 2 && buttonsPerPage >= maxSinglePageButtons && pageActionBuckets[0].size + pageActionBuckets[1].size <= maxSinglePageButtons) {
            pageActionBuckets[0].addAll(pageActionBuckets[1])
            pageActionBuckets[1].clear()
            numPages = 1
        }

        // clamp currentPage
        if (currentPage !in 0 until numPages) currentPage = 0
        // actually show the buttons of the currentPage
        var visibleButtons = 0
        fun place(button: Button) {
            if (!BattlefieldHud.enabled) {
                add(button).colspan(2).row()
                return
            }
            val gap = BattlefieldHud.points(4f)
            val cellWidth = (battlefieldWidth - gap * (battlefieldColumns - 1)) / battlefieldColumns
            val lastColumn = (visibleButtons + 1) % battlefieldColumns == 0
            add(button).width(cellWidth).minHeight(UiNavigation.minimumTouchSize()).pad(0f, 0f, 0f, if (lastColumn) 0f else gap)
            visibleButtons++
            if (visibleButtons % battlefieldColumns == 0) row()
        }
        for (unitAction in pageActionBuckets[currentPage]) {
            val button = getUnitActionButton(unit, unitAction)
            val upgradeAction = unitAction.unitAction as? UpgradeUnitAction
            if (upgradeAction != null) {
                // This is bound even when the button is disabled, but Actor.activate in ActivationExtensions will block any activation for disabled actors...
                // But the menu is built to be useful even when you can't upgrade - so **hack** it to get the handler through.
                // Works because our disable() extension also changes style, and because the normal click is ignored due to unitAction.action being null.
                button.isDisabled = false
                button.touchable = Touchable.enabled
                button.addContextMenu {
                    UnitUpgradeMenu(worldScreen.stage, button, worldScreen.selectedGameView.getMapUnitView(unit), upgradeAction.upgrade, enable = unitAction.action != null, callbackAfterAnimation = true) {
                        worldScreen.shouldUpdate = true
                    }
                }
            }
            place(button)
        }

        // show page navigation
        if (currentPage > 0) {
            if (BattlefieldHud.enabled) place(previousPageButton) else add(previousPageButton)
        }
        if (currentPage < numPages - 1) {
            if (BattlefieldHud.enabled) place(nextPageButton) else add(nextPageButton)
        }
        
        // Animate buttons
        if (!BattlefieldHud.enabled && UncivGame.Current.settings.unitActionsTableAnimation) {
            if (unitChanged || animationNotFinished) {
                val buttons = children.filterIsInstance<Button>()
                var delay = 0f
                for (button in buttons) {
                    animateButton(button, delay)
                    delay += 0.05f
                }
            }
        }

        pack()

        // Bind all currently invisible actions to their keys
        for (page in pageActionBuckets.indices) {
            if (page == currentPage) continue // these are already done
            for (unitAction in pageActionBuckets[page]) {
                if (unitAction.action == null) continue
                keyShortcuts.add(unitAction.uiType.binding) {
                    activateAction(unitAction, unit)
                }
            }
        }
    }

    /** Three stable common-command slots and one expansion; disabled actions keep their place. */
    private fun updateBattlefield(unit: MapUnit) {
        val actions = UiUnitActions.getUnitActions(unit).toList()
        fun first(vararg types: UiUnitActionType) = types.firstNotNullOfOrNull { type -> actions.firstOrNull { it.uiType == type } }
        val role = first(UiUnitActionType.ConstructImprovement, UiUnitActionType.FoundCity,
            UiUnitActionType.CreateImprovement, UiUnitActionType.SpreadReligion, UiUnitActionType.FoundReligion,
            UiUnitActionType.HurryResearch, UiUnitActionType.HurryWonder, UiUnitActionType.ConductTradeMission,
            UiUnitActionType.SetUp, UiUnitActionType.FortifyUntilHealed, UiUnitActionType.Fortify,
            UiUnitActionType.SleepUntilHealed, UiUnitActionType.Sleep)
        val context = if (unit.cache.hasUniqueToBuildImprovements)
            first(UiUnitActionType.StopAutomation, UiUnitActionType.Automate, UiUnitActionType.Repair)
        else first(UiUnitActionType.Promote, UiUnitActionType.StopMovement,
            UiUnitActionType.StopExploration, UiUnitActionType.Explore, UiUnitActionType.Upgrade)
        val primary = listOf(role, context?.takeUnless { it === role }, first(UiUnitActionType.Skip))
        val secondary = actions.filter { it !in primary }.sortedByDescending { it.useFrequency }
        val touch = UiNavigation.minimumTouchSize()
        val gap = BattlefieldHud.points(4f)
        val buttons = primary.mapIndexed { index, action ->
            // Some rules remove an unavailable command entirely. Keep its visual slot without
            // retaining a callback from an earlier unit state.
            if (action != null) battlefieldSlotAppearance[index] = UiUnitAction(action.uiType, title = action.title)
            val displayed = action ?: battlefieldSlotAppearance[index]
            if (displayed != null) getUnitActionButton(unit, displayed)
            else Button(BaseScreen.skin).apply {
                BattlefieldGeometry.fitControl(this, flat = true)
                isDisabled = true
                touchable = Touchable.enabled
            }
        }.toMutableList()
        if (secondary.isNotEmpty()) {
            val more = IconTextButton("Show more",
                BattlefieldGeometry.icon(ImageGetter.getUnitActionPortrait("ShowMore")), BattlefieldHud.font(TextRole.Supporting), labelFontSize = UiTypography.size(TextRole.Control)).apply {
                name = "ui-unit-more"
                BattlefieldGeometry.fitControl(this, flat = true)
                pad(BattlefieldHud.points(2f))
                iconCell.size(BattlefieldHud.points(18f)).padRight(BattlefieldHud.points(5f))
                label.wrap = true
                labelCell.minWidth(0f).growX()
                onActivation {
                    battlefieldMenu?.let { it.close(); return@onActivation }
                    openBattlefieldMenu(this, unit, secondary)
                }
            }
            buttons.add(more)
        }
        // Wrap phrases between words, rather than forcing a word such as "Automate"
        // or "improvement" into narrow fragments beside its icon.
        val requiredWidth = buttons.filterIsInstance<IconTextButton>().maxOfOrNull { button ->
            val wordWidth = button.label.text.split(Regex("\\s+")).maxOfOrNull { word ->
                com.badlogic.gdx.graphics.g2d.GlyphLayout(button.label.style.font, word).width * button.label.fontScaleX
            } ?: 0f
            wordWidth + BattlefieldHud.points(27f)
        } ?: touch
        val maximumColumns = minOf(battlefieldColumns.coerceAtLeast(1), buttons.size,
            ((battlefieldWidth + gap) / (requiredWidth + gap)).toInt().coerceAtLeast(1))
        val columns = (maximumColumns downTo 1).first { buttons.size % it == 0 }
        val commandWidth = (battlefieldWidth - gap * (columns - 1)) / columns
        buttons.forEachIndexed { index, button ->
            val lastColumn = (index + 1) % columns == 0
            add(button).width(commandWidth).minHeight(touch)
                .pad(0f, 0f, 0f, if (lastColumn) 0f else gap)
            if (lastColumn) row()
        }
        for (action in secondary) if (action.action != null)
            keyShortcuts.add(action.uiType.binding) { activateAction(action, unit) }
        pack()
    }

    /** An undimmed, button-anchored menu; its transparent input shield consumes dismissal taps. */
    private fun openBattlefieldMenu(anchor: IconTextButton, unit: MapUnit, actions: List<UiUnitAction>) {
        val gap = BattlefieldHud.points(4f)
        val touch = UiNavigation.minimumTouchSize()
        val safe = worldScreen.safeAreaBoundsInWorld()
        val menuWidth = minOf(BattlefieldHud.points(220f), safe.width - gap * 2f)
        val unitPanel = worldScreen.bottomUnitTable
        // The anchor belongs to the command area below the unit header. Opening directly above
        // that button covers the identity of the unit receiving these orders.
        val besidePanel = unitPanel.x + unitPanel.width + gap + menuWidth <= safe.x + safe.width
        val menuBottom = if (besidePanel) y else unitPanel.y + unitPanel.height + gap
        val menuTop = minOf(safe.y + safe.height, worldScreen.topBar.y) - gap
        val content = Table().apply {
            defaults().growX().minHeight(touch).padBottom(gap)
            for (action in actions) add(getUnitActionButton(unit, action)).width(menuWidth - gap * 2f).row()
        }
        val menuHeight = minOf(content.prefHeight, (menuTop - menuBottom - gap * 2f).coerceAtLeast(touch))
        val popup = object : Popup(worldScreen, Scrollability.None) {
            init {
                name = "ui-unit-more-menu"
                background = null
                innerTable.pad(gap)
                innerTable.defaults().pad(0f)
                add(AutoScrollPane(content).apply {
                    setScrollingDisabled(true, false)
                    setOverscroll(false, false)
                    fadeScrollBars = false
                }).width(menuWidth - gap * 2f).height(menuHeight)
                keyShortcuts.add(KeyCharAndCode.BACK) { close() }
                val menu = this
                addListener(object : ClickListener() {
                    override fun clicked(event: InputEvent?, x: Float, y: Float) {
                        if (event?.target === menu) close()
                    }
                })
            }

            override fun layout() {
                super.layout()
                val point = anchor.localToStageCoordinates(Vector2())
                val x = (if (besidePanel) unitPanel.x + unitPanel.width + gap
                    else point.x + anchor.width - innerTable.width)
                    .coerceIn(safe.x, safe.x + safe.width - innerTable.width)
                innerTable.setPosition(x, menuBottom.coerceIn(safe.y, safe.y + safe.height - innerTable.height))
            }

            override fun act(delta: Float) {
                super.act(delta)
                if (anchor.stage !== stageToShowOn || !worldScreen.canChangeState) close()
            }
        }
        battlefieldMenu = popup
        anchor.label.setText("Show less".tr())
        popup.closeListeners.add {
            anchor.label.setText("Show more".tr())
            if (battlefieldMenu === popup) battlefieldMenu = null
        }
        popup.open()
    }

    internal fun updateBattlefieldCity() {
        clear()
        val city = worldScreen.bottomUnitTable.selectedCity?.tryGetCityView() ?: return
        val button = IconTextButton("City", ImageGetter.getImage("OtherIcons/Cities"), BattlefieldHud.font(TextRole.Supporting), labelFontSize = UiTypography.size(TextRole.Control)).apply {
            name = "ui-context-city-open"
            BattlefieldGeometry.fitControl(this, flat = true)
            iconCell.size(BattlefieldHud.points(18f))
            onActivation { worldScreen.game.pushScreen { CityScreen(city) } }
        }
        add(button).width(battlefieldWidth).minHeight(UiNavigation.minimumTouchSize())
        pack()
    }

    private fun updateButtonsPerPage(button: Button) {
        if (BattlefieldHud.enabled) {
            buttonsPerPage = if (battlefieldColumns > 1) 6 else
                (battlefieldAvailableHeight / (UiNavigation.minimumTouchSize() + BattlefieldHud.points(4f)))
                    .toInt().coerceIn(minButtonsPerPage, maxButtonsPerPage)
            return
        }
        val upperLimit = worldScreen.techPolicyAndDiplomacy.y
        val lowerLimit = this.y
        val availableHeight = upperLimit - lowerLimit - padBetweenButtons
        val buttonHeight = button.height + padBetweenButtons
        buttonsPerPage = (availableHeight / buttonHeight).toInt().coerceIn(minButtonsPerPage, maxButtonsPerPage)
    }

    private fun getUnitActionButton(unit: MapUnit, unitAction: UiUnitAction): Button {
        val artwork = unitAction.getIcon()
        val icon = if (BattlefieldHud.enabled) BattlefieldGeometry.icon(artwork) else artwork
        // If peripheral keyboard not detected, hotkeys will not be displayed
        val binding = unitAction.uiType.binding

        val fontColor = if (unitAction.isCurrentAction) ExpeditionBlueTheme.color(Color.YELLOW, ExpeditionBlueTheme.gold) else Color.WHITE
        val actionButton = IconTextButton(unitAction.title, icon, fontColor = fontColor, labelFontSize = UiTypography.size(TextRole.Control))
        actionButton.name = "ui-unit-action:${unitAction.uiType.name}"
        val upgradeAction = unitAction.unitAction as? UpgradeUnitAction
        if (upgradeAction != null) actionButton.name += ":${upgradeAction.unitToUpgradeTo.name}"
        if (unitAction.uiType == UiUnitActionType.CreateImprovement) actionButton.name += ":${unitAction.unitAction?.actionKey}"
        actionButton.labelCell.padTop(0f) // aligned with icon 
        if (BattlefieldHud.enabled) {
            BattlefieldGeometry.fitControl(actionButton, flat = true)
            actionButton.label.setFontSize(BattlefieldHud.font(TextRole.Control))
            actionButton.label.wrap = true
            actionButton.labelCell.minWidth(0f).growX()
            actionButton.iconCell.size(BattlefieldHud.points(18f)).padRight(BattlefieldHud.points(5f))
            actionButton.pad(BattlefieldHud.points(2f))
        }

        if (unitAction.uiType == UiUnitActionType.Promote && unitAction.action != null)
            if (ExpeditionBlueTheme.enabled) actionButton.label.color = ExpeditionBlueTheme.gold
            else actionButton.color = Color.GREEN.brighten(0.5f)

        if (BattlefieldHud.enabled && unitAction.uiType == UiUnitActionType.DisbandUnit && unitAction.action != null)
            actionButton.label.color = ExpeditionBlueTheme.danger

        if (BattlefieldHud.enabled && battlefieldWidth > 0f) {
            val gap = BattlefieldHud.points(4f)
            actionButton.setSize((battlefieldWidth - gap * (battlefieldColumns - 1)) / battlefieldColumns,
                UiNavigation.minimumTouchSize())
            actionButton.validate()
        } else actionButton.pack()

        if (unitAction.action == null) {
            actionButton.disable()
            // A disabled command must absorb taps instead of targeting the map beneath it.
            if (BattlefieldHud.enabled) {
                // disable() replaces the style; retain the native command's flat geometry.
                BattlefieldGeometry.fitControl(actionButton, flat = true)
                if (upgradeAction == null) actionButton.color.a = 0.5f
                actionButton.touchable = Touchable.enabled
            }
        } else {
            actionButton.onActivation(unitAction.uncivSound, binding) {
                activateAction(unitAction, unit)
            }
        }

        if (BattlefieldHud.enabled && upgradeAction != null) {
            actionButton.isDisabled = false
            actionButton.touchable = Touchable.enabled
            actionButton.addContextMenu {
                UnitUpgradeMenu(worldScreen.stage, actionButton, worldScreen.selectedGameView.getMapUnitView(unit),
                    upgradeAction.upgrade, enable = unitAction.action != null, callbackAfterAnimation = true) {
                    worldScreen.shouldUpdate = true
                }
            }
        }
        return actionButton
    }

    private fun activateAction(unitAction: UiUnitAction, unit: MapUnit) {
        battlefieldMenu?.close()
        battlefieldMenu = null
        val confirmation = unitAction.confirmation
        if (confirmation == null) {
            invokeAction(unitAction, unit)
            return
        }
        ConfirmPopup(worldScreen, confirmation.question, confirmation.confirmText,
            presentation = if (unitAction.uiType == UiUnitActionType.DisbandUnit) ConfirmPopup.Presentation(
                "Disband [${unit.name}]?", "Unit action", "Disbanding removes this unit from the map.",
                if (unit.currentTile.getOwner() == unit.civ) listOf("Gold received" to unit.baseUnit.getDisbandGold(unit.civ).toString()) else emptyList()
            ) else null) {
            invokeAction(unitAction, unit)
        }.open()
    }

    private fun invokeAction(unitAction: UiUnitAction, unit: MapUnit) {
        unitAction.action!!.invoke()
        worldScreen.shouldUpdate = true
        // We keep the unit action/selection overlay from the previous unit open even when already selecting another unit
        // so you need less clicks/touches to do things, but once we do an action with the new unit, we want to close this
        // overlay, since the user definitely wants to interact with the new unit.
        worldScreen.mapHolder.removeUnitActionOverlay()
        if (!UncivGame.Current.settings.autoUnitCycle) return
        if (unit.isDestroyed || 
            unitAction.uiType.isSkippingToNextUnit && (!unit.isMoving() || !unit.hasMovement()))
            worldScreen.switchToNextUnit()
        else worldScreen.bottomUnitTable.shouldUpdate = true
    }

    private fun animateButton(button: Button, delay: Float) {
        button.color.a = 0f
        button.addAction(Actions.sequence(
            Actions.run { animationNotFinished = true },
            Actions.run {
                button.addAction(Actions.moveBy(-150f, 0f))
                button.addAction(Actions.alpha(1f, 0.15f))
                button.addAction(Actions.delay(delay, Actions.moveBy(150f, 0f, 0.15f, Interpolation.smooth)))
            },
            Actions.run { animationNotFinished = false }
        ))
    }
}
