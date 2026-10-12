package com.unciv.logic.campaign

import com.badlogic.gdx.Gdx
import com.unciv.UncivGame
import com.unciv.logic.GameInfo
import com.unciv.logic.battle.Battle
import com.unciv.logic.battle.MapUnitCombatant
import com.unciv.logic.city.CityFlags
import com.unciv.logic.civilization.AlertType
import com.unciv.logic.civilization.PopupAlert
import com.unciv.logic.files.FileConversions
import com.unciv.logic.files.UncivFiles
import com.unciv.logic.map.HexCoord
import com.unciv.logic.map.tile.RoadStatus
import com.unciv.logic.trade.Trade
import com.unciv.logic.trade.TradeEvaluation
import com.unciv.logic.trade.TradeLogic
import com.unciv.logic.trade.TradeOffer
import com.unciv.logic.trade.TradeOfferType
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/** Constructed rule/engine integration checks, not human playthrough or balance acceptance. */
@RunWith(BaseTestRunner::class)
class CampaignAllLevelsTest {
    @get:Rule val temporary = TemporaryFolder()
    @Before fun setup() { TestGame() }
    private fun game(n: Int): GameInfo = CampaignCatalog.newAttempt(CampaignCatalog.levelId(n)).also { UncivGame.Current.gameInfo = it }
    private fun produced(g: GameInfo, name: String) = g.ruleset.units.getValue(name)
        .construct(g.getCurrentPlayerCivilization().getCapital()!!.cityConstructions, null)!!.also {
            CampaignRules.productionCompleted(g.getCurrentPlayerCivilization().getCapital()!!, name, it)
        }
    private fun round(g: GameInfo, number: Int) { g.turns = number; CampaignRules.roundCompleted(g) }
    private fun capture(g: GameInfo, id: String) = g.getCities().first { it.id == id }.also {
        it.puppetCity(g.getCurrentPlayerCivilization())
    }

    @Test fun `all sixteen published scenes have valid dependencies and initially incomplete main goals`() {
        for (n in 1..16) {
            val g = game(n); val s = g.campaign!!; val d = s.definition
            assertEquals(n, d.number)
            assertEquals(3, d.objectives.size)
            assertFalse("$n starts completed", CampaignRules.evaluate(g).main)
            assertFalse("$n starts failed", CampaignRules.evaluate(g).failed)
            assertEquals(0, s.completedRounds)
            if (n == 1 || n == 16) assertEquals(9, g.getCurrentPlayerCivilization().getHappiness())
            if (n == 7) {
                assertTrue(g.getCurrentPlayerCivilization().stats.statsForNextTurn.gold in -4f..-2f)
                assertEquals(5, g.getCurrentPlayerCivilization().getHappiness())
            }
            if (n == 11) assertEquals(1, g.getCurrentPlayerCivilization().getHappiness())
            if (n == 13) assertEquals(-10, g.getCurrentPlayerCivilization().getHappiness())
            assertEquals(1, g.civilizations.count { it.isHuman() })
            assertTrue(g.gameParameters.noBarbarians)
            assertEquals(0, g.gameParameters.numberOfCityStates)
            assertTrue(g.tileMap.mapParameters.noRuins)
            assertEquals(if (n >= 15) setOf("Domination") else emptySet<String>(), g.gameParameters.victoryTypes.toSet())
            for (c in g.civilizations) {
                for (t in c.tech.techsResearched) assertTrue("$n tech $t", c.tech.techsResearched.containsAll(g.ruleset.technologies.getValue(t).prerequisites))
                for (city in c.cities) {
                    if (c.isHuman()) assertTrue("$n ${city.id}", city.cityConstructions.constructionQueue.isEmpty())
                    assertTrue(city.cityConstructions.inProgressConstructions.isEmpty())
                }
                for (unit in c.units.getCivUnits()) { assertEquals(100, unit.health); assertTrue(unit.currentMovement > 0) }
            }
            for (tile in d.areas.flatMap { it.tiles } + d.targetTiles) assertNotNull(g.tileMap[tile])
            if (d.targetCity.isNotEmpty()) assertTrue(g.getCities().any { it.id == d.targetCity })
            val clone = g.clone().also { it.setTransients() }
            if (n == 1 || n == 16) assertEquals(9, clone.getCurrentPlayerCivilization().getHappiness())
            assertEquals(s.seed, clone.campaign!!.seed)
            assertEquals(d.objectives, clone.campaign!!.definition.objectives)
            assertFalse(s.protectedUnitLost)
            assertFalse(clone.campaign!!.protectedUnitLost)
            if (n == 12) assertEquals(2, s.protectedUnits.size)
        }
    }

    @Test fun `five fresh identities per scene keep authored assets stable`() {
        for (n in 1..16) {
            val starts = (1..5).map { game(n) }
            assertEquals(5, starts.map { it.gameId }.toSet().size)
            assertEquals(5, starts.map { it.campaign!!.seed }.toSet().size)
            assertEquals(1, starts.map { it.getCurrentPlayerCivilization().gold }.toSet().size)
            assertEquals(1, starts.map { it.tileMap.tileList.map { tile -> tile.baseTerrain to tile.resource } }.toSet().size)
        }
    }

    @Test fun `founding vision cannot retroactively satisfy scout first and turn four is inclusive`() {
        val g = game(1); val p = g.getCurrentPlayerCivilization(); val s = g.campaign!!
        assertFalse(s.definition.areas.all { it.tiles.all { t -> g.tileMap[t].isExplored(p) } })
        for (area in s.definition.areas) for (t in area.tiles) g.tileMap[t].setExplored(p, true)
        s.completedRounds = 3
        val city = p.addCity(HexCoord(0,0))
        assertEquals(city.id, s.capitalId)
        assertEquals(3, CampaignRules.evaluate(g).stars)
        val late = game(1); late.campaign!!.completedRounds = 4
        late.getCurrentPlayerCivilization().addCity(HexCoord(0,0))
        assertFalse(CampaignRules.evaluate(late).population)
        assertFalse(CampaignRules.evaluate(late).worker)
    }

    @Test fun `land improvements must match resource targets ownership and working citizens`() {
        val g = game(3); val p = g.getCurrentPlayerCivilization(); val cap = p.getCapital()!!
        val tiles = g.campaign!!.definition.targetTiles.map { g.tileMap[it] }
        tiles[0].setImprovement("Farm"); tiles[1].setImprovement("Mine")
        cap.workedTiles = HashSet(tiles.map { it.position })
        assertTrue(CampaignRules.evaluate(g).main); assertTrue(CampaignRules.evaluate(g).worker)
        assertTrue(CampaignRules.evaluate(g).details[0].contains("{小麦农场}: {已完成}"))
        assertTrue(CampaignRules.evaluate(g).details[0].contains("{宝石矿山}: {已完成}"))
        tiles[1].setImprovement("Farm")
        assertFalse(CampaignRules.evaluate(g).main)
        assertTrue(CampaignRules.evaluate(g).details[0].contains("{宝石矿山}: {未建成}"))
        tiles[1].setImprovement("Mine")
        tiles[1].improvementIsPillaged = true
        assertFalse(CampaignRules.evaluate(g).main)
        assertTrue(CampaignRules.evaluate(g).details[0].contains("{宝石矿山}: {已被劫掠，需修复}"))
        tiles[1].improvementIsPillaged = false
        cap.workedTiles.clear()
        assertFalse(CampaignRules.evaluate(g).worker)
        g.tileMap[0,1].setImprovement("Farm")
        assertTrue(CampaignRules.evaluate(g).population)
    }

    @Test fun `growth exploration and research inspect real current state`() {
        val growth = game(4); val cap = growth.getCurrentPlayerCivilization().getCapital()!!
        cap.cityConstructions.addBuilding(growth.ruleset.buildings.getValue("Monument"))
        cap.population.setPopulation(3); assertTrue(CampaignRules.evaluate(growth).main)
        cap.population.setPopulation(4); assertTrue(CampaignRules.evaluate(growth).worker)
        cap.population.setPopulation(2); assertFalse(CampaignRules.evaluate(growth).main)
        val explore = game(5); val p = explore.getCurrentPlayerCivilization()
        for (area in explore.campaign!!.definition.areas) for (t in area.tiles) explore.tileMap[t].setExplored(p, true)
        p.diplomacyFunctions.makeCivilizationsMeet(explore.civilizations.first { !it.isHuman() })
        assertEquals(3, CampaignRules.evaluate(explore).stars)
        val research = game(6); val r = research.getCurrentPlayerCivilization()
        r.tech.addTechnology("Writing"); r.tech.addTechnology("Animal Husbandry")
        r.getCapital()!!.cityConstructions.addBuilding(research.ruleset.buildings.getValue("Library"))
        r.getCapital()!!.population.setPopulation(4)
        assertEquals(3, CampaignRules.evaluate(research).stars)
        assertTrue(CampaignRules.warBlocked(r))
        r.addCity(HexCoord(1,0)); assertTrue(CampaignRules.foundingBlocked(r))
        assertThrows(IllegalStateException::class.java) { r.addCity(HexCoord(1,5)) }
    }

    @Test fun `income observations happen once per complete round and reset on a failing sample`() {
        val g = game(7); val p = g.getCurrentPlayerCivilization()
        p.stats.statsForNextTurn.gold = 3f
        for (i in 1..2) { round(g,i); repeat(3) { round(g,i); CampaignRules.evaluate(g) } }
        assertEquals(2, g.campaign!!.incomeStreak); assertFalse(CampaignRules.evaluate(g).main)
        p.stats.statsForNextTurn.gold = -1f; round(g,3); assertEquals(0, g.campaign!!.incomeStreak)
        p.stats.statsForNextTurn.gold = 3f
        for (i in 4..6) round(g,i)
        assertTrue(CampaignRules.evaluate(g).main)
        val restored = g.clone().also { it.setTransients() }
        CampaignRules.actionSettled(restored); assertEquals(3, restored.campaign!!.incomeStreak)
        p.stats.statsForNextTurn.gold = -1f; assertFalse(CampaignRules.evaluate(g).main)
    }

    @Test fun `new city observations require a real connection first`() {
        val g = game(8); val p = g.getCurrentPlayerCivilization()
        val second = p.addCity(HexCoord(2,0))
        round(g,1); assertEquals(0, g.campaign!!.stableStreak)
        for (x in -3..2) g.tileMap[x,0].setRoadStatus(RoadStatus.Road, p)
        p.cache.updateCitiesConnectedToCapital()
        assertTrue(second.isConnectedToCapital())
        p.stats.happiness = 1; p.stats.statsForNextTurn.gold = 3f
        for (i in 2..4) round(g,i)
        assertTrue(CampaignRules.evaluate(g).main); assertTrue(CampaignRules.evaluate(g).population)
        g.tileMap[0,0].setRoadStatus(RoadStatus.None, p); p.cache.updateCitiesConnectedToCapital()
        assertFalse(CampaignRules.evaluate(g).main)
    }

    @Test fun `military production survives upgrades but not ownership loss and initial units do not count`() {
        val g = game(9); val p = g.getCurrentPlayerCivilization()
        assertFalse(CampaignRules.evaluate(g).main)
        val melee = produced(g,"Warrior")
        produced(g,"Spearman"); produced(g,"Archer"); produced(g,"Archer")
        p.stats.statsForNextTurn.gold = 3f
        for (i in 1..3) round(g,i)
        assertTrue(CampaignRules.evaluate(g).main); assertTrue(CampaignRules.evaluate(g).population)
        melee.upgrade.performUpgrade(g.ruleset.units.getValue("Spearman"), true)
        assertTrue(melee.id in g.campaign!!.producedUnits)
        p.stats.statsForNextTurn.gold = 3f
        assertTrue(CampaignRules.evaluate(g).main)
        p.units.getCivUnits().first { it.id == melee.id }.destroy()
        assertFalse(CampaignRules.evaluate(g).main)
        produced(g,"Warrior"); produced(g,"Warrior")
        p.stats.statsForNextTurn.gold = 3f
        assertTrue(CampaignRules.evaluate(g).worker)
    }

    @Test fun `combat events are idempotent and AI disband is not a player kill`() {
        val g = game(10); val p = g.getCurrentPlayerCivilization(); val enemy = g.civilizations.first { !it.isHuman() }
        val units = enemy.units.getCivUnits().toList()
        units.first().destroy(); assertEquals(0, g.campaign!!.killedUnits.size)
        val killer = MapUnitCombatant(p.units.getCivUnits().first())
        repeat(2) { CampaignRules.combatDefeat(killer, units[1]) }
        assertEquals(1, g.campaign!!.killedUnits.size)
        assertFalse(CampaignRules.evaluate(g).main)
        CampaignRules.enemyDefeated(enemy,p)
        assertTrue(CampaignRules.evaluate(g).main)
        val oldGold = p.gold
        val trade = TradeLogic(p,enemy)
        trade.currentTrade.ourOffers.add(TradeOffer("Peace Treaty", TradeOfferType.Treaty, speed=g.speed))
        trade.currentTrade.ourOffers.add(TradeOffer("Gold", TradeOfferType.Gold, 50, g.speed))
        assertFalse(TradeEvaluation().isTradeValid(trade.currentTrade,p,enemy))
        trade.acceptTrade(); p.getDiplomacyManager(enemy)!!.makePeace()
        assertTrue(p.isAtWarWith(enemy)); assertEquals(oldGold,p.gold)
    }

    @Test fun `defence waits for twentieth complete round and capital loss wins over success`() {
        val g = game(11)
        round(g,19); assertEquals(0,g.campaign!!.resultStars)
        round(g,20); assertTrue(g.campaign!!.resultStars > 0)
        assertEquals(20, CampaignShareData.result(g.campaign!!, g.campaign!!.definition.id, g.campaign!!.attemptId)!!.rounds)
        val turns = g.turns; g.nextTurn(); assertEquals(turns,g.turns)
        val lost = game(11); val p = lost.getCurrentPlayerCivilization(); val enemy = lost.civilizations.first { !it.isHuman() }
        p.getCapital()!!.moveToCiv(enemy)
        round(lost,20); assertTrue(lost.campaign!!.failed); assertEquals(0,lost.campaign!!.resultStars)
    }

    @Test fun `siege settles only after disposition and preserves upgrade identity`() {
        val g = game(12); val s = g.campaign!!; val p = g.getCurrentPlayerCivilization()
        val siege = p.units.getCivUnits().first { it.id in s.protectedUnits }
        siege.upgrade.performUpgrade(g.ruleset.units.getValue("Trebuchet"), true)
        assertFalse(s.protectedUnitLost)
        s.completedRounds = 14
        p.popupAlerts.add(PopupAlert(AlertType.CityConquered,"enemy-fort"))
        val fort = capture(g,"enemy-fort")
        CampaignRules.actionSettled(g); assertEquals(0,s.resultStars)
        fort.isBeingRazed = true; p.popupAlerts.clear(); CampaignRules.actionSettled(g)
        assertEquals(0,s.resultStars)
        fort.isBeingRazed = false; CampaignRules.actionSettled(g)
        assertEquals(3,s.resultStars)
        assertEquals(15, s.resultActionRound)
        val failure = game(12); round(failure,25)
        assertTrue(failure.campaign!!.failed)
        val loss = game(12); val unit = loss.getCurrentPlayerCivilization().units.getCivUnits().first { it.id in loss.campaign!!.protectedUnits }
        unit.destroy(); produced(loss,"Catapult")
        assertTrue(loss.campaign!!.protectedUnitLost)
    }

    @Test fun `postwar stability starts after resistance and capital lesson requires this attempts conquest`() {
        val g = game(13); val p = g.getCurrentPlayerCivilization(); val city = p.cities.first { it.id == "occupied-city" }
        p.stats.statsForNextTurn.gold = 2f; p.stats.happiness = 0
        round(g,1); assertEquals(0,g.campaign!!.stableStreak)
        city.removeFlag(CityFlags.Resistance)
        for(i in 2..4) round(g,i)
        assertTrue(CampaignRules.evaluate(g).main)
        city.isBeingRazed = true; assertFalse(CampaignRules.evaluate(g).main)
        val capital = game(14); capture(capital,"enemy-capital")
        assertTrue(CampaignRules.evaluate(capital).main)
        assertEquals(0,capital.campaign!!.resultStars)
        assertTrue(capital.getCities().any { it.isOriginalCapital && it.civ != capital.getCurrentPlayerCivilization() })
    }

    @Test fun `victory lessons use real engine victory and do not win before opponents found capitals`() {
        val fresh = game(16)
        CampaignRules.actionSettled(fresh)
        assertEquals(0,fresh.campaign!!.resultStars); assertNull(fresh.victoryData)
        for (n in listOf(15,16)) {
            val g = game(n); val p = g.getCurrentPlayerCivilization()
            if (n==16) for ((i,c) in g.civilizations.withIndex()) c.addCity(listOf(HexCoord(-6,-2),HexCoord(5,-2),HexCoord(3,6))[i])
            for (c in g.getCities().filter { it.isOriginalCapital && it.civ != p }.toList()) c.puppetCity(p)
            CampaignRules.actionSettled(g)
            assertNotNull(g.victoryData)
            assertEquals("Domination",g.victoryData!!.victoryType)
            assertTrue(g.campaign!!.resultStars > 0)
        }
    }

    @Test fun `victory sharing freezes player action and full round boundaries separately`() {
        for (n in listOf(15, 16)) for (atRoundEnd in listOf(false, true)) {
            val g = game(n); val p = g.getCurrentPlayerCivilization(); val s = g.campaign!!
            s.completedRounds = 7; s.lastSampleTurn = 7; s.actionDepth++
            if (n == 16) for ((i, c) in g.civilizations.withIndex())
                c.addCity(listOf(HexCoord(-6,-2), HexCoord(5,-2), HexCoord(3,6))[i])
            for (c in g.getCities().filter { it.isOriginalCapital && it.civ != p }.toList()) c.puppetCity(p)
            s.actionDepth--
            if (atRoundEnd) round(g, 8) else CampaignRules.actionSettled(g)
            assertNotNull(g.victoryData)
            assertEquals(8, s.resultActionRound)
            val store = CampaignStore(UncivFiles(Gdx.files, temporary.newFolder("round-$n-$atRoundEnd").path))
            store.save(g)
            assertEquals(8, store.shareResult(s.definition.id, s.attemptId)!!.rounds)
            assertEquals(if (atRoundEnd) 8 else 7, s.completedRounds)
        }
    }

    @Test fun `real AI turns load and advance every authored opponent scene`() {
        for (n in listOf(5,10,11,12,13,14,15,16)) {
            val g = game(n)
            repeat(2) { g.nextTurn() }
            assertFalse("level $n", g.campaign!!.failed)
            assertEquals(2,g.campaign!!.completedRounds)
            assertEquals(2,g.campaign!!.lastSampleTurn)
            assertTrue(g.civilizations.filter { it.isAI() }.all { it.cities.isNotEmpty() })
        }
    }

    @Test fun `last siege action is inclusive and loss and destruction remain irreversible`() {
        val g = game(12); val p = g.getCurrentPlayerCivilization(); val s = g.campaign!!
        s.completedRounds = 24
        val original = p.units.getCivUnits().first { it.id in s.protectedUnits }
        original.gift(g.civilizations.first { !it.isHuman() })
        original.gift(p)
        assertTrue(s.protectedUnitLost)
        capture(g,"enemy-fort"); CampaignRules.actionSettled(g)
        assertEquals(1,s.resultStars)
        assertEquals(25, s.resultActionRound)
        val destroyed = game(12)
        destroyed.getCities().first { it.id == "enemy-fort" }.destroyCity(overrideSafeties=true)
        CampaignRules.actionSettled(destroyed)
        assertTrue(destroyed.campaign!!.failed)
        val defend = game(11); val defender = defend.getCurrentPlayerCivilization(); val cap = defender.getCapital()!!
        cap.moveToCiv(defend.civilizations.first { !it.isHuman() }); cap.moveToCiv(defender)
        CampaignRules.actionSettled(defend)
        assertTrue(defend.campaign!!.failed)
    }

    @Test fun `slots medals and version history survive replay independently`() {
        val store = CampaignStore(UncivFiles(Gdx.files,temporary.root.path))
        val games = (1..16).map { game(it).also { g -> store.start(g,null) } }
        assertEquals(16,games.map { store.resume(it.campaign!!.definition.id).gameId }.toSet().size)
        for (g in games.take(4)) {
            CampaignRules.freeze(g.campaign!!,CampaignRules.Evaluation(true,true,true,false))
            store.save(g)
        }
        val progress = store.progress()
        assertTrue(store.medalKey(0,CampaignCatalog.definitions().take(4)) in progress.medals)
        assertTrue("foundation-05" in progress.unlocked)
        val updated = CampaignCatalog.definition("foundation-01").apply { scoringVersion = 2 }
        assertTrue(store.isUnlocked(updated,progress)); assertEquals(0,store.stars(updated,progress))
        assertEquals(3,progress.bestStars["foundation-01:1"])
        val old = games[5]; old.campaign!!.rulesVersion = "old rules"; store.save(old)
        store.start(game(6),old.campaign!!.attemptId)
        assertEquals(1,Gdx.files.absolute(temporary.root.path).child("Campaigns/Archive").list().size)
    }
}
