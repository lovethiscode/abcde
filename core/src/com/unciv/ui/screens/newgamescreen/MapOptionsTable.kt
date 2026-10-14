package com.unciv.ui.screens.newgamescreen

import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.Actor
import com.unciv.logic.map.MapGeneratedMainType
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.input.onChange
import com.unciv.ui.components.input.onClick
import com.unciv.ui.components.widgets.TranslatedSelectBox
import com.unciv.ui.components.widgets.UiNavigation
import com.unciv.ui.screens.basescreen.BaseScreen

class MapOptionsTable(private val newGameScreen: NewGameScreen) : Table() {

    private val mapParameters = newGameScreen.gameSetupInfo.mapParameters
    private var mapTypeSpecificTable = Table()
    private val councilPreview = Table()
    internal val generatedMapOptionsTable = MapParametersTable(newGameScreen, mapParameters, MapGeneratedMainType.generated)
    private val randomMapOptionsTable = MapParametersTable(newGameScreen, mapParameters, MapGeneratedMainType.randomGenerated)
    private val savedMapOptionsTable = MapFileSelectTable(newGameScreen, mapParameters)
    private val scenarioOptionsTable = ScenarioSelectTable(newGameScreen)
    internal val mapTypeSelectBox: TranslatedSelectBox

    init {
        //defaults().pad(5f) - each nested table having the same can give 'stairs' effects,
        // better control directly. Besides, the first Labels/Buttons should have 10f to look nice
        background = BaseScreen.skinStrings.getUiBackground("NewGameScreen/MapOptionsTable", tintColor = BaseScreen.skinStrings.skinConfig.clearColor)

        val mapTypes = arrayListOf(MapGeneratedMainType.generated, MapGeneratedMainType.randomGenerated)
        if (savedMapOptionsTable.isNotEmpty()) mapTypes.add(MapGeneratedMainType.custom)
        if (newGameScreen.game.files.getScenarioFiles().any()) mapTypes.add(MapGeneratedMainType.scenario)

        val initialMapType = mapParameters.type.takeIf { it in mapTypes } ?: MapGeneratedMainType.generated
        mapTypeSelectBox = TranslatedSelectBox(mapTypes, initialMapType)

        fun updateOnMapTypeChange() {
            mapTypeSpecificTable.clear()
            when (mapTypeSelectBox.selected.value) {
                MapGeneratedMainType.custom -> {
                    mapParameters.type = MapGeneratedMainType.custom
                    mapTypeSpecificTable.add(savedMapOptionsTable)
                    savedMapOptionsTable.activateCustomMaps()
                    newGameScreen.unlockTables()
                }
                MapGeneratedMainType.generated -> {
                    mapParameters.name = ""
                    mapParameters.type = generatedMapOptionsTable.mapTypeSelectBox.selected.value
                    mapTypeSpecificTable.add(generatedMapOptionsTable)
                    newGameScreen.unlockTables()
                }
                MapGeneratedMainType.randomGenerated -> {
                    mapParameters.name = ""
                    mapTypeSpecificTable.add(randomMapOptionsTable).growX()
                    newGameScreen.unlockTables()
                }
                MapGeneratedMainType.scenario -> {
                    mapParameters.name = ""
                    mapTypeSpecificTable.add(scenarioOptionsTable)
                    scenarioOptionsTable.selectScenario()
                    newGameScreen.lockTables()
                }
            }
            if (UiNavigation.isIos) {
                councilPreview.clear()
                createOverviewPreview(newGameScreen.directSetupWidth,
                    (newGameScreen.stage.height * .28f).coerceIn(100f, 165f))?.let {
                    councilPreview.add(it).grow()
                }
                getCell(councilPreview)?.height(if (councilPreview.children.size == 0) 0f
                    else (newGameScreen.stage.height * .28f).coerceIn(100f, 165f))
            }
            newGameScreen.gameSetupInfo.gameParameters.godMode = false
            newGameScreen.updateTables()
        }

        // activate once, so the MapGeneratedMainType.generated controls show
        updateOnMapTypeChange()

        mapTypeSelectBox.onChange { updateOnMapTypeChange() }

        if (UiNavigation.isIos) {
            background = null
            add(councilPreview).width(newGameScreen.directSetupWidth)
                .height(if (councilPreview.children.size == 0) 0f else (newGameScreen.stage.height * .28f).coerceIn(100f, 165f))
                .padBottom(8f).row()
            add(SinglePlayerSetup.form(newGameScreen.directSetupWidth,
                listOf("Map source" to mapTypeSelectBox))).growX().row()
            add(mapTypeSpecificTable).growX().row()
        } else {
            val mapTypeSelectWrapper = Table()  // wrap to center-align Label and SelectBox easier
            mapTypeSelectWrapper.add("{Map Type}:".toLabel()).left().expandX()
            mapTypeSelectWrapper.add(mapTypeSelectBox).right()
            add(mapTypeSelectWrapper).pad(10f).fillX().row()
            add(mapTypeSpecificTable).row()
        }
    }

    internal fun getSelectedScenario(): ScenarioSelectTable.ScenarioData? {
        if (mapTypeSelectBox.selected.value != MapGeneratedMainType.scenario) return null
        return scenarioOptionsTable.selectedScenario
    }

    internal fun createOverviewFields(editWorld: () -> Unit): List<Pair<String, Actor>> {
        if (mapTypeSelectBox.selected.value == MapGeneratedMainType.generated)
            return generatedMapOptionsTable.createOverviewFields(editWorld)
        return listOf(
            "Map Type" to mapTypeSelectBox.selected.value.toTextButton().onClick(editWorld),
            "World Size" to mapParameters.mapSize.name.toTextButton().onClick(editWorld)
        )
    }

    internal fun cancelBackgroundJobs() {
        generatedMapOptionsTable.cancelBackgroundJobs()
        randomMapOptionsTable.cancelBackgroundJobs()
        savedMapOptionsTable.cancelBackgroundJobs()
    }

    internal fun refreshExampleMap() {
        generatedMapOptionsTable.generateExampleMap()
        randomMapOptionsTable.generateExampleMap()
    }

    internal fun createOverviewPreview(width: Float, height: Float): Table? = when (mapTypeSelectBox.selected.value) {
        MapGeneratedMainType.generated -> generatedMapOptionsTable.createOverviewPreview(width, height)
        MapGeneratedMainType.randomGenerated -> randomMapOptionsTable.createOverviewPreview(width, height)
        else -> null
    }
}
