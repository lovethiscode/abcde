package com.unciv.dev

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.backends.headless.HeadlessFiles
import com.unciv.Constants
import com.unciv.logic.civilization.diplomacy.DiplomaticStatus
import com.unciv.logic.endless.EndlessRules
import com.unciv.logic.endless.EndlessState
import com.unciv.logic.files.FileConversions
import com.unciv.models.metadata.Player
import com.unciv.testing.TestGame

/** Deterministic map authoring; never runs at game startup or as part of tests. */
object EndlessScenarioWriter {
    @JvmStatic fun main(args: Array<String>) {
        Gdx.files = HeadlessFiles()
        try { write() } finally { com.unciv.utils.Concurrency.stopThreadPools() }
    }

    private fun write() {
        val f = TestGame()
        var game = f.gameInfo
        f.ruleset.add(EndlessRules.ruleset(game))
        f.setSpeed("Standard")
        f.makeHexagonalMap(10, Constants.grassland)
        val center = f.getTile(0, 0)
        for (tile in f.tileMap.values) {
            f.setTileTerrain(tile.position, Constants.grassland)
            val x = tile.position.x; val y = tile.position.y
            val distance = tile.aerialDistanceTo(center)
            // Three broad open axes remain passable; broken forests provide flank cover.
            if (distance in 4..7 && x != 0 && y != 0 && x != y && (x * 3 + y * 5).mod(7) < 2)
                f.setTileFeatures(tile.position, Constants.forest)
            if (distance in 4..6 && y == 2 && x in -4..4 && x != 0 && x != 2) tile.hasBottomRightRiver = true
        }
        center.hasBottomRightRiver = true
        val guard = f.addCiv(f.ruleset.nations.getValue("Bastion Guard"), true)
        val enemy = f.addCiv(f.ruleset.nations.getValue("Invaders"))
        guard.civID = "endless-guard"; enemy.civID = "endless-invaders"
        for (civ in listOf(guard, enemy)) for (tech in EndlessRules.initialTechs) civ.tech.addTechnology(tech, false)
        val city = f.addCity(guard, center, initialPopulation = 4)
        city.id = "endless-core-city"; city.name = "Border Bastion"
        city.cityConstructions.addBuilding("Granary")
        city.cityConstructions.addBuilding("Monument")
        for (tile in center.getTilesInDistance(2).filter { it != center }) {
            city.expansion.takeOwnership(tile)
            f.setTileTerrain(tile.position, Constants.plains)
            tile.setTerrainFeatures(emptyList())
            tile.setImprovement("Farm")
        }
        fun resource(x: Int, y: Int, name: String, improvement: String, hill: Boolean = false) {
            val tile = f.getTile(x, y)
            tile.setTerrainFeatures(if (hill) listOf(Constants.hill) else emptyList())
            tile.setTileResource(f.ruleset.tileResources.getValue(name))
            tile.resourceAmount = 4
            tile.setImprovement(improvement)
        }
        resource(1, 0, "Wheat", "Farm")
        resource(0, 1, "Iron", "Mine", true)
        resource(1, 1, "Horses", "Pasture")
        resource(-1, 0, "Gems", "Mine", true)
        f.setTileFeatures(f.getTile(-1, -1).position, Constants.hill)
        f.getTile(-1, -1).setImprovement("Mine")
        f.addUnit("Spearman", guard, center)
        f.addUnit("Spearman", guard, f.getTile(1, 0))
        f.addUnit("Archer", guard, f.getTile(0, 1))
        f.addUnit("Archer", guard, f.getTile(-1, 0))
        f.addUnit("Worker", guard, f.getTile(1, 1))
        guard.addGold(160 - guard.gold)
        guard.diplomacyFunctions.makeCivilizationsMeet(enemy)
        guard.getDiplomacyManager(enemy)!!.diplomaticStatus = DiplomaticStatus.War
        enemy.getDiplomacyManager(guard)!!.diplomaticStatus = DiplomaticStatus.War
        game.currentPlayer = guard.civID
        game.gameParameters.apply {
            players = ArrayList(game.civilizations.map { Player(chosenCiv = it.civName, playerType = it.playerType) })
            numberOfCityStates = 0; noBarbarians = true; noCityRazing = true; victoryTypes.clear()
        }
        game.tileMap.mapParameters.apply { noRuins = true; worldWrap = false; seed = 1101 }
        game.endless = EndlessState()
        game = game.clone()
        game.setTransients()
        for (civ in game.civilizations) {
            game.tileMap.values.forEach { it.setExplored(civ, true) }
            civ.cache.updateViewableTiles()
            civ.cache.updateCivResources()
            civ.cache.updateCitiesConnectedToCapital()
            for (c in civ.cities) {
                c.reassignPopulation(); c.cityStats.update()
                c.cityConstructions.editQueue { clear(); add("Gold") }
                c.cityConstructions.inProgressConstructions.clear()
                c.cityConstructions.productionOverflow = 0
            }
            civ.updateStatsForNextTurn()
            civ.popupAlerts.clear(); civ.notifications.clear()
        }
        val player = EndlessRules.player(game)
        val core = EndlessRules.core(game)!!
        core.health = com.unciv.logic.battle.CityCombatant(core).getMaxHealth()
        println("Endless opening: city=${core.cityStats.currentCityStats}; civ=${player.stats.statsForNextTurn}; happiness=${player.getHappiness()}; supply=${player.stats.getUnitSupply()}")
        require(core.cityStats.currentCityStats.food >= 2 && core.cityStats.currentCityStats.production >= 10)
        require(player.stats.statsForNextTurn.science >= 10 && player.stats.statsForNextTurn.gold >= 2 && player.getHappiness() >= 0)
        require(player.stats.getUnitSupplyProductionPenalty() == 0f)
        FileConversions.writeJson(Gdx.files.local("endless/initial-state.json"), game, false)
    }
}
