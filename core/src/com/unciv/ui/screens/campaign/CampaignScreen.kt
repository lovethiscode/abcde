package com.unciv.ui.screens.campaign

import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.unciv.logic.campaign.CampaignCatalog
import com.unciv.logic.campaign.CampaignStore
import com.unciv.models.skins.ExpeditionBlueTheme
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.input.onActivation
import com.unciv.ui.components.widgets.AutoScrollPane
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.popups.ConfirmPopup
import com.unciv.ui.popups.LoadingPopup
import com.unciv.ui.popups.ToastPopup
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.basescreen.RecreateOnResize
import com.unciv.utils.Concurrency

/** The construction prototype uses the real catalog and saves; the chapter map follows in stage four. */
class CampaignScreen : BaseScreen(), RecreateOnResize {
    private val store = CampaignStore(game.files)

    init {
        val content = Table().apply { pad(24f); defaults().pad(10f) }
        content.add("Original campaigns".toLabel(ExpeditionBlueTheme.gold, 32)).row()
        content.add("Construction campaign prototype".toLabel()).row()
        try {
            val definition = CampaignCatalog.definition()
            val template = CampaignCatalog.template()
            val player = template.civilizations.single { it.isHuman() }
            val capital = requireNotNull(player.getCapital())
            val saved = store.saved(definition.id)
            saved?.let(store::reconcile)
            val best = store.progress().bestStars["${definition.id}:${definition.scoringVersion}"] ?: 0
            content.add(definition.title.toLabel(fontSize = 27)).row()
            content.add("Best result: [$best]/3".toLabel()).row()
            content.add("Starting gold: [${player.gold}], capital population: [${capital.population.population}]".toLabel()).row()
            content.add("Produce a Monument and a Scout. Gold purchases do not count and are disabled.".toLabel().apply { wrap = true })
                .width(stage.width.coerceAtMost(650f) - 80f).row()
            content.add("Bonus: produce and keep a Worker. Bonus: grow the initial capital to [${definition.population}] population.".toLabel().apply { wrap = true })
                .width(stage.width.coerceAtMost(650f) - 80f).row()
            content.add("No time limit. Submit when ready, or keep playing for more stars.".toLabel().apply { wrap = true })
                .width(stage.width.coerceAtMost(650f) - 80f).row()
            if (saved?.campaign?.let { it.resultStars == 0 && !it.failed } == true) {
                content.add("Continue campaign".toTextButton().apply {
                    name = "ui-campaign-continue"
                    onActivation { enter(false) }
                }).fillX().height(58f).row()
            }
            content.add((if (saved == null) "Start campaign" else "Restart campaign").toTextButton().apply {
                name = "ui-campaign-start"
                onActivation {
                    if (saved?.campaign?.let { it.resultStars == 0 && !it.failed } == true)
                        ConfirmPopup(this@CampaignScreen, "Replace this level's unfinished attempt? Your best result will be kept.", "Restart campaign") {
                            enter(true, saved.campaign!!.attemptId)
                        }.open()
                    else enter(true, saved?.campaign?.attemptId)
                }
            }).fillX().height(58f).row()
        } catch (ex: Exception) {
            content.add("Campaign could not be loaded. Your saved progress has been kept.".toLabel().apply { wrap = true })
                .width(stage.width * .7f).row()
            com.unciv.utils.Log.error("Cannot open campaign catalog", ex)
        }
        content.add("Back".toTextButton().apply { onActivation { game.popScreen() } }).height(52f).row()
        val root = Table().apply {
            setFillParent(true)
            background = ImageGetter.getWhiteDotDrawable().tint(ExpeditionBlueTheme.background)
            add(AutoScrollPane(content)).grow()
        }
        stage.addActor(root)
    }

    private fun enter(start: Boolean, replacing: String? = null) {
        val loading = LoadingPopup(this)
        Concurrency.runOnGLThread {
            try {
                game.files.autosaves.autoSaveJob?.join()
                val attempt = if (start) CampaignCatalog.newAttempt().also { store.start(it, replacing) }
                    else store.resume(CampaignCatalog.construction)
                game.loadGame(attempt)
            } catch (ex: Exception) {
                com.unciv.utils.Log.error("Cannot enter campaign", ex)
                ToastPopup(ex.message ?: "Could not load campaign", this@CampaignScreen)
            } finally { loading.close() }
        }
    }

    override fun recreate(): BaseScreen = CampaignScreen()
}
