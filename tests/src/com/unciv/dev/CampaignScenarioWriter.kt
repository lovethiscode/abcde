package com.unciv.dev

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.backends.headless.HeadlessFiles
import com.unciv.Constants
import com.unciv.logic.campaign.CampaignCatalog
import com.unciv.logic.city.City
import com.unciv.logic.city.CityFlags
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.PlayerType
import com.unciv.logic.civilization.diplomacy.DiplomaticStatus
import com.unciv.logic.files.FileConversions
import com.unciv.logic.map.HexCoord
import com.unciv.logic.map.tile.RoadStatus
import com.unciv.models.metadata.Player
import com.unciv.testing.TestGame

/** Explicit authoring command. Runtime only reads the resulting, reviewable full-state assets. */
object CampaignScenarioWriter {
    @JvmStatic fun main(args: Array<String>) {
        Gdx.files = HeadlessFiles()
        try { for (number in 1..16) {
            // Keep the construction scene that already passed device route acceptance.
            if (number == 2) continue
            write(number)
        }
        } finally { com.unciv.utils.Concurrency.stopThreadPools() }
    }

    private fun write(n: Int) {
        val f = TestGame()
        f.setSpeed("Quick")
        f.makeHexagonalMap(if (n <= 4) 5 else if (n <= 10) 7 else 9, Constants.grassland)
        val g = f.gameInfo
        // Fixed terrain, with productive wooded plains and resource pockets. No random map generation.
        for (t in f.tileMap.tileList) {
            if ((t.position.x * 3 + t.position.y * 5).mod(7) == 0) {
                f.setTileTerrain(t.position, Constants.plains)
                f.setTileFeatures(t.position, Constants.forest)
            }
            if ((t.position.x * 11 + t.position.y * 7).mod(19) == 0)
                t.setTileResource(f.ruleset.tileResources.getValue("Wheat"))
        }
        fun nation(name: String, human: Boolean = false) = f.addCiv(f.ruleset.nations.getValue(name), isPlayer = human)
        val p = nation("Rome", true)
        val enemy = if (n in listOf(5, 10, 11, 12, 13, 14, 15, 16)) nation("Germany") else null
        val third = if (n in listOf(14, 16)) nation("Greece") else null
        fun tech(c: Civilization, name: String) {
            for (prerequisite in f.ruleset.technologies.getValue(name).prerequisites) tech(c, prerequisite)
            if (!c.tech.isResearched(name)) c.tech.addTechnology(name, false)
        }
        val techs = when (n) {
            1, 16 -> emptyList()
            3 -> listOf("Mining")
            4, 5, 6 -> listOf("Pottery", "Mining")
            7 -> listOf("Currency", "Writing", "Mining")
            13 -> listOf("Currency", "Writing", "Masonry", "Bronze Working")
            8 -> listOf("The Wheel", "Calendar", "Mining")
            9 -> listOf("Pottery", "The Wheel", "Bronze Working", "Archery")
            10 -> listOf("Pottery", "Archery", "Bronze Working")
            11, 12, 14 -> listOf("Mathematics", "Masonry", "Bronze Working", "Pottery", "Writing")
            15 -> listOf("Pottery", "Mining", "Animal Husbandry", "Archery")
            else -> listOf("Pottery")
        }
        for (c in g.civilizations) for (t in techs) tech(c, t)
        fun city(c: Civilization, id: String, x: Int, y: Int, pop: Int, vararg buildings: String): City {
            val t = f.getTile(x, y)
            t.setTerrainFeatures(emptyList())
            t.setTileResource(null)
            val result = f.addCity(c, t, initialPopulation = pop)
            result.id = id
            for (b in buildings) result.cityConstructions.addBuilding(f.ruleset.buildings.getValue(b))
            // Every city has a food tile and a production tile in its starting borders.
            val neighbors = t.neighbors.filter { !it.isCityCenter() }.toList()
            neighbors.firstOrNull()?.apply {
                setTerrainFeatures(emptyList())
                setTileResource(f.ruleset.tileResources.getValue("Wheat"))
            }
            neighbors.getOrNull(1)?.apply {
                f.setTileTerrain(position, Constants.plains)
                setTerrainFeatures(listOf(Constants.forest))
            }
            return result
        }
        val capPos = when (n) { 1, 3, 4 -> 0 to 0; 5 -> -3 to 0; 8 -> -3 to 0; 11 -> -4 to -2; 14 -> -5 to 0; 16 -> -6 to -2; else -> -4 to 0 }
        val cap = if (n in listOf(1, 16)) null else {
            val pop = when (n) { 3, 4 -> 2; 5, 6 -> 3; 11, 12, 13, 14 -> 5; else -> 4 }
            val buildings = when (n) {
                3 -> emptyList()
                4, 6, 8, 10, 12 -> listOf("Granary")
                5 -> listOf("Monument")
                7, 13, 14 -> listOf("Granary", "Library")
                11 -> listOf("Granary", "Monument", "Walls")
                else -> listOf("Granary", "Monument")
            }
            city(p, "player-capital", capPos.first, capPos.second, pop, *buildings.toTypedArray())
        }
        when (n) {
            7 -> city(p, "player-second", 1, 0, 2, "Granary")
            9 -> city(p, "player-second", 1, 0, 2)
            11 -> { city(p, "player-north", 0, -3, 3, "Granary"); city(p, "player-south", 0, 1, 3, "Granary") }
            14 -> city(p, "player-second", -5, -4, 3, "Granary")
        }
        if (enemy != null && n != 16) {
            val ePos = when (n) { 5 -> 5 to 3; 10 -> 3 to 0; 11 -> 5 to -2; 12 -> 5 to 0; 13 -> 6 to 0; 14 -> 1 to 0; else -> 4 to 0 }
            city(enemy, "enemy-capital", ePos.first, ePos.second, when(n) { 5, 10 -> 3; 11 -> 6; 12, 14 -> 5; else -> 4 }, "Granary")
            when (n) {
                11 -> city(enemy, "enemy-second", 5, 2, 3, "Granary")
                12 -> { city(enemy, "enemy-fort", 1, 0, 3, "Walls"); city(enemy, "enemy-second", 5, 4, 3) }
                13 -> {
                    val occupied = city(enemy, "occupied-city", 1, 0, 4, "Granary")
                    occupied.moveToCiv(p)
                    occupied.isPuppet = false
                    occupied.setFlag(CityFlags.Resistance, 3)
                }
                15 -> city(enemy, "enemy-second", 4, 4, 2)
            }
        }
        if (n == 14) city(third!!, "third-capital", 5, 4, 4, "Granary")
        fun army(c: Civilization, names: List<String>, position: HexCoord) {
            for (name in names) checkNotNull(f.tileMap.placeUnitNearTile(position, name, c))
        }
        val ours = when(n) {
            1, 16 -> listOf("Settler", "Warrior")
            3, 4 -> listOf("Worker", "Warrior")
            5 -> listOf("Scout", "Warrior")
            6 -> listOf("Worker")
            7 -> listOf("Worker", "Worker", "Warrior", "Warrior", "Warrior")
            8 -> listOf("Settler", "Worker", "Worker", "Warrior")
            9 -> listOf("Worker", "Warrior")
            10 -> listOf("Spearman", "Warrior", "Archer", "Archer")
            11 -> listOf("Spearman", "Spearman", "Archer", "Archer", "Archer", "Warrior")
            12 -> listOf("Spearman", "Spearman", "Archer", "Archer", "Catapult", "Catapult", "Worker")
            13 -> listOf("Worker", "Spearman", "Spearman", "Archer", "Archer")
            14 -> listOf("Worker", "Spearman", "Archer")
            else -> listOf("Worker", "Scout", "Warrior")
        }
        army(p, ours, HexCoord(capPos.first, capPos.second))
        if (enemy != null) {
            val names = when(n) {
                5 -> listOf("Warrior")
                10 -> listOf("Warrior", "Warrior", "Archer")
                11 -> List(4) { "Spearman" } + List(3) { "Archer" } + "Catapult"
                12 -> List(2) { "Spearman" } + List(3) { "Archer" }
                14 -> listOf("Spearman", "Spearman", "Archer", "Archer")
                15 -> listOf("Warrior", "Warrior", "Archer", "Worker")
                16 -> listOf("Settler", "Warrior")
                else -> listOf("Warrior", "Archer")
            }
            army(enemy, names, when(n) { 12 -> HexCoord(2, 0); 16 -> HexCoord(5, -2); else -> enemy.getCapital()!!.location })
        }
        if (third != null) army(third, if (n == 16) listOf("Settler", "Warrior") else listOf("Warrior", "Archer"), HexCoord(3, 6))
        if (n == 1) {
            // Two sites, one food-rich, one production-rich, both within the four-turn teaching window.
            for ((x,y) in listOf(0 to 1, -1 to 0)) f.getTile(x,y).apply { setTerrainFeatures(emptyList()); setTileResource(f.ruleset.tileResources.getValue("Wheat")) }
            for ((x,y) in listOf(3 to 0, 3 to 1, 2 to 0)) f.setTileTerrainAndFeatures(HexCoord(x,y), Constants.plains, Constants.forest)
        }
        if (n == 3) {
            f.getTile(-1,0).apply { setTerrainFeatures(emptyList()); setTileResource(f.ruleset.tileResources.getValue("Wheat")); setImprovement(null) }
            f.getTile(1,0).apply { setTerrainFeatures(listOf(Constants.hill)); setTileResource(f.ruleset.tileResources.getValue("Gems")); setImprovement(null) }
            f.getTile(0,1).apply { setTerrainFeatures(emptyList()); setTileResource(null); setImprovement(null) }
        }
        if (n == 4) cap!!.population.foodStored = cap.population.getFoodToNextPopulation() / 2
        if (n == 8) {
            f.getTile(-2,0).apply { setTerrainFeatures(listOf(Constants.hill)); setTileResource(f.ruleset.tileResources.getValue("Gems")); setImprovement("Mine") }
            f.getTile(3,1).apply { setTerrainFeatures(emptyList()); setTileResource(f.ruleset.tileResources.getValue("Sugar")); setImprovement(null) }
        }
        if (n in listOf(7, 9, 11, 13, 14, 15, 16)) {
            // Real resource supply, not a direct happiness/stat override.
            for (c in g.civilizations) for ((index, ct) in c.cities.filterNot { it.id == "occupied-city" }.withIndex()) {
                val t = ct.getCenterTile().neighbors.first { !it.isCityCenter() && it.resource != "Wheat" }
                t.setTerrainFeatures(listOf(Constants.hill))
                t.setTileResource(f.ruleset.tileResources.getValue(listOf("Gems", "Gold Ore", "Silver")[index % 3]))
                t.setImprovement("Mine")
            }
        }
        if (n == 7) {
            // Working trading posts pays part of the eight redundant roads' maintenance.
            for (ct in p.cities) for (t in ct.getCenterTile().neighbors.filter { !it.isCityCenter() && it.resource !in listOf("Gems", "Gold Ore", "Silver") }) {
                t.setTerrainFeatures(emptyList())
                t.setTileResource(null)
                t.setImprovement("Trading post")
            }
            for (t in cap!!.getCenterTile().getTilesInDistance(2).filter { !it.isCityCenter() }.take(8)) {
                cap.expansion.takeOwnership(t)
                t.setRoadStatus(RoadStatus.Road, p)
            }
        }
        if (n == 13) {
            for ((position, resource) in listOf(HexCoord(0,0) to "Gold Ore", HexCoord(1,1) to "Silver", HexCoord(-4,1) to "Sugar")) {
                f.getTile(position).apply {
                    setTerrainFeatures(if (resource == "Sugar") emptyList() else listOf(Constants.hill))
                    setTileResource(f.ruleset.tileResources.getValue(resource)); setImprovement(null)
                }
            }
        }
        if (n in listOf(9, 11, 14)) {
            for (ct in p.cities.filter { it != cap }) {
                var at = ct.location
                while (at != cap!!.location) {
                    val tile = f.tileMap[at]
                    tile.setRoadStatus(RoadStatus.Road, p)
                    val next = tile.neighbors.minBy { it.aerialDistanceTo(cap.getCenterTile()) }
                    at = next.position
                }
            }
        }
        if (n >= 9) {
            val starts = if (n == 16) listOf(HexCoord(-6,-2),HexCoord(5,-2),HexCoord(3,6))
                else g.civilizations.mapNotNull { it.getCapital()?.location }
            for (start in starts) {
                for ((offset, resource) in listOf(HexCoord(1,0) to "Iron", HexCoord(0,-1) to "Horses")) {
                    f.tileMap[start.plus(offset)].apply {
                        if (!isCityCenter() && this.resource !in listOf("Gems", "Gold Ore", "Silver")) {
                            setTerrainFeatures(emptyList())
                            setTileResource(f.ruleset.tileResources.getValue(resource), majorDeposit = true)
                            setImprovement(null)
                        }
                    }
                }
            }
        }
        if (n != 5 && n != 16) for (a in g.civilizations) for (b in g.civilizations) {
            if (a != b && !a.knows(b)) a.diplomacyFunctions.makeCivilizationsMeet(b)
        }
        if (n in listOf(10, 11, 12, 14)) {
            p.getDiplomacyManager(enemy!!)!!.diplomaticStatus = DiplomaticStatus.War
            enemy.getDiplomacyManager(p)!!.diplomaticStatus = DiplomaticStatus.War
        }
        val gold = listOf(100,150,100,120,100,300,30,200,200,200,300,300,200,300,200,100)[n-1]
        for (c in g.civilizations) {
            c.addGold((if (c == p) gold else if (n == 11) 300 else if (n in listOf(12,14,15)) 200 else 100) - c.gold)
            for (ct in c.cities) {
                ct.cityConstructions.editQueue { clear() }
                ct.cityConstructions.inProgressConstructions.clear()
            }
        }
        if (n in listOf(10,11,12)) {
            for (y in -2..2) f.getTile(-1,y).hasBottomRightRiver = true
            for (y in listOf(-4,-3,3,4)) f.setTileTerrainAndFeatures(HexCoord(2,y), Constants.plains, Constants.hill)
        }
        g.currentPlayer = p.civID
        g.gameParameters.apply {
            players = ArrayList(g.civilizations.map { Player(chosenCiv = it.civName, playerType = it.playerType) })
            numberOfCityStates = 0
            noBarbarians = true
            victoryTypes.clear()
            if (n >= 15) victoryTypes.add("Domination")
        }
        g.tileMap.mapParameters.noRuins = true
        g.tileMap.mapParameters.seed = n.toLong()
        g.setTransients()
        for (c in g.civilizations) {
            for (t in g.tileMap.tileList) t.setExplored(c, false)
            c.cache.updateViewableTiles()
            for (ct in c.cities) { ct.reassignPopulation(); ct.cityStats.update() }
            c.cache.updateCivResources()
            c.cache.updateCitiesConnectedToCapital()
            c.updateStatsForNextTurn()
            c.popupAlerts.clear()
            c.notifications.clear()
        }
        if (n == 7) {
            // Calibrate the authored riverbank yield against actual rules, never overwrite computed income.
            for (position in cap!!.workedTiles.sortedWith(compareBy({ it.x }, { it.y }))) {
                if (p.stats.statsForNextTurn.gold >= -4f) break
                val tile = f.tileMap[position]
                tile.setConnectedByRiver(tile.neighbors.first(), true)
                for (ct in p.cities) { ct.reassignPopulation(); ct.cityStats.update() }
                p.updateStatsForNextTurn()
            }
            check(p.stats.statsForNextTurn.gold in -4f..-2f) { "Economic opening needs recalibration" }
        }
        // Author no queued production or overflow; AI chooses its own queue when the scene is loaded.
        for (c in g.civilizations) for (ct in c.cities) {
            ct.cityConstructions.editQueue { clear() }
            ct.cityConstructions.inProgressConstructions.clear()
            ct.cityConstructions.productionOverflow = 0
        }
        FileConversions.writeJson(Gdx.files.local("campaigns/foundation/levels/${CampaignCatalog.levelId(n)}/initial-state.json"), g, false)
        println("Level $n: gold ${p.gold}, cities ${p.cities.map { it.population.population }}, income ${p.stats.statsForNextTurn.gold}, happiness ${p.getHappiness()}")
    }
}
