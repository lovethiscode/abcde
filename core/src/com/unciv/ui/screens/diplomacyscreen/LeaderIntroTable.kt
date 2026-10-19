package com.unciv.ui.screens.diplomacyscreen

import com.unciv.ui.components.fonts.UiTypography
import com.unciv.ui.components.fonts.TextRole
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.utils.Align
import com.unciv.ui.components.extensions.setFontSize
import com.unciv.Constants
import com.unciv.UncivGame
import com.unciv.logic.civilization.Civilization
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.input.onClick
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.screens.basescreen.BaseScreen

/**
 * This is meant to be used for any kind of civ introduction - [DiplomacyScreen],
 * [AlertPopup][com.unciv.ui.worldscreen.AlertPopup] [types][com.unciv.logic.civilization.AlertType] WarDeclaration, FirstContact etc.
 *
 * @param civInfo The civilization to display
 * @param hello Optional additional message
 */
class LeaderIntroTable (
    civInfo: Civilization,
    hello: String = "",
    popupStage: com.badlogic.gdx.scenes.scene2d.Stage? = null
): Table(BaseScreen.skin) {
    /**
     * Build either a Table(icon, leaderName <br> hello) or
     * a Table(Portrait, Table(leaderName, icon <br> hello))
     *
     * City states in vanilla have leaderName=="" - but don't test CS, test leaderName to allow modding CS to have portraits
     */
    init {
        defaults().align(Align.center)
        val nation = civInfo.nation
        val leaderPortraitFile = "LeaderIcons/" + nation.leaderName
        val leaderLabel = civInfo.getLeaderDisplayName().toLabel(fontSize = UiTypography.size(TextRole.PageTitle, legacySize = Constants.headingFontSize), hideIcons = true)
        val nationIndicator = ImageGetter.getNationPortrait(nation, 24f)
        if (popupStage != null) {
            val unit = UiTypography.unitsPerPoint(popupStage)
            leaderLabel.setFontSize(UiTypography.size(TextRole.PageTitle, unit))
            leaderLabel.wrap = true
            leaderLabel.setAlignment(Align.left)
            val identity = Table().apply {
                add(leaderLabel).growX().minWidth(0f).left().row()
                if (hello.isNotEmpty()) add(hello.toLabel(fontSize = UiTypography.size(TextRole.Body, unit))
                    .apply { wrap = true }).growX().minWidth(0f).left()
            }
            val portrait = if (nation.leaderName.isNotEmpty() && ImageGetter.imageExists(leaderPortraitFile))
                ImageGetter.getImage(leaderPortraitFile) else ImageGetter.getNationPortrait(nation, 56f * unit)
            add(portrait).size(56f * unit).top().padRight(12f * unit)
            add(identity).growX().minWidth(0f)
        } else if (nation.leaderName.isNotEmpty() && ImageGetter.imageExists(leaderPortraitFile)) {
            val nameTable = Table()
            nameTable.add(leaderLabel)
            nameTable.add(nationIndicator).pad(0f, 10f, 5f, 0f).row()
            if (hello.isNotEmpty())
                nameTable.add(hello.toLabel(TextRole.Body)).colspan(2)
            add(ImageGetter.getImage(leaderPortraitFile)).size(100f)
                .padRight(10f)
            add(nameTable)
        } else {
            add(nationIndicator).pad(0f, 0f, 5f, 10f)
            add(leaderLabel).row()
            if (hello.isNotEmpty())
                add(hello.toLabel(TextRole.Body)).colspan(2)
        }

        onClick {
            UncivGame.Current.screen?.openCivilopedia(civInfo.nation.makeLink())
        }
    }
}
