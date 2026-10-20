package com.unciv.logic.achievements

import com.unciv.json.json
import com.unciv.logic.battle.*
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.transients.CapitalConnectionsFinder.CapitalConnectionMedium
import com.unciv.logic.city.City
import com.unciv.logic.map.HexCoord
import com.unciv.logic.trade.*
import com.unciv.models.stats.Stats
import org.junit.Assert.*
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import com.unciv.testing.BaseTestRunner

@RunWith(org.junit.runners.Parameterized::class)
@org.junit.runners.Parameterized.UseParametersRunnerFactory(com.unciv.testing.TestRunnerFactory::class)
class AchievementNewContentTest(private val base: com.unciv.models.metadata.BaseRuleset) {
    companion object {
        @JvmStatic @org.junit.runners.Parameterized.Parameters(name="{0}")
        fun parameters()=com.unciv.models.metadata.BaseRuleset.entries.map{arrayOf(it)}
    }
    @get:Rule val temporary = TemporaryFolder()
    @After fun cleanup() { AchievementTracker.service = null }
    private fun fixture(nation: String = "Rome") = AchievementTestFixture(nation,base).also { f ->
        f.state.recordingVersion = 4
        f.game.getCities().forEach { AchievementExtendedRules.founded(it) }
    }
    private fun war(f: AchievementTestFixture, other: Civilization = f.opponents[0]) {
        if (!f.player.knows(other)) f.player.diplomacyFunctions.makeCivilizationsMeet(other)
        if (!f.player.isAtWarWith(other)) f.player.getDiplomacyManager(other)!!.declareWar()
    }
    private fun next(f: AchievementTestFixture) {
        f.nextTurn(); f.player.cache.updateViewableTiles(); AchievementExtendedRules.visibleTurnStart(f.player)
    }
    private fun complete(f: AchievementTestFixture, id: String) = id in f.results()

    @Test fun seaConnectionRequiresDifferentValidLandmassAndAnActualHarborMedium() {
        val f = fixture(); val city = f.city(0,0)
        f.capital.getCenterTile().clearContinent();f.capital.getCenterTile().setContinent(1);city.getCenterTile().clearContinent();city.getCenterTile().setContinent(2)
        city.cityConstructions.addBuilding("Harbor")
        f.player.cache.citiesConnectedToCapitalToMediums = mapOf(city to java.util.EnumSet.of(CapitalConnectionMedium.Road))
        assertFalse("F04" in f.results(true))
        f.player.cache.citiesConnectedToCapitalToMediums = mapOf(city to java.util.EnumSet.of(CapitalConnectionMedium.HarborFromRoad))
        assertTrue("F04" in f.results(true))
        city.getCenterTile().clearContinent();city.getCenterTile().setContinent(1); assertFalse("F04" in f.results(true))
        city.getCenterTile().clearContinent(); assertFalse("F04" in f.results(true))
    }
    private fun resource(f: AchievementTestFixture, name: String, x: Int, y: Int, civ: Civilization = f.player) {
        civ.tech.addTechnology("Calendar");civ.tech.addTechnology("Mining")
        val tile = f.test.getTile(x,y);civ.cities.first().expansion.takeOwnership(tile);tile.setTileResource(f.test.ruleset.tileResources.getValue(name))
        val improvement = f.test.ruleset.tileResources.getValue(name).getImprovements().first()
        tile.improvementFunctions.setImprovement(improvement, civ)
    }
    @Test fun luxuryExchangeUsesDomesticSurplusAndValidatesTheSameAcceptedTrade() {
        val f = fixture(); val other = f.opponents[0];f.player.diplomacyFunctions.makeCivilizationsMeet(other)
        resource(f,"Silk",-8,-7);resource(f,"Silk",-7,-8);resource(f,"Gems",7,5,other)
        val trade = TradeLogic(f.player,other);trade.currentTrade.ourOffers.add(TradeOffer("Silk",TradeOfferType.Luxury_Resource,1,30))
        trade.currentTrade.theirOffers.add(TradeOffer("Gems",TradeOfferType.Luxury_Resource,1,30))
        f.player.cache.updateCivResources(); assertEquals(2,f.player.getResourceAmount("Silk"))
        trade.acceptTrade();assertTrue(complete(f,"F05"));assertEquals(1,f.player.getResourceAmount("Silk"))
    }
    @Test fun importedOrCityStateLuxuriesCannotMasqueradeAsDomesticSurplus() {
        val f = fixture();val other=f.opponents[0];f.player.diplomacyFunctions.makeCivilizationsMeet(other)
        resource(f,"Silk",-8,-7)
        val imported=Trade().apply { theirOffers.add(TradeOffer("Silk",TradeOfferType.Luxury_Resource,1,30)) }
        f.player.getDiplomacyManager(other)!!.trades.add(imported);f.player.cache.updateCivResources()
        val trade=Trade().apply {ourOffers.add(TradeOffer("Silk",TradeOfferType.Luxury_Resource,1,30));theirOffers.add(TradeOffer("Gems",TradeOfferType.Luxury_Resource,1,30))}
        assertTrue(AchievementExtendedRules.beforeTrade(f.player,other,trade).isEmpty())
    }
    @Test fun liberationCommitsAfterReturnAndRejectsAnEarlierFormalOwnership() {
        val f=fixture();val other=f.opponents[0];val city=f.city(0,0,civ=other);val unit=f.unit("Warrior",1,0)
        AchievementTracker.cityBattleWon(unit,city);city.liberateCity(f.player)
        assertEquals(other,city.civ);assertTrue(complete(f,"F06"))
        val g=fixture();val owned=g.city(0,0,civ=g.opponents[0]);owned.moveToCiv(g.player);owned.moveToCiv(g.opponents[0])
        AchievementTracker.cityBattleWon(g.unit("Warrior",1,0),owned);owned.liberateCity(g.player)
        assertFalse(complete(g,"F06"))
    }
    @Test fun cityReliefWaitsForTheNextTurnAndLossBreaksItsWindow() {
        val f=fixture();war(f);val city=f.city(0,0);val defender=f.unit("Warrior",0,0)
        val enemy=f.unit("Warrior",1,0,f.opponents[0]).apply { health=1 }
        f.player.cache.updateViewableTiles();AchievementExtendedRules.visibleTurnStart(f.player)
        assertTrue(enemy.id.toString() in f.history.extended.relief.getValue(city.id).enemies)
        Battle.attack(MapUnitCombatant(defender),MapUnitCombatant(enemy));assertFalse(complete(f,"F07"))
        AchievementExtendedRules.visibleTurnStart(f.player) // repeated load/update cannot erase the real defeat
        assertTrue(f.history.extended.relief.getValue(city.id).defeated)
        next(f);assertTrue(complete(f,"F07"))
    }
    @Test fun returningAnObservedCityDoesNotRestoreItsReliefWindow() {
        val f=fixture();war(f);val city=f.city(0,0);f.unit("Warrior",1,0,f.opponents[0])
        f.player.cache.updateViewableTiles();AchievementExtendedRules.visibleTurnStart(f.player)
        f.history.extended.relief.getValue(city.id).defeated=true
        city.moveToCiv(f.opponents[0]);city.moveToCiv(f.player);next(f);assertFalse(complete(f,"F07"))
    }
    private fun siege(f:AchievementTestFixture, city:City) {
        war(f,city.civ);val catapult=f.unit("Catapult",city.location.x.toInt()-2,city.location.y.toInt())
        Battle.attack(MapUnitCombatant(catapult),CityCombatant(city))
        assertTrue(f.history.extended.siege.getValue(city.id).supporters.contains(catapult.id.toString()))
    }
    @Test fun coordinatedCaptureUsesRealSupportDamageAndTheSettledOwnershipChoice() {
        val f=fixture();val city=f.city(0,0,civ=f.opponents[0]);siege(f,city)
        val captor=f.unit("Warrior",1,0);AchievementTracker.cityBattleWon(captor,city)
        city.puppetCity(f.player);assertTrue(complete(f,"F08"))
    }
    @Test fun peaceInvalidatesPriorSiegeEvenIfTheWarRestarts() {
        val f=fixture();val city=f.city(0,0,civ=f.opponents[0]);siege(f,city)
        f.player.getDiplomacyManager(city.civ)!!.makePeace();war(f,city.civ)
        AchievementTracker.cityBattleWon(f.unit("Warrior",1,0),city);city.puppetCity(f.player)
        assertFalse(complete(f,"F08"))
    }
    @Test fun recoveryRequiresPostResistanceNaturalGrowthWhileConnected() {
        val f=fixture();val city=f.city(0,0,population=4,civ=f.opponents[0])
        AchievementTracker.cityBattleWon(f.unit("Warrior",1,0),city);city.puppetCity(f.player)
        city.removeFlag(com.unciv.logic.city.CityFlags.Resistance);AchievementExtendedRules.resistanceChanged(f.player)
        val base=city.population.population;city.population.addPopulation(2);assertFalse(complete(f,"F09"))
        f.player.cache.citiesConnectedToCapitalToMediums=mapOf(city to java.util.EnumSet.of(CapitalConnectionMedium.Road))
        city.avoidGrowth=false;city.population.foodStored=city.population.getFoodToNextPopulation()
        city.population.nextTurn(1);assertTrue(city.population.population>base);assertTrue(complete(f,"F09"))
    }
    @Test fun romanProductionNeedsAnActualBonusTickInTwoDistinctCities() {
        val f=fixture();val first=f.city(0,0);val second=f.city(4,0)
        f.capital.cityConstructions.addBuilding("Monument")
        for(city in listOf(first,second)) {
            city.cityConstructions.removeAll();city.cityConstructions.addToQueue("Monument")
            city.cityStats.update()
            city.cityConstructions.endTurn(city.cityStats.currentCityStats)
            assertTrue(city.id in f.history.extended.romanProduction)
            repeat(80) {
                if (!city.cityConstructions.isBuilt("Monument")) {
                    city.cityStats.update();city.cityConstructions.endTurn(city.cityStats.currentCityStats)
                    city.cityConstructions.constructIfEnough()
                }
            }
            assertTrue(city.cityConstructions.isBuilt("Monument"))
        }
        assertTrue(complete(f,"R28"))
    }
    @Test fun freeCompletionCannotReuseEarlierRealRomanProductionEvidence() {
        val f=fixture();val city=f.city(0,0);val building=f.test.ruleset.buildings.getValue("Monument")
        f.capital.cityConstructions.addBuilding("Monument")
        city.cityConstructions.removeAll();city.cityConstructions.addToQueue("Monument")
        city.cityStats.update();city.cityConstructions.endTurn(city.cityStats.currentCityStats)
        assertTrue("Monument" in f.history.extended.romanProduction.getValue(city.id))
        f.player.civConstructions.addFreeBuildings(building,f.player.cities.size)
        assertTrue(city.cityConstructions.isBuilt("Monument"))
        assertTrue(f.history.extended.romanCities.isEmpty())
        assertFalse("Monument" in f.history.extended.romanProduction[city.id].orEmpty())
    }
    @Test fun overflowWithoutBonusProductionDoesNotCountAsRomanProduction() {
        val f=fixture();val city=f.city(0,0);f.capital.cityConstructions.addBuilding("Monument")
        city.cityConstructions.removeAll();city.cityConstructions.addToQueue("Monument")
        city.cityConstructions.productionOverflow=1000;city.cityConstructions.endTurn(Stats())
        city.cityConstructions.completeConstruction(f.test.ruleset.buildings.getValue("Monument"))
        assertTrue(f.history.extended.romanCities.isEmpty())
    }
    @Test fun chuKoNuCountsAttacksRatherThanKillsAndWaitsForSurvival() {
        val f=fixture("China");war(f);val unit=f.unit("Chu-Ko-Nu",0,0);val target=f.unit("Giant Death Robot",1,0,f.opponents[0])
        repeat(2) { unit.currentMovement=2f;Battle.attack(MapUnitCombatant(unit),MapUnitCombatant(target)) }
        assertFalse(target.isDestroyed);assertFalse(complete(f,"R34"));assertEquals(2,f.history.extended.chuKoNu.getValue(unit.id.toString()).attacks)
        next(f);assertTrue(complete(f,"R34"))
    }
    @Test fun giftAndReturnBreaksAnAttackSurvivalWindow() {
        val f=fixture("China");war(f);val unit=f.unit("Chu-Ko-Nu",0,0);val target=f.unit("Giant Death Robot",1,0,f.opponents[0])
        repeat(2){unit.currentMovement=2f;Battle.attack(MapUnitCombatant(unit),MapUnitCombatant(target))}
        unit.gift(f.opponents[1]);unit.gift(f.player);next(f);assertFalse(complete(f,"R34"))
    }
    @Test fun keshikNeedsVoluntaryMovementAfterItsAttackAndThenSurvival() {
        val f=fixture("Mongolia");war(f);val unit=f.unit("Keshik",0,0);val target=f.unit("Spearman",1,0,f.opponents[0])
        Battle.attack(MapUnitCombatant(unit),MapUnitCombatant(target));unit.currentMovement=5f
        unit.movement.moveToTile(f.test.getTile(0,-1));assertTrue(f.history.extended.keshik.getValue(unit.id.toString()).moved)
        assertFalse(complete(f,"F14"));next(f);assertTrue(complete(f,"F14"))
    }
    @Test fun terracesRequireTheirRealBuilderAndAnActualFoodSettlement() {
        val f=fixture("Inca");val city=f.city(0,0);val tile=f.test.getTile(1,0)
        city.expansion.takeOwnership(tile);tile.neighbors.take(2).forEach{it.baseTerrain="Mountain";it.setTerrainTransients()}
        tile.baseTerrain="Hill";tile.setTerrainTransients();val worker=f.unit("Worker",1,0)
        tile.improvementFunctions.setImprovement("Terrace farm",f.player,worker)
        city.population.setPopulation(3);city.workTile(tile);assertFalse(complete(f,"F13"))
        city.population.nextTurn(1);assertTrue(complete(f,"F13"))
    }
    @Test fun firstTerminalLedgerDeduplicatesReloadsLossesAndTimeAndSurvivesRestart() {
        val service=AchievementService(temporary.root)
        val first=fixture();service.record(first.game,emptySet(),"Time",terminal=true,playerWon=true)
        service.record(first.game,emptySet(),"Scientific",terminal=true,playerWon=true)
        assertTrue(service.handbookSnapshot().victoryTypes().isEmpty())
        val lost=fixture();service.record(lost.game,emptySet(),"Cultural",terminal=true,playerWon=false)
        service.record(lost.game,emptySet(),"Cultural",terminal=true,playerWon=true)
        assertTrue(service.handbookSnapshot().victoryTypes().isEmpty())
        for(route in listOf("Scientific","Cultural","Domination")) service.record(fixture().game,emptySet(),route,terminal=true,playerWon=true)
        assertTrue("F15" in service.completed());assertEquals(5,service.handbookSnapshot().firstTerminals.size)
        assertTrue("F15" in AchievementService(temporary.root).completed())
    }
    @Test fun zeroDamageAndDeathCannotSatisfyAnAttackSurvivalWindow() {
        val f=fixture("Mongolia");war(f);val unit=f.unit("Keshik",0,0);val target=f.unit("Giant Death Robot",1,0,f.opponents[0])
        AchievementExtendedRules.activeAttack(MapUnitCombatant(unit),MapUnitCombatant(target),0)
        assertTrue(f.history.extended.keshik.isEmpty())
        AchievementExtendedRules.activeAttack(MapUnitCombatant(unit),MapUnitCombatant(target),1)
        unit.currentMovement=5f;unit.movement.moveToTile(f.test.getTile(0,-1));unit.destroy()
        next(f);assertFalse(complete(f,"F14"))
    }
    @Test fun previewTileCannotEraseTheRealTerraceProvenance() {
        val f=fixture("Inca");val city=f.city(0,0);val tile=f.test.getTile(1,0)
        city.expansion.takeOwnership(tile);tile.neighbors.take(2).forEach{it.baseTerrain="Mountain";it.setTerrainTransients()}
        tile.baseTerrain="Hill";tile.setTerrainTransients();val worker=f.unit("Worker",1,0)
        tile.improvementFunctions.setImprovement("Terrace farm",f.player,worker)
        val key=AchievementGameState.tileKey(tile);assertTrue(key in f.history.extended.terraces)
        val preview=tile.clone();preview.tileMap=tile.tileMap
        AchievementExtendedRules.improvementChanged(preview);assertTrue(key in f.history.extended.terraces)
        AchievementExtendedRules.improvementChanged(tile);assertFalse(key in f.history.extended.terraces)
    }
    @Test fun failedTerminalCommitCanBeRetriedWithoutInventingAPermanentVictory() {
        val service=AchievementService(temporary.root);val f=fixture()
        val directory=java.io.File(temporary.root,"unbound")
        directory.mkdirs();val block=java.io.File(directory,"pending.tmp");block.mkdirs()
        assertTrue(runCatching{service.record(f.game,emptySet(),"Scientific",terminal=true,playerWon=true)}.isFailure)
        assertTrue(service.handbookSnapshot().firstTerminals.isEmpty())
        block.delete();service.record(f.game,emptySet(),"Scientific",terminal=true,playerWon=true)
        assertEquals(setOf("Scientific"),AchievementService(temporary.root).handbookSnapshot().victoryTypes())
    }
    /** Export only an isolated QA save, never permanent awards. The native app must perform the final production. */
    @Test fun romanNativeFixtureUsesNormalProductionAndSerializesItsRealOneOfTwoProgress() {
        val f=fixture();val first=f.city(0,0);val second=f.city(4,0)
        f.game.gameParameters.noBarbarians=true
        for(civ in f.game.civilizations) {
            civ.tech.addTechnology("Currency");civ.tech.techsToResearch.add("Civil Service")
            civ.cities.forEach{it.cityConstructions.removeAll();it.cityConstructions.addToQueue("Gold")}
        }
        f.capital.cityConstructions.addBuilding("Monument")
        first.cityConstructions.removeAll();first.cityConstructions.addToQueue("Monument")
        repeat(80) {
            if(!first.cityConstructions.isBuilt("Monument")) {
                first.cityStats.update();first.cityConstructions.endTurn(first.cityStats.currentCityStats)
                first.cityConstructions.constructIfEnough()
            }
        }
        assertEquals(setOf(first.id),f.history.extended.romanCities)
        first.cityConstructions.removeAll();first.cityConstructions.addToQueue("Gold")
        f.game.turns=10
        for(civ in f.game.civilizations) {civ.popupAlerts.clear();civ.notifications.clear()}
        second.cityConstructions.removeAll();second.cityConstructions.addToQueue("Monument")
        val building=f.test.ruleset.buildings.getValue("Monument")
        second.cityConstructions.inProgressConstructions["Monument"]=building.getProductionCost(f.player,second)-1
        for(civ in f.game.civilizations) {civ.cache.updateViewableTiles();civ.cities.forEach{it.cityStats.update()}}
        val serialized=json().toJson(f.game)
        val reloaded=json().fromJson(com.unciv.logic.GameInfo::class.java,serialized);reloaded.setTransients()
        assertEquals(1,reloaded.achievements!!.history.extended.romanCities.size)
        assertFalse("R28" in reloaded.achievements!!.history.completed)
        System.getenv("UNCIV_ACHIEVEMENT_QA_DIR")?.let {
            val out=java.io.File(it);out.mkdirs();java.io.File(out,"Rome-${base.name}.json").writeText(serialized)
        }
    }

    @Test fun newFactsCloneWithoutSharingMutableEvidenceAndV3CannotUseThem() {
        val f=fixture();f.history.extended.romanCities.add("one")
        val clone=f.state.clone();clone.history.extended.romanCities.add("two")
        assertEquals(setOf("one"),f.history.extended.romanCities)
        f.state.recordingVersion=3;f.history.completed.add("R28");assertFalse(complete(f,"R28"))
        assertFalse("F04" in AchievementCatalog.reportableIds);assertTrue("F04" in AchievementCatalog.knownIds)
    }
}
