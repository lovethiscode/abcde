package com.unciv.logic.achievements

import com.unciv.UncivGame
import com.unciv.logic.battle.Battle
import com.unciv.logic.battle.CityCombatant
import com.unciv.logic.battle.MapUnitCombatant
import com.unciv.logic.city.City
import com.unciv.logic.civilization.managers.TurnManager
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.RoadStatus
import com.unciv.logic.trade.TradeLogic
import com.unciv.logic.trade.TradeOffer
import com.unciv.logic.trade.TradeOfferType
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.BeliefType
import com.unciv.models.ruleset.tile.ResourceType
import com.unciv.logic.map.mapunit.actions.UnitActions
import com.unciv.models.UnitActionType
import com.unciv.testing.RedirectOutput
import com.unciv.testing.RedirectPolicy
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Controlled engine integration: resources, populations, technology, positions and target health are fixtures.
 * Awards, source history, victory results and collection IDs are never injected. */
@RunWith(org.junit.runners.Parameterized::class)
@org.junit.runners.Parameterized.UseParametersRunnerFactory(com.unciv.testing.TestRunnerFactory::class)
class AchievementReachabilityTest(private val base: BaseRuleset, private val speed: String) {
    companion object {
        @JvmStatic @org.junit.runners.Parameterized.Parameters(name = "{0}, {1}")
        fun parameters() = BaseRuleset.entries.flatMap { base ->
            listOf("Quick", "Standard", "Epic", "Marathon").map { arrayOf<Any>(base, it) }
        }
    }
    @get:org.junit.Rule val temporary = org.junit.rules.TemporaryFolder()
    @After fun reset() { AchievementTracker.service = null }
    private fun fixture(nation: String = "Rome", religion: Boolean = false, oneCity: Boolean = false) =
        AchievementTestFixture(nation, if (religion) BaseRuleset.Civ_V_GnK else base, speed,
            difficulty = "Deity", oneCityChallenge = oneCity).also {
            UncivGame.Current.settings.tutorialTasksCompleted.addAll(listOf("Conquer a city", "Construct an improvement"))
            it.player.addGold(100000)
        }

    private fun endTurn(f: AchievementTestFixture) {
        // Keep the threshold populations alive while the actual end-turn economy is evaluated.
        f.player.cities.forEach { it.population.foodStored = 1000 }
        TurnManager(f.player).endTurn()
    }

    private fun policies(f: AchievementTestFixture, names: List<String>) {
        f.player.policies.freePolicies = 100
        for (name in names) {
            val branch = f.test.ruleset.policyBranches.getValue(name)
            f.player.policies.adopt(branch)
            branch.policies.dropLast(1).forEach { f.player.policies.adopt(it) }
        }
    }

    private fun capture(f: AchievementTestFixture, unit: MapUnit, city: City) {
        val enemy = city.civ
        f.player.diplomacyFunctions.makeCivilizationsMeet(enemy)
        if (!f.player.isAtWarWith(enemy)) f.player.getDiplomacyManager(enemy)!!.declareWar()
        val adjacent = city.getCenterTile().neighbors.first { it.isLand && !it.isCityCenter() && it.militaryUnit == null }
        unit.removeFromTile()
        unit.putInTile(adjacent)
        unit.health = 100
        unit.currentMovement = unit.getMaxMovement().toFloat()
        assertEquals(0, unit.attacksThisTurn)
        city.health = 1
        Battle.attack(MapUnitCombatant(unit), CityCombatant(city))
        if (city.civ != f.player) city.puppetCity(f.player)
        assertEquals(f.player, city.civ)
        assertTrue(AchievementTracker.cityKey(city) in f.history.foreignCaptures)
    }

    @Test @RedirectOutput(RedirectPolicy.Show)
    fun allFortyReachDurableRecordsThroughEngineEventsAndActualTurnOrVictorySettlement() {
        val directory = temporary.newFolder()
        val service = AchievementService(directory)
        AchievementTracker.service = service
        fun earned(vararg ids: String) {
            val reloaded = AchievementService(directory).completed()
            for (id in ids) {
                assertTrue("$base/$speed $id missing from live service", id in service.completed())
                assertTrue("$base/$speed $id missing after reopening the disk profile", id in reloaded)
                println("REACHABLE $id engine -> tracker -> service -> disk -> reload ($base/$speed)")
            }
        }

        val civic = fixture()
        policies(civic, listOf("Tradition", "Liberty"))
        earned("N03", "N22")
        civic.capital.population.setPopulation(25)
        for (name in listOf("Library", "University")) civic.capital.cityConstructions.addBuilding(name)
        endTurn(civic)
        earned("N11", "N21")
        val cities = listOf(civic.capital, civic.city(-4, 0, 20), civic.city(0, 0, 20), civic.city(4, 0, 20))
        earned("N02")
        civic.test.ruleset.technologies.keys.forEach { civic.player.tech.addTechnology(it) }
        for (city in cities) {
            for (name in listOf("Monument", "Granary", "Library", "Barracks", "Market", "Bank", "Colosseum", "Theatre", "Stadium"))
                city.cityConstructions.addBuilding(civic.player.getEquivalentBuilding(name))
        }
        // A legal source of empire happiness: connect every ordinary luxury and provide city buildings.
        val luxuries = civic.test.ruleset.tileResources.values.filter { it.resourceType == ResourceType.Luxury && it.terrainsCanBeFoundOn.isNotEmpty() }
        val tiles = civic.game.tileMap.values.filter { it.getOwner() == civic.player && !it.isCityCenter() }.take(luxuries.size)
        assertEquals(luxuries.size, tiles.size)
        for ((resource, tile) in luxuries.zip(tiles)) {
            tile.setTileResource(resource)
            tile.setImprovement(resource.getImprovements().first(), civic.player)
        }
        civic.player.cache.updateCivResources()
        civic.game.tileMap.values.forEach { it.setRoadStatus(RoadStatus.Road, civic.player) }
        civic.player.cache.updateCitiesConnectedToCapital()
        endTurn(civic)
        assertTrue("Happiness after real economy: ${civic.player.getHappiness()}", civic.player.getHappiness() >= 10)
        earned("N10", "N12", "N29")
        val fifth = civic.city(8, 0, 10)
        for (name in listOf("Monument", "Granary", "Library", "Barracks")) fifth.cityConstructions.addBuilding(name)
        endTurn(civic)
        earned("N28")

        val groups = civic.test.ruleset.nations.values.filter { it.isCityState }.groupBy { it.cityStateType }.values
        val nations = (groups.map { it.first() } + groups.flatMap { it.drop(1) }).take(5)
        for ((i, nation) in nations.withIndex()) {
            val ally = civic.test.addCiv(nation)
            civic.city(-8 + i * 4, 4, civ = ally)
            civic.player.diplomacyFunctions.makeCivilizationsMeet(ally)
            ally.getDiplomacyManager(civic.player)!!.setInfluence(1000f)
        }
        endTurn(civic)
        earned("N13", "N23", "N25")
        val worker = civic.unit("Worker", 0, -4)
        worker.currentTile.queueImprovement("Farm", 1)
        assertTrue(worker.currentTile.doWorkerTurn(worker))
        earned("N09")
        for (other in civic.opponents.take(3)) {
            civic.player.diplomacyFunctions.makeCivilizationsMeet(other)
            val trade = TradeLogic(civic.player, other)
            trade.currentTrade.ourOffers.add(TradeOffer("Gold", TradeOfferType.Gold, amount = 1, speed = civic.game.speed))
            trade.acceptTrade(applyGifts = false)
        }
        earned("N15")
        for ((i, name) in listOf("Mount Fuji", "Barringer Crater", "Grand Mesa").withIndex()) {
            val tile = civic.test.getTile(i * 2, -4)
            tile.naturalWonder = name
            tile.setTerrainTransients()
            civic.player.viewableTiles = hashSetOf(tile)
            civic.player.cache.discoverNaturalWonders()
        }
        earned("N16")
        val scout = civic.unit("Warrior", -4, -4)
        for (x in -3..-1) {
            val tile = civic.test.getTile(x, -4)
            tile.setImprovement("Barbarian encampment")
            scout.currentMovement = 10f
            scout.movement.moveToTile(tile)
        }
        earned("N07")
        val veteran = civic.unit("Swordsman", 0, 2)
        civic.earnCombatExperience(veteran, 150)
        for (name in listOf("Shock I", "Shock II", "Shock III", "Drill I", "Drill II")) veteran.promotions.addPromotion(name)
        earned("N06", "N31")
        civic.nextTurn()
        for ((i, position) in listOf(2 to -2, 6 to -2, 10 to 2).withIndex()) {
            val (x, y) = position
            val captor = if (i == 0) veteran else civic.unit("Swordsman", x, y - 1)
            capture(civic, captor, civic.city(x, y, 6, civic.opponents[i % 2]))
        }
        earned("N08", "N32")

        val egypt = fixture("Egypt")
        val second = egypt.city(0, 0)
        egypt.player.goldenAges.enterGoldenAge()
        val wonders = listOf("The Great Library", "Stonehenge", "The Oracle", "The Pyramids", "Great Wall", "Angkor Wat", "Hagia Sophia", "Chichen Itza")
        for (name in wonders) egypt.capital.cityConstructions.completeConstruction(egypt.test.ruleset.buildings.getValue(name))
        second.cityConstructions.completeConstruction(egypt.test.ruleset.buildings.getValue("Notre Dame"))
        earned("N04", "N05", "N27", "N30")
        assertFalse("N26" in service.completed())

        val persia = fixture("Persia")
        for (name in listOf("Taj Mahal", "The Louvre", "Big Ben"))
            persia.capital.cityConstructions.completeConstruction(persia.test.ruleset.buildings.getValue(name))
        persia.player.goldenAges.enterGoldenAge()
        for ((i, x) in listOf(-4, 0, 4, 8).withIndex()) {
            val unit = persia.unit("Swordsman", x, -1)
            capture(persia, unit, persia.city(x, 0, 6, persia.opponents[i % 2]))
        }
        earned("N35")

        val china = fixture("China")
        china.capital.cityConstructions.completeConstruction(china.test.ruleset.buildings.getValue("The Great Library"))
        earned("N26")
        val crossbow = china.unit("Chu-Ko-Nu", 0, 0)
        china.earnCombatExperience(crossbow, 100)
        for (name in listOf("Accuracy I", "Accuracy II", "Accuracy III", "Barrage I")) crossbow.promotions.addPromotion(name)
        china.nextTurn()
        china.kill(crossbow, 1, 0)
        china.kill(crossbow, 0, 1)
        endTurn(china)
        earned("N34")

        // Vanilla profiles can collect religious medals by starting an eligible G&K game.
        val faith = fixture(religion = true)
        val manager = faith.player.religionManager
        val beliefs = faith.test.ruleset.beliefs.values
        manager.chooseBeliefs(listOf(beliefs.first { it.type == BeliefType.Pantheon }), useFreeBeliefs = true)
        val prophet = faith.unit("Great Prophet", -8, -8)
        manager.foundReligion(prophet)
        manager.chooseBeliefs(listOf(beliefs.first { it.type == BeliefType.Founder }))
        manager.useProphetForEnhancingReligion(prophet)
        manager.chooseBeliefs(listOf(beliefs.first { it.type == BeliefType.Enhancer }))
        val own = listOf(faith.capital) + listOf(-8, -4, 0, 4, 8, -8, -4, 0, 4).mapIndexed { i, x -> faith.city(x, if (i < 5) 0 else 4, 10) }
        val foreign = faith.opponents.take(3).map { it.getCapital()!! } + listOf(faith.city(-4, -4, 10, faith.opponents[0]), faith.city(0, -4, 10, faith.opponents[1]))
        for (city in own + foreign) {
            city.population.setPopulation(10)
            city.religion.addPressure(manager.religion!!.name, 100000)
        }
        endTurn(faith)
        earned("N14", "N24", "N33")

        val science = fixture(oneCity = true)
        science.capital.cityConstructions.completeConstruction(science.test.ruleset.buildings.getValue("Apollo Program"))
        for (name in science.test.ruleset.victories.getValue("Scientific").requiredSpaceshipParts) {
            if (name in science.test.ruleset.units) {
                val part = science.unit(name, -8, -8)
                assertTrue(UnitActions.invokeUnitAction(part, UnitActionType.AddInCapital))
            } else science.capital.cityConstructions.completeConstruction(science.test.ruleset.buildings.getValue(name))
        }
        endTurn(science)
        assertEquals("Scientific", science.game.victoryData!!.victoryType)
        earned("N01", "N17", "N36", "N37", "N38")

        val culture = fixture()
        policies(culture, listOf("Tradition", "Liberty", "Honor", "Piety", "Patronage"))
        culture.capital.cityConstructions.completeConstruction(culture.test.ruleset.buildings.getValue("Utopia Project"))
        endTurn(culture)
        assertEquals("Cultural", culture.game.victoryData!!.victoryType)
        earned("N18", "N39")

        val conquest = fixture()
        for (enemy in conquest.opponents) {
            val city = enemy.getCapital()!!
            val unit = conquest.unit("Swordsman", city.location.x.toInt() - 1, city.location.y.toInt())
            capture(conquest, unit, city)
        }
        endTurn(conquest)
        assertEquals("Domination", conquest.game.victoryData!!.victoryType)
        earned("N19")

        val diplomacy = fixture()
        diplomacy.capital.cityConstructions.completeConstruction(diplomacy.test.ruleset.buildings.getValue("United Nations"))
        for (civ in diplomacy.game.civilizations) civ.diplomaticVoteForCiv(diplomacy.player.civID)
        diplomacy.game.processDiplomaticVictory()
        endTurn(diplomacy)
        assertEquals("Diplomatic", diplomacy.game.victoryData!!.victoryType)
        earned("N20", "N40")
        assertEquals(AchievementCatalog.reportableIds, AchievementService(directory).completed())
        val profile = AchievementStore(java.io.File(directory, "unbound")).load()
        assertEquals(40, profile.unlocks.size)
        assertTrue(profile.v2BuiltWonders.size >= 12)
        assertTrue(profile.wonderGameIds.size >= 3)
    }
}
