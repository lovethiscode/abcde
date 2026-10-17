package com.unciv.ui.screens.victoryscreen

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.utils.Align
import com.unciv.Constants
import com.unciv.logic.civilization.Civilization
import com.unciv.models.ruleset.MilestoneType
import com.unciv.models.ruleset.Victory
import com.unciv.ui.components.input.onChange
import com.unciv.ui.components.widgets.TabbedPager
import com.unciv.ui.components.widgets.TranslatedSelectBox
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.screens.overviewscreen.OverviewUi as U
import com.unciv.ui.screens.overviewscreen.OverviewUi.pt
import com.unciv.ui.screens.worldscreen.WorldScreen
import kotlin.math.roundToInt

/** Per-screen choices survive viewport recreation without changing saved game data. */
internal class VictoryPageState {
    var victory: String? = null
    var ranking = RankingType.Score
    var demographicRanking = RankingType.Score
    var chartRanking = RankingType.Score
    var chartCiv: String? = null
    var chartZoom: IntRange? = null
    var replayTurn: Int? = null
    var replayPlaying = true
}

internal fun styleVictorySelect(select: TranslatedSelectBox) {
    U.styleSelect(select)
    select.style = com.badlogic.gdx.scenes.scene2d.ui.SelectBox.SelectBoxStyle(select.style).apply {
        listStyle = com.badlogic.gdx.scenes.scene2d.ui.List.ListStyle(listStyle).apply {
            selection = U.rounded(U.selected).apply { topHeight = pt(14f); bottomHeight = pt(14f) }
        }
    }
}

internal open class VictoryOverviewPage : Table(), TabbedPager.IPageExtensions {
    override fun activated(index: Int, caption: String, pager: TabbedPager) { pager.setScrollDisabled(true) }
    override fun deactivated(index: Int, caption: String, pager: TabbedPager) { pager.setScrollDisabled(false) }
}

/** Keep the incumbent identity rules: an unknown civilization must not reveal its name or stat. */
internal fun victoryCivKnown(civ: Civilization, viewer: Civilization) =
    viewer.knows(civ) || viewer == civ || civ.isDefeated() || viewer.isDefeated()

internal fun victoryCivIdentity(civ: Civilization, viewer: Civilization): Table = Table().apply {
    val icon = VictoryScreenCivGroup.getCivImageAndColors(civ, viewer,
        VictoryScreenCivGroup.DefeatedPlayerStyle.GREYED_OUT, pt(24f)).first
    add(icon).size(pt(24f)).padRight(pt(9f))
    add(U.plainLabel(if (victoryCivKnown(civ, viewer)) civ.civName else Constants.unknownNationName, 14)
        .apply { wrap = true }).growX().minWidth(0f).left()
}

internal class VictoryProgressPage(
    private val world: WorldScreen,
    private val state: VictoryPageState,
    private val global: Boolean,
    private val compact: Boolean,
    private val tablet: Boolean,
    private val contentWidth: Float,
    private val contentHeight: Float
) : VictoryOverviewPage() {
    private val viewer = world.selectedGameView.civView.getCiv()
    private val victories = world.selectedGameView.civView.getVictoriesShownInVictoryScreen().toList()
    private val sidebar = Table()
    private val detail = Table()

    init {
        top()
        if (compact) {
            add(sidebar).growX().minWidth(0f).padBottom(pt(12f)).row()
            add(detail).grow().minWidth(0f).minHeight(0f)
        } else {
            add(U.scroll(sidebar)).width(pt(if (tablet) 246f else 204f)).growY().padRight(pt(if (tablet) 28f else 20f))
            add(detail).grow().minWidth(0f).minHeight(0f)
        }
        update()
    }

    override fun activated(index: Int, caption: String, pager: TabbedPager) {
        super.activated(index, caption, pager)
        update()
    }

    private fun update() {
        val selected = victories.firstOrNull { it.name == state.victory } ?: victories.firstOrNull() ?: return
        state.victory = selected.name
        sidebar.clear()
        sidebar.top()
        for ((index, victory) in victories.withIndex()) {
            val button = U.button(selected = victory == selected, flat = true) {
                state.victory = victory.name
                update()
            }.apply {
                style = com.badlogic.gdx.scenes.scene2d.ui.Button.ButtonStyle(style).apply { checked = U.rounded(U.raised) }
                pad(pt(8f), pt(12f), pt(8f), pt(12f))
                add(ImageGetter.getImage("VictoryTypeIcons/${victory.name}")).size(pt(26f)).padRight(pt(12f))
                val text = Table().apply {
                    add(U.plainLabel("[${victory.name}] Victory", 15, if (victory == selected) U.accent else com.unciv.models.skins.ExpeditionBlueTheme.text)
                        .apply { wrap = true }).growX().minWidth(0f).left().row()
                    val next = viewer.victoryManager.getNextMilestone(victory)
                        ?.getVictoryScreenButtonHeaderText(false, viewer) ?: "Done!"
                    add(U.plainLabel(next, 12, U.muted).apply { wrap = true }).growX().minWidth(0f).left().padTop(pt(2f))
                }
                add(text).growX().minWidth(0f)
                name = "victory-type-${victory.name}"
            }
            sidebar.add(button).growX().minWidth(0f).minHeight(pt(if (compact) 64f else 58f)).padBottom(pt(5f))
            if (!compact || index % 2 == 1) sidebar.row()
        }
        detail.clear()
        detail.background = U.rounded(U.panel, 10f)
        detail.pad(pt(if (tablet) 24f else 16f), pt(if (tablet) 28f else 20f), pt(10f), pt(if (tablet) 28f else 20f))
        val body = Table().apply { top(); defaults().growX().minWidth(0f) }
        val progress = selected.milestoneObjects.firstOrNull {
            it.getVictoryScreenButtonHeaderText(it.hasBeenCompletedBy(viewer), viewer).endsWith(")") &&
                it.getVictoryScreenButtonHeaderText(it.hasBeenCompletedBy(viewer), viewer).substringAfterLast(" (").contains('/')
        }
        val heading = Table().apply {
            add(U.plainLabel("[${selected.name}] Victory", 19).apply { wrap = true }).growX().minWidth(0f).left()
            if (!global && progress != null) {
                val count = progress.getVictoryScreenButtonHeaderText(progress.hasBeenCompletedBy(viewer), viewer)
                    .substringAfterLast(" (").removeSuffix(")")
                add(U.plainLabel(count, 16, U.accent)).right().padLeft(pt(12f))
            }
        }
        body.add(heading).growX().minWidth(0f).row()
        body.add(U.plainLabel(selected.victoryScreenHeader, 13, U.muted).apply { wrap = true })
            .left().padTop(pt(3f)).padBottom(pt(14f)).row()
        if (global) addGlobal(body, selected) else addOur(body, selected, progress)
        val scroll = U.scroll(body)
        detail.add(scroll).grow().minWidth(0f).minHeight(0f)
        // Bound preferences so the pager does not expand to fit a long ruleset's milestones.
        width = contentWidth
        height = contentHeight
    }

    private fun condition(text: String, complete: Boolean) = Table().apply {
        add(ImageGetter.getImage(if (complete) "OtherIcons/Checkmark" else "OtherIcons/Circle").apply {
            color = if (complete) U.accent else U.muted
        }).size(pt(18f)).padRight(pt(12f))
        add(U.plainLabel(text, 14).apply { wrap = true }).growX().minWidth(0f).left()
        add(U.plainLabel(if (complete) "Completed" else "Not completed", 12, if (complete) U.accent else U.muted)).padLeft(pt(12f))
        padTop(pt(8f)); padBottom(pt(8f))
    }

    private fun addOur(body: Table, victory: Victory, summarized: com.unciv.models.ruleset.Milestone?) {
        var firstIncomplete = true
        for (milestone in victory.milestoneObjects) {
            val completion = when {
                milestone.hasBeenCompletedBy(viewer) -> Victory.CompletionStatus.Completed
                firstIncomplete -> { firstIncomplete = false; Victory.CompletionStatus.Partially }
                else -> Victory.CompletionStatus.Incomplete
            }
            val buttons = milestone.getVictoryScreenButtons(completion, viewer)
            val rows = Table()
            val bodyWidth = contentWidth - pt(if (tablet) 330f else 264f)
            val statusWidth = maxOf(U.plainLabel("Completed", 12).prefWidth, U.plainLabel("Not completed", 12).prefWidth)
            val rowWidth = buttons.drop(1).maxOfOrNull { U.plainLabel(it.text.toString(), 14).prefWidth + statusWidth + pt(42f) } ?: 0f
            val columns = !compact && milestone.type == MilestoneType.CompletePolicyBranches && buttons.size > 1 &&
                bodyWidth >= rowWidth * 2 + pt(20f)
            for ((index, button) in buttons.withIndex()) {
                if (index == 0 && buttons.size > 1 && milestone == summarized) continue
                val done = button.isChecked || button.color == Color.GREEN
                val entry = Table().apply {
                    add(condition(button.text.toString(), done)).growX().minWidth(0f).minHeight(pt(38f)).row()
                    add(ImageGetter.getWhiteDot().apply { color = U.border }).growX().height(pt(.5f))
                }
                if (index == 0 && buttons.size > 1) {
                    rows.add(entry).colspan(if (columns) 2 else 1).growX().minWidth(0f).row()
                } else {
                    rows.add(entry).growX().minWidth(0f).padRight(if (columns && index % 2 == 1) pt(20f) else 0f)
                    if (!columns || index % 2 == 0) rows.row()
                }
            }
            body.add(rows).growX().minWidth(0f).padBottom(pt(8f)).row()
        }
    }

    private fun addGlobal(body: Table, victory: Victory) {
        val civs = world.gameInfo.civilizations.filter { it.isMajorCiv() && victory in it.victoryManager.getAvailableVictories() }
            .sortedWith(compareBy<Civilization> { it.isDefeated() }.thenBy { it.victoryManager.amountMilestonesCompleted(victory) })
        for (civ in civs) {
            val row = Table().apply {
                add(victoryCivIdentity(civ, viewer)).growX().minWidth(0f).row()
                if (victoryCivKnown(civ, viewer)) {
                    val next = civ.victoryManager.getNextMilestone(victory)?.getVictoryScreenButtonHeaderText(false, civ) ?: "Done!"
                    add(U.plainLabel(next, 13, U.muted).apply { wrap = true }).growX().minWidth(0f).padLeft(pt(33f)).padTop(pt(3f))
                }
                padTop(pt(10f)); padBottom(pt(10f))
            }
            body.add(row).growX().minWidth(0f).row()
            body.add(ImageGetter.getWhiteDot().apply { color = U.border }).growX().height(pt(.5f)).row()
        }
    }
}

internal class VictoryStatisticsPage(
    private val world: WorldScreen,
    private val state: VictoryPageState,
    private val demographics: Boolean,
    private val compact: Boolean
) : VictoryOverviewPage() {
    private val viewer = world.selectedGameView.civView.getCiv()
    private val rows = Table()

    init {
        top()
        run {
            val selected = if (demographics) state.demographicRanking else state.ranking
            val select = TranslatedSelectBox(RankingType.filteredEntries(world.gameInfo.gameParameters).map { it.label }, selected.label)
            styleVictorySelect(select)
            select.onChange {
                val metric = RankingType.entries.first { it.label == select.selected.value }
                if (demographics) state.demographicRanking = metric else state.ranking = metric
                update()
            }
            val toolbar = Table().apply {
                add(U.plainLabel(if (demographics) "Demographic" else "Rankings", 14, U.muted)).padRight(pt(12f))
                add(select).height(U.touch).minWidth(pt(160f))
                add().growX()
            }
            add(toolbar).growX().minWidth(0f).padBottom(pt(12f)).row()
        }
        val scroll = U.scroll(rows)
        add(scroll).grow().minWidth(0f).minHeight(0f)
        update()
    }

    private fun cell(text: String, numeric: Boolean = false, muted: Boolean = false): Actor =
        U.plainLabel(text, if (muted) 12 else 14, if (muted) U.muted else com.unciv.models.skins.ExpeditionBlueTheme.text).apply {
            if (!numeric) wrap = true
            setAlignment(if (numeric) Align.right else Align.left)
        }

    private fun update() {
        rows.clear(); rows.top()
        if (demographics) demographics() else rankings()
    }

    private fun rankings() {
        val header = Table().apply {
            background = U.surface(U.raised); pad(pt(10f), pt(14f), pt(10f), pt(14f))
            add(cell("Rank", muted = true)).width(pt(48f))
            add(cell("Civilizations", muted = true)).growX().minWidth(0f)
            add(cell(state.ranking.label, true, true)).width(pt(110f))
        }
        rows.add(header).growX().minWidth(0f).row()
        val civs = world.gameInfo.civilizations.filter { it.isMajorCiv() && VictoryScreen.canViewCivStats(world.gameInfo, viewer, it) }
            .map { VictoryScreen.CivWithStat(it, state.ranking) }.sortedBy { it.civ.civName }
            .sortedByDescending { if (it.civ.isDefeated()) Int.MIN_VALUE else it.value }
        for ((index, entry) in civs.withIndex()) {
            val row = Table().apply {
                background = U.surface(if (entry.civ == viewer) U.selected else U.panel)
                pad(pt(12f), pt(14f), pt(12f), pt(14f))
                add(cell((index + 1).toString())).width(pt(48f))
                add(victoryCivIdentity(entry.civ, viewer)).growX().minWidth(0f)
                add(cell(if (entry.civ.isDefeated() || !victoryCivKnown(entry.civ, viewer)) "—" else entry.value.toString(), true)).width(pt(110f))
            }
            rows.add(row).growX().minWidth(0f).row()
            rows.add(ImageGetter.getWhiteDot().apply { color = U.border }).growX().height(pt(.5f)).row()
        }
    }

    private fun demographics() {
        val category = state.demographicRanking
        val sorted = world.gameInfo.civilizations.filter { it.isMajorCiv() && (it.isAlive() || it == viewer) }
            .map { civ ->
                val history = civ.statsHistory
                val snapshot = history[world.gameInfo.turns] ?: history.maxByOrNull { it.key }?.value
                VictoryScreen.CivWithStat(civ, snapshot?.get(category) ?: civ.getStatForRanking(category))
            }.sortedByDescending { it.value }
        if (sorted.isEmpty()) return
        fun addRow(title: String, identity: Actor, value: String, header: Boolean = false, mine: Boolean = false) {
            val row = Table().apply {
                background = U.surface(if (header) U.raised else if (mine) U.selected else U.panel)
                pad(pt(12f), pt(14f), pt(12f), pt(14f))
                add(cell(title, muted = header)).width(pt(if (compact) 80f else 150f)).left()
                add(identity).growX().minWidth(0f).left().padLeft(pt(8f)).padRight(pt(8f))
                add(cell(value, numeric = true, muted = header)).width(pt(if (compact) 85f else 110f)).right()
            }
            rows.add(row).growX().minWidth(0f).row()
            rows.add(ImageGetter.getWhiteDot().apply { color = U.border }).growX().height(pt(.5f)).row()
        }
        addRow("Demographic", cell("Civilizations", muted = true), category.label, header = true)
        addRow("Rank", victoryCivIdentity(viewer, viewer), (sorted.indexOfFirst { it.civ == viewer } + 1).toString())
        if (viewer.isMajorCiv() && !viewer.isDefeated())
            addRow("Value", victoryCivIdentity(viewer, viewer), sorted.first { it.civ == viewer }.value.toString(), mine = true)
        fun comparison(title: String, entry: VictoryScreen.CivWithStat) = addRow(title,
            victoryCivIdentity(entry.civ, viewer),
            if (!victoryCivKnown(entry.civ, viewer) || entry.civ.isDefeated()) "—" else entry.value.toString())
        comparison("Best", sorted.first())
        addRow("Average", cell("—"), (sorted.sumOf { it.value }.toFloat() / sorted.size).roundToInt().toString())
        comparison("Worst", sorted.last())
    }
}
