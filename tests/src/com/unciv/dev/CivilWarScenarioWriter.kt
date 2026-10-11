package com.unciv.dev

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.backends.headless.HeadlessFiles
import com.unciv.Constants
import com.unciv.logic.campaign.CampaignCatalog
import com.unciv.logic.campaign.CampaignState
import com.unciv.logic.campaign.CivilWarCampaign
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.diplomacy.DiplomaticStatus
import com.unciv.logic.files.FileConversions
import com.unciv.logic.map.HexMath
import com.unciv.logic.map.tile.RoadStatus
import com.unciv.models.metadata.Player
import com.unciv.testing.TestGame

/** Reproducible authoring, never called by game startup or ordinary tests. */
object CivilWarScenarioWriter {
    @JvmStatic fun main(args: Array<String>) {
        Gdx.files = HeadlessFiles()
        try { write() } finally { com.unciv.utils.Concurrency.stopThreadPools() }
    }

    private fun write() {
        val f = TestGame()
        var g = f.gameInfo
        f.ruleset.add(CivilWarCampaign.ruleset(g))
        f.setSpeed("Standard")
        f.makeRectangularMap(32, 40, Constants.grassland)
        fun tile(column: Int, row: Int) = f.tileMap[HexMath.getTileCoordsFromColumnRow(column, row)]
        for (t in f.tileMap.values) {
            val c = t.getColumn(); val r = t.getRow()
            f.setTileTerrain(t.position, Constants.grassland)
            if (c < -14) f.setTileFeatures(t.position, Constants.hill, Constants.forest)
            else if ((c * 3 + r * 7).mod(11) < 3) f.setTileFeatures(t.position, Constants.forest)
            else if ((c * 5 + r).mod(17) == 0) f.setTileFeatures(t.position, Constants.hill)
        }
        val union = f.addCiv(f.ruleset.nations.getValue("Union"), true)
        val confederacy = f.addCiv(f.ruleset.nations.getValue("Confederacy"))
        union.civID = "civil-war-union"
        confederacy.civID = "civil-war-confederacy"
        fun tech(c: Civilization, name: String) {
            f.ruleset.technologies.getValue(name).prerequisites.forEach { tech(c, it) }
            if (!c.tech.isResearched(name)) c.tech.addTechnology(name, false)
        }
        for (c in g.civilizations) for (name in listOf("Rifling", "Military Science", "Chemistry", "Machinery", "Currency")) tech(c, name)
        val specs = listOf(
            Triple("cw-washington", 4 to 9, 10), Triple("cw-baltimore", 10 to 13, 12),
            Triple("cw-alexandria", 3 to 6, 7), Triple("cw-richmond", 2 to -10, 10),
            Triple("cw-petersburg", 3 to -14, 8), Triple("cw-fredericksburg", -1 to -2, 6),
            Triple("cw-manassas", -3 to 3, 5))
        val cities = specs.mapIndexed { i, (id, position, population) ->
            val t = tile(position.first, position.second)
            t.setTerrainFeatures(emptyList())
            val city = f.addCity(if (i < 3) union else confederacy, t, initialPopulation = population)
            city.id = id
            city.name = CivilWarCampaign.cities.getValue(id)
            city.cityConstructions.addBuilding(city.name + " Depot")
            city.cityConstructions.addBuilding("Market")
            if (id in setOf("cw-washington", "cw-richmond", "cw-fredericksburg")) city.cityConstructions.addBuilding("Walls")
            for (nearby in t.getTilesInDistance(2).filter { !it.isCityCenter() }) {
                city.expansion.takeOwnership(nearby)
                f.setTileTerrain(nearby.position, Constants.plains)
                if ((nearby.getColumn() + nearby.getRow()).mod(3) == 0) {
                    nearby.setTerrainFeatures(listOf(Constants.hill)); nearby.setImprovement("Mine")
                } else {
                    nearby.setTerrainFeatures(emptyList()); nearby.setImprovement("Farm")
                }
            }
            city
        }
        // Real luxury and horse deposits are included in ownership and resource calculations.
        for (c in g.civilizations) {
            for ((index, city) in c.cities.withIndex()) {
                val t = city.getCenterTile().neighbors.first { !it.isCityCenter() && it.getOwner() == c }
                t.setTerrainFeatures(listOf(Constants.hill))
                t.setTileResource(f.ruleset.tileResources.getValue(listOf("Gems", "Gold Ore", "Silver", "Marble")[index]))
                t.setImprovement(if (index == 3) "Quarry" else "Mine")
            }
            val t = c.getCapital()!!.getCenterTile().neighbors.first { it.resource == null && !it.isCityCenter() }
            t.setTerrainFeatures(emptyList())
            t.setTileResource(f.ruleset.tileResources.getValue("Horses"))
            t.resourceAmount = 6
            t.setImprovement("Pasture")
        }
        fun road(from: Pair<Int, Int>, to: Pair<Int, Int>, status: RoadStatus, owner: Civilization) {
            var t = tile(from.first, from.second)
            val destination = tile(to.first, to.second)
            while (true) {
                if (!t.isCityCenter()) t.setRoadStatus(status, owner)
                if (t == destination) break
                t = t.neighbors.minBy { it.aerialDistanceTo(destination) }
            }
        }
        road(4 to 9, 10 to 13, RoadStatus.Railroad, union)
        road(4 to 9, 3 to 6, RoadStatus.Road, union)
        road(3 to 6, -3 to 3, RoadStatus.Road, union)
        road(-3 to 3, -1 to -2, RoadStatus.Road, confederacy)
        road(-1 to -2, 2 to -10, RoadStatus.Road, confederacy)
        road(2 to -10, 3 to -14, RoadStatus.Railroad, confederacy)
        road(-10 to 11, -11 to 3, RoadStatus.Road, union)
        road(-11 to 3, -10 to -4, RoadStatus.Road, confederacy)
        road(-10 to -4, -4 to -10, RoadStatus.Road, confederacy)
        road(-10 to 11, 4 to 9, RoadStatus.Road, union)
        tile(-10, 11).roadIsPillaged = true
        tile(-10, 10).roadIsPillaged = true
        // Compressed river barriers, with several traversable crossing edges.
        for (c in -8..7) tile(c, 4).hasBottomRightRiver = true // Bull Run / Potomac approaches
        for (c in -6..8) tile(c, -1).hasBottomRightRiver = true // Rappahannock
        for (c in -5..10) tile(c, -11).hasBottomRightRiver = true // James
        val deployment = arrayListOf<String>()
        fun unit(c: Civilization, name: String, column: Int, row: Int, label: String, promotion: String? = null) {
            val t = tile(column, row)
            if (promotion == "Drill I") t.setTerrainFeatures(listOf(Constants.hill, Constants.forest))
            if (promotion == "Shock I") t.setTerrainFeatures(emptyList())
            val u = f.addUnit(name, c, t)
            u.instanceName = label.replace("union_", "联邦_").replace("confederate_", "邦联_")
                .replace("main_", "主力_").replace("front_", "前线_").replace("capital_", "首都_")
                .replace("west_", "西侧_").replace("reserve_", "预备_").replace("infantry_", "步兵_")
                .replace("cavalry_", "骑兵_").replace("cannon_", "火炮_").replace("worker_", "工人_").replace("_", " ")
            if (promotion != null) {
                u.promotions.addPromotion(promotion, isFree = true)
                u.promotions.numberOfPromotions = 1
            }
            u.promotions.XP = 0
            deployment.add("${u.id}\t${c.civName}\t$name\t$label\t$column,$row\t${t.position}\t${promotion ?: "-"}")
        }
        unit(union, "Rifleman", -1, 6, "union_main_infantry_01", "Shock I")
        unit(union, "Rifleman", 0, 6, "union_main_infantry_02")
        unit(union, "Rifleman", 1, 6, "union_main_infantry_03")
        unit(union, "Rifleman", 2, 5, "union_main_infantry_04")
        unit(union, "Rifleman", 4, 9, "union_capital_infantry_01")
        unit(union, "Rifleman", 6, 10, "union_capital_infantry_02")
        unit(union, "Rifleman", -8, 9, "union_west_infantry_01", "Drill I")
        unit(union, "Cavalry", -7, 8, "union_west_cavalry_01")
        unit(union, "Cavalry", 3, 8, "union_reserve_cavalry_01")
        unit(union, "Cannon", -1, 7, "union_main_cannon_01")
        unit(union, "Cannon", 1, 7, "union_main_cannon_02")
        unit(union, "Cannon", 4, 10, "union_capital_cannon_01")
        unit(union, "Great General", 0, 6, "联邦指挥部")
        unit(union, "Worker", -8, 9, "union_worker_01")
        unit(union, "Worker", 3, 6, "union_worker_02")
        unit(confederacy, "Rifleman", -4, 2, "confederate_front_infantry_01", "Drill I")
        unit(confederacy, "Rifleman", -2, 2, "confederate_front_infantry_02")
        unit(confederacy, "Rifleman", -3, 3, "confederate_front_infantry_03")
        unit(confederacy, "Rifleman", 0, 1, "confederate_front_infantry_04")
        unit(confederacy, "Rifleman", 2, -10, "confederate_capital_infantry_01")
        unit(confederacy, "Rifleman", 1, -8, "confederate_capital_infantry_02")
        unit(confederacy, "Rifleman", -7, -1, "confederate_reserve_infantry_01", "Shock I")
        unit(confederacy, "Cavalry", -7, 1, "confederate_west_cavalry_01")
        unit(confederacy, "Cavalry", 1, -5, "confederate_reserve_cavalry_01")
        unit(confederacy, "Cannon", -2, 1, "confederate_front_cannon_01")
        unit(confederacy, "Cannon", 3, -11, "confederate_capital_cannon_01")
        unit(confederacy, "Great General", -2, 2, "邦联指挥部")
        unit(confederacy, "Worker", 2, -10, "confederate_worker_01")
        unit(confederacy, "Worker", -1, -2, "confederate_worker_02")
        unit(confederacy, "Rifleman", -1, -2, "confederate_reserve_infantry_02")
        unit(confederacy, "Rifleman", 1, -3, "confederate_reserve_infantry_03")
        union.diplomacyFunctions.makeCivilizationsMeet(confederacy)
        union.getDiplomacyManager(confederacy)!!.diplomaticStatus = DiplomaticStatus.War
        confederacy.getDiplomacyManager(union)!!.diplomaticStatus = DiplomaticStatus.War
        union.addGold(600 - union.gold); confederacy.addGold(450 - confederacy.gold)
        g.currentPlayer = union.civID
        g.gameParameters.apply {
            players = ArrayList(g.civilizations.map { Player(chosenCiv = it.civName, playerType = it.playerType) })
            numberOfCityStates = 0; noBarbarians = true; noCityRazing = true; victoryTypes.clear()
        }
        g.tileMap.mapParameters.noRuins = true
        g.tileMap.mapParameters.seed = 18610716L
        g.campaign = CampaignState().apply { definition = CampaignCatalog.definition(CivilWarCampaign.id) }
        g = g.clone() // Rebuild unit lists once, as a real load does.
        g.setTransients()
        for (c in g.civilizations) {
            for (t in g.tileMap.values) t.setExplored(c, true) // geography only; enemy units remain under fog
            c.cache.updateViewableTiles()
            c.cache.updateCivResources()
            c.cache.updateCitiesConnectedToCapital()
            for (city in c.cities) {
                city.reassignPopulation(); city.cityStats.update()
                city.cityConstructions.editQueue {
                    clear()
                    add(when (city.id) { "cw-washington", "cw-richmond" -> "Rifleman"; "cw-baltimore", "cw-petersburg" -> "Cannon"; else -> "Gold" })
                }
                city.cityConstructions.inProgressConstructions.clear()
                city.cityConstructions.productionOverflow = 0
            }
            c.updateStatsForNextTurn()
            c.popupAlerts.clear(); c.notifications.clear()
            println("${c.civName}: supply=${c.stats.getUnitSupply()} penalty=${c.stats.getUnitSupplyProductionPenalty()} units=${c.units.getCivUnits().count()} gold=${c.gold} income=${c.stats.statsForNextTurn.gold} happiness=${c.getHappiness()} science=${c.stats.statsForNextTurn.science}")
            c.cities.forEach { println("${it.id}: ${it.cityStats.currentCityStats}") }
        }
        g.campaign = null
        FileConversions.writeJson(Gdx.files.local("${CivilWarCampaign.folder}/levels/${CivilWarCampaign.id}/initial-state.json"), g, false)
        Gdx.files.local("${CivilWarCampaign.folder}/deployment.tsv").writeString("id\tfaction\ttype\tscenario-id\tcolumn,row\thex\tpromotion\n" + deployment.joinToString("\n") + "\n", false)
    }
}
