package com.unciv.ui.screens.overviewscreen

import com.unciv.ui.components.input.onActivation
import com.unciv.ui.components.widgets.UiNavigation
import com.unciv.ui.components.input.keyShortcuts
import com.badlogic.gdx.scenes.scene2d.ui.Button
import com.unciv.ui.components.input.KeyCharAndCode
import com.unciv.ui.screens.overviewscreen.OverviewUi.pt
import com.unciv.ui.screens.overviewscreen.OverviewUi.font
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextField
import com.unciv.ui.components.extensions.toLabel
import com.unciv.GUI
import com.unciv.logic.civilization.Notification
import com.unciv.ui.components.input.KeyboardBinding
import com.unciv.ui.components.input.DispatcherVetoer
import com.unciv.ui.components.input.KeyShortcutDispatcherVeto.DispatcherVetoResult
import com.unciv.ui.components.widgets.TabbedPager
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.basescreen.RecreateOnResize
import com.unciv.ui.screens.overviewscreen.EmpireOverviewCategories.EmpireOverviewTabState
import com.unciv.view.CivView

class EmpireOverviewScreen(
    private var viewingPlayer: CivView,
    defaultCategory: EmpireOverviewCategories? = null,
    selection: String = ""
) : BaseScreen(), RecreateOnResize {
    // The title and category bars stay outside each page's scrolling content.
    internal val contentWidth = stage.width
    internal val compact = stage.width < pt(640f)
    private val headerHeight = pt(if (compact) 76f else 43f)
    internal val centerAreaHeight = stage.height - headerHeight - pt(57f)

    private val tabbedPager: TabbedPager
    private val categoryButtons = linkedMapOf<EmpireOverviewCategories, Button>()
    private val pageObjects = HashMap<EmpireOverviewCategories, EmpireOverviewTab>()

    internal val persistState by game.settings::overview

    override fun dispose() {
        tabbedPager.selectPage(-1)
        super.dispose()
    }

    override fun getCivilopediaRuleset() = viewingPlayer.ruleset

    override fun getShortcutDispatcherVetoer(): DispatcherVetoer? =
        if (stage.keyboardFocus is TextField) ({ DispatcherVetoResult.SkipWithChildren }) else null

    init {
        val selectCategory = defaultCategory ?: persistState.last
        tabbedPager = TabbedPager(
            contentWidth, contentWidth,
            centerAreaHeight, centerAreaHeight,
            backgroundColor = OverviewUi.background,
            capacity = EmpireOverviewCategories.entries.size,
            showHeader = false).apply { background = OverviewUi.surface(OverviewUi.background) }
        val navigation = Table().apply { left() }

        for (category in EmpireOverviewCategories.entries) {
            val tabState = category.testState(viewingPlayer)
            if (tabState == EmpireOverviewTabState.Hidden) continue
            val button = OverviewUi.button(flat = true) { tabbedPager.selectPage(category.name) }.apply {
                style = Button.ButtonStyle(style).apply { checked = OverviewUi.rounded(OverviewUi.raised); over = checked; down = checked }
                if (category.iconName.isNotEmpty()) add(ImageGetter.getImage(category.iconName)).size(pt(19f)).padRight(pt(6f))
                add(category.name.toLabel(fontSize = font(15), hideIcons = true))
                pad(0f, pt(10f), 0f, pt(10f))
                isDisabled = tabState != EmpireOverviewTabState.Normal
                if (isDisabled) color.a = .5f
                name = "overview-tab-${category.name}"
            }
            categoryButtons[category] = button
            navigation.add(button).height(OverviewUi.touch).padRight(pt(4f))
            val pageObject = category.createTab(viewingPlayer, this, persistState[category])
            pageObject.pad(0f)
            pageObjects[category] = pageObject
            tabbedPager.addPage(
                caption = category.name,
                content = pageObject,
                disabled = tabState != EmpireOverviewTabState.Normal,
                shortcutKey = category.shortcutKey,
                scrollAlign = category.scrollAlign
            )

        }
        persistState.update(pageObjects)

        val closeButton = (if (UiNavigation.isIos) UiNavigation.backButton("Map").apply {
            onActivation { game.popScreen() }
        } else OverviewUi.button(flat = true) { game.popScreen() }.apply {
            add(ImageGetter.getImage("OtherIcons/BackArrow").apply { color = OverviewUi.accent }).size(pt(18f)).padRight(pt(7f))
            add("Map".toLabel(fontColor = OverviewUi.accent, fontSize = font(16), hideIcons = true))
        }).apply {
            keyShortcuts.add(KeyCharAndCode.BACK)
            name = "ui-overview-close"
        }
        val header = Table().apply {
            padRight(pt(12f))
            add(closeButton).minWidth(pt(74f)).height(OverviewUi.touch).padRight(pt(14f))
            add("Empire Overview".toLabel(fontSize = font(20), hideIcons = true).apply { setEllipsis(true) })
                .left().growX().minWidth(0f)
            val info = viewingPlayer.getCiv().gameInfo
            val year = com.unciv.ui.components.YearTextUtil.toYearText(info.getYear(), viewingPlayer.getCiv().isLongCountDisplay())
            val context = Table().apply {
                add(ImageGetter.getNationPortrait(viewingPlayer.getCiv().nation, pt(24f))).size(pt(24f)).padRight(pt(7f))
                add(viewingPlayer.getCiv().civName.toLabel(fontSize = font(15), hideIcons = true).apply { setEllipsis(true) })
                    .minWidth(0f).padRight(pt(14f))
                add("${info.turns} {Turn} · $year".toLabel(fontColor = OverviewUi.muted, fontSize = font(14), hideIcons = true)
                    .apply { setEllipsis(true) }).minWidth(0f)
            }
            if (compact) { row(); add(context).colspan(2).growX().minWidth(0f).left().padLeft(pt(12f)) }
            else add(context)
        }
        val navScroll = OverviewUi.scroll(navigation).apply { setScrollingDisabled(false, true) }
        val root = Table().apply {
            background = OverviewUi.surface(OverviewUi.background)
            setFillParent(true)
            padTop(pt(8f))
            add(header).growX().minWidth(0f).height(headerHeight).row()
            add(ImageGetter.getWhiteDot().apply { color = OverviewUi.border }).growX().height(pt(1f)).row()
            add(navScroll).growX().minWidth(0f).height(pt(48f)).row()
            add(tabbedPager).grow().minWidth(0f).minHeight(0f).row()
        }
        tabbedPager.onSelection { _, caption, _ ->
            for ((category, button) in categoryButtons) {
                button.isChecked = category.name == caption
                (button.cells.last().actor as? com.badlogic.gdx.scenes.scene2d.ui.Label)?.color =
                    if (button.isChecked) OverviewUi.accent else com.unciv.models.skins.ExpeditionBlueTheme.text
            }
            categoryButtons.entries.firstOrNull { it.key.name == caption }?.value?.let { selected ->
                root.validate()
                navScroll.validate()
                navScroll.scrollTo(selected.x, selected.y, selected.width, selected.height, false, false)
                navScroll.updateVisualScroll()
            }
        }
        stage.addActor(root)
        val initial = pageObjects.keys.firstOrNull { it == selectCategory && it.testState(viewingPlayer) == EmpireOverviewTabState.Normal }
            ?: EmpireOverviewCategories.entries.firstOrNull { it.testState(viewingPlayer) == EmpireOverviewTabState.Normal }
        if (initial != null) select(initial, selection)

        globalShortcuts.add(KeyboardBinding.Civilopedia) { openCivilopedia() }
   }

    override fun recreate(): BaseScreen {
        tabbedPager.selectPage(-1)  // trigger deselect on _old_ instance so the tabs can persist their stuff
        return EmpireOverviewScreen(viewingPlayer, persistState.last)
    }

    internal fun activate(tab: EmpireOverviewTab, pager: TabbedPager) {
        pageObjects.entries.firstOrNull { it.value === tab }?.key?.let { persistState.last = it }
        pager.setScrollDisabled(tab.usesOwnScroll)
    }

    fun resizePage(tab: EmpireOverviewTab) {
        val category = (pageObjects.entries.find { it.value == tab } ?: return).key
        tabbedPager.replacePage(category.name, tab)
    }

    fun select(category: EmpireOverviewCategories, selection: String) {
        tabbedPager.selectPage(category.name)
        select(pageObjects[category], selection)
    }
    private fun select(tab: EmpireOverviewTab?, selection: String) {
        if (tab == null) return
        val scrollY = tab.select(selection) ?: return
        tabbedPager.setPageScrollY(tabbedPager.activePage, scrollY)
    }

    /** Helper to show the world screen with a temporary "one-time" notification */
    // Here because it's common to notification history, resource finder, and city WLTK demanded resource
    internal fun showOneTimeNotification(notification: Notification?) {
        if (notification == null) return  // Convenience - easier than a return@lambda for a caller
        val worldScreen = GUI.getWorldScreen()
        worldScreen.notificationsScroll.oneTimeNotification = notification
        GUI.resetToWorldScreen()
        notification.resetExecuteRoundRobin()
        notification.execute(worldScreen)
    }

    override fun resume() {
        // This is called by UncivGame.popScreen - e.g. after City Tab opened a City and the user closes that CityScreen...
        // Notify the current tab via its IPageExtensions.activated entry point so it can refresh if needed
        val index = tabbedPager.activePage
        pageObjects[persistState.last]?.activated(index, "", tabbedPager) // Fake caption marks this as popScreen-triggered
    }
}
