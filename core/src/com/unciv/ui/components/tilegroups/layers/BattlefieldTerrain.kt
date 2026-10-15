package com.unciv.ui.components.tilegroups.layers

import com.unciv.models.skins.ExpeditionBlueTheme
import com.unciv.models.tilesets.TileSetCache
import com.unciv.ui.components.tilegroups.TileGroup
import com.unciv.ui.components.tilegroups.WorldTileGroup
import com.unciv.ui.images.ImageGetter

/** Keep gameplay, rivers, improvements and fog in the existing layers; replace only the default world's terrain art. */
internal object BattlefieldTerrain {
    private val floors = setOf("Grassland", "Plains", "Desert", "Tundra", "Snow", "Coast", "Ocean", "Mountain")
    private val forests = setOf("ForestG", "ForestP", "ForestT", "ForestGH", "ForestPH", "ForestTH")

    fun enabled(group: TileGroup) = ExpeditionBlueTheme.enabled && group is WorldTileGroup &&
        group.tileSetStrings.tileSetName == "HexaRealm" && ImageGetter.imageExists("Battlefield/Grassland") &&
        !ImageGetter.hasModTerrainImages("HexaRealm") && TileSetCache["HexaRealm"]?.hasActiveModConfig() != true

    fun locations(locations: List<String>): List<String> = locations.flatMap { location ->
        val name = location.substringAfterLast('/')
        when {
            name in floors -> listOf("Battlefield/$name")
            name.endsWith("+Hill") && name.removeSuffix("+Hill") in floors ->
                listOf("Battlefield/${name.removeSuffix("+Hill")}", "Battlefield/Hill")
            name in forests -> listOf("Battlefield/Forest")
            else -> listOf(location)
        }
    }
}
