package com.unciv.ui.screens.worldscreen

import com.badlogic.gdx.math.Rectangle
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.Cell
import com.badlogic.gdx.utils.Align
import com.unciv.UncivGame
import com.unciv.models.ruleset.Event
import com.unciv.ui.components.extensions.centerX
import com.unciv.ui.components.extensions.darken
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.fonts.TextRole
import com.unciv.ui.components.fonts.UiTypography
import com.unciv.ui.components.input.onClick
import com.unciv.ui.components.widgets.AutoScrollPane
import com.unciv.ui.components.widgets.UiNavigation
import com.unciv.ui.popups.OrdinaryPopupStyle
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.worldscreen.topbar.WorldScreenTopBar
import kotlin.math.max
import kotlin.math.min

/**
 *  The floating card on [WorldScreen] showing the current tutorial task, centered right below the
 *  [top bar][WorldScreenTopBar]. A click collapses it to an icon and back.
 *
 *  It floats over the map in between the other floating widgets, so [update] fits it into the room
 *  they leave - which means it must run once their geometry is final, late in [WorldScreen.update].
 */
internal class TutorialTaskTable(private val worldScreen: WorldScreen) : Table() {
    private companion object {
        /** Padding between the card's background and its content */
        const val contentPad = 10f
        /** Padding around, and size of, the icon shown in place of the card while it is collapsed */
        const val collapsedPad = 5f
        const val collapsedIconSize = 30f
    }

    /** Identifies the content currently built, so an [update] that changes nothing keeps it */
    private var contentHash = 0
    private var battlefieldBounds: Rectangle? = null
    private val ordinary get() = UiNavigation.isIos && worldScreen.gameInfo.campaign == null && worldScreen.gameInfo.endless == null
    private val unit get() = UiTypography.unitsPerPoint(worldScreen.stage)
    private var ordinaryScrollCell: Cell<AutoScrollPane>? = null
    private var ordinaryHeader: Table? = null

    fun fitBattlefield(bounds: Rectangle) {
        battlefieldBounds = bounds
        update()
    }

    init {
        background = BaseScreen.skinStrings.getUiBackground("WorldScreen/TutorialTaskTable",
            tintColor = BaseScreen.skinStrings.skinConfig.baseColor.darken(0.5f))
        // Once, here: onClick appends to the actions of this actor instead of replacing them, so an
        // update registering it again would stack up one more toggle per update - and a tap runs all
        // of them, an even count leaving the card exactly as it was. The rebuilds below therefore
        // use clearChildren(), which drops the cells only - clear() would take the listeners with them.
        onClick { setCollapsed(!UncivGame.Current.isTutorialTaskCollapsed) }
    }

    /** Rebuilds the card if needed, then refits and repositions it - or hides it when there is no
     *  task to show. Safe to call as often as [WorldScreen] updates. */
    fun update() {
        val task = getCurrentTask() ?: return setInvisible()

        if (!UncivGame.Current.isTutorialTaskCollapsed) {
            if (!buildTask(task)) return setInvisible()
        } else buildCollapsedIcon()

        pack()
        // Only claim the mouse wheel when there actually is something to scroll
        (children.firstOrNull() as? ScrollPane)
            ?.apply { setScrollingDisabled(true, prefHeight <= height) }
        val bounds = battlefieldBounds
        if (BattlefieldHud.enabled && bounds != null) {
            y = bounds.y + bounds.height - height
            // Decide from the final card height, so the collapsed flag can sit above the
            // cutout while an expanded tutorial remains clear of it on the same update.
            x = BattlefieldHud.leftForPanel(worldScreen, bounds.x, y, height)
        } else {
            centerX(worldScreen.stage)
            y = worldScreen.topBar.getYForTutorialTask() - height
        }
        isVisible = true
    }

    private fun setInvisible() {
        isVisible = false
        clearChildren()
        contentHash = 0
    }

    private fun getCurrentTask(): Event? {
        val viewingCiv = worldScreen.viewingCiv
        if (!worldScreen.game.settings.showTutorials || viewingCiv.isDefeated()) return null
        if (!worldScreen.game.settings.tutorialTasksCompleted.contains("Create a trade route")) {
            if (viewingCiv.cache.citiesConnectedToCapitalToMediums.any { it.key.civ == viewingCiv })
                worldScreen.game.settings.addCompletedTutorialTask("Create a trade route")
        }
        val stateForConditionals = viewingCiv.state
        return worldScreen.gameInfo.ruleset.events.values.firstOrNull {
            it.presentation == Event.Presentation.Floating &&
                it.isAvailable(stateForConditionals)
        }
    }

    /** @return `false` when the [task] turns out not to be renderable */
    private fun buildTask(task: Event): Boolean {
        if (ordinary) return buildOrdinaryTask(task)
        val taskWidth = getTaskWidth()
        // Default hashCode is OK - we see the same instance or not. The width is part of the hash
        // because a change means a rebuild, not a re-wrap: RenderEvent bakes the width into its
        // labels, whose preferred width is fixed from construction on (see RenderEvent.labelWidth),
        // so laying an existing card out again can make it neither wider nor narrower.
        val hash = task.hashCode() * 31 + taskWidth.toInt()
        if (hash != contentHash) {
            val renderEvent = RenderEvent(task, worldScreen, labelWidth = taskWidth) {
                worldScreen.shouldUpdate = true
            }
            if (!renderEvent.isValid) return false
            clearChildren()
            // A mod can give a task several paragraphs: scroll instead of running off the screen
            val scrollPane = AutoScrollPane(renderEvent, BaseScreen.skin).apply {
                // Same reasoning as AlertPopup for the same RenderEvent, and as BattleTable:
                // the card floats over the map, so a clipped line is the only other hint there is more
                fadeScrollBars = false
                setScrollbarsVisible(true)
                setOverscroll(false, false)
            }
            add(scrollPane).pad(contentPad).fill()
            contentHash = hash
        }
        // Not part of the hash: the room at the bottom changes with the selection, not with the task.
        // Changing a Cell does not invalidate its Table, so the pack() afterwards would reuse the old size
        cells.firstOrNull()?.maxHeight(getTaskHeight(taskWidth))
        invalidate()
        return true
    }

    private fun setCollapsed(collapsed: Boolean) {
        UncivGame.Current.isTutorialTaskCollapsed = collapsed
        update()
        worldScreen.shouldUpdate = true
    }

    private fun buildOrdinaryTask(task: Event): Boolean {
        val taskWidth = getTaskWidth()
        val hash = task.hashCode() * 31 + taskWidth.toInt()
        if (hash != contentHash) {
            // This is an inline teaching card: tapping it toggles the compact task icon.
            // It has no modal close control or visible scroll chrome. Leave glyph overhang room.
            val bodyWidth = (taskWidth - 8f * unit).coerceAtLeast(1f)
            val content = RenderEvent(task, worldScreen, labelWidth = bodyWidth,
                floatingTutorial = true) { worldScreen.shouldUpdate = true }
            // Font glyphs may overhang their measured advance at the beginning of a line.
            content.pad(0f, 4f * unit, 0f, 4f * unit)
            if (!content.isValid) return false
            clearChildren()
            background = OrdinaryPopupStyle(unit).surface(OrdinaryPopupStyle(unit).background, 16f)
            val heading = Table().apply {
                defaults().left()
                val title = task.text.ifBlank {
                    task.civilopediaText.firstOrNull { it.text.isNotBlank() }?.text ?: "Tutorial"
                }
                add(title.toLabel(TextRole.SectionTitle, unit = unit).apply {
                    wrap = true
                    setAlignment(Align.left)
                }).growX().minWidth(0f)
            }
            ordinaryHeader = heading
            add(heading).width(taskWidth).pad(16f * unit, 16f * unit, 8f * unit, 16f * unit).row()
            // Normal built-in tasks fit at their full preferred height. Retain touch scrolling
            // for unusually long mod instructions without drawing a track or scrollbar thumb.
            val scroll = AutoScrollPane(content).apply {
                name = "ui-tutorial-task-content"
                setScrollingDisabled(true, false)
                setOverscroll(false, false)
            }
            ordinaryScrollCell = add(scroll).width(taskWidth).fill()
                .pad(0f, 16f * unit, 16f * unit, 16f * unit)
            contentHash = hash
        }
        ordinaryHeader?.let { heading ->
            heading.setSize(taskWidth, heading.prefHeight)
            heading.validate()
            ordinaryScrollCell?.maxHeight((getTaskHeight(taskWidth) - heading.prefHeight - 40f * unit)
                .coerceAtLeast(44f * unit))
        }
        invalidate()
        return true
    }

    private fun buildCollapsedIcon() {
        clearChildren()
        if (ordinary) {
            background = null
            add(Table().apply {
                add(ImageGetter.getImage("OtherIcons/HiddenTutorialTask")).size(20f * unit)
            }).size(44f * unit)
            contentHash = 0
            return
        }
        if (BattlefieldHud.enabled) {
            val icon = BattlefieldHud.points(20f)
            add(ImageGetter.getImage("OtherIcons/HiddenTutorialTask"))
                .size(icon).pad((com.unciv.ui.components.widgets.UiNavigation.minimumTouchSize() - icon) / 2f)
            contentHash = 0
            return
        }
        add(ImageGetter.getImage("OtherIcons/HiddenTutorialTask")
            .apply { setSize(collapsedIconSize, collapsedIconSize) }).pad(collapsedPad)
        contentHash = 0
    }

    /** Width the card's texts wrap to, so the card does not slide under the button groups flanking
     *  it. Since the card is centered on the stage, the wider of the two groups decides.
     *  We stop short of the point where that room runs out: below a quarter of the stage - half of
     *  the width the card always had - the free strip is a few characters wide, and a task wrapped
     *  into it becomes a column of single words, unreadable and several screens tall. Under that
     *  threshold the card keeps its full width and lets the buttons cover a corner as they always
     *  did: an overlap we can read beats a fit we cannot. */
    private fun getTaskWidth(): Float {
        battlefieldBounds?.takeIf { BattlefieldHud.enabled }?.let {
            if (ordinary) return (it.width - 32f * unit).coerceAtLeast(120f * unit)
            return (it.width - contentPad * 2).coerceAtLeast(collapsedIconSize)
        }
        val stageWidth = worldScreen.stage.width
        val fullWidth = stageWidth * 0.5f  // what the card always was, and RenderEvent's own default
        val techPolicyAndDiplomacy = worldScreen.techPolicyAndDiplomacy
        val statusButtons = worldScreen.statusButtons
        val blockedPerSide = max(
            if (techPolicyAndDiplomacy.isVisible) techPolicyAndDiplomacy.x + techPolicyAndDiplomacy.width else 0f,
            if (statusButtons.isVisible) stageWidth - statusButtons.x else 0f
        )
        // Content width: minus the card's own padding on both sides, and as much again on both
        // sides so the card keeps a gap to the buttons instead of touching them
        val freeWidth = stageWidth - 2 * blockedPerSide - contentPad * 4
        return if (freeWidth < fullWidth / 2) fullWidth else min(fullWidth, freeWidth)
    }

    /** Height the centered card may use without covering the widgets sitting at the bottom of the
     *  screen. Only those actually overlapping the card's own horizontal span count - the unit table
     *  can be wide enough to reach under a centered card, or narrow enough to stay clear of it.
     *  All of them are packed and positioned earlier in [WorldScreen.update], so their geometry is
     *  final here. */
    private fun getTaskHeight(taskWidth: Float): Float {
        battlefieldBounds?.takeIf { BattlefieldHud.enabled }?.let {
            if (ordinary) return it.height
            return (it.height - contentPad * 2).coerceAtLeast(collapsedIconSize)
        }
        val cardLeft = (worldScreen.stage.width - taskWidth) / 2 - contentPad
        val cardRight = worldScreen.stage.width - cardLeft
        // battleTable is always centered on the stage, so it always overlaps the card's span
        val bottomWidgets = with(worldScreen) {
            sequenceOf(bottomUnitTable, unitActionsTable, bottomTileInfoTable, minimapWrapper, battleTable)
        }
        val blockedFromBottom = bottomWidgets
            .filter { it.isVisible && it.x < cardRight && it.x + it.width > cardLeft }
            .maxOfOrNull { it.y + it.height } ?: 0f
        // Content height: the room left, less the card's own padding top and bottom and a gap to
        // whatever is below. The floor has to stay above zero, because Gdx reads a maxHeight of 0
        // as "no maximum" (Cell.maxHeight, and the `maxHeight > 0` guards in Table.computeSize and
        // Table.layout) - clamping to 0 would switch the cap off in exactly the cramped case it is
        // there for. So when the room does run out the card falls back to the size of the icon it
        // collapses to, which is as small as it has ever been on screen, and scrolls for the rest.
        return (worldScreen.topBar.getYForTutorialTask() - blockedFromBottom - contentPad * 3)
            .coerceAtLeast(collapsedIconSize)
    }
}
