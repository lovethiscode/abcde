package com.unciv.logic.endless

import com.badlogic.gdx.Gdx
import com.unciv.UncivGame
import com.unciv.logic.GameInfo
import com.unciv.logic.achievements.AchievementTracker
import com.unciv.logic.battle.Battle
import com.unciv.logic.battle.CityCombatant
import com.unciv.logic.battle.MapUnitCombatant
import com.unciv.logic.files.UncivFiles
import com.unciv.logic.map.HexCoord
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

@RunWith(BaseTestRunner::class)
class EndlessCitiesTest {
    @get:Rule val temporary = TemporaryFolder()
    @Before fun setup() { TestGame(); UncivGame.Current.settings.citiesAutoBombardAtEndOfTurn = false }
    private fun game() = EndlessCatalog.newAttempt(1101).also { UncivGame.Current.gameInfo = it }
    private fun finishStage(g: GameInfo, claim: Boolean = true) {
        if (g.endless!!.stageState!!.phase == "Rest") {
            g.endless!!.completedRounds = g.endless!!.stageState!!.nextStageRound - 1
            EndlessRules.prepareRound(g)
        }
        for (w in g.endless!!.waves.toList()) {
            g.endless!!.completedRounds = w.round - 1; EndlessRules.prepareRound(g)
            AchievementTracker.action(g) {
                for (u in EndlessRules.invaders(g).units.getCivUnits().toList()) {
                    EndlessRules.combatDefeat(CityCombatant(EndlessRules.core(g)!!),u); u.destroy()
                }
            }
        }
        if (claim) EndlessRules.applyReward(g,"Gold")
    }
    private fun unlock(g: GameInfo, stages: Int = 2) { repeat(stages) { finishStage(g) } }
    private fun found(g: GameInfo, at: HexCoord) = EndlessRules.player(g).let { player ->
        val settler = player.units.placeUnitNearTile(at,"Settler")!!
        assertEquals(at,settler.currentTile.position)
        player.addCity(at,settler).also { assertTrue(settler.isDestroyed) }
    }

    @Test fun `capacity opens only after completion and normal founding consumes the settler`() {
        val g = game(); val player = EndlessRules.player(g)
        assertFalse(EndlessStageRules.canTrainSettler(player)); assertEquals(1,EndlessStageRules.capacity(g.endless!!))
        finishStage(g); assertFalse(EndlessStageRules.canTrainSettler(player))
        finishStage(g); assertEquals(2,EndlessStageRules.capacity(g.endless!!))
        assertTrue(EndlessStageRules.canTrainSettler(player))
        val side = found(g,HexCoord(-4,-4))
        assertEquals(2,player.cities.size); assertEquals(1,side.population.population)
        assertFalse(side.isOriginalCapital); assertFalse(side.cityConstructions.isBuilt("Palace"))
        assertEquals(1,player.cities.count { it.cityConstructions.isBuilt("Palace") })
        assertFalse(EndlessStageRules.canTrainSettler(player))
        assertTrue(side.getTiles().all { it.aerialDistanceTo(EndlessRules.core(g)!!.getCenterTile()) <= 7 })
    }

    @Test fun `missing stale outside and too close founding calls retain units and reject before mutation`() {
        val g = game(); unlock(g); val player = EndlessRules.player(g)
        val outside = player.units.placeUnitNearTile(HexCoord(5,5),"Settler")!!
        val history = player.citiesCreated; val gold = player.gold
        assertTrue(runCatching { player.addCity(outside.currentTile.position,outside) }.isFailure)
        assertFalse(outside.isDestroyed); assertEquals(history,player.citiesCreated); assertEquals(gold,player.gold)
        assertTrue(runCatching { player.addCity(HexCoord(-4,-4),null) }.isFailure)
        assertTrue(runCatching { player.addCity(HexCoord(-4,-4),outside) }.isFailure)
        assertEquals(1,player.cities.size)
    }

    @Test fun `three city ceiling serially rejects a fourth and preserves invested production`() {
        val g = game(); unlock(g,4); val player = EndlessRules.player(g); val core = EndlessRules.core(g)!!
        assertEquals(3,EndlessStageRules.capacity(g.endless!!))
        found(g,HexCoord(-4,-4))
        core.cityConstructions.inProgressConstructions["Settler"] = 100
        found(g,HexCoord(4,4))
        core.cityConstructions.validateInProgressConstructions()
        assertEquals(100,core.cityConstructions.getWorkDone("Settler"))
        assertFalse(core.cityConstructions.purchaseConstruction("Settler",-1,false))
        val spare = player.units.placeUnitNearTile(HexCoord(-4,0),"Settler")!!
        assertFalse(EndlessStageRules.canFound(player,spare.currentTile,spare))
        assertTrue(runCatching { player.addCity(spare.currentTile.position,spare) }.isFailure)
        assertFalse(spare.isDestroyed); assertEquals(3,player.cities.size)
    }

    @Test fun `capturing a side city destroys it releases capacity and retains invader identity`() {
        val g = game(); unlock(g); val side = found(g,HexCoord(-4,-4)); val player = EndlessRules.player(g)
        g.endless!!.completedRounds = g.endless!!.stageState!!.nextStageRound - 1; EndlessRules.prepareRound(g)
        val enemy = EndlessRules.invaders(g).units.getCivUnits().first { it.baseUnit.isMelee() }
        val adjacent = side.getCenterTile().neighbors.first { it.militaryUnit == null && it.civilianUnit == null }
        enemy.removeFromTile(); enemy.putInTile(adjacent); enemy.currentMovement = 2f
        side.health = 1
        val before = g.endless!!.activeCount; val kills = g.endless!!.kills
        g.endless!!.inTurn = true
        Battle.attack(MapUnitCombatant(enemy),CityCombatant(side))
        g.endless!!.inTurn = false
        assertEquals(1,player.cities.size); assertEquals(0,EndlessRules.invaders(g).cities.size)
        assertFalse(enemy.isDestroyed); assertEquals(before,g.endless!!.activeCount); assertEquals(kills,g.endless!!.kills)
        assertTrue(EndlessStageRules.canTrainSettler(player)); assertEquals("Active",g.endless!!.status)
        enemy.removeFromTile(); enemy.putInTile(g.tileMap[HexCoord(-7,-7)])
        val rebuilt = found(g,HexCoord(-4,-4))
        assertEquals(1,rebuilt.population.population); assertFalse(rebuilt.cityConstructions.isBuilt("Walls"))
        val store = EndlessStore(UncivFiles(Gdx.files,temporary.root.path)); store.start(g,null)
        assertEquals(2,EndlessRules.player(store.resume()).cities.size)
    }

    @Test fun `fixed core ownership palace and city disposal guards apply below the interface`() {
        val g = game(); unlock(g); val side = found(g,HexCoord(-4,-4)); val core = EndlessRules.core(g)!!
        val before = EndlessRules.player(g).gold
        assertFalse(EndlessRules.ownershipAllowed(side,g.tileMap[HexCoord(-8,-8)]))
        assertTrue(runCatching { side.destroyCity(true) }.isFailure)
        assertTrue(runCatching { core.sellBuilding("Palace") }.isFailure)
        assertTrue(runCatching { core.cityConstructions.removeBuilding("Palace") }.isFailure)
        assertTrue(runCatching { EndlessRules.player(g).moveCapitalTo(side,core) }.isFailure)
        assertEquals(before,EndlessRules.player(g).gold); assertEquals(2,EndlessRules.player(g).cities.size)
    }

    @Test fun `all surviving cities receive bounded repair only once`() {
        val g = game(); unlock(g); val side = found(g,HexCoord(-4,-4)); finishStage(g,false)
        val core = EndlessRules.core(g)!!; core.health = 1; side.health = 1
        EndlessRules.applyReward(g,"Repair")
        assertEquals(61,core.health); assertEquals(61,side.health)
        assertEquals(3L,g.endless!!.stageState!!.completedStages)
    }

    @Test fun `side city target obeys visibility and recovers the core target after destruction`() {
        val g = game(); unlock(g); val side = found(g,HexCoord(-4,-4))
        g.endless!!.completedRounds = g.endless!!.stageState!!.nextStageRound - 1; EndlessRules.prepareRound(g)
        val enemy = EndlessRules.invaders(g).units.getCivUnits().first { it.baseUnit.isMelee() }
        enemy.removeFromTile(); enemy.putInTile(g.tileMap[HexCoord(-6,-6)]); enemy.civ.cache.updateViewableTiles()
        assertEquals(side.getCenterTile(),EndlessInvaderAI.stageTarget(enemy))
        enemy.civ.viewableTiles = enemy.civ.viewableTiles - side.getCenterTile()
        assertEquals(EndlessRules.core(g)!!.getCenterTile(),EndlessInvaderAI.stageTarget(enemy))
        g.endless!!.inTurn = true; side.destroyCity(true); g.endless!!.inTurn = false
        assertEquals(EndlessRules.core(g)!!.getCenterTile(),EndlessInvaderAI.stageTarget(enemy))
    }
}
