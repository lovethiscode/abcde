package com.unciv.ui.screens.overviewscreen

import com.unciv.ui.screens.overviewscreen.OverviewUi.pt
import com.unciv.ui.screens.overviewscreen.OverviewUi.font
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.unciv.UncivGame
import com.unciv.logic.city.City
import com.unciv.logic.map.tile.Tile
import com.unciv.logic.trade.TradeOfferType
import com.unciv.models.ruleset.tile.ResourceSupplyList
import com.unciv.models.ruleset.tile.ResourceType
import com.unciv.models.ruleset.tile.TileResource
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.translations.tr
import com.unciv.ui.components.input.onClick
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.view.CivView


class ResourcesOverviewTab(
    viewingPlayer: CivView,
    overviewScreen: EmpireOverviewScreen,
    persistedData: EmpireOverviewTabPersistableData? = null
) : EmpireOverviewTab(viewingPlayer, overviewScreen) {
    class ResourcesTabPersistableData(
        var vertical: Boolean = false
    ) : EmpireOverviewTabPersistableData() {
        override fun isEmpty() = !vertical
    }
    override val persistableData = (persistedData as? ResourcesTabPersistableData) ?: ResourcesTabPersistableData()

    companion object {
        private const val iconSize = 50f
        private const val defaultPad = 10f
        private const val tooltipSize = 24f
        private const val total = "Total" // Used both as label and as invisible pseudo-origin
    }

    private val resourceDrilldown: ResourceSupplyList = viewingPlayer.getCiv().detailedCivResources
    private val extraDrilldown: ResourceSupplyList = getExtraDrilldown()
    private val allResources = resourceDrilldown.asSequence() + extraDrilldown  // Do not materialize into another ResourceSupplyList, we have intentional zero amounts

    // Order of source ResourceSupplyList: by tiles, enumerating the map in that spiral pattern
    // UI should not surprise player, thus we need a deterministic and guessable order
    private val resources: List<TileResource> = allResources
        .map { it.resource }
        .filter {
            it.resourceType != ResourceType.Bonus &&
            !it.hasUnique(UniqueType.NotShownOnWorldScreen, viewingPlayer.getCiv().state) &&
            !it.isCityWide // These are Civ-wide resources, so don't show the city-wide ones.
        }
        .distinct()
        .sortedWith(
            compareBy<TileResource> { it.resourceType }
                .thenBy(UncivGame.Current.settings.getCollatorFromLocale()) { it.name.tr(hideIcons = true) }
        )
        .toList()
    private val origins = resourceDrilldown.origins()
    private val extraOrigins: List<ExtraInfoOrigin> = extraDrilldown.asSequence()
        .mapNotNull { ExtraInfoOrigin.safeValueOf(it.origin) }.distinct().toList()

    private fun ResourceSupplyList.getLabel(resource: TileResource, origin: String): Label? {
        fun isAlliedAndUnimproved(tile: Tile): Boolean {
            val owner = tile.getOwner() ?: return false
            if (owner != viewingPlayer.getCiv() && !(owner.isCityState && owner.allyCiv == viewingPlayer.getCiv())) return false
            return tile.countAsUnimproved()
        }
        val amount = get(resource, origin)?.amount ?: return null
        val label = getLabel(resource, origin, amount)
        if (origin == ExtraInfoOrigin.Unimproved.name)
            label.onClick { overviewScreen.showOneTimeNotification(
                gameInfo.getExploredResourcesNotification(viewingPlayer.getCiv(), resource, filter = ::isAlliedAndUnimproved)
            ) }
        return label
    }

    private fun ResourceSupplyList.getTotalLabel(resource: TileResource)
        = getLabel(resource, total, sumBy(resource))

    private fun ResourceSupplyList.getLabel(resource: TileResource, origin: String, amount: Int): Label {
        val color = if (isDeficit(resource, origin, amount)) Color.RED else Color.WHITE
        val text = (if (resource.isStockpiled && amount >= 0 && origin != ExtraInfoOrigin.Stockpile.name) "+" else "") + amount.toString()
        return text.toLabel(color, fontSize = font(14))
    }

    private fun ResourceSupplyList.getOrZero(resource: TileResource, origin: String) =
        get(resource, origin)?.amount ?: 0

    private fun ResourceSupplyList.isDeficit(resource: TileResource, origin: String, amount: Int): Boolean {
        if (resource.isStockpiled && origin != ExtraInfoOrigin.Stockpile.name)
            return amount < -getOrZero(resource, ExtraInfoOrigin.Stockpile.name) // Negative income won't run into deficit next turn
        if (origin != total)
            return amount < 0 && sumBy(resource) < 0 // E.g. units consuming is only bad if overcommitted
        if (amount != 0) return amount < 0
        return extraDrilldown.getOrZero(resource, ExtraInfoOrigin.DemandingWLTK.name) > 0
    }

    private fun getResourceImage(name: String) =
        ImageGetter.getResourcePortrait(name, pt(28f)).apply {
            onClick { overviewScreen.showOneTimeNotification(
                gameInfo.getExploredResourcesNotification(viewingPlayer.getCiv(), name)
            ) }
        }
    private fun TileResource.getLabel(): Label {
        val deficit = resourceDrilldown.isDeficit(this, total, resourceDrilldown.sumBy(this))
        val color = if (deficit) Color.RED else Color.WHITE
        val label = name.toLabel(color, fontSize = font(16), hideIcons = true)
        label.onClick {
            overviewScreen.openCivilopedia(makeLink())
        }
        return label
    }

    private enum class ExtraInfoOrigin(
        val horizontalCaption: String,
        val verticalCaption: String,
        val tooltip: String
    ) {
        Unimproved("Unimproved", "Unimproved",
            "Number of tiles with this resource\nin your territory, without an\nappropriate improvement to use it"),
        CelebratingWLTK("We Love The King Day", "WLTK+",
            "Number of your cities celebrating\n'We Love The King Day' thanks\nto access to this resource"),
        DemandingWLTK("WLTK demand", "WLTK-",
            "Number of your cities\ndemanding this resource for\n'We Love The King Day'"),
        TradeOffer("Trade offer","Trade offer", "Resources we're offering in trades"),
        Stockpile("Stockpiled resources", "Stockpile", "The currently accumulated stockpile amount"),
        ;

        companion object {
            fun safeValueOf(name: String) = entries.firstOrNull { it.name == name }
        }
    }
    private val fixedContent = Table()

    init {
        defaults().pad(0f)
        top()
        fixedContent.defaults().pad(0f)


        update()
    }

    override fun getFixedContent() = fixedContent

    private fun update() {
        clear()
        fixedContent.clear()
        updateResourceList()
    }

    private fun updateResourceList() {
        fixedContent.add().height(pt(8f))
        for (resource in resources) {
            val entry = Table().apply {
                background = OverviewUi.surface(OverviewUi.panel)
                pad(pt(10f), pt(16f), pt(10f), pt(16f))
                add(getResourceImage(resource.name)).size(pt(28f)).padRight(pt(12f))
                val identity = Table().apply {
                    add(resource.getLabel()).left().row()
                    add("${resource.resourceType.name} resource".toLabel(fontColor = OverviewUi.muted, fontSize = font(13))).left().padTop(6f)
                }
                add(identity).width(160f).left().padRight(20f)
                val sources = Table().apply {
                    for (origin in origins) {
                        val label = resourceDrilldown.getLabel(resource, origin) ?: continue
                        add(origin.removeSuffix("+").toLabel(fontColor = OverviewUi.muted, fontSize = font(13))).left().padRight(16f)
                        add(label).right().row()
                    }
                    for (origin in extraOrigins) {
                        val label = extraDrilldown.getLabel(resource, origin.name) ?: continue
                        add(origin.horizontalCaption.toLabel(fontColor = OverviewUi.muted, fontSize = font(13))).left().padRight(16f)
                        add(label).right().row()
                    }
                }
                add(sources).growX().minWidth(0f).left()
                val total = Table().apply {
                    add(resourceDrilldown.getTotalLabel(resource)).row()
                    add("Available".toLabel(fontColor = OverviewUi.muted, fontSize = font(13))).padTop(6f)
                }
                add(total).width(110f).padLeft(16f)
            }
            add(entry).width(overviewScreen.contentWidth).minHeight(pt(66f)).padBottom(pt(1f)).row()
        }
    }

    private fun Tile.countAsUnimproved(): Boolean {
        val resource = tileResource
        return viewingPlayer.getCiv().canSeeResource(resource) &&
            resource.resourceType != ResourceType.Bonus &&
            !providesResources(viewingPlayer.getCiv())
    }

    private fun getExtraDrilldown(): ResourceSupplyList {
        val newResourceSupplyList = ResourceSupplyList(keepZeroAmounts = true)

        fun City.addUnimproved() {
            for (tile in getTiles())
                if (tile.countAsUnimproved())
                    newResourceSupplyList.add(tile.tileResource!!, ExtraInfoOrigin.Unimproved.name)
        }

        // Show resources relevant to WTLK day and/or needing improvement
        for (city in viewingPlayer.getCiv().cities) {
            if (city.demandedResource.isNotEmpty()) {
                val wltkResource = gameInfo.ruleset.tileResources[city.demandedResource]!!
                if (city.isWeLoveTheKingDayActive()) {
                    newResourceSupplyList.add(wltkResource, ExtraInfoOrigin.CelebratingWLTK.name)
                } else {
                    newResourceSupplyList.add(wltkResource, ExtraInfoOrigin.DemandingWLTK.name)
                }
            }
            city.addUnimproved()
        }

        for (otherCiv in viewingPlayer.getCiv().getKnownCivs()) {
            // Show resources received through trade
            for (trade in otherCiv.tradeRequests.filter { it.requestingCiv == viewingPlayer.getCiv().civID })
                for (offer in trade.trade.theirOffers.filter { it.type == TradeOfferType.Strategic_Resource || it.type == TradeOfferType.Luxury_Resource })
                    newResourceSupplyList.add(gameInfo.ruleset.tileResources[offer.name]!!, ExtraInfoOrigin.TradeOffer.name, offer.amount)

            // Show resources your city-state allies have left unimproved
            if (!otherCiv.isCityState || otherCiv.allyCiv != viewingPlayer.getCiv()) continue
            for (city in otherCiv.cities)
                city.addUnimproved()
        }

        /** Show unlocked **strategic** resources even if you have no access at all */
        for (resource in gameInfo.ruleset.tileResources.values) {
            if (resource.resourceType != ResourceType.Strategic) continue
            if (viewingPlayer.canSeeResource(resource))
                newResourceSupplyList.add(resource, "No source", 0)
        }

        // A row for stockpiled resources if required.
        // Note viewingPlayer.detailedCivResources will NOT include stockpiles if current income is zero.
        for (resourceName in viewingPlayer.getCiv().resourceStockpiles.keys) {
            val resource = gameInfo.ruleset.tileResources[resourceName] ?: continue
            if (resource.hasUnique(UniqueType.NotShownOnWorldScreen, viewingPlayer.getCiv().state)) continue
            newResourceSupplyList.add(resource, ExtraInfoOrigin.Stockpile.name, viewingPlayer.getCiv().getResourceAmount(resource))
        }

        return newResourceSupplyList
    }
}
