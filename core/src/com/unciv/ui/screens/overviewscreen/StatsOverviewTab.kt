package com.unciv.ui.screens.overviewscreen

import com.unciv.ui.screens.overviewscreen.OverviewUi.pt
import com.unciv.ui.screens.overviewscreen.OverviewUi.font
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.unciv.GUI
import com.unciv.models.ruleset.unique.Unique
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.stats.Stat
import com.unciv.models.stats.StatMap
import com.unciv.ui.components.extensions.addSeparator
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.widgets.TabbedPager
import com.unciv.ui.components.widgets.UncivSlider
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.screens.civilopediascreen.FormattedLine
import com.unciv.ui.screens.civilopediascreen.MarkupRenderer
import com.unciv.view.CivView
import kotlin.math.roundToInt

class StatsOverviewTab(
    viewingPlayer: CivView,
    overviewScreen: EmpireOverviewScreen
) : EmpireOverviewTab(viewingPlayer, overviewScreen) {
    override val usesOwnScroll = true
    private val selection = Table()
    private val breakdown = Table()
    private var selectedStat = "Gold"
    private val happinessTable = Table()
    private val unhappinessTable = UnhappinessTable()
    private val goldAndSliderTable = Table()
    private val goldTable = Table()
    private val scienceTable = Table()
    private val cultureTable = Table()
    private val faithTable = Table()
    private val greatPeopleTable = Table()
    private val scoreTable = Table()
    private val isReligionEnabled = viewingPlayer.isReligionEnabled()

    override fun activated(index: Int, caption: String, pager: TabbedPager) {
        overviewScreen.game.settings.addCompletedTutorialTask("See your stats breakdown")
        super.activated(index, caption, pager)
    }

    init {
        happinessTable.defaults().pad(0f)
        goldTable.defaults().pad(0f)
        scienceTable.defaults().pad(0f)
        cultureTable.defaults().pad(0f)
        faithTable.defaults().pad(0f)
        greatPeopleTable.defaults().pad(5f)
        scoreTable.defaults().pad(5f)
        unhappinessTable.update()

        goldAndSliderTable.add(goldTable).growX().row()
        if (viewingPlayer.ruleset.modOptions.hasUnique(UniqueType.ConvertGoldToScience))
            goldAndSliderTable.addGoldSlider()

        update()

        val split = Table().apply {
            add(OverviewUi.scroll(selection)).width(pt(170f)).growY()
            add(OverviewUi.scroll(breakdown)).minWidth(0f).grow().padLeft(pt(14f))
        }
        add(split).width(overviewScreen.contentWidth).height(overviewScreen.centerAreaHeight - pt(8f)).padTop(pt(8f))
        showStat(selectedStat)
    }

    private fun showStat(name: String) {
        selectedStat = name
        val tables = linkedMapOf("Gold" to goldAndSliderTable, "Science" to scienceTable,
            "Culture" to cultureTable, "Happiness" to happinessTable)
        if (unhappinessTable.show) tables["Unhappiness"] = unhappinessTable
        if (isReligionEnabled) tables["Faith"] = faithTable
        tables["Great person points"] = greatPeopleTable
        tables["Score"] = scoreTable
        val statMap = viewingPlayer.getStatMapForNextTurn()
        val amounts = Stat.entries.associate { stat -> stat.name to statMap.values.sumOf { it[stat].toDouble() }.roundToInt() }.toMutableMap()
        amounts["Happiness"] = viewingPlayer.getHappinessBreakdown().values.sum().roundToInt()
        amounts["Score"] = viewingPlayer.calculateScoreBreakdown().values.sum().roundToInt()
        selection.clear()
        selection.top()
        for (label in tables.keys) {
            selection.add(OverviewUi.button(label == name) { showStat(label) }.apply {
                val icon = when (label) {
                    "Great person points" -> ImageGetter.getStatIcon("Specialist")
                    "Score" -> ImageGetter.getImage("OtherIcons/Score")
                    "Unhappiness" -> ImageGetter.getStatIcon("Malcontent")
                    else -> ImageGetter.getStatIcon(label)
                }
                add(icon).size(pt(18f)).padLeft(pt(12f)).padRight(pt(8f))
                add(OverviewUi.plainLabel(label, 15).apply { wrap = true })
                    .minWidth(0f).growX().left().padTop(pt(10f)).padBottom(pt(10f))
                amounts[label]?.let { add(it.toString().toLabel(fontColor = OverviewUi.accent, fontSize = font(14))).right().padRight(12f) }
            }).growX().minHeight(pt(52f)).padBottom(pt(4f)).row()
        }
        breakdown.clear()
        breakdown.top().left()
        breakdown.background = OverviewUi.rounded(OverviewUi.panel, 10f)
        breakdown.add(tables[name] ?: goldAndSliderTable).growX().minWidth(0f).pad(pt(10f), pt(16f), pt(10f), pt(16f))
    }

    fun update() {
        val statMap = viewingPlayer.getStatMapForNextTurn()
        updateHappinessTable()
        goldTable.updateStatTable(Stat.Gold, statMap)
        scienceTable.updateStatTable(Stat.Science, statMap)
        cultureTable.updateStatTable(Stat.Culture, statMap)
        if (isReligionEnabled) faithTable.updateStatTable(Stat.Faith, statMap)
        updateGreatPeopleTable()
        updateScoreTable()
    }

    private fun Table.addLabeledValue(label: String, value: Float) {
        val roundedValue = value.roundToInt()
        if (roundedValue == 0) return
        add(label.toLabel(fontColor = OverviewUi.muted, hideIcons = true, fontSize = font(14))).left().expandX().minHeight(pt(35f))
        add(roundedValue.toString().toLabel(fontSize = font(14))).right().row()
        add(ImageGetter.getWhiteDot().apply { color = OverviewUi.border }).colspan(2).growX().height(pt(1f)).row()
    }
    private fun Table.addTotal(value: Float) {
        add("Total".toLabel(fontColor = OverviewUi.accent, fontSize = font(14))).left().minHeight(pt(36f))
        add(value.roundToInt().toString().toLabel(fontColor = OverviewUi.accent, fontSize = font(14))).right().row()
        pack()
    }

    private fun updateHappinessTable() = happinessTable.apply {
        clear()
        val happinessBreakdown = viewingPlayer.getHappinessBreakdown()
        for ((key, value) in happinessBreakdown)
            addLabeledValue(key, value)
        addTotal(happinessBreakdown.values.sum())
    }

    inner class UnhappinessTable : Table() {
        val show: Boolean

        private val uniques: Set<Unique>

        init {
            defaults().pad(5f)
            uniques = sequenceOf(
                    UniqueType.ConditionalWhenAboveAmountStatResource,
                    UniqueType.ConditionalWhenBetweenStatResource,
                    UniqueType.ConditionalWhenBelowAmountStatResource,
                ).flatMap { conditionalType ->
                    viewingPlayer.getMatchingUniques(conditionalType)
                        .filter { it.params.last() == "Happiness" }
                        .sortedBy { it.type } // otherwise order might change as a HashMap is involved
                }.filterNot { it.isHiddenToUsers() }
                .toSet()
            show = uniques.isNotEmpty()
        }

        fun update() {
            add(MarkupRenderer.render(
                uniques.map { FormattedLine(it) },
                labelWidth = (overviewScreen.stage.width * 0.25f).coerceAtLeast(greatPeopleTable.width * 0.8f),
                iconDisplay = FormattedLine.IconDisplay.NoLink
            )).colspan(2)
        }
    }

    private fun Table.updateStatTable(stat: Stat, statMap: StatMap) {
        clear()
        var total = 0f
        for ((source, stats) in statMap) {
            addLabeledValue(source, stats[stat])
            total += stats[stat]
        }
        addTotal(total)
    }

    private fun Table.addGoldSlider() {
        addSeparator()
        val sliderTable = Table()
        sliderTable.add("Convert gold to science".toLabel()).row()

        val slider = UncivSlider(0f, 1f, 0.1f,
            initial = viewingPlayer.getGoldPercentConvertedToScience(),
            getTipText = UncivSlider::formatPercent
        ) {
            viewingPlayer.trySetGoldPercentConvertedToScience(it)
            viewingPlayer.tryUpdateAllCityStats()
            update()
        }
        slider.isDisabled = !GUI.isAllowedChangeState()

        sliderTable.add(slider).padTop(15f)
        add(sliderTable).colspan(2)
    }

    private fun updateGreatPeopleTable() = greatPeopleTable.apply {
        clear()
        add()
        add("Current points".toLabel())
        add("Points per turn".toLabel()).row()

        val greatPersonPoints = viewingPlayer.getGreatPersonPointsCounter()
        val greatPersonPointsPerTurn = viewingPlayer.getGreatPersonPointsForNextTurn()
        for ((greatPerson, points) in greatPersonPoints) {
            val pointsToGreatPerson = viewingPlayer.getPointsRequiredForGreatPerson(greatPerson)
            add(greatPerson.toLabel()).left()
            add("$points/$pointsToGreatPerson".toLabel())
            add(greatPersonPointsPerTurn[greatPerson].toLabel()).right().row()
        }

        val greatGeneralPoints = viewingPlayer.getGreatGeneralPointsCounter()
        val pointsForNextGreatGeneral = viewingPlayer.getPointsForNextGreatGeneralCounter()
        for ((unit, points) in greatGeneralPoints) {
            val pointsToGreatGeneral = pointsForNextGreatGeneral[unit]
            add(unit.toLabel()).left()
            add("$points/$pointsToGreatGeneral".toLabel()).row()
        }

        pack()
    }

    private fun updateScoreTable() = scoreTable.apply {
        clear()
        val scoreBreakdown = viewingPlayer.calculateScoreBreakdown()
        for ((label, value) in scoreBreakdown)
            addLabeledValue(label, value.toFloat())
        addTotal(scoreBreakdown.values.sum().toFloat())
    }
}
