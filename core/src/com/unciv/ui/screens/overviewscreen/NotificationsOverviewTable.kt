package com.unciv.ui.screens.overviewscreen

import com.unciv.ui.components.fonts.TextRole
import com.unciv.ui.screens.overviewscreen.OverviewUi.pt
import com.unciv.ui.screens.overviewscreen.OverviewUi.font
import com.badlogic.gdx.graphics.Color
import com.unciv.models.skins.ExpeditionBlueTheme
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane
import com.badlogic.gdx.scenes.scene2d.ui.SelectBox
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.Notification
import com.unciv.ui.components.YearTextUtil
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.fonts.Fonts
import com.unciv.ui.components.input.onChange
import com.unciv.ui.components.input.onClick
import com.unciv.ui.components.widgets.ColorMarkupLabel
import com.unciv.ui.components.widgets.ExpanderTab
import com.unciv.ui.components.widgets.TabbedPager
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.basescreen.BaseScreen.Companion.skinStrings
import com.unciv.utils.Concurrency
import com.unciv.view.CivView
import com.unciv.utils.toGdxArray

class NotificationsOverviewTable(
    viewingPlayer: CivView,
    overviewScreen: EmpireOverviewScreen,
    persistedData: EmpireOverviewTabPersistableData? = null
) : EmpireOverviewTab(viewingPlayer, overviewScreen) {
    class NotificationsTabPersistableData(
        var scrollY: Float? = null,
        var lastCount: Int? = null,
        var lastTurn: Int? = null,
        val closedTurns: MutableSet<Int> = mutableSetOf()
    ) : EmpireOverviewTabPersistableData()
    override val persistableData = (persistedData as? NotificationsTabPersistableData) ?: NotificationsTabPersistableData()

    private val notificationLog = viewingPlayer.getCiv().notificationsLog
    private val notificationWidth = overviewScreen.contentWidth
    /** Color for notifications that are new this turn but which we have already seen.
     *  Defaults to 'Eggshell'. */
    private val highlightColor1 = skinStrings.getUIColor("OverviewScreen/NotificationLog/HighlightColor1", Color.valueOf("#f0ead6"))
    /** Color for notifications that are new and unseen this turn
     *  Defaults to HSV(33,40,96) intensified 'Bisque' */
    // Not using NotificationsScroll.oneTimeNotificationColor (#fceea8) here, not enough contrast to highlight 1
    private val highlightColor2 = skinStrings.getUIColor("OverviewScreen/NotificationLog/HighlightColor2", Color.valueOf("#f5c993"))
    private val highlightCount1: Int
    private val highlightCount2: Int
    private val expanders = mutableMapOf<Int, ExpanderTab>()
    private val selectItems = mutableListOf<SelectItem>()
    private var selectWidth = 0f
    private val selectBox: SelectBox<SelectItem>
    private val filter = com.unciv.ui.components.widgets.TranslatedSelectBox(
        listOf("All") + Notification.NotificationCategory.entries.map { it.name }, "All")
    private var generation = 0
    private val fixedContent = Table()
    override fun getFixedContent() = fixedContent

    private fun matches(notification: Notification) =
        filter.selected.value == "All" || notification.category.name == filter.selected.value

    companion object {
        private const val tablePadding = 0f
        private const val currentTurn = "Current turn"
    }

    init {
        top()
        defaults().space(tablePadding).top()

        val currentNotificationCount = viewingPlayer.getCiv().notifications.size
        highlightCount1 = viewingPlayer.getCiv().notificationCountAtStartTurn ?: currentNotificationCount
        highlightCount2 = persistableData.lastCount.takeIf { persistableData.lastTurn == gameInfo.turns }
            ?: highlightCount1
        generateNotificationTable()
        persistableData.lastCount = currentNotificationCount
        persistableData.lastTurn = gameInfo.turns

        selectBox = getSelectBox()

        OverviewUi.styleSelect(filter)
        OverviewUi.styleSelect(selectBox)
        val tools = Table().apply {
            background = OverviewUi.rounded(OverviewUi.panel, 10f)
            left().padRight(pt(12f))
            add(filter).height(OverviewUi.touch).padRight(pt(8f))
            add(selectBox).height(OverviewUi.touch)
            add().growX()
        }
        fixedContent.add(tools).width(overviewScreen.contentWidth).height(OverviewUi.touch).padTop(pt(8f)).row()
        fixedContent.add(ImageGetter.getWhiteDot().apply { color = OverviewUi.border })
            .width(overviewScreen.contentWidth).height(pt(1f))
        filter.onChange {
            generation++
            clearChildren()
            expanders.clear()
            selectItems.clear()
            generateNotificationTable()
            addNotificationLogTurnsAsync(notificationLog.asReversed().iterator(), generation)
            refreshSelectBoxItems()
        }
        addNotificationLogTurnsAsync(notificationLog.asReversed().iterator(), generation)
    }

    override fun activated(index: Int, caption: String, pager: TabbedPager) {
        if (persistableData.scrollY != null)
            pager.setPageScrollY(index, persistableData.scrollY!!)
        super.activated(index, caption, pager)

    }

    override fun deactivated(index: Int, caption: String, pager: TabbedPager) {
        persistableData.scrollY = pager.getPageScrollY(index)
        persistableData.closedTurns.clear()
        expanders.filterNot { it.value.isOpen }.mapTo(persistableData.closedTurns) { it.key }
    }

    private fun generateNotificationTable() {
        if (viewingPlayer.getCiv().notifications.any(::matches))
            add(oneTurnTable(gameInfo.turns, viewingPlayer.getCiv().notifications, doHighlight = true)).row()
    }

    /** Adds one past-turn table per call, each scheduled only once the previous one is done -
     *  building all of them up front on the same frame caused ANRs.
     *  If we still see ANRs from notifications overview we may need to do a category at a time
     *  which will be annoying :( */
    private fun addNotificationLogTurnsAsync(iterator: Iterator<Civilization.NotificationsLog>, version: Int) {
        if (version != generation || !iterator.hasNext()) return
        val turnNotifications = iterator.next()
        Concurrency.runOnGLThread {
            if (version != generation) return@runOnGLThread
            if (turnNotifications.notifications.any(::matches))
                add(oneTurnTable(turnNotifications.turn, turnNotifications.notifications, doHighlight = false)).row()
            refreshSelectBoxItems()
            // Lwjgl3Application loop() shows that adding runnables from within a runnable,
            // causes it to run on the next loop - e.g. after render and handling input
            // And if inputs are handled, no ANR :)
            addNotificationLogTurnsAsync(iterator, version)
        }
    }

    private fun refreshSelectBoxItems() {
        selectBox.items = selectItems.toGdxArray()
        selectBox.isDisabled = selectItems.isEmpty()
        val bgWidth = skin[SelectBox.SelectBoxStyle::class.java].background.run { leftWidth + rightWidth }
        selectBox.width = selectWidth + bgWidth + 10f
    }

    private fun oneTurnTable(turn: Int, notifications: List<Notification>, doHighlight: Boolean): Table {
        val open = turn == gameInfo.turns || turn !in persistableData.closedTurns
        val expander = ExpanderTab("", startsOutOpened = open, defaultPad = 0f) { turnTable ->
            for (category in Notification.NotificationCategory.entries) {
                val categoryNotifications = notifications
                    .withIndex().filter { it.value.category == category && matches(it.value) }
                if (categoryNotifications.isEmpty()) continue

                for ((index, notification) in categoryNotifications) {
                    val notificationTable = getNotificationTable(turn, index, notification, doHighlight)
                    turnTable.add(notificationTable).width(notificationWidth).minHeight(pt(65f)).row()
                    turnTable.add(ImageGetter.getWhiteDot().apply { color = OverviewUi.border.cpy().apply { a = .47f } }).height(pt(1f)).row()
                }
            }
        }

        val turnLabel = getTurnLabel(turn)
        expander.headerContent.add(turnLabel).center()
        if (turn == gameInfo.turns) {
            expander.getCell(expander.header).height(0f)
            expander.header.isVisible = false
            expander.header.touchable = Touchable.disabled
        }
        expanders[turn] = expander
        val selectLabel = if (turn != gameInfo.turns) turnLabel
            else currentTurn.toLabel(TextRole.Body)
        selectItems += SelectItem(turn, selectLabel.text.toString())
        selectWidth = selectWidth.coerceAtLeast(selectLabel.prefWidth)

        return expander
    }

    private fun getTurnLabel(turn: Int): Label {
        val turnOffset = turn - gameInfo.turns // Yes, 0 or negative
        val yearText = YearTextUtil.toYearText(
            gameInfo.getYear(turnOffset),
            viewingPlayer.getCiv().isLongCountDisplay()
        )
        val text = (if (turnOffset == 0) "{$currentTurn}\u2004|\u2004" else "") +
            "${Fonts.turn}\u2004{$turn}\u2004|\u2004$yearText" // U+2004: Three-Per-Em Space
        return text.toLabel(TextRole.Body)
    }

    private fun getNotificationTable(turn: Int, index: Int, notification: Notification, doHighlight: Boolean) = Table(BaseScreen.skin).apply {
        val icons = Table()
        notification.addNotificationIconsTo(icons, gameInfo.ruleset, pt(28f))
        pad(pt(10f), pt(16f), pt(10f), pt(16f))
        add(icons).padRight(pt(12f))
        val details = Table().apply {
            left()
            val label = ColorMarkupLabel(notification.text,
                ExpeditionBlueTheme.color(ImageGetter.CHARCOAL, ExpeditionBlueTheme.text), fontSize = font(TextRole.Control))
                .apply { wrap = true }
            add(label).width(notificationWidth - icons.prefWidth - pt(132f)).left().row()
            add(notification.category.name.toLabel(fontColor = OverviewUi.muted, fontSize = font(TextRole.Supporting)))
                .left().padTop(pt(5f))
        }
        add(details).minWidth(0f).growX().left()
        add("{Turn} $turn".toLabel(fontColor = OverviewUi.muted, fontSize = font(TextRole.Supporting))).right().padLeft(pt(16f))

        val tintColor = when {
            !doHighlight -> OverviewUi.panel
            index >= highlightCount2 -> highlightColor2
            index >= highlightCount1 -> highlightColor1
            else -> OverviewUi.panel
        }
        background = OverviewUi.surface(tintColor)
        touchable = Touchable.enabled
        if (notification.actions.isNotEmpty())
            onClick { overviewScreen.showOneTimeNotification(notification) }
    }

    private class SelectItem(val turn: Int, val label: String) {
        override fun toString() = label
    }

    private fun getSelectBox() = SelectBox<SelectItem>(skin).apply {
        items = selectItems.toGdxArray()
        onChange {
            val selectTurn = selected?.turn ?: return@onChange
            val selectedExpander = expanders[selectTurn] ?: return@onChange
            for ((turn, expander) in expanders)
                expander.isOpen = turn == selectTurn
            scrollTo(selectTurn, selectedExpander)
        }
        val bgWidth = skin[SelectBox.SelectBoxStyle::class.java].background.run { leftWidth + rightWidth }
        width = selectWidth + bgWidth + 10f // some extra so the text is not glued to the arrow (Gdx fails to respect its width for its layout)
    }

    private fun scrollTo(turn: Int, expander: ExpanderTab) {
        val scroll = parent as? ScrollPane ?: return
        // scrollTo didn't fit - a: Expander now starts animating, layout not final, b: doesn't prioritize upper edge
        // Use the knowledge that all expanders above the target are closed and have the same height as target's header
        val itemHeight = expander.header.height + tablePadding
        scroll.scrollY = expanders.keys.indexOf(turn) * itemHeight
    }
}
