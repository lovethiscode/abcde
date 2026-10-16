package com.unciv.ui.screens.mapeditorscreen.tabs

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.unciv.logic.map.BFS
import com.unciv.logic.map.mapgenerator.MapGenerationRandomness
import com.unciv.logic.map.mapgenerator.MapGenerator
import com.unciv.logic.map.mapgenerator.RiverGenerator
import com.unciv.logic.map.tile.Tile
import com.unciv.logic.map.tile.TileNormalizer
import com.unciv.models.ruleset.Ruleset
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.input.KeyCharAndCode
import com.unciv.ui.components.input.keyShortcuts
import com.unciv.ui.components.input.onActivation
import com.unciv.ui.components.input.activate
import com.unciv.ui.components.widgets.TabbedPager
import com.unciv.ui.components.widgets.AutoScrollPane
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.popups.ToastPopup
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.civilopediascreen.FormattedLine
import com.unciv.ui.screens.mapeditorscreen.MapEditorScreen
import com.unciv.ui.screens.mapeditorscreen.MapEditorStyle
import com.unciv.ui.screens.mapeditorscreen.tabs.MapEditorOptionsTab.TileMatchFuzziness
import com.unciv.utils.Log

/** The same brush handlers as the editor, presented as a persistent two-level palette. */
class MapEditorEditTab(private val editorScreen: MapEditorScreen, @Suppress("UNUSED_PARAMETER") headerHeight: Float)
    : Table(BaseScreen.skin), TabbedPager.IPageExtensions {
    private var ruleset = editorScreen.ruleset
    internal val randomness = MapGenerationRandomness()
    enum class BrushHandlerType { None, Direct, Tile, Road, River, RiverFromTo }
    private var brushHandlerType = BrushHandlerType.None
    private var brushAction: (Tile)->Unit = {}
    private var strokeTiles: MutableSet<com.unciv.logic.map.HexCoord>? = null
    fun beginStroke() { strokeTiles = mutableSetOf() }
    fun endStroke() { strokeTiles = null }
    internal var brushSize = 1
    var brushName = "Grassland"
        private set
    private var pediaLink = "Terrain/Grassland"
    private var tileMatchFuzziness = TileMatchFuzziness.CompleteMatch
    private var riverStartTile: Tile? = null
    private var riverEndTile: Tile? = null
    private val categories = Table().left()
    private lateinit var collapseButton: TextButton
    private val materials = Table()
    private val categoryButtons = ArrayList<TextButton>()
    private val pages = ArrayList<Table>()
    private val cards = ArrayList<Pair<Table, String>>()
    var selectedCategory = 0
        private set
    var collapsed = false
        private set
    private val u get() = MapEditorStyle.unit
    private val pageNames = listOf("Editor terrain", "Editor features", "Wonders", "Resources", "Improvements", "Rivers", "Editor starts")
    private val factories: List<(MapEditorEditTab, Ruleset)->Table> = listOf(
        ::MapEditorEditTerrainTab, ::MapEditorEditFeaturesTab, ::MapEditorEditWondersTab,
        ::MapEditorEditResourcesTab, ::MapEditorEditImprovementsTab, ::MapEditorEditRiversTab, ::MapEditorEditStartsTab)

    init {
        background = MapEditorStyle.background(MapEditorStyle.panel)
        categories.defaults().height(44*u).padRight(4*u)
        pageNames.forEachIndexed { index, name ->
            val button = MapEditorStyle.button(name) { selectCategory(index) }
            categoryButtons.add(button)
            categories.add(button).minWidth(70*u)
        }
        val scroll = paletteRail(categories, 44*u)
        val categoryRow = Table().apply {
            add(scroll).growX().minWidth(0f)
            collapseButton = MapEditorStyle.button("Collapse") {
                collapsed = !collapsed
                collapseButton.setText((if (collapsed) "Expand" else "Collapse").tr())
                layoutPalette()
                editorScreen.chrome.layoutChrome()
            }
            add(collapseButton).height(44*u).padLeft(8*u)
        }
        add(categoryRow).growX().row()
        add(materials).growX()
        listOf('t','f','w','r','i','v','s').forEachIndexed { i, key -> keyShortcuts.add(key) { selectCategory(i) } }
        for (size in 1..5) keyShortcuts.add(size.toString().first()) { brushSize = size }
        keyShortcuts.add(KeyCharAndCode.ctrl('f')) { brushSize = -1 }
    }

    fun selectCategory(index: Int) {
        if (index !in pages.indices || (pages[index] as IMapEditorEditSubTabs).isDisabled()) return
        selectedCategory = index
        if (index == 5) brushSize = 1
        categoryButtons.forEachIndexed { i, b -> MapEditorStyle.select(b, i == index) }
        collapsed = false
        collapseButton.setText("Collapse".tr())
        layoutPalette()
        if (editorScreen.hasChrome()) editorScreen.chrome.layoutChrome()
    }

    private fun layoutPalette() {
        materials.clearChildren()
        if (collapsed || pages.isEmpty()) return
        val scroll = paletteRail(pages[selectedCategory], 88*u)
        materials.add(scroll).growX().minWidth(0f).height(88*u)
        invalidateHierarchy()
    }

    /** Explicit paging controls make offscreen brushes discoverable without knowing the swipe gesture. */
    private fun paletteRail(content: Table, railHeight: Float): Table {
        val pane = AutoScrollPane(content).apply {
            setScrollingDisabled(false, true)
            setOverscroll(false, false)
            fadeScrollBars = false
        }
        fun pageButton(direction: Int) = MapEditorStyle.button("") {
            pane.scrollX = (pane.scrollX + direction * pane.width * .8f).coerceIn(0f, pane.maxX)
            pane.updateVisualScroll()
        }.apply {
            name = if (direction < 0) "map-palette-previous" else "map-palette-next"
            clearChildren()
            add(ImageGetter.getArrowImage(if (direction < 0) com.badlogic.gdx.utils.Align.left
                else com.badlogic.gdx.utils.Align.right).apply {
                    color = MapEditorStyle.text
                    setOrigin(9*u, 9*u)
                }).size(18*u)
        }
        val previous = pageButton(-1)
        val next = pageButton(1)
        return object : Table() {
            init { addActor(pane); addActor(previous); addActor(next) }
            override fun getMinWidth() = 0f
            override fun getPrefHeight() = railHeight
            override fun layout() {
                val overflow = content.prefWidth > width + 1f
                val edge = if (overflow) 44*u else 0f
                previous.isVisible = overflow
                next.isVisible = overflow
                pane.setBounds(edge, 0f, (width - 2*edge).coerceAtLeast(0f), height)
                previous.setBounds(0f, 0f, edge, height)
                next.setBounds(width-edge, 0f, edge, height)
                pane.validate()
            }
            override fun act(delta: Float) {
                super.act(delta)
                previous.isDisabled = pane.scrollX < 1f
                next.isDisabled = pane.scrollX >= pane.maxX - 1f
                previous.color.a = if (previous.isDisabled) .4f else 1f
                next.color.a = if (next.isDisabled) .4f else 1f
            }
        }
    }

    fun renderPalette(lines: Iterable<FormattedLine>, @Suppress("UNUSED_PARAMETER") iconDisplay: FormattedLine.IconDisplay,
                      action: (String)->Unit): Table = Table().apply {
        defaults().pad(4*u)
        for (line in lines) {
            if (line.separator) { add().width(12*u); continue }
            add(brushCard(line).apply { onActivation { action(line.link) } }).height(80*u)
        }
    }

    internal fun registerCard(card: Table, caption: String) { cards.add(card to caption) }

    fun brushCard(line: FormattedLine): Table = Table().apply {
        background = MapEditorStyle.background(MapEditorStyle.raised)
        val rendered = line.copy(text = "", link = "", size = (30*u).toInt()).render(0f)
        add(rendered).height(34*u).row()
        add(line.text.toLabel(fontSize = (14*u).toInt(), fontColor = MapEditorStyle.text, hideIcons = true).apply {
            wrap = true; setAlignment(com.badlogic.gdx.utils.Align.center)
        }).width(104*u).height(38*u)
        pad(4*u)
        cards.add(this to line.text)
        name = "map-brush-${line.link.ifEmpty { line.text }}"
    }

    class PaletteState(val category: Int, val brush: String, val size: Int, val collapsed: Boolean) {
        // RoboVM does not provide the static Boolean.hashCode(boolean) method.
        override fun hashCode(): Int {
            var result = category
            result = 31 * result + brush.hashCode()
            result = 31 * result + size
            return 31 * result + if (collapsed) 1231 else 1237
        }
    }
    fun saveState() = PaletteState(selectedCategory, brushName, brushSize, collapsed)
    fun restoreState(state: PaletteState) {
        selectCategory(state.category)
        cards.firstOrNull { (_, caption) -> caption == state.brush || caption == "[${state.brush}] starting location" ||
            (state.brush == "Remove" && caption == "Remove starting locations") }?.first?.activate()
        brushSize = state.size
        collapsed = state.collapsed
        collapseButton.setText((if (collapsed) "Expand" else "Collapse").tr())
        layoutPalette()
    }
    fun clearRiverSelection() { riverStartTile = null; riverEndTile = null }
    internal fun screen() = editorScreen

    fun openBrushHelp() { if (pediaLink.isNotEmpty()) editorScreen.openCivilopedia(pediaLink) }

    fun setBrush(name: String, icon: String, handlerType: BrushHandlerType = BrushHandlerType.Tile,
                 pediaLink: String = icon, @Suppress("UNUSED_PARAMETER") isRemove: Boolean = false,
                 applyAction: (Tile)->Unit) {
        brushHandlerType = handlerType; brushAction = applyAction
        brushName = name; this.pediaLink = pediaLink
        cards.forEach { (card, caption) ->
            card.background = if (caption == name || caption == "[$name] starting location")
                MapEditorStyle.outlined() else MapEditorStyle.background(MapEditorStyle.raised)
        }
    }
    fun setBrush(handlerType: BrushHandlerType, name: String, @Suppress("UNUSED_PARAMETER") icon: Actor,
                 pediaLink: String, applyAction: (Tile)->Unit) = setBrush(name, "", handlerType, pediaLink, applyAction = applyAction)

    override fun activated(index: Int, caption: String, pager: TabbedPager) {
        if (editorScreen.editTabsNeedRefresh || pages.isEmpty()) {
            ruleset = editorScreen.ruleset
            ImageGetter.setNewRuleset(ruleset)
            pages.clear(); cards.clear()
            factories.forEach { pages.add(it(this, ruleset).apply { left() }) }
            categoryButtons.forEachIndexed { i, b -> b.isDisabled = (pages[i] as IMapEditorEditSubTabs).isDisabled() }
            selectCategory(selectedCategory)
            val terrain = ruleset.terrains.values.first { it.type.isBaseTerrain }
            setBrush(terrain.name, "Terrain/${terrain.name}") { tile -> tile.baseTerrain = terrain.name; tile.naturalWonder = null }
            editorScreen.editTabsNeedRefresh = false
        }
        editorScreen.tileClickHandler = { tile -> editorScreen.performEdit { tileClickHandler(tile) } }
        tileMatchFuzziness = editorScreen.tileMatchFuzziness
    }
    override fun deactivated(index: Int, caption: String, pager: TabbedPager) {
        pager.setScrollDisabled(false)
        riverStartTile = null; riverEndTile = null
        editorScreen.tileClickHandler = null
    }
    private fun tileClickHandler(tile: Tile) {
        if (brushSize < -1 || brushSize > 5 || brushHandlerType == BrushHandlerType.None) return
        if (editorScreen.mapHolder.isPanning || editorScreen.mapHolder.isZooming()) return
        editorScreen.hideSelection()

        when (brushHandlerType) {
            BrushHandlerType.None -> Unit
            BrushHandlerType.RiverFromTo ->
                selectRiverFromOrTo(tile)
            else ->
                paintTilesWithBrush(tile)
        }
    }

    private fun selectRiverFromOrTo(tile: Tile) {
        val tilesToHighlight = mutableSetOf(tile)
        if (tile.isLand) {
            // Land means river from. Start the river if we have a 'to', choose a 'to' if not.
            riverStartTile = tile
            if (riverEndTile != null) return paintRiverFromTo()
            val riverGenerator = RiverGenerator(editorScreen.tileMap, randomness, ruleset)
            riverEndTile = riverGenerator.getClosestWaterTile(tile)
            if (riverEndTile != null) tilesToHighlight += riverEndTile!!
        } else {
            // Water means river to. Start the river if we have a 'from'
            riverEndTile = tile
            if (riverStartTile != null) return paintRiverFromTo()
        }
        for (tileToHighlight in tilesToHighlight) editorScreen.highlightTile(tileToHighlight, Color.BLUE)
    }
    private fun paintRiverFromTo() {
        val resultingTiles = mutableSetOf<Tile>()
        randomness.seedRNG(editorScreen.newMapParameters.seed)
        try {
            val riverGenerator = RiverGenerator(editorScreen.tileMap, randomness, ruleset)
            riverGenerator.spawnRiver(riverStartTile!!, riverEndTile!!, resultingTiles)
            MapGenerator(ruleset).convertTerrains(resultingTiles)
        } catch (ex: Exception) {
            Log.error("Exception while generating rivers", ex)
            ToastPopup("River generation failed!", editorScreen)
        }
        riverStartTile = null
        riverEndTile = null
        editorScreen.isDirty = true
        for (tile in resultingTiles) editorScreen.updateAndHighlight(tile, Color.SKY)
    }

    internal fun paintTilesWithBrush(tile: Tile) {
        val tiles =
            if (brushSize == -1) {
                val bfs = BFS(tile) { it.isSimilarEnough(tile) }
                bfs.stepToEnd()
                bfs.getReachedTiles().toSet()
            } else {
                tile.getTilesInDistance(brushSize - 1).toSet()
            }
        
        for (tileToPaint in tiles) {
            if (strokeTiles?.add(tileToPaint.position) == false) continue
            when (brushHandlerType) {
                BrushHandlerType.Direct -> directPaintTile(tileToPaint)
                BrushHandlerType.River -> directPaintTile(tileToPaint)
                BrushHandlerType.Tile -> paintTile(tileToPaint)
                BrushHandlerType.Road -> paintTile(tileToPaint)
                else -> {} // other cases can't reach here
            }
        }
        
        // Adjacent tiles could have images changed as well, due to rivers/edge tiles/roads
        val tilesToUpdate = tiles.flatMap { it.neighbors + it }.toSet()
        for (tileToUpdate in tilesToUpdate) editorScreen.updateTile(tileToUpdate)
    }

    /** Used for starting locations - no temp tile as brushAction needs to access tile.tileMap */
    private fun directPaintTile(tile: Tile) {
        brushAction(tile)
        editorScreen.isDirty = true
        editorScreen.highlightTile(tile)
    }

    /** apply brush to a single tile */
    private fun paintTile(tile: Tile): Boolean {
        // Approach is "Try - matches - leave or revert" because an off-map simulation would fail some tile filters
        val savedTile = tile.clone()
        val paintedTile = tile.clone()
        brushAction(paintedTile)
        paintedTile.ruleset = ruleset
        try {
            paintedTile.setTerrainTransients()
        } catch (ex: Exception) {
            val message = ex.message ?: throw ex
            if (!message.endsWith("not exist in this ruleset!")) throw ex
            ToastPopup(message, editorScreen)
        }

        brushAction(tile)
        tile.setTerrainTransients()
        TileNormalizer.normalizeToRuleset(tile, ruleset)
        if (!paintedTile.isSimilarEnough(tile)) {
            // revert tile to original state
            tile.applyFrom(savedTile)
            return false
        }

        if (tile.naturalWonder != savedTile.naturalWonder)
            editorScreen.naturalWondersNeedRefresh = true
        editorScreen.isDirty = true
        editorScreen.highlightTile(tile)
        return true
    }

    private fun Tile.isSimilarEnough(other: Tile) = when {
        tileMatchFuzziness <= TileMatchFuzziness.CompleteMatch &&
                improvement != other.improvement ||
                roadStatus != other.roadStatus -> false
        tileMatchFuzziness <= TileMatchFuzziness.NoImprovement &&
                resource != other.resource -> false
        tileMatchFuzziness <= TileMatchFuzziness.BaseAndFeatures &&
                terrainFeatures.toSet() != other.terrainFeatures.toSet() -> false
        tileMatchFuzziness <= TileMatchFuzziness.BaseTerrain &&
                baseTerrain != other.baseTerrain -> false
        tileMatchFuzziness <= TileMatchFuzziness.LandOrWater &&
                isLand != other.isLand -> false
        else -> naturalWonder == other.naturalWonder
    }

    private fun Tile.applyFrom(other: Tile) {
        // 90% copy w/o position, improvement times or transients. Add units once Unit paint is in.
        baseTerrain = other.baseTerrain
        setTerrainFeatures(other.terrainFeatures)
        tileResource = other.tileResource
        setImprovementBasic(other.tileImprovement)
        naturalWonder = other.naturalWonder
        roadStatus = other.roadStatus
        hasBottomLeftRiver = other.hasBottomLeftRiver
        hasBottomRightRiver = other.hasBottomRightRiver
        hasBottomRiver = other.hasBottomRiver
        setTerrainTransients()
    }

    companion object {
        private fun getBrushTip(value: Float, abbreviate: Boolean = false) = when {
            value <= 5f -> value.toInt().tr()
            abbreviate -> "Floodfill_Abbreviation"
            else -> "Floodfill"
        }
    }
}
