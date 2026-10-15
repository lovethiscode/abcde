package com.unciv.ui.screens.worldscreen.bottombar

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.utils.Align
import com.unciv.ui.objectdescriptions.TileDescription
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.addBorderAllowOpacity
import com.unciv.ui.components.extensions.darken
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.input.onClick
import com.unciv.ui.popups.Popup
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.civilopediascreen.FormattedLine.IconDisplay
import com.unciv.ui.screens.civilopediascreen.MarkupRenderer
import com.unciv.ui.screens.worldscreen.WorldScreen
import com.unciv.ui.screens.worldscreen.BattlefieldHud
import com.unciv.ui.components.widgets.UiNavigation
import com.unciv.utils.DebugUtils
import com.unciv.view.CivView
import com.unciv.view.TileView

class TileInfoTable(private val worldScreen: WorldScreen) : Table(BaseScreen.skin) {
    var civView: CivView = worldScreen.selectedGameView.civView
    private var battlefieldWidth = 0f
    private var lastSelectedTile: TileView? = null
    internal val hasBattlefieldContent get() = cells.size > 0

    internal fun fitBattlefield(targetWidth: Float) {
        if (battlefieldWidth == targetWidth) return
        battlefieldWidth = targetWidth
        updateTileTable(lastSelectedTile)
    }

    init {
        background = BaseScreen.skinStrings.getUiBackground(
            "WorldScreen/TileInfoTable",
            tintColor = BaseScreen.skinStrings.skinConfig.baseColor.darken(0.5f)
        )
    }

    internal fun updateTileTable(selectedTileView: TileView?) {
        lastSelectedTile = selectedTileView
        clearChildren()
        pad(5f)

        // A retained selection may still carry the previous spectator or civilization perspective.
        val tileView = selectedTileView?.let { civView.gameView.getTile(it) }
        if (tileView != null && (DebugUtils.VISIBLE_MAP || civView.hasExplored(tileView))) {
            val native = BattlefieldHud.enabled && battlefieldWidth > 0f
            val contentWidth = if (native) battlefieldWidth - BattlefieldHud.points(10f) else 0f
            val stats = add(getStatsTable(tileView)).left()
            if (native) stats.minHeight(UiNavigation.minimumTouchSize())
            row()
            add(MarkupRenderer.render(TileDescription.toMarkup(tileView), labelWidth = contentWidth,
                padding = 0f, iconDisplay = IconDisplay.None,
                minimumLinkHeight = if (native) UiNavigation.minimumTouchSize() else 0f,
                fitToWidth = native) {
                worldScreen.openCivilopedia(it)
            } ).padTop(5f).left().row()
            if (DebugUtils.VISIBLE_MAP)
                add(tileView.position().toPrettyString()).colspan(2).pad(5f) // No toLabel() to avoid tr(), eats comma
            if (DebugUtils.SHOW_TILE_IMAGE_LOCATIONS){
                val imagesString = "Images: " + worldScreen.mapHolder.tileGroups[tileView]!!.layerTerrain.tileBaseImages.joinToString{"\n"+it.name}
                add(imagesString.toLabel())
            }
        }

        pack()
        if (!BattlefieldHud.enabled) addBorderAllowOpacity(1f, Color.WHITE)
    }

    private fun getStatsTable(tileView: TileView): Table {
        val table = Table()
        table.defaults().pad(2f)
        
        for ((key, value) in tileView.getTileStats(civView)) {
            table.add((key.character + value.toInt().toString()).toLabel())
                .align(Align.left).padRight(5f)
        }
        table.touchable = Touchable.enabled
        table.onClick {
            Popup(worldScreen).apply {
                for ((name, stats) in tileView.getTileStatsBreakdown(civView))
                    add("${name.tr()}: {${stats.clone()}}".toLabel()).row()
                addCloseButton()
            }.open()
        }
        return table
    }

    override fun draw(batch: Batch?, parentAlpha: Float) = super.draw(batch, parentAlpha)
}
