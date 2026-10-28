package com.unciv.logic.endless

import com.unciv.view.GameView
import com.unciv.logic.automation.unit.UnitAutomation
import com.unciv.logic.battle.Battle
import com.unciv.logic.battle.MapUnitCombatant
import com.unciv.logic.battle.TargetHelper
import com.unciv.logic.civilization.Civilization

/** The known fortress is the strategic target; tactical targets use normal visibility. */
object EndlessInvaderAI {
    fun play(civ: Civilization) {
        if (civ.gameInfo.endless?.modeVersion == 2) { playStage(civ); return }
        val game = civ.gameInfo
        val core = EndlessRules.core(game)?.getCenterTile() ?: return
        for (unit in civ.units.getCivUnits().sortedWith(compareBy({ !it.baseUnit.isRanged() }, { it.id })).toList()) {
            if (game.endless!!.ended) break
            if (unit.isDestroyed || !unit.hasMovement()) continue
            fun attack(): Boolean {
                if (!unit.canAttack()) return false
                val targets = TargetHelper.getAttackableEnemies(unit, unit.movement.getDistanceToTiles())
                val target = targets.maxByOrNull {
                    when {
                        it.tileToAttack == core -> if (unit.baseUnit.isMelee() && EndlessRules.core(game)!!.health < 40) 1000 else 80
                        it.tileToAttack.militaryUnit?.baseUnit?.isRanged() == true -> 100
                        else -> 60
                    } - it.tileToAttackFrom.aerialDistanceTo(core)
                } ?: return false
                Battle.moveAndAttack(MapUnitCombatant(unit), target)
                return true
            }
            if (attack()) continue
            if (unit.name in setOf("Horseman", "Knight") && UnitAutomation.tryPillageImprovement(GameView(game, civ).getMapUnitView(unit))) continue
            // Recompute every turn, including when congestion has changed. Search at most 18 goals.
            val destination = core.getTilesInDistance(2).filter { it != core && unit.movement.canMoveTo(it) }
                .sortedWith(compareBy({ it.aerialDistanceTo(unit.currentTile) }, { it.aerialDistanceTo(core) }, { it.position.x }, { it.position.y }))
                .firstOrNull { unit.movement.canReach(it) }
            if (destination != null) unit.movement.headTowards(destination)
            if (!game.endless!!.ended && !unit.isDestroyed) attack()
        }
    }

    /** Static distances ignore armies; actual movement and attacks still use the engine. */
    fun stageTarget(unit: com.unciv.logic.map.mapunit.MapUnit): com.unciv.logic.map.tile.Tile {
        val game = unit.civ.gameInfo
        val core = EndlessRules.core(game)!!.getCenterTile()
        fun distances(from: com.unciv.logic.map.tile.Tile): Map<com.unciv.logic.map.tile.Tile,Int> {
            val result = linkedMapOf(from to 0)
            val queue = java.util.ArrayDeque<com.unciv.logic.map.tile.Tile>(); queue.add(from)
            while (queue.isNotEmpty()) {
                val tile = queue.removeFirst()
                for (next in tile.neighbors.sortedWith(compareBy({it.position.x},{it.position.y})))
                    if (next.isLand && !next.isImpassible() && next !in result) {
                        result[next] = result.getValue(tile) + 1; queue.add(next)
                    }
            }
            return result
        }
        val toCore = distances(core); val fromUnit = distances(unit.currentTile)
        val direct = toCore[unit.currentTile] ?: return core
        val path = arrayListOf(unit.currentTile)
        while (path.last() != core) {
            val next = path.last().neighbors.filter { toCore[it] == toCore.getValue(path.last()) - 1 }
                .minWithOrNull(compareBy({it.position.x},{it.position.y})) ?: return core
            path.add(next)
        }
        return EndlessRules.player(game).cities.filter { it.id != game.endless!!.coreId && it.getCenterTile() in unit.civ.viewableTiles }
            .filter { city ->
                val tile = city.getCenterTile()
                fromUnit[tile] != null && toCore[tile] != null && fromUnit.getValue(tile) + toCore.getValue(tile) <= direct + 2 &&
                    path.minOf { it.aerialDistanceTo(tile) } <= 3
            }.minWithOrNull(compareBy({fromUnit.getValue(it.getCenterTile())},{toCore.getValue(it.getCenterTile())},{it.id}))?.getCenterTile() ?: core
    }

    private fun playStage(civ: Civilization) {
        val game = civ.gameInfo
        val core = EndlessRules.core(game)?.getCenterTile() ?: return
        for (unit in civ.units.getCivUnits().sortedWith(compareBy({!it.baseUnit.isRanged()},{it.id})).toList()) {
            if (game.endless!!.ended) break
            if (unit.isDestroyed || !unit.hasMovement()) continue
            val goal = stageTarget(unit)
            val member = game.endless!!.waves.flatMap { it.members }.single { it.unitId == unit.id }
            val original = unit.currentTile
            fun attack(): Boolean {
                if (!unit.canAttack()) return false
                val target = TargetHelper.getAttackableEnemies(unit,unit.movement.getDistanceToTiles()).filter {
                    it.tileToAttack == core || it.tileToAttack.aerialDistanceTo(goal) <= 2
                }.maxByOrNull {
                    when {
                        it.tileToAttack == core && unit.baseUnit.isMelee() && EndlessRules.core(game)!!.health <=
                            com.unciv.logic.battle.BattleDamage.calculateDamageToDefender(MapUnitCombatant(unit),com.unciv.logic.battle.CityCombatant(EndlessRules.core(game)!!),it.tileToAttackFrom) -> 1000
                        it.tileToAttack == goal -> 150
                        it.tileToAttack.militaryUnit?.baseUnit?.isRanged() == true -> 100
                        else -> 60
                    } - it.tileToAttackFrom.aerialDistanceTo(goal)
                } ?: return false
                Battle.moveAndAttack(MapUnitCombatant(unit),target)
                member.idleRounds = 0; member.stallReason = ""
                return true
            }
            if (attack()) continue
            if (unit.name in setOf("Horseman","Knight") && unit.currentTile.aerialDistanceTo(goal) <= 2 && UnitAutomation.tryPillageImprovement(GameView(game, civ).getMapUnitView(unit))) continue
            val destination = goal.getTilesInDistance(2).filter { it != goal && it != unit.currentTile && unit.movement.canMoveTo(it) }
                .sortedWith(compareBy({it.aerialDistanceTo(unit.currentTile)},{it.aerialDistanceTo(goal)},{it.position.x},{it.position.y}))
                .firstOrNull { unit.movement.canReach(it) }
            if (destination != null) unit.movement.headTowards(destination)
            if (unit.currentTile != original) { member.idleRounds = 0; member.stallReason = "" }
            else {
                member.idleRounds = minOf(3,member.idleRounds + 1)
                if (member.idleRounds == 3) member.stallReason = "No legal movement towards the target. Recomputing next turn."
            }
            if (!game.endless!!.ended && !unit.isDestroyed) attack()
        }
    }
}
