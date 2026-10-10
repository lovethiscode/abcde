package com.unciv.ui.screens.campaign

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.Group
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.badlogic.gdx.utils.Align
import com.unciv.logic.GameInfo
import com.unciv.logic.campaign.CivilWarCampaign
import com.unciv.logic.campaign.CampaignCatalog
import com.unciv.logic.campaign.CampaignDefinition
import com.unciv.logic.campaign.CampaignProgress
import com.unciv.logic.campaign.CampaignStore
import com.unciv.logic.campaign.CampaignSyncState
import com.unciv.logic.campaign.CampaignSyncStatus
import com.unciv.models.skins.ExpeditionBlueTheme as Theme
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.setFontSize
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.input.onActivation
import com.unciv.ui.components.widgets.AutoScrollPane
import com.unciv.ui.components.widgets.UiNavigation
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
class CampaignScreen(private var chapter: Int = -1, private var selected: String = "",
                     private var historical: Boolean = selected == CivilWarCampaign.id) : BaseScreen(), RecreateOnResize {
    private val store = CampaignStore(game.files)
    private val definitions = CampaignCatalog.definitions()
    private val root = Table()
    private var progress = CampaignProgress()
    private val sync = game.files.campaignProgressSync
    private val syncPanel = Table()
    private var mapScrollPane: AutoScrollPane? = null
    private var detailScrollPane: AutoScrollPane? = null
    private var renderedChapter = -1
    private var disposed = false
    private val syncListener: (CampaignSyncStatus) -> Unit = { status ->
        Concurrency.runOnGLThread {
            if (!disposed) {
                updateSyncPanel(status)
                val latest = runCatching { store.progress() }.getOrNull()
                if (latest == null || !progress.samePermanentFacts(latest)) rebuild()
            }
        }
    }

    init {
        root.setFillParent(true)
        root.background = ImageGetter.getWhiteDotDrawable().tint(Theme.background)
        stage.addActor(root)
        rebuild()
        sync.addStatusListener(syncListener)
        sync.requestSync()
    }

    private fun rebuild() {
        val mapScrollY = if (renderedChapter == chapter) mapScrollPane?.scrollY ?: 0f else 0f
        val detailScrollY = if (renderedChapter == chapter) detailScrollPane?.scrollY ?: 0f else 0f
        mapScrollPane = null
        detailScrollPane = null
        root.clearChildren()
        val header = Table().apply {
            background = skinStrings.getUiBackground(
                "CampaignScreen/Header", skinStrings.roundedEdgeRectangleSmallShape, Theme.raised)
            add(UiNavigation.backButton().apply {
                label.setFontSize(22)
                onActivation {
                    if (historical && selected.isNotEmpty()) { selected = ""; rebuild() }
                    else if (chapter >= 0) { chapter = -1; rebuild() } else game.popScreen()
                }
            }).minHeight(maxOf(46f, UiNavigation.minimumTouchSize())).padLeft(8f)
            add((if (historical) "历史战役" else "文明的诞生").toLabel(Theme.gold, 28)).growX().minWidth(0f).left().pad(10f)
        }
        root.add(header).growX().minHeight(maxOf(64f, UiNavigation.minimumTouchSize()))
            .pad(8f, 10f, 4f, 10f).row()
        val themes = Table().apply {
            defaults().pad(4f)
            add("更多战役".toLabel(Theme.gold, 17)).padRight(10f)
            for ((index, title) in (listOf("新手", "历史战役") + CampaignCatalog.futureThemes).withIndex()) {
                add((title + if (index >= 2) " · 筹备中" else "").toTextButton().apply {
                    name = "ui-campaign-theme:$index"
                    isChecked = index == if (historical) 1 else 0
                    style = TextButton.TextButtonStyle(style).apply {
                        checked = Theme.outlinedControl(BaseScreen.skin.newDrawable("RoundedEdgeRectangle", Theme.raised),
                            BaseScreen.skin.newDrawable("RoundedEdgeRectangle", Theme.gold))
                    }
                    onActivation {
                        if (index >= 2) {
                            isChecked = false
                            ToastPopup("筹备中，敬请期待", this@CampaignScreen)
                        } else {
                            historical = index == 1; selected = ""; chapter = -1; rebuild()
                        }
                    }
                }).minWidth(100f).minHeight(UiNavigation.minimumTouchSize())
            }
        }
        root.add(AutoScrollPane(themes).apply { setScrollingDisabled(false, true) }).growX()
            .height(maxOf(58f, UiNavigation.minimumTouchSize() + 8f)).pad(0f, 10f, 4f, 10f).row()
        try {
            (definitions + CampaignCatalog.historicalDefinitions()).forEach { store.saved(it.id)?.let(store::reconcile) }
            progress = store.progress()
            val content = Table().apply { pad(22f); defaults().pad(8f) }
            val progressText = if (historical) "历史战役 · 当前版本 · ${CampaignCatalog.historicalDefinitions().sumOf { store.stars(it, progress) }} / 3 星"
                else "新手战役  ·  当前版本  ·  四章十六关  ·  ${definitions.sumOf { store.stars(it, progress) }} / 48 星"
            content.add(progressText.toLabel(Theme.text).apply { wrap = true }).growX().minWidth(0f).left().row()
            updateSyncPanel(sync.status)
            content.add(syncPanel).growX().row()
            if (historical) {
                val d = CampaignCatalog.definition(CivilWarCampaign.id)
                if (selected == d.id) content.add(details(d, (stage.width - 80f).coerceAtMost(900f))).growX().row()
                else content.add(Table().apply {
                    defaults().pad(10f).left()
                    add(d.title.toLabel(Theme.gold, 24)).row()
                    add("固定联邦视角 · 最高 3 星 · 建议先掌握移动、战斗、攻城与生产".toLabel(Theme.muted).apply { wrap = true })
                        .width((this@CampaignScreen.stage.width - 100f).coerceAtMost(820f)).row()
                    add("查看战役".toTextButton().apply {
                        name = "ui-campaign-historical:civil-war-east"
                        onActivation { selected = d.id; rebuild() }
                    }).minHeight(UiNavigation.minimumTouchSize()).row()
                }).growX().row()
                root.add(AutoScrollPane(content).apply { setScrollingDisabled(true, false) }).grow()
            } else if (chapter < 0) {
                overview(content)
                root.add(AutoScrollPane(content)).grow()
            } else chapter(content)
            renderedChapter = chapter
            root.validate()
            mapScrollPane?.apply { scrollY = mapScrollY; updateVisualScroll() }
            detailScrollPane?.apply { scrollY = detailScrollY; updateVisualScroll() }
        } catch (ex: Exception) {
            com.unciv.utils.Log.error("Cannot open campaign catalog", ex)
            root.add("战役暂时无法读取，已有存档与成绩仍保留。".toLabel()).grow()
        }
    }

    private fun updateSyncPanel(status: CampaignSyncStatus) {
        syncPanel.clearChildren()
        val message = when (status.state) {
            CampaignSyncState.LocalOnly -> "战役成绩已保存在本机"
            CampaignSyncState.Pending -> "成绩已保存在本机 · 等待 iCloud 同步"
            CampaignSyncState.Syncing -> "正在同步战役成绩…"
            CampaignSyncState.Synced -> "战役成绩已同步到 iCloud"
            CampaignSyncState.NoAccount -> "成绩已保存在本机 · 登录 iCloud 后可同步"
            CampaignSyncState.Restricted -> "成绩已保存在本机 · iCloud 访问受限"
            CampaignSyncState.AccountChanged -> "iCloud 账户已更换，自动同步已暂停；本机成绩仍保留"
            CampaignSyncState.WaitingForNetwork -> "成绩已保存在本机 · 等待重试 iCloud 同步"
            CampaignSyncState.Failed -> "iCloud 同步失败，本机成绩仍保留；可点击重试"
        }
        syncPanel.add(message.toLabel(Theme.muted, 15).apply { setWrap(true) })
            .growX().minWidth(0f).left()
        if (status.state == CampaignSyncState.LocalOnly) return
        if (stage.width < 700f) syncPanel.row()
        val changed = status.state == CampaignSyncState.AccountChanged
        syncPanel.add((if (changed) "合并到当前 iCloud" else "同步成绩").toTextButton().apply {
            isDisabled = status.state == CampaignSyncState.Syncing
            onActivation {
                val account = status.accountToConfirm
                if (changed && account != null) {
                    ConfirmPopup(this@CampaignScreen,
                        "将本机所有战役成绩合并到当前 iCloud 账户？\n这会包含之前账户的通关、星级和勋章。\n两端已有成绩都会保留，关内对局不会上传。",
                        "确认合并", true) { sync.requestSync(account) }.open()
                } else sync.requestSync()
            }
        }).minHeight(UiNavigation.minimumTouchSize()).padLeft(10f)
    }

    private fun overview(content: Table) {
        val recent = progress.lastPlayed.takeIf { it.isNotEmpty() && it != CivilWarCampaign.id }?.let(store::saved)
        if (recent?.campaign?.let { it.resultStars == 0 && !it.failed } == true) {
            val d = recent.campaign!!.definition
            content.add("继续最近挑战：${d.title} · 第 ${recent.campaign!!.completedRounds + 1} 回合".toTextButton().apply {
                name = "ui-campaign-recent"
                onActivation { enter(d.id, false) }
            }).height(58f).fillX().row()
        }
        val totalWidth = (stage.width - 60f).coerceAtMost(900f)
        val chapters = definitions.chunked(4)
        val completed = chapters.map { levels -> levels.all { it.id in progress.cleared } }
        val unlocked = chapters.map { levels -> levels.any { store.isUnlocked(it, progress) } }
        val chapterNames = listOf("建造", "发展", "战争", "胜利")
        val journey = Group().apply { setSize(totalWidth, 84f); touchable = Touchable.disabled }
        val step = totalWidth / 4
        for (i in 0..2) {
            val start = step * (i + .5f) + 22f
            val end = step * (i + 1.5f) - 22f
            if (unlocked[i + 1]) line(journey, start, 64f, end, 64f, Theme.gold, 2f)
            else {
                var x = start
                while (x < end) {
                    line(journey, x, 64f, minOf(x + 6f, end), 64f, Theme.border, 2f)
                    x += 12f
                }
            }
        }
        for (i in 0..3) {
            val center = step * (i + .5f)
            val color = if (unlocked[i] || completed[i]) Theme.gold else Theme.muted
            journey.addActor(ImageGetter.getImage(
                if (completed[i]) "OtherIcons/Hexagon" else "OtherIcons/HexagonOutline",
                if (unlocked[i] || completed[i]) Theme.gold else Theme.border).apply {
                setBounds(center - 20f, 44f, 40f, 40f)
            })
            if (completed[i] || !unlocked[i]) {
                journey.addActor(ImageGetter.getImage(
                    if (completed[i]) "OtherIcons/Checkmark" else "OtherIcons/LockSmall",
                    if (completed[i]) Theme.primaryInk else Theme.muted).apply {
                    setBounds(center - 10f, 54f, 20f, 20f)
                })
            } else journey.addActor("${i + 1}".toLabel(Theme.gold, 22).apply {
                setAlignment(Align.center)
                setBounds(center - 20f, 44f, 40f, 40f)
            })
            journey.addActor("${i + 1} ${chapterNames[i]}".toLabel(color, 18).apply {
                setAlignment(Align.center)
                setBounds(i * step, 21f, step, 22f)
            })
            journey.addActor((when {
                completed[i] -> "已完成"
                unlocked[i] -> "进行中"
                else -> "未解锁"
            }).toLabel(if (unlocked[i] || completed[i]) Theme.gold else Theme.muted, 14).apply {
                setAlignment(Align.center)
                setBounds(i * step, 0f, step, 19f)
            })
        }
        content.add(journey).size(totalWidth, 84f).row()
        val cards = Table().apply { defaults().pad(6f) }
        val columns = if (stage.width > 900f) 2 else 1
        for ((index, title) in CampaignCatalog.chapters.withIndex()) {
            val levels = chapters[index]
            val complete = completed[index]
            val mastery = levels.all { store.stars(it, progress) == 3 }
            val enabled = unlocked[index]
            val status = when { mastery -> "精通勋章"; complete -> "章节完成"; enabled -> "进行中"; else -> "未解锁" }
            val card = Table().apply {
                background = ImageGetter.getWhiteDotDrawable().tint(if (enabled) Theme.panel else Theme.unexplored)
                pad(14f); left(); defaults().pad(5f)
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
            cards.add(card).width(totalWidth / columns - 12f).fillX()
            if (index % columns == columns - 1) cards.row()
        }
        content.add(cards).row()
        val historical = progress.medals.count { key -> (0..3).none { key == store.medalKey(it, definitions.subList(it * 4, it * 4 + 4)) } }
        if (historical > 0) content.add("历史精通勋章：$historical（旧评分版本）".toLabel(Theme.muted)).row()

    }

    private fun chapter(content: Table) {
        val levels = definitions.subList(chapter * 4, chapter * 4 + 4)
        val d = levels.firstOrNull { it.id == selected } ?: levels.first().also { selected = it.id }
        content.add("${chapter + 1}  ${CampaignCatalog.chapters[chapter]}".toLabel(Theme.gold, 26)).left().row()
        val navigation = Table().apply { defaults().pad(4f).height(46f) }
        for ((label, destination) in listOf("上一章" to chapter - 1, "战役总览" to -1, "下一章" to chapter + 1)) {
            if ((label == "上一章" && chapter == 0) || destination > 3) continue
            navigation.add(label.toTextButton().apply { onActivation {
                chapter = destination
                if (chapter >= 0) selected = definitions[chapter * 4].id
                rebuild()
            } }).minWidth(105f)
        }
        content.add(navigation).left().row()
        val availableWidth = stage.width - 44f - 18f
        val leftWidth = availableWidth * .52f
        val detailWidth = availableWidth - leftWidth
        val width = leftWidth - 60f // Content padding and the map cell's padding.
        val detailPane = AutoScrollPane(details(d, detailWidth)).apply {
            name = "ui-campaign-detail-scroll"
            setScrollingDisabled(true, false)
            setOverscroll(false, false)
        }
        val map = routeMap(levels, width) { definition ->
            detailPane.actor = details(definition, detailWidth)
            detailPane.scrollY = 0f
            detailPane.updateVisualScroll()
        }
        content.add(map).size(width, 410f).row()
        if (levels.all { it.id in progress.cleared }) {
            content.add("章节完成 · 在自由对局中练习".toTextButton().apply {
                onActivation { game.pushScreen { NewGameScreen() } }
            }).height(52f).fillX().row()
        }
        val mapPane = AutoScrollPane(content).apply {
            name = "ui-campaign-map-scroll"
            setScrollingDisabled(true, false)
            setOverscroll(false, false)
        }
        val body = Table().apply {
            pad(12f, 22f, 16f, 22f)
            add(mapPane).width(leftWidth).growY().minHeight(0f)
            add(detailPane).width(detailWidth).growY().minHeight(0f).padLeft(18f)
        }
        mapScrollPane = mapPane
        detailScrollPane = detailPane
        root.add(body).grow().minHeight(0f)
    }

    private fun routeMap(levels: List<CampaignDefinition>, width: Float, onSelect: (CampaignDefinition) -> Unit): Group {
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
        for (x in listOf(65f, 95f, width - 75f)) {
            line(map, x, 300f, x + 16f, 328f, Theme.border, 2f)
            line(map, x + 16f, 328f, x + 32f, 300f, Theme.border, 2f)
        }
        // Scene2D coordinates start at the bottom, so earlier levels need higher Y positions.
        val positions = listOf(.24f to .85f, .72f to .62f, .27f to .38f, .70f to .18f)
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
                saved?.let { it.resultStars == 0 && !it.failed } == true -> "续玩·${saved.completedRounds + 1}回合"
                d.id in progress.cleared -> if (store.stars(d, progress) == 0) "已通关·新版待挑战" else "已通关"
                else -> "可挑战"
            }
            val stars = store.stars(d, progress)
            val node = "".toTextButton().apply {
                name = "ui-campaign-level:${d.id}"
                clearChildren()
                add(Table().apply {
                    add("${d.number}  ${d.title}".toLabel(if (unlocked) Theme.text else Theme.muted, 18)).row()
                    add(Table().apply {
                        add(campaignStars(stars, 16f)).padRight(5f)
                        add(status.toLabel(Theme.muted, 14))
                    }).padTop(4f)
                }).expandX().pad(6f)
                add(ImageGetter.getImage(when {
                    !unlocked && saved == null -> "OtherIcons/LockSmall"
                    d.id in progress.cleared -> "OtherIcons/Checkmark"
                    else -> "OtherIcons/Capital"
                }).apply { color = if (unlocked) Theme.gold else Theme.muted }).size(16f).padRight(8f)
                setSize((width * .42f).coerceAtMost(210f), 74f)
                setPosition(positions[i].first * width - this.width / 2, positions[i].second * 410 - height / 2)
                style = TextButton.TextButtonStyle(style).apply {
                    checked = Theme.outlinedControl(BaseScreen.skin.newDrawable("RoundedEdgeRectangle", Theme.raised),
                        BaseScreen.skin.newDrawable("RoundedEdgeRectangle", Theme.gold))
                }
                isChecked = d.id == selected
                onActivation {
                    map.children.filterIsInstance<TextButton>().forEach { it.isChecked = it === this }
                    if (selected != d.id) {
                        selected = d.id
                        onSelect(d)
                    }
                }
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
        text(if (d.id == CivilWarCampaign.id) d.title else "${d.number}. ${d.title}", Theme.gold, 25)
        text(d.description)
        text("最高成绩：${store.stars(d, progress)} / 3 星", Theme.gold)
        if (d.id in progress.cleared && store.stars(d, progress) == 0) text("已通关 · 新版待挑战", Theme.gold)
        val saved = store.saved(d.id)
        val unfinished = saved?.campaign?.let { it.resultStars == 0 && !it.failed } == true
        if (unfinished) {
            text("本地续玩 · 行动回合 ${saved!!.campaign!!.completedRounds + 1}")
            add(primaryButton("Continue campaign").apply {
                name = "ui-campaign-continue"
                onActivation { enter(d.id, false) }
            }).height(54f).fillX().row()
        }
        if (store.isUnlocked(d, progress) || saved != null) {
            add(primaryButton(if (saved == null) "Start campaign" else "Restart campaign").apply {
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
        for ((i, goal) in d.objectives.withIndex()) text("${if (i == 0) "通关" else "挑战 ${if (i == 1) "A" else "B"}"} · $goal")
        text(if (d.automatic) "达成条件后自动结算" else "主目标达成后主动结算；可继续争取挑战星", Theme.muted)
        val scene = CampaignCatalog.template(d.id)
        val player = scene.civilizations.single { it.isHuman() }
        text("初始配置", Theme.gold, 21)
        text("金币 ${player.gold} · 幸福度 ${player.getHappiness()} · 净收入 ${player.stats.statsForNextTurn.gold.toInt()}")
        for (city in player.cities) text("${city.name.tr()}：人口 ${city.population.population}；${city.cityConstructions.getBuiltBuildings().joinToString("、") { it.name.tr() }}")
        if (player.cities.isEmpty()) text("尚未建城，从移民开始。")
        text("单位：" + player.units.getCivUnits().groupingBy { it.name }.eachCount().entries.joinToString("、") { "${it.key.tr()} ×${it.value}" })
        text(if (d.id == CivilWarCampaign.id) "已具备本时期基础科技；可研究：军制整编、炮兵改良、野战医疗、军事运输。"
            else "科技：" + player.tech.techsResearched.joinToString("、") { it.tr() })
        add(initialPreview(scene, width - 44f)).size(width - 44f, 180f).row()
        text("地图预览仅显示开局已探索地形；金色标记为任务区域。", Theme.muted, 15)
        text("失败条件：玩家文明被消灭。" + if (d.id == CivilWarCampaign.id) "华盛顿失守或第 ${d.hardTurnLimit} 个完整回合结束。" else when (d.number) {
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

    }

    private fun initialPreview(scene: GameInfo, width: Float): Group {
        val group = Group().apply { setSize(width, 180f) }
        val player = scene.civilizations.single { it.isHuman() }
        if (selected == CivilWarCampaign.id) {
            val scale = minOf((width - 20f) / 41f, 155f / 33f)
            for (tile in scene.tileMap.tileList) {
                val x = width / 2 + tile.getColumn() * scale
                val y = 90f + tile.getRow() * scale
                group.addActor(ImageGetter.getWhiteDot().apply {
                    color = when {
                        tile.position == CivilWarCampaign.harpersFerry || tile.getCity()?.id in setOf("cw-washington", "cw-richmond") -> Theme.gold
                        tile.isCityCenter() -> Theme.text
                        tile.terrainFeatures.isNotEmpty() -> Theme.border
                        else -> Theme.raised
                    }
                    setBounds(x, y, scale * .9f, scale * .85f)
                    touchable = Touchable.disabled
                })
                val label = if (tile.isCityCenter()) tile.getCity()!!.name.tr()
                    else if (tile.position == CivilWarCampaign.harpersFerry) "哈珀斯费里" else null
                if (label != null) group.addActor(label.toLabel(Theme.text, 12).apply {
                    setPosition(x + scale, y); touchable = Touchable.disabled
                })
            }
            return group
        }
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

    private fun primaryButton(text: String) = text.toTextButton().apply {
        style = TextButton.TextButtonStyle(style).apply {
            up = ImageGetter.getWhiteDotDrawable().tint(Theme.gold)
            down = ImageGetter.getWhiteDotDrawable().tint(Theme.muted)
            fontColor = Theme.primaryInk
        }
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

    override fun recreate(): BaseScreen = CampaignScreen(chapter, selected, historical)

    override fun dispose() {
        disposed = true
        sync.removeStatusListener(syncListener)
        super.dispose()
    }
}
