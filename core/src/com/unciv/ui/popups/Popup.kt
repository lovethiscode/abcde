package com.unciv.ui.popups

import com.unciv.ui.components.fonts.UiTypography
import com.unciv.ui.components.fonts.TextRole
import com.unciv.ui.components.widgets.UiNavigation
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.math.Rectangle
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Group
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.Button
import com.badlogic.gdx.scenes.scene2d.ui.Cell
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.badlogic.gdx.scenes.scene2d.ui.TextButton.TextButtonStyle
import com.badlogic.gdx.scenes.scene2d.ui.TextField
import com.badlogic.gdx.scenes.scene2d.ui.WidgetGroup
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener
import com.badlogic.gdx.utils.Align
import com.unciv.UncivGame
import com.unciv.ui.components.extensions.setFontSize
import com.badlogic.gdx.scenes.scene2d.ui.Value
import com.unciv.ui.images.ImageGetter
import com.unciv.Constants
import com.unciv.models.skins.ExpeditionBlueTheme
import com.unciv.GUI
import com.unciv.logic.event.EventBus
import com.unciv.ui.components.extensions.addSeparator
import com.unciv.ui.components.extensions.center
import com.unciv.ui.components.extensions.darken
import com.unciv.ui.components.extensions.getCloseButton
import com.unciv.ui.components.extensions.right
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.extensions.top
import com.unciv.ui.components.input.KeyCharAndCode
import com.unciv.ui.components.input.KeyboardBinding
import com.unciv.ui.components.input.KeyboardBindings
import com.unciv.ui.components.input.keyShortcuts
import com.unciv.ui.components.input.onActivation
import com.unciv.ui.components.widgets.AutoScrollPane
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.basescreen.UncivStage

/**
 * Base class for all Popups, i.e. Tables that get rendered in the middle of a screen and on top of everything else
 *
 * @property stageToShowOn The stage that will be used for [open], measurements or finding other instances
 * @param scrollable Controls how content can scroll if too large - see [Scrollability]
 * @param maxSizePercentage Causes [topTable] to limit its width/height - useful if `scrollable` is on. Will be multiplied by stageToShowOn sizes.
 *        Note this can be overridden if a larger minWidth is reported by [innerTable].
 */
@Suppress("MemberVisibilityCanBePrivate")
open class Popup(
    val stageToShowOn: Stage,
    scrollable: Scrollability = Scrollability.WithoutButtons,
    private val maxSizePercentage: Float = 0.9f,
    ordinaryShell: Boolean = false
): Table(BaseScreen.skin) {

    constructor(
        screen: BaseScreen,
        scrollable: Scrollability = Scrollability.WithoutButtons,
        maxSizePercentage: Float = 0.9f,
        ordinaryShell: Boolean = false
    ) : this(screen.stage, scrollable, maxSizePercentage, ordinaryShell)

    /** Controls how content may scroll.
     *
     * With scrolling enabled, the ScrollPane can be accessed via [getScrollPane].
     * @property None No scrolling
     * @property All Entire content wrapped in an [AutoScrollPane] so it can scroll if larger than maximum dimensions
     * @property WithoutButtons Content separated into scrollable upper part and static lower part containing the buttons
     */
    enum class Scrollability { None, All, WithoutButtons, DevConsole }

    /** Ordinary game presentation is isolated from campaign/endless and specialised tools. */
    protected val ordinaryPresentation = scrollable == Scrollability.WithoutButtons &&
        UiNavigation.isIos && (UncivGame.getGameInfoOrNull()?.let {
            it.campaign == null && it.endless == null
        } ?: ordinaryShell) && !javaClass.name.let {
            it.contains(".options.") || it.contains(".chat.") || it.contains(".devconsole.")
                || it.contains(".newgamescreen.") || it.endsWith("AuthPopup")
        }
    protected val popupUnit get() = UiTypography.unitsPerPoint(stageToShowOn)
    protected var ordinaryViewport = ordinaryPresentation
    protected var ordinaryPreferredWidth = 560f
    protected val popupContentWidth get() = (maxPopupWidth - 40f * popupUnit).coerceAtLeast(1f)
    protected val maxPopupWidth get() = if (ordinaryPresentation)
        minOf(lastKnownVisibleArea().width - 24f * popupUnit,
            minOf(ordinaryPreferredWidth, if (stageToShowOn.height / popupUnit <= 520f) 720f else ordinaryPreferredWidth) * popupUnit).coerceAtLeast(1f)
        else stageToShowOn.width * maxSizePercentage
    private fun contentWidthValue() = object : Value() {
        override fun get(context: Actor?) = popupContentWidth
    }
    private var closed = false
    private var cancelButton: TextButton? = null
    private var lastLayoutWidth = -1f
    private var lastLayoutHeight = -1f
    private var revealFocusedInput = false
    private var typographyUnit = popupUnit
    private val ordinaryButtons = HashMap<TextButton, Pair<Boolean, Boolean>>()

    protected fun styleOrdinaryButton(button: TextButton, primary: Boolean = false, destructive: Boolean = false) {
        if (!ordinaryPresentation || !ExpeditionBlueTheme.enabled) return
        ordinaryButtons[button] = primary to destructive
        button.style = OrdinaryPopupStyle(popupUnit).button(primary, destructive)
    }

    protected fun addChoiceCard(button: com.badlogic.gdx.scenes.scene2d.ui.TextButton, vararg consequences: String):
        com.badlogic.gdx.scenes.scene2d.ui.Cell<com.badlogic.gdx.scenes.scene2d.ui.TextButton> {
        styleOrdinaryButton(button)
        button.label.setFontSize(UiTypography.size(TextRole.Control, popupUnit))
        button.label.wrap = true
        button.label.setAlignment(com.badlogic.gdx.utils.Align.left)
        button.clearChildren()
        val textWidth = object : com.badlogic.gdx.scenes.scene2d.ui.Value() {
            override fun get(context: com.badlogic.gdx.scenes.scene2d.Actor?) = (popupContentWidth - 60f * popupUnit).coerceAtLeast(1f)
        }
        val details = Table().apply {
            add(button.label).width(textWidth).left().row()
            for (text in consequences.filter { it.isNotBlank() }) add(text.toLabel(fontSize = UiTypography.size(TextRole.Supporting, popupUnit), fontColor = Color.LIGHT_GRAY)
                .apply { wrap = true }).width(textWidth).left().padTop(6f * popupUnit).row()
        }
        val indicator = object : com.badlogic.gdx.scenes.scene2d.ui.Image() {
            private val circle = ImageGetter.getCircleDrawable()
            override fun draw(batch: com.badlogic.gdx.graphics.g2d.Batch, parentAlpha: Float) {
                val edge = if (button.isChecked) ExpeditionBlueTheme.gold else ExpeditionBlueTheme.muted
                batch.color = edge
                circle.draw(batch, x, y, width, height)
                val inset = 1.5f * popupUnit
                batch.color = ExpeditionBlueTheme.background
                circle.draw(batch, x + inset, y + inset, width - inset * 2, height - inset * 2)
                if (button.isChecked) {
                    batch.color = edge
                    circle.draw(batch, x + width * .27f, y + height * .27f, width * .46f, height * .46f)
                }
                batch.color = Color.WHITE
            }
        }
        button.pad(14f * popupUnit)
        button.add(indicator).size(20f * popupUnit).top().padRight(12f * popupUnit)
        button.add(details).minWidth(0f).growX().left()
        // Supply a real width before measuring wrapped consequences.
        details.setWidth((popupContentWidth - 60f * popupUnit).coerceAtLeast(1f))
        button.setWidth(popupContentWidth)
        val cell = add(button).width(popupContentWidth).minHeight(44f * popupUnit).padBottom(8f * popupUnit)
        row()
        return cell
    }

    private fun updatePopupScale(group: Group, ratio: Float) {
        // Scene2D labels own their scale; never mutate the shared BitmapFont metrics.
        for (actor in group.children) when (actor) {
            is Label -> actor.setFontScale(actor.fontScaleX * ratio, actor.fontScaleY * ratio)
            is TextField -> actor.style = TextField.TextFieldStyle(actor.style).apply {
                font = com.unciv.ui.components.fonts.Fonts.getUiFont(UiTypography.size(TextRole.Control, popupUnit))
            }
            is com.badlogic.gdx.scenes.scene2d.ui.SelectBox<*> -> {
                val controlFont = UiTypography.controlFont(popupUnit)
                actor.style = com.badlogic.gdx.scenes.scene2d.ui.SelectBox.SelectBoxStyle(actor.style).apply {
                    font = controlFont
                    listStyle = com.badlogic.gdx.scenes.scene2d.ui.List.ListStyle(listStyle).apply { font = controlFont }
                }
            }
            is Group -> updatePopupScale(actor, ratio)
        }
        if (group is Table) for (cell in group.cells) {
            // Fixed geometry is in the previous stage's units. Computed Values already
            // follow their owner (including separators and responsive text columns).
            val minW = if (cell.minWidthValue is Value.Fixed) cell.minWidth * ratio else null
            val prefW = if (cell.prefWidthValue is Value.Fixed) cell.prefWidth * ratio else null
            val maxW = if (cell.maxWidthValue is Value.Fixed) cell.maxWidth * ratio else null
            val minH = if (cell.minHeightValue is Value.Fixed) cell.minHeight * ratio else null
            val prefH = if (cell.prefHeightValue is Value.Fixed) cell.prefHeight * ratio else null
            val maxH = if (cell.maxHeightValue is Value.Fixed) cell.maxHeight * ratio else null
            minW?.let { cell.minWidth(it) }; prefW?.let { cell.prefWidth(it) }; maxW?.let { cell.maxWidth(it) }
            minH?.let { cell.minHeight(it) }; prefH?.let { cell.prefHeight(it) }; maxH?.let { cell.maxHeight(it) }
            cell.pad(cell.padTop * ratio, cell.padLeft * ratio, cell.padBottom * ratio, cell.padRight * ratio)
            cell.space(cell.spaceTop * ratio, cell.spaceLeft * ratio, cell.spaceBottom * ratio, cell.spaceRight * ratio)
        }
        if (group is Table) group.pad(group.padTop * ratio, group.padLeft * ratio, group.padBottom * ratio, group.padRight * ratio)
    }

    /** Reflow the same actors, retaining text input, focus, listeners and game callbacks. */
    private fun layoutOrdinaryPopup() {
        if (!ordinaryPresentation) return
        val unit = popupUnit
        if (unit != typographyUnit) {
            updatePopupScale(innerTable, unit / typographyUnit)
            typographyUnit = unit
            headerCloseButton?.let { button ->
                button.style = UiNavigation.buttonStyle(unit)
                (button.parent as? Table)?.getCell(button)?.size(44f * unit)
                button.cells.firstOrNull()?.size(20f * unit)
            }
            titleTable.pad(16f * unit, 20f * unit, 16f * unit, 20f * unit)
        }
        if (ExpeditionBlueTheme.enabled) {
            val ui = OrdinaryPopupStyle(unit)
            innerTable.background = ui.surface(ui.background, 16f)
            titleTable.background = if (titleTable.hasChildren()) ui.divider(atTop = false) else null
            bottomTable.background = if (bottomTable.hasChildren()) ui.divider(atTop = true, fill = ui.deep) else null
            ordinaryButtons.forEach { (button, kind) -> button.style = ui.button(kind.first, kind.second) }
        }
        topTable.pad(20f * unit, 20f * unit, 20f * unit, 20f * unit)
        topTable.defaults().pad(4f * unit, 0f, 4f * unit, 0f)
        getScrollPane()?.setScrollingDisabled(true, false)
        if (bottomTable.hasChildren()) bottomTable.pad(14f * unit, 20f * unit, 18f * unit, 20f * unit)
        else bottomTable.pad(0f)
        bottomTable.right()
        val buttons = bottomTable.cells.mapNotNull { it.actor as? TextButton }
        if (buttons.size == bottomTable.cells.size && buttons.isNotEmpty()) {
            // Equal action widths match the prototype; a single action keeps a readable minimum.
            // On short landscape windows wrapping keeps the actions above the bottom edge.
            val naturalWidths = buttons.associateWith {
                it.label.wrap = false
                val width = it.label.prefWidth
                it.label.wrap = true
                width
            }
            val stacked = stageToShowOn.height / unit >= 430f &&
                buttons.sumOf { naturalWidths.getValue(it).toDouble() + 32f * unit } +
                (buttons.size - 1) * 8f * unit > popupContentWidth
            bottomTable.clearChildren()
            for (button in buttons) {
                button.label.wrap = true
                button.label.setAlignment(Align.center)
                button.labelCell.minWidth(0f).growX()
                button.pad(10f * unit, 12f * unit, 10f * unit, 12f * unit)
                val width = when {
                    stacked -> popupContentWidth - 8f * unit
                    buttons.size > 1 -> (popupContentWidth - 8f * unit * buttons.size) / buttons.size
                    else -> maxOf(110f * unit, naturalWidths.getValue(button) + 24f * unit).coerceAtMost(popupContentWidth - 8f * unit)
                }.coerceAtLeast(44f * unit)
                button.label.setSize((width - 24f * unit).coerceAtLeast(1f), 0f)
                button.label.invalidate()
                bottomTable.add(button).width(width)
                    .expand(0, 0).fill(false, true)
                    .minHeight(maxOf(44f * unit, button.label.prefHeight + 20f * unit)).pad(4f * unit)
                if (stacked) bottomTable.row()
            }
        }
        for (cell in topTable.cells) {
            if (topTable.columns > 1 && cell.colspan != topTable.columns) continue
            // Full-width body cells already sit inside the 20pt content inset.
            // Additional horizontal cell padding would create a second scroll axis.
            cell.padLeft(0f).padRight(0f)
            when (val actor = cell.actor) {
                is Label -> { actor.wrap = true; cell.minWidth(0f).width(contentWidthValue()) }
                is TextButton -> { actor.label.wrap = true; cell.minWidth(0f).width(contentWidthValue()) }
                is TextField -> cell.minWidth(0f).width(contentWidthValue())
                is com.badlogic.gdx.scenes.scene2d.ui.SelectBox<*>, is Table -> cell.minWidth(0f).width(contentWidthValue())
            }
        }
        innerTable.getCell(titleTable)?.minWidth(0f)?.width(maxPopupWidth)
        innerTable.getCell(bottomTable)?.minWidth(0f)?.width(maxPopupWidth)
        topTableCell.minWidth(0f).width(maxPopupWidth)
        super.getCell(innerTable)?.minWidth(0f)?.width(maxPopupWidth)
        recalculateInnerTableMaxHeight()
        innerTable.invalidateHierarchy()
        lastLayoutWidth = stageToShowOn.width
        lastLayoutHeight = stageToShowOn.height
        revealFocusedInput = true
    }

    /** Re-measure fixed actions after a decision changes their text. */
    protected fun refreshOrdinaryPopupLayout() {
        if (ordinaryPresentation) onVisibleAreaChanged(lastKnownVisibleArea())
    }

    override fun act(delta: Float) {
        super.act(delta)
        if (ordinaryPresentation) {
            cancelButton?.let { headerCloseButton?.isDisabled = it.isDisabled }
            if (lastLayoutWidth != stageToShowOn.width || lastLayoutHeight != stageToShowOn.height || typographyUnit != popupUnit)
                layoutOrdinaryPopup()
            if (revealFocusedInput && isVisible) {
                revealFocusedInput = false
                val input = keyboardFocus as? TextField
                val pane = getScrollPane()
                if (input != null && input.isDescendantOf(topTable) && pane != null) {
                    // Reflow before reading widget coordinates: the keyboard can leave
                    // only enough body space for the field, with its label above view.
                    validate()
                    innerTable.validate()
                    pane.validate()
                    topTable.validate()
                    val position = input.localToAscendantCoordinates(topTable, com.badlogic.gdx.math.Vector2())
                    // Centre the field after rotation instead of retaining an old
                    // edge-aligned offset that can clip its outline under the header.
                    pane.scrollTo(0f, position.y, 0f, input.height, false, true)
                    pane.scrollX = 0f
                    pane.updateVisualScroll()
                }
            }
        }
    }
    protected val maxPopupHeight get() = (if (ordinaryPresentation) lastKnownVisibleArea().height else stageToShowOn.height) * maxSizePercentage

    /** This exists to differentiate the actual popup (this table)
     *  from the 'screen blocking' part of the popup (which covers the entire screen).
     *
     *  Note you seldom need to interact directly with it, Popup has many Table method proxies
     *  that pass through, like [add], [row], [defaults], [addSeparator] or [clear].
     *
     *  For [Scrollability.WithoutButtons], this wraps [topTable] and [bottomTable], otherwise both are aliases.
     */
    /* Hierarchy:
        Scrollability.None:
        * Stage
            * this@Popup (fills parent, catches click-behind)
                * innerTable (entire Popup content, smaller, limited by maxSizePercentage)
                    * topTable and bottomTable _reference_ innerTable
        Scrollability.All:
        * Stage
            * this@Popup (fills parent, catches click-behind)
                * ScrollPane (anonymous)
                    * innerTable (entire Popup content, smaller, limited by maxSizePercentage)
                        * topTable and bottomTable _reference_ innerTable
        Scrollability.WithoutButtons:
        * Stage
            * this@Popup (fills parent, catches click-behind)
                * innerTable (entire Popup content, smaller, limited by maxSizePercentage)
                    * ScrollPane (anonymous)
                        * topTable
                    * bottomTable
    */
    private var floatingCloseHeader = false
    private var contentTopPadding = 0f
    protected val innerTable = object : Table(BaseScreen.skin) {
        override fun layout() {
            super.layout()
            if (floatingCloseHeader) {
                val unit = UiNavigation.minimumTouchSize() / 44f
                titleTable.setBounds(8f * unit, height - 52f * unit,
                    (width - 16f * unit).coerceAtLeast(0f), 44f * unit)
                titleTable.validate()
            }
        }
    }

    /** This contains most of the Popup content.
     *  For [Scrollability.WithoutButtons], that excludes the closing buttons which go in [bottomTable].
     *  In other modes, this is an alias for [innerTable]. */
    private val topTable: Table
    private val topTableCell: Cell<WidgetGroup>
    private val titleTable = Table(BaseScreen.skin)
    private var headerCloseButton: TextButton? = null
    private var headerDismissAction: (() -> Unit)? = null

    /** This contains the bottom row buttons and does not participate in scrolling
     *  (for [Scrollability.WithoutButtons], otherwise alias for [innerTable]). */
    protected val bottomTable: Table

    /** Callbacks that will be called whenever this Popup is shown */
    val showListeners = mutableListOf<() -> Unit>()
    /** Callbacks that will be called whenever this Popup is closed, no matter how (e.g. no distinction OK/Cancel) */
    val closeListeners = mutableListOf<() -> Unit>()

    /** [EventBus] is used to receive [UncivStage.VisibleAreaChanged] */
    protected val events = EventBus.EventReceiver()

    /** Enables/disables closing by clicking/tapping outside [innerTable].
     *
     *  Automatically set when [addCloseButton] is called but may be changed back or enabled without such a button.
     */
    protected var clickBehindToClose = false

    /** Unlike [closeListeners] this is only fired on "click-behind" closing */
    protected var onCloseCallback: (() -> Unit)? = null

    init {
        // Set actor name for debugging
        name = javaClass.simpleName

        if (scrollable != Scrollability.DevConsole)
            background = BaseScreen.skinStrings.getUiBackground(
                "General/Popup/Background",
                tintColor = Color.GRAY.cpy().apply { a = 0.5f })

        //todo topTable and bottomTable _could_ be separately skinnable - but would need care so rounded edges work
        innerTable.background = BaseScreen.skinStrings.getUiBackground(
            "General/Popup/InnerTable",
            tintColor = BaseScreen.skinStrings.skinConfig.baseColor.darken(0.5f)
        )
        innerTable.touchable = Touchable.enabled
        fun wrapInScrollPane(table: Table) = AutoScrollPane(table, BaseScreen.skin)
            .apply { setOverscroll(false, false) }
        when (scrollable) {
            Scrollability.None -> {
                topTable = innerTable
                bottomTable = innerTable
                topTableCell = super.add(innerTable)
            }
            Scrollability.All, Scrollability.DevConsole -> {
                topTable = innerTable
                bottomTable = innerTable
                topTableCell = super.add(wrapInScrollPane(innerTable))
            }
            Scrollability.WithoutButtons -> {
                topTable = Table(BaseScreen.skin)
                topTable.pad(20f).padBottom(0f)
                topTable.defaults().fillX().pad(5f)
                bottomTable = Table(BaseScreen.skin)
                innerTable.add(titleTable).growX().row()
                topTableCell = innerTable.add(wrapInScrollPane(topTable))
                innerTable.defaults().fillX()
                innerTable.row()
                innerTable.add(bottomTable)
                super.add(innerTable)
            }
        }

        bottomTable.pad(20f)
        bottomTable.defaults().pad(5f)
        topTableCell.maxSize(maxPopupWidth, maxPopupHeight)

        isVisible = false
        touchable = if (scrollable == Scrollability.DevConsole) Touchable.childrenOnly else Touchable.enabled
        // clicking behind gets special treatment
        super.addListener(getBehindClickListener())
        super.setFillParent(true)
    }

    private fun recalculateInnerTableMaxHeight() {
        val buttonsHeight = if (topTable === bottomTable) 0f else bottomTable.prefHeight + titleTable.prefHeight
        topTableCell.maxHeight((maxPopupHeight - buttonsHeight).coerceAtLeast(0f))
        innerTable.invalidate()
    }

    private fun recalculateInnerTableMaxWidth() {
        if (ordinaryPresentation) { topTableCell.maxWidth(maxPopupWidth); return }
        val minWidth = innerTable.minWidth.coerceAtMost(stageToShowOn.width)
        topTableCell.maxWidth(maxOf(maxPopupWidth, minWidth))
        innerTable.invalidate()
    }


    /**
     * Displays the Popup on the screen. If another popup is already open, this one will display after the other has
     * closed. Use [force] = true if you want to open this popup above the other one anyway.
     */
    fun open(force: Boolean = false) {
        closed = false
        layoutOrdinaryPopup()
        stageToShowOn.addActor(this)
        recalculateInnerTableMaxHeight()
        innerTable.pack()
        if (titleTable.hasChildren()) recalculateInnerTableMaxHeight()
        recalculateInnerTableMaxWidth()
        pack()
        center(stageToShowOn)
        events.receive(UncivStage.VisibleAreaChanged::class) {
            onVisibleAreaChanged(it.visibleArea)
        }
        centerContentIntoVisibleArea(lastKnownVisibleArea())
        if (force || !stageToShowOn.hasOpenPopups()) {
            show()
        }
    }

    protected open fun onVisibleAreaChanged(visibleArea: Rectangle) {
        layoutOrdinaryPopup()
        if (com.badlogic.gdx.Gdx.app.type == com.badlogic.gdx.Application.ApplicationType.iOS) {
            val buttonsHeight = if (topTable === bottomTable) 0f else bottomTable.prefHeight + titleTable.prefHeight
            topTableCell.maxSize(maxPopupWidth, (visibleArea.height * maxSizePercentage - buttonsHeight).coerceAtLeast(0f))
            innerTable.invalidateHierarchy()
        }
        if (ordinaryPresentation) {
            recalculateInnerTableMaxHeight()
            centerContentIntoVisibleArea(lastKnownVisibleArea())
        } else fitOrCenterContentIntoVisibleArea(visibleArea)
    }

    override fun drawBackground(batch: com.badlogic.gdx.graphics.g2d.Batch, parentAlpha: Float, x: Float, y: Float) {
        val bounds = (stageToShowOn.viewport as? com.unciv.ui.screens.basescreen.SafeAreaViewport)?.drawingBounds
        if (bounds == null || (bounds.x == 0f && bounds.y == 0f && bounds.width == stageToShowOn.width && bounds.height == stageToShowOn.height)) {
            super.drawBackground(batch, parentAlpha, x, y)
            return
        }
        batch.setColor(color.r, color.g, color.b, color.a * parentAlpha)
        background?.draw(batch, x + bounds.x, y + bounds.y, bounds.width, bounds.height)
    }

    override fun hit(x: Float, y: Float, touchable: Boolean): Actor? {
        super.hit(x, y, touchable)?.let { return it }
        if (!isVisible || this.touchable != Touchable.enabled) return null
        val bounds = (stageToShowOn.viewport as? com.unciv.ui.screens.basescreen.SafeAreaViewport)?.drawingBounds
        return if (bounds?.contains(x + this.x, y + this.y) == true) this else null
    }

    private fun Rectangle.isMostOfScreen() =
        width / stageToShowOn.width > 0.9f && height / stageToShowOn.height > 0.9f
    private fun lastKnownVisibleArea(): Rectangle {
        val visible = (stageToShowOn as UncivStage).lastKnownVisibleArea
        if (!ordinaryViewport) return visible
        val safe = (stageToShowOn.viewport as? com.unciv.ui.screens.basescreen.SafeAreaViewport)
            ?.safeAreaBoundsInWorld ?: return visible
        val bottom = maxOf(visible.y, safe.y)
        // iOS keyboard notifications constrain height only. A prior screen may have
        // broadcast a different stage width before the world stage became active.
        return Rectangle(safe.x, bottom, safe.width,
            (minOf(visible.top, safe.top) - bottom).coerceAtLeast(1f))
    }

    /** Centers [innerTable] when only status bar and/or system UI limit the [visibleArea],
     *  otherwise shifts it from center just enough to fit in [visibleArea] plus 5f padding.
     */
    protected fun fitOrCenterContentIntoVisibleArea(visibleArea: Rectangle = lastKnownVisibleArea()) =
        if (visibleArea.isMostOfScreen()) centerContentIntoVisibleArea(visibleArea)
        else fitContentIntoVisibleArea(visibleArea)

    private fun fitContentIntoVisibleArea(visibleArea: Rectangle) {
        // The content, when centered, has this margin around, padded by 5f
        val xMargin = (stageToShowOn.width - innerTable.prefWidth) / 2f - 5f
        val yMargin = (stageToShowOn.height - innerTable.prefHeight) / 2f - 5f
        val visibleRight = stageToShowOn.width - visibleArea.right
        val visibleTop = stageToShowOn.height - visibleArea.top
        // Now push innerTable "out of the way" only if overlapping,
        // e.g. do nothing to the bottom if visibleArea.y is smaller than the calculated margin
        // To "push" by a distance X we'll need to pad by 2*X since innerTable is centered
        padLeft(((visibleArea.x - xMargin) * 2f).coerceIn(0f, visibleArea.width))
        padBottom(((visibleArea.y - yMargin) * 2f).coerceIn(0f, visibleArea.height))
        padRight(((visibleRight - xMargin) * 2f).coerceIn(0f, visibleArea.width))
        padTop(((visibleTop - yMargin) * 2f).coerceIn(0f, visibleArea.height))
        invalidate()
    }

    private fun centerContentIntoVisibleArea(visibleArea: Rectangle) {
        padLeft(visibleArea.x)
        padBottom(visibleArea.y)
        padRight(stageToShowOn.width - visibleArea.right)
        padTop(stageToShowOn.height - visibleArea.top)
        invalidate()
    }

    /** Subroutine for [open] handles only visibility */
    private fun show() {
        this.isVisible = true
        for (listener in showListeners) listener()
    }

    /**
     * Close this popup and - if any other popups are pending - display the next one.
     */
    open fun close() {
        if (ordinaryPresentation && closed) return
        closed = true
        events.stopReceiving()
        for (listener in closeListeners) listener()
        remove()
        val nextPopup = stageToShowOn.actors.firstOrNull { it is Popup }
        if (nextPopup != null) (nextPopup as Popup).show()
    }

    /** Allow closing a popup by clicking 'outside', Android-style, but only if a Close button exists */
    private fun getBehindClickListener() = object : ClickListener() {
        override fun clicked(event: InputEvent?, x: Float, y: Float) {
            if (!clickBehindToClose || GUI.getSettings().forbidPopupClickBehindToClose) return
            // Since Gdx doesn't limit events to the actually `hit` actors...
            if (event?.target != this@Popup) return
            if (ordinaryPresentation && (closed || cancelButton?.isDisabled == true)) return
            close()
            onCloseCallback?.invoke()
        }
    }

    /* All additions to the popup are to the inner table - we shouldn't care that there's an inner table at all.
       Note the Kdoc mentions innerTable when under Scrollability.WithoutButtons it's actually topTable,
       but metioning that distinction seems overkill. innerTable has the clearer Kdoc for "where the Actors go".
    */
    /** Popup proxy redirects [add][com.badlogic.gdx.scenes.scene2d.ui.Table.add] to [topTable] */
    final override fun <T : Actor?> add(actor: T): Cell<T> = topTable.add(actor)
    /** Popup proxy redirects [add][com.badlogic.gdx.scenes.scene2d.ui.Table.add] to [topTable] */
    final override fun add(): Cell<Actor?> = topTable.add()
    /** Popup proxy redirects [row][com.badlogic.gdx.scenes.scene2d.ui.Table.row] to [topTable] */
    override fun row(): Cell<Actor> = topTable.row()
    /** Popup proxy redirects [defaults][com.badlogic.gdx.scenes.scene2d.ui.Table.defaults] to [topTable] */
    override fun defaults(): Cell<Actor> = topTable.defaults()
    /** Popup proxy redirects [addSeparator][com.unciv.ui.components.extensions.addSeparator] to [topTable] */
    fun addSeparator(color: Color = Color.WHITE, colSpan: Int = 0, height: Float = 2f) =
        topTable.addSeparator(if (ordinaryPresentation && color == Color.WHITE) Color.valueOf("385965") else color,
            colSpan, if (ordinaryPresentation) popupUnit else height)
    /** Proxy redirects [clear][com.badlogic.gdx.scenes.scene2d.ui.Table.clear] to clear all content:
     *  [innerTable] or if [Scrollability.WithoutButtons] was used [topTable] and [bottomTable] */
    override fun clear() {
        if (floatingCloseHeader) innerTable.padTop(contentTopPadding)
        floatingCloseHeader = false
        topTable.clear()
        bottomTable.clear()
        titleTable.clear()
        titleTable.pad(0f)
        headerCloseButton = null
        headerDismissAction = null
        cancelButton = null
        ordinaryButtons.clear()
        clickBehindToClose = false
        onCloseCallback = null
    }


    /**
     * Adds a [caption][text] label: A label with word wrap enabled over half the stage width.
     * Will be larger than normal text if the [size] parameter is set to > [Constants.defaultFontSize].
     * @param text The caption text.
     * @param size The font size for the label.
     */
    fun addGoodSizedLabel(text: String, size: Int = UiTypography.size(TextRole.Body, if (ordinaryPresentation) popupUnit else UiTypography.unitsPerPoint()), hideIcons:Boolean = false, color: Color = Color.WHITE): Cell<Label> {
        val label = text.toLabel(fontSize = size, hideIcons = hideIcons, fontColor = color)
        label.wrap = true
        label.setAlignment(if (ordinaryPresentation) Align.left else Align.center)
        return if (ordinaryPresentation) add(label).minWidth(0f).width(contentWidthValue())
            else add(label).width(stageToShowOn.width / 2)
    }

    /**
     * Adds a [TextButton].
     * @param text The button's caption.
     * @param key Associate a key with this button's action.
     * @param action A lambda to be executed when the button is clicked.
     * @return The new [Cell]
     */
    fun addButton(
        text: String,
        key: KeyCharAndCode? = null,
        style: TextButtonStyle? = null,
        action: () -> Unit
    ): Cell<TextButton> {
        val button = text.toTextButton(style)
        if (style == null) styleOrdinaryButton(button)
        if (ordinaryPresentation) button.label.setFontSize(UiTypography.size(TextRole.Control, popupUnit))
        button.onActivation { if (!ordinaryPresentation || !closed) action() }
        button.keyShortcuts.add(key)
        return bottomTable.add(button).apply { if (UiNavigation.isIos) minSize(UiNavigation.minimumTouchSize()) }
    }
    fun addButton(text: String, key: Char, style: TextButtonStyle? = null, action: () -> Unit)
        = addButton(text, KeyCharAndCode(key), style, action)
    @Suppress("unused")  // Keep the offer to pass an Input.keys value
    fun addButton(text: String, key: Int, style: TextButtonStyle? = null, action: () -> Unit)
        = addButton(text, KeyCharAndCode(key), style, action)
    fun addButton(text: String, binding: KeyboardBinding, style: TextButtonStyle? = null, action: () -> Unit): Cell<TextButton> {
        val button = text.toTextButton(style)
        if (style == null) styleOrdinaryButton(button)
        if (ordinaryPresentation) button.label.setFontSize(UiTypography.size(TextRole.Control, popupUnit))
        button.onActivation(binding = binding) { if (!ordinaryPresentation || !closed) action() }
        return bottomTable.add(button).apply { if (UiNavigation.isIos) minSize(UiNavigation.minimumTouchSize()) }
    }

    /**
     * Adds a [TextButton] that closes the popup, with [BACK][KeyCharAndCode.BACK] already mapped.
     * @param text The button's caption, defaults to "Close".
     * @param additionalKey An additional key that should act like a click.
     * @param action A lambda to be executed after closing the popup when the button is clicked.
     * @return The new [Cell]
     */
    fun addCloseButton(
        text: String = Constants.close,
        additionalKey: KeyCharAndCode? = null,
        style: TextButtonStyle? = null,
        action: (()->Unit)? = null
    ): Cell<TextButton> {
        clickBehindToClose = true
        onCloseCallback = action
        fun dismiss() {
            if (ordinaryPresentation && closed) return
            close()
            action?.invoke()
        }
        if (UiNavigation.isIos && text == Constants.close) {
            val button = headerDismissButton { dismiss() }.apply {
                keyShortcuts.add(additionalKey)
                keyShortcuts.add(KeyCharAndCode.BACK)
            }
            return addDismissControl(button)
        }
        val closeStyle = if (UiNavigation.isIos && text == Constants.cancel) UiNavigation.buttonStyle() else style
        val cell = addButton(text, additionalKey, closeStyle) { dismiss() }
        cell.actor.keyShortcuts.add(KeyCharAndCode.BACK)
        if (UiNavigation.isIos && text == Constants.cancel) {
            UiNavigation.styleTextButton(cell.actor, if (ordinaryPresentation) popupUnit else UiNavigation.minimumTouchSize() / 44f)
            styleOrdinaryButton(cell.actor)
            if (ordinaryPresentation) cancelButton = cell.actor
            // Reuse an existing title-bar X, including its cancellation callback.
            val cancelActor = cell.actor
            val button = headerDismissButton { if (!cancelActor.isDisabled) dismiss() }
            if (button.parent == null) addDismissControl(button)
        }
        return cell
    }

    private fun headerDismissButton(action: () -> Unit): TextButton {
        headerDismissAction = action
        return headerCloseButton ?: UiNavigation.dismissButton(if (ordinaryPresentation) popupUnit else UiNavigation.minimumTouchSize() / 44f).apply {
            onActivation { headerDismissAction?.invoke() }
        }.also { headerCloseButton = it }
    }

    private fun addDismissControl(button: TextButton): Cell<TextButton> {
        (button.parent as? Table)?.getCell(button)?.let { return it }
        val unit = if (ordinaryPresentation) popupUnit else UiNavigation.minimumTouchSize() / 44f
        titleTable.add().expandX()
        val cell = titleTable.add(button).size(button.width, button.height).right()
        if (topTable === bottomTable) {
            // These modes share their content/button table. Reserve header space without
            // changing their scrolling mode or moving any existing content cells.
            if (!floatingCloseHeader) contentTopPadding = innerTable.padTop
            floatingCloseHeader = true
            innerTable.padTop(maxOf(contentTopPadding, 60f * unit))
            innerTable.addActor(titleTable)
        } else titleTable.pad(16f * unit, 20f * unit, 16f * unit, 20f * unit)
        return cell
    }

    /** A compulsory decision has a fixed heading, with no dismiss action. */
    protected fun addDecisionTitle(text: String, subtitle: String = "") =
        addDialogTitle(text, subtitle, dismissible = false)

    /** Prototype fact rows keep values in a separate, trailing-aligned column. */
    protected fun addFactRow(caption: String, value: String, action: (() -> Unit)? = null) {
        fun columnWidth(fraction: Float) = object : Value() {
            override fun get(context: Actor?) =
                (popupContentWidth * fraction - 8f * popupUnit).coerceAtLeast(1f)
        }
        val line = Table().apply {
            add(caption.toLabel(TextRole.Body, unit = popupUnit).apply { wrap = true })
                .width(columnWidth(.6f)).left()
            add(value.toLabel(TextRole.Body, unit = popupUnit, alignment = Align.right).apply { wrap = true })
                .width(columnWidth(.4f)).right().padLeft(16f * popupUnit)
        }
        if (action != null) {
            line.touchable = Touchable.enabled
            line.onActivation { action() }
        }
        add(line).width(popupContentWidth).padTop(12f * popupUnit)
            .apply { if (action != null) minHeight(44f * popupUnit) }.row()
    }

    /** Shared identity stack, with optional original-game artwork. */
    protected fun addDialogTitle(title: String, subtitle: String = "", icon: Actor? = null, dismissible: Boolean = true,
                                 onDismiss: (() -> Unit)? = null) {
        val identity = Table().apply {
            val text = Table().apply {
                add(title.toLabel(TextRole.PageTitle, unit = popupUnit, hideIcons = icon != null).apply { wrap = true }).growX().minWidth(0f).left().row()
                if (subtitle.isNotBlank()) add(subtitle.toLabel(TextRole.Supporting, unit = popupUnit, fontColor = Color.LIGHT_GRAY)
                    .apply { wrap = true }).growX().minWidth(0f).left().padTop(4f * popupUnit)
            }
            if (icon != null) add(icon).size((if (maxPopupWidth / popupUnit <= 430f) 36f else 52f) * popupUnit).top().padRight(14f * popupUnit)
            add(text).growX().minWidth(0f).left()
        }
        if (dismissible) addTitleWithCloseButton(identity, action = onDismiss)
        else {
            titleTable.pad(16f * popupUnit, 20f * popupUnit, 16f * popupUnit, 20f * popupUnit)
            titleTable.add(identity).growX().minWidth(0f)
        }
    }

    /** Adds a centered title and right-hand X, fixed above the scrollable content. */
    fun addTitleWithCloseButton(
        title: Actor,
        additionalKey: KeyCharAndCode? = null,
        action: (() -> Unit)? = null
    ): Group {
        clickBehindToClose = true
        onCloseCallback = action
        val closeButton = if (UiNavigation.isIos) headerDismissButton { if (!ordinaryPresentation || !closed) { close(); action?.invoke() } }.apply {
            keyShortcuts.add(KeyCharAndCode.BACK)
        } else getCloseButton { close(); action?.invoke() }
        if (additionalKey != null) closeButton.keyShortcuts.add(additionalKey)
        val header = if (ordinaryPresentation) {
            if (title is Label) {
                title.setFontSize(UiTypography.size(TextRole.PageTitle, popupUnit))
                title.wrap = true
                title.setAlignment(Align.left)
            }
            Table(skin).apply {
                add(title).minWidth(0f).growX().left()
                add(closeButton).size(44f * popupUnit).top().padLeft(12f * popupUnit)
            }
        } else UiNavigation.popupHeader(title, closeButton)
        if (topTable === bottomTable) add(header).growX().row()
        else {
            if (ordinaryPresentation) titleTable.pad(16f * popupUnit, 20f * popupUnit, 16f * popupUnit, 20f * popupUnit)
            else titleTable.pad(20f, 25f, 0f, 25f)
            titleTable.add(header).growX()
        }
        return closeButton
    }

    /**
     * Adds a [TextButton] that can close the popup, with [RETURN][KeyCharAndCode.RETURN] already mapped.
     * @param text The button's caption, defaults to "OK".
     * @param additionalKey An additional key that should act like a click.
     * @param validate Function that should return true when the popup can be closed and `action` can be run.
     * When this function returns false, nothing happens.
     * @param action A lambda to be executed after closing the popup when the button is clicked.
     * @return The new [Cell]
     */
    fun addOKButton(
        text: String = Constants.OK,
        additionalKey: KeyCharAndCode? = null,
        style: TextButtonStyle? = null,
        validate: (() -> Boolean) = { true },
        action: (() -> Unit),
    ): Cell<TextButton> {
        val cell = addButton(text, additionalKey, style) {
            if (validate()) {
                close()
                action()
            }
        }
        cell.actor.keyShortcuts.add(KeyCharAndCode.RETURN)
        styleOrdinaryButton(cell.actor, primary = true,
            destructive = style === BaseScreen.skin.get("negative", TextButtonStyle::class.java))
        return cell
    }

    /** Overload of [addCloseButton] accepting a bindable key definition as [additionalKey] */
    fun addCloseButton(text: String, additionalKey: KeyboardBinding, action: (() -> Unit)? = null) =
        addCloseButton(text, KeyboardBindings[additionalKey], action = action)
    /** Overload of [addOKButton] accepting a bindable key definition as [additionalKey] */
    fun addOKButton(text: String, additionalKey: KeyboardBinding, style: TextButtonStyle? = null, action: () -> Unit) =
        addOKButton(text, KeyboardBindings[additionalKey], style, action = action)

    /**
     * The last two additions ***must*** be buttons.
     * Make their width equal by setting minWidth of one cell to actor width of the other.
     */
    fun equalizeLastTwoButtonWidths() {
        if (ordinaryPresentation) return // Measured with the available window width on open.
        val n = bottomTable.cells.size
        if (n < 2) throw UnsupportedOperationException()
        val cell1 = bottomTable.cells[n-2]
        val cell2 = bottomTable.cells[n-1]
        if (cell1.actor !is Button || cell2.actor !is Button) throw UnsupportedOperationException()
        cell1.minWidth(cell2.actor.width).uniformX()
        cell2.minWidth(cell1.actor.width).uniformX()
    }

    /**
     * Reuse this popup as an error/info popup with a new message.
     * Removes everything from the popup to replace it with the message
     * and a close button if requested
     */
    fun reuseWith(newText: String, withCloseButton: Boolean = false) {
        clear()
        addGoodSizedLabel(newText)
        if (withCloseButton) {
            addCloseButton()
        }
    }

    /**
     * Sets or retrieves the [Actor] that currently has keyboard focus.
     *
     * Setting focus on a [TextField] will select all contained text unless a
     * [FocusListener][com.badlogic.gdx.scenes.scene2d.utils.FocusListener] cancels the event.
     */
    var keyboardFocus: Actor?
        get() = stageToShowOn.keyboardFocus
        set(value) {
            if (stageToShowOn.setKeyboardFocus(value))
                (value as? TextField)?.selectAll()
        }

    /** Gets the ScrollPane the content is wrapped in (only if Popup was instantiated with scrollable=true) */
    fun getScrollPane() = topTable.parent as? ScrollPane
}


/** @return A [List] of currently active or pending [Popup] screens. */
val BaseScreen.popups
    get() = stage.popups
private val Stage.popups: List<Popup>
    get() = actors.filterIsInstance<Popup>()

/** @return The currently active [Popup] or `null` if none. */
val BaseScreen.activePopup: Popup?
    get() = popups.lastOrNull { it.isVisible }

/**
 * Checks if there are visible [Popup]s.
 * @return `true` if any were found.
 */
fun BaseScreen.hasOpenPopups(): Boolean = stage.hasOpenPopups()
private fun Stage.hasOpenPopups(): Boolean = actors.any { it is Popup && it.isVisible }

/** Closes all [Popup]s. */
fun BaseScreen.closeAllPopups() = popups.forEach { it.close() }
