package com.unciv.ui.screens.overviewscreen

import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.unciv.GUI
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.input.onChange
import com.unciv.ui.components.widgets.SortableGrid
import com.unciv.ui.components.widgets.TabbedPager
import com.unciv.ui.components.widgets.TranslatedSelectBox
import com.unciv.ui.screens.overviewscreen.OverviewUi.detailRow
import com.unciv.ui.screens.overviewscreen.OverviewUi.pt
import com.unciv.ui.screens.overviewscreen.OverviewUi.font
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.screens.pickerscreens.UnitRenamePopup
import com.unciv.view.CivView
import com.unciv.view.MapUnitView

/** Inspect a unit in place; only the explicit location action leaves the overview. */
class UnitOverviewTab(
    viewingPlayer: CivView,
    overviewScreen: EmpireOverviewScreen,
    persistedData: EmpireOverviewTabPersistableData? = null
) : EmpireOverviewTab(viewingPlayer, overviewScreen) {
    class UnitTabPersistableData : EmpireOverviewTabPersistableData(), SortableGrid.ISortState<UnitOverviewTabColumn> {
        @Transient var scrollY: Float? = null
        @Transient var selectedUnitId: Int? = null
        @Transient var detailScrollY = 0f
        override var sortedBy = UnitOverviewTabColumn.Name
        override var direction = SortableGrid.SortDirection.None
        override fun isEmpty() = sortedBy == UnitOverviewTabColumn.Name && direction != SortableGrid.SortDirection.Descending
    }
    override val persistableData = (persistedData as? UnitTabPersistableData) ?: UnitTabPersistableData()
    override val usesOwnScroll = true
    private val unitList = Table()
    private val detail = Table()
    private val listScroll = OverviewUi.scroll(unitList)
    private val detailScroll = OverviewUi.scroll(detail)
    private val listWidth = if (overviewScreen.compact) overviewScreen.contentWidth
        else pt(190f).coerceAtMost(overviewScreen.contentWidth * .35f)
    private val rows = linkedMapOf<Int, com.badlogic.gdx.scenes.scene2d.ui.Button>()
    private var units = viewingPlayer.getUnits()

    init {
        name = "overview-units"
        top()
        val sortColumns = UnitOverviewTabColumn.entries.filter { it != UnitOverviewTabColumn.EditName }
        val sort = TranslatedSelectBox(sortColumns.map { it.headerTip.ifEmpty { it.name } },
            persistableData.sortedBy.headerTip.ifEmpty { persistableData.sortedBy.name })
        OverviewUi.styleSelect(sort)
        sort.onChange {
            persistableData.sortedBy = sortColumns[sort.selectedIndex]
            persistableData.direction = persistableData.sortedBy.defaultSort
            rebuildList()
        }
        val listPanel = Table().apply {
            background = OverviewUi.rounded(OverviewUi.panel, 10f)
            add(sort).growX().minWidth(0f).height(OverviewUi.touch).row()
            add(ImageGetter.getWhiteDot().apply { color = OverviewUi.border }).growX().height(pt(1f)).row()
            add(listScroll).grow().minHeight(0f)
        }
        val split = Table().apply {
            if (overviewScreen.compact) {
                add(listPanel).growX().minWidth(0f).height(overviewScreen.centerAreaHeight * .32f).row()
                add(detailScroll).padTop(pt(12f)).minWidth(0f).minHeight(0f).grow()
            } else {
                add(listPanel).width(listWidth).growY()
                add(detailScroll).padLeft(pt(16f)).minWidth(0f).grow()
            }
        }
        add(split).width(overviewScreen.contentWidth).height(overviewScreen.centerAreaHeight - pt(8f)).padTop(pt(8f)).row()
        rebuildList()
        listScroll.scrollY = persistableData.scrollY ?: 0f
        detailScroll.scrollY = persistableData.detailScrollY
        listScroll.updateVisualScroll()
        detailScroll.updateVisualScroll()
    }

    private fun rebuildList() {
        val scrollY = listScroll.scrollY
        units = viewingPlayer.getUnits().let { source ->
            if (persistableData.direction == SortableGrid.SortDirection.None) source else {
                val comparator = persistableData.sortedBy.getComparator()
                source.sortedWith(if (persistableData.direction == SortableGrid.SortDirection.Descending) Comparator { a, b -> comparator.compare(b, a) } else comparator)
            }
        }
        unitList.clear()
        rows.clear()
        unitList.top()
        for (unit in units) {
            val row = Table().apply { background = OverviewUi.surface(OverviewUi.border.cpy().apply { a = .47f }) }
            val inspect = OverviewUi.button(unit.id == persistableData.selectedUnitId, flat = true) { selectUnit(unit) }
            inspect.name = "unit-${unit.id}"
            val text = Table().apply {
                add(unit.displayName().toLabel(fontSize = font(15), hideIcons = true).apply { setEllipsis(true) }).growX().minWidth(0f).left().row()
                val city = UnitOverviewTabColumn.ClosestCity.getEntryString(unit) ?: "—"
                add("$city · {Movement} ${unit.getMovementString()}".toLabel(fontColor = OverviewUi.muted, fontSize = font(12), hideIcons = true).apply { setEllipsis(true) })
                    .growX().minWidth(0f).left().padTop(pt(7f))
            }
            inspect.add(text).grow().minWidth(0f).pad(pt(10f), pt(12f), pt(10f), pt(8f))
            rows[unit.id] = inspect
            row.add(inspect).minWidth(0f).grow()
            val locate = OverviewUi.locateButton { locateUnit(unit) }
            locate.name = "unit-locate-${unit.id}"
            row.add(ImageGetter.getWhiteDot().apply { color = OverviewUi.border }).width(pt(1f)).growY()
            row.add(locate).width(OverviewUi.touch).growY()
            unitList.add(row).width(listWidth).height(pt(65f)).row()
            unitList.add(ImageGetter.getWhiteDot().apply { color = OverviewUi.border.cpy().apply { a = .47f } }).width(listWidth).height(pt(1f)).row()
        }
        unitList.add().expandY()
        val selected = units.firstOrNull { it.id == persistableData.selectedUnitId } ?: units.firstOrNull()
        if (selected != null) selectUnit(selected, resetScroll = false) else {
            detail.clear()
            detail.add("No units".toLabel()).pad(20f)
        }
        listScroll.layout()
        listScroll.scrollY = scrollY
        listScroll.updateVisualScroll()
    }

    internal fun selectUnit(unit: MapUnitView, resetScroll: Boolean = true) {
        persistableData.selectedUnitId = unit.id
        for ((id, row) in rows) {
            row.isChecked = id == unit.id
            (row.parent as Table).background = OverviewUi.surface(if (row.isChecked) OverviewUi.selected else OverviewUi.panel)
        }
        detail.clear()
        detail.top().left().pad(pt(16f))
        detail.background = OverviewUi.rounded(OverviewUi.panel, 10f)
        val title = Table().apply {
            add(ImageGetter.getImage("UnitIcons/${unit.getBaseUnit().getIconName()}")).size(pt(19f)).padRight(pt(10f))
            add(unit.displayName().toLabel(fontSize = font(22), hideIcons = true).apply { wrap = true }).growX().minWidth(0f).left()

        }
        detail.add(title).colspan(2).growX().minHeight(pt(28f)).padBottom(pt(12f)).row()
        detail.detailRow("Action", UnitOverviewTabColumn.Action.getEntryString(unit) ?: "—")
        detail.detailRow("Movement", unit.getMovementString())
        val city = unit.getTile().getVisibleTilesInDistance(3).firstOrNull { it.isCityCenter() }?.owningCity()
        if (city?.tryGetCityView() == null) detail.detailRow("Closest city", city?.name ?: "—") else {
            val cityButton = OverviewUi.button(flat = true) {
                overviewScreen.game.resetToWorldScreen().mapHolder.setCenterPosition(city.getCity().getCenterTile().position, selectUnit = false)
            }.apply {
                right()
                add(city.name.toLabel(fontSize = font(14), hideIcons = true)).right()
                name = "unit-city-${city.getCity().id}"
            }
            detail.detailRow("Closest city", cityButton)
        }
        detail.detailRow("Strength", unit.getBaseUnit().strength.toString())
        if (unit.getBaseUnit().rangedStrength > 0)
            detail.detailRow("Ranged strength", unit.getBaseUnit().rangedStrength.toString())
        if (!unit.isCivilian()) detail.detailRow("XP", "${unit.xp}/${unit.xpForNextPromotion()}")
        detail.detailRow("Health", "${unit.unitHealth}/100")
        val promotions = UnitOverviewTabColumn.Promotions.getEntryActor(unit, 28f, this)
        if (promotions != null) detail.detailRow("Promotions", promotions)
        val upgrade = UnitOverviewTabColumn.Upgrade.getEntryActor(unit, 28f, this)
        if (upgrade != null) detail.detailRow("Upgrade", upgrade)
        if (!viewingPlayer.isSpectator()) detail.add(OverviewUi.iconButton("OtherIcons/Pencil", "Rename unit") {
                UnitRenamePopup(overviewScreen, unit) { update() }
            }).size(OverviewUi.touch).left().row()
        detail.add(UnitSupplyTable.create(overviewScreen, this, viewingPlayer.getCiv(),
            overviewScreen.contentWidth - (if (overviewScreen.compact) 0f else listWidth + pt(16f)) - pt(32f)))
            .colspan(2).growX().padTop(pt(12f)).row()
        detail.add().expandY()
        if (resetScroll) detailScroll.scrollY = 0f
        detailScroll.updateVisualScroll()
    }

    private fun locateUnit(unit: MapUnitView) {
        persistableData.selectedUnitId = unit.id
        GUI.resetToWorldScreen()
        GUI.getMap().setCenterPosition(unit.getTile().position(), forceSelectUnit = unit)
    }

    override fun deactivated(index: Int, caption: String, pager: TabbedPager) {
        persistableData.scrollY = listScroll.scrollY
        persistableData.detailScrollY = detailScroll.scrollY
    }

    internal fun update(unitsChanged: Boolean = false) { rebuildList() }

    override fun select(selection: String): Float? {
        val unit = units.firstOrNull { it.id.toString() == selection } ?: return null
        selectUnit(unit)
        listScroll.layout()
        listScroll.scrollY = units.indexOf(unit) * (pt(67f))
        listScroll.updateVisualScroll()
        return null
    }
}
