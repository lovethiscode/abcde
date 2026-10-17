package com.unciv.ui.screens.worldscreen

import com.unciv.ui.components.fonts.UiTypography
import com.unciv.ui.components.fonts.TextRole
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.unciv.Constants
import com.unciv.models.skins.ExpeditionBlueTheme
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.input.KeyboardBinding
import com.unciv.ui.components.input.keyShortcuts
import com.unciv.ui.components.input.onActivation
import com.unciv.ui.screens.basescreen.BaseScreen

class PlayerReadyScreen(worldScreen: WorldScreen) : BaseScreen() {
    init {
        val table = Table()
        table.touchable = Touchable.enabled
        val curCiv = worldScreen.selectedGameView.civView
        table.background = skinStrings.getUiBackground(
            "PlayerReadyScreen/Background",
            tintColor = curCiv.getOuterColor()
        )

        table.add("[${curCiv.civName}] ready?".toLabel(
            ExpeditionBlueTheme.color(curCiv.getInnerColor(), ExpeditionBlueTheme.text), UiTypography.size(TextRole.PageTitle, legacySize = Constants.headingFontSize)))

        table.onActivation {
            game.replaceCurrentScreen{ worldScreen }
        }
        // Doing this separately instead of passing the binding to onActivation avoids the tooltip
        table.keyShortcuts.add(KeyboardBinding.NextTurnAlternate)
        table.setFillParent(true)
        stage.addActor(table)
    }
}
