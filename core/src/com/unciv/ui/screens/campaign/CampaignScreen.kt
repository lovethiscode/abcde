package com.unciv.ui.screens.campaign

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.Group
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.unciv.logic.GameInfo
import com.unciv.logic.campaign.CampaignCatalog
import com.unciv.logic.campaign.CampaignDefinition
import com.unciv.logic.campaign.CampaignProgress
import com.unciv.logic.campaign.CampaignStore
import com.unciv.models.skins.ExpeditionBlueTheme as Theme
import com.unciv.models.translations.tr
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
import com.unciv.ui.screens.newgamescreen.NewGameScreen
import com.unciv.utils.Concurrency
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** The journey map and detail panel both read the real catalog, slots, and versioned results. */
class CampaignScreen(private var chapter: Int = -1, private var selected: String = "") : BaseScreen(), RecreateOnResize {
    private val store = CampaignStore(game.files)
    private val definitions = CampaignCatalog.definitions()
    private val root = Table()
    private var progress = CampaignProgress()

    init {
        root.setFillParent(true)
        root.background = ImageGetter.getWhiteDotDrawable().tint(Theme.background)
        stage.addActor(root)
        rebuild()
    }

    private fun rebuild() {
        root.clearChildren()
        try {
            definitions.forEach { store.saved(it.id)?.let(store::reconcile) }
            progress = store.progress()
            val content = Table().apply { pad(22f); defaults().pad(8f) }
            content.add("文明的诞生".toLabel(Theme.gold, 32)).left().row()
            content.add("新手战役  ·  四章十六关  ·  ${definitions.sumOf { store.stars(it, progress) }} / 48 星".toLabel(Theme.text)).left().row()
            content.add("进度仅保存在本机".toLabel(Theme.muted, 15)).left().row()
            if (chapter < 0) overview(content) else chapter(content)
            content.add("Back".toTextButton().apply {
                onActivation { if (chapter >= 0) { chapter = -1; rebuild() } else game.popScreen() }
            }).height(50f).fillX().row()
            root.add(AutoScrollPane(content)).grow()
        } catch (ex: Exception) {
            com.unciv.utils.Log.error("Cannot open campaign catalog", ex)
            root.add("战役暂时无法读取，已有存档与成绩仍保留。".toLabel()).row()
            root.add("Back".toTextButton().apply { onActivation { game.popScreen() } })
        }
    }

    private fun overview(content: Table) {
        val recent = progress.lastPlayed.takeIf { it.isNotEmpty() }?.let(store::saved)
        if (recent?.campaign?.let { it.resultStars == 0 && !it.failed } == true) {
            val d = recent.campaign!!.definition
            content.add("继续最近挑战：${d.title} · 第 ${recent.campaign!!.completedRounds + 1} 回合".toTextButton().apply {
                name = "ui-campaign-recent"
                onActivation { enter(d.id, false) }
            }).height(58f).fillX().row()
        }
        for ((index, title) in CampaignCatalog.chapters.withIndex()) {
            val levels = definitions.subList(index * 4, index * 4 + 4)
            val complete = levels.all { it.id in progress.cleared }
            val mastery = levels.all { store.stars(it, progress) == 3 }
            val enabled = levels.any { store.isUnlocked(it, progress) }
            val status = when { mastery -> "精通勋章"; complete -> "章节完成"; enabled -> "探索中"; else -> "待解锁" }
            val card = Table().apply {
                background = ImageGetter.getWhiteDotDrawable().tint(if (enabled) Theme.panel else Theme.unexplored)
                pad(14f); defaults().pad(5f)
                add("0${index + 1}  $title".toLabel(Theme.gold, 24)).left().row()
                add("$status   ·   ${levels.sumOf { store.stars(it, progress) }} / 12 星".toLabel(Theme.muted)).left().row()
                add("查看章节地图".toTextButton().apply {
                    name = "ui-campaign-chapter:$index"
                    onActivation {
                        chapter = index
                        selected = levels.firstOrNull { store.isUnlocked(it, progress) && store.stars(it, progress) < 3 }?.id ?: levels.first().id
                        rebuild()
                    }
                }).height(50f).fillX()
            }
            content.add(card).width((stage.width - 60f).coerceAtMost(900f)).fillX().row()
        }
        val historical = progress.medals.count { key -> (0..3).none { key == store.medalKey(it, definitions.subList(it * 4, it * 4 + 4)) } }
        if (historical > 0) content.add("历史精通勋章：$historical（旧评分版本）".toLabel(Theme.muted)).row()
        content.add("未来主题".toLabel(Theme.gold, 23)).left().padTop(20f).row()
        val future = Table().apply { defaults().pad(5f) }
        for ((i, title) in CampaignCatalog.futureThemes.withIndex()) {
            future.add("$title · 筹备中".toTextButton().apply {
                name = "ui-campaign-future:$i"
                onActivation { ToastPopup("筹备中，敬请期待", this@CampaignScreen) }
            }).minWidth(145f).height(54f)
            if (i % (if (stage.width > 750f) 3 else 2) == (if (stage.width > 750f) 2 else 1)) future.row()
        }
        content.add(future).row()
    }

    private fun chapter(content: Table) {
        val levels = definitions.subList(chapter * 4, chapter * 4 + 4)
        val d = levels.firstOrNull { it.id == selected } ?: levels.first().also { selected = it.id }
        content.add("${chapter + 1}  ${CampaignCatalog.chapters[chapter]}".toLabel(Theme.gold, 26)).left().row()
        val wide = stage.width >= 1000f
        val width = if (wide) (stage.width * .49f).coerceAtMost(650f) else (stage.width - 65f).coerceAtMost(700f)
        val map = routeMap(levels, width)
        val detailWidth = if (wide) (stage.width * .40f).coerceAtMost(600f) else width
        val body = Table().apply { defaults().pad(8f).top() }
        body.add(map).size(width, 410f)
        if (!wide) body.row()
        body.add(details(d, detailWidth)).width(detailWidth).growY()
        content.add(body).row()
        if (levels.all { it.id in progress.cleared }) {
            content.add("章节完成 · 在自由对局中练习".toTextButton().apply {
                onActivation { game.pushScreen { NewGameScreen() } }
            }).height(52f).fillX().row()
        }
    }

    private fun routeMap(levels: List<CampaignDefinition>, width: Float): Group {
        val map = Group().apply { setSize(width, 410f) }
        // Subdued hex terrain and a meandering river keep the path readable at phone size.
        for (x in 0..(width / 44).toInt()) for (y in 0..8) {
            val cx = 24f + x * 44f
            val cy = 22f + y * 44f + if (x % 2 == 0) 0f else 22f
            for (edge in 0..5) {
                val a = edge * Math.PI / 3
                val b = (edge + 1) * Math.PI / 3
                line(map, cx + cos(a).toFloat() * 24, cy + sin(a).toFloat() * 24,
                    cx + cos(b).toFloat() * 24, cy + sin(b).toFloat() * 24, Theme.border.cpy().apply { this.a = .28f }, 1f)
            }
        }
        for (i in 0..7) line(map, width * .56f + sin(i.toFloat()) * 20, i * 54f,
            width * .56f + sin(i + 1f) * 20, (i + 1) * 54f, Color.valueOf("315F71"), 7f)
        val positions = listOf(.24f to .18f, .72f to .38f, .27f to .62f, .70f to .85f)
        for (i in 0..2) {
            val a = positions[i]; val b = positions[i+1]
            line(map, a.first * width, a.second * 410, b.first * width, b.second * 410,
                if (levels[i].id in progress.cleared) Theme.gold else Theme.border, 3f)
        }
        for ((i, d) in levels.withIndex()) {
            val unlocked = store.isUnlocked(d, progress)
            val saved = store.saved(d.id)?.campaign
            val status = when {
                !unlocked && saved == null -> "锁定"
                saved?.let { it.resultStars == 0 && !it.failed } == true -> "续玩"
                d.id in progress.cleared -> "已通关"
                else -> "可挑战"
            }
            val stars = store.stars(d, progress)
            val node = "${d.number}  ${d.title}\n${"★".repeat(stars)}${"☆".repeat(3-stars)}  $status".toTextButton().apply {
                name = "ui-campaign-level:${d.id}"
                setSize((width * .42f).coerceAtMost(210f), 74f)
                setPosition(positions[i].first * width - this.width / 2, positions[i].second * 410 - height / 2)
                color = if (d.id == selected) Theme.gold else if (unlocked) Color.WHITE else Theme.muted
                onActivation { selected = d.id; rebuild() }
            }
            map.addActor(node)
        }
        return map
    }

    private fun details(d: CampaignDefinition, width: Float): Table = Table().apply {
        background = ImageGetter.getWhiteDotDrawable().tint(Theme.panel)
        pad(16f); defaults().pad(6f).left()
        fun text(value: String, color: Color = Theme.text, size: Int = 18) {
            add(value.toLabel(color, size).apply { wrap = true }).width(width - 44f).row()
        }
        text("${d.number}. ${d.title}", Theme.gold, 25)
        text(d.description)
        text("最高成绩：${store.stars(d, progress)} / 3 星", Theme.gold)
        for ((i, goal) in d.objectives.withIndex()) text("${if (i == 0) "通关" else "挑战 ${if (i == 1) "A" else "B"}"} · $goal")
        text(if (d.automatic) "达成条件后自动结算" else "主目标达成后主动结算；可继续争取挑战星", Theme.muted)
        val scene = CampaignCatalog.template(d.id)
        val player = scene.civilizations.single { it.isHuman() }
        text("初始配置", Theme.gold, 21)
        text("金币 ${player.gold} · 幸福度 ${player.getHappiness()} · 净收入 ${player.stats.statsForNextTurn.gold.toInt()}")
        for (city in player.cities) text("${city.name.tr()}：人口 ${city.population.population}；${city.cityConstructions.getBuiltBuildings().joinToString("、") { it.name.tr() }}")
        if (player.cities.isEmpty()) text("尚未建城，从移民开始。")
        text("单位：" + player.units.getCivUnits().groupingBy { it.name }.eachCount().entries.joinToString("、") { "${it.key.tr()} ×${it.value}" })
        text("科技：" + player.tech.techsResearched.joinToString("、") { it.tr() })
        add(initialPreview(scene, width - 44f)).size(width - 44f, 180f).row()
        text("地图预览仅显示开局已探索地形；金色标记为任务区域。", Theme.muted, 15)
        text("失败条件：玩家文明被消灭。" + when (d.number) {
            11 -> "初始首都一旦失守，本次立即失败。"
            12 -> "第 25 轮结束仍未保留目标城，或目标城永久摧毁。"
            13, 14 -> "目标城市永久摧毁后无法继续完成本关。"
            15, 16 -> "对手获得征服胜利。"
            else -> "普通教学关没有硬性时间限制。"
        }, Theme.muted, 16)
        for (restriction in d.restrictions) text(restriction, Theme.muted, 16)
        for (hint in d.hints) text("提示 · $hint", Theme.muted, 16)
        val history = progress.bestStars.filterKeys { it.startsWith("${d.id}:") && it != "${d.id}:${d.scoringVersion}" }
        for ((key, value) in history) text("历史评分版本 ${key.substringAfter(':')}：$value / 3 星", Theme.muted, 15)
        val saved = store.saved(d.id)
        val unfinished = saved?.campaign?.let { it.resultStars == 0 && !it.failed } == true
        if (unfinished) {
            text("本地续玩 · 行动回合 ${saved!!.campaign!!.completedRounds + 1}")
            add("Continue campaign".toTextButton().apply {
                name = "ui-campaign-continue"
                onActivation { enter(d.id, false) }
            }).height(54f).fillX().row()
        }
        if (store.isUnlocked(d, progress) || saved != null) {
            add((if (saved == null) "Start campaign" else "Restart campaign").toTextButton().apply {
                name = "ui-campaign-start"
                onActivation {
                    if (unfinished) ConfirmPopup(this@CampaignScreen,
                        "Replace this level's unfinished attempt? Your best result will be kept.", "Restart campaign") {
                        enter(d.id, true, saved!!.campaign!!.attemptId)
                    }.open()
                    else enter(d.id, true, saved?.campaign?.attemptId)
                }
            }).height(54f).fillX().row()
        } else text("上一关至少获得一星后解锁。", Theme.muted)
    }

    private fun initialPreview(scene: GameInfo, width: Float): Group {
        val group = Group().apply { setSize(width, 180f) }
        val player = scene.civilizations.single { it.isHuman() }
        val radius = scene.tileMap.mapParameters.mapSize.radius.coerceAtLeast(1)
        val scale = minOf(width / (radius * 4 + 4), 160f / (radius * 2 + 2))
        val targets = CampaignCatalog.definition(selected).let { it.areas.flatMap { a -> a.tiles } + it.targetTiles }
        for (tile in scene.tileMap.tileList) {
            val revealed = tile.isExplored(player)
            val color = when {
                tile.position in targets -> Theme.gold
                !revealed -> Theme.unexplored
                tile.isCityCenter() -> Theme.text
                tile.terrainFeatures.isNotEmpty() -> Theme.border
                else -> Theme.raised
            }
            group.addActor(ImageGetter.getWhiteDot().apply {
                this.color = color
                setBounds(width / 2 + (tile.position.x - tile.position.y * .5f) * scale,
                    90f + tile.position.y * scale * .85f, scale * .85f, scale * .7f)
                touchable = Touchable.disabled
            })
        }
        return group
    }

    private fun line(group: Group, x1: Float, y1: Float, x2: Float, y2: Float, color: Color, thickness: Float) {
        group.addActor(ImageGetter.getWhiteDot().apply {
            this.color = color
            setBounds(x1, y1, sqrt((x2-x1)*(x2-x1)+(y2-y1)*(y2-y1)), thickness)
            rotation = Math.toDegrees(atan2((y2-y1).toDouble(), (x2-x1).toDouble())).toFloat()
            touchable = Touchable.disabled
        })
    }

    private fun enter(id: String, start: Boolean, replacing: String? = null) {
        val loading = LoadingPopup(this)
        Concurrency.runOnGLThread {
            try {
                game.files.autosaves.autoSaveJob?.join()
                check(store.isUnlocked(CampaignCatalog.definition(id), store.progress()) || store.saved(id) != null)
                val attempt = if (start) CampaignCatalog.newAttempt(id).also { store.start(it, replacing) } else store.resume(id)
                game.loadGame(attempt)
            } catch (ex: Exception) {
                com.unciv.utils.Log.error("Cannot enter campaign", ex)
                ToastPopup(ex.message ?: "Could not load campaign", this@CampaignScreen)
            } finally { loading.close() }
        }
    }

    override fun recreate(): BaseScreen = CampaignScreen(chapter, selected)
}
