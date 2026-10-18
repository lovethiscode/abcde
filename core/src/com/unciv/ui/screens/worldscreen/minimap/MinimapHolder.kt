package com.unciv.ui.screens.worldscreen.minimap

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.math.Vector2
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.Image
import com.badlogic.gdx.scenes.scene2d.ui.Stack
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.utils.DragListener
import com.badlogic.gdx.utils.Align
import com.unciv.GUI
import com.unciv.UncivGame
import com.unciv.logic.civilization.Civilization
import com.unciv.models.skins.ExpeditionBlueTheme
import com.unciv.ui.components.InputDisabling
import com.unciv.ui.components.extensions.addInTable
import com.unciv.ui.components.extensions.surroundWithCircle
import com.unciv.ui.components.input.onActivation
import com.unciv.ui.components.input.onClick
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.worldscreen.BattlefieldHud
import com.unciv.ui.components.widgets.UiNavigation
import com.unciv.ui.screens.worldscreen.worldmap.WorldMapHolder

class MinimapHolder(val mapHolder: WorldMapHolder) : Table() {
    private val worldScreen = mapHolder.worldScreen
    private var minimapSize = Int.MIN_VALUE
    private var maximized = false
    private var lastCutoutSetting = false
    private var battlefieldMaxWidth = Float.MAX_VALUE
    private var battlefieldMaxHeight = Float.MAX_VALUE
    private var battlefieldExpandedWidth = Float.MAX_VALUE
    private var battlefieldExpandedHeight = Float.MAX_VALUE
    internal val battlefieldExpanded get() = BattlefieldHud.enabled && maximized
    lateinit var minimap: Minimap

    /** Button, next to the minimap, to toggle the unit movement map overlay. */
    val movementsImageButton = MapOverlayToggleButton(
        "TileIcons/MapOverlayToggleMovement",
        getter = { UncivGame.Current.settings.showUnitMovements },
        setter = { UncivGame.Current.settings.showUnitMovements = it }
    )
    /** Button, next to the minimap, to toggle the tile yield map overlay. */
    val yieldImageButton = MapOverlayToggleButton(
        "TileIcons/MapOverlayToggleYields",
        // This is a use in the UI that has little to do with the stat… These buttons have more in common with each other than they do with other uses of getStatIcon().
        getter = { UncivGame.Current.settings.showTileYields },
        setter = { UncivGame.Current.settings.showTileYields = it }
    )
    /** Button, next to the minimap, to toggle the worked tiles map overlay. */
    val populationImageButton = MapOverlayToggleButton(
        "TileIcons/MapOverlayToggleWorkedTiles",
        getter = { UncivGame.Current.settings.showWorkedTiles },
        setter = { UncivGame.Current.settings.showWorkedTiles = it }
    )
    /** Button, next to the minimap, to toggle the resource icons map overlay. */
    val resourceImageButton = MapOverlayToggleButton(
        "TileIcons/MapOverlayToggleResources",
        getter = { UncivGame.Current.settings.showResourcesAndImprovements },
        setter = { UncivGame.Current.settings.showResourcesAndImprovements = it }
    )
    /** Button, next to the minimap, to toggle the pixel improvements map overlay. */
    val improvementsImageButton = MapOverlayToggleButton(
        "TileIcons/MapOverlayToggleImprovements",
        getter = { UncivGame.Current.settings.showPixelImprovements },
        setter = { UncivGame.Current.settings.showPixelImprovements = it }
    )
    val buttons = listOf(
        movementsImageButton,
        yieldImageButton,
        populationImageButton,
        resourceImageButton,
        improvementsImageButton
    )

    private fun rebuildIfSizeChanged(civInfo: Civilization) {
        // For Spectator should not restrict minimap
        val civ: Civilization? = civInfo.takeUnless { GUI.getWorldScreen().selectedGameView.spectatorMode }
        val newMinimapSize = if (battlefieldExpanded && ::minimap.isInitialized)
            minimap.getClosestMinimapSize(Vector2(battlefieldExpandedWidth, battlefieldExpandedHeight), touchInside = true)
        else worldScreen.game.settings.minimapSize
        val cutoutSetting = worldScreen.game.settings.androidCutout
        if (newMinimapSize == minimapSize && civ?.exploredRegion?.shouldUpdateMinimap() != true && cutoutSetting == lastCutoutSetting) return
        lastCutoutSetting = cutoutSetting
        minimapSize = newMinimapSize
        if (!BattlefieldHud.enabled) maximized = false
        rebuild(civ)
    }

    private fun rebuild(civInfo: Civilization?) {
        this.clear()
        minimap = Minimap(mapHolder, minimapSize, civInfo)
        if (BattlefieldHud.enabled && !maximized &&
            (minimap.width > battlefieldMaxWidth || minimap.height > battlefieldMaxHeight)) {
            val size = minimap.getClosestMinimapSize(Vector2(battlefieldMaxWidth, battlefieldMaxHeight), touchInside = true)
            minimap = Minimap(mapHolder, size, civInfo)
        }
        val wrappedMinimap = getWrappedMinimap()
        if (!BattlefieldHud.enabled) add(getToggleIcons(wrappedMinimap.height)).bottom().padRight(5f)

        val stack = Stack()
        stack.add(wrappedMinimap)
        stack.addInTable(getCornerHandleIcon()).size(20f).pad(8f).top().left()
        val alignment = if (worldScreen.game.settings.androidCutout) Align.topRight else Align.bottomRight
        stack.addInTable(getMaximizeToggleButton(civInfo, alignment))
            .size(if (BattlefieldHud.enabled) UiNavigation.minimumTouchSize() else 40f).align(alignment)
        add(stack).bottom()

        pack()
        if (stage != null) {
            x = stage.width - width
        }

        addListener(ResizeDragListener(civInfo))
    }

    private fun rebuildAndUpdateMap(civInfo: Civilization?) {
        InputDisabling.withInputDisabled {
            rebuild(civInfo) // re-create views
            civInfo?.let { minimap.update(it) } // update map
            minimap.mapHolder.onViewportChanged() // update scroll position
        }
    }

    private fun getMaximizeToggleButton(civInfo: Civilization?, alignment: Int): Actor {

        // when maximized, collapse map when a location was clicked
        if (maximized) {
            minimap.onClick { maximized = false }
        }

        val toggle = fun() {
            maximized = !maximized
            if (battlefieldExpanded) worldScreen.battleTable.isVisible = false
            minimapSize = if (maximized && stage!=null) {
                val available = if (BattlefieldHud.enabled) Vector2(battlefieldExpandedWidth, battlefieldExpandedHeight)
                    else Vector2(stage.width, stage.height)
                minimap.getClosestMinimapSize(available, touchInside = true)
            } else {
                worldScreen.game.settings.minimapSize
            }
            rebuildAndUpdateMap(civInfo)
            if (BattlefieldHud.enabled) worldScreen.layoutBattlefieldHud()
        }

        val table = Table()
        if (shouldShowMapButtons()) {
            // table provides larger click area. we want the resize icon to be small to not cover the map
            val name = if (maximized) "Reduce" else "Increase"
            val image = ImageGetter.getImage("OtherIcons/$name").let {
                if (ExpeditionBlueTheme.enabled && !maximized)
                    it.surroundWithCircle(20f, color = ExpeditionBlueTheme.background)
                else it
            }
            table.add(image).expand().size(20f).pad(8f).align(alignment)
            table.touchable = Touchable.enabled
            table.onActivation(toggle)
        } else {
            // map is really small: use whole MinimapHolder as click area to maximize map
            table.isVisible = false
            minimap.touchable = Touchable.disabled
            touchable = Touchable.enabled
            onActivation(toggle)
        }
        return table
    }

    internal fun toggleBattlefield() {
        maximized = !maximized
        if (maximized) worldScreen.battleTable.isVisible = false
        minimapSize = if (maximized)
            minimap.getClosestMinimapSize(Vector2(battlefieldExpandedWidth, battlefieldExpandedHeight), touchInside = true)
        else worldScreen.game.settings.minimapSize
        rebuildAndUpdateMap(worldScreen.getGameViewConsideringForOfWar().civView.getCiv())
        worldScreen.shouldUpdate = true
    }

    private fun getCornerHandleIcon(): Image {
        return ImageGetter.getImage("OtherIcons/Corner").apply {
            touchable = Touchable.disabled
            isVisible = !maximized && shouldShowMapButtons()
        }
    }

    private fun shouldShowMapButtons() = minimapSize > 0 &&
        (minimap.width > 100f || minimap.height > 100f)

    private fun getWrappedMinimap(): Table {
        val internalMinimapWrapper = Table()
        internalMinimapWrapper.add(minimap)

        internalMinimapWrapper.background = BaseScreen.skinStrings.getUiBackground(
            "WorldScreen/Minimap/Background",
            tintColor = Color.GRAY
        )
        internalMinimapWrapper.pack()

        val externalMinimapWrapper = Table()
        externalMinimapWrapper.add(internalMinimapWrapper).pad(5f)
        externalMinimapWrapper.background = BaseScreen.skinStrings.getUiBackground(
            "WorldScreen/Minimap/Border",
            tintColor = Color.WHITE
        )
        externalMinimapWrapper.pack()

        return externalMinimapWrapper
    }

    /** @return Layout table for the little green map overlay toggle buttons, show to the left of the minimap. */
    private fun getToggleIcons(minimapHeight: Float): Table {
        val toggleIconTable = Table()

        val availableForPadding = minimapHeight - buttons.sumOf { it.height.toDouble() }.toFloat()
        val paddingBetweenElements = (availableForPadding/3).coerceIn(0f, 5f)

        toggleIconTable.defaults().padTop(paddingBetweenElements)

        for (button in buttons) {
            toggleIconTable.add(button).row()
        }

        return toggleIconTable
    }

    fun update(civInfo: Civilization) {
        rebuildIfSizeChanged(civInfo)
        isVisible = if (BattlefieldHud.enabled) true else UncivGame.Current.settings.showMinimap
        if (isVisible) {
            minimap.update(civInfo)
            for (button in buttons) button.update()
        }
    }

    internal fun fitBattlefield(maxWidth: Float, maxHeight: Float, expandedWidth: Float, expandedHeight: Float) {
        if (battlefieldMaxWidth == maxWidth && battlefieldMaxHeight == maxHeight &&
            battlefieldExpandedWidth == expandedWidth && battlefieldExpandedHeight == expandedHeight) return
        battlefieldMaxWidth = maxWidth
        battlefieldMaxHeight = maxHeight
        battlefieldExpandedWidth = expandedWidth
        battlefieldExpandedHeight = expandedHeight
        minimapSize = Int.MIN_VALUE
    }

    // For debugging purposes
    override fun draw(batch: Batch?, parentAlpha: Float) = super.draw(batch, parentAlpha)
    override fun hit(x: Float, y: Float, touchable: Boolean): Actor? = super.hit(x, y, touchable)
    override fun act(delta: Float){} // No actions

    inner class ResizeDragListener(val civInfo: Civilization?): DragListener() {
        private var dragged = false
        override fun touchDragged(event: InputEvent, x: Float, y: Float, pointer: Int) {
            super.touchDragged(event, x, y, pointer)
            if (!isDragging || maximized)
                return
            dragged = true
            val targetSize = Vector2(stage.width - event.stageX, event.stageY)
            val newMinimapSize = minimap.getClosestMinimapSize(targetSize) 
            if (newMinimapSize == minimapSize) return
            
            minimapSize = newMinimapSize
            rebuildAndUpdateMap(civInfo)
        }
        override fun touchUp(event: InputEvent, x: Float, y: Float, pointer: Int, button: Int) {
            super.touchUp(event, x, y, pointer, button)
            if (dragged) {
                worldScreen.game.settings.minimapSize = minimapSize
                GUI.setUpdateWorldOnNextRender() // full update
            }
        }
    }
}
