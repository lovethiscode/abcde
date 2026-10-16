package com.unciv.ui.screens.worldscreen

import com.badlogic.gdx.math.Rectangle
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.Button
import com.badlogic.gdx.scenes.scene2d.ui.Image
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.utils.Align
import com.unciv.logic.civilization.Notification
import com.unciv.logic.civilization.NotificationCategory
import com.unciv.logic.civilization.PromoteUnitAction
import com.unciv.models.skins.ExpeditionBlueTheme
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.UncivTooltip.Companion.addTooltip
import com.unciv.ui.components.input.onActivation
import com.unciv.ui.components.widgets.UiNavigation
import com.unciv.ui.images.IconTextButton
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.popups.popups
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.worldscreen.status.MultiplayerStatusButton
import kotlin.math.roundToInt

/** B: commands at the left, turn navigation at the right, and an independent combat preview. */
internal class BattlefieldHud(private val world: WorldScreen) {
    companion object {
        val enabled get() = ExpeditionBlueTheme.enabled
        fun points(value: Float) = value * UiNavigation.minimumTouchSize() / 44f
        fun font(value: Float) = points(value).roundToInt()
    }

    private var eventsExpanded = false
    private var tileRequested = false
    private val contextBackground = Image(BaseScreen.skinStrings.getUiBackground("WorldScreen/UnitTable"))
    private val events = BattlefieldEvents(world)
    private val urgent = Table()
    private val eventCount = "0".toLabel(fontSize = font(12f))
    private val allEvents = Button(BaseScreen.skin).apply {
        name = "ui-notifications-open"
        BattlefieldGeometry.fitControl(this)
        pad(points(4f))
        add(ImageGetter.getImage("OtherIcons/Notifications")).size(points(18f)).row()
        add("All".toLabel(fontSize = font(11f))).row()
        add(eventCount)
        onActivation { eventsExpanded = true; world.shouldUpdate = true }
    }
    private val auxiliaryControls = Table().apply { name = "ui-battlefield-auxiliary-controls" }
    private val eventHeader = Table(BaseScreen.skin)
    private val tileInfo = ScrollPane(world.bottomTileInfoTable, BaseScreen.skin).apply {
        name = "ui-battlefield-tile-details"
        setScrollingDisabled(true, false)
        setOverscroll(false, false)
        setFadeScrollBars(false)
    }
    private val tileClose = iconButton("OtherIcons/Close", "ui-tile-details-close") {
        tileRequested = false
        world.shouldUpdate = true
    }
    private val tools = Table(BaseScreen.skin).apply {
        name = "ui-battlefield-map-tools"
        background = BaseScreen.skinStrings.getUiBackground("WorldScreen/UnitTable")
    }
    private val layerButtons by lazy {
        val labels = listOf("Movement", "Yields", "Worked tiles", "Resources", "Improvements")
        world.minimapWrapper.buttons.mapIndexed { index, toggle ->
            Button(Button.ButtonStyle()).apply {
                name = "ui-map-layer:$index"
                pad(0f)
                add(toggle.actor)
                addTooltip(labels[index])
                onActivation {
                    toggle.toggle()
                    toggle.update()
                    world.shouldUpdate = true
                }
            }
        }
    }

    private fun layoutTools() {
        val settings = world.game.settings
        val checked = listOf(settings.showUnitMovements, settings.showTileYields, settings.showWorkedTiles,
            settings.showResourcesAndImprovements, settings.showPixelImprovements)
        tools.clearChildren()
        // User-requested compact rail: five borderless glyphs share the minimap's actual height.
        val rowHeight = world.minimapWrapper.height / layerButtons.size
        val iconSize = minOf(points(16f), rowHeight)
        tools.pad(0f)
        layerButtons.forEachIndexed { index, button ->
            button.isChecked = checked[index]
            button.cells.first().size(iconSize)
            tools.add(button).width(points(24f)).height(rowHeight).pad(0f).row()
        }
        tools.pack()
    }

    init {
        if (world.gameInfo.turns > 0) world.game.isTutorialTaskCollapsed = true
        contextBackground.touchable = Touchable.disabled
        contextBackground.name = "ui-battlefield-context"
        world.stage.addActor(contextBackground)
        world.stage.addActor(tileInfo)
        world.stage.addActor(tileClose)
        world.stage.addActor(events)
        world.stage.addActor(eventHeader)
        world.stage.addActor(urgent)
        world.stage.addActor(allEvents)
        world.stage.addActor(tools)
        world.stage.addActor(auxiliaryControls)
        world.stage.addActor(world.bottomUnitTable.battlefieldPreviousUnitButton)
        events.onExecuted = { tileRequested = true }
    }

    fun dismissTileDetails() { tileRequested = false }
    fun onMapTap() { tileRequested = true; eventsExpanded = false }

    fun setVisible(visible: Boolean) {
        if (visible) { world.shouldUpdate = true; return }
        for (actor in listOf(contextBackground, tileInfo, tileClose, events, eventHeader, urgent, allEvents, tools, auxiliaryControls,
            world.bottomUnitTable.battlefieldPreviousUnitButton)) actor.isVisible = visible
    }

    private fun iconButton(path: String, id: String, action: () -> Unit) = Button(BaseScreen.skin).apply {
        name = id
        BattlefieldGeometry.fitControl(this)
        pad(points(6f))
        add(ImageGetter.getImage(path)).size(points(20f))
        onActivation { action() }
    }

    private fun execute(notification: Notification) {
        notification.execute(world)
        tileRequested = true
        world.shouldUpdate = true
    }

    private fun rebuildUrgent(width: Float, notifications: List<Notification>) {
        urgent.clearChildren()
        val touch = UiNavigation.minimumTouchSize()
        val gap = points(4f)
        val war = notifications.filter { it.category == NotificationCategory.War }
            .minByOrNull { if (it.icons.any { icon -> icon.endsWith("CrosshairB") }) 0 else 1 }
        val promotion = notifications.firstOrNull { it.actions.any { action -> action is PromoteUnitAction } }
        // Clicking an event must not move its row: a second tap continues its original action cycle.
        val entries = (listOfNotNull(war, promotion) + notifications).distinct().take(2)
        for ((index, notification) in entries.withIndex()) {
            val row = Button(BaseScreen.skin).apply {
                name = "ui-urgent-notification:$index"
                BattlefieldGeometry.fitControl(this)
                pad(points(5f))
                val icons = Table()
                notification.addNotificationIconsTo(icons, world.gameInfo.ruleset, points(17f))
                icons.touchable = Touchable.disabled
                add(icons).padRight(points(5f))
                val label = notification.text.toLabel(fontSize = font(12f), hideIcons = true).apply {
                    wrap = true
                    setAlignment(Align.left)
                    color = if (notification.category == NotificationCategory.War) ExpeditionBlueTheme.danger else ExpeditionBlueTheme.gold
                    touchable = Touchable.disabled
                }
                add(label).minWidth(0f).growX()
                onActivation { execute(notification) }
            }
            urgent.add(row).width(width).height(touch)
                .padBottom(if (index < entries.lastIndex) gap else 0f).row()
        }
        eventCount.setText(notifications.size.toString())
        urgent.pack()
    }

    fun layout() {
        val safe = world.safeAreaBoundsInWorld()
        val gap = points(8f)
        val left = safe.x + gap
        val right = safe.x + safe.width - gap
        val bottom = safe.y + gap
        val width = right - left
        val portrait = safe.height > safe.width
        val touch = UiNavigation.minimumTouchSize()
        val inlineTech = world.topBar.refitBattlefield()
        if (inlineTech != null) world.techPolicyAndDiplomacy.setPosition(inlineTech.x, inlineTech.y)
        else world.techPolicyAndDiplomacy.setPosition(left, world.topBar.y - gap - world.techPolicyAndDiplomacy.height)
        val leftTop = if (inlineTech == null) world.techPolicyAndDiplomacy.y - gap else world.topBar.y - gap
        val top = if (portrait) leftTop else world.topBar.y - gap

        val status = world.statusButtons
        status.update(false)
        val auxiliary = status.children.filter { it !== world.nextTurnButton }
        world.nextTurnButton.fitBattlefield(minOf(points(164f), width * .44f))
        for (actor in auxiliary) {
            if (actor is Button) {
                BattlefieldGeometry.fitControl(actor)
                actor.pad(points(6f))
                for (cell in actor.cells) cell.size(points(20f)).pad(0f)
                if (actor is MultiplayerStatusButton) actor.fitBattlefield()
                if (actor is IconTextButton) {
                    actor.label.isVisible = false
                    actor.labelCell.size(0f).pad(0f)
                }
            }
            status.getCell(actor).size(touch).padRight(gap)
        }
        auxiliaryControls.clearChildren()
        auxiliaryControls.isVisible = !portrait && auxiliary.isNotEmpty()
        if (!portrait) {
            // Keep turn navigation to one row. Optional automation/cycle controls belong with orders.
            status.clearChildren()
            for (actor in auxiliary) auxiliaryControls.add(actor).size(touch).padRight(gap)
            auxiliaryControls.pack()
            status.add(world.nextTurnButton).size(world.nextTurnButton.width, world.nextTurnButton.height)
        } else {
            status.getCell(world.nextTurnButton).size(world.nextTurnButton.width, world.nextTurnButton.height)
        }
        status.pack()
        if (portrait && status.width + touch + gap > width) {
            // A multiplayer/automation combination can exceed a narrow phone's bottom row.
            status.clearChildren()
            for (actor in auxiliary) status.add(actor).size(touch).padBottom(gap)
            if (auxiliary.isNotEmpty()) status.row()
            status.add(world.nextTurnButton).colspan(auxiliary.size.coerceAtLeast(1))
                .size(world.nextTurnButton.width, world.nextTurnButton.height)
            status.pack()
        }
        status.setPosition(right - status.width, bottom)
        val previous = world.bottomUnitTable.battlefieldPreviousUnitButton
        previous.pad(points(6f))
        previous.setSize(touch, touch)
        previous.setPosition(status.x - touch - gap, bottom + (world.nextTurnButton.height - touch) / 2f)
        for (cell in previous.cells) cell.size(points(20f)).pad(0f)
        previous.isVisible = true
        previous.touchable = Touchable.enabled // A disabled arrow must absorb the tap instead of selecting terrain.

        val contextWidth = if (portrait) minOf(width, points(440f)) else minOf(points(200f), width * .28f)
        val contextX = left
        val contextY = if (portrait) bottom + status.height + gap else bottom
        val rightWidth = minOf(width, points(236f))
        val map = world.minimapWrapper
        map.fitBattlefield(points(76f), points(76f), minOf(width, points(560f)) - gap * 2f,
            maxOf(touch, top - bottom - status.height - gap * 3f - if (world.chatButton.isVisible) touch + gap else 0f))
        map.update(world.getGameViewConsideringForOfWar().civView.getCiv())
        layoutTools()
        tools.isVisible = !map.battlefieldExpanded
        tools.setPosition(right - tools.width, top - tools.height)
        map.setPosition(if (map.battlefieldExpanded) right - map.width else tools.x - points(4f) - map.width,
            top - map.height)
        allEvents.setBounds(map.x - gap - touch, map.y + (map.height - points(64f)) / 2f, touch, points(64f))
        world.chatButton.apply {
            fitBattlefield()
            setPosition(if (map.battlefieldExpanded) right - touch else allEvents.x - gap - touch,
                if (map.battlefieldExpanded) map.y - gap - touch else map.y + (map.height - touch) / 2f)
        }
        tools.toFront()
        map.toFront()
        allEvents.toFront()
        world.chatButton.toFront()

        val battle = world.battleTable
        val battleX = if (portrait) left else contextX + contextWidth + gap
        val battleWidth = if (portrait) contextWidth else minOf(points(406f), right - rightWidth - gap - battleX)
        battle.fitBattlefield(battleWidth)
        if (map.battlefieldExpanded) battle.isVisible = false
        val selected = world.bottomUnitTable.hasBattlefieldSelection
        val showUnit = selected && !map.battlefieldExpanded
        val showActions = showUnit && !(portrait && battle.isVisible) &&
            (world.canChangeState || world.bottomUnitTable.selectedCity != null)
        world.bottomUnitTable.isVisible = showUnit
        world.unitActionsTable.isVisible = showActions
        contextBackground.isVisible = showUnit
        tileInfo.isVisible = !selected && tileRequested && !battle.isVisible && !map.battlefieldExpanded &&
            world.bottomTileInfoTable.hasBattlefieldContent
        tileClose.isVisible = tileInfo.isVisible
        var contextTop = contextY
        if (showUnit) {
            val actions = world.unitActionsTable
            val unit = world.bottomUnitTable
            actions.battlefieldWidth = contextWidth - points(8f)
            actions.battlefieldColumns = if (portrait) 4 else 2
            actions.update(unit.selectedUnit?.getUnit())
            if (unit.selectedCity != null) actions.updateBattlefieldCity()
            unit.fitBattlefield(contextWidth)
            actions.setPosition(contextX + points(4f), contextY + points(4f))
            unit.setPosition(contextX, if (showActions) actions.y + actions.height else contextY)
            contextTop = unit.y + unit.height
            contextBackground.setBounds(contextX, contextY, contextWidth, contextTop - contextY)
            contextBackground.toFront()
            unit.toFront()
            if (showActions) actions.toFront()
        } else if (tileInfo.isVisible) {
            world.bottomTileInfoTable.fitBattlefield(contextWidth - touch - gap)
            val height = minOf(points(156f), maxOf(touch, top - contextY - gap))
            tileInfo.setBounds(contextX, contextY, contextWidth - touch, height)
            tileInfo.invalidate()
            tileInfo.validate()
            tileClose.setBounds(contextX + contextWidth - touch, contextY + height - touch, touch, touch)
            contextTop = contextY + height
            tileInfo.toFront()
            tileClose.toFront()
        }
        if (auxiliaryControls.isVisible) {
            auxiliaryControls.setPosition(contextX, contextTop + gap)
            contextTop = auxiliaryControls.y + auxiliaryControls.height
            auxiliaryControls.toFront()
        }
        if (battle.isVisible) {
            battle.setPosition(battleX, if (portrait) contextTop + gap else bottom)
            contextTop = maxOf(contextTop, battle.y + battle.height)
            battle.toFront()
        }

        val notificationSetting = world.game.settings.notificationScroll
        val showEvents = notificationSetting != NotificationsScroll.UserSetting.Disabled.name &&
            notificationSetting != NotificationsScroll.UserSetting.Hidden.name
        val oneTime = world.notificationsScroll.oneTimeNotification
        events.remember(oneTime)
        if (oneTime != null) { eventsExpanded = true; world.notificationsScroll.oneTimeNotification = null }
        urgent.isVisible = showEvents && !eventsExpanded && !map.battlefieldExpanded
        allEvents.isVisible = urgent.isVisible
        events.isVisible = showEvents && eventsExpanded && !map.battlefieldExpanded
        eventHeader.isVisible = events.isVisible
        if (showEvents) {
            world.notificationsScroll.isVisible = false
            rebuildUrgent(rightWidth - tools.width - points(4f), world.viewingCiv.notifications)
            urgent.setPosition(right - urgent.width, map.y - gap - urgent.height)
            if (events.isVisible) {
                val eventWidth = if (portrait) width else points(304f)
                eventHeader.clearChildren()
                eventHeader.add("Notifications".toLabel(fontSize = font(14f))).minWidth(0f).growX().left()
                eventHeader.add(iconButton("OtherIcons/Close", "ui-notifications-close") {
                    eventsExpanded = false; world.shouldUpdate = true
                }).size(touch)
                eventHeader.background = BaseScreen.skinStrings.getUiBackground("WorldScreen/Notification")
                eventHeader.setBounds(right - eventWidth, top - touch, eventWidth, touch)
                eventHeader.validate()
                val eventBottom = if (portrait) maxOf(contextTop + gap, bottom + status.height + gap) else bottom + status.height + gap
                val eventHeight = if (events.hasEntries(world.viewingCiv.notifications))
                    maxOf(touch, eventHeader.y - eventBottom) else touch
                events.update(world.viewingCiv.notifications, oneTime, Rectangle(right - eventWidth, eventHeader.y - eventHeight,
                    eventWidth, eventHeight))
                events.toFront()
                eventHeader.toFront()
            }
        } else world.notificationsScroll.update(world.viewingCiv.notifications, safe.y + safe.height - top, bottom)
        if (world.gameInfo.campaign == null && world.gameInfo.endless == null) {
            val tutorialTop = if (portrait && urgent.isVisible) urgent.y - gap else leftTop
            world.tutorialTaskTable.fitBattlefield(Rectangle(left,
                maxOf(contextTop + gap, tutorialTop - points(190f)),
                minOf(points(340f), width),
                minOf(points(190f), maxOf(touch, tutorialTop - contextTop - gap))))
        }
        world.zoomController.setPosition(left, contextTop + gap)
        // HUD actors must stay below the popup's full-screen input shield.
        world.popups.forEach { it.toFront() }
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
    var onExecuted: (Notification) -> Unit = {}
    fun remember(notification: Notification?) { if (notification != null) historyEntry = notification }
    fun hasEntries(notifications: List<Notification>) = notifications.isNotEmpty() || historyEntry != null

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
        if (entries.isEmpty()) {
            list.add("No notifications".toLabel(fontSize = BattlefieldHud.font(13f))).left()
                .pad(BattlefieldHud.points(8f)).minHeight(touch).row()
        }
        for ((index, notification) in entries.withIndex()) {
            val row = Button(BaseScreen.skin)
            BattlefieldGeometry.fitControl(row)
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
                onExecuted(notification)
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
