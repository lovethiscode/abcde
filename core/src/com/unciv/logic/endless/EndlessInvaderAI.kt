package com.unciv.logic.endless

import com.unciv.logic.automation.unit.UnitAutomation
import com.unciv.logic.battle.Battle
import com.unciv.logic.battle.MapUnitCombatant
import com.unciv.logic.battle.TargetHelper
import com.unciv.logic.civilization.Civilization

/** The known fortress is the strategic target; tactical targets use normal visibility. */
object EndlessInvaderAI {
    fun play(civ: Civilization) {
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
            if (unit.name in setOf("Horseman", "Knight") && UnitAutomation.tryPillageImprovement(unit)) continue
            // Recompute every turn, including when congestion has changed. Search at most 18 goals.
            val destination = core.getTilesInDistance(2).filter { it != core && unit.movement.canMoveTo(it) }
                .sortedWith(compareBy({ it.aerialDistanceTo(unit.currentTile) }, { it.aerialDistanceTo(core) }, { it.position.x }, { it.position.y }))
                .firstOrNull { unit.movement.canReach(it) }
            if (destination != null) unit.movement.headTowards(destination)
            if (!game.endless!!.ended && !unit.isDestroyed) attack()
        }
    }
}
