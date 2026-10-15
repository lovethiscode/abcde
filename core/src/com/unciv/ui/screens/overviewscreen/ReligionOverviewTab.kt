package com.unciv.ui.screens.overviewscreen

import com.unciv.ui.screens.overviewscreen.OverviewUi.pt
import com.unciv.ui.screens.overviewscreen.OverviewUi.font
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.utils.Align
import com.unciv.Constants
import com.unciv.models.Religion
import com.unciv.models.ruleset.Belief
import com.unciv.models.translations.fillPlaceholders
import com.unciv.models.translations.tr
import com.unciv.ui.components.widgets.ExpanderTab
import com.unciv.ui.screens.civilopediascreen.MarkupRenderer
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.components.extensions.addSeparator
import com.unciv.ui.components.input.onClick
import com.unciv.ui.components.extensions.setFontSize
import com.unciv.ui.components.extensions.toLabel
import com.unciv.view.CivView
import kotlin.math.max

class ReligionOverviewTab(
    viewingPlayer: CivView,
    overviewScreen: EmpireOverviewScreen,
    persistedData: EmpireOverviewTabPersistableData? = null
) : EmpireOverviewTab(viewingPlayer, overviewScreen) {
    class ReligionTabPersistableData(
        var selectedReligion: String? = null
    ) : EmpireOverviewTabPersistableData()
    override val persistableData = (persistedData as? ReligionTabPersistableData) ?: ReligionTabPersistableData()

    private val civStatsTable = Table()
    private val religionButtons = Table().apply { top() }
    private val religionButtonLabel = "Click an icon to see the stats of this religion".toLabel()
    private val statsTable = Table()
    private val beliefsTable = Table()

    override val usesOwnScroll = true

    init {
        civStatsTable.defaults().left().pad(5f)
        civStatsTable.addCivSpecificStats()

        if (persistableData.selectedReligion == null)
            persistableData.selectedReligion = viewingPlayer.getCiv().religionManager.religion?.name
                ?: gameInfo.religions.values.firstOrNull()?.name
        loadReligionButtons()
        statsTable.defaults().left().pad(0f)
        beliefsTable.defaults().padBottom(16f)
        loadReligion(persistableData.selectedReligion)
        val detail = Table().apply {
            background = OverviewUi.rounded(OverviewUi.panel, 10f)
            top().left().pad(pt(16f))
            add(religionButtonLabel).left().padBottom(pt(12f)).row()
            add(statsTable).growX().row()
            add(civStatsTable).growX().padTop(pt(12f)).row()
            add(beliefsTable).growX().minWidth(0f).padTop(pt(12f))
        }
        val split = Table().apply {
            add(OverviewUi.scroll(religionButtons)).width(pt(170f)).growY()
            add(OverviewUi.scroll(detail)).grow().minWidth(0f).padLeft(pt(14f))
        }
        add(split).width(overviewScreen.contentWidth).height(overviewScreen.centerAreaHeight - pt(8f)).padTop(pt(8f))
    }

    private fun Table.addCivSpecificStats() {
        // This is not Civ-specific, but -oh well- still fits
        val minWidth = max(religionButtonLabel.prefWidth, overviewScreen.stage.width / 3)
        val manager = viewingPlayer.getCiv().religionManager
        val headerText =
            if (viewingPlayer.getCiv().shouldHideCivCount()) "Religions to be founded: [?]"
            else "Religions to be founded: [${manager.remainingFoundableReligions()}]"
        val religionCountExpander = ExpanderTab(
            headerText, fontSize = font(14), headerPad =  5f,
            startsOutOpened = false, defaultPad =  0f, expanderWidth = minWidth,
            onChange = { overviewScreen.resizePage(this@ReligionOverviewTab) }
        ) {
            it.defaults().padTop(10f)
            for ((text, num) in manager.remainingFoundableReligionsBreakdown()) {
                it.add(text.toLabel())
                it.add(num.tr().toLabel(alignment = Align.right)).right().row()
            }
        }
        add(religionCountExpander).colspan(2).growX().row()

        if (manager.canGenerateProphet(ignoreFaithAmount = true)) {
            add("Minimal Faith required for\nthe next [great prophet equivalent]:"
                .fillPlaceholders(manager.getGreatProphetEquivalent()!!.name)
                .toLabel()
            )
            add(
                (manager.faithForNextGreatProphet() + 1)
                .toLabel()
            ).right().row()
        }

        add("Religious status:".toLabel()).left()
        add(manager.religionState.toString().toLabel()).right().row()
    }

    private fun loadReligionButtons() {
        religionButtons.clear()
        val existingReligions: List<Religion> = gameInfo.civilizations.mapNotNull { it.religionManager.religion }
        for (religion in existingReligions) {
            val image = if (religion.isPantheon()) {
                if (viewingPlayer.getCiv().knows(religion.foundingCiv) || viewingPlayer.getCiv() == religion.foundingCiv)
                    ImageGetter.getNationPortrait(religion.foundingCiv.nation, 28f)
                else
                    ImageGetter.getRandomNationPortrait(28f)
            } else {
                    ImageGetter.getReligionPortrait(religion.getIconName(), 28f)
            }
            val button = OverviewUi.button(persistableData.selectedReligion == religion.name) {
                persistableData.selectedReligion = religion.name
                loadReligionButtons()
                loadReligion(religion)
            }.apply {
                add(image).size(28f).padRight(10f)
                add(religion.getReligionDisplayName().toLabel(fontSize = font(14)).apply { wrap = true }).minWidth(0f).growX().left()
                pad(12f)
            }

            religionButtons.add(button).growX().minHeight(OverviewUi.touch).padBottom(1f).row()
        }
    }

    override fun select(selection: String): Float? {
        if (selection.isEmpty()) return null
        persistableData.selectedReligion = selection
        loadReligionButtons()  // so the icon is "highlighted"
        loadReligion(selection)
        return null
    }
    private fun loadReligion(religionName: String?) {
        if (religionName == null) return
        val religion = gameInfo.religions[religionName] ?: return
        loadReligion(religion)
    }
    private fun loadReligion(religion: Religion) {
        statsTable.clear()
        beliefsTable.clear()
        religionButtonLabel.setText(religion.getReligionDisplayName().tr())
        religionButtonLabel.setFontSize(font(18))
        religionButtonLabel.color = com.unciv.models.skins.ExpeditionBlueTheme.text

        for (belief in religion.getAllBeliefsOrdered()) {
            beliefsTable.add(createBeliefDescription(belief)).row()
        }

        val foundingCivName =
            if (viewingPlayer.getCiv().knows(religion.foundingCiv) || viewingPlayer.getCiv() == religion.foundingCiv)
                religion.foundingCivName else Constants.unknownNationName
        with(OverviewUi) {
            statsTable.detailRow("Founding Civ:", foundingCivName)
            if (religion.isMajorReligion()) {
                val holyCity = gameInfo.getCities().firstOrNull { it.isHolyCityOf(religion.name) }
                if (holyCity != null) statsTable.detailRow("Holy City:",
                    if (viewingPlayer.getCiv().hasExplored(holyCity.getCenterTile())) holyCity.name else Constants.unknownNationName)
            }
            val manager = religion.foundingCiv.religionManager
            statsTable.detailRow("Cities following this religion:", manager.numberOfCitiesFollowingThisReligion().toString())
            statsTable.detailRow("Followers of this religion:", manager.numberOfFollowersFollowingThisReligion("in all cities").toString())
        }

        val minWidth = max(statsTable.minWidth, beliefsTable.minWidth) + 5

        statsTable.width = minWidth
        for (cell in beliefsTable.cells) {
            cell.minWidth(minWidth)
        }

        // If we're wider than the container set our ScrollPane to center us
        (parent as? ScrollPane)?.apply {
            layout()
            scrollX = maxX / 2
            updateVisualScroll()
        }
    }

    private fun createBeliefDescription(belief: Belief) =
        MarkupRenderer.render(
            belief.getCivilopediaTextLines(withHeader = true),
            labelWidth = overviewScreen.contentWidth * .65f
        ) {
            overviewScreen.openCivilopedia(it)
        }.apply {
            background = BaseScreen.skinStrings.getUiBackground(
                "OverviewScreen/ReligionOverviewTab/BeliefDescription",
                tintColor = BaseScreen.skinStrings.skinConfig.baseColor
            )
        }
}
