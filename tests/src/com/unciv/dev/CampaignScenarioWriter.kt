package com.unciv.dev

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.backends.headless.HeadlessFiles
import com.unciv.Constants
import com.unciv.logic.files.FileConversions
import com.unciv.models.metadata.Player
import com.unciv.logic.civilization.PlayerType
import com.unciv.testing.TestGame

/** Authoring tool only. Runtime loads the published scene; it never recreates these defaults. */
object CampaignScenarioWriter {
    @JvmStatic fun main(args: Array<String>) {
        Gdx.files = HeadlessFiles()
        val fixture = TestGame()
        fixture.setSpeed("Quick")
        fixture.makeHexagonalMap(5, Constants.grassland)
        // Plenty of food, with production available around the capital.
        for ((x, y) in listOf(1 to 0, 0 to 1, -1 to -1))
            fixture.setTileTerrain(fixture.getTile(x, y).position, Constants.plains)
        fixture.setTileFeatures(fixture.getTile(1, 0).position, Constants.forest)
        fixture.setTileFeatures(fixture.getTile(0, 1).position, Constants.forest)
        fixture.getTile(-1, 0).setTileResource(fixture.ruleset.tileResources.getValue("Wheat"))
        val player = fixture.addCiv(fixture.ruleset.nations.getValue("Rome"), isPlayer = true)
        player.tech.addTechnology("Pottery")
        val capital = fixture.addCity(player, fixture.getTile(0, 0), initialPopulation = 2)
        capital.id = "construction-capital"
        capital.cityConstructions.editQueue { clear() }
        capital.cityConstructions.inProgressConstructions.clear()
        fixture.addUnit("Warrior", player, fixture.getTile(0, 0))
        player.addGold(150 - player.gold)
        player.popupAlerts.clear()
        player.notifications.clear()
        val game = fixture.gameInfo
        game.currentPlayer = player.civID
        game.gameParameters.apply {
            players = arrayListOf(Player(chosenCiv = "Rome", playerType = PlayerType.Human))
            numberOfCityStates = 0
            noBarbarians = true
            victoryTypes.clear()
        }
        game.tileMap.mapParameters.noRuins = true
        game.tileMap.mapParameters.seed = 2L
        game.setTransients()
        player.cache.updateViewableTiles()
        capital.reassignPopulation()
        capital.cityStats.update()
        FileConversions.writeJson(Gdx.files.local("campaigns/foundation/levels/foundation-02/initial-state.json"), game, false)
        com.unciv.utils.Concurrency.stopThreadPools()
        println("Wrote construction scene: ${player.gold} gold, population ${capital.population.population}")
    }
}
