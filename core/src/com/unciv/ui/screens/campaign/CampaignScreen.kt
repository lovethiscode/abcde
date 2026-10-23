package com.unciv.ui.screens.campaign

import com.unciv.ui.components.fonts.UiTypography
import com.unciv.ui.components.fonts.TextRole
import com.unciv.logic.challenges.ChallengeTelemetry
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.Group
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.Stack
import com.unciv.ui.popups.Popup
import kotlin.math.roundToInt
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.badlogic.gdx.utils.Align
import com.unciv.logic.GameInfo
import com.unciv.logic.campaign.CivilWarCampaign
import com.unciv.logic.campaign.CampaignCatalog
import com.unciv.logic.campaign.CampaignDefinition
import com.unciv.logic.campaign.CampaignShareData
import com.unciv.logic.campaign.CampaignProgress
import com.unciv.logic.campaign.CampaignStore
import com.unciv.logic.campaign.CampaignSyncState
import com.unciv.logic.campaign.CampaignSyncStatus

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
import kotlin.math.sqrt

/** The journey map and detail panel both read the real catalog, slots, and versioned results. */
class CampaignScreen(private var chapter: Int = -1, private var selected: String = "",
                     private var historical: Boolean = CampaignCatalog.historicalDefinitions().any { it.id == selected },
                     private var detailTab: Int = 0) : BaseScreen(), RecreateOnResize {
    private object Theme {
        val background = Color.valueOf("17343F")
        val panel = Color.valueOf("203F49")
        val raised = Color.valueOf("2B515B")
        val text = Color.valueOf("F4F3E8")
        val muted = Color.valueOf("BFD1D0")
        val gold = Color.valueOf("E7CD94")
        val primaryInk = Color.valueOf("213B42")
        val border = Color.valueOf("557780")
        val unexplored = Color.valueOf("102E38")
    }
    private val unit = UiNavigation.minimumTouchSize() / 44f
    private fun px(value: Float) = value * unit
    private val compact get() = this@CampaignScreen.stage.width > this@CampaignScreen.stage.height && this@CampaignScreen.stage.height / unit < 600f
    private val narrowPortrait get() = this@CampaignScreen.stage.width < this@CampaignScreen.stage.height &&
        this@CampaignScreen.stage.width / unit < 700f
    private val stacked get() = narrowPortrait ||
        this@CampaignScreen.stage.width / unit / game.settings.fontSizeMultiplier < 520f
    private val margin get() = px(if (stacked) 16f else 24f)
    private val contentWidth get() = this@CampaignScreen.stage.width - margin * 2f
    private val artwork = CampaignMapArtwork()
    private fun label(text: String, size: TextRole = TextRole.Body, color: Color = Theme.text) =
        text.toLabel(color, if (size == TextRole.Display) UiTypography.displaySize(compact, unit = unit) else UiTypography.size(size, unit)).apply { wrap = true }
    private fun control(text: String, id: String = "", primary: Boolean = false, quiet: Boolean = false, action: () -> Unit): TextButton =
        text.toTextButton().apply {
            if (id.isNotEmpty()) name = id
            label.setFontSize(UiTypography.size(TextRole.Control, unit))
            label.wrap = false
            val copyWidth = label.prefWidth.coerceAtMost(px(260f))
            label.wrap = true
            labelCell.minWidth(0f).prefWidth(copyWidth)
            style = TextButton.TextButtonStyle(style).apply {
                up = if (quiet) null else campaignSurface(if (primary) Theme.gold else Theme.panel, px(if (primary) 10f else 8f))
                down = campaignSurface(if (primary) Theme.muted else Theme.raised, px(8f))
                over = down; checked = down
                fontColor = if (primary) Theme.primaryInk else Theme.text
                checkedFontColor = fontColor
            }
            pad(px(8f), px(12f), px(8f), px(12f))
            onActivation { action() }
        }
    private fun openChapter(index: Int) {
        chapter = index; detailTab = 0
        val levels = definitions.subList(index * 4, index * 4 + 4)
        selected = levels.firstOrNull { store.isUnlocked(it, progress) && store.stars(it, progress) < 3 }?.id ?: levels.first().id
        rebuild()
    }
    private fun back() {
        if (historical) {
            if (selected.isNotEmpty()) selected = "" else { historical = false; chapter = -1 }
            rebuild()
        } else if (chapter >= 0) { chapter = -1; rebuild() } else game.popScreen()
    }
    private val store = CampaignStore(game.files)
    private val definitions = CampaignCatalog.definitions()
    private val historicalDefinitions = CampaignCatalog.historicalDefinitions()
    private val root = Table()
    private var progress = CampaignProgress()
    private var progressReadable = true
    private val sync = game.files.campaignProgressSync
    private val syncPanel = Table()
    private var mapScrollPane: AutoScrollPane? = null
    private var detailScrollPane: AutoScrollPane? = null
    private var historicalScrollPane: AutoScrollPane? = null
    private var historicalScrollY = 0f
    private var detailTop = 0f
    private var renderedChapter = -1
    private var renderedSyncState = sync.status.state
    private var disposed = false
    private val syncListener: (CampaignSyncStatus) -> Unit = { status ->
        Concurrency.runOnGLThread {
            if (!disposed) {
                val captionChanged = renderedSyncState != status.state
                updateSyncPanel(status)
                val latest = runCatching { store.progress() }.getOrNull()
                if (captionChanged || latest == null || !progress.samePermanentFacts(latest)) rebuild()
            }
        }
    }

    init {
        root.setFillParent(true)
        root.background = ImageGetter.getWhiteDotDrawable().tint(Theme.background)
        this@CampaignScreen.stage.addActor(root)
        rebuild()
        sync.addStatusListener(syncListener)
        sync.requestSync()
    }

    private fun rebuild(recoverResults: Boolean = true) {
        val mapScrollY = if (renderedChapter == chapter) mapScrollPane?.scrollY ?: 0f else 0f
        val detailScrollY = if (renderedChapter == chapter) detailScrollPane?.scrollY ?: 0f else 0f
        historicalScrollPane?.let { historicalScrollY = it.scrollY }
        mapScrollPane = null
        detailScrollPane = null
        historicalScrollPane = null
        root.clearChildren()
        root.pad(px(if (compact) 8f else 12f), margin, px(if (compact) 14f else 20f), margin).top()
        val header = Table().apply {
            val backButton = if (UiNavigation.isIos) UiNavigation.backButton().apply {
                name = "ui-campaign-back"
                onActivation { back() }
            } else control(if (chapter >= 0 && !stacked) "战役总览" else "", "ui-campaign-back", quiet = true, action = ::back).apply {
                addActor(artwork.icon("back", Theme.muted).apply { setBounds(0f, px(13f), px(18f), px(18f)) })
                padLeft(px(24f))
            }
            val title = if (historical) "历史战役" else if (chapter >= 0) CampaignCatalog.chapters[chapter] else "文明的诞生"
            val titleLabel = label(title, TextRole.PageTitle)
            titleLabel.wrap = false
            val titleWidth = titleLabel.prefWidth
            titleLabel.wrap = true
            val syncButton = control(if (stacked) "" else syncCaption(), "ui-campaign-sync", quiet = true) {
                Popup(this@CampaignScreen).apply {
                    addGoodSizedLabel("战役成绩").row()
                    add(syncPanel).width((this@CampaignScreen.stage.width - px(80f)).coerceAtMost(px(480f))).row()
                    addCloseButton(); open()
                }
            }
            syncButton.apply {
                clearChildren()
                add(artwork.icon("cloud", Theme.muted)).size(px(18f))
                if (!stacked) add(label(syncCaption(), TextRole.Supporting, Theme.muted).apply { wrap = false }).padLeft(px(6f))
            }
            if (backButton.prefWidth + titleWidth + syncButton.prefWidth + px(20f) > contentWidth) {
                add(titleLabel).colspan(2).growX().minWidth(0f).left().padBottom(px(8f)).row()
                add(backButton).minSize(px(44f), px(44f)).left()
                add(syncButton).minHeight(px(44f)).expandX().right().padLeft(px(8f))
            } else {
                add(backButton).minSize(px(44f), px(44f)).padRight(px(12f))
                add(titleLabel).growX().minWidth(0f).left()
                add(syncButton).minHeight(px(44f)).padLeft(px(8f))
            }
        }
        if (game.campaignSharingAvailable && progressReadable) header.add(control("", "ui-campaign-share-journey", quiet = true) {
            showCampaignShare(this@CampaignScreen, { rebuild(recoverResults = false) }) { CampaignShareData.journey(store.progress()) }
        }.apply { add(artwork.icon("share", Theme.muted)).size(px(20f)) }).size(px(44f))
        root.add(header).growX().padBottom(px(if (compact) 6f else 12f)).row()
        root.add(ImageGetter.getWhiteDot().apply { color = Theme.border }).growX().height(px(1f)).row()
        if (chapter < 0 && !(historical && selected.isNotEmpty())) {
            val themes = Table().apply {
                add(control("新手", "ui-campaign-theme:0", quiet = true) { historical = false; selected = ""; rebuild() }.apply { isChecked = !historical })
                    .minHeight(px(44f)).padRight(px(8f))
                add(control("历史战役", "ui-campaign-theme:1", quiet = true) { historical = true; selected = ""; rebuild() }.apply { isChecked = historical })
                    .minHeight(px(44f))
                add().growX()
                add(control("更多战役", "ui-campaign-more", quiet = true) {
                    Popup(this@CampaignScreen).apply {
                        addGoodSizedLabel("更多战役").row()
                        for ((index, title) in CampaignCatalog.futureThemes.withIndex()) {
                            add(control("[$title] · 筹备中", "ui-campaign-theme:${index + 2}") {
                                ToastPopup("筹备中，敬请期待", this@CampaignScreen)
                            }).minHeight(px(44f)).growX().row()
                        }
                        addCloseButton(); open()
                    }
                }).minHeight(px(44f))
            }
            root.add(themes).growX().padTop(px(8f)).padBottom(px(if (compact) 6f else 12f)).row()
        }
        try {
            if (recoverResults) (definitions + historicalDefinitions).forEach { definition ->
                runCatching { store.saved(definition.id)?.let(store::reconcile) }
                    .onFailure { com.unciv.utils.Log.error("Cannot recover campaign progress", it) }
            }
            val persisted = runCatching { store.progress() }.getOrNull()
            progressReadable = persisted != null
            progress = persisted ?: CampaignProgress()
            renderedSyncState = sync.status.state
            updateSyncPanel(sync.status)
            if (historical) historicalCatalog()
            else if (chapter < 0) overview() else chapter()
            renderedChapter = chapter
            root.validate()
            mapScrollPane?.apply { scrollY = mapScrollY; updateVisualScroll() }
            detailScrollPane?.apply { scrollY = detailScrollY; updateVisualScroll() }
            historicalScrollPane?.apply { scrollY = historicalScrollY; updateVisualScroll() }
        } catch (ex: Exception) {
            com.unciv.utils.Log.error("Cannot open campaign catalog", ex)
            root.add("战役暂时无法读取，已有存档与成绩仍保留。".toLabel(TextRole.Body)).grow()
        }
    }

    private fun historicalCatalog() {
        if (selected.isNotEmpty()) {
            val d = historicalDefinitions.first { it.id == selected }
            if (narrowPortrait) {
                val panel = details(d, contentWidth, includeActions = false)
                root.add(AutoScrollPane(Table().apply {
                    top()
                    add(panel).width(contentWidth).top().row()
                    add().growY()
                }).apply {
                    setScrollingDisabled(true, false)
                    setOverscroll(false, false)
                }).grow().minHeight(0f).row()
                root.add(levelActions(d, contentWidth)).growX().padTop(px(10f))
            } else {
                val gap = px(18f)
                val contextWidth = (contentWidth * .34f).coerceIn(px(280f), px(390f))
                val detailWidth = contentWidth - contextWidth - gap
                root.add(Table().apply {
                    add(historicalContext(d, contextWidth)).width(contextWidth).growY().minHeight(0f).padRight(gap)
                    add(details(d, detailWidth)).width(detailWidth).growY().minHeight(0f)
                }).grow().minHeight(0f)
            }
            return
        }
        val content = Table().apply { top() }
        val stars = historicalDefinitions.sumOf { store.stars(it, progress) }
        content.add(label("重回历史转折点", TextRole.PageTitle)).width(contentWidth).left().padBottom(px(5f)).row()
        content.add(label("历史战役采用独立规则与固定开局，不影响文明之路进度 · [${historicalDefinitions.size}] 个战役 · [$stars] / [${historicalDefinitions.size * 3}] 星", TextRole.Supporting, Theme.muted))
            .width(contentWidth).left().padBottom(px(14f)).row()
        for (d in historicalDefinitions) {
            val yearWidth = px(if (narrowPortrait) 58f else 76f)
            val copyWidth = contentWidth - yearWidth - px(62f)
            content.add(control("", "ui-campaign-historical:${d.id}") {
                selected = d.id; detailTab = 0; rebuild()
            }.apply {
                clearChildren(); pad(px(12f)); left()
                val saved = store.saved(d.id)?.campaign
                val status = when {
                    saved?.failed == true -> "本次挑战失败"
                    saved?.resultStars == 0 -> "进行中 · 第 [${saved.completedRounds + 1}] 回合"
                    d.id in progress.cleared -> if (store.stars(d, progress) == 0) "已通关 · 新版待挑战" else "已通关"
                    else -> "未挑战"
                }
                add(label(if (d.id == CivilWarCampaign.id) "[1861]" else "历史", TextRole.SectionTitle, Theme.gold).apply {
                    setAlignment(Align.center)
                }).width(yearWidth).left().padRight(px(12f))
                add(Table().apply {
                    defaults().left()
                    add(label(d.title, TextRole.SectionTitle)).width(copyWidth).row()
                    add(label(status, TextRole.Supporting, Theme.muted)).width(copyWidth).padTop(px(5f)).row()
                    add(campaignStars(store.stars(d, progress), px(13f))).left().padTop(px(5f))
                }).width(copyWidth).growX().minWidth(0f)
                add(artwork.icon("arrow", Theme.muted)).size(px(18f)).padLeft(px(10f))
            }).width(contentWidth).minHeight(px(96f)).padBottom(px(10f)).row()
        }
        root.add(AutoScrollPane(content)).grow().minHeight(0f)
    }

    private fun historicalContext(d: CampaignDefinition, width: Float) = Table().apply {
        background = campaignSurface(Theme.panel, px(14f))
        pad(px(22f)); top(); left(); defaults().left()
        add(label(if (d.id == CivilWarCampaign.id) "独立战役 · [1861]" else "独立战役", TextRole.Supporting, Theme.gold))
            .width(width - px(44f)).row()
        add(label(d.title, TextRole.Display)).width(width - px(44f)).padTop(px(8f)).row()
        add(label(d.description, TextRole.Body, Theme.muted)).width(width - px(44f)).padTop(px(14f)).row()
        add(ImageGetter.getWhiteDot().apply { color = Theme.border }).width(width - px(44f)).height(px(1f)).padTop(px(20f)).row()
        val copyWidth = width - px(44f)
        add(label("固定开局", TextRole.Supporting, Theme.muted)).width(copyWidth).padTop(px(16f)).row()
        add(label(if (d.id == CivilWarCampaign.id) "联邦" else "历史阵营", TextRole.SectionTitle))
            .width(copyWidth).padTop(px(4f)).row()
        add(label("版本最高", TextRole.Supporting, Theme.muted)).width(copyWidth).padTop(px(18f)).row()
        add(score(store.stars(d, progress), 3, TextRole.SectionTitle)).width(copyWidth).padTop(px(4f))
    }

    private fun syncCaption() = when (sync.status.state) {
        CampaignSyncState.Synced -> "已同步"
        CampaignSyncState.Syncing -> "正在同步"
        CampaignSyncState.Failed -> "同步失败"
        else -> "本机成绩"
    }

    private fun updateSyncPanel(status: CampaignSyncStatus) {
        syncPanel.clearChildren()
        val message = if (!progressReadable) "Campaign progress unavailable" else when (status.state) {
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
        syncPanel.add(label(message, TextRole.Supporting, Theme.muted).apply { setWrap(true) })
            .growX().minWidth(0f).left()
        if (status.state == CampaignSyncState.LocalOnly) return
        syncPanel.row()
        val changed = status.state == CampaignSyncState.AccountChanged
        syncPanel.add((if (changed) "合并到当前 iCloud" else "同步成绩").toTextButton().apply {
            label.setFontSize(UiTypography.size(TextRole.Control, unit))
            isDisabled = status.state == CampaignSyncState.Syncing
            onActivation {
                val account = status.accountToConfirm
                if (changed && account != null) {
                    ConfirmPopup(this@CampaignScreen,
                        "将本机所有战役成绩合并到当前 iCloud 账户？\n这会包含之前账户的通关、星级和勋章。\n两端已有成绩都会保留，关内对局不会上传。",
                        "确认合并", true) { sync.requestSync(account) }.open(true)
                } else sync.requestSync()
            }
        }).minHeight(UiNavigation.minimumTouchSize()).padLeft(10f)
    }

    private fun shareButton(title: String, id: String, snapshot: () -> CampaignShareData?) = title.toTextButton().apply {
        name = id
        label.wrap = true
        label.setFontSize(UiTypography.size(TextRole.Control, unit))
        onActivation { showCampaignShare(this@CampaignScreen, { rebuild(recoverResults = false) }, snapshot) }
    }

    private fun score(earned: Int, total: Int, size: TextRole) = Table().apply {
        add(artwork.icon("star", Theme.gold)).size(px(20f)).padRight(px(6f))
        add(label("$earned", size, Theme.text).apply { wrap = false })
        add(label("/ [$total] 星", TextRole.Supporting, Theme.muted).apply { wrap = false }).padLeft(px(4f))
    }

    private fun exploreButton(text: String, id: String, action: () -> Unit) = control("", id, true, action = action).apply {
        clearChildren()
        add(artwork.icon("flag", Theme.primaryInk)).size(px(18f)).padRight(px(8f))
        add(label(text, TextRole.Supporting, Theme.primaryInk)).growX().minWidth(0f).left()
        add(artwork.icon("arrow", Theme.primaryInk)).size(px(18f)).padLeft(px(8f))
    }

    private fun overview() {
        if (narrowPortrait) {
            overviewPortrait()
            return
        }
        val width = contentWidth
        val gap = px(20f)
        val introWidth = if (stacked) width else px(if (compact) 210f else 280f)
        val mapWidth = if (stacked) width else width - introWidth - gap
        val mapHeight = if (stacked) px(520f * game.settings.fontSizeMultiplier) else (this@CampaignScreen.stage.height - root.prefHeight).coerceAtLeast(px(240f))
        val intro = Table().apply {
            top(); left()
            val stars = definitions.sumOf { store.stars(it, progress) }
            val journeyComplete = definitions.all { it.id in progress.cleared }
            val heading = Table().apply {
                add(label(if (journeyComplete) "你的文明，\n已经写入史册。" else "文明，从这里\n开始生长。", TextRole.Display)).growX().minWidth(0f).left()
                if (stacked) add(score(stars, 48, TextRole.SectionTitle)).top().right()
            }
            add(heading).width(introWidth).left().row()
            add(label(if (journeyComplete) "全部关卡均已通关。回顾章节、挑战更高成绩，或分享这段旅程。"
                else "沿河谷出发，穿过未知边境。\n四个章节，十六次策略抉择。", TextRole.Supporting, Theme.muted))
                .width(introWidth).left().padTop(px(12f)).row()
            if (!stacked) add(score(stars, 48, if (compact) TextRole.SectionTitle else TextRole.PageTitle)).left().padTop(px(16f)).row()
            val recent = progress.lastPlayed.takeIf { id -> definitions.any { it.id == id } }?.let(store::saved)
            if (journeyComplete) {
                add(label("最高完成度 · [${definitions.count { store.stars(it, progress) == 3 }}] / 16 关三星", TextRole.Supporting, Theme.gold))
                    .width(introWidth).left().padTop(px(16f)).row()
                add(exploreButton("回顾章节", "ui-campaign-review") { openChapter(3) })
                    .width(introWidth).minHeight(px(44f)).padTop(px(18f)).row()
                if (game.campaignSharingAvailable && progressReadable) add(control("分享旅程", "ui-campaign-share-complete") {
                    showCampaignShare(this@CampaignScreen, { rebuild(recoverResults = false) }) { CampaignShareData.journey(store.progress()) }
                }).width(introWidth).minHeight(px(44f)).padTop(px(8f)).row()
            } else if (recent?.campaign?.let { it.resultStars == 0 && !it.failed } == true) {
                val d = recent.campaign!!.definition
                add(exploreButton("继续探索", "ui-campaign-recent") { enter(d.id, false) })
                    .width(introWidth).minHeight(px(44f)).padTop(px(22f)).row()
                add(label("继续最近挑战：[${d.title}] · 第 [${recent.campaign!!.completedRounds + 1}] 回合", TextRole.Supporting, Theme.muted))
                    .width(introWidth).left().padTop(px(8f)).row()
            } else {
                val next = definitions.firstOrNull { store.isUnlocked(it, progress) && store.stars(it, progress) < 3 } ?: definitions.first()
                add(exploreButton(if (progress.cleared.isEmpty()) "开始第一章" else "继续探索", "ui-campaign-explore") { openChapter((next.number - 1) / 4) })
                    .width(introWidth).minHeight(px(44f)).padTop(px(22f)).row()
            }
            if (!compact && !stacked) add(control("战役说明", "ui-campaign-help", quiet = true) {
                Popup(this@CampaignScreen).apply {
                    addGoodSizedLabel("探索你的文明之路").row()
                    addGoodSizedLabel("原创战役共有四章十六关。每关至少获得一颗星，解锁下一关。\n完成本章获得章节完成记录；全部三星可获得精通勋章。\n各关的局势与目标独立，已通关关卡可反复挑战。").row()
                    addCloseButton(); open()
                }
            }).left().minHeight(px(44f)).padTop(px(24f)).row()
            val oldMedals = progress.medals.count { key -> (0..3).none { key == store.medalKey(it, definitions.subList(it * 4, it * 4 + 4)) } }
            if (oldMedals > 0) add(label("历史精通勋章：[$oldMedals]（旧评分版本）", TextRole.Supporting, Theme.muted)).width(introWidth).padTop(px(12f)).row()
        }
        val map = Group().apply { name = "ui-campaign-overview-map"; setSize(mapWidth, mapHeight) }
        map.addActor(artwork.image(px(14f)).apply { setSize(mapWidth, mapHeight) })
        mapAnnotations(map, true)
        val positions = if (stacked) listOf(.28f to .24f, .68f to .45f, .28f to .68f, .68f to .85f)
            else listOf(.21f to .32f, .42f to .54f, .64f to .44f, .82f to .76f)
        for (i in 0..2) route(map, positions[i].first * mapWidth, positions[i].second * mapHeight,
            positions[i + 1].first * mapWidth, positions[i + 1].second * mapHeight,
            definitions.subList((i + 1) * 4, (i + 2) * 4).any { store.isUnlocked(it, progress) })
        for ((i, title) in CampaignCatalog.chapters.withIndex()) {
            val levels = definitions.subList(i * 4, i * 4 + 4)
            val complete = levels.all { it.id in progress.cleared }
            val mastery = levels.all { store.stars(it, progress) == 3 }
            val enabled = levels.any { store.isUnlocked(it, progress) }
            val status = when { mastery -> "精通勋章"; complete -> "章节完成"; enabled -> "进行中"; else -> "未解锁" }
            val nodeWidth = px(if (compact) 110f else 126f).coerceAtMost(mapWidth * if (compact) .20f else .38f)
            val node = mapNode("第 [${i + 1}] 章", title.tr(), "[$status] · [${levels.sumOf { store.stars(it, progress) }}] / 12 星",
                listOf("city", "compass", "flag", "sun")[i], nodeWidth, false, -1,
                "ui-campaign-chapter:$i", locked = !enabled) { openChapter(i) }
            node.setPosition(positions[i].first * mapWidth - node.width / 2, positions[i].second * mapHeight - node.height / 2)
            map.addActor(node)
        }
        val body = Table().apply { top() }
        if (stacked) {
            body.add(intro).width(introWidth).padBottom(gap).row()
            body.add(map).size(mapWidth, mapHeight)
            root.add(AutoScrollPane(body)).grow().minHeight(0f)
        } else {
            body.add(AutoScrollPane(intro)).width(introWidth).growY().minHeight(0f).padRight(gap)
            body.add(map).size(mapWidth, mapHeight)
            root.add(body).grow().minHeight(0f)
        }
    }

    private fun overviewPortrait() {
        val stars = definitions.sumOf { store.stars(it, progress) }
        val journeyComplete = definitions.all { it.id in progress.cleared }
        val recent = progress.lastPlayed.takeIf { id -> definitions.any { it.id == id } }?.let(store::saved)
        val unfinished = recent?.campaign?.takeIf { it.resultStars == 0 && !it.failed }
        val next = unfinished?.definition ?: definitions.firstOrNull {
            store.isUnlocked(it, progress) && store.stars(it, progress) < 3
        } ?: definitions.last()
        val currentChapter = (next.number - 1) / 4
        val content = Table().apply {
            top(); defaults().left()
            add(label(if (journeyComplete) "你的文明，已经写入史册" else "从一座城，开拓你的山河", TextRole.Display))
                .width(contentWidth).row()
            add(label(if (journeyComplete) "全部十六关均已通关，可以回顾章节或分享旅程。" else "四章循序渐进的文明挑战。",
                TextRole.Supporting, Theme.muted)).width(contentWidth).padTop(px(6f)).row()

            val strip = Table()
            val rowCopyWidth = (contentWidth - px(91f)).coerceAtLeast(px(160f))
            for ((index, title) in CampaignCatalog.chapters.withIndex()) {
                val levels = definitions.subList(index * 4, index * 4 + 4)
                val enabled = levels.any { store.isUnlocked(it, progress) }
                strip.add(control("", "ui-campaign-journey-step:$index", quiet = true) { if (enabled) openChapter(index) }.apply {
                    clearChildren(); pad(px(4f))
                    add(label("${index + 1}", TextRole.Control, if (index == currentChapter) Theme.primaryInk else Theme.muted).apply {
                        setAlignment(Align.center)
                    }).growX().row()
                    add(ImageGetter.getWhiteDot().apply { color = if (index == currentChapter) Theme.gold else Theme.border })
                        .growX().height(px(if (index == currentChapter) 4f else 2f)).padTop(px(4f)).row()
                    add(label(if (index == 3) "终章" else "第${index + 1}章", TextRole.Metadata,
                        if (index == currentChapter) Theme.text else Theme.muted).apply { setAlignment(Align.center) })
                        .growX().padTop(px(4f))
                }).width(contentWidth / 4f).minHeight(px(58f))
            }
            add(strip).width(contentWidth).padTop(px(6f)).padBottom(px(6f)).row()

            val levels = definitions.subList(currentChapter * 4, currentChapter * 4 + 4)
            add(Table().apply {
                background = campaignSurface(Theme.panel, px(14f)); pad(px(12f)); defaults().left()
                add(label(if (journeyComplete) "旅程完成" else "当前章节", TextRole.Supporting, Theme.gold)).growX().row()
                val titleRow = Table().apply {
                    add(label(CampaignCatalog.chapters[currentChapter], TextRole.PageTitle)).growX().minWidth(0f).left()
                    add(score(levels.sumOf { store.stars(it, progress) }, 12, TextRole.Body)).padLeft(px(8f))
                }
                add(titleRow).width(contentWidth - px(32f)).padTop(px(5f)).row()
                val stateCopy = if (unfinished != null) "下一关 · [${unfinished.definition.title}] · 继续第 [${unfinished.completedRounds + 1}] 回合"
                    else "[${levels.count { it.id in progress.cleared }}] / 4 关已完成"
                add(label(stateCopy, TextRole.Supporting, Theme.muted)).width(contentWidth - px(32f)).padTop(px(8f))
            }).width(contentWidth).padBottom(px(8f)).row()

            add(label("章节路线", TextRole.SectionTitle)).width(contentWidth).padBottom(px(6f)).row()
            for ((index, title) in CampaignCatalog.chapters.withIndex()) {
                val chapterLevels = definitions.subList(index * 4, index * 4 + 4)
                val enabled = chapterLevels.any { store.isUnlocked(it, progress) }
                val complete = chapterLevels.all { it.id in progress.cleared }
                val status = when {
                    chapterLevels.all { store.stars(it, progress) == 3 } -> "精通 · 12 星"
                    complete -> "已完成 · [${chapterLevels.sumOf { store.stars(it, progress) }}] 星"
                    enabled -> "进行中 · [${chapterLevels.sumOf { store.stars(it, progress) }}] 星"
                    else -> "尚未解锁"
                }
                add(control("", "ui-campaign-chapter-row:$index") { if (enabled) openChapter(index) }.apply {
                    clearChildren(); pad(px(10f)); left(); isDisabled = !enabled
                    add(label("${index + 1}", TextRole.Control, if (enabled) Theme.gold else Theme.muted).apply {
                        setAlignment(Align.center)
                    }).size(px(36f))
                    add(Table().apply {
                        defaults().left()
                        add(label(title.tr(), TextRole.Control).apply { wrap = false }).width(rowCopyWidth).row()
                        add(label(status, TextRole.Supporting, Theme.muted).apply { wrap = false })
                            .width(rowCopyWidth).padTop(px(3f))
                    }).width(rowCopyWidth).minWidth(0f).padLeft(px(10f))
                    add(artwork.icon(if (enabled) "arrow" else "lock", Theme.muted)).size(px(17f)).padLeft(px(8f))
                }).width(contentWidth).minHeight(px(64f)).padBottom(px(7f)).row()
            }
            add(label("当前版本共获得 [$stars] / 48 星", TextRole.Supporting, Theme.muted)).width(contentWidth).padTop(px(4f))
        }
        root.add(AutoScrollPane(content).apply { setScrollingDisabled(true, false) }).grow().minHeight(0f).row()
        val actions = Table()
        if (journeyComplete && game.campaignSharingAvailable && progressReadable) {
            actions.add(control("分享旅程", "ui-campaign-share-complete") {
                showCampaignShare(this@CampaignScreen, { rebuild(recoverResults = false) }) { CampaignShareData.journey(store.progress()) }
            }).minHeight(px(44f)).growX().padRight(px(8f))
        } else actions.add(control("历史战役", "ui-campaign-history-shortcut") {
            historical = true; selected = ""; rebuild()
        }).minHeight(px(44f)).growX().padRight(px(8f))
        val primaryCopy = when {
            journeyComplete -> "回顾章节"
            unfinished != null -> "继续关卡"
            else -> "进入第 [${currentChapter + 1}] 章"
        }
        actions.add(control(primaryCopy, "ui-campaign-overview-primary", true) {
            if (unfinished != null) enter(unfinished.definition.id, false) else openChapter(currentChapter)
        }).minHeight(px(44f)).growX()
        root.add(actions).growX().padTop(px(10f))
    }

    private fun chapter() {
        val levels = definitions.subList(chapter * 4, chapter * 4 + 4)
        val d = levels.firstOrNull { it.id == selected } ?: levels.first().also { selected = it.id }
        val navigation = Table().apply { left() }
        for ((i, title) in CampaignCatalog.chapters.withIndex()) navigation.add(
            control(if (narrowPortrait) "${i + 1}" else "${(i + 1).toString().padStart(2, '0')}  ${title.tr()}",
                "ui-campaign-chapter:$i", quiet = true) { openChapter(i) }.apply {
                isChecked = i == chapter
                label.setFontSize(UiTypography.size(TextRole.Control, unit))
                if (narrowPortrait) { padLeft(px(4f)); padRight(px(4f)); label.wrap = false; labelCell.prefWidth(label.prefWidth) }
                if (!narrowPortrait && !store.isUnlocked(definitions[i * 4], progress)) add(artwork.icon("lock", Theme.muted)).size(px(14f)).padLeft(px(8f))
            }
        ).minHeight(px(44f)).padRight(px(if (narrowPortrait) 4f else 8f))
        root.add(AutoScrollPane(navigation).apply { setScrollingDisabled(false, true) }).growX()
            .height(px(52f * game.settings.fontSizeMultiplier)).padTop(px(6f)).row()
        if (!compact) root.add(Table().apply {
            val heading = Table().apply {
                add(label(CampaignCatalog.chapters[chapter], TextRole.PageTitle)).growX().left().row()
                add(label(listOf("让第一座城，在河谷中生根。", "越过边境，建立繁荣的国家。", "从第一场交锋，到夺取要塞。", "把你的策略，连成完整的胜利。")[chapter], TextRole.Supporting, Theme.muted)).growX().left().padTop(px(5f))
            }
            add(heading).growX()
            add(score(levels.sumOf { store.stars(it, progress) }, 12, TextRole.Body)).padLeft(px(16f)).right()
        }).growX().padBottom(px(16f)).row()
        if (narrowPortrait) {
            root.add(compactLevelTrack(levels)).width(contentWidth).padBottom(px(10f)).row()
            val panel = details(d, contentWidth, includeActions = false)
            root.add(AutoScrollPane(Table().apply { add(panel).width(contentWidth) }).apply {
                name = "ui-campaign-detail-scroll"
                setScrollingDisabled(true, false)
                setOverscroll(false, false)
            }).grow().minHeight(0f).row()
            root.add(levelActions(d, contentWidth)).growX().padTop(px(10f))
            return
        }
        val gap = px(16f)
        val detailWidth = if (stacked) contentWidth else px(if (compact) (if (this@CampaignScreen.stage.height / unit < 420f) 260f else 300f) else 360f)
        val mapWidth = if (stacked) contentWidth else contentWidth - detailWidth - gap
        val mapHeight = if (stacked) px((if (contentWidth / unit >= 700f) 340f else 450f) * game.settings.fontSizeMultiplier) else
            (this@CampaignScreen.stage.height - root.prefHeight).coerceAtLeast(px(220f))
        val map = routeMap(levels, mapWidth, mapHeight)
        val detail = details(d, detailWidth)
        val mapColumn = Table().apply {
            add(map).size(mapWidth, mapHeight).row()
            if (levels.all { it.id in progress.cleared }) {
                if (game.campaignSharingAvailable && progressReadable) add(
                    shareButton("Share: [${"章节完成".tr()}]", "ui-campaign-share-chapter:$chapter") {
                        CampaignShareData.chapter(store.progress(), chapter)
                    }).width(mapWidth).minHeight(px(44f)).row()
                add(control("章节完成 · 在自由对局中练习", "ui-campaign-free-play") { game.pushScreen { NewGameScreen() } })
                    .width(mapWidth).minHeight(px(44f)).row()
            }
        }
        val body = Table().apply { top() }
        if (stacked) {
            body.add(mapColumn).width(mapWidth).padBottom(gap).row()
            body.add(detail).width(detailWidth)
            detailTop = mapColumn.prefHeight + gap
            val pane = AutoScrollPane(body).apply { name = "ui-campaign-map-scroll"; setScrollingDisabled(true, false) }
            mapScrollPane = pane
            root.add(pane).grow().minHeight(0f)
        } else {
            body.add(AutoScrollPane(mapColumn).apply { mapScrollPane = this; name = "ui-campaign-map-scroll" })
                .width(mapWidth).growY().minHeight(0f).padRight(gap)
            body.add(detail).width(detailWidth).growY().minHeight(0f)
            root.add(body).grow().minHeight(0f)
        }
    }

    private fun compactLevelTrack(levels: List<CampaignDefinition>) = Table().apply {
        background = campaignSurface(Theme.panel, px(12f))
        pad(px(10f))
        for ((index, d) in levels.withIndex()) {
            val unlocked = store.isUnlocked(d, progress) || store.saved(d.id) != null
            val active = d.id == selected
            add(control("${index + 1}", "ui-campaign-level:${d.id}", primary = active, quiet = !active) {
                if (unlocked) { selected = d.id; detailTab = 0; rebuild() }
            }.apply { isDisabled = !unlocked }).size(px(44f))
            if (index < levels.lastIndex) add(ImageGetter.getWhiteDot().apply {
                color = if (d.id in progress.cleared) Theme.gold else Theme.border
            }).growX().height(px(2f)).padLeft(px(5f)).padRight(px(5f))
        }
    }

    private fun mapAnnotations(map: Group, overview: Boolean) {
        fun caption(text: String, x: Float, y: Float, width: Float, color: Color = Theme.muted) {
            map.addActor(label(text, TextRole.Supporting, color).apply {
                setBounds(x, y, width, px(24f)); touchable = Touchable.disabled
            })
        }
        if (overview) {
            route(map, px(14f), map.height - px(22f), px(34f), map.height - px(22f), true)
            caption("已探索", px(40f), map.height - px(34f), px(50f))
            route(map, px(98f), map.height - px(22f), px(118f), map.height - px(22f), false)
            caption("待探索", px(124f), map.height - px(34f), px(50f))
        } else {
            map.addActor(artwork.icon("compass", Theme.muted).apply { setBounds(px(14f), map.height - px(34f), px(18f), px(18f)) })
            caption("章节地图", px(38f), map.height - px(34f), px(100f))
        }
        caption(if (overview) "选择章节，查看关卡路线" else "点击地点查看目标 · 虚线路线尚未解锁",
            px(14f), px(8f), map.width - px(28f))
        caption(if (overview) "河谷平原" else listOf("晨曦河谷", "北方高地", "边境前线", "远方大陆")[chapter],
            map.width * .12f, map.height * .55f, px(100f))
        caption(if (overview) "未知边境" else listOf("开拓平原", "知识之城", "群山要塞", "文明之都")[chapter],
            map.width * .48f, map.height * .12f, px(100f))
        if (overview) caption("N", map.width - px(30f), px(36f), px(20f))
        else map.addActor(control("", "ui-campaign-locate", quiet = true) {
            val levels = definitions.subList(chapter * 4, chapter * 4 + 4)
            selected = levels.firstOrNull { store.isUnlocked(it, progress) && store.stars(it, progress) < 3 }?.id ?: levels.last().id
            detailTab = 0; rebuild()
        }.apply {
            style = TextButton.TextButtonStyle(style).apply {
                up = campaignNodeSurface(Theme.background, Theme.muted, false, false, px(1f))
            }
            clearChildren(); pad(0f)
            add(artwork.icon("refresh", Theme.muted)).size(px(18f))
            setBounds(map.width - px(52f), map.height - px(52f), px(44f), px(44f))
        })
    }

    private fun routeMap(levels: List<CampaignDefinition>, width: Float, height: Float): Group {
        val map = Group().apply { name = "ui-campaign-chapter-map"; setSize(width, height) }
        map.addActor(artwork.image(px(14f), chapter).apply { setSize(width, height) })
        mapAnnotations(map, false)
        val positions = if (narrowPortrait) listOf(.26f to .78f, .74f to .61f, .27f to .38f, .74f to .21f)
            else listOf(.16f to .37f, .40f to .68f, .63f to .37f, .84f to if (compact && width / unit < 450f) .60f else .72f)
        for (i in 0..2) route(map, positions[i].first * width, positions[i].second * height,
            positions[i + 1].first * width, positions[i + 1].second * height, levels[i].id in progress.cleared)
        for ((i, d) in levels.withIndex()) {
            val unlocked = store.isUnlocked(d, progress)
            val saved = store.saved(d.id)?.campaign
            val status = when {
                !unlocked && saved == null -> "锁定"
                saved?.let { it.resultStars == 0 && !it.failed } == true -> "续玩·[${saved.completedRounds + 1}]回合"
                d.id in progress.cleared -> if (store.stars(d, progress) == 0) "已通关·新版待挑战" else "已通关"
                else -> "可挑战"
            }
            val node = mapNode(d.number.toString().padStart(2, '0'), d.title.tr(), status,
                if (!unlocked && saved == null) "lock" else if (d.id in progress.cleared) "check" else if (saved != null) "flag" else "city",
                px(if (compact) 104f else 116f).coerceAtMost(width * if (compact) .26f else .38f), d.id == selected, store.stars(d, progress),
                "ui-campaign-level:${d.id}") {
                selected = d.id; detailTab = 0; detailScrollPane?.scrollY = 0f; rebuild()
                if (stacked) { root.validate(); mapScrollPane?.apply { scrollY = detailTop.coerceAtMost(maxY); updateVisualScroll() } }
            }
            node.setPosition(positions[i].first * width - node.width / 2, positions[i].second * height - node.height / 2)
            map.addActor(node)
        }
        return map
    }

    private fun mapNode(index: String, title: String, status: String, icon: String, width: Float, selected: Boolean,
                        stars: Int, id: String, locked: Boolean = icon == "lock", action: () -> Unit): TextButton = control("", id, action = action).apply {
        clearChildren(); pad(0f)
        style = TextButton.TextButtonStyle(style).apply { up = null; down = null; over = null; checked = null }
        val ink = if (selected) Theme.primaryInk else Theme.text
        val symbol = Stack().apply {
            add(com.badlogic.gdx.scenes.scene2d.ui.Image(campaignNodeSurface(if (selected) Theme.gold else Theme.background, if (selected || (stars < 0 && !locked)) Theme.gold else Theme.muted, stars < 0, locked, px(1f))))
            add(Table().apply { add(artwork.icon(icon, if (selected) Theme.primaryInk else Theme.muted)).size(px(22f)) })
        }
        add(symbol).size(px(44f)).padBottom(px(6f)).row()
        val copy = Table().apply {
            background = campaignSurface(if (selected) Theme.gold else Theme.background, px(5f))
            pad(px(6f))
            if (stars < 0) add(label(index, TextRole.CompactData, Theme.muted).apply { setAlignment(Align.center) })
                .width(width - px(12f)).row()
            val titleRow = Table().apply {
                if (stars >= 0) add(label(index, TextRole.CompactData, ink).apply { wrap = false }).padRight(px(4f))
                add(label(title, TextRole.Control, ink).apply { wrap = false; setAlignment(Align.center) })
            }
            add(titleRow).width(width - px(12f)).row()
            add(label(status, TextRole.Supporting, if (selected) Theme.primaryInk else Theme.muted).apply { setAlignment(Align.center) })
                .width(width - px(12f)).padTop(px(3f)).row()
            if (stars >= 0) {
                add(campaignStars(stars, px(12f), selected)).padTop(px(4f))
            }
        }
        add(copy).width(width).row()
        setWidth(width); pack()
    }

    private fun route(map: Group, x: Float, y: Float, endX: Float, endY: Float, explored: Boolean) {
        if (explored) line(map, x, y, endX, endY, Theme.gold, px(2f))
        else {
            val distance = sqrt((endX - x) * (endX - x) + (endY - y) * (endY - y))
            var offset = 0f
            while (offset < distance) {
                val end = minOf(offset + px(5f), distance)
                line(map, x + (endX - x) * offset / distance, y + (endY - y) * offset / distance,
                    x + (endX - x) * end / distance, y + (endY - y) * end / distance, Theme.muted, px(1f))
                offset += px(11f)
            }
        }
    }

    private fun details(d: CampaignDefinition, width: Float, includeActions: Boolean = true): Table = Table().apply {
        name = "ui-campaign-detail-panel"
        background = campaignSurface(Theme.panel, px(14f))
        val inset = px(if (compact) 12f else 20f)
        val copyWidth = width - inset * 2f
        pad(inset).top(); defaults().left()
        if (stacked && !historical && !narrowPortrait) add(control("返回章节地图", "ui-campaign-return-map", quiet = true) {
            mapScrollPane?.apply { scrollY = 0f; updateVisualScroll() }
        }).minHeight(px(44f)).row()
        val titleRow = Table().apply {
            val titleCopy = Table().apply {
                add(label(if (historical) "独立战役" else "第 [${d.number}] 关", TextRole.Metadata, Theme.muted)).growX().left().row()
                add(label(d.title, TextRole.PageTitle)).growX().minWidth(0f).padTop(px(3f)).left()
            }
            add(titleCopy).growX().minWidth(0f)
            add(campaignStars(store.stars(d, progress), px(12f))).top().padLeft(px(8f))
        }
        add(titleRow).width(copyWidth).row()
        if (!compact) add(label(d.description, TextRole.Supporting, Theme.muted)).width(copyWidth).padTop(px(8f)).row()
        val saved = store.saved(d.id)
        val terminal = saved?.campaign
        val unfinished = terminal?.let { it.resultStars == 0 && !it.failed } == true
        val state = when {
            unfinished -> "本地续玩 · 行动回合 [${terminal.completedRounds + 1}]"
            !store.isUnlocked(d, progress) -> "尚未解锁"
            d.id in progress.cleared -> "已通关"
            else -> "可挑战"
        }
        add(label(state, TextRole.Supporting, Theme.muted)).width(copyWidth).padTop(px(8f)).row()
        val tabs = Table()
        for ((index, key) in listOf("星级目标", "初始配置", "提示与规则").withIndex()) {
            val title = key
            tabs.add(Table().apply {
                add(control(title, "ui-campaign-detail-tab:$index", quiet = true) {
                detailTab = index; detailScrollPane?.scrollY = 0f; rebuild()
                if (stacked) { root.validate(); mapScrollPane?.apply { scrollY = detailTop.coerceAtMost(maxY); updateVisualScroll() } }
            }.apply { label.setFontSize(UiTypography.size(TextRole.Control, unit)); pad(0f) }).width(copyWidth / 3f).minHeight(px(44f)).row()
                add(ImageGetter.getWhiteDot().apply { color = if (detailTab == index) Theme.gold else Theme.border }).growX().height(px(if (detailTab == index) 2f else 1f))
            }).width(copyWidth / 3f)
        }
        add(tabs).width(copyWidth).padTop(px(8f)).row()
        val content = Table().apply { top(); defaults().left() }
        fun text(value: String, color: Color = Theme.text, size: TextRole = TextRole.Body) {
            content.add(label(value, size, color)).width(copyWidth).padTop(px(10f)).row()
        }
        when (detailTab) {
            0 -> {
                if (compact && this@CampaignScreen.stage.height / unit >= 420f) text(d.description, Theme.muted, TextRole.Supporting)
                if (d.id in progress.cleared && store.stars(d, progress) == 0) text("已通关 · 新版待挑战", Theme.gold)
                val columns = stacked && contentWidth / unit >= 700f
                val goals = Table().apply { top() }
                val goalWidth = if (columns) (copyWidth - px(32f)) / 3f else copyWidth
                for ((i, goal) in d.objectives.withIndex()) {
                    val row = Table().apply {
                        top(); left()
                        add(artwork.icon("star", Theme.gold)).size(px(16f)).top().padRight(px(10f))
                        add(Table().apply {
                            add(label(if (i == 0) "通关目标" else "挑战 [${if (i == 1) "A" else "B"}]", TextRole.Supporting, Theme.gold)).width(goalWidth - px(26f)).left().row()
                            add(label(goal, TextRole.Body)).width(goalWidth - px(26f)).left().padTop(px(5f))
                        }).width(goalWidth - px(26f))
                    }
                    goals.add(row).width(goalWidth).top().padTop(px(10f)).padBottom(px(10f))
                    if (columns) { if (i < 2) goals.getCell(row).padRight(px(16f)) }
                    else {
                        goals.row()
                        goals.add(ImageGetter.getWhiteDot().apply { color = Theme.border }).width(copyWidth).height(px(1f)).row()
                    }
                }
                content.add(goals).width(copyWidth).row()
                text(if (d.automatic) "达成条件后自动结算" else "主目标达成后主动结算；可继续争取挑战星", Theme.muted, TextRole.Supporting)
                if (game.campaignSharingAvailable && progressReadable && store.stars(d, progress) > 0)
                    content.add(shareButton("Share: [${"Best result".tr()}]", "ui-campaign-share-best:${d.id}") {
                        CampaignShareData.best(store.progress(), d.id, d.scoringVersion)
                    }).width(copyWidth).minHeight(px(44f)).row()
                if (terminal != null && game.campaignSharingAvailable && CampaignShareData.result(terminal, d.id, terminal.attemptId, recent = true) != null) {
                    text("Latest attempt: [${terminal.resultStars}] / 3 stars", Theme.muted)
                    content.add(shareButton("Share: [${"Latest attempt".tr()}]", "ui-campaign-share-recent:${d.id}") {
                        store.shareResult(d.id, terminal.attemptId, recent = true)
                    }).width(copyWidth).minHeight(px(44f)).row()
                }
            }
            1 -> {
                val scene = CampaignCatalog.template(d.id)
                val player = scene.civilizations.single { it.isHuman() }
                text("金币 [${player.gold}] · 幸福度 [${player.getHappiness()}] · 净收入 [${player.stats.statsForNextTurn.gold.toInt()}]")
                for (city in player.cities) text("[${city.name.tr()}]：人口 [${city.population.population}]；[${city.cityConstructions.getBuiltBuildings().joinToString("、") { it.name.tr() }}]")
                if (player.cities.isEmpty()) text("尚未建城，从移民开始。")
                text("单位：".tr() + player.units.getCivUnits().groupingBy { it.name }.eachCount().entries.joinToString(", ") { "${it.key.tr()} ×${it.value}" })
                text(if (d.id == CivilWarCampaign.id) "已具备本时期基础科技；可研究：军制整编、炮兵改良、野战医疗、军事运输。"
                    else "科技：".tr() + player.tech.techsResearched.joinToString(", ") { it.tr() })
                content.add(initialPreview(scene, copyWidth)).size(copyWidth, px(180f)).padTop(px(12f)).row()
                text("地图预览仅显示开局已探索地形；金色标记为任务区域。", Theme.muted, TextRole.Supporting)
            }
            else -> {
                text("失败条件：玩家文明被消灭。".tr() + (if (d.id == CivilWarCampaign.id) "华盛顿失守或第 [${d.hardTurnLimit}] 个完整回合结束。" else when (d.number) {
                    11 -> "初始首都一旦失守，本次立即失败。"
                    12 -> "第 25 轮结束仍未保留目标城，或目标城永久摧毁。"
                    13, 14 -> "目标城市永久摧毁后无法继续完成本关。"
                    15, 16 -> "对手获得征服胜利。"
                    else -> "普通教学关没有硬性时间限制。"
                }).tr(), Theme.muted, TextRole.Supporting)
                for (restriction in d.restrictions) text(restriction, Theme.muted, TextRole.Supporting)
                for (hint in d.hints) text("提示 · [$hint]", Theme.muted, TextRole.Supporting)
                val history = progress.bestStars.filterKeys { it.startsWith("${d.id}:") && it != "${d.id}:${d.scoringVersion}" }
                for ((key, value) in history) {
                    text("历史评分版本 [${key.substringAfter(':')}]：[$value] / 3 星", Theme.muted, TextRole.Supporting)
                    val version = key.substringAfter(':').toIntOrNull()
                    if (game.campaignSharingAvailable && progressReadable && value > 0 && version != null && version > 0)
                        content.add(shareButton("Share: [${"Best result".tr()}]", "ui-campaign-share-best:$key") {
                            CampaignShareData.best(store.progress(), d.id, version)
                        }).width(copyWidth).minHeight(px(44f)).row()
                }
            }
        }
        val pane = AutoScrollPane(content).apply {
            name = "ui-campaign-detail-scroll"; setScrollingDisabled(true, false); setOverscroll(false, false)
        }
        detailScrollPane = pane
        if (stacked) add(content).width(copyWidth).padTop(px(6f)).row()
        else add(pane).width(copyWidth).growY().minHeight(0f).padTop(px(6f)).row()
        if (includeActions) add(levelActions(d, copyWidth)).width(copyWidth).padTop(px(10f)).row()
    }

    private fun levelActions(d: CampaignDefinition, width: Float) = Table().apply {
        val saved = store.saved(d.id)
        val terminal = saved?.campaign
        val unfinished = terminal?.let { it.resultStars == 0 && !it.failed } == true
        if (unfinished) {
            add(control("Continue campaign", "ui-campaign-continue", true) { enter(d.id, false) }.apply {
                add(artwork.icon("arrow", Theme.primaryInk)).size(px(18f)).padLeft(px(10f))
            })
                .growX().minHeight(px(44f))
            add(control("Restart campaign", "ui-campaign-start", quiet = true) { confirmRestart(d, saved) })
                .minHeight(px(44f)).padLeft(px(8f))
        } else if (store.isUnlocked(d, progress) || saved != null) {
            add(control(if (saved == null) "Start campaign" else "Restart campaign", "ui-campaign-start", true) {
                if (saved == null) enter(d.id, true) else confirmRestart(d, saved)
            }.apply { add(artwork.icon("arrow", Theme.primaryInk)).size(px(18f)).padLeft(px(10f)) }).growX().minHeight(px(44f))
        } else {
            add(label("上一关至少获得一星后解锁。", TextRole.Supporting, Theme.muted)).width(width).minHeight(px(44f))
        }
    }

    private fun confirmRestart(d: CampaignDefinition, saved: GameInfo) {
        val unfinished = saved.campaign!!.let { it.resultStars == 0 && !it.failed }
        ConfirmPopup(this, if (unfinished) "Replace this level's unfinished attempt? Your best result will be kept."
            else "Restart campaign".tr() + "?\n" + "Latest attempt: [${saved.campaign!!.resultStars}] / 3 stars".tr() +
                "\n" + (if (progressReadable) "最高成绩：[${store.stars(d, progress)}] / 3 星" else "Campaign progress unavailable").tr(),
            "Restart campaign") { enter(d.id, true, saved.campaign!!.attemptId) }.open()
    }

    private fun initialPreview(scene: GameInfo, width: Float): Group {
        val group = Group().apply { setSize(width, px(180f)) }
        val player = scene.civilizations.single { it.isHuman() }
        if (selected == CivilWarCampaign.id) {
            val scale = minOf((width - px(20f)) / 41f, px(155f) / 33f)
            for (tile in scene.tileMap.tileList) {
                val x = width / 2 + tile.getColumn() * scale
                val y = px(90f) + tile.getRow() * scale
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
            }
            for (tile in scene.tileMap.tileList.filter { it.isCityCenter() || it.position == CivilWarCampaign.harpersFerry }) {
                val x = width / 2 + tile.getColumn() * scale
                val y = px(90f) + tile.getRow() * scale
                val label = if (tile.isCityCenter()) tile.getCity()!!.name.tr()
                    else if (tile.position == CivilWarCampaign.harpersFerry) "哈珀斯费里" else null
                if (label != null) group.addActor(label.toLabel(Theme.text, (12 * unit).roundToInt()).apply {
                    setPosition(x + scale, y); touchable = Touchable.disabled
                })
            }
            return group
        }
        val radius = scene.tileMap.mapParameters.mapSize.radius.coerceAtLeast(1)
        val scale = minOf(width / (radius * 4 + 4), px(160f) / (radius * 2 + 2))
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
                    px(90f) + tile.position.y * scale * .85f, scale * .85f, scale * .7f)
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
                ChallengeTelemetry.report(if (start) "start" else "resume", attempt)
            } catch (ex: Exception) {
                if (ex !is kotlinx.coroutines.CancellationException)
                    ChallengeTelemetry.error("campaign", if (start) "start_failed" else "resume_failed", id)
                com.unciv.utils.Log.error("Cannot enter campaign", ex)
                ToastPopup(ex.message ?: "Could not load campaign", this@CampaignScreen)
            } finally { loading.close() }
        }
    }

    override fun recreate(): BaseScreen = CampaignScreen(chapter, selected, historical, detailTab)

    override fun dispose() {
        disposed = true
        sync.removeStatusListener(syncListener)
        artwork.dispose()
        super.dispose()
    }
}
