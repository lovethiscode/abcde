package com.unciv.ui.screens.worldscreen.topbar

import com.unciv.ui.components.fonts.TextRole
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.unciv.logic.civilization.Civilization
import com.unciv.models.ruleset.tile.ResourceType
import com.unciv.models.ruleset.tile.TileResource
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.translations.tr
import com.unciv.ui.components.MayaCalendar
import com.unciv.ui.components.YearTextUtil
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.extensions.setFontSize
import com.unciv.ui.screens.worldscreen.BattlefieldHud
import com.unciv.ui.screens.worldscreen.BattlefieldGeometry
import com.unciv.ui.components.widgets.UiNavigation
import com.unciv.ui.components.extensions.toStringSigned
import com.unciv.ui.components.fonts.Fonts
import com.unciv.ui.components.input.onClick
import com.unciv.ui.components.input.onClickSuppressive
import com.unciv.ui.components.widgets.ScalingTableWrapper
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.screens.overviewscreen.EmpireOverviewCategories
import com.unciv.ui.screens.victoryscreen.VictoryScreen

internal class WorldScreenTopBarResources(topbar: WorldScreenTopBar) : ScalingTableWrapper() {
    private val turnsLabel = "Turns: 0/400".toLabel(TextRole.Body)
    internal val battlefieldTurnLabel get() = turnsLabel
    private data class ResourceActors(val resource: TileResource, val label: Label, val icon: Actor)
    private val resourceActors = ArrayList<ResourceActors>(12)
    private val resourcesWrapper = Table()
    internal val battlefieldResources get() = resourcesWrapper
    val worldScreen = topbar.worldScreen

    // Note: For a proper functioning of the "shift floating buttons down when cramped" feature, it is
    // important that this entire Widget has only the bare minimum padding to its left and right.
    // #7193 did let the resources and turn label swap places, with the side effect of the "first resource getting extra left padding"
    // now being on the outside - the buttons moved out of the way despite there being visually ample space.

    private companion object {
        const val defaultPad = 5f
        const val extraPadBetweenLabelAndResources = 20f
        const val bottomPad = 10f
        const val extraPadBetweenResources = 5f
        const val outerHorizontalPad = 2f
        const val iconSize = 20f
        const val resourceAmountDescentTweak = 3f
    }

    init {
        defaults().space(extraPadBetweenLabelAndResources)
            .pad(defaultPad, outerHorizontalPad, bottomPad, outerHorizontalPad)

        resourcesWrapper.defaults().space(defaultPad)
        resourcesWrapper.touchable = Touchable.enabled

        turnsLabel.onClickSuppressive {
            if (worldScreen.selectedCiv.isLongCountDisplay()) {
                val gameInfo = worldScreen.selectedCiv.gameInfo
                MayaCalendar.openPopup(worldScreen, worldScreen.selectedCiv, gameInfo.getYear())
            } else {
                worldScreen.game.pushScreen{ VictoryScreen(worldScreen) }
            }
        }
        resourcesWrapper.onClick {
            worldScreen.openEmpireOverview(EmpireOverviewCategories.Resources)
        }

        val strategicResources = worldScreen.gameInfo.ruleset.tileResources.values
            .filter { it.resourceType == ResourceType.Strategic && !it.isCityWide }
        for (resource in strategicResources) {
            val resourceImage = ImageGetter.getResourcePortrait(resource.name, iconSize).let {
                if (BattlefieldHud.enabled) BattlefieldGeometry.icon(it) else it
            }
            val resourceLabel = "0".toLabel(TextRole.Body)
            resourceActors += ResourceActors(resource, resourceLabel, resourceImage)
        }

        add(turnsLabel)

        // in case the icons are configured higher than a label, we add a dummy - height will be measured once before it's updated
        if (resourceActors.isNotEmpty()) {
            resourcesWrapper.add(resourceActors[0].icon)
            add(resourcesWrapper)
        }
    }

    fun update(civInfo: Civilization) {
        resetScale()
        if (BattlefieldHud.enabled) {
            turnsLabel.setFontSize(BattlefieldHud.font(TextRole.CompactData))
            defaults().space(BattlefieldHud.points(4f)).pad(0f).minHeight(UiNavigation.minimumTouchSize())
        }

        val civilWar = com.unciv.logic.campaign.CivilWarCampaign.isActive(civInfo.gameInfo)
        val yearText = if (civilWar) "[1861] AD".tr() else YearTextUtil.toYearText(
            civInfo.gameInfo.getYear(), civInfo.isLongCountDisplay()
        )
        val displayedTurn = civInfo.gameInfo.campaign?.let {
            if (civilWar) (it.completedRounds + 1).coerceAtMost(it.definition.hardTurnLimit) else it.completedRounds + 1
        } ?: civInfo.gameInfo.turns
        turnsLabel.setText(Fonts.turn + "\u2004" + displayedTurn.tr() + "\u2004|\u2004" + yearText) // U+2004: Three-Per-Em Space

        civInfo.gameInfo.endless?.let {
            turnsLabel.setText("R ${it.round}  |  C ${it.completedRounds}")
        }

        resourcesWrapper.clearChildren()
        val civResources = civInfo.getCivResourcesByName()
        val civResourceSupply = civInfo.getCivResourceSupply()
        for ((index, resourceActors) in resourceActors.withIndex()) {
            val (resource, label, icon) = resourceActors

            if (resource.hasUnique(UniqueType.NotShownOnWorldScreen, civInfo.state)) continue

            val amount = civResources[resource.name] ?: 0

            if (!civInfo.canSeeResource(resource) && amount == 0) // You cannot trade for resources you cannot process yourself yet
                continue

            val iconCell = resourcesWrapper.add(icon).padLeft(if (index == 0) 0f else extraPadBetweenResources)
            if (BattlefieldHud.enabled) {
                iconCell.size(BattlefieldHud.points(16f))
                label.setFontSize(BattlefieldHud.font(TextRole.Supporting))
            }

            if (!resource.isStockpiled)
                label.setText(amount.tr())
            else {
                val perTurn = civResourceSupply.firstOrNull { it.resource == resource }?.amount ?: 0
                if (perTurn == 0) label.setText(amount.tr())
                else label.setText("${amount.tr()} (${perTurn.toStringSigned()})")
            }
            resourcesWrapper.add(label).padTop(resourceAmountDescentTweak)  // digits don't have descenders, so push them down a little
        }

        scaleToWidth(worldScreen.stage.width)
    }
}
