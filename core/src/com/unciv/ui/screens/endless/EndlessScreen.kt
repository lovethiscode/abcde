package com.unciv.ui.screens.endless

import com.unciv.logic.challenges.ChallengeTelemetry
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.Group
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.unciv.logic.GameInfo
import com.unciv.logic.endless.EndlessCatalog
import com.unciv.logic.endless.EndlessRules
import com.unciv.logic.endless.EndlessShareData
import com.unciv.logic.endless.EndlessState
import com.unciv.logic.endless.EndlessStore
import com.unciv.models.skins.ExpeditionBlueTheme as Theme
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.setFontSize
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.input.onActivation
import com.unciv.ui.components.tilegroups.TileSetStrings
import com.unciv.ui.components.widgets.AutoScrollPane
import com.unciv.ui.components.widgets.UiNavigation
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.popups.LoadingPopup
import com.unciv.ui.popups.ToastPopup
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.basescreen.RecreateOnResize
import com.unciv.utils.Concurrency

/** The adopted defense hall, populated exclusively from the local slot and versioned records. */
class EndlessScreen : BaseScreen(), RecreateOnResize {
    private val store = EndlessStore(game.files)
    private val root = Table()
    private var recordsPace = "casual"

    init {
        root.setFillParent(true)
        root.background = ImageGetter.getWhiteDotDrawable().tint(Theme.background)
        stage.addActor(root)
        build()
    }

    private fun build() {
        root.clearChildren()
        val header = Table().apply {
            add(UiNavigation.backButton().apply { onActivation { game.popScreen() } }).minHeight(UiNavigation.minimumTouchSize())
            add("Defense hall".toLabel(Theme.gold, 28)).expandX().left().padLeft(14f)
            add("Standard".toLabel(Theme.muted, 17)).right().padRight(10f)
        }
        root.add(header).growX().pad(12f, 18f, 10f, 18f).row()
        try {
            val saved = store.saved()
            saved?.let(store::reconcile)
            val state = saved?.endless
            val compatible = saved == null || runCatching { EndlessCatalog.requireCompatible(saved) }.isSuccess
            val scene = if (saved != null && compatible && state?.status != "Faulted") saved.also { it.setTransients() } else EndlessCatalog.newAttempt(1101)
            val width = stage.width - 44f
            val wide = width >= 760f
            val mapWidth = if (wide) (width - 18f) * .60f else width
            val statusWidth = if (wide) width - mapWidth - 18f else width
            val content = Table().apply { top(); defaults().left() }
            val mapPanel = panel(mapWidth).apply {
                text("Border Bastion", mapWidth - 36f, Theme.gold, 25)
                text("Five waves per stage. Clear the stage before its deadline; queued enemies do not extend it.", mapWidth - 36f, Theme.muted)
                val height = (mapWidth * .60f).coerceIn(200f, 380f)
                add(mapPreview(scene, mapWidth - 36f, height)).size(mapWidth - 36f, height).padTop(10f).row()
                text("Explored terrain · enemy units remain under fog of war", mapWidth - 36f, Theme.muted, 14)
                text("Up to 3 cities. Clear stages 2 and 4 to unlock city slots; side cities are destroyed when captured.", mapWidth - 36f, Theme.text, 16)
            }
            val status = panel(statusWidth).apply {
                val inner = statusWidth - 36f
                text(if (state?.ended == false) "Current defense" else "Ready the garrison", inner, Theme.gold, 24)
                if (state == null) {
                    text("No active defense. Your first record starts here.", inner, Theme.muted)
                    text("Population [4] · [160] Gold · [4] military units", inner)
                    text("The first wave arrives in round [6].", inner)
                } else {
                    text(if (state.stageState == null) "Legacy rules".tr() + " · " + endlessStatus(state).tr() else endlessStatus(state), inner)
                    text("Completed [${state.completedRounds}] rounds · cleared [${state.clearedWaves}] waves", inner)
                    if (!compatible) text("This endless save needs a compatibility update. Your save and records have been kept.", inner, Theme.danger, 16)
                    if (state.ended) text(if (state.stageState?.endReason == "StageTimeout") "The stage deadline was missed." else if (state.status == "Defeated") "The bastion has fallen." else if (state.status == "Quit") "Defense ended voluntarily." else "Defense paused by a rules error.", inner, Theme.muted)
                }
                if (state?.ended == false && compatible) {
                    action("Continue defense", "ui-endless-continue", inner, true) { resumeDefense() }
                }
                if (state != null) {
                    action("Endless settings", "ui-endless-settings", inner) {
                        game.pushScreen { EndlessConfigScreen(savedState = state.clone()) }
                    }.apply { label.setWrap(true); labelCell.width(inner - 24f) }
                }
                action("New defense", "ui-endless-new", inner, state?.ended != false) {
                    game.pushScreen { EndlessConfigScreen(replacingAttempt = state?.attemptId) }
                }
                if (game.endlessSharingAvailable) state?.let(EndlessShareData::fromState)?.let { data ->
                    action("Share defense", "ui-endless-share-current", inner, false) { game.showEndlessShare(data) }
                }
                text("Saved on this device", inner, Theme.muted, 14)
            }
            val hero = Table().apply {
                add(mapPanel).width(mapWidth).top()
                if (wide) add(status).width(statusWidth).top().padLeft(18f)
                else { row(); add(status).width(statusWidth).padTop(14f) }
            }
            content.add(hero).width(width).row()
            content.add(panel(width).apply {
                text("Defense records", width - 36f, Theme.gold, 23)
                for (option in listOf("casual","wild")) {
                    val title = (if (option == "casual") "Casual" else "Wild").tr() + if (option == recordsPace) " ✓" else ""
                    action(title,"ui-endless-records-pace:$option",width - 36f,option == recordsPace) { recordsPace = option; build() }
                }
                for (newRules in listOf(true,false)) {
                val records = store.records(newRules)
                val keys = (if (newRules) linkedSetOf(EndlessCatalog.newRecordKey("casual"),EndlessCatalog.newRecordKey("wild")) else linkedSetOf<String>()).apply {
                    addAll(records.best.keys.sorted()); addAll(records.recent.map { it.key })
                }
                for (key in keys.filter { !newRules || it.split(':').getOrNull(4) == recordsPace }) {
                    text(if (newRules) if (key.split(':').getOrNull(4) == "wild") "Wild" else "Casual" else "Legacy rules", width - 36f, Theme.gold, 17)
                    val version = key.split(':')
                    text("Map [${version.getOrNull(if (newRules) 2 else 1) ?: "?"}] · rules [${version.getOrNull(if (newRules) 5 else 3) ?: "?"}] · scoring [${version.getOrNull(if (newRules) 6 else 4) ?: "?"}]", width - 36f, Theme.muted, 13)
                    val best = records.best[key]
                    text(if (best == null) "No record yet. Hold the bastion to set your best." else if (newRules)
                        "Best result".tr() + ": " + "[${best.stages}] stages · [${best.partialWaves}] waves · [${best.combatRounds}] combat rounds".tr() else
                        "Best: [${best.rounds}] completed rounds · [${best.waves}] waves · [${best.kills}] kills", width - 36f)
                    if (game.endlessSharingAvailable) best?.let(EndlessShareData::fromRecord)?.let { data ->
                        action("Share defense", "ui-endless-share-best:${best.attemptId}", width - 36f, false) { game.showEndlessShare(data) }
                    }
                    for (record in records.recent.filter { it.key == key }) {
                        text(if (newRules) "[${record.stages}] stages · [${record.partialWaves}] waves · [${record.combatRounds}] combat rounds" else
                            "[${record.rounds}] rounds · [${record.waves}] waves · [${record.kills}] kills · [${if (record.status == "Quit") "Ended voluntarily".tr() else "Defeated".tr()}]", width - 36f, Theme.muted, 15)
                        if (game.endlessSharingAvailable) EndlessShareData.fromRecord(record)?.let { data ->
                            action("Share defense", "ui-endless-share-record:${record.attemptId}", width - 36f, false) { game.showEndlessShare(data) }
                        }
                    }
                }
                }
            }).width(width).padTop(16f).row()
            content.add(panel(width).apply {
                text("Prepare · defend · resupply", width - 36f, Theme.gold, 23)
                text("Five waves per stage. Clear the stage before its deadline; queued enemies do not extend it.", width - 36f)
                text("Casual: 3 full rest rounds; Gold, repairs or reinforcement.", width - 36f)
                text("Wild: no rest; Gold or reinforcement.", width - 36f)
                text("Core loss or a missed deadline ends the defense. Scores compare stages, waves and completed-stage combat time.", width - 36f)
                text("Starting garrison: 2 Spearmen, 2 Archers and 1 Worker. Palace, Granary and Monument are already built.", width - 36f, Theme.muted, 16)
                text("Up to 3 cities. Clear stages 2 and 4 to unlock city slots; side cities are destroyed when captured.", width - 36f, Theme.muted, 14)
            }).width(width).padTop(16f).row()
            root.add(AutoScrollPane(content).apply { name = "ui-endless-hall-scroll"; setScrollingDisabled(true, false) })
                .grow().minHeight(0f).pad(0f, 22f, 14f, 22f)
        } catch (ex: Exception) {
            com.unciv.utils.Log.error("Cannot read endless hall", ex)
            root.add("Could not read the endless save. Existing files have been kept.".toLabel(Theme.danger).apply { wrap = true })
                .width(stage.width - 60f).pad(20f)
        }
    }

    private fun resumeDefense() {
        val loading = LoadingPopup(this)
        Concurrency.runOnGLThread {
            try {
                game.files.autosaves.autoSaveJob?.join()
                val attempt = store.resume()
                game.loadGame(attempt)
                ChallengeTelemetry.report("resume", attempt)
            } catch (ex: Exception) {
                if (ex !is kotlinx.coroutines.CancellationException)
                    ChallengeTelemetry.error("endless", "resume_failed")
                com.unciv.utils.Log.error("Cannot enter endless defense", ex)
                ToastPopup(ex.message ?: "Could not start defense", this@EndlessScreen)
            } finally { loading.close() }
        }
    }

    private fun panel(width: Float) = Table().apply {
        background = BaseScreen.skin.newDrawable("RoundedEdgeRectangle", Theme.panel)
        pad(18f); top(); left(); defaults().left().spaceBottom(10f)
        this.width = width
    }

    private fun Table.text(value: String, width: Float, color: Color = Theme.text, size: Int = 18) {
        add(value.toLabel(color, size).apply { wrap = true }).width(width).left().row()
    }

    private fun Table.action(value: String, id: String, width: Float, primary: Boolean = false, action: () -> Unit): TextButton {
        val button = value.toTextButton().apply {
            name = id; label.setFontSize(20)
            if (primary) style = TextButton.TextButtonStyle(style).apply {
                up = ImageGetter.getWhiteDotDrawable().tint(Theme.gold)
                down = ImageGetter.getWhiteDotDrawable().tint(Theme.muted)
                fontColor = Theme.primaryInk
            }
            onActivation { action() }
        }
        add(button).width(width).minHeight(maxOf(48f, UiNavigation.minimumTouchSize())).padTop(3f).row()
        return button
    }

    private fun mapPreview(scene: GameInfo, width: Float, height: Float): Group {
        val group = Group().apply { setSize(width, height); touchable = Touchable.disabled }
        val scale = minOf((width - 40f) / 32, (height - 36f) / 37)
        val hexagon = TileSetStrings().hexagon
        val viewer = EndlessRules.player(scene)
        for (tile in scene.tileMap.values) {
            val position = com.unciv.logic.map.HexMath.hex2WorldCoords(tile.position)
            val x = width / 2 + position.x * scale
            val y = height / 2 + position.y * scale
            group.addActor(ImageGetter.getImage(hexagon).apply {
                color = when {
                    tile.isCityCenter() -> Theme.gold
                    tile.getOwner() == viewer -> Theme.color(Color.valueOf("a5b892"), Color.valueOf("42686d"))
                    tile.terrainFeatures.isNotEmpty() -> Theme.color(Color.valueOf("738c78"), Color.valueOf("27473f"))
                    else -> Theme.color(Color.valueOf("c4d1c0"), Theme.raised)
                }
                setBounds(x - scale, y - scale * .866f, scale * 2, scale * 1.732f)
            })
            if (tile.isCityCenter()) group.addActor(ImageGetter.getConstructionPortrait("Palace", 28f).apply { setPosition(x - 14f, y - 14f) })
            else if (tile.militaryUnit != null && tile in viewer.viewableTiles) group.addActor(
                ImageGetter.getUnitIcon(tile.militaryUnit!!.baseUnit, if (tile.militaryUnit!!.civ == viewer) Theme.text else Theme.danger)
                    .apply { setBounds(x - 7f, y - 7f, 14f, 14f) })
        }
        for (sector in 0..5) {
            val angle = Math.PI + sector * Math.PI / 3
            val x = width / 2 + kotlin.math.cos(angle).toFloat() * (width / 2 - 26f)
            val y = height / 2 + kotlin.math.sin(angle).toFloat() * (height / 2 - 14f)
            group.addActor(directionName(sector).toLabel(Theme.muted, 12).apply { setPosition(x - prefWidth / 2, y - prefHeight / 2) })
        }
        return group
    }

    override fun recreate(): BaseScreen = EndlessScreen()
}

internal fun endlessStatus(s: EndlessState): String = when {
    s.saveBlocked -> "Save failed · retry required"
    s.stageState != null -> stageStatus(s)
    s.ended -> "Completed [${s.completedRounds}] rounds"
    s.offer != null -> "Stage [${s.offer!!.stage}] cleared · choose a reward"
    s.waveNumber == 0 -> "Preparation · first wave in [${(6 - s.round).coerceAtLeast(0)}] rounds"
    else -> "Wave [${s.waveNumber}] · [${s.activeCount}] enemies · [${s.pendingCount}] waiting · next in [${(s.nextWaveRound - s.round).coerceAtLeast(0)}] rounds"
}

private fun stageStatus(s: EndlessState): String {
    val state = s.stageState!!
    val pace = (if (state.pace == "wild") "Wild" else "Casual").tr()
    val text = when {
        s.ended -> "[${state.completedStages}] stages · [${if (state.phase == "Combat") s.waves.count { it.cleared } else 0}] waves · [${state.combatRounds}] combat rounds"
        state.phase == "RewardPending" -> "Stage [${state.completedStages}] cleared · choose a reward"
        state.phase == "Preparation" -> "Preparation · first wave in [${(6 - s.round).coerceAtLeast(0)}] rounds"
        state.phase == "Rest" -> "Rest · [${minOf(if (state.pace == "wild") 0 else 3,(state.nextStageRound - s.round).coerceAtLeast(0))}] full rounds until stage [${state.completedStages + 1}]"
        else -> "Stage [${state.currentStage}] · combat round [${s.round - state.startRound + 1}]/[${state.deadline - state.startRound + 1}] · [${state.deadline - s.round + 1}] rounds remaining"
    }
    return pace + " · " + text.tr()
}
