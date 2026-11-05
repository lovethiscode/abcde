package com.unciv.logic.achievements

import com.unciv.logic.map.mapunit.PossibleUpgrade
import com.unciv.logic.map.mapunit.UpgradeAvailability
import com.unciv.models.Counter

import com.unciv.json.json
import com.unciv.logic.battle.Battle
import com.unciv.logic.battle.MapUnitCombatant
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.HexCoord
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.models.metadata.BaseRuleset
import com.unciv.testing.TestGame
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

internal class AchievementTestFixture(nation: String = "Rome", baseRuleset: BaseRuleset = BaseRuleset.Civ_V_GnK,
                                      speed: String = "Standard", aiCount: Int = 4, difficulty: String = "Emperor",
                                      oneCityChallenge: Boolean = false) {
    val test = TestGame(baseRuleset = baseRuleset).apply {
        setSpeed(speed); setDifficulty(difficulty); makeHexagonalMap(12)
        gameInfo.gameParameters.oneCityChallenge = oneCityChallenge
    }
    val game = test.gameInfo
    val player = test.addCiv(test.ruleset.nations.getValue(nation), isPlayer = true)
    val opponents = listOf("Greece", "China", "Egypt", "Rome", "America").filter { it != nation }.take(aiCount)
        .map { test.addCiv(test.ruleset.nations.getValue(it)) }
    val capital = test.addCity(player, test.getTile(-8, -8))
    val state: AchievementGameState
    val history get() = state.history
    init {
        // Engine fixtures do not initialize an application settings directory.
        com.unciv.UncivGame.Current.settings.tutorialTasksCompleted.add("Meet another civilization")
        opponents.forEachIndexed { i, civ -> test.addCity(civ, test.getTile(8, 5 + i * 2)) }
        game.currentPlayer = player.civID
        game.currentPlayerCiv = player
        game.gameParameters.victoryTypes.addAll(AchievementCatalog.victoryRoutes)
        game.gameParameters.baseRuleset = baseRuleset.fullName
        state = AchievementGameState.create(game, true, false)!!.apply {
            recordingVersion = AchievementCatalog.firstCompleteRecordingVersion
            initializationComplete = true
        }
        game.achievements = state
        game.getCities().forEach { AchievementTracker.cityFounded(it) }
    }
    fun unit(name: String, x: Int, y: Int, civ: Civilization = player): MapUnit =
        civ.units.placeUnitNearTile(HexCoord(x, y), test.ruleset.units.getValue(name))!!
    fun city(x: Int, y: Int, population: Int = 1, civ: Civilization = player): City =
        test.addCity(civ, test.getTile(x, y), initialPopulation = population)
    fun kill(attacker: MapUnit, x: Int, y: Int, civ: Civilization = opponents[0]) {
        val victim = unit("Warrior", x, y, civ).apply { health = 1 }
        if (!player.isAtWarWith(civ)) {
            player.diplomacyFunctions.makeCivilizationsMeet(civ)
            player.getDiplomacyManager(civ)!!.declareWar()
        }
        attacker.currentMovement = attacker.getMaxMovement().toFloat()
        Battle.attack(MapUnitCombatant(attacker), MapUnitCombatant(victim))
    }
    fun results(end: Boolean = false, route: String? = null) =
        AchievementRules.evaluate(game, end, player.takeIf { route != null }, route)
    fun earnCombatExperience(unit: MapUnit, amount: Int) {
        while (history.unit(unit.id).combatExperience < amount) {
            nextTurn()
            unit.health = 100
            kill(unit, 1, 1)
        }
    }
    fun nextTurn() {
        game.turns++
        AchievementTracker.startTurn(player)
        player.units.getCivUnits().forEach { it.currentMovement = it.getMaxMovement().toFloat(); it.attacksThisTurn = 0 }
    }
}

@RunWith(org.junit.runners.Parameterized::class)
@org.junit.runners.Parameterized.UseParametersRunnerFactory(com.unciv.testing.TestRunnerFactory::class)
class AchievementEngineTest(private val baseRuleset: com.unciv.models.metadata.BaseRuleset) {
    companion object {
        @JvmStatic @org.junit.runners.Parameterized.Parameters(name = "{0}")
        fun parameters() = com.unciv.models.metadata.BaseRuleset.entries.map { arrayOf(it) }
    }
    private fun fixture(nation: String = "Rome") = AchievementTestFixture(nation, baseRuleset)

    @get:org.junit.Rule val temporary = org.junit.rules.TemporaryFolder()
    @After fun clearService() { AchievementTracker.service = null }

    @Test fun firstExpansionRequiresASecondFoundedCityRatherThanAnAcquisition() {
        val f = fixture()
        assertFalse("N02" in f.results())
        val foreign = f.city(0, 0, civ = f.opponents[0])
        foreign.moveToCiv(f.player)
        assertFalse("N02" in f.results())
        f.city(4, 0)
        assertTrue("N02" in f.results())
        assertEquals(2, f.history.foundedCities.size)
    }

    @Test fun policyOpenerAndFullBranchUseCompletedAdoptionIncludingFreePolicies() {
        val f = fixture()
        val branch = f.test.ruleset.policyBranches.getValue("Tradition")
        f.player.policies.freePolicies = 30
        assertFalse("N03" in f.results())
        f.player.policies.adopt(branch)
        assertTrue("N03" in f.results())
        assertFalse("N22" in f.results())
        for (policy in branch.policies.dropLast(1)) f.player.policies.adopt(policy)
        assertFalse("N22" in f.results())
        val second = f.test.ruleset.policyBranches.getValue("Liberty")
        f.player.policies.adopt(second)
        for (policy in second.policies.dropLast(1)) f.player.policies.adopt(policy)
        assertTrue("N22" in f.results())
    }

    @Test fun goldenAgeEventsWaitForOuterActionAndPersistWithoutRequiringVictory() {
        val f = fixture()
        val directory = temporary.newFolder()
        val service = AchievementService(directory)
        AchievementTracker.service = service
        val second = f.city(0, 0)
        AchievementTracker.beginAction(f.game)
        f.player.goldenAges.enterGoldenAge()
        f.capital.cityConstructions.completeConstruction(f.test.ruleset.buildings.getValue("The Great Library"))
        second.cityConstructions.completeConstruction(f.test.ruleset.buildings.getValue("The Oracle"))
        assertFalse("N04" in service.completed())
        AchievementTracker.endAction(f.game)
        assertTrue("N04" in service.completed())
        assertNull(f.game.victoryData)
        assertTrue("N04" in service.pending())
    }

    @Test fun worldsWondersCountActualCompletionIncludingProductionInAcquiredCities() {
        val f = fixture("Egypt")
        val acquired = f.city(0, 0, civ = f.opponents[0])
        acquired.cityConstructions.addBuilding("The Pyramids")
        acquired.moveToCiv(f.player)
        assertFalse("N05" in f.results())
        f.capital.cityConstructions.completeConstruction(f.test.ruleset.buildings.getValue("National College"))
        assertFalse("N05" in f.results())
        for (name in listOf("Stonehenge", "The Great Library", "The Oracle", "The Great Lighthouse", "Colossus"))
            acquired.cityConstructions.completeConstruction(f.test.ruleset.buildings.getValue(name))
        assertTrue("N05" in f.results())
        assertTrue("N27" in f.results())
        assertEquals(5, f.history.builtWonders.size)
        assertNull(f.game.victoryData)
    }

    @Test fun majorMilitaryAndBarbarianKillsUnlockButCivilianCaptureDoesNot() {
        val f = fixture()
        val unit = f.unit("Swordsman", 0, 0)
        val enemy = f.opponents[0]
        f.player.diplomacyFunctions.makeCivilizationsMeet(enemy)
        f.player.getDiplomacyManager(enemy)!!.declareWar()
        val worker = f.unit("Worker", 1, 0, enemy)
        Battle.attack(MapUnitCombatant(unit), MapUnitCombatant(worker))
        assertFalse("N06" in f.results())
        f.kill(unit, 2, 0)
        assertTrue("N06" in f.results())
        val second = fixture()
        val barbarian = second.test.addBarbarianCiv()
        val attacker = second.unit("Swordsman", 0, 0)
        val victim = second.unit("Warrior", 1, 0, barbarian).apply { health = 1 }
        Battle.attack(MapUnitCombatant(attacker), MapUnitCombatant(victim))
        assertTrue("N06" in second.results())
    }

    @Test fun threeEncampmentsNeedTheSameLivingUnitAndDifferentLocations() {
        val f = fixture()
        val unit = f.unit("Warrior", 0, 0)
        for (x in 1..3) {
            val tile = f.test.getTile(x, 0)
            tile.setImprovement("Barbarian encampment")
            unit.currentMovement = 10f
            unit.movement.moveToTile(tile)
            assertFalse(tile.isBarbarianEncampment())
            assertEquals(x == 3, "N07" in f.results())
        }
        assertEquals(3, f.history.unit(unit.id).clearedEncampments.size)
        val other = fixture()
        val scout = other.unit("Warrior", 0, 0)
        val tile = other.test.getTile(1, 0)
        repeat(3) { AchievementTracker.encampmentCleared(scout, tile) }
        assertFalse("N07" in other.results())
        assertEquals(1, other.history.unit(scout.id).clearedEncampments.size)
    }

    @Test fun workerCompletionExcludesRepairClonesAndPreviouslyOwnedImprovements() {
        for (name in listOf("Farm", "Mine", "Pasture")) {
            val f = fixture()
            val tile = f.test.getTile(0, 0)
            val worker = f.unit("Worker", 0, 0)
            tile.setImprovement(name, f.player)
            assertFalse("N09" in f.results())
            tile.improvementIsPillaged = true
            tile.setImprovement("Repair", f.player, worker)
            assertFalse("N09" in f.results())
            tile.clone().setImprovement(name, f.player, worker)
            assertFalse("N09" in f.results())
            tile.removeImprovement()
            com.unciv.UncivGame.Current.settings.tutorialTasksCompleted.add("Construct an improvement")
            tile.queueImprovement(name, 2)
            assertFalse(tile.doWorkerTurn(worker))
            assertFalse("N09" in f.results())
            assertTrue(tile.doWorkerTurn(worker))
            assertTrue(name, "N09" in f.results())
        }
    }

    @Test fun naturalWondersRequireThreeDifferentDiscoveriesAndCannotCountUnseenTiles() {
        val f = fixture()
        for ((index, name) in listOf("Mount Fuji", "Barringer Crater", "Grand Mesa").withIndex()) {
            val tile = f.test.getTile(index * 2, 0)
            tile.naturalWonder = name
            tile.setTerrainTransients()
            assertFalse("N16" in f.results())
            f.player.viewableTiles = hashSetOf(tile)
            f.player.cache.discoverNaturalWonders()
            assertEquals(index == 2, "N16" in f.results())
        }
    }

    @Test fun completedTradesIncludeGoldIncomeResourcesAndOpenBordersInEitherDirection() {
        val offers = listOf(
            "Gold" to com.unciv.logic.trade.TradeOfferType.Gold,
            "Gold per turn" to com.unciv.logic.trade.TradeOfferType.Gold_Per_Turn,
            "Silk" to com.unciv.logic.trade.TradeOfferType.Luxury_Resource,
            "Iron" to com.unciv.logic.trade.TradeOfferType.Strategic_Resource,
            "Open Borders" to com.unciv.logic.trade.TradeOfferType.Agreement
        )
        for ((name, type) in offers) for (reverse in listOf(false, true)) {
            val f = fixture()
            for ((index, other) in f.opponents.take(3).withIndex()) {
            f.player.diplomacyFunctions.makeCivilizationsMeet(other)
            f.player.addGold(100)
            other.addGold(100)
            val trade = com.unciv.logic.trade.TradeLogic(if (reverse) other else f.player, if (reverse) f.player else other)
            trade.currentTrade.ourOffers.add(com.unciv.logic.trade.TradeOffer(name, type, amount = 1, speed = f.game.speed))
            assertFalse("N15" in f.results())
            trade.acceptTrade(applyGifts = false)
            assertEquals("$name reverse=$reverse index=$index", index == 2, "N15" in f.results())
            }
        }
    }

    @Test fun embassyOnlyAgreementDoesNotBecomeAQualifyingTrade() {
        val f = fixture()
        val other = f.opponents[0]
        f.player.diplomacyFunctions.makeCivilizationsMeet(other)
        val trade = com.unciv.logic.trade.TradeLogic(f.player, other)
        trade.currentTrade.ourOffers.add(com.unciv.logic.trade.TradeOffer("Embassy", com.unciv.logic.trade.TradeOfferType.Embassy, speed = f.game.speed))
        trade.acceptTrade(applyGifts = false)
        assertFalse("N15" in f.results())
    }

    @Test fun pantheonAndEnhancementRequireCompletingTheBeliefChoice() {
        val f = fixture()
        org.junit.Assume.assumeTrue(f.game.isReligionEnabled())
        val manager = f.player.religionManager
        val beliefs = f.test.ruleset.beliefs.values
        val pantheon = beliefs.first { it.type == com.unciv.models.ruleset.BeliefType.Pantheon }
        manager.chooseBeliefs(listOf(pantheon), useFreeBeliefs = true)
        assertFalse("N14" in f.results())
        assertFalse("N24" in f.results())
        val prophet = f.unit("Great Prophet", -8, -8)
        manager.foundReligion(prophet)
        assertEquals(com.unciv.logic.civilization.managers.ReligionState.FoundingReligion, manager.religionState)
        manager.chooseBeliefs(listOf(beliefs.first { it.type == com.unciv.models.ruleset.BeliefType.Founder }))
        val own = listOf(f.capital, f.city(-4, 0), f.city(0, 0))
        (own + f.opponents.take(3).map { it.getCapital()!! }).forEach {
            it.population.setPopulation(10)
            it.religion.addPressure(manager.religion!!.name, 100000)
        }
        assertTrue("N14" in f.results(end = true))
        manager.useProphetForEnhancingReligion(prophet)
        assertEquals(com.unciv.logic.civilization.managers.ReligionState.EnhancingReligion, manager.religionState)
        assertFalse("N24" in f.results())
        manager.chooseBeliefs(listOf(beliefs.first { it.type == com.unciv.models.ruleset.BeliefType.Enhancer }))
        assertTrue("N24" in f.results(end = true))
        assertTrue(manager.religion!!.isEnhancedReligion())
    }

    @Test fun fifthEarnedPromotionNeedsTheSameLivingMilitaryUnitAndSurvivesUpgrade() {
        val f = fixture()
        val unit = f.unit("Warrior", 0, 0)
        f.earnCombatExperience(unit, 150)
        unit.promotions.addPromotion("Drill I", isFree = true)
        val names = listOf("Shock I", "Shock II", "Shock III", "Drill II", "Drill III")
        for (name in names.take(4)) unit.promotions.addPromotion(name)
        assertFalse("N31" in f.results())
        unit.upgrade.performUpgrade(PossibleUpgrade(f.test.ruleset.units.getValue("Spearman"), null, false, UpgradeAvailability.AvailableForFree, 0, Counter()), isFree = true)
        val upgraded = f.player.units.getUnitById(unit.id)!!
        upgraded.promotions.addPromotion(names.last())
        assertTrue("N31" in f.results())
        assertTrue(f.history.unit(unit.id).majorMilitaryKills >= 10)
        assertEquals(5, f.history.units.getValue(unit.id.toString()).earnedPromotions)
        assertEquals(5, f.history.units.getValue(unit.id.toString()).combatPromotions)
        upgraded.destroy()
        assertTrue("N31" in f.results()) // Already earned medals remain unlocked.
    }

    @Test fun saveAndUndoDeepCopyV2EventsWithoutUpgradingLegacyMarkers() {
        val f = fixture()
        val branch = f.game.clone()
        val second = f.city(0, 0)
        f.player.goldenAges.enterGoldenAge()
        f.capital.cityConstructions.completeConstruction(f.test.ruleset.buildings.getValue("The Great Library"))
        second.cityConstructions.completeConstruction(f.test.ruleset.buildings.getValue("The Oracle"))
        assertEquals(1, branch.achievements!!.history.foundedCities.size)
        assertFalse("N04" in branch.achievements!!.history.completed)
        val restored = json().fromJson(AchievementGameState::class.java, json().toJson(f.state))
        assertEquals(f.history.foundedCities, restored.history.foundedCities)
        assertTrue("N04" in restored.history.completed)
        assertEquals(0, restored.actionDepth)
        f.state.recordingVersion = 1
        assertTrue(f.results().isEmpty())
    }
    @Test fun officialTimeVictoryTriggersFirstWinAtTheTurnLimit() {
        val f = AchievementTestFixture(baseRuleset = baseRuleset, aiCount = 1, difficulty = "Settler")
        val service = AchievementService(temporary.newFolder())
        AchievementTracker.service = service
        f.game.gameParameters.victoryTypes = arrayListOf("Time")
        f.game.gameParameters.maxTurns = 10
        assertEquals("Settler", f.state.difficulty)
        assertEquals(1, f.state.aiOpponents)
        assertEquals(1, f.game.civilizations.count { it.isAI() && it.isMajorCiv })
        f.state.enabledVictories = hashSetOf("Time")
        f.capital.population.setPopulation(30)
        f.game.turns = 9
        com.unciv.logic.civilization.managers.TurnManager(f.player).updateWinningCiv()
        assertNull(f.game.victoryData)
        assertFalse("N01" in service.completed())
        f.game.turns = 10
        com.unciv.logic.civilization.managers.TurnManager(f.player).updateWinningCiv()
        assertEquals("Time", f.game.victoryData!!.victoryType)
        assertTrue("N01" in service.completed())
        assertTrue(f.state.ended)
    }

    @Test fun fifthPromotionDoesNotQualifyAUnitKilledBeforeTheActionSettles() {
        val f = fixture()
        val unit = f.unit("Warrior", 0, 0)
        f.earnCombatExperience(unit, 150)
        for (name in listOf("Shock I", "Shock II", "Shock III", "Drill I")) unit.promotions.addPromotion(name)
        f.history.unit(unit.id).majorMilitaryKills = 10
        AchievementTracker.action(f.game) {
            unit.promotions.addPromotion("Drill II")
            unit.destroy()
        }
        assertFalse("N31" in f.results())
    }

}
