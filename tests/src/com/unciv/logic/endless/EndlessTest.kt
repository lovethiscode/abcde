package com.unciv.logic.endless

import com.badlogic.gdx.Gdx
import com.unciv.UncivGame
import com.unciv.logic.GameInfo
import com.unciv.logic.achievements.AchievementTracker
import com.unciv.logic.battle.Battle
import com.unciv.logic.battle.CityCombatant
import com.unciv.logic.battle.MapUnitCombatant
import com.unciv.logic.civilization.managers.TurnManager
import com.unciv.logic.files.UncivFiles
import com.unciv.logic.map.mapunit.UnitTurnManager
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.PerpetualConstruction
import com.unciv.models.ruleset.RulesetCache
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/** Engine/storage fixtures are deliberately separate from legal-route balance acceptance. */
@RunWith(BaseTestRunner::class)
class EndlessTest {
    @get:Rule val temporary = TemporaryFolder()
    @Before fun setup() { TestGame(); UncivGame.Current.settings.citiesAutoBombardAtEndOfTurn = false }
    private fun game(seed: Long = 1101) = EndlessCatalog.newAttempt(seed).also { UncivGame.Current.gameInfo = it }
    private fun store() = EndlessStore(UncivFiles(Gdx.files, temporary.root.path))
    private fun prepare(g: GameInfo, round: Int) {
        g.endless!!.completedRounds = round - 1
        EndlessRules.prepareRound(g)
    }
    private fun clearWave(g: GameInfo, number: Int) {
        val wave = g.endless!!.waves.single { it.number == number }
        check(wave.members.all { it.unitId != 0 })
        AchievementTracker.action(g) {
            for (id in wave.members.map { it.unitId }) {
                val unit = EndlessRules.invaders(g).units.getCivUnits().single { it.id == id }
                unit.destroy()
                EndlessRules.combatDefeat(CityCombatant(EndlessRules.core(g)!!), unit)
            }
        }
    }

    @Test fun `opening is fixed economically viable and distinct from ordinary rules`() {
        val g = game(); val other = game(9999); val p = EndlessRules.player(g); val city = EndlessRules.core(g)!!
        assertEquals(331, g.tileMap.values.size)
        assertEquals(1, g.getCities().count())
        assertEquals(mapOf("Spearman" to 2, "Archer" to 2, "Worker" to 1), p.units.getCivUnits().groupingBy { it.name }.eachCount())
        assertEquals(160, p.gold); assertEquals(4, city.population.population)
        assertEquals(0, g.endless!!.completedRounds); assertEquals(1, g.endless!!.round)
        assertTrue(EndlessRules.invaders(g).units.getCivUnits().none())
        assertFalse(EndlessRules.invaders(g).isDefeated())
        assertTrue(p.isAtWarWith(EndlessRules.invaders(g)))
        assertFalse(g.isReligionEnabled()); assertTrue(g.gameParameters.victoryTypes.isEmpty())
        assertTrue(city.cityStats.currentCityStats.food >= 2); assertTrue(city.cityStats.currentCityStats.production >= 10)
        assertTrue(p.stats.statsForNextTurn.science >= 10); assertTrue(p.stats.statsForNextTurn.gold >= 2)
        assertTrue(p.getHappiness() >= 0); assertEquals(0, p.stats.getUnitSupplyDeficit())
        assertTrue(city.getCenterTile().isAdjacentToRiver())
        assertEquals(g.tileMap.values.map { it.baseTerrain to it.terrainFeatures }, other.tileMap.values.map { it.baseTerrain to it.terrainFeatures })
        for (sector in 0..5) assertTrue(g.tileMap.values.count { it.aerialDistanceTo(city.getCenterTile()) in 8..10 && EndlessRules.sector(it) == sector && it.isLand && !it.isImpassible() } >= 8)
        assertNotEquals(g.endless!!.attemptId, other.endless!!.attemptId)
        val base = RulesetCache[BaseRuleset.Civ_V_GnK.fullName]!!
        assertEquals(3f, base.buildings.getValue("Palace").production)
        assertEquals("Calendar", base.tileImprovements.getValue("Plantation").techRequired)
        assertEquals("Pikeman", base.units.getValue("Spearman").upgradesTo)
    }

    @Test fun `normal turns prepare the first wave before it can move and reload does not prepare again`() {
        val g = game()
        repeat(4) { g.nextTurn() }
        assertEquals(5, g.endless!!.round); assertEquals(0, g.endless!!.waveNumber)
        g.nextTurn()
        val s = g.endless!!
        assertEquals(6, s.round); assertEquals(5, s.completedRounds); assertEquals(1, s.waveNumber); assertEquals(3, s.activeCount)
        val city = EndlessRules.core(g)!!
        assertTrue(EndlessRules.invaders(g).units.getCivUnits().all { it.currentTile.aerialDistanceTo(city.getCenterTile()) in 8..10 })
        val store = store(); store.start(g, null)
        val resumed = store.resume()
        EndlessRules.prepareRound(resumed)
        assertEquals(s.waveNumber, resumed.endless!!.waveNumber)
        assertEquals(s.activeCount, resumed.endless!!.activeCount)
        assertEquals(s.completedRounds, resumed.endless!!.completedRounds)
    }

    @Test fun `clear on round nine gives two full rest rounds and exactly one wave bounty`() {
        val g = game(); prepare(g, 6); g.endless!!.completedRounds = 8
        val before = EndlessRules.player(g).gold
        clearWave(g, 1)
        assertEquals(before + 32, EndlessRules.player(g).gold)
        assertEquals(12, g.endless!!.earlyWaveRound)
        repeat(3) { EndlessRules.actionSettled(g) }
        assertEquals(before + 32, EndlessRules.player(g).gold)
        prepare(g, 10); prepare(g, 11)
        assertEquals(1, g.endless!!.waveNumber)
        prepare(g, 12); EndlessRules.prepareRound(g)
        assertEquals(2, g.endless!!.waveNumber); assertEquals(0, g.endless!!.earlyWaveRound)
    }

    @Test fun `hard deadline preserves old enemies and wins over a late clear`() {
        val g = game(); prepare(g, 6)
        val old = EndlessRules.invaders(g).units.getCivUnits().map { it.id }.toSet()
        prepare(g, 14)
        assertEquals(2, g.endless!!.waveNumber); assertEquals(7, g.endless!!.activeCount)
        assertTrue(EndlessRules.invaders(g).units.getCivUnits().map { it.id }.toSet().containsAll(old))
        val h = game(); prepare(h, 6); h.endless!!.completedRounds = 12; clearWave(h, 1)
        assertEquals(16, h.endless!!.earlyWaveRound)
        prepare(h, 14); assertEquals(2, h.endless!!.waveNumber)
    }

    @Test fun `enemy turn bounties are deferred and core loss cancels them`() {
        val g = game(); prepare(g, 6); val before = EndlessRules.player(g).gold
        g.endless!!.inTurn = true; clearWave(g, 1)
        assertEquals(before, EndlessRules.player(g).gold); assertEquals(32, g.endless!!.pendingGold)
        assertEquals(9, g.endless!!.earlyWaveRound)
        EndlessRules.loseCore(g, g.endless!!.coreId)
        g.endless!!.inTurn = false; EndlessRules.settleBenefits(g)
        assertEquals(before, EndlessRules.player(g).gold); assertEquals(0, g.endless!!.pendingGold)
        assertEquals(5, g.endless!!.completedRounds)
    }

    @Test fun `out of order clears cannot release a stage past an old survivor`() {
        val g = game(); prepare(g, 6)
        for (wave in 2..5) { prepare(g, 6 + 8 * (wave - 1)); clearWave(g, wave) }
        assertNull(g.endless!!.offer); assertEquals(0, g.endless!!.clearedPrefix)
        clearWave(g, 1)
        assertEquals(5, g.endless!!.clearedPrefix); assertEquals(5, g.endless!!.clearedWaves)
        assertEquals(1, g.endless!!.offer!!.stage)
        assertTrue(EndlessRules.blocked(g))
        val round = g.endless!!.round; g.nextTurn(); assertEquals(round, g.endless!!.round)
    }

    @Test fun `unit cap leaves one frozen pending wave and bounds the ledger`() {
        val g = game()
        for (round in 6..200) prepare(g, round)
        val s = g.endless!!
        assertEquals(40, s.activeCount); assertTrue(s.pendingCount in 1..16)
        assertEquals(1, s.waves.count { it.members.any { member -> member.unitId == 0 } })
        val ids = s.waves.flatMap { it.members }.filter { it.unitId != 0 }.map { it.unitId }
        assertEquals(ids.size, ids.toSet().size)
        val plan = com.unciv.json.json().toJson(s.waves.last())
        prepare(g, 201); assertEquals(plan, com.unciv.json.json().toJson(s.waves.last()))
        assertTrue(s.waves.size <= 41)
    }

    @Test fun `all research is finite and disallowed construction is rejected in logic`() {
        val g = game(); val p = EndlessRules.player(g); val city = EndlessRules.core(g)!!
        assertTrue(PerpetualConstruction.Gold.isBuildable(city.cityConstructions))
        assertFalse(PerpetualConstruction.Science.isBuildable(city.cityConstructions))
        val before = p.tech.techsResearched.toSet(); p.tech.addTechnology("Future Tech")
        assertEquals(before, p.tech.techsResearched)
        assertFalse(g.ruleset.units.getValue("Settler").isBuildable(city.cityConstructions))
        assertFalse(city.cityConstructions.purchaseConstruction("Settler", -1, false))
        assertFalse(city.cityConstructions.purchaseConstruction("Shrine", -1, false))
        assertFalse(p.policies.canAdoptPolicy())
        EndlessRules.technologies.forEach { p.tech.addTechnology(it, false) }
        assertTrue(p.tech.allTechsAreResearched()); assertFalse(p.tech.canResearchTech())
        assertTrue(g.ruleset.units.getValue("Spearman").isBuildable(city.cityConstructions))
        assertTrue(g.ruleset.units.getValue("Archer").isBuildable(city.cityConstructions))
        assertEquals("Swordsman", g.ruleset.units.getValue("Spearman").upgradesTo)
        assertEquals("Chivalry", g.ruleset.tileImprovements.getValue("Fort").techRequired)
    }

    @Test fun `reward candidate commits once and frozen offer survives a resume`() {
        val g = game(); val store = store()
        g.endless!!.clearedPrefix = 5; EndlessRules.settleBenefits(g); store.start(g, null)
        val before = EndlessRules.player(g).gold
        val resumed = store.resume(); val reward = resumed.endless!!.offer!!
        assertEquals(80, reward.gold); assertEquals("Spearman", reward.unit)
        assertFalse(EndlessRules.canRepair(resumed))
        val claimed = store.claim(resumed, "Gold")
        assertEquals(before, EndlessRules.player(g).gold)
        assertEquals(before + 80, EndlessRules.player(claimed).gold)
        assertNull(claimed.endless!!.offer); assertEquals(1, claimed.endless!!.claimedStages)
        assertTrue(runCatching { store.claim(resumed, "Gold") }.isFailure)
        assertEquals(before + 80, EndlessRules.player(store.resume()).gold)
    }

    @Test fun `repair does not restore actions and reinforcement cannot act on arrival`() {
        val g = game(); val p = EndlessRules.player(g); val city = EndlessRules.core(g)!!
        val unit = p.units.getCivUnits().first { it.isMilitary() }
        city.health = 1; unit.health = 30; unit.currentMovement = 0f; unit.attacksThisTurn = 1
        g.endless!!.clearedPrefix = 5; EndlessRules.settleBenefits(g)
        EndlessRules.applyReward(g, "Repair")
        assertEquals(61, city.health); assertEquals(55, unit.health)
        assertEquals(0f, unit.currentMovement); assertEquals(1, unit.attacksThisTurn)
        g.endless!!.clearedPrefix = 10; EndlessRules.settleBenefits(g)
        val ids = p.units.getCivUnits().map { it.id }.toSet()
        EndlessRules.applyReward(g, "Reinforcement")
        val arrival = p.units.getCivUnits().single { it.id !in ids }
        assertEquals(0f, arrival.currentMovement); assertFalse(arrival.canAttack())
        assertEquals(0, arrival.promotions.XP); assertEquals(0, arrival.promotions.numberOfPromotions)
    }

    @Test fun `terminal journal recovers records and delayed saves cannot restore the game`() {
        val g = game(); val store = store(); store.start(g, null)
        val delayed = store.snapshot(g)
        g.endless!!.completedRounds = 17; EndlessRules.loseCore(g, g.endless!!.coreId); store.save(g)
        store.saveSnapshot(delayed)
        assertEquals("Defeated", store.saved()!!.endless!!.status)
        repeat(3) { store.reconcile(g) }
        assertEquals(1, store.records().recent.size)
        assertEquals(17, store.records().best.getValue(g.endless!!.recordKey).rounds)
        val new = game(); store.start(new, g.endless!!.attemptId); store.saveSnapshot(delayed)
        assertEquals(new.endless!!.attemptId, store.saved()!!.endless!!.attemptId)
    }

    @Test fun `quit is recent only and schema mismatch preserves the slot`() {
        val g = game(); val store = store(); store.start(g, null); store.quit(g)
        assertTrue(store.records().best.isEmpty()); assertEquals("Quit", store.records().recent.single().status)
        val next = game(); store.start(next, g.endless!!.attemptId)
        next.endless!!.engineCompatibility = 999; store.save(next)
        val bytes = store.slot.readBytes()
        assertTrue(runCatching { store.resume() }.isFailure)
        assertArrayEquals(bytes, store.slot.readBytes())
    }

    @Test fun `unconfirmed invader removal faults rather than inventing a battle reward`() {
        val g = game(); prepare(g, 6)
        EndlessRules.invaders(g).units.getCivUnits().first().destroy()
        EndlessRules.actionSettled(g)
        assertEquals("Faulted", g.endless!!.status); assertFalse(g.endless!!.recordEligible); assertEquals(0, g.endless!!.kills)
    }

    @Test fun `invaders retain units under debt and do not promote`() {
        val g = game(); prepare(g, 6); val enemy = EndlessRules.invaders(g)
        enemy.addGold(-10000); val ids = enemy.units.getCivUnits().map { it.id }.toList()
        enemy.units.getCivUnits().forEach { it.promotions.XP = 100 }
        assertTrue(enemy.units.getCivUnits().none { it.promotions.canBePromoted() })
        TurnManager(enemy).endTurn()
        assertEquals(ids, enemy.units.getCivUnits().map { it.id }.toList()); assertEquals(-10000, enemy.gold)
    }

    @Test fun `legal invader movement reaches combat within four enemy turns`() {
        val g = game(); prepare(g, 6); val center = EndlessRules.core(g)!!.getCenterTile()
        var reached = false
        repeat(4) {
            g.nextTurn()
            if (EndlessRules.invaders(g).units.getCivUnits().any { u -> u.currentTile.aerialDistanceTo(center) <= 1 || u.attacksThisTurn > 0 }) reached = true
        }
        assertTrue("The opening wave must advance into combat without teleporting", reached)
        assertEquals("Active", g.endless!!.status)
    }

    @Test fun `a legal melee capture freezes the completed round count`() {
        val g = game(); prepare(g, 6); val city = EndlessRules.core(g)!!
        val enemy = EndlessRules.invaders(g).units.getCivUnits().first()
        city.getCenterTile().militaryUnit!!.destroy()
        val spot = city.getCenterTile().neighbors.first { it.militaryUnit == null }
        enemy.removeFromTile(); enemy.putInTile(spot); enemy.currentMovement = 2f
        city.health = 1
        g.endless!!.inTurn = true
        Battle.attack(MapUnitCombatant(enemy), CityCombatant(city))
        g.endless!!.inTurn = false
        assertTrue(g.endless!!.coreLost); assertEquals("Defeated", g.endless!!.status)
        assertEquals(5, g.endless!!.completedRounds)
        g.nextTurn(); assertEquals(5, g.endless!!.completedRounds)
    }
}
