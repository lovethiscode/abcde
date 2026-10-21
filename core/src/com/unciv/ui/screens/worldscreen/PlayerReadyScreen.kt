package com.unciv.ui.screens.worldscreen

import com.unciv.ui.components.fonts.TextRole
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.unciv.ui.components.input.KeyboardBinding
import com.unciv.ui.components.input.keyShortcuts
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.overviewscreen.OverviewUi

class PlayerReadyScreen(worldScreen: WorldScreen) : BaseScreen() {
    init {
        val curCiv = worldScreen.selectedGameView.civView
        val continueButton = OverviewUi.primaryButton("Continue") {
            game.replaceCurrentScreen{ worldScreen }
        }.apply {
            name = "ui-player-ready-continue"
            keyShortcuts.add(KeyboardBinding.NextTurnAlternate)
        }

        val cardWidth = OverviewUi.pt(460f)
        val card = Table().apply {
            background = OverviewUi.rounded(OverviewUi.panel, 14f)
            pad(OverviewUi.pt(24f))
            add(ImageGetter.getNationPortrait(curCiv.getCiv().nation, OverviewUi.pt(64f)))
                .size(OverviewUi.pt(64f)).padBottom(OverviewUi.pt(16f)).row()
            add(OverviewUi.plainLabel("[${curCiv.civName}] ready?", TextRole.PageTitle).apply {
                wrap = true
            }).growX().minWidth(0f).maxWidth(cardWidth - OverviewUi.pt(48f))
                .padBottom(OverviewUi.pt(18f)).row()
            add(continueButton).growX().minWidth(0f).maxWidth(OverviewUi.pt(240f))
                .minHeight(OverviewUi.touch)
        }

        stage.addActor(Table().apply {
            setFillParent(true)
            background = OverviewUi.surface(OverviewUi.background)
            add(card).growX().minWidth(0f).maxWidth(cardWidth).pad(OverviewUi.pt(16f))
        })
    }
}
