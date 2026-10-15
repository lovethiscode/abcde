package com.unciv.ui.screens.overviewscreen

import com.unciv.ui.screens.overviewscreen.OverviewUi.pt
import com.unciv.ui.screens.overviewscreen.OverviewUi.font
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.unciv.Constants
import com.unciv.UncivGame
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.diplomacy.DiplomacyFlags
import com.unciv.logic.civilization.diplomacy.RelationshipLevel
import com.unciv.models.ruleset.Policy.PolicyBranchType
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.addBorder
import com.unciv.ui.components.extensions.addSeparator
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.fonts.Fonts
import com.unciv.ui.components.input.onClick
import com.unciv.ui.components.widgets.AutoScrollPane
import com.unciv.ui.components.widgets.ColorMarkupLabel
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.diplomacyscreen.DiplomacyScreen
import com.unciv.view.CivView
import yairm210.purity.annotations.Readonly
import kotlin.math.roundToInt

class GlobalPoliticsOverviewTable(
    viewingPlayer: CivView,
    overviewScreen: EmpireOverviewScreen,
    persistedData: EmpireOverviewTabPersistableData? = null
) : EmpireOverviewTab(viewingPlayer, overviewScreen) {

    class DiplomacyTabPersistableData(
        var showDiagram: Boolean = false,
        var includeCityStates: Boolean = false
    ) : EmpireOverviewTabPersistableData() {
        override fun isEmpty() = !showDiagram && !includeCityStates
    }
    override val persistableData = (persistedData as? DiplomacyTabPersistableData) ?: DiplomacyTabPersistableData()

    // Widgets that are kept between updates
    private val fixedContent = Table()
    private val civTable = Table().apply {
        defaults().pad(5f)
        background = BaseScreen.skinStrings.getUiBackground(
            "OverviewScreen/DiplomacyOverviewTab/CivTable",
            tintColor = ImageGetter.CHARCOAL
        )
    }

    // Reusable sequences for the Civilizations to display
    private var undefeatedCivs = listOf<Civilization>()
    private var defeatedCivs = listOf<Civilization>()

    private var relevantCivsCount = "?"  // includes unknown civs if player allowed to know
    private var showDiplomacyGroup = false
    private var portraitMode = false


    init {
        top()
        if (persistableData.showDiagram) updateDiagram()
        else updatePoliticsTable()
    }

    override fun getFixedContent() = fixedContent
    override val usesOwnScroll get() = !persistableData.showDiagram

    //region Politics Table View

    private fun updatePoliticsTable() {
        persistableData.showDiagram = false
        clear()
        fixedContent.clear()
        val diagram = OverviewUi.button(flat = true) { updateDiagram(); overviewScreen.resizePage(this) }.apply {
            add(OverviewUi.plainLabel("Relations diagram", 14, OverviewUi.accent)).padRight(pt(8f))
            add(ImageGetter.getImage("OtherIcons/ArrowRight").apply { color = OverviewUi.accent }).size(pt(12f))
            pad(0f, pt(12f), 0f, pt(12f))
        }
        val ownCiv = viewingPlayer.getCiv()
        val identity = Table().apply {
            top().left().pad(pt(16f))
            background = OverviewUi.rounded(OverviewUi.panel, 10f)
            add(getCivInfoTable(ownCiv)).growX().left().padBottom(pt(12f)).row()
            add(getPoliciesTable(ownCiv)).growX().left().padBottom(pt(12f)).row()
            add(getWondersOfCivTable(ownCiv)).growX().left()
        }
        val relations = Table().apply {
            top().left()
            background = OverviewUi.rounded(OverviewUi.panel, 10f)
            add(getPoliticsOfCivTable(ownCiv)).growX().pad(pt(16f)).row()
            // Keep other known civilizations' real information below the current relations.
            for (civ in ownCiv.diplomacyFunctions.getKnownCivsSorted(includeCityStates = false)) {
                add(getCivInfoTable(civ)).growX().pad(pt(16f)).row()
                add(getPoliciesTable(civ)).growX().pad(pt(16f)).row()
                add(getWondersOfCivTable(civ)).growX().pad(pt(16f)).row()
                add(getPoliticsOfCivTable(civ)).growX().pad(pt(16f)).row()
            }
        }
        val relationsPanel = Table().apply {
            background = OverviewUi.rounded(OverviewUi.panel, 10f)
            val header = Table().apply {
                add(OverviewUi.plainLabel("Relations", 15)).growX().left().padLeft(pt(16f))
                add(diagram).height(OverviewUi.touch)
            }
            add(header).growX().row()
            add(ImageGetter.getWhiteDot().apply { color = OverviewUi.border }).growX().height(pt(1f)).row()
            add(OverviewUi.scroll(relations)).grow().minHeight(0f)
        }
        val height = overviewScreen.centerAreaHeight - pt(8f)
        add(OverviewUi.scroll(identity)).width(pt(210f)).height(height).padTop(pt(8f))
        add(relationsPanel).growX().minWidth(0f).height(height).padLeft(pt(14f)).padTop(pt(8f))
    }

    private fun getCivInfoTable(civ: Civilization): Table {
        val civInfoTable = Table(skin).apply { left(); defaults().left() }
        civInfoTable.add(civ.civName.toLabel(fontSize = font(18), hideIcons = true)).row()
        civInfoTable.add(civ.nation.leaderName.toLabel(fontColor = OverviewUi.muted, fontSize = font(13))).padTop(pt(5f)).row()
        civInfoTable.add(civ.tech.era.name.toLabel(fontSize = font(14))).padTop(pt(10f)).row()
        return civInfoTable
    }

    private fun getPoliciesTable(civ: Civilization): Table {
        val policiesTable = Table(skin).apply { left() }
        for (branch in civ.policies.branches)
            if (civ.policies.isAdopted(branch.name)) {
                val count = 1 + branch.policies.count {
                    it.policyBranchType != PolicyBranchType.BranchComplete &&
                    civ.policies.isAdopted(it.name)
                }
                with(OverviewUi) { policiesTable.detailRow(branch.name, count.toString()) }
            }
        return policiesTable
    }

    private fun getWondersOfCivTable(civ: Civilization): Table {
        val wonderTable = Table(skin).apply { left() }
        val wonderInfo = WonderInfo()
        val allWorldWonders = wonderInfo.collectInfo(viewingPlayer.getCiv())

        for (wonder in allWorldWonders) {
            if (wonder.civ == civ) {
                val wonderEntry = Table().apply {
                    left()
                    // Portrait children keep their creation size when the table cell resizes.
                    add(wonder.getImage(pt(20f))).size(pt(20f)).padRight(pt(12f))
                    add(wonder.name.toLabel(fontSize = font(14), hideIcons = true).apply { wrap = true })
                        .growX().minWidth(0f).left()
                }
                if (wonder.location != null && wonder.location.isVisible(viewingPlayer.getCiv())) {
                    wonderEntry.onClick {
                        val worldScreen = UncivGame.Current.resetToWorldScreen()
                        worldScreen.mapHolder.setCenterPosition(wonder.location.position)
                    }
                }
                wonderTable.add(wonderEntry).growX().minWidth(0f).left().minHeight(pt(40f)).row()
            }
        }

        return wonderTable
    }

    @Readonly
    private fun getCivName(otherciv: Civilization): String {
        if (viewingPlayer.getCiv().knows(otherciv) || otherciv == viewingPlayer.getCiv()) {
            return otherciv.civName
        }
        return "an unknown civilization"
    }

    private fun getPoliticsOfCivTable(civ: Civilization): Table {
        val politicsTable = Table(skin).apply { left(); defaults().left().minHeight(pt(40f)) }

        if (!viewingPlayer.getCiv().knows(civ) && civ != viewingPlayer.getCiv())
            return politicsTable

        if (civ.isDefeated()) {
            politicsTable.add("{Defeated} ${Fonts.death}".toLabel())
            return politicsTable
        }

        // wars
        for (otherCiv in civ.getKnownCivs()) {
            if (civ.isAtWarWith(otherCiv)) {
                val warText = ColorMarkupLabel("At war with [${getCivName(otherCiv)}]", Color.RED, fontSize = font(14))
                politicsTable.add(warText).row()
            }
        }
        politicsTable.row()

        // defensive pacts and declaration of friendships
        for (otherCiv in civ.getKnownCivs()) {
            if (civ.getDiplomacyManager(otherCiv)?.hasFlag(DiplomacyFlags.DefensivePact) == true) {
                val friendText = ColorMarkupLabel("Defensive pact with [${getCivName(otherCiv)}]", Color.CYAN, fontSize = font(14))
                val turnsLeftText = " (${civ.getDiplomacyManager(otherCiv)?.getFlag(DiplomacyFlags.DefensivePact)} ${Fonts.turn})".toLabel()
                politicsTable.add(friendText)
                politicsTable.add(turnsLeftText).row()
            } else if (civ.getDiplomacyManager(otherCiv)?.hasFlag(DiplomacyFlags.DeclarationOfFriendship) == true) {
                val friendText = ColorMarkupLabel("Friends with [${getCivName(otherCiv)}]", Color.GREEN, fontSize = font(14))
                val turnsLeftText = " (${civ.getDiplomacyManager(otherCiv)?.getFlag(DiplomacyFlags.DeclarationOfFriendship)} ${Fonts.turn})".toLabel()
                politicsTable.add(friendText)
                politicsTable.add(turnsLeftText).row()
            }
        }
        politicsTable.row()

        // denounced civs
        for (otherCiv in civ.getKnownCivs()) {
            if (civ.getDiplomacyManager(otherCiv)?.hasFlag(DiplomacyFlags.Denunciation) == true) {
                val denouncedText = ColorMarkupLabel("Denounced [${getCivName(otherCiv)}]", Color.RED, fontSize = font(14))
                val turnsLeftText = "(${civ.getDiplomacyManager(otherCiv)?.getFlag(DiplomacyFlags.Denunciation)} ${Fonts.turn})".toLabel()
                politicsTable.add(denouncedText)
                politicsTable.add(turnsLeftText).row()
            }
        }
        politicsTable.row()

        //allied CS
        for (cityState in gameInfo.getAliveCityStates()) {
            if (cityState.getDiplomacyManager(civ)?.isRelationshipLevelEQ(RelationshipLevel.Ally) == true) {
                val alliedText = ColorMarkupLabel("Allied with [${getCivName(cityState)}]", Color.CYAN, fontSize = font(14))
                politicsTable.add(alliedText).row()
            }
        }

        return politicsTable
    }

    //endregion
    //region Diagram View ("Ball of Yarn")

    // Refresh content and determine landscape/portrait layout
    private fun updateDiagram() {
        persistableData.showDiagram = true
        val politicsButton = "Show global politics".toTextButton().onClick {
            updatePoliticsTable()
            overviewScreen.resizePage(this)  // Or else the header stays curernt size, which with any non-empty diagram is most of the client area
        }

        val toggleCityStatesButton: TextButton = Constants.cityStates.toTextButton().apply {
            onClick {
                persistableData.includeCityStates = !persistableData.includeCityStates
                updateDiagram()
            }
        }

        val civTableScroll = AutoScrollPane(civTable).apply {
            setOverscroll(false, false)
        }
        val floatingTable = Table().apply {
            add(toggleCityStatesButton).pad(10f).row()
            add(politicsButton).row()
            add(civTableScroll.addBorder(2f, Color.WHITE)).pad(10f)
        }

        val hideCivsCount = viewingPlayer.getCiv().shouldHideCivCount() ||
            persistableData.includeCityStates && viewingPlayer.getCiv().hideCityStateCount()
        relevantCivsCount = if (hideCivsCount) "?"
            else gameInfo.civilizations.count {
                !it.isSpectator() && !it.isBarbarian && (persistableData.includeCityStates || !it.isCityState)
            }.tr()
        undefeatedCivs = (if (viewingPlayer.isSpectator()) emptyList() else listOf(viewingPlayer.getCiv())) +
                viewingPlayer.getCiv().diplomacyFunctions.getKnownCivsSorted(persistableData.includeCityStates)
        defeatedCivs = viewingPlayer.getCiv().diplomacyFunctions.getKnownCivsSorted(persistableData.includeCityStates, true)
            .filter { it.isDefeated() }.toList()

        clear()
        fixedContent.clear()

        showDiplomacyGroup = undefeatedCivs.any { it != viewingPlayer.getCiv() }
        updateCivTable(2)
        portraitMode = !showDiplomacyGroup ||
                civTable.minWidth > overviewScreen.stage.width / 2 ||
                overviewScreen.isPortrait()
        val table = if (portraitMode) this else fixedContent

        if (showDiplomacyGroup) {
            val diplomacySize = (overviewScreen.stage.width - (if (portraitMode) 0f else civTable.minWidth))
                .coerceAtMost(overviewScreen.centerAreaHeight)
            val diplomacyGroup = GlobalPoliticsDiagramGroup(undefeatedCivs, diplomacySize)
            table.add(diplomacyGroup).top()
        }

        if (portraitMode) {
            if (showDiplomacyGroup) table.row()
            val columns = 2 * (overviewScreen.stage.width / civTable.minWidth).roundToInt()
            if (columns > 2) {
                updateCivTable(columns)
                if (civTable.minWidth > overviewScreen.stage.width)
                    updateCivTable(columns - 2)
            }
        }

        table.add(floatingTable)
        toggleCityStatesButton.style = if (persistableData.includeCityStates) {
            BaseScreen.skin.get("negative", TextButton.TextButtonStyle::class.java)
        } else {
            BaseScreen.skin.get("positive", TextButton.TextButtonStyle::class.java)
        }
        civTableScroll.setScrollingDisabled(portraitMode, portraitMode)
    }

    /** Same as [Civilization.shouldHideCivCount] but for City-States instead of Major Civs */
    @Readonly
    private fun Civilization.hideCityStateCount(): Boolean {
        if (!gameInfo.gameParameters.randomNumberOfCityStates) return false
        val knownCivs = 1 + getKnownCivs().count { it.isCityState }
        if (knownCivs >= gameInfo.gameParameters.maxNumberOfCityStates) return false
        if (hasUnique(UniqueType.OneTimeRevealEntireMap)) return false
        return true
    }

    private fun updateCivTable(columns: Int) = civTable.apply {
        clear()
        addTitleInfo(columns)
        addCivsCategory(columns, "alive", undefeatedCivs.filter { it != viewingPlayer.getCiv() })
        addCivsCategory(columns, "defeated", defeatedCivs)
        layout()
    }

    private fun getCivMiniTable(civInfo: Civilization): Table {
        val table = Table()
        table.add(ImageGetter.getNationPortrait(civInfo.nation, 25f)).pad(5f)
        table.add(civInfo.civName.toLabel(hideIcons = true)).left().padRight(10f)
        table.touchable = Touchable.enabled
        table.onClick {
            if (civInfo.isDefeated() || viewingPlayer.isSpectator() || civInfo == viewingPlayer.getCiv()) return@onClick
            UncivGame.Current.pushScreen{
                DiplomacyScreen(viewingPlayer, viewingPlayer.gameView.getForeignCivView(civInfo))
            }
        }
        return table
    }

    private fun Table.addTitleInfo(columns: Int) {
        add("[$relevantCivsCount] Civilizations in the game".toLabel()).colspan(columns).row()
        if (!viewingPlayer.isSpectator()) {
            add("Our Civilization:".toLabel()).colspan(columns).left().padLeft(10f).padTop(10f).row()
            add(getCivMiniTable(viewingPlayer.getCiv())).left()
            val scoreText = if (viewingPlayer.getCiv().isDefeated()) Fonts.death.toString()
                else viewingPlayer.getCiv().calculateTotalScore().toInt().tr()
            add(scoreText.toLabel()).left().row()
        }
        val turnsTillNextDiplomaticVote = viewingPlayer.getCiv().getTurnsTillNextDiplomaticVote() ?: return
        add("Turns until the next\ndiplomacy victory vote: [$turnsTillNextDiplomaticVote]".toLabel()).colspan(columns).row()
    }

    private fun Table.addCivsCategory(columns: Int, aliveOrDefeated: String, civs: List<Civilization>) {
        addSeparator()
        val count = civs.count()
        add("Known and $aliveOrDefeated ([$count])".toLabel())
            .pad(5f).colspan(columns).row()
        if (count == 0) return
        addSeparator()

        var currentColumn = 0
        var lastCivWasMajor = false
        fun advanceCols(delta: Int) {
            currentColumn += delta
            if (currentColumn >= columns) {
                row()
                currentColumn = 0
            }
            lastCivWasMajor = delta == 2
        }

        for (civ in civs) {
            if (lastCivWasMajor && civ.isCityState)
                advanceCols(columns)
            add(getCivMiniTable(civ)).left()
            if (civ.isCityState) {
                advanceCols(1)
            } else {
                add(civ.calculateTotalScore().toInt().toLabel()).left()
                advanceCols(2)
            }
        }
    }

    //endregion
}
