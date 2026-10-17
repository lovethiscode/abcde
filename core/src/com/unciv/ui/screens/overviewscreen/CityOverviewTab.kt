package com.unciv.ui.screens.overviewscreen

import com.unciv.ui.components.fonts.UiTypography
import com.unciv.ui.components.fonts.TextRole
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.utils.Align
import com.unciv.models.ruleset.PerpetualConstruction
import com.unciv.ui.screens.overviewscreen.OverviewUi.pt
import com.unciv.ui.screens.overviewscreen.OverviewUi.font
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.unciv.logic.city.City
import com.unciv.models.translations.tr
import com.unciv.ui.components.ISortableGridContentProvider
import com.unciv.ui.components.UncivTooltip.Companion.addTooltip
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.input.onChange
import com.unciv.ui.components.widgets.SortableGrid
import com.unciv.ui.components.widgets.TabbedPager
import com.unciv.ui.components.widgets.UncivTextField
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.screens.overviewscreen.OverviewUi.detailRow
import com.unciv.view.CivView

private typealias CityColumn = ISortableGridContentProvider<City, EmpireOverviewScreen>

/** Fixed city identity, column headings and totals; only the measures scroll horizontally. */
class CityOverviewTab(
    viewingPlayer: CivView,
    overviewScreen: EmpireOverviewScreen,
    persistedData: EmpireOverviewTabPersistableData? = null
) : EmpireOverviewTab(viewingPlayer, overviewScreen) {
    class CityTabPersistableData : EmpireOverviewTabPersistableData(), SortableGrid.ISortState<CityColumn> {
        override var sortedBy: CityColumn = CityOverviewTabColumn.CityColumn
        override var direction = SortableGrid.SortDirection.None
        @Transient var query = ""
        @Transient var scrollX = 0f
        @Transient var scrollY = 0f
        @Transient var selectedCityId: String? = null
        @Transient var detailsCityId: String? = null
        override fun isEmpty() = sortedBy == CityOverviewTabColumn.CityColumn && direction != SortableGrid.SortDirection.Descending
    }
    override val persistableData = (persistedData as? CityTabPersistableData) ?: CityTabPersistableData()
    override val usesOwnScroll = true
    private val content = Table()
    private val cityNames = Table().apply { top() }
    private val values = Table().apply { top() }
    private val headings = Table()
    private val totals = Table()
    private val nameHeading = Table()
    private val nameTotal = Table()
    private val headerScroll = TabbedPager.LinkedScrollPane(true, headings)
    private val valuesScroll = TabbedPager.LinkedScrollPane(true, values, headerScroll)
    private val totalsScroll = TabbedPager.LinkedScrollPane(true, totals, headerScroll)
    private val body = Table().apply { top() }
    private val bodyScroll = OverviewUi.scroll(body).apply { setScrollbarsOnTop(true) }
    private val nameWidth = pt(235f)
    private val table = Table()
    private val inspector = Table()
    private val columns: List<CityColumn> = CityOverviewTabColumn.getColumns(viewingPlayer.getCiv())
        .filter { it.isVisible(viewingPlayer) && it !in listOf(CityOverviewTabColumn.CityColumn,
            CityOverviewTabColumn.Status, CityOverviewTabColumn.ConstructionIcon) }
        .sortedBy { column ->
            val order = listOf(CityOverviewTabColumn.Population, CityOverviewTabColumn.Construction,
                CityOverviewTabColumn.Food, CityOverviewTabColumn.Production, CityOverviewTabColumn.Gold,
                CityOverviewTabColumn.Science, CityOverviewTabColumn.Culture, CityOverviewTabColumn.Happiness,
                CityOverviewTabColumn.Faith)
            order.indexOf(column).takeIf { it >= 0 } ?: order.size
        }

    init {
        name = "overview-cities"
        valuesScroll.linkedScrolls += totalsScroll
        totalsScroll.linkedScrolls += valuesScroll
        val search = UncivTextField("Search cities", persistableData.query).apply { name = "overview-city-search" }
        OverviewUi.styleSearch(search)
        search.width = pt(158f)
        search.onChange { persistableData.query = search.text; rebuildRows() }
        val civ = viewingPlayer.getCiv()
        val header = Table().apply {
            pad(0f, pt(12f), 0f, pt(12f))
            add(OverviewUi.plainLabel("[${civ.cities.size}] cities · [${civ.cities.sumOf { it.population.population }}] population", TextRole.Supporting, OverviewUi.muted)
                .apply { setEllipsis(true) }).growX().minWidth(0f).left()
            add(ImageGetter.getImage("OtherIcons/Search").apply { color = OverviewUi.muted })
                .size(pt(16f)).padLeft(pt(16f)).padRight(pt(8f))
            add(search).width(pt(142f)).height(OverviewUi.touch)
        }
        table.background = OverviewUi.rounded(OverviewUi.panel, 10f)
        layoutCityTable()
        val panel = Table().apply {
            background = OverviewUi.rounded(OverviewUi.panel, 10f)
            add(header).growX().height(OverviewUi.touch).row()
            add(ImageGetter.getWhiteDot().apply { color = OverviewUi.border }).growX().height(pt(1f)).row()
            add(content).grow().minHeight(0f)
        }
        add(panel).width(overviewScreen.contentWidth).height(overviewScreen.centerAreaHeight - pt(8f)).padTop(pt(8f))
        row()
        rebuildRows()
        val selected = civ.cities.firstOrNull { it.id == persistableData.detailsCityId }
        if (selected != null) showDetails(selected)
        bodyScroll.scrollY = persistableData.scrollY
        headerScroll.scrollX = persistableData.scrollX
        valuesScroll.scrollX = persistableData.scrollX
        totalsScroll.scrollX = persistableData.scrollX
    }

    private fun layoutCityTable() {
        val detailsOpen = persistableData.detailsCityId != null
        val scrollY = bodyScroll.scrollY
        table.clearChildren()
        body.clearChildren()
        table.add(nameHeading).width(nameWidth).height(OverviewUi.touch)
        if (!detailsOpen) table.add(headerScroll).growX().minWidth(0f).height(OverviewUi.touch)
        table.row()
        body.add(cityNames).width(nameWidth).top()
        if (!detailsOpen) body.add(valuesScroll).growX().minWidth(0f).top()
        table.add(bodyScroll).colspan(if (detailsOpen) 1 else 2).grow().minWidth(0f).row()
        table.add(nameTotal).width(nameWidth).height(pt(36f))
        if (!detailsOpen) table.add(totalsScroll).growX().minWidth(0f).height(pt(36f))
        content.clearChildren()
        if (detailsOpen) {
            if (!overviewScreen.compact) content.add(table).width(nameWidth).growY()
            content.add(OverviewUi.scroll(inspector)).grow().minWidth(0f).padLeft(pt(12f))
        } else content.add(table).grow().minWidth(0f)
        bodyScroll.scrollY = scrollY
        bodyScroll.updateVisualScroll()
    }

    private fun width(column: CityColumn) = when (column) {
        CityOverviewTabColumn.Construction -> pt(156f)
        CityOverviewTabColumn.Population -> pt(84f)
        CityOverviewTabColumn.CityDefense -> pt(100f)
        else -> pt(64f)
    }

    private fun caption(column: CityColumn) = when (column) {
        CityOverviewTabColumn.CityDefense -> "City defense"
        CityOverviewTabColumn.Religion -> "Religion"
        CityOverviewTabColumn.Garrison -> "Garrisoned unit"
        CityOverviewTabColumn.WLTK -> "We Love The King Day"
        else -> column.headerTip
    }

    private fun sortButton(column: CityColumn, title: String = caption(column)) = OverviewUi.button(flat = true) {
        persistableData.direction = if (persistableData.sortedBy == column && persistableData.direction == SortableGrid.SortDirection.Descending)
            SortableGrid.SortDirection.Ascending else SortableGrid.SortDirection.Descending
        persistableData.sortedBy = column
        rebuildRows()
    }.apply {
        val arrow = if (persistableData.sortedBy != column || persistableData.direction == SortableGrid.SortDirection.None) ""
            else if (persistableData.direction == SortableGrid.SortDirection.Descending) " ↓" else " ↑"
        if (column == CityOverviewTabColumn.CityColumn) {
            add(OverviewUi.plainLabel(title, TextRole.Supporting, OverviewUi.muted).apply {
                setText(text.toString() + arrow)
                setAlignment(Align.left)
            }).growX().pad(0f, pt(12f), 0f, pt(12f))
        } else {
            add().width(pt(12f)).padRight(pt(4f))
            add(column.getHeaderActor(pt(28f))).size(pt(28f))
            add(OverviewUi.plainLabel(arrow.trim(), TextRole.Supporting, OverviewUi.accent)).width(pt(12f)).padLeft(pt(4f))
            pad(0f, pt(2f), 0f, pt(2f))
            addTooltip(title, font(TextRole.Supporting).toFloat(), hideIcons = true)
        }
        name = "city-sort-$title"
    }

    private fun rebuildRows() {
        val y = bodyScroll.scrollY
        val x = valuesScroll.scrollX
        val query = persistableData.query.trim()
        val filtered = viewingPlayer.getCiv().cities.filter {
            it.name.tr(hideIcons = true).contains(query, ignoreCase = true) || it.name.contains(query, ignoreCase = true)
        }
        val cities = if (persistableData.direction == SortableGrid.SortDirection.None) filtered else {
            val comparator = persistableData.sortedBy.getComparator()
            filtered.sortedWith(if (persistableData.direction == SortableGrid.SortDirection.Descending) Comparator { a, b -> comparator.compare(b, a) } else comparator)
        }
        body.background = OverviewUi.surface(OverviewUi.border.cpy().apply { a = .47f })
        values.background = body.background
        cityNames.background = body.background
        cityNames.clear(); values.clear(); headings.clear(); totals.clear(); nameTotal.clear()
        nameHeading.clear()
        nameHeading.background = OverviewUi.surface(OverviewUi.raised)
        nameHeading.add(sortButton(CityOverviewTabColumn.CityColumn, "Cities")).grow()
        for (column in columns) {
            headings.add(sortButton(column)).width(width(column)).height(OverviewUi.touch)
            val total = if (column == CityOverviewTabColumn.Construction) "City production total".toLabel(TextRole.Body) else column.getTotalsActor(cities)
            if (total is Label) {
                total.color = OverviewUi.accent
                total.setAlignment(Align.center)
                total.setFontScale(font(TextRole.CompactData) / com.unciv.ui.components.fonts.Fonts.ORIGINAL_FONT_SIZE)
            }
            totals.add(Table().apply { add(total).growX().minWidth(0f).pad(0f, pt(8f), 0f, pt(8f)) })
                .width(width(column)).height(pt(36f))
        }
        headings.background = OverviewUi.surface(OverviewUi.raised)
        totals.background = OverviewUi.surface(OverviewUi.raised)
        nameTotal.left()
        nameTotal.background = OverviewUi.surface(OverviewUi.raised)
        nameTotal.add("{Total} ${cities.size} {Cities}".toLabel(fontColor = OverviewUi.accent, fontSize = font(TextRole.Supporting), hideIcons = true)).left().padLeft(pt(12f))
        for (city in cities) {
            val identity = Table().apply {
                background = OverviewUi.surface(if (city.id == persistableData.selectedCityId) OverviewUi.selected else OverviewUi.panel)
            }
            val cityName = OverviewUi.button(city.id == persistableData.selectedCityId, flat = true) {
                persistableData.selectedCityId = city.id
                rebuildRows()
            }.apply {
                name = "city-${city.id}"
                if (city.isCapital()) add(ImageGetter.getImage("OtherIcons/Capital").apply { color = OverviewUi.accent }).size(pt(14f)).padRight(pt(8f))
                add(city.name.toLabel(fontSize = font(TextRole.Control), hideIcons = true).apply { setEllipsis(true) }).minWidth(0f).growX().left()
                pad(0f, pt(12f), 0f, 0f)
            }
            identity.add(cityName).grow().minWidth(0f)
            identity.add(OverviewUi.infoButton {
                persistableData.selectedCityId = city.id
                showDetails(city)
            }.apply {
                name = "city-details-${city.id}"
            }).width(OverviewUi.touch).growY()
            identity.add(ImageGetter.getWhiteDot().apply { color = OverviewUi.border }).width(pt(1f)).growY()
            identity.add(OverviewUi.locateButton { locateCity(city) }.apply {
                name = "city-locate-${city.id}"
            }).width(OverviewUi.touch).growY()
            cityNames.add(identity).width(nameWidth).height(OverviewUi.rowHeight).padBottom(pt(1f)).row()
            for (column in columns) {
                val actor = if (column == CityOverviewTabColumn.Construction) {
                    val construction = city.cityConstructions.currentConstructionName()
                    val text = when {
                        construction.isEmpty() -> "Pick construction".tr()
                        construction in PerpetualConstruction.perpetualConstructionsMap -> construction.tr(hideIcons = true)
                        else -> construction.tr(hideIcons = true) + "  " + "[${city.cityConstructions.turnsToConstruction(construction)}] turns".tr()
                    }
                    text.toLabel(hideIcons = true, role = TextRole.Body)
                } else column.getEntryActor(city, pt(20f), overviewScreen)
                if (actor is Label) {
                    actor.setFontScale(font(TextRole.Control) / com.unciv.ui.components.fonts.Fonts.ORIGINAL_FONT_SIZE)
                    actor.setEllipsis(true)
                    actor.setAlignment(Align.center)
                    if (column == CityOverviewTabColumn.Happiness && column.getEntryValue(city) < 0) actor.color = OverviewUi.danger
                }
                val cell = Table().apply {
                    background = OverviewUi.surface(if (city.id == persistableData.selectedCityId) OverviewUi.selected else OverviewUi.panel)
                    val entryCell = add(actor).pad(8f)
                    if (actor is Label) entryCell.growX().minWidth(0f)
                }
                values.add(cell).width(width(column)).height(OverviewUi.rowHeight).padBottom(pt(1f))
            }
            values.row()
        }
        if (cities.isEmpty()) cityNames.add("No matching cities".toLabel(fontSize = UiTypography.size(TextRole.Body, legacySize = 18))).width(nameWidth).pad(12f)
        bodyScroll.layout()
        bodyScroll.scrollY = y
        bodyScroll.updateVisualScroll()
        for (scroll in listOf(headerScroll, valuesScroll, totalsScroll)) { scroll.scrollX = x; scroll.updateVisualScroll() }
    }

    private fun showDetails(city: City) {
        persistableData.detailsCityId = city.id
        inspector.clear()
        inspector.top().pad(pt(12f))
        inspector.background = OverviewUi.rounded(OverviewUi.panel, 10f)
        val close = OverviewUi.iconButton("OtherIcons/Close", "Close") {
            persistableData.detailsCityId = null
            layoutCityTable()
            rebuildRows()
        }
        val title = Table().apply {
            add(city.name.toLabel(fontColor = OverviewUi.accent, fontSize = font(TextRole.Body), hideIcons = true).apply { setEllipsis(true) })
                .growX().minWidth(0f).left().height(OverviewUi.touch)
            add(close).size(OverviewUi.touch)
        }
        inspector.add(title).colspan(2).growX().row()
        inspector.add("{Population} ${city.population.population}".toLabel(fontColor = OverviewUi.muted, fontSize = font(TextRole.Supporting), hideIcons = true))
            .colspan(2).left().padBottom(pt(12f)).row()
        val yields = Table()
        for ((index, column) in listOf(CityOverviewTabColumn.Food, CityOverviewTabColumn.Production,
            CityOverviewTabColumn.Science, CityOverviewTabColumn.Gold, CityOverviewTabColumn.Culture, CityOverviewTabColumn.Faith).withIndex()) {
            if (!column.isVisible(viewingPlayer)) continue
            yields.add(Table().apply {
                add(ImageGetter.getStatIcon(column.name)).size(pt(23f)).padRight(pt(8f))
                add(Table().apply {
                    add(column.getEntryValue(city).toString().toLabel(fontSize = font(TextRole.SectionTitle))).left().row()
                    add(OverviewUi.plainLabel(column.name, TextRole.Supporting, OverviewUi.muted)).left()
                }).left()
            }).expandX().left().padBottom(pt(16f))
            if (index % 3 == 2) yields.row()
        }
        inspector.add(yields).colspan(2).growX().row()
        val construction = CityOverviewTabColumn.Construction.getEntryActor(city, pt(20f), overviewScreen).apply {
            if (this is Label) {
                setFontScale(font(TextRole.CompactData) / com.unciv.ui.components.fonts.Fonts.ORIGINAL_FONT_SIZE)
                wrap = true
            }
        }
        inspector.add(construction).colspan(2).growX().padBottom(pt(12f)).row()
        CityOverviewTabColumn.Status.getEntryActor(city, pt(20f), overviewScreen)?.let {
            inspector.detailRow("Status", it)
        }
        inspector.detailRow("Happiness", CityOverviewTabColumn.Happiness.getEntryValue(city).toString())
        inspector.detailRow("City defense", CityOverviewTabColumn.CityDefense.getEntryActor(city, pt(20f), overviewScreen)!!)
        layoutCityTable()
        rebuildRows()
    }

    private fun locateCity(city: City) {
        overviewScreen.game.resetToWorldScreen().mapHolder.setCenterPosition(city.getCenterTile().position, selectUnit = false)
    }

    override fun activated(index: Int, caption: String, pager: TabbedPager) {
        super.activated(index, caption, pager)
        if (caption.isEmpty()) {
            rebuildRows()
            viewingPlayer.getCiv().cities.firstOrNull { it.id == persistableData.detailsCityId }?.let(::showDetails)
        }
    }

    override fun deactivated(index: Int, caption: String, pager: TabbedPager) {
        persistableData.scrollX = valuesScroll.scrollX
        persistableData.scrollY = bodyScroll.scrollY
    }
}
