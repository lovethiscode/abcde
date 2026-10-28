package com.unciv.ui.screens.worldscreen.bottombar

import com.unciv.ui.components.fonts.TextRole
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.unciv.models.skins.ExpeditionBlueTheme
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
import kotlin.math.roundToInt

class TileInfoTable(private val worldScreen: WorldScreen) : Table(BaseScreen.skin) {
    var civView: CivView = worldScreen.selectedGameView.civView
    private var battlefieldWidth = 0f
    private var lastSelectedTile: TileView? = null
    internal val battlefieldHeader = Table(BaseScreen.skin).apply {
        // A static heading must still shield the map from taps through its background.
        touchable = Touchable.enabled
    }
    internal val hasBattlefieldContent get() = cells.size > 0 || battlefieldHeader.cells.size > 0

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
        battlefieldHeader.background = background
    }

    internal fun updateTileTable(selectedTileView: TileView?) {
        lastSelectedTile = selectedTileView
        clearChildren()
        battlefieldHeader.clearChildren()
        top().left()
        pad(5f)

        // A retained selection may still carry the previous spectator or civilization perspective.
        val tileView = selectedTileView?.let { civView.gameView.getTile(it) }
        if (tileView != null && (DebugUtils.VISIBLE_MAP || civView.hasExplored(tileView))) {
            val native = BattlefieldHud.enabled && battlefieldWidth > 0f
            if (native) {
                addBattlefieldDetails(tileView)
                pack()
                return
            }
            add(getStatsTable(tileView)).left().row()
            add(MarkupRenderer.render(TileDescription.toMarkup(tileView), padding = 0f,
                iconDisplay = IconDisplay.None) {
                worldScreen.openCivilopedia(it)
            }).padTop(5f).left().row()
            if (DebugUtils.VISIBLE_MAP)
                add(tileView.position().toPrettyString()).colspan(2).pad(5f) // No toLabel() to avoid tr(), eats comma
            if (DebugUtils.SHOW_TILE_IMAGE_LOCATIONS){
                val imagesString = "Images: " + worldScreen.mapHolder.tileGroups[tileView]!!.layerTerrain.tileBaseImages.joinToString{"\n"+it.name}
                add(imagesString.toLabel(TextRole.Body))
            }
        }

        pack()
        if (!BattlefieldHud.enabled) addBorderAllowOpacity(1f, Color.WHITE)
    }

    /** The upstream description is shown in place, with terrain promoted to a fixed heading. */
    private fun addBattlefieldDetails(tileView: TileView) {
        val inset = BattlefieldHud.points(8f)
        val touch = UiNavigation.minimumTouchSize()
        val contentWidth = battlefieldWidth - inset * 2f
        pad(0f, inset, inset, inset)
        val description = TileDescription.toMarkup(tileView)
        val terrain = description.filter { it.link.startsWith("Terrain/") }
        val headingWidth = contentWidth - touch
        val title = terrain.joinToString(" · ") { it.text.tr() }
            .toLabel(fontSize = BattlefieldHud.font(TextRole.Supporting), hideIcons = true).apply {
                color = ExpeditionBlueTheme.text
                touchable = Touchable.disabled
            }
        val titleWidth = title.prefWidth
        val stats = getStatsTable(tileView, compactWidth = headingWidth)
        val statsWidth = stats.prefWidth
        val hasStats = stats.cells.size > 0
        val inlineStats = hasStats && titleWidth + BattlefieldHud.points(6f) + statsWidth <= headingWidth
        title.wrap = true
        battlefieldHeader.apply {
            top().left()
            pad(BattlefieldHud.points(4f), inset, BattlefieldHud.points(4f), inset + touch)
            if (inlineStats) {
                add(title).width(headingWidth - statsWidth - BattlefieldHud.points(6f))
                    .minHeight(BattlefieldHud.points(20f)).left()
                add(stats).width(statsWidth).padLeft(BattlefieldHud.points(6f)).left()
            } else {
                add(title).width(headingWidth).minHeight(BattlefieldHud.points(20f)).left().row()
                if (hasStats) add(stats).width(headingWidth).left().row()
            }
            pack()
        }
        // Keep all upstream fields, including production, units, research requirements,
        // pillaging, construction progress, defence and fresh water. Do not open another UI.
        val details = description.filterNot { it in terrain }.map {
            it.copy(link = "", size = BattlefieldHud.font(TextRole.Supporting), padding = BattlefieldHud.points(2f))
        }
        if (details.isEmpty()) pad(0f) // Basic terrain/yields are already visible in the heading.
        else {
            add(MarkupRenderer.render(details, labelWidth = contentWidth,
                padding = BattlefieldHud.points(2f), iconDisplay = IconDisplay.None,
                fitToWidth = true).apply { touchable = Touchable.disabled })
                .width(contentWidth).left().row()
        }
    }

    private fun getStatsTable(tileView: TileView, compactWidth: Float? = null): Table {
        val table = Table().apply { left() }
        val spacing = if (compactWidth != null) BattlefieldHud.points(6f) else 5f
        table.defaults().pad(if (compactWidth != null) BattlefieldHud.points(2f) else 2f)
        var usedWidth = 0f
        for ((key, value) in tileView.getTileStats(civView)) {
            val label = (key.character + value.roundToInt().toString()).toLabel(
                fontSize = if (compactWidth != null) BattlefieldHud.font(TextRole.Supporting) else 18)
            val itemWidth = label.prefWidth + spacing + BattlefieldHud.points(4f)
            if (compactWidth != null && usedWidth > 0f && usedWidth + itemWidth > compactWidth) {
                table.row()
                usedWidth = 0f
            }
            table.add(label).align(Align.left).padRight(spacing)
            usedWidth += itemWidth
        }
        if (compactWidth != null) {
            table.touchable = Touchable.disabled
            return table
        }
        table.touchable = Touchable.enabled
        table.onClick {
            Popup(worldScreen).apply {
                for ((name, stats) in tileView.getTileStatsBreakdown(civView))
                    add("${name.tr()}: {${stats.clone()}}".toLabel(TextRole.Body)).row()
                addCloseButton()
            }.open()
        }
        return table
    }

    override fun draw(batch: Batch?, parentAlpha: Float) = super.draw(batch, parentAlpha)
}
