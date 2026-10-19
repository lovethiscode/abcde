package com.unciv.ui.screens.pickerscreens

import com.unciv.ui.components.fonts.TextRole
import com.unciv.ui.components.fonts.UiTypography
import com.unciv.ui.components.widgets.UiNavigation
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.utils.Align
import com.unciv.logic.civilization.CivFlags
import com.unciv.logic.civilization.Civilization
import com.unciv.models.UncivSound
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.enable
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.input.KeyCharAndCode
import com.unciv.ui.components.input.keyShortcuts
import com.unciv.ui.components.input.onActivation
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.popups.Popup
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.basescreen.RecreateOnResize

class DiplomaticVoteResultScreen(
    private val votesCast: HashMap<String, String?>,
    private val viewingCiv: Civilization
) : OrdinaryGamePopupScreen(), RecreateOnResize {
    val gameInfo = viewingCiv.gameInfo
    private val ordinary = UiNavigation.isIos && gameInfo.campaign == null && gameInfo.endless == null
    private val unit get() = UiTypography.unitsPerPoint(stage)
    private val resultWidth get() = minOf(stage.width - 40f * unit, 560f * unit)
    private val constructionNameUN: String?
    private val civOwningUN: String?

    init {
        closeButton.remove()
        topTable.pad(10f)
        topTable.defaults().space(15f)

        val findUN = viewingCiv.victoryManager.getUNBuildingAndOwnerNames()
        constructionNameUN = findUN.first
        civOwningUN = findUN.second

        val breakdown = viewingCiv.victoryManager.getDiplomaticVictoryVoteBreakdown()
        val (results, winnerText) = breakdown

        if (ordinary) topTable.add(winnerText.toLabel(fontSize = UiTypography.size(TextRole.PageTitle, unit))
            .apply { wrap = true }).width(resultWidth).padBottom(16f * unit).row()
        val orderedCivs = gameInfo.getCivsSorted(civToSortFirst = viewingCiv)
        for (civ in orderedCivs) addVote(civ, results[civ.civID])

        descriptionLabel.setAlignment(Align.center)
        descriptionLabel.setText(if (ordinary) "" else winnerText.tr())

        rightSideButton.onActivation(UncivSound.Click) {
            viewingCiv.addFlag(CivFlags.ShowDiplomaticVotingResults.name, -1)
            game.popScreen()
        }
        rightSideButton.keyShortcuts.add(KeyCharAndCode.BACK)
        rightSideButton.keyShortcuts.add(KeyCharAndCode.SPACE)
        rightSideButton.enable()
        rightSideButton.setText("Continue".tr())
        bottomTable.cells[0].minWidth(rightSideButton.prefWidth + 20f)  // center descriptionLabel
        if (ordinary) presentGameDialog(object : Popup(this@DiplomaticVoteResultScreen) {
            init {
                addDecisionTitle(breakdown.title, "{Diplomatic voting results} · {${viewingCiv.civName}}")
                addFactRow("Election threshold", "[${viewingCiv.victoryManager.votesNeededForDiplomaticVictory()}] votes")
                val voters = orderedCivs.filterNot { it.isBarbarian || it.isSpectator() || it.isDefeated() }
                for (civ in voters.filter { it.isMajorCiv() })
                    addFactRow("{${civ.civName}} · {Votes received}", "[${results[civ.civID]}] votes")
                breakdown.tieExplanation?.let { addGoodSizedLabel(it).row() }
                addSeparator()
                addGoodSizedLabel("Votes cast", UiTypography.size(TextRole.SectionTitle, popupUnit)).padTop(14f * popupUnit).row()
                for (civ in voters) {
                    val held = if (civ.civID == civOwningUN && constructionNameUN != null) 2 else 1
                    val target = votesCast[civ.civID]?.let { gameInfo.getCivilization(it) }?.takeUnless { it.isDefeated() }
                    addGoodSizedLabel("{${civ.civName}} · " + "Votes available: [$held]".tr()).padTop(10f * popupUnit).row()
                    addGoodSizedLabel(if (target == null) "Abstained" else "{Voted for} {${target.civName}}",
                        UiTypography.size(TextRole.Supporting, popupUnit)).row()
                }
                styleOrdinaryButton(addButton("Continue") {
                    viewingCiv.addFlag(CivFlags.ShowDiplomaticVotingResults.name, -1)
                    game.popScreen()
                }.actor, primary = true)
            }
        })
    }

    private fun addVote(civ: Civilization, votesReceived: Int) {
        val civName = civ.civName
        if (ordinary) {
            val card = Table().apply {
                defaults().left().growX().minWidth(0f)
                fun line(text: String, role: TextRole) {
                    add(text.toLabel(fontSize = UiTypography.size(role, unit)).apply { wrap = true })
                        .width(resultWidth - 24f * unit).padBottom(6f * unit).row()
                }
                line(civName, TextRole.SectionTitle)
                if (civ.isMajorCiv()) line("Votes received: [$votesReceived]", TextRole.Body)
                val held = if (civ.civID == civOwningUN && constructionNameUN != null) "Votes available: [2]" else "Votes available: [1]"
                val target = votesCast[civ.civID]?.let { gameInfo.getCivilization(it) }?.takeUnless { it.isDefeated() }
                line(held + " · " + if (target == null) "Abstained" else "{Voted for} {${target.civName}}", TextRole.Supporting)
            }
            topTable.add(card).width(resultWidth).padBottom(12f * unit).row()
            return
        }

        if (civ.isMajorCiv()) topTable.add(votesReceived.toLabel(TextRole.Body)) else topTable.add()

        topTable.add(ImageGetter.getNationPortrait(civ.nation, 30f))
        topTable.add(civName.toLabel(hideIcons = true, role = TextRole.Body)).padLeft(20f).padRight(20f)

        if (civ.civID == civOwningUN && constructionNameUN != null) {
            topTable.add(ImageGetter.getConstructionPortrait(constructionNameUN, 30f))
            topTable.add("[2] votes".toLabel(TextRole.Body))
        } else {
            topTable.add("[1] vote".toLabel(TextRole.Body)).colspan(2)
        }

        fun abstained() = topTable.add("Abstained".toLabel(TextRole.Body)).colspan(3).row()
        val votedCivName = votesCast[civ.civID]
            ?: return abstained()

        val votedCiv = gameInfo.getCivilization(votedCivName)
        if (votedCiv.isDefeated()) return abstained()

        topTable.add("Voted for".toLabel(TextRole.Body)).padLeft(20f)
        topTable.add(ImageGetter.getNationPortrait(votedCiv.nation, 30f))
        topTable.add(votedCiv.civName.toLabel(hideIcons = true, role = TextRole.Body))
        topTable.row()
    }

    override fun recreate(): BaseScreen = DiplomaticVoteResultScreen(votesCast, viewingCiv)
}
