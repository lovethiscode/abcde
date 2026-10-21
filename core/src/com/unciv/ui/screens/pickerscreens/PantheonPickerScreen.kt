package com.unciv.ui.screens.pickerscreens

import com.unciv.logic.civilization.Civilization
import com.unciv.models.ruleset.Belief
import com.unciv.models.ruleset.BeliefType
import com.unciv.models.ruleset.unique.GameContext
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.fonts.TextRole
import com.unciv.ui.screens.overviewscreen.OverviewUi

class PantheonPickerScreen(
    choosingCiv: Civilization
) : ReligionPickerScreenCommon(choosingCiv) {
    private var selectedPantheon: Belief? = null
    private val selection = Selection()

    init {
        pickerPane.setNavigationContent("Choose a pantheon".toLabel(TextRole.PageTitle))
        topTable.defaults().fillX().minWidth(0f)
        topTable.pad(OverviewUi.pt(16f))

        for (belief in ruleset.beliefs.values) {
            if (belief.type != BeliefType.Pantheon) continue
            val beliefButton = getBeliefButton(belief, withTypeLabel = false)
            beliefButton.name = "ui-belief:${belief.name}"
            if (choosingCiv.religionManager.getReligionWithBelief(belief) == null && beliefIsAllowed(belief, choosingCiv)) {
                beliefButton.onClickSelect(selection, belief) {
                    selectedPantheon = belief
                    pick("Follow [${belief.name}]".tr())
                }
            } else {
                beliefButton.disable(redDisableColor)
            }
            topTable.add(beliefButton).growX().minHeight(OverviewUi.touch)
                .padBottom(OverviewUi.pt(8f)).row()
        }

        setOKAction("Choose a pantheon") {
            chooseBeliefs(listOf(selectedPantheon!!), useFreeBeliefs = usingFreeBeliefs())
        }
    }
    fun beliefIsAllowed(belief: Belief, choosingCiv: Civilization): Boolean {
        if (belief.getMatchingUniques(UniqueType.OnlyAvailable, GameContext.IgnoreConditionals)
                .any { !it.conditionalsApply(choosingCiv.state) })
            return false
        if (belief.getMatchingUniques(UniqueType.Unavailable, choosingCiv.state).any())
            return false
        return true
    }
}
