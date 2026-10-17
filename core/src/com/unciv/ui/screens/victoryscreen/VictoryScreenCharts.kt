package com.unciv.ui.screens.victoryscreen

import com.unciv.ui.screens.overviewscreen.OverviewUi as U
import com.unciv.ui.screens.overviewscreen.OverviewUi.pt
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.utils.Align
import com.unciv.Constants
import com.unciv.models.translations.tr
import com.unciv.ui.components.widgets.AutoScrollPane
import com.unciv.ui.components.widgets.TabbedPager
import com.unciv.ui.components.input.onChange
import com.unciv.ui.components.input.onClick
import com.unciv.ui.components.extensions.packIfNeeded
import com.unciv.ui.components.input.OnClickListener
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.components.widgets.TranslatedSelectBox
import com.unciv.ui.screens.victoryscreen.VictoryScreenCivGroup.DefeatedPlayerStyle
import com.unciv.ui.screens.worldscreen.WorldScreen

class VictoryScreenCharts internal constructor(
    worldScreen: WorldScreen,
    private val state: VictoryPageState? = null,
    private val compact: Boolean = false
) : Table(BaseScreen.skin), TabbedPager.IPageExtensions {
    private val gameInfo = worldScreen.gameInfo

    private var rankingType = state?.chartRanking ?: RankingType.Score
    private var selectedCiv = gameInfo.civilizations.firstOrNull { it.civName == state?.chartCiv } ?: worldScreen.selectedCiv
    private val viewingCiv = worldScreen.selectedGameView.civView.getCiv()

    private val rankingTypeSelect = TranslatedSelectBox(
        RankingType.filteredEntries(gameInfo.gameParameters).map { it.label },
        rankingType.label
    )
    private val civButtonsTable = Table()
    private val civButtonsScroll = AutoScrollPane(civButtonsTable)
    private val controlsColumn = Table()
    private val markerIcon = ImageGetter.getImage("OtherIcons/Star").apply {
        color = Color.GOLD
        align = Align.center
    }

    private var lineChart = LineChart(viewingCiv, state != null)
    private val chartContext = if (state == null) null else U.plainLabel("", 12, U.muted).apply { wrap = true }

    /** if it is negative - no zoom, if positive - zoom at turn X */
    private var zoomAtX : IntRange? = state?.chartZoom

    init {
        if (state == null) {
            civButtonsScroll.setScrollingDisabled(x = true, y = false)
            civButtonsTable.defaults().space(20f).fillX()
            controlsColumn.defaults().space(20f).fillX()
            controlsColumn.add(rankingTypeSelect).right().row()
            controlsColumn.add(civButtonsScroll).fillY()
            defaults().fill().pad(20f)
            add(controlsColumn)
            add(lineChart).growX().expandY().top().padLeft(0f)
        } else {
            styleVictorySelect(rankingTypeSelect)
            val toolbar = Table().apply {
                add(U.plainLabel("Charts", 14, U.muted)).padRight(pt(12f))
                add(rankingTypeSelect).height(U.touch).minWidth(pt(160f))
                add().growX()
            }
            add(toolbar).growX().minWidth(0f).padBottom(pt(12f)).row()
            val chart = Table().apply {
                background = U.rounded(U.panel)
                pad(pt(12f))
                add(lineChart).grow().minWidth(0f).minHeight(0f).row()
                val reset = U.button(flat = true) { zoomAtX = null; update() }.apply {
                    add(U.plainLabel("Reset zoom", 12, U.accent)); name = "victory-chart-reset"
                }
                add(Table().apply {
                    add(chartContext).growX().minWidth(0f).left().padRight(pt(8f))
                    add(reset).height(U.touch).right()
                }).growX().minWidth(0f)
            }
            val layout = Table().apply {
                civButtonsScroll.setScrollingDisabled(!compact, compact)
                if (compact) {
                    add(civButtonsScroll).growX().minWidth(0f).height(pt(52f)).padBottom(pt(8f)).row()
                } else add(civButtonsScroll).width(pt(170f)).growY().padRight(pt(18f))
                add(chart).grow().minWidth(0f).minHeight(0f)
            }
            add(layout).grow().minWidth(0f).minHeight(0f)
        }
        updateControls()

        val onChartClick = OnClickListener(function = { _ , x, _ ->
            zoomAtX = if (zoomAtX == null) lineChart.getTurnAt(x) else null
            state?.chartZoom = zoomAtX
            updateChart()
        })
        lineChart.addListener(onChartClick)

        rankingTypeSelect.onChange {
            rankingType = RankingType.entries
                .firstOrNull { it.label == rankingTypeSelect.selected.value }
                ?: RankingType.Score
            update()
        }
    }

    private fun update() {
        state?.let { it.chartRanking = rankingType; it.chartCiv = selectedCiv.civName; it.chartZoom = zoomAtX }
        updateChart()
        updateControls()
    }

    private fun updateControls() {
        val oldScrollY = civButtonsScroll.scrollY
        civButtonsTable.clear()
        val sortedCivs = gameInfo.civilizations.asSequence()
            .filter { it.isMajorCiv() }
            .filter { targetCiv -> VictoryScreen.canViewCivStats(gameInfo, viewingCiv, targetCiv) }
            .map { VictoryScreen.CivWithStat(it, rankingType) }
            .sortedWith(
                compareByDescending<VictoryScreen.CivWithStat> { if (it.civ.isDefeated()) Int.MIN_VALUE else it.value }
                .thenBy { it.civ.civName }
            )
        for (civEntry in sortedCivs) {
            if (state != null) {
                val button = U.button(selected = civEntry.civ == selectedCiv, flat = true) {
                    selectedCiv = civEntry.civ
                    update()
                }.apply {
                    add(victoryCivIdentity(civEntry.civ, viewingCiv)).growX().minWidth(0f)
                    val value = if (civEntry.civ.isDefeated() || !victoryCivKnown(civEntry.civ, viewingCiv)) "—"
                        else civEntry.value.toString()
                    add(U.plainLabel(value, 12, U.muted)).right().padLeft(pt(8f))
                    pad(pt(8f))
                }
                civButtonsTable.add(button).width(pt(if (compact) 150f else 166f)).minHeight(U.touch).padBottom(pt(5f))
                if (!compact) civButtonsTable.row()
            } else {
                if (civEntry.civ != selectedCiv) civButtonsTable.add()
                else civButtonsTable.add(markerIcon).size(24f).right()
                val button = VictoryScreenCivGroup(civEntry, viewingCiv, DefeatedPlayerStyle.REGULAR)
                button.touchable = Touchable.enabled
                civButtonsTable.add(button).row()
                button.onClick { selectedCiv = civEntry.civ; update() }
            }
        }
        civButtonsTable.add().padBottom(20f).row()
        civButtonsTable.pack()
        
        validate()
        civButtonsScroll.scrollY = oldScrollY
    }

    private fun updateChart() {
        val points = getLineChartData(rankingType)
        lineChart.update(points, selectedCiv)
        val first = points.minOfOrNull { it.x } ?: 0
        val last = points.maxOfOrNull { it.x } ?: 0
        val identity = if (victoryCivKnown(selectedCiv, viewingCiv)) selectedCiv.civName else Constants.unknownNationName
        chartContext?.setText("{$identity} · {Turn}: ${first.tr()}–${last.tr()}".tr())
        if (state == null) packIfNeeded()
    }

    private fun getLineChartData(rankingType: RankingType): List<DataPoint<Int>> {
        val dataPoints = gameInfo.civilizations.asSequence()
            .filter { it.isMajorCiv() }
            .filter { targetCiv -> VictoryScreen.canViewCivStats(gameInfo, viewingCiv, targetCiv) }
            .flatMap { civ ->
                civ.statsHistory
                    .filterKeys { zoomAtX == null || it in zoomAtX!!  }
                    .filterValues { it.containsKey(rankingType) }
                    .map { (turn, data) -> Pair(turn, Pair(civ, data.getValue(rankingType))) }
            }
            .groupBy({ it.first }, { it.second })
            .mapValues { group -> group.value.toMap() }
            .flatMap { turn ->
                turn.value.map { (civ, value) -> DataPoint(turn.key, value, civ) }
            }.toMutableList()

        // Historical data is recorded at the end of each turn, so we append the live stat for the current turn
        val currentTurn = gameInfo.turns
        if (zoomAtX == null || currentTurn in zoomAtX!!)
            for (civ in dataPoints.map { it.civ }.distinct().filterNot { it.isDefeated() })
                dataPoints += DataPoint(currentTurn, civ.getStatForRanking(rankingType), civ)

        return dataPoints
    }

    override fun activated(index: Int, caption: String, pager: TabbedPager) {
        pager.setScrollDisabled(true)
        if (state == null) { controlsColumn.height = parent.height; lineChart.height = parent.height }
        update()
        civButtonsTable.invalidateHierarchy()
    }

    override fun deactivated(index: Int, caption: String, pager: TabbedPager) {
        pager.setScrollDisabled(false)
    }
}
