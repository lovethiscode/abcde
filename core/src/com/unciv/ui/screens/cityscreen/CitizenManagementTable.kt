package com.unciv.ui.screens.cityscreen

import com.unciv.ui.components.fonts.UiTypography
import com.unciv.ui.components.fonts.TextRole
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.unciv.Constants
import com.unciv.logic.city.CityFocus
import com.unciv.ui.components.widgets.ExpanderTab
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.input.KeyboardBinding
import com.unciv.ui.components.input.onActivation
import com.unciv.ui.screens.basescreen.BaseScreen

class CitizenManagementTable(val cityScreen: CityScreen) : Table(BaseScreen.skin) {
    private val cityView = cityScreen.cityView
    private val numCol = if (cityScreen.usesWorkbench) 2 else 4

    fun update() {
        clear()

        val colorSelected = BaseScreen.skin.getColor("selection")
        val colorButton = BaseScreen.skin.getColor("color")

        val topTable = Table() // holds 2 buttons
        // effectively a button, but didn't want to rewrite TextButton style
        // and much more compact and can control backgrounds easily based on settings
        val resetLabel = "Reset Citizens".toLabel(fontSize = UiTypography.size(TextRole.Supporting, unit = if (cityScreen.usesWorkbench) 1f else UiTypography.unitsPerPoint(cityScreen.stage), legacySize = if (cityScreen.usesWorkbench) 13 else Constants.defaultFontSize))
        val resetCell = Table()
        resetCell.name = "ui-city-reset-citizens"
        resetCell.add(resetLabel).pad(5f).minHeight(if (cityScreen.usesWorkbench) 44f else 0f)
        if (cityScreen.canCityBeChanged()) {
            resetCell.touchable = Touchable.enabled
            resetCell.onActivation(binding = KeyboardBinding.ResetCitizens) {
                cityView.tryReassignPopulation(resetLocked = true)
                cityScreen.updateAsync()
            }
        }
        resetCell.background = BaseScreen.skinStrings.getUiBackground(
            "CityScreen/CitizenManagementTable/ResetCell",
            tintColor = colorButton
        )
        topTable.add(resetCell).pad(3f)

        val avoidLabel = "Avoid Growth".toLabel(fontSize = UiTypography.size(TextRole.Supporting, unit = if (cityScreen.usesWorkbench) 1f else UiTypography.unitsPerPoint(cityScreen.stage), legacySize = if (cityScreen.usesWorkbench) 13 else Constants.defaultFontSize))
        val avoidCell = Table()
        avoidCell.name = "ui-city-avoid-growth"
        avoidCell.add(avoidLabel).pad(5f).minHeight(if (cityScreen.usesWorkbench) 44f else 0f)
        if (cityScreen.canCityBeChanged()) {
            avoidCell.touchable = Touchable.enabled
            avoidCell.onActivation(binding = KeyboardBinding.AvoidGrowth) {
                cityView.tryToggleAvoidGrowth()
                cityScreen.updateAsync()
            }
        }
        avoidCell.background = BaseScreen.skinStrings.getUiBackground(
            "CityScreen/CitizenManagementTable/AvoidCell",
            tintColor = if (cityView.avoidGrowth) colorSelected else colorButton
        )
        topTable.add(avoidCell).pad(3f)
        add(topTable).colspan(numCol).growX()
        row()

        val focusLabel = "Citizen Focus".toLabel(fontSize = UiTypography.size(TextRole.Supporting, unit = if (cityScreen.usesWorkbench) 1f else UiTypography.unitsPerPoint(cityScreen.stage), legacySize = if (cityScreen.usesWorkbench) 14 else Constants.defaultFontSize))
        val focusCell = Table()
        focusCell.add(focusLabel).pad(5f)
        add(focusCell).colspan(numCol).growX().pad(3f)
        row()

        var currCol = numCol
        val defaultTable = Table()
        for (focus in CityFocus.entries) {
            if (!focus.tableEnabled) continue
            if (focus == CityFocus.FaithFocus && !cityView.viewingCiv().isReligionEnabled()) continue
            val caption = if (cityScreen.usesWorkbench) when (focus) {
                CityFocus.GoldGrowthFocus -> "{Gold} + {Growth}"
                CityFocus.ProductionGrowthFocus -> "{Production} + {Growth}"
                else -> focus.stat?.name ?: focus.label
            } else focus.label
            val label = caption.toLabel(fontSize = UiTypography.size(TextRole.Control, unit = if (cityScreen.usesWorkbench) 1f else UiTypography.unitsPerPoint(cityScreen.stage), legacySize = if (cityScreen.usesWorkbench) 15 else Constants.defaultFontSize))
            val cell = Table()
            cell.name = "ui-city-focus:${focus.name}"
            cell.add(label).pad(5f).minHeight(if (cityScreen.usesWorkbench) 44f else 0f)
            if (cityScreen.canCityBeChanged()) {
                cell.touchable = Touchable.enabled
                // Note the binding here only works when visible, so the main one is on CityStatsTable.miniStatsTable
                // If we bind both, both are executed - so only add the one here that re-applies the current focus
                val binding = if (cityView.getCityFocus() == focus) focus.binding else KeyboardBinding.None
                cell.onActivation(binding = binding) {
                    cityView.trySetCityFocus(focus)
                    cityScreen.updateAsync()
                }
            }
            cell.background = BaseScreen.skinStrings.getUiBackground(
                "CityScreen/CitizenManagementTable/FocusCell",
                tintColor = if (cityView.getCityFocus() == focus) colorSelected else colorButton
            )
            // make NoFocus and Manual their own special row
            if (focus == CityFocus.NoFocus) {
                defaultTable.add(cell).growX().pad(3f)
            } else if (focus == CityFocus.Manual) {
                defaultTable.add(cell).growX().pad(3f)
                add(defaultTable).colspan(numCol).growX()
                row()
            } else {
                cell.padTop(5f)  // Stat symbols need extra padding on top
                add(cell).growX().pad(3f)
                --currCol
                if (currCol == 0) {  // make new row
                    row()
                    currCol = numCol
                }
            }
        }

        pack()
    }

    fun asExpander(onChange: (() -> Unit)?): ExpanderTab {
        return ExpanderTab(
            title = "{Citizen Management}",
            fontSize = UiTypography.size(TextRole.SectionTitle, legacySize = Constants.defaultFontSize),
            persistenceID = "CityStatsTable.CitizenManagement",
            startsOutOpened = false,
            toggleKey = KeyboardBinding.CitizenManagement,
            onChange = onChange
        ) {
            it.add(this)
            update()
        }
    }

}
