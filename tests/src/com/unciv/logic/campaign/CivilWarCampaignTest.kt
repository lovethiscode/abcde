package com.unciv.logic.campaign

import com.unciv.logic.map.mapunit.PossibleUpgrade
import com.unciv.logic.map.mapunit.UpgradeAvailability
import com.unciv.models.Counter

import com.badlogic.gdx.Gdx
import com.unciv.UncivGame
import com.unciv.logic.GameInfo
import com.unciv.logic.battle.BattleDamage
import com.unciv.logic.battle.CityCombatant
import com.unciv.logic.battle.MapUnitCombatant
import com.unciv.logic.city.CityFlags
import com.unciv.logic.files.UncivFiles
import com.unciv.logic.map.mapunit.UnitTurnManager
import com.unciv.logic.map.tile.RoadStatus
import com.unciv.logic.civilization.managers.TurnManager
import com.unciv.logic.trade.TradeLogic
import com.unciv.logic.trade.TradeOffer
import com.unciv.logic.trade.TradeOfferType
import com.unciv.models.ruleset.PerpetualConstruction
import com.unciv.models.ruleset.RulesetCache
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.stats.Stat
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import com.unciv.logic.map.mapunit.actions.UnitActionsFromUniques
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/** Rule integration tests use constructed states; these are not legal-route/balance acceptance. */
@RunWith(BaseTestRunner::class)
class CivilWarCampaignTest {
    @get:Rule val temporary = TemporaryFolder()
    @Before fun setup() { TestGame() }
    private fun game() = CampaignCatalog.newAttempt(CivilWarCampaign.id).also { UncivGame.Current.gameInfo = it }
    private fun city(g: GameInfo, id: String) = g.getCities().first { it.id == id }
    private fun store() = CampaignStore(UncivFiles(Gdx.files, temporary.root.path))

    @Test fun `research picker paths contain only the selected military improvement`() {
        val g = game()
        for (c in g.civilizations) {
            for (name in CivilWarCampaign.technologies) {
                assertEquals(listOf(name), c.tech.getRequiredTechsToDestination(g.ruleset.technologies.getValue(name)).map { it.name })
            }
            assertTrue(c.tech.getRequiredTechsToDestination(g.ruleset.technologies.getValue("Dynamite")).isEmpty())
        }
    }

    @Test fun `unfinished earlier branch replaces slot but delayed writes cannot undo it`() {
        val g = game(); val store = store()
        store.start(g, null)
        val earlier = g.clone().also { it.setTransients() }
        g.getCurrentPlayerCivilization().units.getCivUnits().first { it.isMilitary() }.disband()
        g.turns = 5
        g.campaign!!.completedRounds = 5
        store.save(g)
        val delayed = store.snapshot(g)
        store.save(earlier)
        store.saveSnapshot(delayed)
        val resumed = store.resume(CivilWarCampaign.id)
        assertEquals(g.gameId, resumed.gameId)
        assertEquals(0, resumed.turns)
        assertEquals(0, resumed.campaign!!.completedRounds)
        assertTrue(resumed.campaign!!.lostMilitary.isEmpty())
        assertTrue(resumed.campaign!!.lossReasons.isEmpty())
    }

    @Test fun `opening has seven stable cities exact assets and unique identities`() {
        val first = game(); val second = game()
        assertEquals(1280, first.tileMap.tileList.size)
        assertEquals(CivilWarCampaign.cities.keys, first.getCities().map { it.id }.toSet())
        val p = first.getCurrentPlayerCivilization(); val enemy = first.civilizations.single { it != p }
        assertEquals(15, p.units.getCivUnits().count())
        assertEquals(16, enemy.units.getCivUnits().count())
        assertEquals(mapOf("Rifleman" to 7, "Cavalry" to 2, "Cannon" to 3, "Great General" to 1, "Worker" to 2), p.units.getCivUnits().groupingBy { it.name }.eachCount())
        assertEquals(mapOf("Rifleman" to 9, "Cavalry" to 2, "Cannon" to 2, "Great General" to 1, "Worker" to 2), enemy.units.getCivUnits().groupingBy { it.name }.eachCount())
        assertEquals(31, first.civilizations.flatMap { it.units.getCivUnits().toList() }.map { it.id }.toSet().size)
        assertEquals(600, p.gold); assertEquals(450, enemy.gold)
        assertTrue(p.isAtWarWith(enemy)); assertFalse(first.isReligionEnabled())
        assertTrue(first.gameParameters.noCityRazing)
        assertTrue(first.gameParameters.mods.isEmpty()); assertTrue(first.gameParameters.victoryTypes.isEmpty())
        assertNotEquals(first.gameId, second.gameId)
        assertNotEquals(first.campaign!!.attemptId, second.campaign!!.attemptId)
        assertNotEquals(first.campaign!!.seed, second.campaign!!.seed)
        assertEquals("cw-washington", first.campaign!!.capitalId)
        assertFalse(CampaignRules.evaluate(first).main); assertFalse(CampaignRules.evaluate(first).failed)
        assertTrue(first.civilizations.all { it.stats.getUnitSupplyDeficit() == 0 })
        assertTrue(first.civilizations.all { it.getHappiness() in 12..18 })
        assertTrue(first.civilizations.all { it.getCivResourcesByName()["Horses"] == 4 })
    }

    @Test fun `four veteran units have one paid promotion and no extra xp`() {
        val units = game().civilizations.flatMap { it.units.getCivUnits().toList() }
        val expected = mapOf(1 to "Shock I", 7 to "Drill I", 16 to "Drill I", 22 to "Shock I")
        for (unit in units) {
            assertEquals(0, unit.promotions.XP)
            if (unit.id in expected) {
                assertEquals(setOf(expected[unit.id]), unit.promotions.promotions)
                assertEquals(1, unit.promotions.numberOfPromotions)
                assertEquals(20, unit.promotions.xpForNextPromotion())
            } else {
                assertEquals(0, unit.promotions.numberOfPromotions)
                assertEquals(10, unit.promotions.xpForNextPromotion())
            }
        }
    }

    @Test fun `only four improvements are researchable and no ordinary policy is adoptable`() {
        val g = game()
        for (c in g.civilizations) {
            assertEquals(CivilWarCampaign.technologies, g.ruleset.technologies.keys.filter { c.tech.canBeResearched(it) }.toSet())
            val before = c.tech.techsResearched.toSet()
            c.tech.addTechnology("Dynamite")
            assertEquals(before, c.tech.techsResearched)
            c.policies.freePolicies = 2
            assertFalse(c.policies.canAdoptPolicy())
            c.policies.adopt(g.ruleset.policyBranches.values.first())
            assertTrue(c.policies.getAdoptedPolicies().isEmpty())
            CivilWarCampaign.technologies.forEach { c.tech.addTechnology(it) }
            assertFalse(c.tech.canResearchTech())
            assertTrue(c.tech.allTechsAreResearched())
        }
        val base = RulesetCache[BaseRuleset.Civ_V_GnK.fullName]!!
        assertFalse("Military Transport" in base.technologies)
        assertEquals("Railroads", base.tileImprovements.getValue("Railroad").techRequired)
        assertTrue(base.specialists.getValue("Scientist").greatPersonPoints.isNotEmpty())
    }

    @Test fun `all purchases enforce unit ban building whitelist management prerequisites and funds`() {
        val g = game()
        for (c in g.civilizations) {
            val city = c.getCapital()!!
            val before = c.gold
            for (name in CivilWarCampaign.militaryUnits + "Worker") for (stat in listOf(Stat.Gold, Stat.Faith))
                assertFalse(city.cityConstructions.purchaseConstruction(name, -1, false, stat))
            assertFalse(city.cityConstructions.purchaseConstruction("Library", -1, false))
            assertFalse(city.cityConstructions.purchaseConstruction("Courthouse", -1, false))
            assertEquals(before, c.gold)
            city.cityConstructions.removeBuilding("Market")
            city.isPuppet = true
            assertFalse(city.cityConstructions.purchaseConstruction("Market", -1, false))
            city.isPuppet = false; city.setFlag(CityFlags.Resistance, 2)
            assertFalse(city.cityConstructions.purchaseConstruction("Market", -1, false))
            city.removeFlag(CityFlags.Resistance)
            c.addGold(3000)
            assertTrue(city.cityConstructions.purchaseConstruction("Market", -1, false))
            assertFalse(city.cityConstructions.purchaseConstruction("Market", -1, false))
            city.cityConstructions.removeBuilding("Market"); c.addGold(-c.gold)
            assertFalse(city.cityConstructions.purchaseConstruction("Market", -1, false))
        }
    }

    @Test fun `recruitment follows stable city not owner name and puppets cannot recruit`() {
        val g = game(); val p = g.getCurrentPlayerCivilization()
        val alexandria = city(g, "cw-alexandria")
        assertFalse(g.ruleset.units.getValue("Rifleman").isBuildable(alexandria.cityConstructions))
        assertFalse(alexandria.cityConstructions.completeConstruction(g.ruleset.units.getValue("Rifleman")))
        assertTrue(g.ruleset.units.getValue("Worker").isBuildable(alexandria.cityConstructions))
        val captured = city(g, "cw-petersburg")
        captured.puppetCity(p)
        captured.name = "Renamed"
        assertFalse(CivilWarCampaign.constructionAllowed(captured, "Cannon"))
        captured.isPuppet = false
        assertFalse(CivilWarCampaign.constructionAllowed(captured, "Cannon"))
        captured.removeFlag(CityFlags.Resistance)
        assertTrue(CivilWarCampaign.constructionAllowed(captured, "Cannon"))
        assertTrue(captured.cityConstructions.isBuilt("Petersburg Depot"))
        assertTrue(PerpetualConstruction.Gold.isBuildable(captured.cityConstructions))
        assertTrue(PerpetualConstruction.Idle.isBuildable(captured.cityConstructions))
        assertFalse(PerpetualConstruction.Science.isBuildable(captured.cityConstructions))
    }

    @Test fun `rail transport research leaves existing connected production unchanged`() {
        val g = game()
        for (c in g.civilizations) {
            c.cache.updateCitiesConnectedToCapital()
            val other = c.cities.first { it.id in setOf("cw-baltimore", "cw-petersburg") }
            assertTrue(other.isConnectedToCapital { mediums -> mediums.any { it.name == "Railroad" } })
            c.cities.forEach { it.cityStats.update() }
            val before = c.cities.associate { it.id to it.cityStats.currentCityStats.production }
            c.tech.addTechnology("Military Transport")
            c.cache.updateCitiesConnectedToCapital(); c.cities.forEach { it.cityStats.update() }
            assertEquals(before, c.cities.associate { it.id to it.cityStats.currentCityStats.production })
        }
    }

    @Test fun `worker restrictions allow existing farm repair but reject new farm and forest removal`() {
        val g = game(); val p = g.getCurrentPlayerCivilization()
        val unit = p.units.getCivUnits().first { it.name == "Worker" }
        val t = unit.currentTile
        t.setImprovement("Farm"); t.improvementIsPillaged = true
        assertNotNull(UnitActionsFromUniques.getRepairAction(unit)?.action)
        assertFalse(unit.canBuildImprovement(g.ruleset.tileImprovements.getValue("Farm")))
        assertFalse(unit.canBuildImprovement(g.ruleset.tileImprovements.getValue("Remove Forest")))
        t.startWorkingOnImprovement(g.ruleset.tileImprovements.getValue("Farm"), p, unit)
        assertNull(t.improvementInProgress)
        UnitActionsFromUniques.getRepairAction(unit)!!.action!!.invoke()
        assertFalse(unit.canBuildImprovement(g.ruleset.tileImprovements.getValue("Farm")))
        t.startWorkingOnImprovement(g.ruleset.tileImprovements.getValue("Farm"), p, unit)
        assertEquals("Repair", t.improvementInProgress)
        UnitTurnManager(unit).endTurn(); UnitTurnManager(unit).endTurn()
        assertFalse(t.improvementIsPillaged)
        assertTrue(unit.canBuildImprovement(g.ruleset.tileImprovements.getValue("Road")))
        assertFalse(unit.canBuildImprovement(g.ruleset.tileImprovements.getValue("Railroad")))
        t.setRoadStatus(RoadStatus.Railroad, p); t.roadIsPillaged = true
        val before = UnitActionsFromUniques.getRepairAction(unit)!!.title
        p.tech.addTechnology("Military Transport")
        assertTrue(unit.canBuildImprovement(g.ruleset.tileImprovements.getValue("Railroad")))
        val after = UnitActionsFromUniques.getRepairAction(unit)!!.title
        assertNotEquals(before, after)
        UnitActionsFromUniques.getRepairAction(unit)!!.action!!.invoke()
        assertEquals(1, t.turnsToImprovement)
        UnitTurnManager(unit).endTurn()
        assertFalse(t.roadIsPillaged)
    }

    @Test fun `loss reasons deduplicate disband debt combat and survive cloning without workers`() {
        val g = game(); val p = g.getCurrentPlayerCivilization(); val s = g.campaign!!
        val units = p.units.getCivUnits().filter { it.name in CivilWarCampaign.militaryUnits }.toList()
        val before = g.clone()
        units[0].disband(); units[0].disband()
        units[1].disband(CampaignLossReason.Debt)
        val enemy = g.civilizations.first { it != p }.units.getCivUnits().first { it.isMilitary() }
        CampaignRules.combatDefeat(MapUnitCombatant(enemy), units[2]); units[2].takeDamage(100)
        p.units.getCivUnits().first { it.name == "Worker" }.disband()
        p.units.getCivUnits().first { it.name == "Great General" }.disband()
        assertEquals(3, s.lostMilitary.size)
        assertEquals(setOf("Disband", "Debt", "Combat"), s.lossReasons.values.toSet())
        val restored = g.clone().also { it.setTransients() }
        assertEquals(s.lossReasons, restored.campaign!!.lossReasons)
        assertEquals(0, before.campaign!!.lostMilitary.size)
        assertEquals(3, CampaignRules.evaluate(restored).details.size)
    }

    @Test fun `illegal upgrade is refused and normal identity replacement is not a loss`() {
        val g = game(); val u = g.getCurrentPlayerCivilization().units.getCivUnits().first { it.isMilitary() }
        val unitCount = u.civ.units.getCivUnits().count()
        u.upgrade.performUpgrade(PossibleUpgrade(g.ruleset.units.getValue("Infantry"), null, false, UpgradeAvailability.AvailableForFree, 0, Counter()), true)
        assertEquals(unitCount, u.civ.units.getCivUnits().count())
        assertFalse(u.isDestroyed)
        u.destroy(preserveCampaignIdentity = true)
        assertTrue(g.campaign!!.lostMilitary.isEmpty())
    }

    @Test fun `zero one two and three stars use 30 31 and six seven boundaries`() {
        for (round in listOf(30, 31, 50)) for (losses in listOf(6, 7)) {
            val g = game(); val p = g.getCurrentPlayerCivilization(); val s = g.campaign!!
            s.completedRounds = round - 1
            p.units.getCivUnits().filter { it.name in CivilWarCampaign.militaryUnits }.take(losses).toList().forEach { it.disband() }
            city(g, "cw-richmond").puppetCity(p)
            CampaignRules.actionSettled(g)
            assertEquals(1 + (if (round <= 30) 1 else 0) + (if (losses <= 6) 1 else 0), s.resultStars)
            val result = s.resultStars; g.nextTurn(); assertEquals(result, s.resultStars)
        }
        val g = game(); g.turns = 50; CampaignRules.roundCompleted(g)
        assertTrue(g.campaign!!.failed); assertEquals(0, CampaignRules.evaluate(g).stars)
        g.nextTurn(); assertEquals(50, g.turns)
    }

    @Test fun `capital loss takes precedence and fixed targets survive rename`() {
        val g = game(); val p = g.getCurrentPlayerCivilization(); val enemy = g.civilizations.first { it != p }
        city(g, "cw-richmond").name = "Different"
        city(g, "cw-washington").puppetCity(enemy)
        assertTrue(g.campaign!!.failed)
        city(g, "cw-richmond").puppetCity(p)
        assertEquals(0, CampaignRules.evaluate(g).stars)
        assertTrue(g.campaign!!.failureReason.contains("华盛顿"))
    }

    @Test fun `history is independent and retains unknown scoring versions on merge`() {
        val g = game(); val store = store(); val p = g.getCurrentPlayerCivilization()
        assertTrue(store.isUnlocked(g.campaign!!.definition, store.progress()))
        store.start(g, null)
        city(g, "cw-richmond").puppetCity(p)
        store.save(g)
        val progress = store.progress()
        assertEquals(3, store.stars(g.campaign!!.definition, progress))
        assertTrue(progress.medals.isEmpty())
        assertEquals(0, CampaignCatalog.definitions().sumOf { store.stars(it, progress) })
        val newer = CampaignProgress().apply { bestStars["civil-war-east:12"] = 2; cleared.add("civil-war-east") }
        assertEquals(2, store.mergeProgress(newer).bestStars["civil-war-east:12"])
        assertEquals(3, store.resume(CivilWarCampaign.id).campaign!!.resultStars)
    }

    @Test fun `incompatible content is refused while save and historical results survive`() {
        val g = game(); val store = store(); store.start(g, null)
        g.campaign!!.rulesVersion = "old"
        store.save(g)
        val bytes = store.slot(CivilWarCampaign.id).readBytes()
        try { store.resume(CivilWarCampaign.id); fail("incompatible save loaded") }
        catch (e: com.unciv.logic.UncivShowableException) { assertTrue(e.message!!.contains("请重新开始")) }
        assertArrayEquals(bytes, store.slot(CivilWarCampaign.id).readBytes())
        val fresh = game(); store.start(fresh, g.campaign!!.attemptId)
        assertNotEquals(g.gameId, store.resume(CivilWarCampaign.id).gameId)
    }

    @Test fun `infantry artillery and medicine effects follow their exact conditions`() {
        val g = game(); val p = g.getCurrentPlayerCivilization(); val enemy = g.civilizations.first { it != p }
        val infantry = p.units.getCivUnits().first { it.name == "Rifleman" }
        val cannon = p.units.getCivUnits().first { it.name == "Cannon" }
        val victim = MapUnitCombatant(enemy.units.getCivUnits().first { it.name == "Rifleman" })
        fun modifiers(u: com.unciv.logic.map.mapunit.MapUnit, defender: com.unciv.logic.battle.ICombatant) =
            BattleDamage.getAttackModifiers(MapUnitCombatant(u), defender, u.currentTile).values.sum()
        val before = modifiers(infantry, victim)
        p.tech.addTechnology("Military Reorganization")
        assertEquals(before + 10, modifiers(infantry, victim))
        val field = modifiers(cannon, victim)
        val capital = CityCombatant(enemy.getCapital()!!)
        val siege = modifiers(cannon, capital)
        p.tech.addTechnology("Artillery Improvement")
        assertEquals(field + 10, modifiers(cannon, victim))
        assertEquals(siege, modifiers(cannon, capital))
        val guard = p.units.getCivUnits().first { it.name == "Rifleman" && it.currentTile.getOwner() == p }
        guard.health = 50
        val healing = guard.getHealAmountForCurrentTile()
        p.tech.addTechnology("Field Medicine")
        assertEquals(healing + 5, guard.getHealAmountForCurrentTile())
        guard.currentMovement = 0f
        UnitTurnManager(guard).endTurn(); assertEquals(50, guard.health)
    }

    @Test fun `pack validates and restricted sources cannot block the next turn`() {
        val g = game()
        val errors = g.ruleset.getErrorList()
        assertFalse(errors.getErrorText(), errors.isError())
        for (c in g.civilizations) {
            assertTrue(c.greatPeople.getGreatPersonPointsForNextTurn().isEmpty())
            assertEquals(0, c.greatPeople.freeGreatPeople)
            assertEquals(0, c.tech.freeTechs)
            assertFalse(g.ruleset.units.getValue("Great General").uniques.any { it.contains("Citadel") })
            CivilWarCampaign.technologies.forEach { c.tech.addTechnology(it) }
        }
        g.nextTurn()
        assertEquals(1, g.turns)
        assertFalse(g.campaign!!.failed)
    }

    @Test fun `both factions reject direct founding peace city trades and forbidden research queues`() {
        val g = game()
        for (c in g.civilizations) {
            val enemy = g.civilizations.first { it != c }
            val count = g.getCities().count()
            try { c.addCity(g.tileMap.values.first { it.getOwner() == null }.position); fail("founded city") }
            catch (_: IllegalStateException) { }
            assertEquals(count, g.getCities().count())
            c.getDiplomacyManager(enemy)!!.makePeace()
            assertTrue(c.isAtWarWith(enemy))
            val offered = c.cities.last()
            val trade = TradeLogic(c, enemy)
            trade.currentTrade.ourOffers.add(TradeOffer(offered.id, TradeOfferType.City, speed = g.speed))
            trade.acceptTrade()
            assertSame(c, offered.civ)
            c.tech.techsToResearch.add("Dynamite")
            c.tech.addScience(50)
            assertFalse(c.tech.isResearched("Dynamite"))
            assertFalse(c.tech.techsToResearch.contains("Dynamite"))
            assertFalse(c.tech.techsInProgress.containsKey("Dynamite"))
        }
    }

    @Test fun `failed attempts cannot be rescored from an older winning branch`() {
        val g = game(); val store = store(); store.start(g, null)
        val earlier = g.clone().also { it.setTransients() }
        city(g, "cw-washington").puppetCity(g.civilizations.first { it.isAI() })
        store.save(g)
        city(earlier, "cw-richmond").puppetCity(earlier.getCurrentPlayerCivilization())
        assertEquals(3, earlier.campaign!!.resultStars)
        try { store.submit(earlier); fail("failed result rescored") }
        catch (_: IllegalStateException) { }
        assertTrue(earlier.campaign!!.failed)
        assertTrue(store.resume(CivilWarCampaign.id).campaign!!.failed)
        assertTrue(store.progress().bestStars.isEmpty())
    }

    @Test fun `last enemy round really executes and player round fifty one never starts`() {
        val g = game(); val p = g.getCurrentPlayerCivilization()
        g.turns = 49; g.campaign!!.completedRounds = 49; g.campaign!!.lastSampleTurn = 49
        val enemy = g.civilizations.first { it != p }
        val treasury = enemy.gold
        p.units.getCivUnits().forEach { it.currentMovement = 0f }
        g.nextTurn()
        assertNotEquals(treasury, enemy.gold)
        assertEquals(50, g.turns)
        assertEquals(50, g.campaign!!.completedRounds)
        assertTrue(g.campaign!!.failed)
        assertTrue(p.units.getCivUnits().filter { it.id <= 15 }.all { it.currentMovement == 0f })
        g.nextTurn(); assertEquals(50, g.turns)
    }

    @Test fun `actual upkeep debt causes counted disband and reduces supply pressure`() {
        val g = game(); val p = g.getCurrentPlayerCivilization()
        repeat(45) { p.units.addUnit("Rifleman", p.getCapital()) }
        p.updateStatsForNextTurn()
        assertTrue(p.stats.getUnitSupplyProductionPenalty() < 0)
        assertTrue(p.stats.statsForNextTurn.gold < 0)
        val units = p.units.getCivUnits().count()
        p.addGold(-p.gold - 1000)
        TurnManager(p).endTurn()
        assertTrue(p.units.getCivUnits().count() < units)
        assertTrue(g.campaign!!.lossReasons.values.contains("Debt"))
        assertEquals(g.campaign!!.lostMilitary.size, g.campaign!!.lossReasons.size)
    }

    @Test fun `new recruits have normal experience and reconstructed depots work after recapture`() {
        val g = game(); val p = g.getCurrentPlayerCivilization(); val e = g.civilizations.first { it != p }
        val capital = p.getCapital()!!
        assertTrue(capital.cityConstructions.completeConstruction(g.ruleset.units.getValue("Rifleman")))
        val recruit = p.units.getCivUnits().maxBy { it.id }
        assertEquals(0, recruit.promotions.XP)
        assertEquals(0, recruit.promotions.numberOfPromotions)
        val c = city(g, "cw-petersburg")
        c.puppetCity(p); c.annexCity(); c.removeFlag(CityFlags.Resistance)
        c.cityConstructions.removeBuilding("Petersburg Depot")
        assertTrue(c.cityConstructions.completeConstruction(g.ruleset.buildings.getValue("Petersburg Depot")))
        c.puppetCity(e); c.annexCity(); c.removeFlag(CityFlags.Resistance)
        assertTrue(c.cityConstructions.isBuilt("Petersburg Depot"))
        assertTrue(g.ruleset.units.getValue("Cannon").isBuildable(c.cityConstructions))
    }

    @Test fun `automatic workers repair the damaged road at Harpers Ferry without an underlying improvement`() {
        val g = game(); val p = g.getCurrentPlayerCivilization()
        val worker = p.units.getCivUnits().first { it.name == "Worker" }
        val tile = g.tileMap[CivilWarCampaign.harpersFerry]
        worker.removeFromTile(); worker.putInTile(tile); p.cache.updateViewableTiles()
        assertNull(tile.improvement)
        assertTrue(tile.roadIsPillaged)
        val workerView = com.unciv.view.GameView(g, p).getMapUnitView(worker)
        com.unciv.logic.automation.unit.WorkerAutomation(p, g.turns).automateWorkerAction(workerView, hashSetOf())
        assertEquals("Repair", tile.improvementInProgress)
        repeat(2) { UnitTurnManager(worker).endTurn() }
        assertFalse(tile.roadIsPillaged)
    }

    @Test fun `transport rounds odd road repairs upward but does not accelerate farm repairs`() {
        val g = game(); val p = g.getCurrentPlayerCivilization()
        val worker = p.units.getCivUnits().first { it.name == "Worker" }
        val tile = worker.currentTile
        val repair = g.ruleset.tileImprovements.getValue("Repair")
        val original = repair.turnsToBuild
        try {
            repair.turnsToBuild = 3
            p.tech.addTechnology("Military Transport")
            tile.setRoadStatus(RoadStatus.Railroad, p); tile.roadIsPillaged = true
            UnitActionsFromUniques.getRepairAction(worker)!!.action!!.invoke()
            assertEquals(2, tile.turnsToImprovement)
            repeat(2) { UnitTurnManager(worker).endTurn() }
            tile.setImprovement("Farm"); tile.improvementIsPillaged = true
            UnitActionsFromUniques.getRepairAction(worker)!!.action!!.invoke()
            assertEquals(3, tile.turnsToImprovement)
        } finally { repair.turnsToBuild = original }
    }

    @Test fun `ordinary games still allow their normal units policies research and worker improvements`() {
        val f = TestGame(); f.makeHexagonalMap(5)
        val c = f.addCiv(isPlayer = true)
        val city = f.addCity(c, f.tileMap.values.first())
        c.tech.addTechnology("Rifling")
        assertTrue(c.tech.isResearched("Rifling"))
        c.addGold(3000)
        assertTrue(city.cityConstructions.purchaseConstruction("Rifleman", -1, false))
        c.policies.freePolicies = 1
        assertTrue(c.policies.canAdoptPolicy())
        val worker = c.units.addUnit("Worker", city)!!
        assertTrue(worker.canBuildImprovement(f.ruleset.tileImprovements.getValue("Farm")))
        assertFalse(CampaignRules.foundingBlocked(c))
    }
}
