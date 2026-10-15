package com.unciv.ui.screens.worldscreen

import com.badlogic.gdx.math.Rectangle
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.Button
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.utils.Align
import com.unciv.logic.civilization.Notification
import com.unciv.logic.civilization.NotificationCategory
import com.unciv.models.skins.ExpeditionBlueTheme
import com.unciv.ui.components.extensions.setFontSize
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.input.onActivation
import com.unciv.ui.components.widgets.UiNavigation
import com.unciv.ui.screens.basescreen.BaseScreen
import kotlin.math.roundToInt

/** The battlefield's chrome uses native point-sized controls; map actors keep their own coordinates. */
internal class BattlefieldHud(private val world: WorldScreen) {
    companion object {
        val enabled get() = ExpeditionBlueTheme.enabled
        fun points(value: Float) = value * UiNavigation.minimumTouchSize() / 44f
        fun font(value: Float) = points(value).roundToInt()
    }

    private val events = BattlefieldEvents(world)
    private val tileInfo = ScrollPane(world.bottomTileInfoTable, BaseScreen.skin).apply {
        name = "ui-battlefield-tile-details"
        setScrollingDisabled(true, false)
        setOverscroll(false, false)
        setFadeScrollBars(false)
    }
    private val mapTools = Table(BaseScreen.skin).apply {
        name = "ui-battlefield-map-tools"
        background = BaseScreen.skinStrings.getUiBackground("WorldScreen/UnitTable")
        defaults().space(points(4f))
        for ((index, toggle) in world.minimapWrapper.buttons.withIndex()) {
            val button = Button(BaseScreen.skin)
            button.name = "ui-map-layer:$index"
            val icon = toggle.actor
            icon.touchable = Touchable.disabled
            button.add(icon).size(points(20f))
            button.onActivation { toggle.toggle() }
            add(button).size(UiNavigation.minimumTouchSize())
        }
        pack()
    }

    init {
        world.stage.addActor(tileInfo)
        world.stage.addActor(events)
        world.stage.addActor(mapTools)
    }

    fun setVisible(visible: Boolean) {
        tileInfo.isVisible = visible
        events.isVisible = visible
        mapTools.isVisible = visible
    }

    fun layout() {
        val safe = world.safeAreaBoundsInWorld()
        val gap = points(6f)
        val left = safe.x + gap
        val right = safe.x + safe.width - gap
        val bottom = safe.y + gap
        val width = right - left
        val portrait = safe.height > safe.width
        val touch = UiNavigation.minimumTouchSize()
        val top = world.topBar.y - gap

        world.techPolicyAndDiplomacy.setPosition(left, top - world.techPolicyAndDiplomacy.height)
        world.chatButton.updatePosition()
        world.nextTurnButton.label.setFontSize(font(14f))
        world.nextTurnButton.label.wrap = true
        world.nextTurnButton.labelCell.minWidth(0f)
        val sideWidth = minOf(points(224f), width * .28f)
        val actionWidth = if (portrait) width else sideWidth
        val footerWidth = if (portrait) (width - gap) / 2f else sideWidth
        world.statusButtons.update(false)
        val auxiliary = world.statusButtons.children.filter { it !== world.nextTurnButton }
        world.nextTurnButton.fitBattlefield(footerWidth - auxiliary.size * (touch + gap))
        for (actor in auxiliary) world.statusButtons.getCell(actor).size(touch).padRight(gap)
        world.statusButtons.getCell(world.nextTurnButton).size(world.nextTurnButton.width, world.nextTurnButton.height)
        world.statusButtons.pack()
        world.statusButtons.setPosition(right - world.statusButtons.width, bottom)

        val tileHeight: Float
        val tileBottom: Float
        val commandBottom: Float
        if (portrait) {
            tileHeight = maxOf(points(88f), world.statusButtons.height)
            tileBottom = bottom
            commandBottom = bottom + tileHeight + gap
        } else {
            tileHeight = points(96f)
            tileBottom = bottom + world.statusButtons.height + gap
            commandBottom = bottom
        }
        world.bottomTileInfoTable.fitBattlefield(footerWidth)
        tileInfo.isVisible = world.bottomTileInfoTable.hasBattlefieldContent
        tileInfo.setBounds(if (portrait) left else right - footerWidth, tileBottom, footerWidth, tileHeight)
        tileInfo.invalidate()
        tileInfo.validate()

        val unit = world.bottomUnitTable
        val actions = world.unitActionsTable
        actions.battlefieldWidth = actionWidth
        actions.battlefieldColumns = if (portrait) 3 else 1
        unit.fitBattlefield(actionWidth)
        if (portrait) {
            actions.y = commandBottom
            actions.battlefieldAvailableHeight = touch * 2f + gap * 2f
            actions.update(unit.selectedUnit?.getUnit())
            actions.setPosition(left, commandBottom)
            unit.setPosition(left, actions.y + actions.height + gap)
            mapTools.setPosition(left + (width - mapTools.width) / 2f, unit.y + unit.height + gap)
        } else {
            unit.setPosition(left, bottom)
            actions.y = unit.y + unit.height + gap
            actions.battlefieldAvailableHeight = world.techPolicyAndDiplomacy.y - actions.y - gap
            if (actions.battlefieldAvailableHeight < (touch + points(4f)) * 5f)
                actions.battlefieldColumns = 2
            actions.update(unit.selectedUnit?.getUnit())
            actions.setPosition(left, unit.y + unit.height + gap)
            mapTools.setPosition(left + sideWidth + (width - sideWidth * 2f - mapTools.width) / 2f, bottom)
        }
        unit.fitBattlefield(actionWidth)

        val minimap = world.minimapWrapper
        val minimapTop = if (portrait) world.techPolicyAndDiplomacy.y - gap else top
        val expandedWidth = if (portrait) width else width - sideWidth - gap
        val expandedHeight = maxOf(touch, minimapTop - mapTools.y - mapTools.height - gap)
        minimap.fitBattlefield(points(if (portrait) 125f else 145f), points(86f), expandedWidth - gap * 2f, expandedHeight - gap * 2f)
        minimap.update(world.getGameViewConsideringForOfWar().civView.getCiv())
        minimap.setPosition(right - minimap.width, minimapTop - minimap.height)

        val battle = world.battleTable
        if (battle.isVisible) {
            battle.setPosition(left + (width - battle.width) / 2f, mapTools.y + mapTools.height + gap)
            if (portrait) minimap.isVisible = false
        }

        var eventBottom = if (portrait) maxOf(mapTools.y + mapTools.height + gap, minimap.y - points(138f) - gap)
            else if (tileInfo.isVisible) tileBottom + tileHeight + gap else tileBottom
        if (portrait && battle.isVisible) eventBottom = maxOf(eventBottom, battle.y + battle.height + gap)
        val eventTop = if (minimap.isVisible) minimap.y - gap else minimapTop
        val eventWidth = if (portrait) minOf(points(184f), width * .52f) else sideWidth
        val eventBounds = Rectangle(right - eventWidth, eventBottom, eventWidth, maxOf(0f, eventTop - eventBottom))
        val notificationSetting = world.game.settings.notificationScroll
        val showEvents = notificationSetting != NotificationsScroll.UserSetting.Disabled.name &&
            notificationSetting != NotificationsScroll.UserSetting.Hidden.name
        events.isVisible = showEvents && eventBounds.height >= touch && !minimap.battlefieldExpanded
        if (minimap.battlefieldExpanded) tileInfo.isVisible = false
        if (showEvents) {
            world.notificationsScroll.isVisible = false
            val oneTime = world.notificationsScroll.oneTimeNotification
            events.update(world.viewingCiv.notifications, oneTime, eventBounds)
            world.notificationsScroll.oneTimeNotification = null
        } else {
            // Preserve the existing hidden/disabled bell and notification-history behavior.
            world.notificationsScroll.update(world.viewingCiv.notifications, safe.y + safe.height - eventTop, eventBottom)
        }

        world.zoomController.setPosition(right - sideWidth - world.zoomController.width - gap,
            mapTools.y + mapTools.height + gap)
    }
}

/** Each row retains the original Notification instance, including its action-cycle cursor. */
private class BattlefieldEvents(private val world: WorldScreen) : ScrollPane(Table(), BaseScreen.skin) {
    private val list = actor as Table
    private var lastEntries = emptyList<Notification>()
    private var lastWidth = 0f
    private var lastTouch = 0f
    private var selected: Notification? = null
    private var historyEntry: Notification? = null

    init {
        name = "ui-battlefield-events"
        setScrollingDisabled(true, false)
        setOverscroll(false, false)
        setFadeScrollBars(false)
        style = ScrollPaneStyle(style).apply {
            background = BaseScreen.skinStrings.getUiBackground("WorldScreen/Notification")
        }
    }

    fun update(notifications: List<Notification>, oneTime: Notification?, bounds: Rectangle) {
        if (oneTime != null) historyEntry = oneTime
        val entries = (listOfNotNull(historyEntry?.takeIf { it !in notifications }) + notifications)
            .sortedBy { when (it.category) {
                NotificationCategory.Units -> 0
                NotificationCategory.War -> 1
                else -> it.category.ordinal + 2
            } }
        val touch = UiNavigation.minimumTouchSize()
        setBounds(bounds.x, bounds.y, bounds.width, bounds.height)
        if (entries == lastEntries && width == lastWidth && touch == lastTouch) return
        val previousScroll = scrollY
        list.clearChildren()
        list.top().left()
        for ((index, notification) in entries.withIndex()) {
            val row = Button(BaseScreen.skin)
            row.name = "ui-notification:${notification.category.name}:$index"
            row.isChecked = selected === notification
            row.pad(BattlefieldHud.points(5f))
            val icons = Table()
            notification.addNotificationIconsTo(icons, world.gameInfo.ruleset, BattlefieldHud.points(17f))
            icons.touchable = Touchable.disabled
            row.add(icons).padRight(BattlefieldHud.points(5f))
            val label = notification.text.toLabel(fontSize = BattlefieldHud.font(13f), hideIcons = true)
            label.wrap = true
            label.setAlignment(Align.left)
            label.touchable = Touchable.disabled
            row.add(label).minWidth(0f).growX()
            row.onActivation {
                notification.execute(world)
                selected = notification
                lastEntries = emptyList()
                world.shouldUpdate = true
            }
            list.add(row).width(bounds.width).minHeight(touch).growX().padBottom(BattlefieldHud.points(4f)).row()
        }
        lastEntries = entries.toList()
        lastWidth = width
        lastTouch = touch
        validate()
        scrollY = previousScroll
        updateVisualScroll()
    }
}
