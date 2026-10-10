package com.unciv.ui.screens.campaign

import com.unciv.logic.campaign.CampaignRules
import com.unciv.logic.campaign.CampaignStore
import com.unciv.models.translations.tr
import com.unciv.ui.popups.LoadingPopup
import com.unciv.ui.popups.Popup
import com.unciv.ui.popups.ToastPopup
import com.unciv.ui.screens.worldscreen.WorldScreen
import com.unciv.utils.Concurrency

class CampaignTasksPopup(private val screen: WorldScreen) : Popup(screen) {
    init {
        val state = requireNotNull(screen.gameInfo.campaign)
        val result = CampaignRules.evaluate(screen.gameInfo)
        addGoodSizedLabel(state.definition.title).row()
        addGoodSizedLabel("Action turn [${state.completedRounds + 1}], completed rounds [${state.completedRounds}]").row()
        for (item in state.definition.requiredProduction)
            addGoodSizedLabel("${if (item in state.produced) "✓" else "○"} " + "Campaign production: [$item]".tr()).row()
        addGoodSizedLabel("${if (result.worker) "✓" else "○"} " + "Produce and keep a Worker".tr()).row()
        addGoodSizedLabel("${if (result.population) "✓" else "○"} " + "Initial capital population: [${state.definition.population}]".tr()).row()
        addGoodSizedLabel("Monument and Scout must be produced; their gold purchases are disabled.").row()
        when {
            state.resultStars > 0 -> addGoodSizedLabel("Campaign complete: [${state.resultStars}]/3").row()
            result.failed -> addGoodSizedLabel("Campaign failed").row()
            result.main -> {
                addGoodSizedLabel("You can submit now or continue for more stars.").row()
                addButton("Submit campaign result") { submit() }.actor.name = "ui-campaign-submit"
                row()
            }
            else -> addGoodSizedLabel("Complete both production objectives to submit.").row()
        }
        addButton("Save and return to campaigns") { leave() }.actor.name = "ui-campaign-return"
        row()
        addCloseButton()
        open()
    }

    private fun submit() {
        if (!screen.isPlayersTurn || screen.isNextTurnUpdateRunning()) return
        close()
        val loading = LoadingPopup(screen)
        Concurrency.runOnGLThread {
            try {
                screen.game.files.autosaves.autoSaveJob?.join()
                CampaignStore(screen.game.files).submit(screen.gameInfo)
                screen.shouldUpdate = true
                CampaignTasksPopup(screen)
            } catch (ex: Exception) {
                ToastPopup("Could not save campaign result. Please try again.", screen)
                com.unciv.utils.Log.error("Campaign submission failed", ex)
            } finally { loading.close() }
        }
    }

    private fun leave() {
        if (!screen.isPlayersTurn || screen.isNextTurnUpdateRunning()) return
        close()
        val loading = LoadingPopup(screen)
        Concurrency.runOnGLThread {
            try {
                screen.game.files.autosaves.autoSaveJob?.join()
                CampaignStore(screen.game.files).save(screen.gameInfo)
                screen.game.pushScreen { CampaignScreen() }
            } catch (ex: Exception) {
                ToastPopup("Could not save game!", screen)
            } finally { loading.close() }
        }
    }
}
