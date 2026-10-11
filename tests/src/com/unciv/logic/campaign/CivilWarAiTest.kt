package com.unciv.logic.campaign

import com.badlogic.gdx.Gdx
import com.unciv.UncivGame
import com.unciv.logic.GameInfo
import com.unciv.logic.PlayerDecisionControl
import com.unciv.logic.battle.Battle
import com.unciv.logic.battle.CityCombatant
import com.unciv.logic.battle.MapUnitCombatant
import com.unciv.logic.files.FileConversions
import com.unciv.logic.map.HexMath
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Explicitly constructed AI inspection positions, separate from legal opening-to-win routes. */
@RunWith(BaseTestRunner::class)
class CivilWarAiTest {
    private lateinit var g: GameInfo
    private val evidence = System.getProperty("unciv.campaignEvidenceDir")?.let {
        File(it, "civil-war-ai-${System.nanoTime()}").apply { mkdirs() }
    }
    @Before fun setup() {
        TestGame()
        g = CampaignCatalog.newAttempt(CivilWarCampaign.id)
        UncivGame.Current.gameInfo = g
        PlayerDecisionControl.acquire(g, g.campaign!!.playerId)
    }
    @After fun teardown() { PlayerDecisionControl.release() }
    private fun unit(id: Int) = g.civilizations.flatMap { it.units.getCivUnits().toList() }.first { it.id == id }
    private fun city(id: String) = g.getCities().first { it.id == id }
    private fun place(u: MapUnit, tile: Tile) {
        check(tile.militaryUnit == null)
        u.removeFromTile(); u.putInTile(tile)
        g.civilizations.forEach { it.cache.updateViewableTiles() }
    }
    private fun place(u: MapUnit, column: Int, row: Int) = place(u, g.tileMap[HexMath.getTileCoordsFromColumnRow(column, row)])
    private fun save(name: String) {
        if (evidence != null) FileConversions.writeJson(Gdx.files.absolute(File(evidence, "$name.json").path), g, true)
    }

    @Test fun `capital retains infantry and artillery while the front advances`() {
        val capital = city("cw-richmond").getCenterTile()
        val guards = listOf(unit(20), unit(21), unit(26))
        save("rear-defense-before")
        repeat(3) {
            g.nextTurn()
            save("rear-defense-after-${it + 1}")
            assertTrue(guards.all { guard -> !guard.isDestroyed && guard.currentTile.aerialDistanceTo(capital) <= 2 })
        }
        assertTrue("The field army must still engage", g.campaign!!.lostMilitary.isNotEmpty() ||
            g.getCurrentPlayerCivilization().units.getCivUnits().any { it.health < 100 })
    }

    @Test fun `rear defense never controls human units or ordinary games`() {
        assertFalse(CivilWarCampaign.holdDefensivePosition(unit(5)))
        val guard = unit(20)
        g.campaign = null
        assertFalse(CivilWarCampaign.holdDefensivePosition(guard))
    }

    @Test fun `a damaged front-line city does not empty the capital defense`() {
        val capital = city("cw-richmond").getCenterTile()
        val guards = listOf(unit(20), unit(21), unit(26))
        place(unit(1), 0, -3)
        city("cw-fredericksburg").health = 120
        save("front-siege-before")
        repeat(2) {
            g.nextTurn()
            save("front-siege-after-${it + 1}")
            assertTrue(guards.all { guard -> !guard.isDestroyed && guard.currentTile.aerialDistanceTo(capital) <= 2 })
        }
    }

    @Test fun `visible capital threat mobilizes an available reserve within two enemy turns`() {
        val capital = city("cw-richmond").getCenterTile()
        place(unit(1), 0, -7); place(unit(10), 0, -6)
        val reserve = unit(22)
        place(reserve, -1, -10)
        val start = reserve.currentTile
        assertTrue(unit(1).currentTile.isVisible(reserve.civ))
        save("capital-before")
        var responded = false
        repeat(2) {
            g.nextTurn(); save("capital-after-${it + 1}")
            responded = responded || !reserve.isDestroyed && (reserve.currentTile != start &&
                reserve.currentTile.aerialDistanceTo(capital) <= 3 || reserve.attacksThisTurn > 0)
        }
        assertTrue("Reserve must enter the defense area or fight", responded)
    }

    @Test fun `exposed cannon receives retreat support or removes its attacker next enemy turn`() {
        val cannon = unit(26); val threat = unit(8)
        place(threat, 5, -11)
        cannon.health = 35
        val old = cannon.currentTile
        val originalSupport = cannon.civ.units.getCivUnits().filter { it.isMilitary() && it != cannon && it.currentTile.aerialDistanceTo(old) <= 1 }.map { it.id }.toSet()
        assertTrue(threat.currentTile.isVisible(cannon.civ))
        save("cannon-before")
        g.nextTurn(); save("cannon-after")
        val newSupport = cannon.civ.units.getCivUnits().any { it.isMilitary() && it != cannon && it.id !in originalSupport && it.currentTile.aerialDistanceTo(cannon.currentTile) <= 1 }
        assertTrue("Cannon must be preserved with a concrete response", !cannon.isDestroyed &&
            (threat.isDestroyed || cannon.currentTile != old && cannon.currentTile.aerialDistanceTo(threat.currentTile) > old.aerialDistanceTo(threat.currentTile) || newSupport))
    }

    @Test fun `depleted army starts legal recruiting in two turns and completes within production deadline`() {
        val enemy = g.civilizations.first { it.isAI() }
        val initialUnitIds = enemy.units.getCivUnits().map { it.id }.toSet()
        listOf(16, 17, 23, 30, 31).forEach { unit(it).destroy() }
        for (c in enemy.cities) c.cityConstructions.editQueue { clear(); add("Gold") }
        save("recruit-before")
        repeat(2) { g.nextTurn(); save("recruit-start-${it + 1}") }
        assertTrue(enemy.cities.any { it.id in CivilWarCampaign.recruitmentCities && it.cityConstructions.currentConstructionName() in CivilWarCampaign.militaryUnits })
        repeat(5) { g.nextTurn() }
        save("recruit-completed")
        assertTrue(enemy.units.getCivUnits().any { it.id !in initialUnitIds && it.name in CivilWarCampaign.militaryUnits })
    }

    @Test fun `an available attack on Washington defenders is executed within two enemy turns`() {
        val defender = unit(6)
        place(defender, 5, 8)
        val attacker = unit(17)
        place(attacker, 5, 7)
        place(unit(25), 6, 7)
        assertTrue(defender.currentTile.isVisible(attacker.civ))
        val health = defender.health
        val capitalHealth = city("cw-washington").health
        save("washington-before")
        repeat(2) { g.nextTurn(); save("washington-after-${it + 1}") }
        assertTrue(defender.isDestroyed || defender.health < health || city("cw-washington").health < capitalHealth)
    }

    @Test fun `garrison destroyed by city conquest is a combat loss`() {
        val target = city("cw-alexandria")
        val guard = unit(6)
        place(guard, target.getCenterTile())
        val attacker = unit(17)
        place(attacker, target.getCenterTile().neighbors.first { it.militaryUnit == null })
        target.health = 1
        save("garrison-before")
        Battle.attack(MapUnitCombatant(attacker), CityCombatant(target))
        save("garrison-after")
        assertTrue(guard.isDestroyed)
        assertEquals("Combat", g.campaign!!.lossReasons[guard.id.toString()])
    }
}
