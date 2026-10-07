package com.unciv.logic.automation.unit

import com.unciv.logic.automation.AutomationPreview
import com.unciv.logic.battle.AttackableTile
import com.unciv.logic.battle.Battle
import com.unciv.logic.battle.BattleDamage
import com.unciv.logic.battle.CityCombatant
import com.unciv.logic.battle.MapUnitCombatant
import com.unciv.logic.battle.TargetHelper
import com.unciv.logic.city.City
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.mapunit.movement.MovementCost
import com.unciv.logic.map.tile.Tile
import com.unciv.models.ruleset.unique.GameContext
import com.unciv.models.ruleset.unique.UniqueType
import yairm210.purity.annotations.LocalState
import yairm210.purity.annotations.Readonly

object BattleHelper {

    /** Returns true if the unit cannot further move this turn - NOT if an attack was successful! */
    fun tryAttackNearbyEnemy(unit: MapUnit, stayOnTile: Boolean = false): Boolean {
        if (unit.hasUnique(UniqueType.CannotAttack)) return false
        val distanceToTiles = unit.movement.getDistanceToTiles()
        val enemyTileToAttack = chooseAttackTarget(unit,
            TargetHelper.getAttackableEnemies(unit, distanceToTiles, stayOnTile=stayOnTile))

        if (enemyTileToAttack != null) {
            if (enemyTileToAttack.tileToAttack.militaryUnit == null && unit.baseUnit.isRanged()
                && unit.movement.canMoveTo(enemyTileToAttack.tileToAttack)
                && distanceToTiles.containsKey(enemyTileToAttack.tileToAttack)) { // Since the 'getAttackableEnemies' could return a tile we attack at range but cannot reach
                // Ranged units should move to caputre a civilian unit instead of attacking it
                unit.movement.moveToTile(enemyTileToAttack.tileToAttack)
            } else {
                Battle.moveAndAttack(MapUnitCombatant(unit), enemyTileToAttack)
            }
        }
        return !unit.hasMovement()
    }

    /** Selects a survivable attack without moving the unit or resolving combat. */
    @Readonly
    fun chooseAttackTarget(unit: MapUnit, candidates: List<AttackableTile>): AttackableTile? {
        if (unit.hasUnique(UniqueType.CannotAttack)) return null
        val attackableEnemies = candidates
            // Only take enemies we can fight without dying or are made to die
            .filter {
                val defender = Battle.getMapCombatantOfTile(it.tileToAttack)
                unit.hasUnique(UniqueType.SelfDestructs) || (defender != null &&
                (BattleDamage.calculateDamageToAttacker(
                    MapUnitCombatant(unit),
                    defender) < unit.health
                    && unit.getDamageFromTerrain(it.tileToAttackFrom) <= 0))
                    // For mounted units it is fine to attack from these tiles, but with current AI movement logic it is not easy to determine if our unit can meaningfully move away after attacking
                    // Also, AI doesn't build tactical roads
            }
            .filter { !AutomationPreview.militaryGoal(unit.civ) || !unit.baseUnit.isLandUnit ||
                isSafeMilitaryAttack(unit, it) }

        return rankAttackTarget(unit, attackableEnemies)
    }

    @Readonly
    private fun isSafeMilitaryAttack(unit: MapUnit, attack: AttackableTile): Boolean {
        val attacker = MapUnitCombatant(unit)
        val defender = Battle.getMapCombatantOfTile(attack.tileToAttack) ?: return false
        val retaliation = BattleDamage.calculateDamageToAttacker(attacker, defender, attack.tileToAttackFrom)
        val damage = BattleDamage.calculateDamageToDefender(attacker, defender, attack.tileToAttackFrom)
        val killsDefender = damage >= defender.getHealth()
        if (unit.type.name == "Scout" && !killsDefender && retaliation >= damage) {
            AutomationPreview.decision(unit.civ, "scout attack rejected unit=${unit.id} losing exchange damage=$damage retaliation=$retaliation")
            return false
        }
        // A legal immediate capture must remain possible; the city's bombardment then disappears.
        if (killsDefender && defender is CityCombatant && unit.baseUnit.isMelee()) return true

        // Evaluate the post-attack position on a detached unit, without changing the live map.
        val postTile = if (killsDefender && unit.baseUnit.isMelee()) attack.tileToAttack else attack.tileToAttackFrom
        val remaining = unit.health - retaliation
        val followupDamage = visibleFollowupDamage(unit, postTile, attack.tileToAttack.takeIf { killsDefender }, remaining)
        if (followupDamage < remaining) return true
        // Supported defenders must be able to clear a siege instead of all fleeing the same upper bound.
        if (unit.type.name != "Scout" && remaining >= 50 && damage > retaliation &&
            postTile == unit.currentTile && defender is MapUnitCombatant &&
            unit.civ.cities.any { city -> city.getCenterTile().aerialDistanceTo(postTile) <= 2 &&
                city.getCenterTile().aerialDistanceTo(attack.tileToAttack) <= 2 } &&
            postTile.getTilesInDistance(2).any { tile -> tile.militaryUnit?.let { ally ->
                ally != unit && ally.civ == unit.civ && ally.health >= 50 && ally.type.name != "Scout" &&
                    !ally.hasUnique(UniqueType.CannotAttack)
            } == true }) {
            AutomationPreview.decision(unit.civ, "supported city defense unit=${unit.id} remaining=$remaining visible followup=$followupDamage")
            return true
        }
        AutomationPreview.decision(unit.civ, "military attack rejected unit=${unit.id} remaining=$remaining visible followup=$followupDamage")
        return false
    }

    @Readonly
    fun visibleFollowupDamage(unit: MapUnit, postTile: Tile, defeatedTile: Tile? = null, health: Int = unit.health): Int {
        @LocalState val predicted = unit.clone()
        predicted.currentTile = postTile
        predicted.health = health
        predicted.turnsFortified = 0
        predicted.action = null
        predicted.setTransients(unit.civ.gameInfo.ruleset)
        val protectingCity = postTile.getCity()?.takeIf { postTile.isCityCenter() && it.civ == unit.civ }
        val postCombatant = if (protectingCity == null) MapUnitCombatant(predicted) else CityCombatant(protectingCity)
        var followupDamage = 0
        var canCaptureProtectingCity = false
        for (enemyCiv in unit.civ.getKnownCivs().filter { unit.civ.isAtWarWith(it) }) {
            for (enemy in enemyCiv.units.getCivUnits()) {
                val tile = enemy.currentTile
                if (!enemy.isMilitary() || !enemy.isVisibleTo(unit.civ) || tile == defeatedTile ||
                    enemy.hasUnique(UniqueType.CannotAttack) ||
                    enemy.baseUnit.isWaterUnit && enemy.baseUnit.isMelee() && !postTile.isWater) continue
                val positions = visibleAttackPositions(enemy, unit).filter { from ->
                    from.aerialDistanceTo(postTile) in 1..enemy.getRange() &&
                        (enemy.baseUnit.isMelee() || enemy.hasUnique(UniqueType.IndirectFire) || postTile in
                            from.tileMap.getViewableTiles(from.position, enemy.getRange(), true))
                }
                if (positions.isNotEmpty() && enemy.baseUnit.isMelee() && !enemy.hasUnique(UniqueType.CannotCaptureCities))
                    canCaptureProtectingCity = true
                followupDamage += positions.maxOfOrNull { from ->
                    BattleDamage.calculateDamageToDefender(MapUnitCombatant(enemy), postCombatant, from)
                } ?: 0
            }
            for (city in enemyCiv.cities) {
                val tile = city.getCenterTile()
                if (tile != defeatedTile && tile.isVisible(unit.civ) &&
                    tile.aerialDistanceTo(postTile) <= city.getBombardRange())
                    followupDamage += BattleDamage.calculateDamageToDefender(CityCombatant(city), postCombatant, tile)
            }
        }
        // A garrison is attacked through its city; it is lost only if the city can be captured.
        return if (protectingCity == null) followupDamage
            else if (canCaptureProtectingCity && followupDamage >= protectingCity.health) health else 0
    }

    /** Next-turn movement through currently visible terrain only; hidden armies are not inspected. */
    @Readonly
    private fun visibleAttackPositions(enemy: MapUnit, observer: MapUnit): Set<Tile> {
        val origin = enemy.currentTile
        if (!enemy.baseUnit.isLandUnit || enemy.hasUnique(UniqueType.MustSetUp) || enemy.cache.cannotMove)
            return setOf(origin)
        val movement = enemy.getMaxMovement().toFloat()
        @LocalState val costs = hashMapOf(origin to 0f)
        @LocalState val pending = java.util.ArrayDeque<Tile>()
        pending.add(origin)
        while (pending.isNotEmpty()) {
            val from = pending.removeFirst()
            for (to in from.neighbors) {
                if (!to.isVisible(observer.civ) || !enemy.movement.canPassThrough(to, false)) continue
                val cost = costs.getValue(from) + MovementCost.getMovementCostBetweenAdjacentTiles(enemy, from, to)
                // Moving with no movement left cannot be followed by an attack.
                if (cost >= movement || cost >= (costs[to] ?: Float.MAX_VALUE)) continue
                costs[to] = cost
                pending.add(to)
            }
        }
        return costs.keys.filter { it == origin || enemy.movement.canMoveTo(it, includeOtherEscortUnit = false) }.toSet()
    }

    fun tryDisembarkUnitToAttackPosition(unit: MapUnit): Boolean {
        if (!unit.baseUnit.isMelee() || !unit.baseUnit.isLandUnit || !unit.isEmbarked()) return false
        val unitDistanceToTiles = unit.movement.getDistanceToTiles()

        val attackableEnemiesNextTurn = TargetHelper.getAttackableEnemies(unit, unitDistanceToTiles)
                // Only take enemies we can fight without dying
                .filter {
                    BattleDamage.calculateDamageToAttacker(
                        MapUnitCombatant(unit),
                        Battle.getMapCombatantOfTile(it.tileToAttack)!!
                    ) < unit.health
                }
                .filter { it.tileToAttackFrom.isLand }

        val enemyTileToAttackNextTurn = rankAttackTarget(unit, attackableEnemiesNextTurn)

        if (enemyTileToAttackNextTurn != null) {
            unit.movement.moveToTile(enemyTileToAttackNextTurn.tileToAttackFrom)
            return true
        }
        return false
    }

    /**
     * Choses the best target in attackableEnemies, this could be a city or a unit.
     */
    @Readonly
    private fun rankAttackTarget(unit: MapUnit, attackableEnemies: List<AttackableTile>): AttackableTile? {
        // Get the highest valued attackableEnemy
        var highestAttackValue = 0
        var attackTile: AttackableTile? = null
        // We always have to calculate the attack value even if there is only one attackableEnemy
        for (attackableEnemy in attackableEnemies) {
            val tempAttackValue = if (attackableEnemy.tileToAttack.isCityCenter())
                getCityAttackValue(unit, attackableEnemy.tileToAttack.getCity()!!)
            else getUnitAttackValue(unit, attackableEnemy)
            if (tempAttackValue > highestAttackValue) {
                highestAttackValue = tempAttackValue
                attackTile = attackableEnemy
            }
        }
        // todo For air units, prefer to attack tiles with lower intercept chance
        // Only return that tile if it is actually a good tile to attack
        return if (highestAttackValue > 30) attackTile else null
    }

    /**
     * Returns a value which represents the attacker's motivation to attack a city.
     * Siege units will almost always attack cities.
     * Base value is 100(Mele) 110(Ranged) standard deviation is around 80 to 130
     */
    @Readonly
    private fun getCityAttackValue(attacker: MapUnit, city: City): Int {
        val attackerUnit = MapUnitCombatant(attacker)
        val cityUnit = CityCombatant(city)
        
        val canCaptureCity = attacker.baseUnit.isMelee() && !attacker.hasUnique(UniqueType.CannotCaptureCities)
        if (city.health == 1)
            return if (canCaptureCity) 10000 // Capture the city immediately!
            else 0 // No reason to attack, we won't make any difference
        
        if (canCaptureCity && city.health <= BattleDamage.calculateDamageToDefender(attackerUnit, cityUnit).coerceAtLeast(1))
            return 10000
        if (canCaptureCity && AutomationPreview.isConquestCapital(attacker.civ, city) &&
            !AutomationPreview.canJoinConquestAssault(attacker, city)) return 0
            

        if (attacker.baseUnit.isMelee()) {
            val battleDamage = BattleDamage.calculateDamageToAttacker(attackerUnit, cityUnit)
            if (attacker.health - battleDamage * 2 <= 0 && !attacker.hasUnique(UniqueType.SelfDestructs)) {
                // The more fiendly units around the city, the more willing we should be to just attack the city
                var friendlyUnitsAroundCity = 0
                city.getCenterTile().forEachTileInDistance(3) { if (it.militaryUnit?.civ == attacker.civ) friendlyUnitsAroundCity++ }
                // If we have more than 4 other units around the city, go for it
                if (friendlyUnitsAroundCity < 5) {
                    val attackerHealthModifier = 1.0 + 1.0 / friendlyUnitsAroundCity
                    if (attacker.health - battleDamage * attackerHealthModifier <= 0)
                        return 0 // We'll probably die next turn if we attack the city
                }
            }
        }

        var attackValue = 100
        // Siege units should really only attack the city
        if (attacker.baseUnit.isProbablySiegeUnit()) attackValue += 100
        // Ranged units don't take damage from the city
        else if (attacker.baseUnit.isRanged()) attackValue += 10
        if (attacker.baseUnit.isProbablySiegeUnit() && AutomationPreview.isConquestCapital(attacker.civ, city) &&
            AutomationPreview.hasConquestCaptureSupport(attacker.civ, city)) attackValue += 150
        // Lower health cities have a higher priority to attack ranges from -20 to 30
        attackValue -= (city.health - 60) / 2

        // Add value based on number of units around the city
        val defendingCityCiv = city.civ
        city.getCenterTile().forEachTileInDistance(2) {
            if (it.militaryUnit != null) {
                if (it.militaryUnit!!.civ.isAtWarWith(attacker.civ))
                    attackValue -= 5
                if (it.militaryUnit!!.civ.isAtWarWith(defendingCityCiv))
                    attackValue += 15
            }
        }

        return attackValue
    }

    /**
     * Returns a value which represents the attacker's motivation to attack a unit.
     * Base value is 100 and standard deviation is around 80 to 130
     */
    @Readonly
    private fun getUnitAttackValue(attacker: MapUnit, attackTile: AttackableTile): Int {
        // Base attack value, there is nothing there...
        var attackValue = Int.MIN_VALUE
        // Prioritize attacking military
        val militaryUnit = attackTile.tileToAttack.militaryUnit
        val civilianUnit = attackTile.tileToAttack.civilianUnit
        if (militaryUnit != null) {
            attackValue = 200 - militaryUnit.health + // continuously prioritise lower-health units
                BattleDamage.calculateDamageToDefender(MapUnitCombatant(attacker), MapUnitCombatant(militaryUnit))
        } else if (civilianUnit != null) {
            attackValue = 50
            // Only melee units should really attack/capture civilian units, ranged units may be able to capture by moving
            if (attacker.baseUnit.isMelee() || attacker.movement.canReachInCurrentTurn(attackTile.tileToAttack)) {
                if (civilianUnit.isGreatPerson()) {
                    attackValue += 150
                }
                if (civilianUnit.hasUnique(UniqueType.FoundCity, GameContext.IgnoreConditionals)) attackValue += 60
            } else if (attacker.baseUnit.isRanged() && !civilianUnit.hasUnique(UniqueType.Uncapturable)) {
                return 10 // Don't shoot civilians that we can capture!
            }
        }
        // Prioritise closer units as they are generally more threatening to this unit
        // Moving around less means we are straying less into enemy territory
        // Average should be around 2.5-5 early game and up to 35 for tanks in late game
        attackValue += (attackTile.movementLeftAfterMovingToAttackTile * 5).toInt()

        return attackValue
    }
}
