package com.unciv.ui.screens.endless

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.Group
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.unciv.logic.GameInfo
import com.unciv.logic.endless.EndlessCatalog
import com.unciv.logic.endless.EndlessRules
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
import com.unciv.ui.popups.ConfirmPopup
import com.unciv.ui.popups.LoadingPopup
import com.unciv.ui.popups.ToastPopup
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.basescreen.RecreateOnResize
import com.unciv.utils.Concurrency

/** The adopted defense hall, populated exclusively from the local slot and versioned records. */
class EndlessScreen : BaseScreen(), RecreateOnResize {
    private val store = EndlessStore(game.files)
    private val root = Table()

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
                text("One city. Hold out as long as you can.", mapWidth - 36f, Theme.muted)
                val height = (mapWidth * .60f).coerceIn(200f, 380f)
                add(mapPreview(scene, mapWidth - 36f, height)).size(mapWidth - 36f, height).padTop(10f).row()
                text("Explored terrain · enemy units remain under fog of war", mapWidth - 36f, Theme.muted, 14)
                text("Six approaches · two full rounds of rest after an early clear", mapWidth - 36f, Theme.text, 16)
            }
            val status = panel(statusWidth).apply {
                val inner = statusWidth - 36f
                text(if (state?.ended == false) "Current defense" else "Ready the garrison", inner, Theme.gold, 24)
                if (state == null) {
                    text("No active defense. Your first record starts here.", inner, Theme.muted)
                    text("Population [4] · [160] Gold · [4] military units", inner)
                    text("The first wave arrives in round [6].", inner)
                } else {
                    text(endlessStatus(state), inner)
                    text("Completed [${state.completedRounds}] rounds · cleared [${state.clearedWaves}] waves", inner)
                    text("Seed [${state.seed}]", inner, Theme.muted, 14)
                    if (!compatible) text("This endless save needs a compatibility update. Your save and records have been kept.", inner, Theme.danger, 16)
                    if (state.ended) text(if (state.status == "Defeated") "The bastion has fallen." else if (state.status == "Quit") "Defense ended voluntarily." else "Defense paused by a rules error.", inner, Theme.muted)
                }
                if (state?.ended == false && compatible) {
                    action("Continue defense", "ui-endless-continue", inner, true) { enter(null, state.attemptId) }
                }
                action("New defense", "ui-endless-new", inner, state?.ended != false) { confirmStart(null, state) }
                if (state != null && compatible) action("Retry this seed", "ui-endless-retry", inner) { confirmStart(state.seed, state) }
                text("Saved on this device", inner, Theme.muted, 14)
            }
            val hero = Table().apply {
                add(mapPanel).width(mapWidth).top()
                if (wide) add(status).width(statusWidth).top().padLeft(18f)
                else { row(); add(status).width(statusWidth).padTop(14f) }
            }
            content.add(hero).width(width).row()
            val records = store.records()
            content.add(panel(width).apply {
                text("Defense records", width - 36f, Theme.gold, 23)
                val keys = linkedSetOf(EndlessCatalog.recordKey).apply {
                    addAll(records.best.keys.sorted()); addAll(records.recent.map { it.key })
                }
                for (key in keys) {
                    text(if (key == EndlessCatalog.recordKey) "Current rules" else "Previous rules version", width - 36f, Theme.gold, 17)
                    val version = key.split(':')
                    text("Map [${version.getOrNull(1) ?: "?"}] · rules [${version.getOrNull(3) ?: "?"}] · scoring [${version.getOrNull(4) ?: "?"}]", width - 36f, Theme.muted, 13)
                    val best = records.best[key]
                    text(if (best == null) "No record yet. Hold the bastion to set your best." else
                        "Best: [${best.rounds}] completed rounds · [${best.waves}] waves · [${best.kills}] kills", width - 36f)
                    for (record in records.recent.filter { it.key == key }) text(
                        "[${record.rounds}] rounds · [${record.waves}] waves · [${record.kills}] kills · [${if (record.status == "Quit") "Quit".tr() else "Defeated".tr()}]", width - 36f, Theme.muted, 15)
                }
            }).width(width).padTop(16f).row()
            content.add(panel(width).apply {
                text("Prepare · defend · resupply", width - 36f, Theme.gold, 23)
                text("Build your economy, research fourteen technologies and protect the core city. Clear five waves to choose Gold, repairs or one reinforcement.", width - 36f)
                text("A new wave arrives at most eight rounds after the previous wave. Clearing all enemies earlier brings it forward after two full rest rounds.", width - 36f)
                text("Losing the core city ends the defense. Your score counts fully completed rounds. Quitting is kept in recent history but does not set a best record.", width - 36f)
                text("Starting garrison: 2 Spearmen, 2 Archers and 1 Worker. Palace, Granary and Monument are already built.", width - 36f, Theme.muted, 16)
                text("Local singleplayer · one city · no ordinary victory conditions", width - 36f, Theme.muted, 14)
            }).width(width).padTop(16f).row()
            root.add(AutoScrollPane(content).apply { name = "ui-endless-hall-scroll"; setScrollingDisabled(true, false) })
                .grow().minHeight(0f).pad(0f, 22f, 14f, 22f)
        } catch (ex: Exception) {
            com.unciv.utils.Log.error("Cannot read endless hall", ex)
            root.add("Could not read the endless save. Existing files have been kept.".toLabel(Theme.danger).apply { wrap = true })
                .width(stage.width - 60f).pad(20f)
        }
    }

    private fun confirmStart(seed: Long?, state: EndlessState?) {
        if (state == null) { enter(seed ?: kotlin.random.Random.nextLong(), null); return }
        ConfirmPopup(this, "Replace the current defense?", "Start new defense") {
            enter(seed ?: kotlin.random.Random.nextLong(), state.attemptId)
        }.open()
    }

    private fun enter(seed: Long?, replacing: String?) {
        val loading = LoadingPopup(this)
        Concurrency.runOnGLThread {
            try {
                game.files.autosaves.autoSaveJob?.join()
                val attempt = if (seed == null) store.resume() else EndlessCatalog.newAttempt(seed).also { store.start(it, replacing) }
                game.loadGame(attempt)
            } catch (ex: Exception) {
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

    private fun Table.action(value: String, id: String, width: Float, primary: Boolean = false, action: () -> Unit) {
        add(value.toTextButton().apply {
            name = id; label.setFontSize(20)
            if (primary) style = TextButton.TextButtonStyle(style).apply {
                up = ImageGetter.getWhiteDotDrawable().tint(Theme.gold)
                down = ImageGetter.getWhiteDotDrawable().tint(Theme.muted)
                fontColor = Theme.primaryInk
            }
            onActivation { action() }
        }).width(width).minHeight(maxOf(48f, UiNavigation.minimumTouchSize())).padTop(3f).row()
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
    s.ended -> "Completed [${s.completedRounds}] rounds"
    s.offer != null -> "Stage [${s.offer!!.stage}] cleared · choose a reward"
    s.waveNumber == 0 -> "Preparation · first wave in [${(6 - s.round).coerceAtLeast(0)}] rounds"
    else -> "Wave [${s.waveNumber}] · [${s.activeCount}] enemies · [${s.pendingCount}] waiting · next in [${(s.nextWaveRound - s.round).coerceAtLeast(0)}] rounds"
}
