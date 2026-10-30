package com.unciv.ui.screens.cityscreen

import com.unciv.ui.components.fonts.UiTypography
import com.unciv.ui.components.fonts.TextRole
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.unciv.GUI
import com.unciv.logic.city.City
import com.unciv.logic.civilization.LocationAction
import com.unciv.logic.civilization.Notification
import com.unciv.logic.civilization.NotificationIcon
import com.unciv.models.UncivSound
import com.unciv.models.ruleset.Building
import com.unciv.models.ruleset.IConstruction
import com.unciv.models.ruleset.INonPerpetualConstruction
import com.unciv.models.ruleset.IRulesetObject
import com.unciv.models.ruleset.PerpetualConstruction
import com.unciv.models.ruleset.PerpetualConstruction.StatConversion
import com.unciv.models.ruleset.RejectionReason
import com.unciv.models.ruleset.RejectionReasonType
import com.unciv.models.ruleset.unit.BaseUnit
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.setFontSize
import com.unciv.ui.components.SmallButtonStyle
import com.unciv.ui.components.extensions.darken
import com.unciv.ui.components.extensions.disable
import com.unciv.ui.components.extensions.getTurnsToConstructionString
import com.unciv.ui.components.extensions.isEnabled
import com.unciv.ui.components.extensions.toCheckBox
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.fonts.Fonts
import com.unciv.ui.components.input.onClick
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.popups.ConfirmPopup
import com.unciv.ui.popups.closeAllPopups
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.view.CityConstructionsView
import kotlin.sequences.asIterable

/** This is the bottom-right table in the city screen that shows the currently selected construction */
class ConstructionInfoTable(val cityScreen: CityScreen) : Table() {
    private val selectedConstructionTable = Table()
    private val fixedButtons = Table()
    internal fun fixedWorkbenchActions() = fixedButtons
    private val buyButtonFactory = BuyButtonFactory(cityScreen)
    private val cityView get() = cityScreen.cityView

    init {
        selectedConstructionTable.background = BaseScreen.skinStrings.getUiBackground(
            "CityScreen/ConstructionInfoTable/SelectedConstructionTable",
            tintColor = BaseScreen.skinStrings.skinConfig.baseColor.darken(0.5f)
        )
        add(selectedConstructionTable).pad(if (cityScreen.usesWorkbench) 0f else 2f).apply {
            if (cityScreen.usesWorkbench) growX() else fill()
        }
        background = BaseScreen.skinStrings.getUiBackground(
            "CityScreen/ConstructionInfoTable/Background",
            tintColor = Color.WHITE
        )
    }

    fun update(selectedConstruction: IConstruction?) {
        if (cityScreen.usesWorkbench) { background = null; selectedConstructionTable.background = null }
        selectedConstructionTable.clear()  // clears content and listeners
        fixedButtons.clear()

        if (selectedConstruction == null) {
            isVisible = false
            return
        }
        isVisible = true

        updateSelectedConstructionTable(selectedConstruction)

        pack()
    }

    private fun updateSelectedConstructionTable(construction: IConstruction) {
        val cityConstructions = cityView.constructions

        //val selectedConstructionTable = Table()
        selectedConstructionTable.run {
            pad(if (cityScreen.usesWorkbench) 0f else 10f)

            add(ImageGetter.getConstructionPortrait(construction.name, if (cityScreen.usesWorkbench) 28f else 50f).apply {
                val link = (construction as? IRulesetObject)?.makeLink() ?: return@apply
                if (link.isEmpty()) return@apply
                touchable = Touchable.enabled
                this.onClick {
                    cityScreen.openCivilopedia(link)
                }
            }).minSize(if (cityScreen.usesWorkbench) 44f else 0f).pad(if (cityScreen.usesWorkbench) 0f else 5f)

            var buildingText = if (cityScreen.workbenchPhone) "" else construction.name.tr(hideIcons = true)
            val specialConstruction = PerpetualConstruction.perpetualConstructionsMap[construction.name]

            buildingText += specialConstruction?.let { cityView.getProductionTooltip(it) }
                    ?: cityConstructions.getTurnsToConstructionString(construction)

            add(Label(buildingText, BaseScreen.skin).apply { wrap = cityScreen.usesWorkbench; if (cityScreen.usesWorkbench) setFontSize(UiTypography.size(TextRole.Control, unit = if (cityScreen.usesWorkbench) 1f else UiTypography.unitsPerPoint(cityScreen.stage), legacySize = 15)) }).apply {
                if (cityScreen.usesWorkbench) width(cityScreen.workbenchWidth - 52f)
            }.expandX().row()  // already translated

            val description = when (construction) {
                is BaseUnit -> cityView.getUnitDescription(construction)
                is Building -> cityView.getBuildingDescription(construction)
                is StatConversion -> construction.description.replace("[rate]", "[${cityView.getConversionRate(construction)}]").tr()
                is PerpetualConstruction -> construction.description.tr()
                else -> ""  // Should never happen
            }

            val descriptionLabel = Label(description, BaseScreen.skin)  // already translated
            descriptionLabel.wrap = true
            if (cityScreen.usesWorkbench) descriptionLabel.setFontSize(UiTypography.size(TextRole.Body, unit = if (cityScreen.usesWorkbench) 1f else UiTypography.unitsPerPoint(cityScreen.stage), legacySize = 14))
            add(descriptionLabel).colspan(2).width(if (cityScreen.usesWorkbench) cityScreen.workbenchWidth else cityScreen.stage.width / if(cityScreen.isCrampedPortrait()) 3 else 4)

            if (construction is INonPerpetualConstruction && "require" in description)
                tryAddShowMissingCities(cityConstructions, construction)

            if (cityConstructions.isBuilt(construction.name)) {
                showSellButton(construction, if (cityScreen.workbenchPhone) fixedButtons else selectedConstructionTable)
            } else if (!cityScreen.workbenchPhone && buyButtonFactory.hasBuyButtons(construction)) {
                row()
                for (button in buyButtonFactory.getBuyButtons(construction)) {
                    selectedConstructionTable.add(button).padTop(5f).colspan(2).center().row()
                }
            }
            if (construction is BaseUnit) {
                val baseUnit = construction.name
                val buildUnitWithPromotions = cityView.getUnitShouldUseSavedPromotion(baseUnit)

                if (buildUnitWithPromotions != null) {
                    row()
                    add("Use default promotions".toCheckBox(buildUnitWithPromotions, unit = if (cityScreen.usesWorkbench) 1f else UiTypography.unitsPerPoint(cityScreen.stage)) {
                        cityView.trySetUnitShouldUseSavedPromotion(baseUnit, it)
                    }).colspan(2).center()
                }
            }
        }
    }

    private fun Table.tryAddShowMissingCities(cityConstructions: CityConstructionsView, construction: INonPerpetualConstruction) {
        val rejections = cityConstructions.getRejectionReasons(construction)
            .filter { it.type == RejectionReasonType.RequiresBuildingInAllCities && it.extraData != null }
        for (rejection in rejections) {
            val (requiredBuilding, missingCities) = rejection.extraData as RejectionReason.RequiresBuildingInAllCitiesExtraData
            if (missingCities.isEmpty()) return
            fun addButton(label: String, action: () -> Unit) {
                val button = label.toTextButton(SmallButtonStyle())
                button.onClick(action)
                row()
                add(button).padTop(5f).colspan(2).center()
            }
            if (missingCities.size == 1) {
                addButton("Show [${missingCities[0].name}]") { jumpToCity(missingCities[0]) }
            } else {
                addButton("Show cities missing requirements") { notifyMissingCities(missingCities, requiredBuilding, construction) }
            }
        }
    }
    //TODO Unify with the one in CityScreenTileTable?
    private fun jumpToCity(city: City) {
        val otherCityView = cityView.gameView.getCityView(city)
        cityScreen.game.replaceCurrentScreen { CityScreen(otherCityView) }
    }
    private fun notifyMissingCities(missingCities: List<City>, requiredBuilding: String, construction: INonPerpetualConstruction) {
        val notification = Notification(
            "[${missingCities.size}] cities need a [$requiredBuilding] to allow construction of [$construction]",
            arrayOf(NotificationIcon.City),
            actions = LocationAction(missingCities.asSequence().map { it.location }).asIterable()
        )
        val worldScreen = GUI.getWorldScreen()
        worldScreen.notificationsScroll.oneTimeNotification = notification
        GUI.resetToWorldScreen()
        notification.resetExecuteRoundRobin()
        notification.execute(worldScreen)
    }

    // Show sell button if construction is a currently sellable building
    private fun showSellButton(
        construction: IConstruction,
        target: Table = selectedConstructionTable
    ) {
        if (construction is Building && construction.isSellable()) {
            target.run {
                val sellAmount = cityView.getGoldForSellingBuilding(construction.name)
                val sellText = "{Sell} $sellAmount " + Fonts.gold
                val sellBuildingButton = sellText.toTextButton(unit = if (cityScreen.usesWorkbench) 1f else UiTypography.unitsPerPoint(cityScreen.stage))
                row()
                add(sellBuildingButton).padTop(5f).colspan(2).center()

                val isFree = cityScreen.hasFreeBuilding(construction)
                val enableSell = !isFree &&
                    !cityView.isPuppet() &&
                    cityScreen.canChangeState &&
                    (!cityView.hasSoldBuildingThisTurn() || cityView.isGodModeEnabled())
                sellBuildingButton.isEnabled = enableSell
                if (enableSell)
                    sellBuildingButton.onClick(UncivSound.Coin) {
                        sellBuildingButton.disable()
                        sellBuildingClicked(construction, sellText)
                    }

                if (cityView.hasSoldBuildingThisTurn() && !cityView.isGodModeEnabled()
                        || cityView.isPuppet()
                        || !cityScreen.canChangeState)
                    sellBuildingButton.disable()
            }
        }
    }

    private fun sellBuildingClicked(construction: Building, sellText: String) {
        cityScreen.closeAllPopups()

        ConfirmPopup(
            cityScreen,
            "Are you sure you want to sell this [${construction.name}]?",
            sellText,
            restoreDefault = {
                cityScreen.updateAsync()
            },
            presentation = ConfirmPopup.Presentation("Sell [${construction.name}]?", cityView.name,
                "Selling removes this building and its effects from the city.",
                listOf("Gold received" to cityView.getGoldForSellingBuilding(construction.name).toString()),
                construction.getShortDescription(multiline = true), sellText)
        ) {
            sellBuildingConfirmed(construction)
        }.open()
    }

    private fun sellBuildingConfirmed(construction: Building) {
        cityView.trySellBuilding(construction)
        cityScreen.clearSelection()
        cityScreen.updateAsync()
    }

}
