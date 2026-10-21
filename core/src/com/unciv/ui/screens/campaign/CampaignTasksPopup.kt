package com.unciv.ui.screens.campaign

import com.unciv.ui.components.fonts.UiTypography
import com.unciv.ui.components.fonts.TextRole
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.badlogic.gdx.scenes.scene2d.ui.TextButton.TextButtonStyle
import com.unciv.logic.campaign.CivilWarCampaign
import com.unciv.logic.campaign.CampaignCatalog
import com.unciv.logic.campaign.CampaignRules
import com.unciv.logic.campaign.CampaignStore
import com.unciv.logic.campaign.trCampaign
import com.unciv.logic.map.HexCoord
import com.unciv.models.skins.ExpeditionBlueTheme as Theme
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.setFontSize
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.input.onActivation
import com.unciv.ui.components.widgets.UiNavigation
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.popups.LoadingPopup
import com.unciv.ui.popups.Popup
import com.unciv.ui.popups.ToastPopup
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.worldscreen.WorldScreen
import com.unciv.utils.Concurrency

/** Fixed heading and settlement controls surround a scrollable, two-column task board. */
class CampaignTasksPopup(private val screen: WorldScreen) : Popup(screen) {
    init {
        val state = requireNotNull(screen.gameInfo.campaign)
        if (!state.briefingSeen) {
            state.briefingSeen = true
            state.hintsDismissed = true
        }
        val d = state.definition
        // Refresh explanatory copy for compatible saves without replacing their scoring snapshot.
        val copy = CampaignCatalog.definition(d.id).takeIf {
            it.configVersion == d.configVersion && it.scoringVersion == d.scoringVersion
        } ?: d
        val result = CampaignRules.evaluate(screen.gameInfo)
        val terminal = state.resultStars > 0 || result.failed
        val unit = UiNavigation.minimumTouchSize() / 44f
        val boardWidth = (maxPopupWidth - 50f).coerceAtMost((if (terminal) 720f else 1100f) * unit)
        val wide = !terminal && boardWidth / unit >= 700f
        val goalsWidth = if (wide) (boardWidth - 24f) * .61f else boardWidth
        val sideWidth = if (wide) boardWidth - goalsWidth - 24f else boardWidth
        innerTable.background = BaseScreen.skin.newDrawable("RoundedEdgeRectangle", Theme.panel)

        addDialogTitle(
            when {
                result.failed -> "本次挑战结束"
                terminal -> "关卡完成"
                else -> "战役任务"
            },
            "[${d.number.toString().padStart(2, '0')}] · [${d.title}] · 行动回合 [${if (d.hardTurnLimit > 0) (state.completedRounds + 1).coerceAtMost(d.hardTurnLimit) else state.completedRounds + 1}]",
            dismissible = false
        )

        val goals = Table().apply { top(); defaults().left().growX() }
        if (terminal) {
            screen.campaignResultShown = true
            goals.add(bodyText(if (result.failed) (result.failureReason.ifBlank { "本次挑战失败" }) else d.title, goalsWidth,
                if (result.failed) Theme.danger else Theme.gold, TextRole.PageTitle)).padBottom(6f).row()
            val best = runCatching { CampaignStore(screen.game.files).progress().bestStars["${d.id}:${d.scoringVersion}"] ?: 0 }.getOrNull()
            goals.add(bodyText(if (result.failed) "本次获得 0 星 · 战役进度仍保留" else
                if (best == null) "Latest attempt: [${state.resultStars}] / 3 stars" else
                "本次获得 [${state.resultStars}] 星 · 本版本最高 [$best] 星", goalsWidth, Theme.muted, TextRole.Control))
                .padBottom(12f).row()
            if (result.failed) goals.add(Table().apply {
                background = BaseScreen.skin.newDrawable("RoundedEdgeRectangle", Theme.background)
                pad(12f)
                add(bodyText("下一步建议", goalsWidth - 24f, Theme.gold, TextRole.Control)).row()
                add(bodyText("先查看地图确认局势，再返回战役重新挑战。教学提示仍会保留，并默认收起。",
                    goalsWidth - 24f, Theme.muted, TextRole.Supporting)).padTop(5f)
            }).width(goalsWidth).padBottom(12f).row()
        }
        val met = listOf(result.main, result.worker, result.population)
        for ((i, goal) in copy.objectives.withIndex()) {
            if (i < 2) goals.add(bodyText(if (i == 0) "通关目标 · 必做 1 星" else "挑战目标 · 可选，各 1 星",
                goalsWidth, Theme.gold, TextRole.Control)).padTop(if (i == 0) 0f else 12f).padBottom(7f).row()
            val card = Table().apply {
                background = BaseScreen.skin.newDrawable("RoundedEdgeRectangle", if (i == 0) Theme.raised else Theme.background)
                pad(12f)
                add((if (met[i] && !result.failed) "✓" else "○").toLabel(
                    if (met[i] && !result.failed) Theme.gold else Theme.muted, UiTypography.size(TextRole.SectionTitle, legacySize = 22))).top().width(26f).padRight(8f)
                val textWidth = goalsWidth - 58f
                add(Table().apply {
                    defaults().left()
                    add(bodyText(goal, textWidth, Theme.text, TextRole.Body)).row()
                    result.details.getOrNull(i)?.let {
                        add(bodyText(it, textWidth, if (met[i] && !result.failed) Theme.success else Theme.muted, TextRole.Control)).padTop(5f).row()
                    }
                    if (i in result.impossible) add(bodyText("本次已错过这颗星，仍可完成通关及其他挑战。",
                        textWidth, Theme.danger, TextRole.Control)).padTop(5f).row()
                }).width(textWidth)
            }
            goals.add(card).width(goalsWidth).padBottom(8f).row()
        }

        val side = Table().apply { top(); defaults().left().growX().spaceBottom(8f) }
        val canShare = screen.game.campaignSharingAvailable && state.resultStars > 0 && !state.failed &&
            runCatching { CampaignStore(screen.game.files).shareResult(d.id, state.attemptId) }.getOrNull() != null
        if (canShare && !terminal) {
            side.add(boardButton("Share: [${"Battle report".tr()}]") {
                showCampaignShare(screen) { CampaignStore(screen.game.files).shareResult(d.id, state.attemptId) }
            }.apply { name = "ui-campaign-share-result" }).width(sideWidth).minHeight(UiNavigation.minimumTouchSize()).row()
        }
        if (!terminal) {
            val locations = d.areas.map { it.title to it.tiles.first() }.toMutableList()
            for ((i, tile) in d.targetTiles.withIndex()) {
                val name = screen.gameInfo.tileMap[tile].resource?.let { "[${it.tr()}]地块" } ?: "目标地块 [${i + 1}]"
                locations.add(name to tile)
            }
            screen.gameInfo.getCities().firstOrNull { it.id == d.targetCity }?.let {
                val title = when (d.number) { 12 -> "目标要塞"; 13 -> "占领城市"; else -> "目标首都" }
                locations.add(title to it.location)
            }
            if (d.id == CivilWarCampaign.id) screen.gameInfo.getCities().firstOrNull { it.id == state.capitalId }?.let {
                locations.add("华盛顿" to it.location)
                locations.add("哈珀斯费里 · 受损交通" to CivilWarCampaign.harpersFerry)
            }
            if (locations.isNotEmpty()) {
                side.add(bodyText("地图定位", sideWidth, Theme.gold, TextRole.Control)).row()
                for ((title, position) in locations) side.add(boardButton("定位 · [$title]") { locate(position) })
                    .width(sideWidth).minHeight(UiNavigation.minimumTouchSize()).row()
            }
        }
        if (!terminal) {
            side.add(boardButton(if (state.hintsDismissed) "展开教学提示 ›" else "收起教学提示 ⌄") {
                state.hintsDismissed = !state.hintsDismissed
                close(); CampaignTasksPopup(screen)
            }).width(sideWidth).minHeight(UiNavigation.minimumTouchSize()).row()
            if (!state.hintsDismissed) {
                for ((i, hint) in copy.hints.withIndex())
                    side.add(bodyText("${i + 1}. ${hint.tr()}", sideWidth, Theme.text, TextRole.Control)).row()
                side.add(bodyText("本关设置", sideWidth, Theme.gold, TextRole.Control)).padTop(6f).row()
                for (restriction in copy.restrictions) side.add(bodyText(restriction, sideWidth, Theme.muted, TextRole.Supporting)).row()
            }
            side.add(boardButton("保存并返回战役") { leave(d.id) }.apply {
                name = "ui-campaign-return"
            }).width(sideWidth).minHeight(UiNavigation.minimumTouchSize()).padTop(8f).row()
        }
        if (canShare && terminal) goals.add(boardButton("分享本次战报") {
            showCampaignShare(screen) { CampaignStore(screen.game.files).shareResult(d.id, state.attemptId) }
        }.apply { name = "ui-campaign-share-result" }).width(goalsWidth).minHeight(UiNavigation.minimumTouchSize()).padTop(4f).row()
        val board = Table().apply {
            add(goals).width(goalsWidth).top()
            if (!terminal) {
                if (wide) add(side).width(sideWidth).padLeft(24f).top()
                else { row(); add(side).width(sideWidth).padTop(16f) }
            }
        }
        add(board).width(boardWidth).row()
        getScrollPane()?.setScrollingDisabled(true, false)

        val statusWidth = if (wide) boardWidth * .43f else boardWidth
        val status = Table().apply {
            defaults().left()
            add(campaignStars(if (terminal) state.resultStars else result.stars, 24f)).row()
            val caption = when {
                result.failed -> "可返回战役，重新挑战"
                terminal -> "成绩已保存到本机 · 云同步状态见战役总览"
                d.automatic -> "按本关目标自动结算"
                result.main -> "当前可获得 [${result.stars}] 星 · 结算时检查目标"
                else -> "完成通关目标后可结算"
            }
            add(bodyText(caption, statusWidth, Theme.muted, TextRole.Supporting)).padTop(5f)
        }
        bottomTable.pad(14f, 25f, 18f, 25f)
        bottomTable.defaults().pad(0f).space(10f)
        val primary = when {
            state.resultStars > 0 && d.nextLevel.isNotEmpty() -> "前往下一关"
            terminal -> "返回战役"
            !d.automatic -> "结算通关"
            else -> null
        }
        bottomTable.add(status).width(statusWidth).expandX().left().apply {
            if (!wide) colspan(if (primary == null) 1 else 2).row()
        }
        addCloseButton(if (terminal) "查看地图" else "继续挑战")
            .width(if (wide) boardWidth * .24f else if (primary == null) boardWidth else (boardWidth - 10f) / 2)
            .minHeight(UiNavigation.minimumTouchSize())
        if (primary != null) addButton(primary, style = BaseScreen.skin.get("positive", TextButtonStyle::class.java)) {
            when {
                !terminal -> submit()
                state.resultStars > 0 && d.nextLevel.isNotEmpty() -> leave(d.nextLevel)
                else -> leave(d.id)
            }
        }.apply {
            width(if (wide) boardWidth * .29f else (boardWidth - 10f) / 2)
            minHeight(UiNavigation.minimumTouchSize())
            actor.label.setFontSize(UiTypography.size(TextRole.Control, legacySize = 18))
            if (!terminal) {
                actor.name = "ui-campaign-submit"
                actor.isDisabled = !result.main || !screen.isPlayersTurn || screen.isNextTurnUpdateRunning()
            }
        }
        open()
    }

    private fun bodyText(text: String, width: Float, color: Color = Theme.text, size: TextRole = TextRole.Body) =
        Table().apply { add(text.trCampaign().toLabel(size, color).apply { wrap = true }).width(width).left() }

    private fun boardButton(title: String, action: () -> Unit): TextButton = title.toTextButton().apply {
        label.setFontSize(UiTypography.size(TextRole.Control, legacySize = 17))
        label.wrap = true
        onActivation { action() }
    }

    private fun locate(position: HexCoord) {
        // Centering does not change explored tiles or remove fog of war.
        screen.mapHolder.setCenterPosition(position, immediately = true, selectUnit = false)
        close()
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
