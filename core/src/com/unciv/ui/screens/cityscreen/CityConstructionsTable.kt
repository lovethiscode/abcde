package com.unciv.ui.screens.cityscreen

import com.unciv.ui.components.fonts.UiTypography
import com.unciv.ui.components.fonts.TextRole
import com.unciv.ui.components.widgets.UiNavigation
import com.unciv.models.skins.ExpeditionBlueTheme
import com.unciv.view.isBeingConstructedOrEnqueued
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Group
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.Cell
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.unciv.ui.components.extensions.setFontSize
import com.unciv.ui.components.input.onChange
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener
import com.badlogic.gdx.utils.Align
import com.unciv.Constants
import com.unciv.models.UncivSound
import com.unciv.models.ruleset.Building
import com.unciv.models.ruleset.IConstruction
import com.unciv.models.ruleset.INonPerpetualConstruction
import com.unciv.models.ruleset.PerpetualConstruction
import com.unciv.models.ruleset.RejectionReason
import com.unciv.models.ruleset.RejectionReasonType
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.ruleset.unit.BaseUnit
import com.unciv.models.stats.Stat
import com.unciv.models.translations.tr
import com.unciv.ui.audio.SoundPlayer
import com.unciv.ui.components.UncivTooltip.Companion.addTooltip
import com.unciv.ui.components.extensions.addBorder
import com.unciv.ui.components.extensions.addCell
import com.unciv.ui.components.extensions.addSeparator
import com.unciv.ui.components.extensions.brighten
import com.unciv.ui.components.extensions.darken
import com.unciv.ui.components.extensions.getConsumesAmountString
import com.unciv.ui.components.extensions.getTurnsToConstructionString
import com.unciv.ui.components.extensions.packIfNeeded
import com.unciv.ui.components.extensions.setEnabled
import com.unciv.ui.components.extensions.surroundWithCircle
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.fonts.Fonts
import com.unciv.ui.components.input.KeyboardBinding
import com.unciv.ui.components.input.keyShortcuts
import com.unciv.ui.components.input.onActivation
import com.unciv.ui.components.input.onClick
import com.unciv.ui.components.widgets.ColorMarkupLabel
import com.unciv.ui.components.widgets.ExpanderTab
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.popups.AnimatedMenuPopup.Companion.addContextMenu
import com.unciv.ui.popups.AnimatedMenuPopup.Companion.adjustContextMenuIndicators
import com.unciv.ui.popups.ConfirmPopup
import com.unciv.ui.popups.CityScreenConstructionMenu
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.utils.Concurrency
import com.unciv.utils.launchOnGLThread
import kotlin.math.max
import kotlin.math.min
import com.unciv.ui.components.widgets.AutoScrollPane as ScrollPane

private class ConstructionButtonDTO(
    val construction: IConstruction,
    val buttonText: String,
    val resourcesRequired: HashMap<String, Int>? = null,
    val rejectionReason: RejectionReason? = null)

/**
 * Manager to hold and coordinate two widgets for the city screen left side:
 * - Construction queue with the buy button.
 *   The queue is scrollable, limited to one third of the stage height.
 * - Available constructions display, scrolling, grouped with expanders and therefore of dynamic height.
 */
class CityConstructionsTable(private val cityScreen: CityScreen) {
    /* -1 = Nothing, >= 0 queue entry (0 = current construction) */
    var selectedQueueEntry = -1 // None

    private val cityView get() = cityScreen.cityView

    private val upperTable = Table(BaseScreen.skin)
    private val constructionsQueueScrollPane: ScrollPane
    private val constructionsQueueTable = Table()
    private val queueExpander: ExpanderTab
    private val buttonsTable = Table()
    private val buyButtonFactory = BuyButtonFactory(cityScreen)

    private val lowerTable = Table()
    private val availableConstructionsScrollPane: ScrollPane
    private val availableConstructionsTable = Table()
    private val lowerTableScrollCell: Cell<ScrollPane>

    private val pad = 10f
    private val miniQueueHeight = 54f
    private val posFromEdge = CityScreen.posFromEdge
    private val stageHeight = cityScreen.stage.height - if (UiNavigation.isMobile) UiNavigation.minimumTouchSize() + 20f else 0f

    private var queueMutationPending = false
    private var nativeCategory = "All"
    private var nativeCategories = linkedMapOf<String, List<Table>>()
    private val nativeContent = Table()
    private val nativeScrollPositions = mutableMapOf<String, Float>()
    private var nativeScrollKey = ""
    private val nativePane = ScrollPane(nativeContent).apply { setOverscroll(false, false); setScrollingDisabled(true, false) }

    private val highlightColor = ExpeditionBlueTheme.color(Color.GREEN.darken(0.4f), ExpeditionBlueTheme.raised)

    /** Gets or sets visibility of [both widgets][CityConstructionsTable] */
    var isVisible: Boolean
        get() = upperTable.isVisible
        set(value) {
            upperTable.isVisible = value
            lowerTable.isVisible = value
        }

    init {
        constructionsQueueScrollPane = ScrollPane(constructionsQueueTable.addBorder(2f, Color.WHITE))
        constructionsQueueScrollPane.setOverscroll(false, false)
        constructionsQueueTable.background = BaseScreen.skinStrings.getUiBackground(
            "CityScreen/CityConstructionTable/ConstructionsQueueTable",
            tintColor = ImageGetter.CHARCOAL
        )
        queueExpander = ExpanderTab(
            "Construction queue",
            onChange = { cityScreen.updateAsync() },
            defaultPad = 0f,
            // keep lowerTable at fixed position
            startsOutOpened = false,
            expanderHeight = miniQueueHeight
        )
        queueExpander.header.name = "ui-construction-queue-expand"

        upperTable.defaults().left().top()

        // The construction queue is collapsed by default, so there's more vertical space for the
        // available-constructions-table. When the user decides to expand the
        // queue, we can use the majority of available vertical space (3/4 of stage height)!
        // Landscape on Android benefits most from this UX/UI optimization.
        // Effectively, the available-construction-table takes almost all available vertical space
        // by default, and by expancing the construction queue, the user changes precedence to the
        // construction queue.
        upperTable.add(constructionsQueueScrollPane).padBottom(pad).maxHeight(stageHeight*3/4).row()
        upperTable.add(buttonsTable)
            .height("".toTextButton(unit = if (cityScreen.usesWorkbench) 1f else UiTypography.unitsPerPoint(cityScreen.stage)).height) // constant height in order to not let the lowerTable jump
            .padBottom(pad).row()

        availableConstructionsScrollPane = ScrollPane(availableConstructionsTable.addBorder(2f, Color.WHITE))
        availableConstructionsScrollPane.setOverscroll(false, false)
        availableConstructionsTable.background = BaseScreen.skinStrings.getUiBackground(
            "CityScreen/CityConstructionTable/AvailableConstructionsTable",
            tintColor = ImageGetter.CHARCOAL
        )
        lowerTableScrollCell = lowerTable.add(availableConstructionsScrollPane).bottom()
        lowerTable.row()
    }

    internal fun selectWorkbenchCategory(category: String) { nativeCategory = category }

    internal fun restoreWorkbench(other: CityConstructionsTable) {
        other.rememberWorkbenchScroll()
        nativeCategory = other.nativeCategory
        nativeScrollPositions.putAll(other.nativeScrollPositions)
    }

    private fun rememberWorkbenchScroll() {
        if (nativePane.parent != null && nativeScrollKey.isNotEmpty())
            nativeScrollPositions[nativeScrollKey] = nativePane.scrollY
    }

    internal fun workbenchContent(width: Float, queueOpen: Boolean, toggleQueue: () -> Unit): Actor {
        rememberWorkbenchScroll()
        nativeScrollKey = if (queueOpen) "Queue" else nativeCategory
        nativeContent.clear()
        nativeContent.top().pad(0f)
        nativeContent.defaults().growX().minWidth(0f)
        val result = Table().apply { top() }
        val queue = cityView.constructions.constructionQueue
        if (queueOpen) {
            result.add("Project turns exclude queue waiting time.".toLabel(TextRole.Supporting, unit = 1f).apply { wrap = true })
                .width(width).pad(6f, 12f, 6f, 12f).row()
            for ((index, constructionName) in queue.withIndex()) {
                val construction = cityView.constructions.getConstruction(constructionName)
                val entry = Table()
                entry.add((index + 1).toString().toLabel(TextRole.CompactData, unit = 1f)).width(20f).padRight(4f)
                val caption = constructionName.tr(hideIcons = true) + "\n" + cityView.constructions.getTurnsToConstructionString(construction).trim()
                val select = Table().apply {
                    name = "ui-construction-queue:$constructionName"
                    add(caption.toLabel(TextRole.Control, unit = 1f, hideIcons = true).apply { wrap = true })
                        .width((width - 164f).coerceAtLeast(80f)).growX().left()
                    onActivation { selectQueueEntry(index) }
                }
                entry.add(select).growX().minWidth(0f).minHeight(56f)
                if (cityScreen.canCityBeChanged()) {
                    entry.add(getRaisePriorityButton(index, constructionName).apply { isVisible = index > 0 }).size(44f)
                    entry.add(getLowerPriorityButton(index, constructionName).apply { isVisible = index < queue.lastIndex }).size(44f)
                    entry.add(getRemoveFromQueueButton(index)).size(44f)
                }
                nativeContent.add(entry).width(width).minHeight(56f).row()
            }
            if (queue.isEmpty()) nativeContent.add("Pick a construction".toLabel(TextRole.Body, unit = 1f).apply { wrap = true }).width(width).pad(12f).row()
        } else {
            val top = Table()
            val current = queue.firstOrNull()
            val compact = cityScreen.workbenchPhone
            val queueButton = cityWorkbenchControl("{Queue} " + maxOf(0, queue.size - 1), "ui-city-queue", action = toggleQueue)
            val keys = listOf("All", "Units", "Buildings", "Wonders", "National Wonders", "Other", "Disabled")
            val category = cityWorkbenchCategorySelectBox().apply {
                setItems(*keys.map { it.tr() }.toTypedArray())
                selectedIndex = keys.indexOf(nativeCategory).coerceAtLeast(0)
                if (compact) setSelectedPrefWidth(true)
                name = "ui-city-production-category"
                onChange { nativeCategory = keys[selectedIndex]; cityScreen.updateWithoutConstructionAndMap() }
            }
            val queueWidth = maxOf(44f, queueButton.prefWidth)
            val categoryWidth = maxOf(44f, category.prefWidth)
            val summaryWidth = if (compact) width - queueWidth - categoryWidth - 16f
                else if (cityScreen.workbenchExpanded) width * .46f else width
            val currentLabel = (current ?: "Pick a construction").toLabel(TextRole.Control, unit = 1f, hideIcons = true)
            val turnsLabel = current?.takeIf { cityView.constructions.getConstruction(it) is INonPerpetualConstruction }?.let {
                "[${cityView.constructions.turnsToConstruction(it)}] turns".toLabel(TextRole.Supporting, unit = 1f)
            }
            // Reflow the whole header when real text cannot fit; never squeeze a long name
            // or the selected category into a narrow column to keep the single-row layout.
            val minimumCopyWidth = maxOf(currentLabel.prefWidth, turnsLabel?.let { it.prefWidth + 24f } ?: 0f)
            val inline = compact && minimumCopyWidth + (if (current != null) 52f else 0f) <= summaryWidth
            currentLabel.wrap = true
            val copyWidth = (if (inline) summaryWidth else width) - if (current != null) 52f else 0f
            val summary = Table()
            if (current != null) {
                val construction = cityView.constructions.getConstruction(current)
                summary.add(ImageGetter.getConstructionPortrait(current, if (compact) 24f else 28f))
                    .size(if (compact) 24f else 28f).padRight(8f)
                val copy = Table()
                copy.add(currentLabel).width(if (compact) copyWidth else summaryWidth - 152f).left().row()
                if (construction is INonPerpetualConstruction) {
                    val progress = cityView.constructions.getWorkDone(current).toFloat() / cityView.getConstructionProductionCost(construction).coerceAtLeast(1)
                    if (compact) {
                        val status = Table()
                        val turns = turnsLabel!!
                        status.add(turns).left().padRight(8f)
                        status.add(ImageGetter.ProgressBar((copyWidth - turns.prefWidth - 8f).coerceAtLeast(0f), 3f, false)
                            .setBackground(Color.valueOf("102b35")).setProgress(Color.valueOf("e4c48a"), progress)).height(3f).growX()
                        copy.add(status).width(copyWidth).left()
                    } else copy.add(ImageGetter.ProgressBar(summaryWidth - 152f, 3f, false)
                        .setBackground(Color.valueOf("102b35")).setProgress(Color.valueOf("e4c48a"), progress)).height(3f).padTop(4f)
                }
                summary.add(copy).growX().minWidth(0f)
                if (!compact && construction is INonPerpetualConstruction) summary.add(
                    "[${cityView.constructions.turnsToConstruction(current)}] turns".toLabel(TextRole.Control, unit = 1f)
                ).padLeft(8f)
                summary.add(ImageGetter.getImage("OtherIcons/ArrowRight")).size(if (compact) 12f else 16f).padLeft(8f)
                summary.name = "ui-city-current-production"
                summary.onActivation { selectQueueEntry(0) }
            } else summary.add(currentLabel).width(if (inline) summaryWidth else width).left().growX()
            val tools = Table()
            tools.add(queueButton).minWidth(44f).minHeight(44f).padRight(8f)
            tools.add(category).growX().minWidth(0f).minHeight(44f)
            if (inline) {
                top.add(summary).width(summaryWidth).minHeight(44f).padRight(8f)
                top.add(tools).width(queueWidth + categoryWidth + 8f).minHeight(44f)
            } else if (!compact && cityScreen.workbenchExpanded) {
                top.add(summary).width(width * .46f).minHeight(44f).padRight(12f)
                top.add(tools).growX().minWidth(0f).minHeight(44f)
            } else {
                top.add(summary).growX().minWidth(0f).minHeight(if (compact) 44f else 52f).row()
                top.add(tools).growX().minWidth(0f).minHeight(44f)
            }
            result.add(top).width(width).padLeft(12f).padRight(12f).row()
            val rows = if (nativeCategory == "All") nativeCategories.values.flatten() else nativeCategories[nativeCategory].orEmpty()
            for (row in rows) nativeContent.add(row).width(width).minHeight(if (cityScreen.workbenchExpanded) 44f else 56f).row()
            if (rows.isEmpty()) nativeContent.add("None".toLabel(TextRole.Body, unit = 1f)).pad(12f).row()
        }
        result.add(nativePane).grow().minSize(0f).padLeft(12f).padRight(12f).row()
        nativePane.layout()
        nativePane.scrollY = nativeScrollPositions[nativeScrollKey] ?: 0f
        nativePane.updateVisualScroll()
        return result
    }

    internal fun workbenchActions(construction: IConstruction): Table = Table().apply {
        val queueIndex = selectedQueueEntry.takeIf { it in cityView.constructions.constructionQueue.indices && cityView.constructions.constructionQueue[it] == construction.name }
        lateinit var more: com.badlogic.gdx.scenes.scene2d.ui.TextButton
        more = cityWorkbenchControl("More", "ui-city-construction-more") {
            val popup = CityWorkbenchPopup(cityScreen, "More", construction.name)
            val popupUnit = UiTypography.unitsPerPoint(cityScreen.stage)
            for (buy in buyButtonFactory.getBuyButtons(construction, popupUnit, showRestrictions = true)) {
                popup.add(buy).width(minOf(400f * popupUnit, cityScreen.stage.width - 64f * popupUnit)).minHeight(UiNavigation.minimumTouchSize()).row()
            }
            popup.addButton("City construction queue") {
                popup.close()
                if (cityScreen.canCityBeChanged()) CityScreenConstructionMenu(cityScreen.stage, more, cityView, construction) {
                    cityView.tryReassignPopulation(); cityScreen.updateAsync()
                }
            }.apply { actor.isDisabled = !cityScreen.canCityBeChanged() }.growX().row()
            popup.addCloseButton()
            popup.open()
        }
        add(more).minHeight(44f).padRight(8f)
        if (queueIndex != null) {
            add(cityWorkbenchControl("Remove from queue", "ui-city-detail-remove", cityScreen.canCityBeChanged()) {
                removeWorkbenchQueueEntry(queueIndex)
            }).growX().minWidth(0f).minHeight(44f)
        } else {
            add(cityWorkbenchControl("Add to queue", "ui-city-detail-add", !cannotAddConstructionToQueue(construction)) {
                addConstructionToQueue(construction)
                cityScreen.updateAsync()
            }).growX().minWidth(0f).minHeight(44f)
        }
    }

    private fun removeWorkbenchQueueEntry(index: Int) {
        if (!cityScreen.canCityBeChanged() || queueMutationPending) return
        val expected = cityView.constructions.constructionQueue.getOrNull(index) ?: return
        fun remove() {
            if (queueMutationPending || cityView.constructions.constructionQueue.getOrNull(index) != expected) return
            queueMutationPending = true
            Concurrency.run {
                val success = cityView.tryRemoveFromQueue(index, false)
                if (success) cityView.tryReassignPopulation()
                Concurrency.runOnGLThread {
                    queueMutationPending = false
                    if (success) cityScreen.clearSelection()
                    cityScreen.updateAsync()
                }
            }
        }
        if (index == 0) ConfirmPopup(cityScreen,
            "Remove [${cityView.constructions.constructionQueue[index]}] from the queue?", "Remove from queue",
            restoreDefault = { cityScreen.updateAsync() }) { remove() }.open()
        else remove()
    }

    private fun getWorkbenchConstructionButton(dto: ConstructionButtonDTO): Table {
        val construction = dto.construction
        val expanded = cityScreen.workbenchExpanded
        val width = cityScreen.workbenchWidth
        val available = (width - 88f).coerceAtLeast(80f)
        val row = Table().apply { top(); name = "ui-construction-row:${construction.name}" }
        val select = Table().apply {
            name = "ui-construction:${construction.name}"
            touchable = Touchable.enabled
            add(ImageGetter.getConstructionPortrait(construction.name, if (expanded) 24f else 28f)).size(if (expanded) 24f else 28f).padRight(8f)
        }
        val copy = Table()
        val name = construction.name.toLabel(TextRole.Control, unit = 1f, hideIcons = true).apply { wrap = true }
        val info = dto.buttonText.toLabel(TextRole.Supporting, unit = 1f).apply { wrap = true }
        if (expanded) {
            copy.add(name).width((available - 180f).coerceAtLeast(80f)).left().growX()
            copy.add(info).width(172f).padLeft(8f).left()
        } else {
            copy.add(name).width(available).left().row()
            copy.add(info).width(available).left().padTop(2f)
        }
        select.add(copy).growX().minWidth(0f)
        select.onActivation {
            if (cityScreen.selectedConstruction == construction) addConstructionToQueue(construction)
            else cityScreen.selectConstruction(construction)
            selectedQueueEntry = -1
            cityScreen.updateAsync()
        }
        if (cityScreen.canCityBeChanged()) select.addContextMenu(isAllowed = { cityScreen.canCityBeChanged() }) {
            CityScreenConstructionMenu(cityScreen.stage, select, cityView, construction) {
                cityView.tryReassignPopulation(); cityScreen.updateAsync()
            }
        }
        row.add(select).growX().minWidth(0f).minHeight(if (expanded) 44f else 56f)
        val add = cityWorkbenchIcon("OtherIcons/New", "Add to queue", "ui-construction-add:${construction.name}", !cannotAddConstructionToQueue(construction)) {
            selectedQueueEntry = -1
            addConstructionToQueue(construction)
            cityScreen.updateAsync()
        }
        row.add(add).size(44f).padLeft(4f)
        val resources = (dto.resourcesRequired?.entries?.map { it.key to it.value } ?: emptyList()) +
            cityView.getStockpiledResourceRequirements(construction).map { it.key to it.value }
        if (resources.isNotEmpty()) {
            row.row()
            val requirements = Table()
            var used = 0f
            for ((resource, amount) in resources) {
                val group = Table().apply {
                    add(amount.toString().toLabel(TextRole.CompactData, unit = 1f)).padRight(4f)
                    add(ImageGetter.getResourcePortrait(resource, 16f)).size(16f)
                }
                val groupWidth = group.prefWidth + 12f
                if (used + groupWidth > width && used > 0f) { requirements.row(); used = 0f }
                requirements.add(group).left().padRight(12f)
                used += groupWidth
            }
            row.add(requirements).colspan(2).left().width(width).padBottom(4f)
        }
        dto.rejectionReason?.let {
            row.row()
            row.add(it.errorMessage.toLabel(TextRole.Body, unit = 1f, fontColor = ExpeditionBlueTheme.gold).apply { wrap = true })
                .colspan(2).width(width).padBottom(8f).left()
        }
        return row
    }

    /** Forces layout calculation and returns the upper Table's (construction queue) width */
    fun getUpperWidth() = upperTable.packIfNeeded().width
    /** Forces layout calculation and returns the lower Table's (available constructions) width
     *  - or - the upper Table's width, whichever is greater (in case the former only contains "Loading...")
     */
    fun getLowerWidth() = max(lowerTable.packIfNeeded().width, getUpperWidth())

    fun addActorsToStage() {
        cityScreen.stage.addActor(upperTable)
        cityScreen.stage.addActor(lowerTable)
        lowerTable.setPosition(posFromEdge, posFromEdge, Align.bottomLeft)
    }

    fun update(selectedConstruction: IConstruction?) {
        if (!cityScreen.usesWorkbench) updateQueueAndButtons(selectedConstruction)
        updateAvailableConstructions()
    }

    private fun updateQueueAndButtons(construction: IConstruction?) {
        if (cityScreen.usesWorkbench) return
        updateButtons(construction)
        updateConstructionQueue()
        upperTable.pack()
        // Need to reposition when height changes as setPosition's alignment does not persist, it's just a readability shortcut to calculate bottomLeft
        upperTable.setPosition(posFromEdge, stageHeight - posFromEdge, Align.topLeft)
        lowerTableScrollCell.maxHeight(
            (stageHeight - upperTable.height - 2 * posFromEdge).coerceAtLeast(20f)
        )
        constructionsQueueTable.adjustContextMenuIndicators()
    }

    private fun updateButtons(construction: IConstruction?) {
        if (!cityScreen.canChangeState) return
        /** [UniqueType.MayBuyConstructionsInPuppets] support - we need a buy button for civs that could buy items in puppets */
        if (cityView.isPuppet() && !cityView.hasMatchingUnique(UniqueType.MayBuyConstructionsInPuppets)) return
        buttonsTable.clear()

        for (button in buyButtonFactory.getBuyButtons(construction)) {
            buttonsTable.add(button).width(120f).padRight(10f)
        }
        // priority buttons and remove button
        val queue = cityView.constructions.constructionQueue
        if (selectedQueueEntry in 0..<queue.size && queue.size > 1) {
            val constructionName = queue[selectedQueueEntry]

            val raiseButton = getRaisePriorityButton(selectedQueueEntry, constructionName)
            raiseButton.name = "ui-construction-raise-selected"
            raiseButton.setEnabled(cityScreen.canCityBeChanged() && selectedQueueEntry > 0)
            buttonsTable.add(raiseButton).padRight(5f)

            val lowerButton = getLowerPriorityButton(selectedQueueEntry, constructionName)
            lowerButton.setEnabled(selectedQueueEntry != queue.lastIndex && cityScreen.canCityBeChanged())
            buttonsTable.add(lowerButton).padRight(5f)

            if (cityScreen.canCityBeChanged() && !queueExpander.isOpen && selectedQueueEntry in 1..4)
                buttonsTable.add(getRemoveFromQueueButton(selectedQueueEntry)).padLeft(10f)
        }
    }

    private fun updateConstructionQueue() {
        val queueScrollY = constructionsQueueScrollPane.scrollY
        constructionsQueueTable.clear()

        val cityConstructions = cityView.constructions
        val currentConstruction = cityConstructions.currentConstructionName()
        val queue = cityConstructions.constructionQueue

        constructionsQueueTable.defaults().pad(0f)
        constructionsQueueTable.add(getHeader("Current construction")).fillX()

        constructionsQueueTable.addSeparator()

        if (currentConstruction != "")
            constructionsQueueTable.add(getQueueEntry(0, currentConstruction))
                    .expandX().fillX().row()
        else
            constructionsQueueTable.add("Pick a construction".toLabel(TextRole.Body, unit = if (cityScreen.usesWorkbench) 1f else UiTypography.unitsPerPoint(cityScreen.stage))).height(50f).pad(2f).row()

        // always show queue expander, even when empty, in order to keep lowerTable at constant position
        queueExpander.innerTable.clear()
        queueExpander.headerContent.clear()
        queueExpander.isHeaderIconVisible = queue.size >= 2
        queueExpander.header.pad(0f)
        queueExpander.setText("Construction queue".tr())
        queue.forEachIndexed { i, constructionName ->
            // The first entry is already displayed as "Current construction"
            if (i != 0) {
                queueExpander.innerTable.add(getQueueEntry(i, constructionName))
                    .expandX().fillX().row()
                if (i != queue.lastIndex) {
                    queueExpander.innerTable.addSeparator()
                }
            }
        }
        if (!queueExpander.isOpen) {
            updateQueuePreview(queue)
        }
        constructionsQueueTable.add(queueExpander).fillX().pad(2f)

        constructionsQueueScrollPane.layout()
        constructionsQueueScrollPane.scrollY = queueScrollY
        constructionsQueueScrollPane.updateVisualScroll()
    }

    private fun updateQueuePreview(queue: List<String>) {
        queueExpander.header.pad(-5f, 0f, -5f, 0f)
        val title = when(queue.size) {
            in 0..1 -> "Queue empty".tr()
            in 2..4 -> "Queue".tr()
            else -> ""
        }
        queueExpander.setText(title)
        queue.forEachIndexed { i, constructionName ->
            if (i in 1..3) {
                val color = if (selectedQueueEntry == i) highlightColor else BaseScreen.skinStrings.skinConfig.baseColor
                val image = ImageGetter.getConstructionPortrait(constructionName, 40f).surroundWithCircle(miniQueueHeight, false, color)
                image.name = "ui-construction-queue-preview:$constructionName"
                image.addListener(object: ClickListener() {
                    // Calling event.stop() to prevent click propagation to the parent,
                    // the expander header.
                    // We are using touchDown and touchUp here, because event.stop()
                    // won't work on the click() callback alone. This is because a click consists
                    // of both a touch down and touch up.
                    override fun touchDown(event: InputEvent, x: Float, y: Float, pointer: Int, button: Int): Boolean {
                        event.stop()
                        return super.touchDown(event, x, y, pointer, button)
                    }
                    override fun touchUp(event: InputEvent, x: Float, y: Float, pointer: Int, button: Int) {
                        cityScreen.selectConstruction(constructionName)
                        selectedQueueEntry = i
                        cityScreen.updateAsync()
                        event.stop()
                        super.touchUp(event, x, y, pointer, button)
                    }
                })
                queueExpander.headerContent.add(image)
            }
            if (i == 4) {
                queueExpander.headerContent.add("(+{${queue.size - i}})".toLabel(TextRole.Body, unit = if (cityScreen.usesWorkbench) 1f else UiTypography.unitsPerPoint(cityScreen.stage)))
            }
        }
    }

    private fun getConstructionButtonDTOs(): ArrayList<ConstructionButtonDTO> {
        val constructionButtonDTOList = ArrayList<ConstructionButtonDTO>()

        val cityConstructions = cityView.constructions

        val constructionsSequence = cityView.getRuleset().units.values.asSequence() +
                cityView.getRuleset().buildings.values.asSequence()

        cityView.updateTileStats() // only once
        for (entry in constructionsSequence.filter { cityConstructions.shouldBeDisplayed(it) }) {

            val useStoredProduction = entry is Building || !cityConstructions.isBeingConstructedOrEnqueued(entry.name)

            // this is susceptible to comodification since ANY change in unique sources - for example a new building -
            //   can cause comodification change
            // This is however rare enough and short enough in duration that a second run will work
            val buttonText = try {
                cityConstructions.getTurnsToConstructionString(entry, useStoredProduction).trim()
            } catch (_: Exception){
                cityConstructions.getTurnsToConstructionString(entry, useStoredProduction).trim()
            }

            val resourcesRequired = cityView.getResourceRequirementsPerTurn(entry)
            val mostImportantRejection =
                    cityConstructions.getRejectionReasons(entry)
                        .filter { it.isImportantRejection() }
                        .minByOrNull { it.getRejectionPrecedence() }

            constructionButtonDTOList.add(
                ConstructionButtonDTO(
                    entry,
                    buttonText,
                    if (resourcesRequired.isEmpty()) null else resourcesRequired,
                    mostImportantRejection
                )
            )
        }

        for (specialConstruction in PerpetualConstruction.perpetualConstructionsMap.values
                .filter { cityConstructions.shouldBeDisplayed(it) }
        ) {
            constructionButtonDTOList.add(
                ConstructionButtonDTO(
                    specialConstruction,
                    "Produce [${specialConstruction.name}]".tr() + " " + cityView.getProductionTooltip(specialConstruction).trim()
                )
            )
        }

        return constructionButtonDTOList
    }

    private fun updateAvailableConstructions() {
        val constructionsScrollY = availableConstructionsScrollPane.scrollY

        if (!availableConstructionsTable.hasChildren()) { //
            availableConstructionsTable.add(Constants.loading.toLabel(TextRole.Body, unit = if (cityScreen.usesWorkbench) 1f else UiTypography.unitsPerPoint(cityScreen.stage))).pad(10f)
        }

        Concurrency.run("Construction info gathering - ${cityView.name}") {
            // Since this can be a heavy operation and leads to many ANRs on older phones we put the metadata-gathering in another thread.
            val constructionButtonDTOList = getConstructionButtonDTOs()
            launchOnGLThread {
                val units = ArrayList<Table>()
                val buildableWonders = ArrayList<Table>()
                val buildableNationalWonders = ArrayList<Table>()
                val buildableBuildings = ArrayList<Table>()
                val specialConstructions = ArrayList<Table>()
                val blacklisted = ArrayList<Table>()
                val disabledAutoAssignConstructions: Set<String> = cityView.getDisabledConstructions()

                var maxButtonWidth = constructionsQueueTable.width
                for (dto in constructionButtonDTOList) {

                    /** filter out showing buildings that have RequiresBuildingInThisCity
                     * rejection (eg requiredBuilding entry) which are buildable.
                     * The rejection for RequiresBuildingInThisCity isn't yielded if
                     * the prerequisite is in the queue
                     */
                    if (dto.construction is Building
                            && dto.rejectionReason?.type == RejectionReasonType.RequiresBuildingInThisCity
                            && constructionButtonDTOList.any {
                                (it.construction is Building) && (it.construction.name == dto.construction.requiredBuilding
                                        || it.construction.replaces == dto.construction.requiredBuilding
                                        || it.construction.hasTagUnique(dto.construction.requiredBuilding!!, cityView.getState()))
                            })
                        continue

                    val constructionButton = getConstructionButton(dto)
                    if (dto.construction.name in disabledAutoAssignConstructions)
                        blacklisted.add(constructionButton)
                    else when (dto.construction) {
                        is BaseUnit -> units.add(constructionButton)
                        is Building -> {
                            when {
                                dto.construction.isWonder -> buildableWonders += constructionButton
                                dto.construction.isNationalWonder -> buildableNationalWonders += constructionButton
                                else -> buildableBuildings += constructionButton
                            }
                        }
                        is PerpetualConstruction -> specialConstructions.add(constructionButton)
                    }
                    maxButtonWidth = max(maxButtonWidth, constructionButton.packIfNeeded().width)
                }

                if (cityScreen.usesWorkbench) {
                    nativeCategories = linkedMapOf("Units" to units, "Buildings" to buildableBuildings,
                        "Wonders" to buildableWonders, "National Wonders" to buildableNationalWonders,
                        "Other" to specialConstructions, "Disabled" to blacklisted)
                    if (cityScreen.game.screen === cityScreen) cityScreen.updateWithoutConstructionAndMap()
                    return@launchOnGLThread
                }
                availableConstructionsTable.apply {
                    clear()
                    defaults().left().bottom()
                    addCategory("Units", units, maxButtonWidth, KeyboardBinding.BuildUnits)
                    addCategory("Buildings", buildableBuildings, maxButtonWidth, KeyboardBinding.BuildBuildings)
                    addCategory("Wonders", buildableWonders, maxButtonWidth, KeyboardBinding.BuildWonders)
                    addCategory("National Wonders", buildableNationalWonders, maxButtonWidth, KeyboardBinding.BuildNationalWonders)
                    addCategory("Other", specialConstructions, maxButtonWidth, KeyboardBinding.BuildOther)
                    addCategory("Disabled", blacklisted, maxButtonWidth, KeyboardBinding.BuildDisabled, startsOutOpened = false)
                    pack()
                }

                availableConstructionsScrollPane.apply {
                    setSize(maxButtonWidth, min(availableConstructionsTable.prefHeight, lowerTableScrollCell.maxHeight))
                    layout()
                    scrollY = constructionsScrollY
                    updateVisualScroll()
                }
                lowerTable.pack()
                availableConstructionsTable.adjustContextMenuIndicators()
            }
        }
    }

    private fun getQueueEntry(constructionQueueIndex: Int, constructionName: String): Table {
        val cityConstructions = cityView.constructions

        val table = Table()
        table.align(Align.left).pad(5f)
        highlightQueueEntry(table, constructionQueueIndex == selectedQueueEntry)

        val construction = cityConstructions.getConstruction(constructionName)
        val isFirstConstructionOfItsKind = cityConstructions.isFirstConstructionOfItsKind(constructionQueueIndex, constructionName)

        var text = constructionName.tr(true) +
                if (constructionName in PerpetualConstruction.perpetualConstructionsMap) "\n" + Fonts.infinity
                else cityConstructions.getTurnsToConstructionString(construction, isFirstConstructionOfItsKind)

        val constructionResource = cityView.getResourceRequirementsPerTurn(construction)
        for ((resourceName, amount) in constructionResource) {
            val resource = cityView.getRuleset().tileResources[resourceName] ?: continue
            text += "\n" + resourceName.getConsumesAmountString(amount, resource.isStockpiled).tr()
        }

        table.defaults().pad(2f).minWidth(40f)
        if (isFirstConstructionOfItsKind) table.add(getProgressBar(constructionName)).minWidth(5f)
        else table.add().minWidth(5f)
        table.add(ImageGetter.getConstructionPortrait(constructionName, 40f)).padRight(10f)
        table.add(text.toLabel(TextRole.Body, unit = if (cityScreen.usesWorkbench) 1f else UiTypography.unitsPerPoint(cityScreen.stage)).apply { name = "ui-construction-queue:$constructionName" }).expandX().fillX().left()

        if (queueExpander.isOpen) {
            if (constructionQueueIndex > 0 && cityScreen.canCityBeChanged()){
                table.add(getRaisePriorityButton(constructionQueueIndex, constructionName)).right()}
            else table.add().right()
            if (constructionQueueIndex != cityConstructions.constructionQueue.lastIndex && cityScreen.canCityBeChanged())
                table.add(getLowerPriorityButton(constructionQueueIndex, constructionName)).right()
            else table.add().right()
        }

        if (cityScreen.canCityBeChanged()) table.add(getRemoveFromQueueButton(constructionQueueIndex)).right()
        else table.add().right()

        table.touchable = Touchable.enabled

        table.onClick { selectQueueEntry(constructionQueueIndex) }
        if (cityScreen.canCityBeChanged()) {
            table.addContextMenu {
                selectQueueEntry(constructionQueueIndex) {
                    CityScreenConstructionMenu(cityScreen.stage, table, cityView, construction) {
                        cityView.tryReassignPopulation()
                        cityScreen.updateAsync()
                    }
                }
            }
        }

        return table
    }

    private fun <T> selectQueueEntry(constructionQueueIndex: Int, onBeforeUpdate: () -> T): T {
        if (constructionQueueIndex in 0..<cityView.constructions.constructionQueue.size) {
            cityScreen.selectConstructionFromQueue(constructionQueueIndex)
            selectedQueueEntry = constructionQueueIndex
        } else {
            cityScreen.clearSelection()
            selectedQueueEntry = -1
        }
        val result = onBeforeUpdate()
        cityScreen.updateAsync()  // Not before CityScreenConstructionMenu or table will have no parent to get stage coords
        ensureQueueEntryVisible()
        return result
    }
    private fun selectQueueEntry(constructionQueueIndex: Int) = selectQueueEntry(constructionQueueIndex) {}

    private fun highlightQueueEntry(queueEntry: Table, highlight: Boolean) {
        queueEntry.background =
            if (highlight)
                BaseScreen.skinStrings.getUiBackground(
                    "CityScreen/CityConstructionTable/QueueEntrySelected",
                    tintColor = highlightColor
                )
            else
                BaseScreen.skinStrings.getUiBackground(
                    "CityScreen/CityConstructionTable/QueueEntry",
                    tintColor = ImageGetter.CHARCOAL
                )
    }

    private fun getProgressBar(constructionName: String): Group {
        val cityConstructions = cityView.constructions
        val construction = cityConstructions.getConstruction(constructionName)
        if (construction is PerpetualConstruction) return Table()
        if (cityConstructions.getWorkDone(constructionName) == 0) return Table()

        val constructionPercentage = cityConstructions.getWorkDone(constructionName) /
                cityView.getConstructionProductionCost(construction as INonPerpetualConstruction).toFloat()
        return ImageGetter.getProgressBarVertical(2f, 30f, constructionPercentage,
                Color.BROWN.brighten(0.5f), Color.WHITE)
    }

    private fun getConstructionButton(constructionButtonDTO: ConstructionButtonDTO): Table {
        if (cityScreen.usesWorkbench) return getWorkbenchConstructionButton(constructionButtonDTO)
        val construction = constructionButtonDTO.construction
        val pickConstructionButton = Table().apply {
            name = "ui-construction:${construction.name}"
            isTransform = false
            align(Align.left).pad(5f)
            touchable = Touchable.enabled
        }

        highlightConstructionButton(pickConstructionButton, !isSelectedQueueEntry() && cityScreen.selectedConstruction == construction)

        val icon = ImageGetter.getConstructionPortrait(construction.name, 40f)
        pickConstructionButton.add(getProgressBar(construction.name)).padRight(5f)
        pickConstructionButton.add(icon).padRight(10f)

        val constructionTable = Table().apply { isTransform = false }
        val resourceTable = Table().apply { isTransform = false }

        val textColor = if (constructionButtonDTO.rejectionReason == null) Color.WHITE else Color.RED

        val statIcons = if (construction is Building)
            " " + Stat.entries.filter { cityView.isStatRelated(it, construction) }.map { it.character }.joinToString("")
        else ""

        val constructionNameText = "${construction.name.tr(hideIcons = true)}$statIcons"

        constructionTable.add(constructionNameText.toLabel(fontSize = UiTypography.size(TextRole.Control, unit = if (cityScreen.usesWorkbench) 1f else UiTypography.unitsPerPoint(cityScreen.stage), legacySize = if (cityScreen.usesWorkbench) 15 else Constants.defaultFontSize), fontColor = textColor, hideIcons = true).apply { wrap=true })
            .width(if (cityScreen.usesWorkbench) cityScreen.workbenchWidth - 110f else cityScreen.stage.width/5).expandX().left().row()

        resourceTable.add(constructionButtonDTO.buttonText.toLabel(fontSize = UiTypography.size(TextRole.Supporting, unit = if (cityScreen.usesWorkbench) 1f else UiTypography.unitsPerPoint(cityScreen.stage), legacySize = if (cityScreen.usesWorkbench) 12 else Constants.defaultFontSize)).apply { wrap = cityScreen.usesWorkbench }).apply {
            if (cityScreen.usesWorkbench) width(maxOf(44f, cityScreen.workbenchWidth - 120f - 48f *
                ((constructionButtonDTO.resourcesRequired?.size ?: 0) + cityView.getStockpiledResourceRequirements(construction).size)))
        }.expandX().left()
        if (constructionButtonDTO.resourcesRequired != null) {
            for ((resource, amount) in constructionButtonDTO.resourcesRequired) {
                val color = if (constructionButtonDTO.rejectionReason?.type == RejectionReasonType.ConsumesResources)
                    Color.RED else Color.WHITE
                resourceTable.add(amount.tr().toLabel(fontColor = color, role = TextRole.Body, unit = if (cityScreen.usesWorkbench) 1f else UiTypography.unitsPerPoint(cityScreen.stage))).expandX().left().padLeft(5f)
                resourceTable.add(ImageGetter.getResourcePortrait(resource, 15f)).padBottom(1f).padLeft(if (cityScreen.usesWorkbench) 8f else 0f)
            }
        }
        for ((resourceName, amount) in cityView.getStockpiledResourceRequirements(constructionButtonDTO.construction)) {
            val color = if (constructionButtonDTO.rejectionReason?.type == RejectionReasonType.ConsumesResources)
                Color.RED else Color.WHITE
            resourceTable.add(ColorMarkupLabel(amount.toString(), color, fontSize = UiTypography.size(TextRole.CompactData, unit = if (cityScreen.usesWorkbench) 1f else UiTypography.unitsPerPoint(cityScreen.stage)))).expandX().left().padLeft(5f)
            resourceTable.add(ImageGetter.getResourcePortrait(resourceName, 15f)).padBottom(1f).padLeft(if (cityScreen.usesWorkbench) 8f else 0f)
        }
        constructionTable.add(resourceTable).expandX().left()

        pickConstructionButton.add(constructionTable).expandX().left()

        if (!cannotAddConstructionToQueue(construction)) {
            val addToQueueButton = ImageGetter.getImage("OtherIcons/New")
                .apply { color = ImageGetter.CHARCOAL }.surroundWithCircle(40f)
            addToQueueButton.name = "ui-construction-add:${construction.name}"
            addToQueueButton.onClick(UncivSound.Silent) {
                // Since the pickConstructionButton.onClick adds the construction if it's selected,
                // this effectively adds the construction even if it's unselected
                cityScreen.selectConstruction(construction)
            }
            pickConstructionButton.add(addToQueueButton)
        }
        pickConstructionButton.row()

        // no rejection reason means we can build it!
        if (constructionButtonDTO.rejectionReason != null) {
            pickConstructionButton.color.a = 0.9f
            icon.color.a = 0.5f
            if (constructionButtonDTO.rejectionReason.type != RejectionReasonType.ConsumesResources) {
                pickConstructionButton.add(
                    ColorMarkupLabel(constructionButtonDTO.rejectionReason.errorMessage, Color.RED, fontSize = UiTypography.size(TextRole.Body, unit = if (cityScreen.usesWorkbench) 1f else UiTypography.unitsPerPoint(cityScreen.stage)))
                        .apply { wrap = true })
                    .colspan(pickConstructionButton.columns)
                    .width(if (cityScreen.usesWorkbench) cityScreen.workbenchWidth - 16f else cityScreen.stage.width/4).fillX().left().padTop(2f)
            }
        }

        pickConstructionButton.onClick {
            if (cityScreen.selectedConstruction == construction) {
                addConstructionToQueue(construction)
                if (cityScreen.usesWorkbench) cityScreen.clearSelection()
            } else {
                cityScreen.selectConstruction(construction)
                highlightConstructionButton(pickConstructionButton, true, true)  // without, will highlight but with visible delay
            }
            selectedQueueEntry = -1
            cityScreen.updateAsync()
        }

        if (!cityScreen.canCityBeChanged()) return pickConstructionButton

        pickConstructionButton.addContextMenu {
            if (cityScreen.selectedConstruction != construction) {
                // Ensure context is visible
                cityScreen.selectConstruction(construction)
                highlightConstructionButton(pickConstructionButton, true, true)
                cityScreen.updateWithoutConstructionAndMap()
            }
            CityScreenConstructionMenu(cityScreen.stage, pickConstructionButton, cityView, construction) {
                cityView.tryReassignPopulation()
                cityScreen.updateAsync()
            }
        }
        return pickConstructionButton
    }

    private fun highlightConstructionButton(
        pickConstructionButton: Table,
        highlight: Boolean,
        clearOthers: Boolean = false
    ) {
        val unselected by lazy {
            // Lazy because possibly not needed (highlight true, clearOthers false) and slightly costly
            BaseScreen.skinStrings.getUiBackground(
                "CityScreen/CityConstructionTable/PickConstructionButton",
                tintColor = ImageGetter.CHARCOAL
            )
        }

        pickConstructionButton.background =
            if (highlight)
                BaseScreen.skinStrings.getUiBackground(
                    "CityScreen/CityConstructionTable/PickConstructionButtonSelected",
                    tintColor = highlightColor
                )
            else unselected

        if (!clearOthers) return
        // Using knowledge about Widget hierarchy - Making the Buttons their own class might be a better design.
        for (categoryExpander in availableConstructionsTable.children.filterIsInstance<ExpanderTab>()) {
            if (!categoryExpander.isOpen) continue
            for (button in categoryExpander.innerTable.children.filterIsInstance<Table>()) {
                if (button == pickConstructionButton) continue
                button.background = unselected
            }
        }

        if (!isSelectedQueueEntry()) return
        // Same as above but worse - both buttons and headers are typed `Table`
        for (button in constructionsQueueTable.children.filterIsInstance<Table>()) {
            if (button.children.size == 1) continue // Skip headers, they only have 1 Label
            highlightQueueEntry(button, false)
        }
        selectedQueueEntry = -1
    }

    private fun isSelectedQueueEntry(): Boolean = selectedQueueEntry >= 0

    private fun cannotAddConstructionToQueue(construction: IConstruction): Boolean {
        val cityConstructions = cityView.constructions
        return cityConstructions.isQueueFull()
                || !cityScreen.canChangeState
                || cityView.isPuppet()
                || !cityConstructions.isBuildable(construction)
                || construction is PerpetualConstruction && cityConstructions.isBeingConstructedOrEnqueued(construction.name)
    }

    private fun addConstructionToQueue(construction: IConstruction) {
        // Some evil person decided to double tap real fast - #4977
        if (cannotAddConstructionToQueue(construction))
            return

        // UniqueType.CreatesOneImprovement support - don't add yet, postpone until target tile for the improvement is selected
        if (construction is Building && construction.hasCreateOneImprovementUnique()) {
            cityScreen.startPickTileForCreatesOneImprovement(construction, Stat.Gold, false)
            return
        }
        cityScreen.stopPickTileForCreatesOneImprovement()

        SoundPlayer.play(getConstructionSound(construction))

        cityView.tryAddToQueue(construction.name)
        if (!cityView.constructions.shouldBeDisplayed(construction)) // For buildings - unlike units which can be queued multiple times
            cityScreen.clearSelection()
        if (cityView.constructions.constructionQueue.first() == construction.name)
            cityView.tryReassignPopulation()
        cityScreen.game.settings.addCompletedTutorialTask("Pick construction")
    }

    private fun getConstructionSound(construction: IConstruction): UncivSound {
        return when(construction) {
            is Building -> UncivSound.Construction
            is BaseUnit -> UncivSound.Promote
            PerpetualConstruction.Gold -> UncivSound.Coin
            PerpetualConstruction.Science -> UncivSound.Paper
            PerpetualConstruction.Culture -> UncivSound.Policy
            PerpetualConstruction.Faith -> UncivSound.Choir
            else -> UncivSound.Click
        }
    }

    private fun getMovePriorityButton(
        arrowDirection: Int,
        binding: KeyboardBinding,
        constructionQueueIndex: Int,
        name: String,
        movePriority: (Int) -> Int?
    ): Table {
        val button = Table()
        if (cityScreen.usesWorkbench) button.add(ImageGetter.getArrowImage(arrowDirection).apply { setSize(20f, 20f); setOrigin(Align.center) }).size(20f)
        else button.add(ImageGetter.getArrowImage(arrowDirection).apply { color = ImageGetter.CHARCOAL }.surroundWithCircle(40f))
        button.touchable = Touchable.enabled
        // Don't bind the queue reordering keys here - those should affect only the selected entry, not all of them
        button.onActivation {
            button.touchable = Touchable.disabled
            selectedQueueEntry = movePriority(constructionQueueIndex) ?: return@onActivation
            // Selection display may need to update as I can click the button of a non-selected entry.
            if (cityScreen.usesWorkbench) cityScreen.clearSelection()
            else cityScreen.selectConstruction(name)
            cityView.tryReassignPopulation()
            cityScreen.updateAsync()
            //cityScreen.updateWithoutConstructionAndMap()
            updateQueueAndButtons(cityScreen.selectedConstruction)
            ensureQueueEntryVisible()  // Not passing current button info - already outdated, our parent is already removed from the stage hierarchy and replaced
        }
        if (selectedQueueEntry == constructionQueueIndex) {
            button.keyShortcuts.add(binding)  // This binds without automatic tooltip
            button.addTooltip(binding)
        }
        return button
    }

    private fun getRaisePriorityButton(constructionQueueIndex: Int, name: String) =
        getMovePriorityButton(Align.top, KeyboardBinding.RaisePriority, constructionQueueIndex, name, cityView::tryRaisePriority)

    private fun getLowerPriorityButton(constructionQueueIndex: Int, name: String) =
        getMovePriorityButton(Align.bottom, KeyboardBinding.LowerPriority, constructionQueueIndex, name, cityView::tryLowerPriority)

    private fun getRemoveFromQueueButton(constructionQueueIndex: Int): Table {
        val tab = Table()
        tab.name = "ui-construction-remove:$constructionQueueIndex"
        if (cityScreen.usesWorkbench) tab.add(ImageGetter.getImage("OtherIcons/Close", ExpeditionBlueTheme.text)).size(20f)
        else tab.add(ImageGetter.getImage("OtherIcons/Stop").surroundWithCircle(40f))
        tab.touchable = Touchable.enabled
        tab.onClick {
            if (cityScreen.usesWorkbench) { removeWorkbenchQueueEntry(constructionQueueIndex); return@onClick }
            tab.touchable = Touchable.disabled
            Concurrency.run {
                val success = cityView.tryRemoveFromQueue(constructionQueueIndex, false)
                if (!success) {
                    Concurrency.runOnGLThread { cityScreen.updateAsync() }
                    return@run
                }
                cityView.tryReassignPopulation()
                Concurrency.runOnGLThread {
                    cityScreen.clearSelection()
                    // Select next entry in list if available.
                    // If the last one was deleted, select the new last one.
                    if (cityScreen.usesWorkbench) cityScreen.updateAsync()
                    else selectQueueEntry(constructionQueueIndex.coerceAtMost(cityView.constructions.constructionQueue.lastIndex)) { }
                }
            }
        }
        return tab
    }

    private fun getHeader(title: String): Table {
        return Table()
                .background(
                    BaseScreen.skinStrings.getUiBackground(
                        "CityScreen/CityConstructionTable/Header",
                        tintColor = BaseScreen.skinStrings.skinConfig.baseColor
                    )
                )
                .addCell(title.toLabel(fontSize = UiTypography.size(TextRole.PageTitle, unit = if (cityScreen.usesWorkbench) 1f else UiTypography.unitsPerPoint(cityScreen.stage), legacySize = Constants.headingFontSize)))
                .pad(4f)
    }

    private fun ensureQueueEntryVisible() {
        getSelectedQueueButton()?.let {
            constructionsQueueScrollPane.scrollTo(2f, it.y, it.width, it.height, true, true)
        }
    }

    /** The queue widgets can be out of sync with the construction queue when this is called:
     *  [selectQueueEntry] triggers the *asynchronous* [CityScreen.updateAsync] and then scrolls to the
     *  selected entry, so the cells read here are still the ones of the previous layout - and they are
     *  not even guaranteed to exist, as this table is only populated by [updateConstructionQueue].
     *  Therefore: check the bounds instead of indexing blindly, a missing button simply means no scrolling. */
    private fun getSelectedQueueButton(): Actor? {
        if (selectedQueueEntry == 0) {
            val cells = constructionsQueueTable.cells
            if (cells.size == 0) return null
            return cells[0].actor
        }
        if (selectedQueueEntry > 0 && selectedQueueEntry < cityView.constructions.constructionQueue.size) {
            // *2 because it's always the entry and a separator
            val cells = queueExpander.innerTable.cells
            val index = selectedQueueEntry * 2 - 2
            if (index >= cells.size) return null
            return cells[index].actor
        }
        return null
    }

    private fun resizeAvailableConstructionsScrollPane() {
        availableConstructionsScrollPane.height =
            min(availableConstructionsTable.prefHeight, lowerTableScrollCell.maxHeight)
        lowerTable.pack()
    }

    private fun Table.addCategory(
        title: String,
        list: ArrayList<Table>,
        prefWidth: Float,
        toggleKey: KeyboardBinding,
        startsOutOpened: Boolean = !cityScreen.isCrampedPortrait()
    ) {
        if (list.isEmpty()) return

        if (rows > 0) addSeparator()
        val expander = ExpanderTab(
            title,
            startsOutOpened = startsOutOpened,
            defaultPad = 0f,
            expanderWidth = prefWidth,
            persistenceID = "CityConstruction.$title",
            toggleKey = toggleKey,
            onChange = { resizeAvailableConstructionsScrollPane() }
        ) {
            for (table in list) {
                it.addSeparator(colSpan = 1)
                it.add(table).left().row()
            }
        }
        expander.header.name = "ui-construction-category:$title"
        add(expander).prefWidth(prefWidth).growX().row()
    }
}
