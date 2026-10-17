package com.unciv.ui.screens.endless

import com.unciv.ui.components.fonts.UiTypography
import com.unciv.ui.components.fonts.TextRole
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.unciv.logic.endless.EndlessRules
import com.unciv.logic.endless.EndlessShareData
import com.unciv.logic.endless.EndlessStore
import com.unciv.logic.endless.EndlessWavePlanner
import com.unciv.models.skins.ExpeditionBlueTheme as Theme
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.input.onActivation
import com.unciv.ui.components.widgets.UiNavigation
import com.unciv.ui.popups.ConfirmPopup
import com.unciv.ui.popups.LoadingPopup
import com.unciv.ui.popups.Popup
import com.unciv.ui.popups.ToastPopup
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.worldscreen.WorldScreen
import com.unciv.utils.Concurrency

class EndlessPopup(private val screen: WorldScreen) : Popup(screen) {
    private val mode = screen.gameInfo.endless!!
    private val store = EndlessStore(screen.game.files)
    init {
        val width = (maxPopupWidth - 50f).coerceAtMost(980f)
        innerTable.background = BaseScreen.skin.newDrawable("RoundedEdgeRectangle", Theme.panel)
        addTitleWithCloseButton((when {
            mode.saveBlocked -> "Save failed · retry required"
            mode.ended -> "Defense result"
            mode.offer != null -> "Choose your resupply"
            else -> "Defense status"
        }).toLabel(Theme.gold, UiTypography.size(TextRole.PageTitle, legacySize = 26)))
        fun text(value: String, muted: Boolean = false) {
            add(value.toLabel(if (muted) Theme.muted else Theme.text, UiTypography.size(TextRole.Body, legacySize = 18)).apply { wrap = true }).width(width).left().padBottom(10f).row()
        }
        text("Action round [${mode.round}] · completed [${mode.completedRounds}] rounds")
        if (mode.stageState == null) text("Legacy rules",true)
        text(endlessStatus(mode))
        if (mode.saveBlocked) {
            text("Your last successful save is preserved. Retry saving to continue.")
            addButton("Retry save") { retrySave() }.minHeight(UiNavigation.minimumTouchSize())
        } else if (mode.ended) {
            text(when (mode.status) {
                "Defeated" -> if (mode.stageState?.endReason == "StageTimeout") "The stage deadline was missed." else "The bastion has fallen."
                "Quit" -> "Defense ended voluntarily."
                else -> "Defense paused by a rules error."
            })
            text("[${mode.clearedWaves}] waves cleared · [${mode.kills}] battle kills")
            text("Military losses [${mode.militaryLosses}] · civilian losses [${mode.civilianLosses}]", true)
            text("Stages cleared [${mode.stageState?.completedStages ?: (mode.clearedPrefix / 5)}] · highest wave [${mode.waveNumber}]", true)
            if (mode.status == "Faulted") text(mode.fault, true)
            else text(if (mode.status == "Quit") "Quitting does not set a best record." else "Saved on this device", true)
            addButton("New defense") { start() }
        } else if (mode.offer != null) {
            val offer = mode.offer!!
            text("Stage [${offer.stage}] cleared · choose one reward")
            val wide = width >= 750f
            val repairAllowed = mode.stageState?.pace != "wild"
            val cardCount = if (repairAllowed) 3 else 2
            val cardWidth = if (wide) (width - 12f * (cardCount - 1)) / cardCount else width
            val choices = Table()
            fun choice(key: String, title: String, detail: String, enabled: Boolean, reason: String) {
                val card = Table().apply {
                    background = BaseScreen.skin.newDrawable("RoundedEdgeRectangle", Theme.raised)
                    pad(14f); top(); defaults().left()
                    add(title.toLabel(Theme.gold, UiTypography.size(TextRole.PageTitle, legacySize = 21))).width(cardWidth - 28f).row()
                    add(detail.toLabel(Theme.text, UiTypography.size(TextRole.Body, legacySize = 17)).apply { wrap = true }).width(cardWidth - 28f).padTop(9f).row()
                    if (!enabled) add(reason.toLabel(Theme.muted, UiTypography.size(TextRole.Control, legacySize = 15)).apply { wrap = true }).width(cardWidth - 28f).padTop(9f).row()
                    add("Choose reward".toTextButton().apply {
                        name = "ui-endless-reward:$key"; isDisabled = !enabled
                        onActivation { claim(key) }
                    }).width(cardWidth - 28f).minHeight(UiNavigation.minimumTouchSize()).padTop(14f)
                }
                choices.add(card).width(cardWidth).top().padRight(if (wide && key != "Reinforcement") 12f else 0f)
                if (!wide) choices.row()
            }
            choice("Gold", "Emergency supplies", "Gain [${offer.gold}] Gold.", true, "")
            if (repairAllowed) choice("Repair", "Field repairs", if (mode.stageState != null) "Restore 30% of each surviving city's maximum health and 25% for every surviving military unit. Actions are not restored." else "Restore 30% of the city's maximum health and 25% for every surviving military unit. Actions are not restored.",
                EndlessRules.canRepair(screen.gameInfo), "The city and all military units are at full health.")
            choice("Reinforcement", "Reinforcements", "Receive [${offer.unit.tr()}]. It cannot act this round. If blocked for three rounds, receive [${offer.gold}] Gold instead.",
                mode.reinforcement == null, "A reinforcement is already waiting to arrive.")
            add(choices).width(width).row()
            text("Claim all pending rewards before continuing map actions.", true)
        } else {
            val city = EndlessRules.core(screen.gameInfo)!!
            text("Core city: [${city.health}] / [${com.unciv.logic.battle.CityCombatant(city).getMaxHealth()}] HP · population [${city.population.population}]")
            text("[${mode.clearedWaves}] waves cleared · [${mode.kills}] battle kills")
            mode.stageState?.let { state ->
                text("Cities [${EndlessRules.player(screen.gameInfo).cities.size}] / [${com.unciv.logic.endless.EndlessStageRules.capacity(mode)}] · found within 4 tiles of the core · territory within 7 tiles",true)
                text("[${mode.activeCount}] enemies · [${mode.pendingCount}] waiting",true)
                if (state.phase == "Rest") text("Next stage [${state.completedStages + 1}] · [${com.unciv.logic.endless.EndlessStagePlanner.duration(state.completedStages + 1)}] combat rounds · [+${com.unciv.logic.endless.EndlessStagePlanner.pressure(state.completedStages + 1)}]% enemy strength",true)
            }
            if (mode.stageState == null && mode.waves.isNotEmpty()) text("Clearing all enemies can bring the next wave forward.", true)
            val preview = if (mode.stageState != null) mode.waves.firstOrNull { !it.announced } ?: mode.stageState!!.preview
                else EndlessWavePlanner.plan(mode.clone().apply { completedRounds = nextWaveRound - 1 })
            if (preview != null && (mode.stageState?.phase != "Preparation" || mode.round >= 4))
            text("Next approach: [${directionName(preview.mainSector).tr()}] · [${preview.template.tr()}]", true)
            if (mode.stageState == null) {
            text("Continuous siege: [+${EndlessWavePlanner.pressure(mode.round)}]% enemy strength", true)
            if (mode.round < EndlessWavePlanner.finalTierRound) text("Next unit tier unlocks in round [${if (mode.round < 36) 36 else EndlessWavePlanner.finalTierRound}].", true)
            }
            mode.reinforcement?.let { text("Reinforcement waiting: [${it.unit.tr()}] · converts after round [${it.expiresRound}]", true) }
            addButton("End defense") {
                close()
                ConfirmPopup(screen, "End defense without a best record?", "End defense") {
                    try { store.quit(screen.gameInfo); screen.shouldUpdate = true; EndlessPopup(screen) }
                    catch (ex: Exception) { screen.shouldUpdate = true; EndlessPopup(screen) }
                }.open()
            }.minHeight(UiNavigation.minimumTouchSize())
        }
        if (screen.game.endlessSharingAvailable && EndlessShareData.fromState(mode) != null)
            addButton("Share defense") { share() }.apply { actor.name = "ui-endless-share" }.minHeight(UiNavigation.minimumTouchSize())
        if (!mode.saveBlocked) addButton("Endless settings") {
            close()
            screen.game.pushScreen { EndlessConfigScreen(savedState = mode.clone()) }
        }.apply {
            actor.labelCell.width(minOf(actor.label.prefWidth, width - 24f))
            actor.label.setWrap(true)
        }.minHeight(UiNavigation.minimumTouchSize())
        if (!mode.saveBlocked) addButton("Return to defense hall") { leave() }.minHeight(UiNavigation.minimumTouchSize())
        addCloseButton(if (mode.offer != null) "Claim later" else "View map").minHeight(UiNavigation.minimumTouchSize())
        val buttons = bottomTable.children.toList()
        bottomTable.clearChildren()
        var rowWidth = 0f
        for (button in buttons) {
            val buttonWidth = if (width < 620f) width else (button as com.badlogic.gdx.scenes.scene2d.ui.TextButton).prefWidth.coerceAtMost(width)
            if (rowWidth > 0 && rowWidth + buttonWidth + 10f > width) { bottomTable.row(); rowWidth = 0f }
            bottomTable.add(button).width(buttonWidth).minHeight(maxOf(48f, UiNavigation.minimumTouchSize())).pad(4f)
            rowWidth += buttonWidth + 10f
        }
        getScrollPane()?.setScrollingDisabled(true, false)
        open(force = true)
    }

    private fun retrySave() {
        val loading = LoadingPopup(screen)
        Concurrency.runOnGLThread {
            try {
                if (mode.pendingRewardCandidate != null) {
                    val candidate = store.retryReward(screen.gameInfo)
                    close(); screen.game.loadGame(candidate)
                } else {
                    store.save(screen.gameInfo)
                    close(); screen.shouldUpdate = true; EndlessPopup(screen)
                }
            } catch (ex: Exception) { ToastPopup("Could not save game!",screen) }
            finally { loading.close() }
        }
    }

    private fun claim(choice: String) {
        val loading = LoadingPopup(screen)
        Concurrency.runOnGLThread {
            try {
                screen.game.files.autosaves.autoSaveJob?.join()
                val candidate = store.claim(screen.gameInfo, choice)
                close()
                screen.game.loadGame(candidate)
            } catch (ex: Exception) {
                com.unciv.utils.Log.error("Endless reward save failed", ex)
                close(); screen.shouldUpdate = true; EndlessPopup(screen)
            } finally { loading.close() }
        }
    }

    private fun share() {
        val loading = LoadingPopup(screen)
        Concurrency.runOnGLThread {
            try {
                screen.game.files.autosaves.autoSaveJob?.join()
                screen.game.showEndlessShare(store.shareSnapshot(screen.gameInfo))
            } catch (ex: Exception) {
                close(); screen.shouldUpdate = true; EndlessPopup(screen)
                ToastPopup("Could not prepare the defense share. Please try again.", screen)
            } finally { loading.close() }
        }
    }

    private fun start() {
        if (mode.saveBlocked || !mode.ended) return
        close()
        screen.game.pushScreen { EndlessConfigScreen(replacingAttempt = mode.attemptId) }
    }

    fun leave(toHall: Boolean = true) {
        if (mode.saveBlocked) return
        val loading = LoadingPopup(screen)
        Concurrency.runOnGLThread {
            try {
                screen.game.files.autosaves.autoSaveJob?.join()
                store.save(screen.gameInfo)
                close()
                screen.game.goToMainMenu { if (toHall) screen.game.pushScreen { EndlessScreen() } }
            } catch (ex: Exception) {
                close(); screen.shouldUpdate = true; EndlessPopup(screen)
            } finally { loading.close() }
        }
    }
}

internal fun directionName(sector: Int) = listOf("West", "Southwest", "Southeast", "East", "Northeast", "Northwest")[sector]
