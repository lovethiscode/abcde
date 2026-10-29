package com.unciv.ui.screens.worldscreen.status

import com.unciv.ui.components.fonts.UiTypography
import com.unciv.ui.components.fonts.TextRole
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.ui.Cell
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.TextButton.TextButtonStyle
import com.unciv.models.skins.ExpeditionBlueTheme
import com.unciv.logic.civilization.managers.TurnManager
import com.unciv.models.translations.tr
import com.unciv.ui.components.UncivTooltip.Companion.addTooltip
import com.unciv.ui.components.UncivTooltip.Companion.removeTooltips
import com.unciv.ui.components.extensions.isEnabled
import com.unciv.ui.components.extensions.setSize
import com.unciv.ui.components.extensions.surroundWithCircle
import com.unciv.ui.components.input.KeyboardBinding
import com.unciv.ui.components.input.keyShortcuts
import com.unciv.ui.components.input.onActivation
import com.unciv.ui.images.IconTextButton
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.popups.AnimatedMenuPopup.Companion.addContextMenu
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.worldscreen.WorldScreen
import com.unciv.ui.screens.worldscreen.BattlefieldHud
import com.unciv.ui.screens.worldscreen.BattlefieldGeometry
import com.unciv.ui.components.widgets.UiNavigation
import com.unciv.ui.components.extensions.setFontSize
import com.unciv.ui.screens.worldscreen.status.NextTurnAction.Default
import com.unciv.utils.withGLContext
import yairm210.purity.annotations.Readonly

class NextTurnButton(
    private val worldScreen: WorldScreen
) : IconTextButton("", null, 30, labelFontSize = UiTypography.size(TextRole.Control)) {
    private var nextTurnAction = Default
    private val disabledIconColor = Color.valueOf("B8C7CA")
    val currentAction: NextTurnAction get() = nextTurnAction
    private val unitsDueLabel = Label("", BaseScreen.skin)
    private val unitsDueCell: Cell<Label>

    init {
        name = "ui-next-turn"
        pad(15f)
        onActivation { nextTurnAction.action(worldScreen) }
        addContextMenu { NextTurnMenu(stage, this, worldScreen) }
        keyShortcuts.add(KeyboardBinding.NextTurn)
        keyShortcuts.add(KeyboardBinding.NextTurnAlternate)
        labelCell.row()
        unitsDueCell = add(unitsDueLabel).padTop(6f).colspan(2).center()
    }

    fun update() {
        nextTurnAction = getNextTurnAction(worldScreen)
        updateButton(nextTurnAction)
        val autoPlay = worldScreen.autoPlay
        if (worldScreen.gameInfo.allowsPlayerAutoPlay() && autoPlay.shouldContinueAutoPlaying() && worldScreen.isPlayersTurn
            && !worldScreen.waitingForAutosave && !worldScreen.isNextTurnUpdateRunning()) {
            autoPlay.runAutoPlayJobInNewThread("MultiturnAutoPlay", worldScreen, false) {
                TurnManager(worldScreen.selectedGameView.civView.getCiv()).automateTurn()
                withGLContext { worldScreen.nextTurn(fromAutoPlay = true) }
                autoPlay.endTurnMultiturnAutoPlay()
            }
        }

        isEnabled = nextTurnAction.getText(worldScreen) == "AutoPlay"
            || ((worldScreen.isPlayersTurn || worldScreen.failedUpload) && !worldScreen.waitingForAutosave && !worldScreen.isNextTurnUpdateRunning())
        if (ExpeditionBlueTheme.enabled && nextTurnAction == NextTurnAction.NextTurn)
            iconCell.actor?.color = if (isEnabled) ExpeditionBlueTheme.primaryInk else disabledIconColor
        if (isEnabled) {
            addTooltip(KeyboardBinding.NextTurn)
        } else {
            removeTooltips()
        }

        worldScreen.smallUnitButton.update()
    }

    internal fun updateButton(nextTurnAction: NextTurnAction) {
        isChecked = false
        label.setText(nextTurnAction.getText(worldScreen).tr())
        val primary = ExpeditionBlueTheme.enabled && nextTurnAction == NextTurnAction.NextTurn
        val foreground = if (primary) ExpeditionBlueTheme.primaryInk
            else if (ExpeditionBlueTheme.enabled && nextTurnAction == NextTurnAction.FoundReligion) ExpeditionBlueTheme.danger
            else if (ExpeditionBlueTheme.enabled && nextTurnAction == NextTurnAction.WorldCongressVote) ExpeditionBlueTheme.text
            else ExpeditionBlueTheme.labelColor(nextTurnAction.color)
        if (ExpeditionBlueTheme.enabled) style = BaseScreen.skin.get(if (primary) "positive" else "default", TextButtonStyle::class.java)
        label.color = foreground
        unitsDueLabel.color = if (primary) ExpeditionBlueTheme.primaryInk else ExpeditionBlueTheme.color(com.badlogic.gdx.graphics.Color.WHITE, ExpeditionBlueTheme.text)
        if (nextTurnAction.icon != null && ImageGetter.imageExists(nextTurnAction.icon!!)) {
            val image = ImageGetter.getImage(nextTurnAction.icon).apply {
                setSize(30f)
                color = if (ExpeditionBlueTheme.enabled) when (nextTurnAction) {
                    NextTurnAction.FoundReligion -> ExpeditionBlueTheme.danger
                    NextTurnAction.PickConstruction, NextTurnAction.WorldCongressVote -> Color.WHITE
                    else -> foreground
                } else foreground
            }
            iconCell.setActor(if (ExpeditionBlueTheme.enabled && nextTurnAction == NextTurnAction.PickConstruction)
                image.apply { setSize(BattlefieldHud.points(18f)) }
                    .surroundWithCircle(BattlefieldHud.points(20f), resizeActor = false, color = ExpeditionBlueTheme.text)
            else image)
        } else
            iconCell.clearActor()

        nextTurnAction.getSubText(worldScreen)?.let {
            unitsDueLabel.setText(it.tr())
            unitsDueCell.setActor(unitsDueLabel)
        } ?: unitsDueCell.clearActor()

        pack()
    }

    private fun getNextTurnAction(worldScreen: WorldScreen) =
        // Guaranteed to return a non-null NextTurnAction because the last isChoice always returns true
        NextTurnAction.entries.first { it.isChoice(worldScreen) }

    @Readonly fun isNextUnitAction(): Boolean = nextTurnAction == NextTurnAction.NextUnit

    internal fun fitBattlefield(targetWidth: Float) {
        BattlefieldGeometry.fitControl(this, primary = nextTurnAction == NextTurnAction.NextTurn && isEnabled)
        label.setFontSize(BattlefieldHud.font(TextRole.Control))
        unitsDueLabel.setFontSize(BattlefieldHud.font(TextRole.CompactData))
        label.wrap = true
        labelCell.minWidth(0f).growX()
        iconCell.size(BattlefieldHud.points(20f)).padRight(BattlefieldHud.points(5f))
        pad(BattlefieldHud.points(6f))
        width = targetWidth
        invalidate()
        validate()
        height = maxOf(UiNavigation.minimumTouchSize(), prefHeight)
        validate()
    }

}
