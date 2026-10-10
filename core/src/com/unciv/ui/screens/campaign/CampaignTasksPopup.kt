package com.unciv.ui.screens.campaign

import com.badlogic.gdx.scenes.scene2d.ui.TextButton.TextButtonStyle
import com.badlogic.gdx.utils.Align
import com.unciv.logic.campaign.CampaignCatalog
import com.unciv.logic.campaign.CampaignRules
import com.unciv.logic.campaign.CampaignStore
import com.unciv.logic.map.HexCoord
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.input.onActivation
import com.unciv.ui.components.widgets.UiNavigation
import com.unciv.ui.popups.LoadingPopup
import com.unciv.ui.popups.Popup
import com.unciv.ui.popups.ToastPopup
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.worldscreen.WorldScreen
import com.unciv.utils.Concurrency

/** The same panel presents live progress and frozen, recoverable results. */
class CampaignTasksPopup(private val screen: WorldScreen) : Popup(screen) {
    init {
        val state = requireNotNull(screen.gameInfo.campaign)
        val d = state.definition
        // Refresh explanatory copy for compatible saves without replacing their scoring snapshot.
        val copy = CampaignCatalog.definition(d.id).takeIf {
            it.configVersion == d.configVersion && it.scoringVersion == d.scoringVersion
        } ?: d
        val result = CampaignRules.evaluate(screen.gameInfo)
        val terminal = state.resultStars > 0 || result.failed
        addGoodSizedLabel(d.title).row()
        addGoodSizedLabel("行动回合 ${state.completedRounds + 1} · 已完成 ${state.completedRounds} 回合").row()
        if (terminal) {
            screen.campaignResultShown = true
            addBodyText(if (result.failed) "本次挑战失败：${result.failureReason}" else "通关 · ${state.resultStars} / 3 星").row()
            val best = CampaignStore(screen.game.files).progress().bestStars["${d.id}:${d.scoringVersion}"] ?: 0
            addBodyText("本版本最高成绩：${maxOf(best, state.resultStars)} / 3 星").row()
        }
        val goals = copy.objectives.ifEmpty {
            listOf("本次生产完成：" + d.requiredProduction.joinToString("、") { it.tr() },
                "本次生产并保留：" + d.worker.tr(), "初始首都人口至少 ${d.population}")
        }
        val met = listOf(result.main, result.worker, result.population)
        for ((i, goal) in goals.withIndex()) {
            addBodyText("${if (met[i] && !result.failed) "✓" else "○"} $goal").row()
            result.details.getOrNull(i)?.let { addBodyText(it).row() }
            if (i in result.impossible) addBodyText("本次挑战星已无法获得；仍可争取其他目标，或重新挑战。").row()
        }
        if (!terminal) {
            for (restriction in d.restrictions) addBodyText(restriction).row()
            if (result.main && !d.automatic) {
                addBodyText("主目标已达成。点击下方“结算通关”保存成绩并解锁下一关，也可继续争取挑战星。").row()
            } else addBodyText(if (d.automatic) "按本关规则自动结算。" else "完成主目标后可主动结算。").row()
            for (area in d.areas) location("定位：${area.title}", area.tiles.first())
            for ((i, tile) in d.targetTiles.withIndex()) {
                val name = screen.gameInfo.tileMap[tile].resource?.let { "${it.tr()}地块" } ?: "改良地块 ${i+1}"
                location("定位：$name", tile)
            }
            screen.gameInfo.getCities().firstOrNull { it.id == d.targetCity }?.let { location("定位：目标城市", it.location) }
            if (!state.hintsDismissed) {
                for (hint in copy.hints) addBodyText("提示 · $hint").row()
            }
            contentButton(if (state.hintsDismissed) "展开教学提示" else "收起教学提示") {
                state.hintsDismissed = !state.hintsDismissed
                close(); CampaignTasksPopup(screen)
            }
            if (result.main && !d.automatic) {
                addButton("Submit campaign result", style = BaseScreen.skin.get("positive", TextButtonStyle::class.java)) {
                    submit()
                }.apply {
                    actor.name = "ui-campaign-submit"
                    colspan(2).growX().row()
                }
            }
        } else {
            if (state.resultStars > 0 && d.nextLevel.isNotEmpty())
                addButton("前往下一关") { leave(d.nextLevel) }.colspan(2).growX().row()
            addButton("查看本关 / 重新挑战") { leave(d.id) }.colspan(2).growX().row()
        }
        addButton("Save and return to campaigns") { leave(d.id) }.actor.name = "ui-campaign-return"
        addCloseButton(if (terminal) "查看地图（只读）" else "继续挑战")
        open()
    }

    private fun addBodyText(text: String) = addGoodSizedLabel(text).apply {
        actor.setAlignment(Align.left)
    }

    private fun location(title: String, position: HexCoord) {
        contentButton(title) {
            // Centering does not change explored tiles or remove fog of war.
            screen.mapHolder.setCenterPosition(position, immediately = true, selectUnit = false)
            close()
        }
    }

    private fun contentButton(title: String, action: () -> Unit) {
        add(title.toTextButton().apply { onActivation { action() } })
            .minHeight(UiNavigation.minimumTouchSize()).row()
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

    private fun leave(level: String) {
        if (!screen.isPlayersTurn || screen.isNextTurnUpdateRunning()) return
        close()
        val loading = LoadingPopup(screen)
        Concurrency.runOnGLThread {
            try {
                screen.game.files.autosaves.autoSaveJob?.join()
                CampaignStore(screen.game.files).save(screen.gameInfo)
                val number = CampaignCatalog.definition(level).number
                screen.game.pushScreen { CampaignScreen((number - 1) / 4, level) }
            } catch (ex: Exception) {
                ToastPopup("Could not save game!", screen)
                com.unciv.utils.Log.error("Campaign save failed", ex)
            } finally { loading.close() }
        }
    }
}
