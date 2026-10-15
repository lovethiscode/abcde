package com.unciv.ui.screens.newgamescreen

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.ui.CheckBox
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.utils.Align
import com.unciv.UncivGame
import com.unciv.logic.map.*
import com.unciv.logic.map.mapgenerator.MapGenerator
import com.unciv.logic.map.mapgenerator.MapResourceSetting
import com.unciv.models.metadata.GameParameters
import com.unciv.models.ruleset.RulesetCache
import com.unciv.models.ruleset.unique.GameContext
import com.unciv.models.skins.ExpeditionBlueTheme
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.*
import com.unciv.ui.components.input.onChange
import com.unciv.ui.components.input.onClick
import com.unciv.ui.components.widgets.*
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.victoryscreen.LoadMapPreview
import com.unciv.utils.Concurrency
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive

/** Table for editing [mapParameters]
 *
 *  This is a separate class, because it should be in use both in the New Game screen and the Map Editor screen
 *
 *  @param forMapEditor whether the [MapType.empty] option should be present. Is used by the Map Editor, but should **never** be used with the New Game
 * */
class MapParametersTable(
    private val previousScreen: IPreviousScreen? = null,
    private val mapParameters: MapParameters,
    private val mapGeneratedMainType: String,
    private val forMapEditor: Boolean = false,
    private val roomLayoutWidth: Float? = null,
    private val councilLayoutWidth: Float? = null,
    private val councilLayoutFont: com.badlogic.gdx.graphics.g2d.BitmapFont? = null,
    private val sizeChangedCallback: (()->Unit)? = null
) : Table() {
    // These are accessed from outside the class to read _and_ write values,
    // namely from MapOptionsTable, NewMapScreen and NewGameScreen
    lateinit var mapTypeSelectBox: TranslatedSelectBox
    lateinit var customMapSizeRadius: UncivTextField.Integer
    lateinit var customMapWidth: UncivTextField.Integer
    lateinit var customMapHeight: UncivTextField.Integer

    private lateinit var mapShapeSelectBox: TranslatedSelectBox
    private var roomPreview: Table? = null
    private lateinit var worldSizeSelectBox: TranslatedSelectBox
    private var customWorldSizeTable = Table()
    private var hexagonalSizeTable = Table()
    private var rectangularSizeTable = Table()
    lateinit var resourceSelectBox: TranslatedSelectBox
    lateinit var mirrorSelectBox: TranslatedSelectBox
    private lateinit var noRuinsCheckbox: CheckBox
    private lateinit var noNaturalWondersCheckbox: CheckBox
    private lateinit var worldWrapCheckbox: CheckBox
    private lateinit var legendaryStartCheckbox: CheckBox
    private lateinit var strategicBalanceCheckbox: CheckBox
    private lateinit var seedTextField: UncivTextField.Numeric

    private lateinit var mapShapesOptionsValues: HashSet<String>
    private lateinit var mapTypesOptionsValues: HashSet<String>
    private lateinit var mapSizesOptionsValues: HashSet<String>
    private lateinit var mapResourcesOptionsValues: HashSet<String>

    // Preview is shrunk to 65% of column width so Width/Height fields and warnings below stay visible without scrolling
    private val maxMapSize = (((previousScreen as? NewGameScreen)?.getColumnWidth() ?: 200f) - 10f) * 0.65f
    private val mapTypeExample = Table()
    private var lastExampleMap: TileMap? = null
    private var overviewPreview: Table? = null
    private var overviewPreviewWidth = 0f
    private var overviewPreviewHeight = 0f
    private var exampleMapJob: Job? = null
    @Volatile
    private var exampleMapGeneration = 0

    // Keep references (in the key) and settings value getters (in the value) of the 'advanced' sliders
    // in a HashMap for reuse later - in the reset to defaults button. Better here as field than as closure.
    // A HashMap indexed on a Widget is problematic, as it does not define its own hashCode and equals
    // overrides nor is a Widget a data class. Seems to work anyway.
    private val advancedSliders = HashMap<UncivSlider, ()->Float>()
    private val directLayoutWidth: Float?
        get() = councilLayoutWidth ?: (previousScreen as? NewGameScreen)?.takeIf { UiNavigation.isIos }?.directSetupWidth
    private val directLayoutFont get() = councilLayoutFont ?: (previousScreen as NewGameScreen).formFont

    private lateinit var hexWarningLabel: Label
    private lateinit var rectWarningLabel: Label

    companion object {
        private const val LARGE_MAP_WARNING = "This is a large map - may cause latency issues"
        private const val VERY_LARGE_MAP_WARNING = "This is a very large map - may cause Out Of Memory crashes"
        private const val LARGE_MAP_TILES = 4000
        private const val VERY_LARGE_MAP_TILES = 16000
    }

    // used only in map editor (forMapEditor == true)
    var randomizeSeed = true

    init {
        update()
    }

    fun update() {
        clear()

        skin = BaseScreen.skin
        defaults().pad(if (directLayoutWidth == null) 5f else 0f)
        if (mapGeneratedMainType == MapGeneratedMainType.randomGenerated) {
            val prompt = "Which options should be available to the random selection?"
            val width = directLayoutWidth ?: (previousScreen as? NewGameScreen)?.getColumnWidth() ?: 200f
            val label = WrappableLabel(prompt, width - 10f)  // 20 is the defaults() padding
            label.setAlignment(Align.center)
            label.wrap = true
            add(label).colspan(2).grow().row()
        }
        addMapShapeSelectBox()
        addMapTypeSelectBox()
        addWorldSizeTable()
        addResourceSelectBox()
        addWrappedCheckBoxes()
        addMirrorSelectBox()
        if (directLayoutWidth != null && mapGeneratedMainType == MapGeneratedMainType.generated)
            buildDirectLayout(directLayoutWidth!!)
        else if (directLayoutWidth != null) {
            add(SinglePlayerSetup.group("Advanced Settings", directLayoutWidth!!, "World.RandomAdvanced") {
                addAdvancedControls(it)
            }).width(directLayoutWidth!!).padTop(8f).growX().row()
            SinglePlayerSetup.style(this, directLayoutFont)
        }
        else if (roomLayoutWidth == null) addAdvancedSettings()
        else buildRoomLayout(roomLayoutWidth)
        generateExampleMap()
    }

    fun reseed() {
        mapParameters.reseed()
        seedTextField.value = mapParameters.seed
    }

    internal fun createOverviewFields(editWorld: () -> Unit): List<Pair<String, com.badlogic.gdx.scenes.scene2d.Actor>> {
        val type = TranslatedSelectBox(MapType.allValues, mapParameters.type).apply {
            onChange { mapTypeSelectBox.setSelected(selected.value) }
        }
        val size = TranslatedSelectBox(MapSize.names() + MapSize.custom, mapParameters.mapSize.name).apply {
            onChange {
                worldSizeSelectBox.setSelected(selected.value)
                if (selected.value == MapSize.custom) editWorld()
            }
        }
        return listOf("Map Generation Type" to type, "World Size" to size)
    }

    private fun buildDirectLayout(width: Float) {
        clearChildren()
        defaults().pad(0f)
        add(SinglePlayerSetup.form(width, listOf(
            "Map Type" to mapTypeSelectBox, "World Size" to worldSizeSelectBox
        ))).growX().row()
        add(customWorldSizeTable).growX().row()
        add(SinglePlayerSetup.group("Map parameters", width, "World.Parameters") { table ->
            table.add(SinglePlayerSetup.form(width - 24f, listOf(
                "Map Shape" to mapShapeSelectBox, "Resource Setting" to resourceSelectBox
            ))).growX().row()
            table.defaults().left().growX().minHeight(44f).padBottom(8f)
            for (check in listOf(strategicBalanceCheckbox, legendaryStartCheckbox, noRuinsCheckbox,
                noNaturalWondersCheckbox, worldWrapCheckbox)) table.add(check).row()
            table.add("World wrap maps are very memory intensive - creating large world wrap maps on Android can lead to crashes!"
                .toLabel(fontSize = 18).apply { wrap = true }).row()
            if (this::mirrorSelectBox.isInitialized)
                table.add(SinglePlayerSetup.field("Map mirroring", mirrorSelectBox, width - 24f)).growX().row()
            addAdvancedControls(table)
            SinglePlayerSetup.style(table, directLayoutFont)
        }).width(width).padTop(6f).row()
        SinglePlayerSetup.style(this, directLayoutFont)
    }

    private fun addMapShapeSelectBox() {
        val mapShapes = MapShape.allValues
        val rng = GameContext().stateBasedRandom("MapParametersTable.addMapShapeSelectBox", System.currentTimeMillis().toInt())

        if (mapGeneratedMainType == MapGeneratedMainType.randomGenerated) {
            mapShapesOptionsValues = mapShapes.toHashSet()
            val optionsTable = MultiCheckboxTable("{Enabled Map Shapes}", "NewGameMapShapes", mapShapesOptionsValues, directLayoutWidth) {
                if (mapShapesOptionsValues.isEmpty()) {
                    mapParameters.shape = mapShapes.random(rng)
                } else {
                    mapParameters.shape = mapShapesOptionsValues.random(rng)
                }
            }
            add(optionsTable).colspan(2).grow().row()
        } else {
            mapShapeSelectBox =
                    TranslatedSelectBox(mapShapes, mapParameters.shape)
            mapShapeSelectBox.onChange {
                mapParameters.shape = mapShapeSelectBox.selected.value
                updateWorldSizeTable()
                generateExampleMap()
            }

            add ("{Map Shape}:".toLabel()).left()
            add(mapShapeSelectBox).fillX().row()
        }
    }

    internal fun generateExampleMap() {
        cancelBackgroundJobs()
        val generation = ++exampleMapGeneration
        val ruleset = previousScreen?.ruleset?.clone() ?: RulesetCache.getVanillaRuleset()
        val mapParametersForExample =
            if (forMapEditor) mapParameters
            else mapParameters.clone().apply {
                seed = 0
                mirroring = MirroringType.none
            }
        exampleMapJob = Concurrency.run("Generate example map") {
            val exampleMap = MapGenerator(ruleset).generateMap(mapParametersForExample, GameParameters())
            if (!isActive) return@run
            Concurrency.runOnGLThread {
                if (generation != exampleMapGeneration) return@runOnGLThread
                lastExampleMap = exampleMap
                updateOverviewPreview()
                updateRoomPreview()
                mapTypeExample.clear()
                val mapPreview = LoadMapPreview(exampleMap, maxMapSize, maxMapSize)
                if (!forMapEditor) {
                    val label = "Example map".toLabel()
                    label.centerX(mapPreview)
                    label.y = mapPreview.height - label.height - 10f
                    mapPreview.addActor(label)
                }
                mapTypeExample.add(mapPreview)
                pack()
            }
        }.apply {
            invokeOnCompletion {
                if (generation == exampleMapGeneration)
                    exampleMapJob = null
            }
        }
    }

    internal fun cancelBackgroundJobs() {
        exampleMapGeneration++
        exampleMapJob?.cancel()
        exampleMapJob = null
    }

    internal fun createOverviewPreview(width: Float, height: Float): Table {
        overviewPreviewWidth = width
        overviewPreviewHeight = height
        return Table().also {
            overviewPreview = it
            updateOverviewPreview()
        }
    }

    private fun updateOverviewPreview() {
        val preview = overviewPreview ?: return
        preview.clear()
        val map = lastExampleMap
        if (map == null) preview.add("Loading...".toLabel())
        else if (UiNavigation.isIos && directLayoutWidth != null) {
            val tileSet = com.unciv.ui.components.tilegroups.TileSetStrings()
            val view = com.unciv.view.TileMapView(map, null)
            val groups = map.values.map { tile ->
                com.unciv.ui.components.tilegroups.TileGroup(view.getTile(tile), tileSet).apply { update() }
            }
            val terrain = com.unciv.ui.components.tilegroups.TileGroupMap(
                com.unciv.ui.components.widgets.ZoomableScrollPane(), groups).apply {
                val scale = minOf(overviewPreviewWidth / width, overviewPreviewHeight / height)
                setScale(scale)
                setPosition((overviewPreviewWidth - width * scale) / 2f,
                    (overviewPreviewHeight - height * scale) / 2f)
                touchable = com.badlogic.gdx.scenes.scene2d.Touchable.disabled
            }
            preview.background = SinglePlayerSetup.surface(
                ExpeditionBlueTheme.color(Color.valueOf("163D58"), ExpeditionBlueTheme.panel))
            preview.add(com.badlogic.gdx.scenes.scene2d.Group().apply {
                setSize(overviewPreviewWidth, overviewPreviewHeight)
                addActor(terrain)
            }).size(overviewPreviewWidth, overviewPreviewHeight)
        } else preview.add(LoadMapPreview(map, overviewPreviewWidth, overviewPreviewHeight))
    }

    private fun buildRoomLayout(width: Float) {
        clearChildren()
        defaults().pad(0f)
        val stacked = width < 700f
        val previewWidth = if (stacked) width else (width - 18f) * 0.46f
        val optionsWidth = if (stacked) width else width - previewWidth - 18f
        val previewCard = GameSetupOverview.card().apply {
            add("Example map".toLabel(fontSize = 24)).left().padBottom(14f).row()
            roomPreview = Table()
            add(roomPreview).width(previewWidth - 36f).height(190f).padBottom(12f).row()
            add(Table().apply {
                for ((index, type) in listOf(MapType.pangaea, MapType.twoContinents, MapType.archipelago).withIndex()) {
                    add(GameSetupOverview.choice(type, mapParameters.type == type).apply {
                        onClick { mapTypeSelectBox.setSelected(type) }
                    }).width((previewWidth - 52f) / 3f).minHeight(UiNavigation.minimumTouchSize()).padRight(if (index == 2) 0f else 8f)
                }
            }).row()
        }
        val options = GameSetupOverview.card().apply {
            val innerWidth = optionsWidth - 36f
            val fieldWidth = (innerWidth - 14f) / 2f
            defaults().left().padBottom(8f)
            add(GameSetupOverview.field("Map Generation Type", mapTypeSelectBox, innerWidth)).colspan(2).row()
            add(GameSetupOverview.field("World Size", worldSizeSelectBox, fieldWidth)).padRight(14f)
            add(GameSetupOverview.field("Map Shape", mapShapeSelectBox, fieldWidth)).row()
            add(customWorldSizeTable).colspan(2).growX().padBottom(0f).row()
            add(GameSetupOverview.field("Resource Setting", resourceSelectBox, fieldWidth)).padRight(14f)
            add(worldWrapCheckbox).width(fieldWidth).minHeight(UiNavigation.minimumTouchSize()).padTop(28f).row()
        }
        add(previewCard).width(previewWidth).top().padRight(if (stacked) 0f else 18f)
        if (stacked) row()
        add(options).width(optionsWidth).top().padTop(if (stacked) 18f else 0f).row()
        add(ExpanderTab("Advanced Settings", startsOutOpened = false, expanderWidth = width,
            persistenceID = "MultiplayerRoom.WorldAdvanced") { table ->
            table.defaults().left().growX().padBottom(12f)
            for (check in listOf(strategicBalanceCheckbox, legendaryStartCheckbox, noRuinsCheckbox, noNaturalWondersCheckbox))
                table.add(check).minHeight(44f).row()
            addAdvancedControls(table)
        }).colspan(if (stacked) 1 else 2).width(width).padTop(18f)
        updateRoomPreview()
    }

    private fun updateRoomPreview() {
        val preview = roomPreview ?: return
        val width = roomLayoutWidth ?: return
        val stacked = width < 700f
        preview.clear()
        val map = lastExampleMap
        if (map == null) preview.add("Loading...".toLabel())
        else preview.add(LoadMapPreview(map, (if (stacked) width else (width - 18f) * 0.46f) - 36f,
            190f))
    }

    private fun addMapTypeSelectBox() {
        // MapType is not an enum so we can't simply enumerate. //todo: make it so!
        val rng = GameContext().stateBasedRandom("MapParametersTable.addMapTypeSelectBox", System.currentTimeMillis().toInt())
        var mapTypes = MapType.allValues
        if (forMapEditor && mapGeneratedMainType != MapGeneratedMainType.randomGenerated) mapTypes = mapTypes + MapType.empty

        if (mapGeneratedMainType == MapGeneratedMainType.randomGenerated) {
            mapTypesOptionsValues = mapTypes.toHashSet()
            val optionsTable = MultiCheckboxTable("{Enabled Map Generation Types}", "NewGameMapGenerationTypes", mapTypesOptionsValues, directLayoutWidth) {
                if (mapTypesOptionsValues.isEmpty()) {
                    mapParameters.type = mapTypes.random(rng)
                } else {
                    mapParameters.type = mapTypesOptionsValues.random(rng)
                }
            }
            add(optionsTable).colspan(2).grow().row()
        } else {
            mapTypeSelectBox = TranslatedSelectBox(mapTypes, mapParameters.type)


            mapTypeSelectBox.onChange {
                mapParameters.type = mapTypeSelectBox.selected.value

                // If the map won't be generated, these options are irrelevant and are hidden
                noRuinsCheckbox.isVisible = mapParameters.type != MapType.empty
                noNaturalWondersCheckbox.isVisible = mapParameters.type != MapType.empty

                generateExampleMap()
            }

            add("{Map Generation Type}:".toLabel()).left()
            add(mapTypeSelectBox).fillX().row()
            add(mapTypeExample).colspan(2).grow().row()
        }
    }

    private fun addWorldSizeTable() {
        val rng = GameContext().stateBasedRandom("MapParametersTable.addWorldSizeTable", System.currentTimeMillis().toInt())
        if (mapGeneratedMainType == MapGeneratedMainType.randomGenerated) {
            val mapSizes = MapSize.names()
            mapSizesOptionsValues = mapSizes.toHashSet()
            val optionsTable = MultiCheckboxTable("{Enabled World Sizes}", "NewGameWorldSizes", mapSizesOptionsValues, directLayoutWidth) {
                if (mapSizesOptionsValues.isEmpty()) {
                    mapParameters.mapSize = MapSize(mapSizes.random(rng))
                } else {
                    mapParameters.mapSize = MapSize(mapSizesOptionsValues.random(rng))
                }
            }
            add(optionsTable).colspan(2).grow().row()
        } else {
            val mapSizes = MapSize.names() + listOf(MapSize.custom)
            worldSizeSelectBox = TranslatedSelectBox(mapSizes, mapParameters.mapSize.name)
            worldSizeSelectBox.onChange { updateWorldSizeTable(); generateExampleMap() }

            addHexagonalSizeTable()
            addRectangularSizeTable()

            add("{World Size}:".toLabel()).left()
            add(worldSizeSelectBox).fillX().row()
            add(customWorldSizeTable).colspan(2).grow().row()

            updateWorldSizeTable()
        }
    }

    private fun addHexagonalSizeTable() {
        val defaultRadius = mapParameters.mapSize.radius
        customMapSizeRadius = UncivTextField.Integer("Radius", defaultRadius)
        customMapSizeRadius.onChange {
            mapParameters.mapSize = MapSize(customMapSizeRadius.intValue ?: 0)
            updateHexagonalWarnings()
        }
        hexWarningLabel = "".toLabel(Color.RED).apply { wrap = true }
        hexagonalSizeTable.add("{Radius}:".toLabel()).grow().left()
        hexagonalSizeTable.add(customMapSizeRadius).right().row()
        hexagonalSizeTable.add(hexWarningLabel).fillX().colspan(hexagonalSizeTable.columns).row()
        updateHexagonalWarnings()
    }

    private fun updateHexagonalWarnings() {
        val tiles = HexMath.getNumberOfTilesInHexagon(customMapSizeRadius.intValue ?: 0)
        hexWarningLabel.isVisible = tiles >= LARGE_MAP_TILES
        if (tiles >= VERY_LARGE_MAP_TILES) hexWarningLabel.setText(VERY_LARGE_MAP_WARNING.tr())
        else hexWarningLabel.setText(LARGE_MAP_WARNING.tr())
    }

    private fun addRectangularSizeTable() {
        val defaultWidth = mapParameters.mapSize.width
        customMapWidth = UncivTextField.Integer("Width", defaultWidth)
        val defaultHeight = mapParameters.mapSize.height
        customMapHeight = UncivTextField.Integer("Height", defaultHeight)

        customMapWidth.onChange {
            mapParameters.mapSize = MapSize(customMapWidth.intValue ?: 0, customMapHeight.intValue ?: 0)
            updateRectangularWarnings()
        }
        customMapHeight.onChange {
            mapParameters.mapSize = MapSize(customMapWidth.intValue ?: 0, customMapHeight.intValue ?: 0)
            updateRectangularWarnings()
        }

        rectWarningLabel = "".toLabel(Color.RED).apply { wrap = true }

        rectangularSizeTable.defaults().pad(5f)
        rectangularSizeTable.add("{Width}:".toLabel()).grow().left()
        rectangularSizeTable.add(customMapWidth).right().row()
        rectangularSizeTable.add("{Height}:".toLabel()).grow().left()
        rectangularSizeTable.add(customMapHeight).right().row()
        rectangularSizeTable.add(rectWarningLabel).fillX().colspan(hexagonalSizeTable.columns).row()
        updateRectangularWarnings()
    }

    private fun updateRectangularWarnings() {
        val tiles = (customMapWidth.intValue ?: 0) * (customMapHeight.intValue ?: 0)
        rectWarningLabel.isVisible = tiles >= LARGE_MAP_TILES
        if (tiles >= VERY_LARGE_MAP_TILES) rectWarningLabel.setText(VERY_LARGE_MAP_WARNING.tr())
        else rectWarningLabel.setText(LARGE_MAP_WARNING.tr())
    }

    private fun updateWorldSizeTable() {
        customWorldSizeTable.clear()

        if ((mapParameters.shape == MapShape.hexagonal || mapParameters.shape == MapShape.flatEarth) && worldSizeSelectBox.selected.value == MapSize.custom)
            customWorldSizeTable.add(hexagonalSizeTable).grow().row()
        else if (mapParameters.shape == MapShape.rectangular && worldSizeSelectBox.selected.value == MapSize.custom)
            customWorldSizeTable.add(rectangularSizeTable).grow().row()
        else
            mapParameters.mapSize = MapSize(worldSizeSelectBox.selected.value)

        sizeChangedCallback?.invoke()
    }

    private fun addResourceSelectBox() {
        val rng = GameContext().stateBasedRandom("MapParametersTable.addResourceSelectBox", System.currentTimeMillis().toInt())
        val mapResources = MapResourceSetting.activeLabels()

        if (mapGeneratedMainType == MapGeneratedMainType.randomGenerated) {
            mapResourcesOptionsValues = mapResources.toHashSet()
            val optionsTable = MultiCheckboxTable("{Enabled Resource Settings}", "NewGameResourceSettings", mapResourcesOptionsValues, directLayoutWidth) {
                if (mapResourcesOptionsValues.isEmpty()) {
                    mapParameters.mapResources = mapResources.random(rng)
                } else {
                    mapParameters.mapResources = mapResourcesOptionsValues.random(rng)
                }
            }
            add(optionsTable).colspan(2).grow().row()
        } else {
            resourceSelectBox = TranslatedSelectBox(mapResources, mapParameters.mapResources)

            resourceSelectBox.onChange {
                mapParameters.mapResources = resourceSelectBox.selected.value
            }

            if (forMapEditor) {
                val comment = "This is used for painting resources, not in map generator steps:"
                val expectedWidth = (UncivGame.Current.screen?.stage?.width ?: 1200f) * 0.4f
                val label = WrappableLabel(comment, expectedWidth, Color.GOLD, 14)
                label.setAlignment(Align.center)
                label.wrap = true
                add(label).colspan(2).row()
            }
            add("{Resource Setting}:".toLabel()).left()
            add(resourceSelectBox).fillX().row()
        }
    }

    private fun addMirrorSelectBox() {
        if (! forMapEditor)
            return
        
        // only support these, as the rest seem buggy
        val options = listOf(
            MirroringType.none,
            MirroringType.leftright
        )
        
        mirrorSelectBox = TranslatedSelectBox(options, mapParameters.mirroring)

        mirrorSelectBox.onChange {
            mapParameters.mirroring = mirrorSelectBox.selected.value
        }

        add("{Mirroring Type}:".toLabel()).left()
        add(mirrorSelectBox).fillX().row()
    }

    private fun Table.addNoRuinsCheckbox() {
        noRuinsCheckbox = "No Ancient Ruins".toCheckBox(mapParameters.noRuins) {
            mapParameters.noRuins = it
        }
        add(noRuinsCheckbox).row()
    }

    private fun Table.addNoNaturalWondersCheckbox() {
        noNaturalWondersCheckbox = "No Natural Wonders".toCheckBox(mapParameters.noNaturalWonders) {
            mapParameters.noNaturalWonders = it
        }
        add(noNaturalWondersCheckbox).row()
    }

    private fun Table.addWorldWrapCheckbox() {
        worldWrapCheckbox = "World Wrap".toCheckBox(mapParameters.worldWrap) {
            mapParameters.worldWrap = it
        }
        add(worldWrapCheckbox).row()
    }

    private fun Table.addStrategicBalanceCheckbox() {
        strategicBalanceCheckbox = "Strategic Balance".toCheckBox(mapParameters.strategicBalance) {
            mapParameters.strategicBalance = it
        }
        add(strategicBalanceCheckbox).row()
    }

    private fun Table.addLegendaryStartCheckbox() {
        legendaryStartCheckbox = "Legendary Start".toCheckBox(mapParameters.legendaryStart) {
            mapParameters.legendaryStart = it
        }
        add(legendaryStartCheckbox).row()
    }

    private fun addWrappedCheckBoxes() {
        val worldWrapWarning = "World wrap maps are very memory intensive - creating large world wrap maps on Android can lead to crashes!"
        if (mapGeneratedMainType == MapGeneratedMainType.randomGenerated) {
            if (directLayoutWidth != null) {
                add(SinglePlayerSetup.group("{Other Settings}", directLayoutWidth!!, "World.RandomOther") {
                    it.defaults().left().growX().minHeight(44f).padBottom(8f)
                    it.addStrategicBalanceCheckbox()
                    it.addLegendaryStartCheckbox()
                    it.addNoRuinsCheckbox()
                    it.addNoNaturalWondersCheckbox()
                    it.addWorldWrapCheckbox()
                    it.add(worldWrapWarning.toLabel(fontSize = 18).apply { wrap = true }).minWidth(0f).row()
                }).width(directLayoutWidth!!).padTop(8f).growX().row()
                return
            }
            add(ExpanderTab("{Other Settings}",
                icon = ImageGetter.getImage("OtherIcons/Settings").apply { setSize(20f, 20f) },
                persistenceID = "NewGameOtherSettings", startsOutOpened = false) {
                it.defaults().pad(5f,0f)
                it.addStrategicBalanceCheckbox()
                it.addLegendaryStartCheckbox()
                it.addNoRuinsCheckbox()
                it.addNoNaturalWondersCheckbox()
                it.addWorldWrapCheckbox()
                it.add(worldWrapWarning.toLabel(fontSize = 14).apply { wrap=true }).colspan(2).fillX().row()
            }).pad(10f).padTop(10f).colspan(2).growX().row()
        } else {
            add(Table(skin).apply {
                defaults().left().pad(2.5f)
                addStrategicBalanceCheckbox()
                addLegendaryStartCheckbox()
                addNoRuinsCheckbox()
                addNoNaturalWondersCheckbox()
                addWorldWrapCheckbox()
            }).colspan(2).center().row()
            add(worldWrapWarning.toLabel(fontSize = 14).apply { wrap=true }).colspan(2).fillX().row()
        }
    }

    private fun addAdvancedSettings() {
        val expander = ExpanderTab("Advanced Settings",
            icon = ImageGetter.getImage("OtherIcons/Settings").apply { setSize(20f, 20f) },
            startsOutOpened = false, defaultPad = 0f) {
            addAdvancedControls(it)
        }
        add(expander).padTop(10f).colspan(2).growX().row()
    }

    private fun addAdvancedControls(table: Table) {
        table.defaults().pad(2f).padTop(10f)

        seedTextField = UncivTextField.Numeric("RNG Seed", mapParameters.seed, integerOnly = true)

        seedTextField.onChange {
            mapParameters.seed = seedTextField.value?.toLong() ?: 0L
        }

        table.add("RNG Seed".toLabel()).left()
        if (roomLayoutWidth != null || directLayoutWidth != null) table.row()
        table.add(seedTextField).fillX().padBottom(10f).row()

        fun addSlider(text: String, getValue:()->Float, min: Float, max: Float, step: Float, onChange: (value: Float)->Unit): UncivSlider {
            val slider = UncivSlider(min, max, step, onChange = {onChange(it); generateExampleMap()}, initial = getValue())
            table.add(text.toLabel()).left()
            if (roomLayoutWidth != null || directLayoutWidth != null) table.row()
            table.add(slider).fillX().row()
            advancedSliders[slider] = getValue
            return slider
        }

        fun addSlider(text: String, getValue:()->Float, min: Float, max: Float, onChange: (value: Float)->Unit): UncivSlider {
            val step = (max - min) / 20
            return addSlider(text, getValue, min, max, step, onChange)
        }

        fun addTextButton(text: String, shouldAddToTable: Boolean = false, action: ((Boolean) -> Unit)) {
            val button = text.toTextButton()
            button.onClick { action.invoke(true) }
            if (shouldAddToTable)
                table.add(button).colspan(if (directLayoutWidth != null) 1 else 2).padTop(10f).row()
        }

        fun addCheckBox(text: String, initialState: Boolean, action: ((Boolean) -> Unit)) {
            val checkbox = text.toCheckBox(initialState){
                action(it)
            }
            table.add(checkbox).colspan(2).row()
        }
        if (forMapEditor) {
            addCheckBox("Randomize seed", randomizeSeed) {
                randomizeSeed = it
            }
        }
        addSlider("Map Elevation", {mapParameters.elevationExponent}, 0.6f, 0.8f)
        { mapParameters.elevationExponent = it }

        addSlider("Temperature intensity", {mapParameters.temperatureintensity}, 0.4f, 0.8f)
        { mapParameters.temperatureintensity = it }

        addSlider("Temperature shift", {mapParameters.temperatureShift}, -0.4f, 0.4f, 0.1f)
        { mapParameters.temperatureShift = it }

        if (forMapEditor) {
            addSlider("Resource richness", { mapParameters.resourceRichness }, 0f, 0.5f)
            { mapParameters.resourceRichness = it }
        }

        addSlider("Vegetation richness", {mapParameters.vegetationRichness}, 0f, 1f)
        { mapParameters.vegetationRichness = it }

        addSlider("Rare features richness", {mapParameters.rareFeaturesRichness}, 0f, 0.5f)
        { mapParameters.rareFeaturesRichness = it }

        addSlider("Max Coast extension", {mapParameters.maxCoastExtension.toFloat()}, 1f, 5f)
        { mapParameters.maxCoastExtension = it.toInt() }.apply { stepSize = 1f }

        addSlider("Biome size", {mapParameters.tilesPerBiomeArea.toFloat()}, 1f, 15f)
        { mapParameters.tilesPerBiomeArea = it.toInt() }.apply { stepSize = 1f }

        addSlider("Water level", {mapParameters.waterThreshold}, -0.1f, 0.1f)
        { mapParameters.waterThreshold = it }

        addTextButton("Reset to defaults", true) {
            mapParameters.resetAdvancedSettings()
            seedTextField.value = mapParameters.seed
            for (entry in advancedSliders)
                entry.key.value = entry.value()
        }
    }
}
