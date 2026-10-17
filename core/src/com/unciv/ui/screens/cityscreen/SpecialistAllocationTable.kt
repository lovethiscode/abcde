package com.unciv.ui.screens.cityscreen

import com.unciv.ui.components.fonts.UiTypography
import com.unciv.ui.components.fonts.TextRole
import com.unciv.models.skins.ExpeditionBlueTheme
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.utils.Align
import com.unciv.Constants
import com.unciv.models.translations.tr
import com.unciv.ui.components.SmallButtonStyle
import com.unciv.ui.components.UncivTooltip.Companion.addTooltip
import com.unciv.ui.components.extensions.addSeparatorVertical
import com.unciv.ui.components.extensions.darken
import com.unciv.ui.components.extensions.surroundWithCircle
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.input.KeyboardBinding
import com.unciv.ui.components.input.onActivation
import com.unciv.ui.components.input.onClick
import com.unciv.ui.components.widgets.ExpanderTab
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.screens.basescreen.BaseScreen

class SpecialistAllocationTable(private val cityScreen: CityScreen) : Table(BaseScreen.skin) {
    private val cityView = cityScreen.cityView
    private val smallButtonStyle = SmallButtonStyle()

    fun update() {
        // 5 columns: "-" unassignButton, AllocationTable, "+" assignButton, SeparatorVertical, SpecialistsStatsTable
        clear()

        // Auto/Manual Specialists Toggle
        if (cityScreen.canCityBeChanged()) {
            val toggleButton = if (cityView.manualSpecialists) {
                "Manual Specialists".toTextButton(smallButtonStyle, unit = if (cityScreen.usesWorkbench) 1f else UiTypography.unitsPerPoint(cityScreen.stage))
                   .onActivation {
                       cityView.tryDisableManualSpecialists()
                       cityScreen.updateAsync()
                   }
            } else {
                "Auto Specialists".toTextButton(smallButtonStyle, unit = if (cityScreen.usesWorkbench) 1f else UiTypography.unitsPerPoint(cityScreen.stage))
                    .onActivation {
                        cityView.tryEnableManualSpecialists()
                        update()
                    }
            }
            toggleButton.name = "ui-city-specialists-toggle"
            add(toggleButton).minHeight(if (cityScreen.usesWorkbench) 44f else 0f).colspan(5).row()
        }

        // Since getMaxSpecialists is a Counter, iteration is potentially in random order. Ensure consistency by simplified sorting (no collator)
        for ((specialistName, maxSpecialists) in cityView.getMaxSpecialists().asSequence().sortedBy { it.key }) {
            if (!cityView.getRuleset().specialists.containsKey(specialistName)) // specialist doesn't exist in this ruleset, probably a mod
                continue
            val newSpecialists = cityView.getNewSpecialists()
            val assignedSpecialists = newSpecialists[specialistName]

            if (cityScreen.usesWorkbench) {
                val row = Table()
                row.add(specialistName.toLabel(fontSize = UiTypography.size(TextRole.Control, unit = if (cityScreen.usesWorkbench) 1f else UiTypography.unitsPerPoint(cityScreen.stage), legacySize = 15))).minWidth(0f).growX()
                row.add(getUnassignButton(assignedSpecialists, specialistName)).size(44f)
                row.add("$assignedSpecialists/$maxSpecialists".toLabel(fontSize = UiTypography.size(TextRole.Control, unit = if (cityScreen.usesWorkbench) 1f else UiTypography.unitsPerPoint(cityScreen.stage), legacySize = 16))).pad(6f)
                row.add(getAssignButton(assignedSpecialists, maxSpecialists, specialistName)).size(44f)
                add(row).colspan(5).growX().padTop(12f).row()
                add(getSpecialistStatsTable(specialistName)).colspan(5).left().row()
                continue
            }
            if (cityScreen.canChangeState) add(getUnassignButton(assignedSpecialists, specialistName))
            add(getAllocationTable(assignedSpecialists, maxSpecialists, specialistName)).pad(10f)
            if (cityScreen.canChangeState) add(getAssignButton(assignedSpecialists, maxSpecialists, specialistName))

            addSeparatorVertical().pad(10f)
            add(getSpecialistStatsTable(specialistName)).row()
        }

        pack()
    }


    private fun getAllocationTable(assignedSpecialists: Int, maxSpecialists: Int, specialistName: String): Table {
        val specialistIconTable = Table()
        val specialistObject = cityView.getRuleset().specialists[specialistName]!!
        for (i in 1..maxSpecialists) {
            val color = if (i <= assignedSpecialists) specialistObject.colorObject
            else Color.GRAY // unassigned
            val icon = ImageGetter.getSpecialistIcon(color)
            specialistIconTable.add(icon).size(30f)
            if (i % 5 == 0) specialistIconTable.row()
        }
        specialistIconTable.addTooltip(specialistName, 24f)
        return specialistIconTable
    }

    private fun getAssignButton(assignedSpecialists: Int, maxSpecialists: Int, specialistName: String): Actor {

        if (assignedSpecialists >= maxSpecialists || cityView.isPuppet()) return Table()
        val assignButton = "+".toLabel(ExpeditionBlueTheme.color(ImageGetter.CHARCOAL, ExpeditionBlueTheme.text), Constants.headingFontSize)
            .apply { this.setAlignment(Align.center) }
            .surroundWithCircle(if (cityScreen.usesWorkbench) 44f else 30f).apply { circle.color = ExpeditionBlueTheme.color(Color.GREEN.darken(0.2f), ExpeditionBlueTheme.raised) }
        assignButton.name = "ui-city-specialist-add:$specialistName"
        assignButton.onClick {
            cityView.tryAssignSpecialist(specialistName)
            cityScreen.updateAsync()
        }
        if (cityView.getFreePopulation() == 0 || !cityScreen.canChangeState)
            assignButton.clear()
        return assignButton
    }

    private fun getUnassignButton(assignedSpecialists: Int, specialistName: String): Actor {
        val unassignButton = "-".toLabel(ExpeditionBlueTheme.color(ImageGetter.CHARCOAL, ExpeditionBlueTheme.text), Constants.headingFontSize)
            .apply { this.setAlignment(Align.center) }
            .surroundWithCircle(if (cityScreen.usesWorkbench) 44f else 30f).apply { circle.color = ExpeditionBlueTheme.color(Color.RED.darken(0.1f), ExpeditionBlueTheme.dangerSurface) }
        unassignButton.name = "ui-city-specialist-remove:$specialistName"
        unassignButton.onClick {
            cityView.tryUnassignSpecialist(specialistName)
            cityScreen.updateAsync()
        }

        if (assignedSpecialists <= 0 || cityView.isPuppet()) unassignButton.isVisible = false
        if (!cityScreen.canChangeState) unassignButton.clear()
        return unassignButton
    }


    private fun getSpecialistStatsTable(specialistName: String): Table {
        val specialistStatTable = Table().apply { defaults().padBottom(5f).padTop(5f) }
        val specialistStats = cityView.getStatsOfSpecialist(specialistName)
        val specialist = cityView.getRuleset().specialists[specialistName]!!
        val maxStat = specialistStats.maxOf { it.value }
        val lightGreen = Color(0x7fff7fff)
        var itemsInRow = 0

        fun addWrapping(value: Int, labelColor: Color, icon: Actor) {
            specialistStatTable.add(value.tr().toLabel(fontColor = labelColor, role = TextRole.Body, unit = if (cityScreen.usesWorkbench) 1f else UiTypography.unitsPerPoint(cityScreen.stage)))
            specialistStatTable.add(icon).size(20f).padRight(10f)

            itemsInRow++
            if (itemsInRow % 3 == 0) {
                itemsInRow = 0
                specialistStatTable.row()
            }
        }

        // Show gpp points first, makes recognizing what the specialist is good for easier
        // greatPersonPoints is a Counter so iteration order is potentially random:
        // Sort by unit name without collator to ensure consistency in those rare mods where one Specialist gives points to several GP counters
        for ((gpName, gpPoints) in specialist.greatPersonPoints.asSequence().sortedBy { it.key }) {
            val greatPerson = cityView.getRuleset().units[gpName] ?: continue
            addWrapping(gpPoints, Color.GOLD, ImageGetter.getUnitIcon(greatPerson, Color.GOLD))
        }

        // This uses Stats.iterator() which ensures consistent Stat order and returns no zero value
        // To make recognizing the Specialist still easier, the max values of the Stats are done in green
        for ((stat, value) in specialistStats) {
            addWrapping(value.toInt(), if (value == maxStat) lightGreen else Color.WHITE, ImageGetter.getStatIcon(stat.name))
        }

        return specialistStatTable
    }


    fun asExpander(onChange: (() -> Unit)?): ExpanderTab {
        return ExpanderTab(
            title = "{Specialists}:",
            fontSize = UiTypography.size(TextRole.SectionTitle, legacySize = Constants.defaultFontSize),
            persistenceID = "CityStatsTable.Specialists",
            startsOutOpened = true,
            toggleKey = KeyboardBinding.SpecialistDetail,
            onChange = onChange
        ) {
            it.add(this)
            update()
        }
    }

}
