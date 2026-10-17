package com.unciv.ui.screens.mapeditorscreen.tabs

import com.unciv.ui.components.extensions.setFontSize
import com.unciv.ui.components.fonts.UiTypography
import com.unciv.ui.components.fonts.TextRole
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.ui.ButtonGroup
import com.badlogic.gdx.scenes.scene2d.ui.CheckBox
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.unciv.Constants
import com.unciv.UncivGame
import com.unciv.logic.map.TileMap
import com.unciv.logic.map.tile.RoadStatus
import com.unciv.logic.map.tile.Tile
import com.unciv.view.TileView
import com.unciv.models.ruleset.Ruleset
import com.unciv.models.ruleset.nation.Nation
import com.unciv.models.ruleset.tile.*
import com.unciv.models.ruleset.unique.GameContext
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.translations.tr
import com.unciv.ui.audio.MusicMood
import com.unciv.ui.audio.MusicTrackChooserFlags
import com.unciv.ui.components.NonTransformGroup
import com.unciv.ui.components.extensions.center
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.input.onClick
import com.unciv.ui.components.tilegroups.TileGroup
import com.unciv.ui.components.tilegroups.TileSetStrings
import com.unciv.ui.components.widgets.TabbedPager
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.civilopediascreen.FormattedLine
import com.unciv.ui.screens.civilopediascreen.MarkupRenderer
import com.unciv.ui.screens.mapeditorscreen.tabs.MapEditorEditTab.BrushHandlerType

internal interface IMapEditorEditSubTabs {
    fun isDisabled(): Boolean
}


/** Implements the Map editor Edit-Terrains UI Tab */
class MapEditorEditTerrainTab(
    private val editTab: MapEditorEditTab,
    private val ruleset: Ruleset
): Table(BaseScreen.skin), IMapEditorEditSubTabs {
    init {
        top()
        defaults().pad(4f * com.unciv.ui.screens.mapeditorscreen.MapEditorStyle.unit).left()
        add(
            editTab.renderPalette(
            getTerrains(),
            iconDisplay = FormattedLine.IconDisplay.NoLink
        ) {
            editTab.setBrush(it, "Terrain/$it") { tile ->
                tile.baseTerrain = it
                tile.naturalWonder = null
            }
        })
    }

    private fun allTerrains() = ruleset.terrains.values.asSequence()
        .filter { it.type.isBaseTerrain }
        .filterNot { it.hasUnique(UniqueType.ExcludedFromMapEditor, GameContext.IgnoreConditionals) }
    private fun getTerrains() = allTerrains()
        .map { FormattedLine(it.name, it.name, "Terrain/${it.name}", size = 32) }
        .asIterable()

    override fun isDisabled() = false // allTerrains().none() // wanna see _that_ mod...
}


/** Implements the Map editor Edit-Features UI Tab */
class MapEditorEditFeaturesTab(
    private val editTab: MapEditorEditTab,
    private val ruleset: Ruleset
): Table(BaseScreen.skin), IMapEditorEditSubTabs {
    init {
        top()
        defaults().pad(4f * com.unciv.ui.screens.mapeditorscreen.MapEditorStyle.unit).left()
        allowedFeatures().firstOrNull()?.let { addFeatures(it) }
    }

    private fun addFeatures(firstFeature: Terrain) {
        val eraserIcon = "Terrain/${firstFeature.name}"
        val eraser = FormattedLine("Remove features", icon = eraserIcon, size = 32, iconCrossed = true)
        add(editTab.brushCard(eraser).apply { onClick {
            editTab.setBrush("Remove features", eraserIcon, pediaLink = "", isRemove = true) { tile ->
                tile.removeTerrainFeatures()
            }
        } }).padBottom(0f)
        add(
            editTab.renderPalette(
            getFeatures(),
            iconDisplay = FormattedLine.IconDisplay.NoLink
        ) {
            editTab.setBrush(it, "Terrain/$it") { tile ->
                if (it !in tile.terrainFeatures)
                    tile.addTerrainFeature(it)
            }
        }).padTop(0f)
    }

    private fun allowedFeatures() = ruleset.terrains.values.asSequence()
        .filter { it.type == TerrainType.TerrainFeature }
        .filterNot { it.hasUnique(UniqueType.ExcludedFromMapEditor, GameContext.IgnoreConditionals) }
    private fun getFeatures() = allowedFeatures()
        .map { FormattedLine(it.name, it.name, "Terrain/${it.name}", size = 32) }
        .asIterable()

    override fun isDisabled() = allowedFeatures().none()
}


/** Implements the Map editor Edit-NaturalWonders UI Tab */
class MapEditorEditWondersTab(
    private val editTab: MapEditorEditTab,
    private val ruleset: Ruleset
): Table(BaseScreen.skin), IMapEditorEditSubTabs {
    init {
        top()
        defaults().pad(4f * com.unciv.ui.screens.mapeditorscreen.MapEditorStyle.unit).left()
        add(
            editTab.renderPalette(
            getWonders(),
            iconDisplay = FormattedLine.IconDisplay.NoLink
        ) {
            editTab.setBrush(it, "Terrain/$it") { tile ->
                // Normally the caller would ensure compliance, but here we make an exception - place it no matter what
                if (ruleset.terrains[it]!!.turnsInto != null)
                    tile.baseTerrain = ruleset.terrains[it]!!.turnsInto!!
                tile.removeTerrainFeatures()
                tile.naturalWonder = it
            }
        })
    }

    private fun allowedWonders() = ruleset.terrains.values.asSequence()
        .filter { it.type == TerrainType.NaturalWonder }
        .filterNot { it.hasUnique(UniqueType.ExcludedFromMapEditor, GameContext.IgnoreConditionals) }
    private fun getWonders() = allowedWonders()
        .map { FormattedLine(it.name, it.name, "Terrain/${it.name}", size = 32) }
        .asIterable()

    override fun isDisabled() = allowedWonders().none()
}


/** Implements the Map editor Edit-Resources UI Tab */
class MapEditorEditResourcesTab(
    private val editTab: MapEditorEditTab,
    private val ruleset: Ruleset
): Table(BaseScreen.skin), IMapEditorEditSubTabs {
    init {
        top()
        defaults().pad(4f * com.unciv.ui.screens.mapeditorscreen.MapEditorStyle.unit).left()
        allowedResources().firstOrNull()?.let { addResources(it) }
    }

    private fun addResources(firstResource: TileResource) {
        val eraserIcon = "Resource/${firstResource.name}"
        val eraser = FormattedLine("Remove resource", icon = eraserIcon, size = 32, iconCrossed = true)
        add(editTab.brushCard(eraser).apply { onClick {
            editTab.setBrush("Remove resource", eraserIcon, pediaLink = "", isRemove = true) { tile ->
                tile.tileResource = null
                tile.resourceAmount = 0
            }
        } }).padBottom(0f)
        add(
            editTab.renderPalette(
            getResources(),
            iconDisplay = FormattedLine.IconDisplay.NoLink
        ) { resourceName ->
            val resource = ruleset.tileResources[resourceName]!!
            editTab.setBrush(resourceName, resource.makeLink()) {
                if (it.tileResource == resource && resource.resourceType == ResourceType.Strategic)
                    it.resourceAmount = (it.resourceAmount + 1).coerceAtMost(42)
                else
                    it.setTileResource(resource, rng = editTab.randomness.RNG)
            }
        }).padTop(0f)
    }

    private fun allowedResources() = ruleset.tileResources.values.asSequence()
        .filterNot { it.hasUnique(UniqueType.CityStateOnlyResource) }
        .filterNot { it.hasUnique(UniqueType.ExcludedFromMapEditor, GameContext.IgnoreConditionals) }
    private fun getResources(): Iterable<FormattedLine> = sequence {
        var lastGroup = ResourceType.Bonus
        for (resource in allowedResources()) {
            val name = resource.name
            if (resource.resourceType != lastGroup) {
                lastGroup = resource.resourceType
                yield(FormattedLine(separator = true, color = "#888"))
            }
            yield (FormattedLine(name, name, "Resource/$name", size = 32))
        }
    }.asIterable()

    override fun isDisabled() = allowedResources().none()
}


/** Implements the Map editor Edit-Improvements UI Tab */
class MapEditorEditImprovementsTab(
    private val editTab: MapEditorEditTab,
    private val ruleset: Ruleset
): Table(BaseScreen.skin), IMapEditorEditSubTabs {
    init {
        top()
        defaults().pad(4f * com.unciv.ui.screens.mapeditorscreen.MapEditorStyle.unit).left()
        allowedImprovements().firstOrNull()?.let { addImprovements(it) }
    }

    private fun addImprovements(firstImprovement: TileImprovement) {
        val eraserIcon = "Improvement/${firstImprovement.name}"
        val eraser = FormattedLine("Remove improvement", icon = eraserIcon, size = 32, iconCrossed = true)
        add(editTab.brushCard(eraser).apply { onClick {
            editTab.setBrush("Remove improvement", eraserIcon, pediaLink = "", isRemove = true) { tile ->
                tile.removeImprovement()
                tile.removeRoad()
            }
        } }).padBottom(0f)
        add(
            editTab.renderPalette(
            getImprovements(),
            iconDisplay = FormattedLine.IconDisplay.NoLink
        ) {
            val road = RoadStatus.entries.firstOrNull { r -> r.name == it }
            if (road != null)
                editTab.setBrush(it, "Improvement/$it", handlerType = BrushHandlerType.Road) { tile ->
                    tile.setRoadStatus(if (tile.roadStatus == road) RoadStatus.None else road, null)
                }
            else
                editTab.setBrush(it, "Improvement/$it") { tile ->
                    tile.setImprovement(it)
                }
        }).padTop(0f)
    }

    private fun allowedImprovements() = ruleset.tileImprovements.values.asSequence()
        .filterNot { it.hasUnique(UniqueType.ExcludedFromMapEditor, GameContext.IgnoreConditionals) }
    private fun getImprovements(): Iterable<FormattedLine> = sequence {
        var lastGroup = 0
        for (improvement in allowedImprovements()) {
            val name = improvement.name
            val group = improvement.group()
            if (group != lastGroup) {
                lastGroup = group
                yield(FormattedLine(separator = true, color = "#888"))
            }
            yield (FormattedLine(name, name, "Improvement/$name", size = 32))
        }
    }.asIterable()

    override fun isDisabled() = allowedImprovements().none()

    companion object {
        private fun TileImprovement.group() = when {
            RoadStatus.entries.any { it.name == name } -> 2
            "Great Improvement" in uniques -> 3
            uniqueTo != null -> 4
            "Unpillagable" in uniques -> 5
            else -> 0
        }
    }
}


/** Implements the Map editor Edit-StartingLocations UI Tab */
class MapEditorEditStartsTab(
    private val editTab: MapEditorEditTab,
    private val ruleset: Ruleset
): Table(BaseScreen.skin), IMapEditorEditSubTabs {
    private val collator = UncivGame.Current.settings.getCollatorFromLocale()
    private val usageOptionGroup = ButtonGroup<CheckBox>()

    init {
        top()
        defaults().pad(4f * com.unciv.ui.screens.mapeditorscreen.MapEditorStyle.unit).left()
        allowedNations().firstOrNull()?.let { addNations(it) }
    }

    private fun String.spectatorToAnyCiv() = if (this == Constants.spectator) "Any Civ" else this

    private fun addNations(firstNation: Nation) {
        val eraserIcon = "Nation/${firstNation.name}"
        val eraser = FormattedLine("Remove starting locations", icon = eraserIcon, size = 24, iconCrossed = true)
        add(editTab.brushCard(eraser).apply { onClick {
            editTab.setBrush("Remove", eraserIcon, handlerType = BrushHandlerType.Direct, pediaLink = "", isRemove = true) { tile ->
                tile.tileMap.removeStartingLocations(tile.position)
            }
        } }).padBottom(0f)

        addUsage()

        // Create the nation list with the spectator nation included, and shown/interpreted as "Any Civ" starting location.
        // We use Nation/Spectator because it hasn't been used yet and we need an icon within the Nation.
        add(
            editTab.renderPalette(
                getNations(),
                iconDisplay = FormattedLine.IconDisplay.NoLink
            ) {
                UncivGame.Current.musicController.chooseTrack(it, MusicMood.Theme, MusicTrackChooserFlags.setSpecific)
                val icon = "Nation/$it"
                val pediaLink = if (it == Constants.spectator) "" else icon
                val isMajorCiv = ruleset.nations[it]?.isMajorCiv ?: false
                editTab.setBrush(it.spectatorToAnyCiv(), icon, BrushHandlerType.Direct, pediaLink) { tile ->
                    // toggle the starting location here, note this allows
                    // both multiple locations per nation and multiple nations per tile
                    if (!tile.tileMap.addStartingLocation(it, tile, if (isMajorCiv) TileMap.StartingLocation.Usage.entries[usageOptionGroup.checkedIndex] else TileMap.StartingLocation.Usage.Normal))
                        tile.tileMap.removeStartingLocation(it, tile)
                }
            }
        ).padTop(0f).row()
    }

    private fun allowedNations() = ruleset.nations.values.asSequence()
        .filterNot { it.hasUnique(UniqueType.ExcludedFromMapEditor) }
    private fun getNations() = allowedNations()
        .sortedWith(
            compareBy<Nation> { !it.isSpectator }
                .thenBy { it.isCityState }
                .thenBy(collator) { it.name.tr(hideIcons = true) }
        ).map {
            FormattedLine("[${it.name.spectatorToAnyCiv()}] starting location", link = it.name, icon = "Nation/${it.name}", size = 24)
        }.asIterable()

    private fun usageCaption(usage: TileMap.StartingLocation.Usage) = when (usage) {
        TileMap.StartingLocation.Usage.Normal -> "Starting position only"
        TileMap.StartingLocation.Usage.Player -> "Include in player selection"
        TileMap.StartingLocation.Usage.Human -> "First human player"
    }
    private fun addUsage() {
        val popup = com.unciv.ui.popups.Popup(editTab.screen())
        popup.addGoodSizedLabel("Use for new game \"Select players\" button:").row()
        val defaultUsage = TileMap.StartingLocation.Usage.default
        val button = com.unciv.ui.screens.mapeditorscreen.MapEditorStyle.button(usageCaption(defaultUsage)) { popup.open() }
        for (usage in TileMap.StartingLocation.Usage.entries) {
            val checkBox = CheckBox(usageCaption(usage).tr(), skin).apply { label.setFontSize(UiTypography.size(TextRole.Control)) }
            popup.add(checkBox).minHeight(com.unciv.ui.components.widgets.UiNavigation.minimumTouchSize()).row()
            usageOptionGroup.add(checkBox)
            checkBox.isChecked = usage == defaultUsage
            checkBox.onClick { button.setText(usageCaption(usage).tr()); popup.close() }
        }
        popup.addCloseButton()
        add(button).height(com.unciv.ui.components.widgets.UiNavigation.minimumTouchSize())
    }

    override fun isDisabled() = allowedNations().none()
}


/** Implements the Map editor Edit-Rivers UI Tab */
class MapEditorEditRiversTab(
    private val editTab: MapEditorEditTab,
    private val ruleset: Ruleset
): Table(BaseScreen.skin), IMapEditorEditSubTabs, TabbedPager.IPageExtensions {
    private val iconSize = 32f * com.unciv.ui.screens.mapeditorscreen.MapEditorStyle.unit
    private val showOnTerrain = ruleset.terrains.values.asSequence()
        .filter { it.type.isBaseTerrain && !it.isRough}
        .sortedByDescending { it.production * 2 + it.food }
        .firstOrNull()
        ?: ruleset.terrains[Constants.plains]
        ?: ruleset.terrains.values.first()

    init {
        val pediaLink = "Terrain/River"

        top()
        defaults().pad(4f * com.unciv.ui.screens.mapeditorscreen.MapEditorStyle.unit).left()
        val removeLine = Table().apply {
            add(getRemoveRiverIcon()).size(iconSize).row()
            add("Remove rivers".toLabel(fontSize = UiTypography.size(TextRole.Supporting, unit = com.unciv.ui.screens.mapeditorscreen.MapEditorStyle.unit)))
            onClick {
                editTab.setBrush(BrushHandlerType.River,"Remove rivers", getRemoveRiverIcon(), pediaLink) { tile ->
                    tile.hasBottomLeftRiver = false
                    tile.hasBottomRightRiver = false
                    tile.hasBottomRiver = false
                    // User probably expects all six edges to be cleared
                    val x = tile.position.x
                    val y = tile.position.y
                    tile.tileMap.getIfTileExistsOrNull(x, y + 1)?.hasBottomLeftRiver = false
                    tile.tileMap.getIfTileExistsOrNull(x + 1, y)?.hasBottomRightRiver = false
                    tile.tileMap.getIfTileExistsOrNull(x + 1, y + 1)?.hasBottomRiver = false
                }
            }
        }
        editTab.registerCard(removeLine, "Remove rivers")
        removeLine.background = com.unciv.ui.screens.mapeditorscreen.MapEditorStyle.background(com.unciv.ui.screens.mapeditorscreen.MapEditorStyle.raised)
        add(removeLine).height(80f * com.unciv.ui.screens.mapeditorscreen.MapEditorStyle.unit).pad(4f)

        val leftRiverLine = Table().apply {
            add(getRiverIcon(RiverEdge.Left)).size(iconSize).row()
            add("Bottom left river".toLabel(fontSize = UiTypography.size(TextRole.Supporting, unit = com.unciv.ui.screens.mapeditorscreen.MapEditorStyle.unit)))
            onClick {
                editTab.setBrush(BrushHandlerType.Direct,"Bottom left river", getTileGroupWithRivers(
                    RiverEdge.Left
                ), pediaLink) { tile ->
                    tile.hasBottomLeftRiver = !tile.hasBottomLeftRiver
                }
            }
        }
        editTab.registerCard(leftRiverLine, "Bottom left river")
        leftRiverLine.background = com.unciv.ui.screens.mapeditorscreen.MapEditorStyle.background(com.unciv.ui.screens.mapeditorscreen.MapEditorStyle.raised)
        add(leftRiverLine).height(80f * com.unciv.ui.screens.mapeditorscreen.MapEditorStyle.unit).pad(4f)

        val bottomRiverLine = Table().apply {
            add(getRiverIcon(RiverEdge.Bottom)).size(iconSize).row()
            add("Bottom river".toLabel(fontSize = UiTypography.size(TextRole.Supporting, unit = com.unciv.ui.screens.mapeditorscreen.MapEditorStyle.unit)))
            onClick {
                editTab.setBrush(BrushHandlerType.Direct,"Bottom river", getTileGroupWithRivers(
                    RiverEdge.Bottom
                ), pediaLink) { tile ->
                    tile.hasBottomRiver = !tile.hasBottomRiver
                }
            }
        }
        editTab.registerCard(bottomRiverLine, "Bottom river")
        bottomRiverLine.background = com.unciv.ui.screens.mapeditorscreen.MapEditorStyle.background(com.unciv.ui.screens.mapeditorscreen.MapEditorStyle.raised)
        add(bottomRiverLine).height(80f * com.unciv.ui.screens.mapeditorscreen.MapEditorStyle.unit).pad(4f)

        val rightRiverLine = Table().apply {
            add(getRiverIcon(RiverEdge.Right)).size(iconSize).row()
            add("Bottom right river".toLabel(fontSize = UiTypography.size(TextRole.Supporting, unit = com.unciv.ui.screens.mapeditorscreen.MapEditorStyle.unit)))
            onClick {
                editTab.setBrush(BrushHandlerType.Direct,"Bottom right river", getTileGroupWithRivers(
                    RiverEdge.Right
                ), pediaLink) { tile ->
                    tile.hasBottomRightRiver = !tile.hasBottomRightRiver
                }
            }
        }
        editTab.registerCard(rightRiverLine, "Bottom right river")
        rightRiverLine.background = com.unciv.ui.screens.mapeditorscreen.MapEditorStyle.background(com.unciv.ui.screens.mapeditorscreen.MapEditorStyle.raised)
        add(rightRiverLine).height(80f * com.unciv.ui.screens.mapeditorscreen.MapEditorStyle.unit).pad(4f)

        //todo this needs a better icon
        val spawnRiverLine = Table().apply {
            add(getRiverIcon(RiverEdge.All)).size(iconSize).row()
            add("Spawn river from/to".toLabel(fontSize = UiTypography.size(TextRole.Supporting, unit = com.unciv.ui.screens.mapeditorscreen.MapEditorStyle.unit)))
            onClick {
                editTab.setBrush(
                    BrushHandlerType.RiverFromTo,
                    name = "Spawn river from/to",
                    icon = getTileGroupWithRivers(RiverEdge.All),
                    pediaLink = pediaLink,
                    applyAction = {}  // Actual effect done via BrushHandlerType
                )
            }
        }
        editTab.registerCard(spawnRiverLine, "Spawn river from/to")
        spawnRiverLine.background = com.unciv.ui.screens.mapeditorscreen.MapEditorStyle.background(com.unciv.ui.screens.mapeditorscreen.MapEditorStyle.raised)
        add(spawnRiverLine).height(80f * com.unciv.ui.screens.mapeditorscreen.MapEditorStyle.unit).pad(4f)
    }

    override fun isDisabled() = false

    override fun activated(index: Int, caption: String, pager: TabbedPager) {
        editTab.brushSize = 1
    }

    private fun Tile.makeTileGroup(): TileGroup {
        ruleset = this@MapEditorEditRiversTab.ruleset
        setTerrainTransients()
        return TileGroup(TileView.forSingleTile(this), TileSetStrings(ruleset, UncivGame.Current.settings), iconSize * 36f/54f).apply {
            isForMapEditorIcon = true
            update()
        }
    }

    private enum class RiverEdge { Left, Bottom, Right, All }
    private fun getTileGroupWithRivers(edge: RiverEdge) =
        Tile().apply {
            baseTerrain = showOnTerrain.name
            when (edge) {
                RiverEdge.Left -> hasBottomLeftRiver = true
                RiverEdge.Bottom -> hasBottomRiver = true
                RiverEdge.Right -> hasBottomRightRiver = true
                RiverEdge.All -> {
                    hasBottomLeftRiver = true
                    hasBottomRightRiver = true
                    hasBottomRiver = true
                }
            }
        }.makeTileGroup()
    private fun getRemoveRiverIcon() =
        ImageGetter.getCrossedImage(getTileGroupWithRivers(RiverEdge.All), iconSize)
    private fun getRiverIcon(edge: RiverEdge) = NonTransformGroup().apply {
        // wrap same as getRemoveRiverIcon so the icons align the same (using getTileGroupWithRivers directly works but looks ugly - reason unknown to me)
        setSize(iconSize, iconSize)
        val tileGroup = getTileGroupWithRivers(edge)
        tileGroup.center(this)
        addActor(tileGroup)
    }
}


/** Implements the Map editor Edit-Units UI Tab */
@Suppress("unused")
class MapEditorEditUnitsTab(
    private val editTab: MapEditorEditTab,
    private val ruleset: Ruleset
): Table(BaseScreen.skin), IMapEditorEditSubTabs {
    init {
        top()
        defaults().pad(4f * com.unciv.ui.screens.mapeditorscreen.MapEditorStyle.unit).left()
        add("Work in progress".toLabel(Color.FIREBRICK, UiTypography.size(TextRole.PageTitle, legacySize = 24)))
    }

    override fun isDisabled() = true
}
