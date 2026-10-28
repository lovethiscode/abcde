package com.unciv.ui.screens.pickerscreens

import com.unciv.logic.civilization.Civilization
import com.unciv.models.ruleset.Belief
import com.unciv.models.ruleset.BeliefType
import com.unciv.models.ruleset.unique.GameContext
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.translations.tr
import com.unciv.ui.screens.overviewscreen.OverviewUi
import com.unciv.ui.components.widgets.UiNavigation

class PantheonPickerScreen(
    choosingCiv: Civilization,
    private val initialPantheonName: String? = null
) : ReligionPickerScreenCommon(choosingCiv), com.unciv.ui.screens.basescreen.RecreateOnResize {
    private var selectedPantheon: Belief? = null
    private val selection = Selection()

    init {
        topTable.defaults().fillX().minWidth(0f)
        topTable.pad(OverviewUi.pt(if (UiNavigation.isMobile) 8f else 16f)).top()

        val columns = if (UiNavigation.isMobile && stage.width >= OverviewUi.pt(640f)) 2 else 1
        var index = 0
        for (belief in ruleset.beliefs.values) {
            if (belief.type != BeliefType.Pantheon) continue
            val beliefButton = getBeliefButton(belief, withTypeLabel = false)
            beliefButton.name = "ui-belief:${belief.name}"
            if (choosingCiv.religionManager.getReligionWithBelief(belief) == null && beliefIsAllowed(belief, choosingCiv)) {
                beliefButton.onClickSelect(selection, belief) {
                    selectedPantheon = belief
                    setBeliefReference(belief)
                    pick("Follow [${belief.name}]".tr())
                }
                if (belief.name == initialPantheonName) {
                    selection.switch(beliefButton)
                    selectedPantheon = belief
                    setBeliefReference(belief)
                    pick("Follow [${belief.name}]".tr())
                }
            } else {
                beliefButton.disable(redDisableColor)
            }
            topTable.add(beliefButton).growX().uniformX().minWidth(0f).minHeight(OverviewUi.touch)
                .padRight(if (columns > 1 && index % columns == 0) OverviewUi.pt(4f) else 0f)
                .padBottom(OverviewUi.pt(if (UiNavigation.isMobile) 4f else 8f))
            if (++index % columns == 0) topTable.row()
        }

        setOKAction("Choose a pantheon") {
            chooseBeliefs(listOf(selectedPantheon!!), useFreeBeliefs = usingFreeBeliefs())
        }
        selectedPantheon?.let { rightSideButton.setText("Follow [${it.name}]".tr()) }
    }
    override fun recreate() = PantheonPickerScreen(choosingCiv, selectedPantheon?.name)

    fun beliefIsAllowed(belief: Belief, choosingCiv: Civilization): Boolean {
        if (belief.getMatchingUniques(UniqueType.OnlyAvailable, GameContext.IgnoreConditionals)
                .any { !it.conditionalsApply(choosingCiv.state) })
            return false
        if (belief.getMatchingUniques(UniqueType.Unavailable, choosingCiv.state).any())
            return false
        return true
    }
}
