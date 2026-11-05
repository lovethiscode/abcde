package com.unciv.ui.screens.worldscreen.topbar

import com.unciv.ui.components.fonts.UiTypography
import com.unciv.ui.components.fonts.TextRole
import com.badlogic.gdx.scenes.scene2d.Group
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.unciv.Constants
import com.unciv.logic.civilization.Civilization
import com.unciv.models.stats.Stats
import com.unciv.models.skins.ExpeditionBlueTheme
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.colorFromRGB
import com.unciv.ui.components.extensions.setFontColor
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.extensions.setFontSize
import com.unciv.ui.components.widgets.UiNavigation
import com.unciv.ui.screens.worldscreen.BattlefieldHud
import com.unciv.ui.components.extensions.toStringSigned
import com.unciv.ui.components.fonts.Fonts
import com.unciv.ui.components.input.onClick
import com.unciv.ui.components.widgets.ScalingTableWrapper
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.overviewscreen.EmpireOverviewCategories
import com.unciv.ui.screens.overviewscreen.EmpireOverviewScreen
import com.unciv.ui.screens.pickerscreens.PolicyPickerScreen
import com.unciv.ui.screens.pickerscreens.TechPickerScreen
import kotlin.math.ceil
import kotlin.math.roundToInt

internal class WorldScreenTopBarStats(topbar: WorldScreenTopBar) : ScalingTableWrapper() {

    private val goldLabel = "0".toLabel(fontColor = colorFromRGB(225, 217, 71), role = TextRole.Body) // #ed1947
    private val goldPerTurnLabel = "+0"
        .toLabel(colorFromRGB(225, 217, 71), UiTypography.size(TextRole.Supporting, legacySize = 14))

    private val scienceLabel = "0".toLabel(fontColor = ExpeditionBlueTheme.color(colorFromRGB(78, 140, 151), ExpeditionBlueTheme.text), role = TextRole.Body).apply { name = "ui-tech-stat-open" } // #4e8c97
    private val happinessLabel = "0".toLabel(TextRole.Body)
    private val cultureLabel = "0".toLabel(fontColor = ExpeditionBlueTheme.color(colorFromRGB(210, 94, 210), ExpeditionBlueTheme.text), role = TextRole.Body) // #d25ed2

    private val faithLabel = "0".toLabel(fontColor = colorFromRGB(168, 196, 241), role = TextRole.Body) // #a8c4f1
    private val faithPerTurnLabel = "+0"
        .toLabel(colorFromRGB(168, 196, 241), UiTypography.size(TextRole.Supporting, legacySize = 14))

    private val happinessContainer = Group()

    // These are all to improve performance IE reduce update time (was 150 ms on my phone, which is a lot!)
    private val malcontentColor = ExpeditionBlueTheme.color(colorFromRGB(239,83,80), ExpeditionBlueTheme.danger) // Color.valueOf("ef5350")
    private val happinessColor = ExpeditionBlueTheme.color(colorFromRGB(92, 194, 77), ExpeditionBlueTheme.success) // Color.valueOf("8cc24d")
    private val malcontentImage = ImageGetter.getStatIcon("Malcontent")
    private val happinessImage = ImageGetter.getStatIcon("Happiness")

    private val worldScreen = topbar.worldScreen
    private val nativeStats = ArrayList<Table>()
    private var lastStat: Table? = null


    companion object {
        const val defaultImageSize = 20f
        const val defaultHorizontalPad = 3f
        const val defaultTopPad = 8f
        const val defaultBottomPad = 3f
        const val defaultImageBottomPad = 6f
        const val padRightBetweenStats = 20f
    }

    init {
        isTransform = false

        defaults().pad(defaultTopPad, defaultHorizontalPad, defaultBottomPad, defaultHorizontalPad)

        fun addStat(
            icon: String,
            label: Label,
            noPad: Boolean = false,
            screenFactory: () -> BaseScreen?
        ) {
            val image = ImageGetter.getStatIcon(icon)
            val action = {
                val screen = screenFactory()
                // I don't like that it's ANR-able but to not do so we'd need to get rid of the nullable option
                if (screen != null) worldScreen.game.pushScreen{ screen } 
            }
            label.onClick(action)
            image.onClick(action)
            if (BattlefieldHud.enabled) {
                val stat = Table().apply {
                    touchable = Touchable.enabled
                    onClick(action)
                    image.touchable = Touchable.disabled
                    label.touchable = Touchable.disabled
                    add(image).size(BattlefieldHud.points(16f)).padRight(BattlefieldHud.points(3f))
                    add(label)
                }
                nativeStats.add(stat)
                lastStat = stat
                return
            }
            add(image).padBottom(defaultImageBottomPad).size(defaultImageSize)
            add(label).padRight(if (noPad) 0f else padRightBetweenStats)
        }

        fun addStat(
            icon: String,
            label: Label,
            overviewPage: EmpireOverviewCategories,
            noPad: Boolean = false
        ) = addStat(icon, label, noPad) {
            EmpireOverviewScreen(worldScreen.selectedGameView.civView, overviewPage)
        }

        fun addPerTurnLabel(label: Label) {
            if (BattlefieldHud.enabled) {
                lastStat!!.add(label).padLeft(BattlefieldHud.points(2f))
                label.touchable = Touchable.disabled
                return
            }
            add(label).padRight(padRightBetweenStats)
                .height(Constants.defaultFontSize.toFloat()).top()
        }


        addStat("Gold", goldLabel, EmpireOverviewCategories.Stats, true)
        addPerTurnLabel(goldPerTurnLabel)

        addStat("Science", scienceLabel) { TechPickerScreen(worldScreen.selectedCiv) }

        val invokeResourcesPage = {
            worldScreen.openEmpireOverview(EmpireOverviewCategories.Resources)
        }
        happinessContainer.onClick(invokeResourcesPage)
        happinessLabel.onClick(invokeResourcesPage)
        if (BattlefieldHud.enabled) {
            val stat = Table().apply {
                touchable = Touchable.enabled
                onClick(invokeResourcesPage)
                happinessContainer.touchable = Touchable.disabled
                happinessLabel.touchable = Touchable.disabled
                add(happinessContainer).size(BattlefieldHud.points(16f)).padRight(BattlefieldHud.points(3f))
                add(happinessLabel)
            }
            nativeStats.add(stat)
        } else {
            add(happinessContainer).padBottom(defaultImageBottomPad).size(defaultImageSize)
            add(happinessLabel).padRight(padRightBetweenStats)
        }

        addStat("Culture", cultureLabel) {
            if (worldScreen.gameInfo.ruleset.policyBranches.isEmpty()) null
            else PolicyPickerScreen(worldScreen.selectedCiv, worldScreen.canChangeState)
        }

        if (worldScreen.gameInfo.isReligionEnabled()) {
            addStat("Faith", faithLabel, EmpireOverviewCategories.Religion, true)
            addPerTurnLabel(faithPerTurnLabel)
        } else add("Religion: Off".toLabel(TextRole.Body))


    }


    fun update(civInfo: Civilization) {
        resetScale()

        val nextTurnStats = civInfo.stats.statsForNextTurn

        goldLabel.setText(civInfo.gold.tr())
        goldPerTurnLabel.setText(rateLabel(nextTurnStats.gold))

        scienceLabel.setText(rateLabel(nextTurnStats.science))

        happinessLabel.setText(if (BattlefieldHud.enabled) civInfo.getHappiness().tr() else getHappinessText(civInfo))

        if (civInfo.getHappiness() < 0) {
            happinessLabel.setFontColor(malcontentColor)
            happinessContainer.clearChildren()
            happinessContainer.addActor(malcontentImage)
        } else {
            happinessLabel.setFontColor(happinessColor)
            happinessContainer.clearChildren()
            happinessContainer.addActor(happinessImage)
        }

        cultureLabel.setText(if (BattlefieldHud.enabled) rateLabel(nextTurnStats.culture) else getCultureText(civInfo, nextTurnStats))

        faithLabel.setText(civInfo.religionManager.storedFaith.tr())
        faithPerTurnLabel.setText(rateLabel(nextTurnStats.faith))

        if (BattlefieldHud.enabled) {
            for (label in listOf(goldLabel, scienceLabel, happinessLabel, cultureLabel, faithLabel))
                label.setFontSize(BattlefieldHud.font(TextRole.Supporting))
            goldPerTurnLabel.setFontSize(BattlefieldHud.font(TextRole.CompactData))
            faithPerTurnLabel.setFontSize(BattlefieldHud.font(TextRole.CompactData))
            happinessContainer.children.firstOrNull()?.setSize(BattlefieldHud.points(16f), BattlefieldHud.points(16f))
            clearContent()
            val perTurnCell = nativeStats.first().getCell(goldPerTurnLabel)
            goldPerTurnLabel.isVisible = true
            perTurnCell.width(goldPerTurnLabel.prefWidth).padLeft(BattlefieldHud.points(2f))
            for (stat in nativeStats) {
                add(stat).pad(0f).minWidth(0f).minHeight(UiNavigation.minimumTouchSize()).padRight(BattlefieldHud.points(4f)).growX()
            }
        } else scaleToWidth(worldScreen.stage.width)
    }

    private fun getCultureText(civInfo: Civilization, nextTurnStats: Stats): String {
        var cultureString = rateLabel(nextTurnStats.culture)
        // kotlin Float division by Zero produces `Float.POSITIVE_INFINITY`, not an exception
        val turnsToNextPolicy = (civInfo.policies.getCultureNeededForNextPolicy() - civInfo.policies.storedCulture) / nextTurnStats.culture
        cultureString += when {
            turnsToNextPolicy <= 0f -> "\u2004(!)" // Can choose policy right now
            nextTurnStats.culture <= 0 -> "\u2004(${Fonts.infinity})" // when you start the game, you're not producing any culture
            else -> "\u2004(" + Fonts.turn + "\u2009" + ceil(turnsToNextPolicy).toInt().tr() + ")" // U+2004: Three-Per-Em Space, U+2009: Thin Space
        }
        return cultureString
    }

    private fun getHappinessText(civInfo: Civilization): String {
        var happinessText = civInfo.getHappiness().tr()
        val goldenAges = civInfo.goldenAges
        happinessText +=
            if (goldenAges.isGoldenAge())
                "    {GOLDEN AGE}(${goldenAges.turnsLeftForCurrentGoldenAge})".tr()
            else
                " (${goldenAges.storedHappiness.tr()}/${goldenAges.happinessRequiredForNextGoldenAge().tr()})"
        return happinessText
    }

    private fun rateLabel(value: Float): String {
        return if (value.roundToInt() == 0) "±0" else value.roundToInt().toStringSigned()
    }
}
