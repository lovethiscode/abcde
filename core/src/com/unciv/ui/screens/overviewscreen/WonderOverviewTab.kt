package com.unciv.ui.screens.overviewscreen

import com.unciv.ui.screens.overviewscreen.OverviewUi.pt
import com.unciv.ui.screens.overviewscreen.OverviewUi.font
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.unciv.UncivGame
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.tile.Tile
import com.unciv.models.ruleset.Building
import com.unciv.models.ruleset.QuestName
import com.unciv.models.ruleset.tech.Era
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.input.onClick
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.screens.civilopediascreen.CivilopediaCategories
import com.unciv.utils.DebugUtils
import com.unciv.view.CivView

class WonderOverviewTab(
    viewingPlayer: CivView,
    overviewScreen: EmpireOverviewScreen
) : EmpireOverviewTab(viewingPlayer, overviewScreen) {
    private val wonderInfo = WonderInfo()
    private val wonders: Array<WonderInfo.WonderInfo> = wonderInfo.collectInfo(viewingPlayer.getCiv())

    private val fixedContent = Table()
    override fun getFixedContent() = fixedContent

    init {
        fixedContent.add().height(pt(8f))
        top()
        for (wonder in wonders) {
            if (wonder.status == WonderInfo.WonderStatus.Hidden) continue
            val entry = Table().apply {
                background = OverviewUi.surface(OverviewUi.panel)
                pad(pt(10f), pt(16f), pt(10f), pt(16f))
                val image = wonder.getImage()
                image?.onClick { overviewScreen.openCivilopedia(wonder.makeLink()) }
                add(image).size(pt(28f)).padRight(pt(12f))
                val identity = Table().apply {
                    left()
                    add(wonder.getNameColumn().toLabel(fontSize = font(16), hideIcons = true)).left().row()
                    val location = wonder.getLocationColumn()
                    val context = if (location.isEmpty()) wonder.groupName else "{${wonder.groupName}} · {$location}"
                    add(context.toLabel(fontColor = OverviewUi.muted, fontSize = font(13), hideIcons = true)).left().padTop(6f)
                }
                if (wonder.location != null) identity.onClick {
                    UncivGame.Current.resetToWorldScreen().mapHolder.setCenterPosition(wonder.location.position)
                }
                add(identity).minWidth(0f).growX().left()
                add(wonder.getStatusColumn().toLabel(fontSize = font(14))).padLeft(16f).right()
            }
            add(entry).width(overviewScreen.contentWidth).minHeight(pt(66f)).padBottom(pt(1f)).row()
        }
    }

}


class WonderInfo {

    enum class WonderStatus(val label: String) {
        Hidden(""),
        Unknown("Unknown"),
        Unbuilt("Not built"),
        NotFound("Not found"),
        Known("Known"),
        Owned("Owned")
    }

    class WonderInfo (
        val name: String,
        val category: CivilopediaCategories,
        val groupName: String,
        val groupColor: Color,
        val status: WonderStatus,
        val civ: Civilization?,
        val city: City?,
        val location: Tile?
    ) {
        private val viewEntireMapForDebug = DebugUtils.VISIBLE_MAP

        fun getImage(size: Float = if (category == CivilopediaCategories.Terrain) 50f else 45f) =
            if (status == WonderStatus.Unknown && !viewEntireMapForDebug) null
            else category.getImage?.invoke(name, size)

        fun getNameColumn() = when {
            viewEntireMapForDebug -> name
            status == WonderStatus.Unknown -> status.label
            else -> name
        }

        fun getStatusColumn() = when {
            status != WonderStatus.Known -> status.label
            civ == null -> status.label
            else -> civ.civID
        }

        fun getLocationColumn() = when {
            status <= WonderStatus.NotFound -> ""
            location == null -> ""
            location.isCityCenter() -> location.getCity()!!.name
            location.getCity() != null -> "Near [${location.getCity()!!}]"
            city != null -> "Somewhere around [$city]"
            viewEntireMapForDebug -> location.position.toPrettyString()
            else -> "Far away"
        }

        fun makeLink() = category.name + "/" + name
    }

    private fun shouldBeDisplayed(viewingPlayer: Civilization, wonder: Building, wonderEra: Int?) =
        !wonder.isHiddenFromCivilopedia(viewingPlayer.gameInfo) &&
        (wonderEra == null || wonderEra <= viewingPlayer.getEraNumber())

    /** Do we know about a natural wonder despite not having found it yet? */
    private fun knownFromQuest(viewingPlayer: Civilization, name: String): Boolean {
        // No, *your* civInfo's QuestManager has no idea about your quests
        for (civ in viewingPlayer.gameInfo.civilizations) {
            for (quest in civ.questManager.getAssignedQuestsFor(viewingPlayer)) {
                if (quest.questName == QuestName.FindNaturalWonder.value && quest.data1 == name)
                    return true
            }
        }
        return false
    }

    fun collectInfo(viewingPlayer: Civilization): Array<WonderInfo> {
        val collator = UncivGame.Current.settings.getCollatorFromLocale()
        val ruleset = viewingPlayer.gameInfo.ruleset

        // Maps all World Wonders by name to their era for grouping
        val wonderEraMap: Map<String, Era?> =
            ruleset.buildings.values.asSequence()
                    .filter { it.isWonder }
                    .associate { it.name to it.era(ruleset) }

        // Maps all World Wonders by their position in sort order to their name
        val allWonderMap: Map<Int, String> =
            ruleset.buildings.values.asSequence()
                    .filter { it.isWonder }
                        // 100 is so wonders with no era get displayed after all eras, not before
                    .sortedWith(compareBy<Building> { wonderEraMap[it.name]?.eraNumber ?: 100 }.thenBy(collator) { it.name.tr(hideIcons = true) })
                    .withIndex()
                    .associate { it.index to it.value.name }
        val wonderCount = allWonderMap.size

        // Inverse of the above
        val wonderIndexMap: Map<String, Int> = allWonderMap.map { it.value to it.key }.toMap()

        // Maps all Natural Wonders on the map by name to their tile
        val allNaturalsMap: Map<String, Tile> =
                viewingPlayer.gameInfo.tileMap.values.asSequence()
                    .filter { it.isNaturalWonder() }
                    .associateBy { it.naturalWonder!! }
        val naturalsCount = allNaturalsMap.size

        // Natural Wonders sort order index to name
        val naturalsIndexMap: Map<Int, String> = allNaturalsMap.keys
            .sortedWith(compareBy(collator) { it.tr() })
            .withIndex()
            .associate { it.index to it.value }

        // Pre-populate result with "Unknown" entries
        val wonders = Array(wonderCount + naturalsCount) { index ->
            if (index < wonderCount) {
                val wonder = ruleset.buildings[allWonderMap[index]!!]!!
                val era = wonderEraMap[wonder.name]
                val status = if (shouldBeDisplayed(viewingPlayer, wonder, era?.eraNumber)) WonderStatus.Unbuilt else WonderStatus.Hidden
                WonderInfo(
                    allWonderMap[index]!!, CivilopediaCategories.Wonder,
                    era?.name ?: "Other", era?.getColor() ?: Color.WHITE, status, null, null, null
                )
            } else {
                WonderInfo(
                    naturalsIndexMap[index - wonderCount]!!,
                    CivilopediaCategories.Terrain,
                    "Natural Wonders",
                    Color.FOREST,
                    WonderStatus.Unknown,
                    null,
                    null,
                    null
                )
            }
        }

        for (city in viewingPlayer.gameInfo.getCities()) {
            for (wonderName in city.cityConstructions.getBuiltBuildings().map { it.name }.toList().intersect(wonderIndexMap.keys)) {
                val index = wonderIndexMap[wonderName]!!
                val status = when {
                    viewingPlayer == city.civ -> WonderStatus.Owned
                    viewingPlayer.hasExplored(city.getCenterTile()) -> WonderStatus.Known
                    else -> WonderStatus.NotFound
                }
                wonders[index] = WonderInfo(
                    wonderName, CivilopediaCategories.Wonder,
                    wonders[index].groupName, wonders[index].groupColor,
                    status, city.civ, city, city.getCenterTile()
                )
            }
        }

        for ((index, name) in naturalsIndexMap) {
            val tile = allNaturalsMap[name]!!
            val civ = tile.getOwner()
            val status = when {
                civ == viewingPlayer -> WonderStatus.Owned
                name in viewingPlayer.naturalWonders -> WonderStatus.Known
                else -> WonderStatus.NotFound
            }
            if (status == WonderStatus.NotFound && !knownFromQuest(viewingPlayer, name)) continue
            val city = if (status == WonderStatus.NotFound) null
            else viewingPlayer.gameInfo.getCities()
                .filter { it.getCenterTile().aerialDistanceTo(tile) <= 5
                    && viewingPlayer.knows(it.civ) 
                    && viewingPlayer.hasExplored(it.getCenterTile()) }
                .sortedBy { it.getCenterTile().aerialDistanceTo(tile) }
                .firstOrNull()
            wonders[index + wonderCount] = WonderInfo(
                name, CivilopediaCategories.Terrain,
                "Natural Wonders", Color.FOREST, status, civ, city, tile
            )
        }

        return wonders
    }
}
