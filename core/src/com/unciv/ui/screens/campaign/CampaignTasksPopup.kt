package com.unciv.ui.screens.campaign

import com.unciv.logic.campaign.CampaignCatalog
import com.unciv.logic.campaign.CampaignRules
import com.unciv.logic.campaign.CampaignStore
import com.unciv.logic.map.HexCoord
import com.unciv.ui.popups.LoadingPopup
import com.unciv.ui.popups.Popup
import com.unciv.ui.popups.ToastPopup
import com.unciv.ui.screens.worldscreen.WorldScreen
import com.unciv.utils.Concurrency

/** The same panel presents live progress and frozen, recoverable results. */
class CampaignTasksPopup(private val screen: WorldScreen) : Popup(screen) {
    init {
        val state = requireNotNull(screen.gameInfo.campaign)
        val d = state.definition
        val result = CampaignRules.evaluate(screen.gameInfo)
        val terminal = state.resultStars > 0 || result.failed
        addGoodSizedLabel(d.title).row()
        addGoodSizedLabel("行动回合 ${state.completedRounds + 1} · 已完成 ${state.completedRounds} 回合").row()
        if (terminal) {
            addGoodSizedLabel(if (result.failed) "本次挑战失败：${result.failureReason}" else "通关 · ${state.resultStars} / 3 星").row()
            val best = CampaignStore(screen.game.files).progress().bestStars["${d.id}:${d.scoringVersion}"] ?: 0
            addGoodSizedLabel("本版本最高成绩：${maxOf(best, state.resultStars)} / 3 星").row()
        }
        val goals = d.objectives.ifEmpty { CampaignCatalog.definition(d.id).objectives }
        val met = listOf(result.main, result.worker, result.population)
        for ((i, goal) in goals.withIndex()) {
            addGoodSizedLabel("${if (met[i] && !result.failed) "✓" else "○"} $goal").row()
            result.details.getOrNull(i)?.let { addGoodSizedLabel(it).row() }
            if (i in result.impossible) addGoodSizedLabel("本次挑战星已无法获得；仍可争取其他目标，或重新挑战。").row()
        }
        if (!terminal) {
            for (restriction in d.restrictions) addGoodSizedLabel(restriction).row()
            if (result.main && !d.automatic) {
                addGoodSizedLabel("主目标已达成。现在结算，或继续争取挑战星；结算时重新检查当前条件。").row()
                addButton("Submit campaign result") { submit() }.actor.name = "ui-campaign-submit"
                row()
            } else addGoodSizedLabel(if (d.automatic) "按本关规则自动结算。" else "完成主目标后可主动结算。").row()
            for (area in d.areas) location("定位：${area.title}", area.tiles.first())
            for ((i, tile) in d.targetTiles.withIndex()) location("定位：改良地块 ${i+1}", tile)
            screen.gameInfo.getCities().firstOrNull { it.id == d.targetCity }?.let { location("定位：目标城市", it.location) }
            if (!state.hintsDismissed) {
                for (hint in d.hints) addGoodSizedLabel("提示 · $hint").row()
            }
            addButton(if (state.hintsDismissed) "展开教学提示" else "收起教学提示") {
                state.hintsDismissed = !state.hintsDismissed
                close(); CampaignTasksPopup(screen)
            }.row()
        } else {
            if (state.resultStars > 0 && d.nextLevel.isNotEmpty())
                addButton("前往下一关") { leave(d.nextLevel) }.row()
            addButton("查看本关 / 重新挑战") { leave(d.id) }.row()
        }
        addButton("Save and return to campaigns") { leave(d.id) }.actor.name = "ui-campaign-return"
        row()
        addCloseButton(if (terminal) "查看地图（只读）" else "继续挑战")
        open()
    }

    private fun location(title: String, position: HexCoord) {
        addButton(title) {
            // Centering does not change explored tiles or remove fog of war.
            screen.mapHolder.setCenterPosition(position, immediately = true, selectUnit = false)
            close()
        }.row()
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
