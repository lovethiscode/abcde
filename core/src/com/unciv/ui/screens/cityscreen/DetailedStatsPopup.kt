package com.unciv.ui.screens.cityscreen

import com.unciv.ui.components.fonts.UiTypography
import com.unciv.ui.components.fonts.TextRole
import com.unciv.models.skins.ExpeditionBlueTheme
import com.badlogic.gdx.Input
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.unciv.Constants
import com.unciv.ui.components.input.onChange
import com.unciv.ui.components.input.activate
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.utils.Align
import com.unciv.logic.city.StatTreeNode
import com.unciv.models.stats.Stat
import com.unciv.models.stats.Stats
import com.unciv.models.translations.tr
import com.unciv.ui.components.widgets.AutoScrollPane
import com.unciv.ui.components.input.KeyCharAndCode
import com.unciv.ui.components.extensions.addSeparator
import com.unciv.ui.components.extensions.brighten
import com.unciv.ui.components.extensions.darken
import com.unciv.ui.components.input.keyShortcuts
import com.unciv.ui.components.input.onActivation
import com.unciv.ui.components.input.onClick
import com.unciv.ui.components.extensions.packIfNeeded
import com.unciv.ui.components.extensions.pad
import com.unciv.ui.components.extensions.surroundWithCircle
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.extensions.setTextRole
import com.unciv.ui.components.input.KeyboardBinding
import com.unciv.ui.components.widgets.IconCircleGroup
import com.unciv.ui.popups.Popup
import com.unciv.ui.screens.basescreen.BaseScreen
import kotlin.math.max

class DetailedStatsPopup(
    private val cityScreen: CityScreen
) : Popup(cityScreen) {
    private var embedded = false
    private val headerTable = Table()
    private val totalTable = Table()

    private var sourceHighlighted: String? = null
    private var onlyWithStat: Stat? = null
    private var isDetailed: Boolean = false

    private val colorTotal: Color = ExpeditionBlueTheme.color(Color.BLUE.brighten(0.5f), ExpeditionBlueTheme.raised)
    private val colorSelector: Color = ExpeditionBlueTheme.color(Color.GREEN.darken(0.5f), ExpeditionBlueTheme.raised)

    private val settings get() = cityScreen.game.settings
    private val percentFormatter = settings.getAndModifyCurrentNumberFormat {
        applyPattern(settings.getCurrentPercentPattern())
        positivePrefix = "+"
        multiplier = 1
        maximumFractionDigits = 1
    }
    private val decimalFormatter = settings.getAndModifyCurrentNumberFormat {
        maximumFractionDigits = 1
    }

    init {
        headerTable.defaults().pad(3f, 0f)
        totalTable.defaults().pad(3f, 0f)
        if (ordinaryPresentation) {
            addDialogTitle(cityScreen.cityView.name)
            val matrix = Table().apply {
                add(headerTable).growX().row()
                add(totalTable).growX()
            }
            val scrollPane = AutoScrollPane(matrix).apply {
                setOverscroll(false, false)
                // The matrix needs horizontal panning on a phone; vertical overflow
                // remains owned by Popup's single body scroll view.
                setScrollingDisabled(false, true)
            }
            add(scrollPane).width(popupContentWidth).padTop(0f).row()
        } else {
            add(headerTable).padBottom(0f).row()
            val scrollPane = AutoScrollPane(totalTable)
            scrollPane.setOverscroll(false, false)
            add(scrollPane).padTop(0f).maxHeight(cityScreen.stage.height * 3 / 4)
            row()
            addCloseButton(additionalKey = KeyCharAndCode.SPACE)
        }
        update()

        showListeners.add { cityScreen.pauseFireworks = true }
        closeListeners.add { cityScreen.pauseFireworks = false }
    }

    internal fun workbenchContent(
        initialStat: Stat? = Stat.Production,
        initialDetailed: Boolean = false,
        onSelection: (Stat?, Boolean) -> Unit = { _, _ -> }
    ): Table {
        embedded = true
        onlyWithStat = initialStat
        isDetailed = initialDetailed
        val combined = Table().apply { top().left() }
        val matrixPane = AutoScrollPane(combined).apply { setOverscroll(false, false) }
        val filters = cityWorkbenchSelectBox()
        val filterStats = Stat.entries.filter { it != Stat.Faith || cityScreen.cityView.viewingCiv().isReligionEnabled() }
        filters.setItems(*listOf("All".tr()).plus(filterStats.map { cityWorkbenchIconText(it.name.tr(hideIcons = true)) }).toTypedArray())
        filters.selectedIndex = if (onlyWithStat == null) 0 else filterStats.indexOf(onlyWithStat) + 1
        filters.name = "ui-city-stat-filter"
        lateinit var toggle: com.badlogic.gdx.scenes.scene2d.ui.TextButton
        fun refresh() {
            update()
            combined.clear()
            if (onlyWithStat == null) {
                combined.add("Swipe horizontally to view all resources.".toLabel(TextRole.Supporting, unit = 1f).apply { wrap = true })
                    .width(cityScreen.workbenchManagementWidth).left().padBottom(8f).row()
            }
            if (onlyWithStat == null) combined.add(headerTable).growX().row()
            else {
                val stat = onlyWithStat!!
                val amount = if (stat == Stat.Happiness) cityScreen.cityView.getHappinessList().values.sum()
                    else cityScreen.cityView.getCurrentCityStats()[stat]
                val summary = Table().apply {
                    add(stat.name.toLabel(TextRole.Body, unit = 1f)).growX().left()
                    add(amount.toOneDecimalLabel().apply { setTextRole(TextRole.SectionTitle, unit = 1f); color = ExpeditionBlueTheme.gold }).right()
                }
                combined.add(summary).width(cityScreen.workbenchManagementWidth).minHeight(32f).padBottom(4f).row()
            }
            combined.add(totalTable).growX().row()
            matrixPane.setScrollingDisabled(onlyWithStat != null, false)
            toggle.setText((if (isDetailed) "Collapse sources" else "Expand sources").tr())
            onSelection(onlyWithStat, isDetailed)
        }
        filters.onChange { onlyWithStat = filterStats.getOrNull(filters.selectedIndex - 1); refresh() }
        toggle = cityWorkbenchControl(if (isDetailed) "Collapse sources" else "Expand sources", "ui-city-stat-details") {
            isDetailed = !isDetailed
            refresh()
        }
        val controls = Table().apply {
            add(filters).growX().minWidth(0f).minHeight(44f).padRight(8f)
            add(toggle).minHeight(44f)
        }
        refresh()
        return Table().apply {
            top()
            add(controls).growX().padLeft(12f).padRight(12f).row()
            add(matrixPane).grow().minSize(0f).pad(8f, 12f, 8f, 12f)
        }
    }

    private fun update() {
        headerTable.clear()
        totalTable.clear()
        if (embedded) { headerTable.defaults().pad(0f); totalTable.defaults().pad(0f) }

        val cityView = cityScreen.cityView
        val showFaith = cityView.viewingCiv().isReligionEnabled()

        val stats = when {
            onlyWithStat != null -> listOfNotNull(onlyWithStat)
            !showFaith -> Stat.entries.filter { it != Stat.Faith }
            else -> Stat.entries
        }
        val columnCount = stats.size + 1
        val statColMinWidth = if (embedded) if (onlyWithStat != null) 88f else 65f else if (onlyWithStat != null) 150f else 110f

        if (embedded) headerTable.add("Source".toLabel(TextRole.Control, unit = 1f))
            .width(if (onlyWithStat != null) (cityScreen.workbenchManagementWidth - 88f).coerceAtLeast(120f) else 144f).left().minHeight(44f)
        else headerTable.add(getToggleButton(isDetailed)).minWidth(150f).grow()

        for (stat in stats) {
            val label = stat.name.toLabel(fontSize = UiTypography.size(TextRole.Supporting, unit = if (embedded) 1f else UiTypography.unitsPerPoint(cityScreen.stage), legacySize = if (embedded) 13 else Constants.defaultFontSize))
            if (!embedded) label.onClick {
                onlyWithStat = if (onlyWithStat == null) stat else null
                update()
            }
            headerTable.add(wrapInTable(label, if (onlyWithStat == stat) colorSelector else null))
                .minWidth(statColMinWidth).grow()
        }
        headerTable.row()
        headerTable.addSeparator().padBottom(2f)

        totalTable.add("Base values".toLabel(fontSize = UiTypography.size(TextRole.Supporting, unit = if (embedded) 1f else UiTypography.unitsPerPoint(cityScreen.stage), legacySize = if (embedded) 13 else Constants.defaultFontSize)).apply { setAlignment(Align.center) })
            .colspan(columnCount).growX().row()
        totalTable.addSeparator(colSpan = columnCount).padTop(2f)
        traverseTree(totalTable, stats, cityView.getBaseStatTree(), mergeHappiness = true, percentage = false)

        totalTable.addSeparator().padBottom(2f)
        totalTable.add("Bonuses".toLabel(fontSize = UiTypography.size(TextRole.Supporting, unit = if (embedded) 1f else UiTypography.unitsPerPoint(cityScreen.stage), legacySize = if (embedded) 13 else Constants.defaultFontSize)).apply { setAlignment(Align.center) })
            .colspan(columnCount).growX().row()
        totalTable.addSeparator().padTop(2f)
        traverseTree(totalTable, stats, cityView.getStatPercentBonusTree(), percentage = true)

        totalTable.addSeparator().padBottom(2f)
        totalTable.add("Final".toLabel(fontSize = UiTypography.size(TextRole.Supporting, unit = if (embedded) 1f else UiTypography.unitsPerPoint(cityScreen.stage), legacySize = if (embedded) 13 else Constants.defaultFontSize)).apply { setAlignment(Align.center) })
            .colspan(columnCount).growX().row()
        totalTable.addSeparator().padTop(2f)

        val final = LinkedHashMap<Stat, Float>()
        val map = cityView.getFinalStatList().toSortedMap()

        for ((key, value) in cityView.getHappinessList()) {
            if (!map.containsKey(key)) {
                map[key] = Stats(happiness = value)
            } else if (map[key]!![Stat.Happiness] == 0f) {
                map[key]!![Stat.Happiness] = value
            }
        }

        for ((source, finalStats) in map) {

            if (finalStats.isEmpty())
                continue

            if (onlyWithStat != null && finalStats[onlyWithStat!!] == 0f)
                continue

            val label = source.toLabel(fontSize = UiTypography.size(TextRole.Supporting, unit = if (embedded) 1f else UiTypography.unitsPerPoint(cityScreen.stage), legacySize = if (embedded) 13 else Constants.defaultFontSize), hideIcons = true).apply {
                setAlignment(Align.left)
                onClick {
                    sourceHighlighted = if (sourceHighlighted == source) null else source
                    update()
                }
            }

            val color = colorSelector.takeIf { sourceHighlighted == source }
            totalTable.add(wrapInTable(label, color, Align.left)).grow()

            for (stat in stats) {
                val value = finalStats[stat]
                val cell = when (value) {
                    0f -> "-".toLabel(fontSize = UiTypography.size(TextRole.Supporting, unit = if (embedded) 1f else UiTypography.unitsPerPoint(cityScreen.stage), legacySize = if (embedded) 13 else Constants.defaultFontSize))
                    else -> value.toOneDecimalLabel()
                }

                totalTable.add(wrapInTable(cell, color)).grow()

                var f = final[stat]
                if (f == null)
                    f = 0f
                f += value
                final[stat] = f

            }
            totalTable.row()
        }

        totalTable.add(wrapInTable("Total".toLabel(fontSize = UiTypography.size(TextRole.Supporting, unit = if (embedded) 1f else UiTypography.unitsPerPoint(cityScreen.stage), legacySize = if (embedded) 13 else Constants.defaultFontSize)), colorTotal)).grow()
        for (stat in stats) {
            totalTable.add(wrapInTable(final[stat]?.toOneDecimalLabel(), colorTotal))
                .minWidth(statColMinWidth).grow()
        }
        totalTable.row()

        // Measure after spacing icon glyphs, then share every column width with the header.
        if (embedded) {
            spaceCityWorkbenchIcons(headerTable)
            spaceCityWorkbenchIcons(totalTable)
        }
        headerTable.packIfNeeded()
        totalTable.packIfNeeded()
        for (column in 0 until columnCount) {
            val columnWidth = max(totalTable.getColumnWidth(column), headerTable.getColumnWidth(column))
            headerTable.cells.filter { it.column == column && it.colspan == 1 }.forEach { it.width(columnWidth) }
            totalTable.cells.filter { it.column == column && it.colspan == 1 }.forEach { it.width(columnWidth) }
        }
        headerTable.invalidate()
        totalTable.invalidate()
    }

    private fun getToggleButton(showDetails: Boolean): IconCircleGroup<IconCircleGroup<Label>> {
        val label = (if (showDetails) "-" else "+").toLabel(fontSize = UiTypography.size(TextRole.Supporting, unit = if (embedded) 1f else UiTypography.unitsPerPoint(cityScreen.stage), legacySize = if (embedded) 13 else Constants.defaultFontSize))
        label.setAlignment(Align.center)
        val button = label
            .surroundWithCircle(25f, color = BaseScreen.skinStrings.skinConfig.baseColor)
            .surroundWithCircle(27f, false)
        button.onActivation(binding = KeyboardBinding.ShowStatDetails) {
            isDetailed = !isDetailed
            update()
        }
        button.keyShortcuts.add(Input.Keys.PLUS)  //todo Choose alternative (alt binding, remove, auto-equivalence, multikey bindings)
        return button
    }

    private fun traverseTree(
        table: Table,
        stats: List<Stat>,
        statTreeNode: StatTreeNode,
        mergeHappiness: Boolean = false,
        percentage: Boolean = false,
        indentation: Int = 0
    ) {

        val total = LinkedHashMap<Stat, Float>()
        val map = statTreeNode.children.toSortedMap()

        if (mergeHappiness) {
            for ((key, value) in cityScreen.cityView.getHappinessList()) {
                if (!map.containsKey(key)) {
                    map[key] = StatTreeNode()
                    map[key]?.setInnerStat(Stat.Happiness, value)
                } else if (map[key]!!.totalStats.happiness == 0f) {
                    map[key]?.setInnerStat(Stat.Happiness, value)
                }
            }
        }

        for ((name, child) in map) {

            val text = "- ".repeat(indentation) + name.tr()

            if (child.totalStats.all { it.value == 0f }) {
                table.row()
                continue
            }

            if (onlyWithStat != null && child.totalStats[onlyWithStat!!] == 0f) {
                table.row()
                continue
            }

            val label = text.toLabel(fontSize = UiTypography.size(TextRole.Supporting, unit = if (embedded) 1f else UiTypography.unitsPerPoint(cityScreen.stage), legacySize = if (embedded) 13 else Constants.defaultFontSize), hideIcons = true).apply {
                setAlignment(Align.left)
                onClick {
                    sourceHighlighted = if (sourceHighlighted == text) null else text
                    update()
                }
            }

            var color: Color? = null

            if (sourceHighlighted == text)
                color = colorSelector

            table.add(wrapInTable(label, color, Align.left)).fill().left()

            for (stat in stats) {
                val value = child.totalStats[stat]
                val cell = when {
                    value == 0f -> "-".toLabel(fontSize = UiTypography.size(TextRole.Supporting, unit = if (embedded) 1f else UiTypography.unitsPerPoint(cityScreen.stage), legacySize = if (embedded) 13 else Constants.defaultFontSize))
                    percentage ->  value.toPercentLabel()
                    else -> value.toOneDecimalLabel()
                }

                table.add(wrapInTable(cell, color)).grow()

                if (indentation == 0) {
                    var current = total[stat]
                    if (current == null)
                        current = 0f
                    total[stat] = current + value
                }
            }

            table.row()
            if (isDetailed)
                traverseTree(table, stats, child, percentage = percentage, indentation = indentation + 1)

        }

        if (indentation == 0) {
            table.add(wrapInTable("Total".toLabel(fontSize = UiTypography.size(TextRole.Supporting, unit = if (embedded) 1f else UiTypography.unitsPerPoint(cityScreen.stage), legacySize = if (embedded) 13 else Constants.defaultFontSize)), colorTotal)).grow()
            for (stat in stats) {
                if (percentage)
                    table.add(wrapInTable(total[stat]?.toPercentLabel(), colorTotal)).grow()
                else
                    table.add(wrapInTable(total[stat]?.toOneDecimalLabel(), colorTotal)).grow()
            }
            table.row()
        }

    }

    private fun wrapInTable(label: Label?, color: Color? = null, align: Int = Align.center) : Table {
        val tbl = Table()
        label?.setAlignment(align)
        if (ExpeditionBlueTheme.enabled && color === colorTotal)
            label?.color = ExpeditionBlueTheme.gold
        if (color != null)
            tbl.background = BaseScreen.skinStrings.getUiBackground("General/Border", tintColor = color)
        if (embedded && align == Align.left && label != null) {
            label.wrap = true
            tbl.add(label).width(if (onlyWithStat != null) (cityScreen.workbenchManagementWidth - 96f).coerceAtLeast(112f) else 136f).growX()
        } else tbl.add(label).growX()
        if (embedded) {
            tbl.pad(4f)
            tbl.cells.forEach { it.minHeight(36f) }
            if (align == Align.left && label != null) {
                label.touchable = Touchable.disabled
                tbl.onActivation { label.activate() }
            }
        }
        return tbl
    }

    private fun Float.toPercentLabel() = percentFormatter.format(this).toLabel(fontSize = UiTypography.size(TextRole.Supporting, unit = if (embedded) 1f else UiTypography.unitsPerPoint(cityScreen.stage), legacySize = if (embedded) 13 else Constants.defaultFontSize))
    private fun Float.toOneDecimalLabel() = decimalFormatter.format(this).toLabel(fontSize = UiTypography.size(TextRole.Supporting, unit = if (embedded) 1f else UiTypography.unitsPerPoint(cityScreen.stage), legacySize = if (embedded) 13 else Constants.defaultFontSize))
}
