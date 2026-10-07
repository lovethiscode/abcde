package com.unciv.logic.automation.unit

import com.unciv.logic.automation.Timers.Companion.timeThis
import com.unciv.logic.automation.AutomationPreview
import com.unciv.logic.automation.civilization.NextTurnAutomation
import com.unciv.logic.battle.BattleDamage
import com.unciv.logic.battle.CityCombatant
import com.unciv.logic.battle.MapUnitCombatant
import com.unciv.logic.city.City
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.mapunit.movement.PathsToTilesWithinTurn
import com.unciv.logic.map.mapunit.movement.UnitMovement
import com.unciv.logic.map.tile.Tile
import yairm210.purity.annotations.Readonly
import com.unciv.models.ruleset.unique.UniqueType

object HeadTowardsEnemyCityAutomation {

    /** @returns whether the unit has taken this action */
    fun tryHeadTowardsEnemyCity(unit: MapUnit): Boolean = timeThis("tryHeadTowardsEnemyCity") {
        if (unit.civ.cities.isEmpty()) return false

        // only focus on *attacking* 1 enemy at a time otherwise you'll lose on both fronts
        val closestReachableEnemyCity = getEnemyCitiesByPriority(unit)
            .firstOrNull { unit.movement.canReach(it.getCenterTile()) }
            ?: return false // No enemy city reachable

        return headTowardsEnemyCity(
            unit,
            closestReachableEnemyCity.getCenterTile(),
            // This should be cached after the `canReach` call above.
            unit.movement.getShortestPath(closestReachableEnemyCity.getCenterTile())
        )
    }

    @Readonly
    fun getEnemyCitiesByPriority(unit: MapUnit): Sequence<City> {
        val enemies = unit.civ.getKnownCivs()
            .filter { unit.civ.isAtWarWith(it) && it.cities.isNotEmpty() }
            .toList()
        val victoryEnemies = enemies.filter { AutomationPreview.warTargetPriority(unit.civ, it) > 0f }
        if (AutomationPreview.strategy(unit.civ)?.combat in setOf(com.unciv.logic.automation.CombatPriority.Capital,
                com.unciv.logic.automation.CombatPriority.Nearest, com.unciv.logic.automation.CombatPriority.Counterattack)) {
            return AutomationPreview.targetCities(unit.civ, atWarOnly = true).asSequence()
                .filterNot { unit.baseUnit.isRanged() && it.health == 1 }
        }

        val closestEnemyCity = (victoryEnemies.ifEmpty { enemies })
            .mapNotNull { NextTurnAutomation.getForeignCityNearCapital(unit.civ.getCapital(), it) }
            .minByOrNull { it.aerialDistance }?.city
            ?: return emptySequence() // no attackable cities found

        val knownCapitals = closestEnemyCity.civ.cities.filter {
            it.isOriginalCapital && AutomationPreview.isConquestCapital(unit.civ, it)
        }.sortedBy { it.getCenterTile().aerialDistanceTo(unit.currentTile) }

        // Our main attack target is the closest city, but we're fine with deviating from that a bit
        var enemyCitiesByPriority = closestEnemyCity.civ.cities
            .associateWith { it.getCenterTile().aerialDistanceTo(closestEnemyCity.getCenterTile()) }
            .asSequence().filterNot { it.value > 10 } // anything 10 tiles away from the target is irrelevant
            .sortedBy { it.value }.map { it.key } // sort the list by closeness to target - least is best!

        enemyCitiesByPriority = (knownCapitals.asSequence() + enemyCitiesByPriority).distinct()

        if (unit.baseUnit.isRanged()) // ranged units don't harm capturable cities, waste of a turn
            enemyCitiesByPriority = enemyCitiesByPriority.filterNot { it.health == 1 }

        return enemyCitiesByPriority
    }


    private const val maxDistanceFromCityToConsiderForLandingArea = 5
    private const val minDistanceFromCityToConsiderForLandingArea = 3

    /** @returns whether the unit has taken this action */
    fun headTowardsEnemyCity(
        unit: MapUnit,
        closestReachableEnemyCity: Tile,
        shortestPath: List<Tile>
    ): Boolean {
        val destination = chooseMovementDestination(unit, closestReachableEnemyCity, shortestPath)
            ?: return false
        if (destination != unit.currentTile) {
            if (unit.getRange() <= 2 && destination == shortestPath.firstOrNull())
                unit.movement.moveToTile(destination)
            else unit.movement.headTowards(destination)
        }
        return true
    }

    /** Chooses the same city approach as the executing AI, without moving. */
    @Readonly
    fun chooseMovementDestination(
        unit: MapUnit,
        closestReachableEnemyCity: Tile,
        shortestPath: List<Tile>
    ): Tile? {
        val unitDistanceToTiles = unit.movement.getDistanceToTiles()

        val unitRange = unit.getRange()
        if (unitRange > 2) { // long-ranged unit, should never be in a bombardable position
            return chooseLongRangeDestination(closestReachableEnemyCity, unitDistanceToTiles, unitRange, unit)
        }

        val nextTileInPath = shortestPath.firstOrNull() ?: return unit.currentTile

        // None of the stuff below is relevant if we're still quite far away from the city, so we
        // short-circuit here for performance reasons.
        if (unit.currentTile.aerialDistanceTo(closestReachableEnemyCity) > maxDistanceFromCityToConsiderForLandingArea
            // Even in the worst case of only being able to move 1 tile per turn, we would still
            // not overshoot.
            && shortestPath.size > minDistanceFromCityToConsiderForLandingArea ) {
            return nextTileInPath
        }

        val city = closestReachableEnemyCity.getCity()!!
        val conquest = AutomationPreview.isConquestCapital(unit.civ, city)
        if (conquest && unit.baseUnit.isLandUnit && unit.baseUnit.isMelee() &&
            city.health > BattleDamage.calculateDamageToDefender(MapUnitCombatant(unit), CityCombatant(city)).coerceAtLeast(1) &&
            !AutomationPreview.canJoinConquestAssault(unit, city))
            return chooseLandingGround(closestReachableEnemyCity, unit)
        val ourUnitsAroundEnemyCity = closestReachableEnemyCity.getTilesInDistance(if (conquest) 3 else 6)
            .flatMap { it.getUnits() }
            .filter { it.isMilitary() && it.civ == unit.civ }
            .filter { !conquest || (it.health >= 50 && !it.isEmbarked() && !it.hasUnique(UniqueType.CannotAttack)) }

        if ((conquest && !AutomationPreview.hasConquestCaptureSupport(unit.civ, city)) ||
            cannotTakeCitySoon(ourUnitsAroundEnemyCity, city)) {
            return chooseLandingGround(closestReachableEnemyCity, unit)
        }

        if (conquest && unit.baseUnit.isProbablySiegeUnit()) {
            val firingPosition = unitDistanceToTiles.asSequence().filter { (tile, _) ->
                tile.aerialDistanceTo(closestReachableEnemyCity) == unitRange &&
                    (tile == unit.currentTile || unit.movement.canMoveTo(tile)) &&
                    unit.getDamageFromTerrain(tile) <= 0 &&
                    BattleHelper.visibleFollowupDamage(unit, tile) < unit.health &&
                    (unit.hasUnique(UniqueType.IndirectFire) || closestReachableEnemyCity in
                        tile.tileMap.getViewableTiles(tile.position, unitRange, true))
            }.sortedWith(compareByDescending<Map.Entry<Tile, UnitMovement.ParentTileAndTotalMovement>> { entry ->
                entry.key.neighbors.count { tile -> tile.militaryUnit?.let {
                    it.civ == unit.civ && it.baseUnit.isMelee() && it.health >= 50
                } == true }
            }.thenBy { entry -> entry.key.neighbors.count { it.militaryUnit?.civ?.isAtWarWith(unit.civ) == true } }
                .thenBy { it.value.totalMovement }).firstOrNull()?.key
            if (firingPosition != null) return firingPosition
            return chooseLandingGround(closestReachableEnemyCity, unit)
        }
        return nextTileInPath // go for it!
    }

    /** Cannot take within 5 turns */
    @Readonly
    private fun cannotTakeCitySoon(
        ourUnitsAroundEnemyCity: Sequence<MapUnit>,
        city: City
    ): Boolean {
        val cityCombatant = CityCombatant(city)
        val expectedDamagePerTurn = ourUnitsAroundEnemyCity
            .filterNot { AutomationPreview.militaryGoal(it.civ) && it.baseUnit.isWaterUnit &&
                it.baseUnit.isMelee() && !city.isNaval() }
            .sumOf { BattleDamage.calculateDamageToDefender(MapUnitCombatant(it), cityCombatant) }

        val cityHealingPerTurn = 20
        return expectedDamagePerTurn < city.health && // Cannot take immediately
            (expectedDamagePerTurn <= cityHealingPerTurn // No lasting damage
                || city.health / (expectedDamagePerTurn - cityHealingPerTurn) > 5) // Can damage, but will take more than 5 turns
    }

    @Readonly
    private fun chooseLandingGround(closestReachableEnemyCity: Tile, unit: MapUnit): Tile {
        // don't head straight to the city, try to head to landing grounds -
        // this is against tha AI's brilliant plan of having everyone embarked and attacking via sea when unnecessary.
        val maxDistance = if (AutomationPreview.isConquestCapital(unit.civ, closestReachableEnemyCity.getCity()!!))
            minDistanceFromCityToConsiderForLandingArea else maxDistanceFromCityToConsiderForLandingArea
        val tileToHeadTo = closestReachableEnemyCity.getTilesInDistanceRange(minDistanceFromCityToConsiderForLandingArea..maxDistance)
            .filter { it.isLand && unit.getDamageFromTerrain(it) <= 0 } // Don't head for hurty terrain
            .sortedBy { it.aerialDistanceTo(unit.currentTile) }
            .firstOrNull { tile ->
                if (!(unit.movement.canMoveTo(tile) || tile == unit.currentTile) || !unit.movement.canReach(tile)) false
                else if (!AutomationPreview.militaryGoal(unit.civ) || !unit.baseUnit.isRanged()) true
                else {
                    val nextTile = if (tile in unit.movement.getDistanceToTiles()) tile
                        else unit.movement.getShortestPath(tile).firstOrNull() ?: unit.currentTile
                    BattleHelper.visibleFollowupDamage(unit, nextTile) < unit.health
                }
            }
        return tileToHeadTo ?: unit.currentTile
    }

    @Readonly
    private fun chooseLongRangeDestination(
        closestReachableEnemyCity: Tile,
        unitDistanceToTiles: PathsToTilesWithinTurn,
        unitRange: Int,
        unit: MapUnit
    ): Tile? {
        // If we're already in fire range but have no sight, hold position
        // If there IS sight, automateUnitMoves would order attacking instead of heading to city
        if (closestReachableEnemyCity.getTilesInDistance(unitRange).contains(unit.getTile()))
            return unit.currentTile

        val tilesInBombardRange = closestReachableEnemyCity.getTilesInDistance(2).toSet()
        val candidateTiles = unitDistanceToTiles.asSequence().filter {
            it.key.aerialDistanceTo(closestReachableEnemyCity) >= unitRange
                && it.key !in tilesInBombardRange
                && unit.getDamageFromTerrain(it.key) <= 0 // Don't set up on a mountain 
                // Avoid mountains in path because unitDistanceToTiles parameter doesn't exclude them due to getMovementToTilesAtPosition
                && unit.movement.canMoveTo(it.key)
        }

        // Sort by closest distance to target city, then by the least amount of moves needed to get into fire range
        val tileToMoveTo = candidateTiles.sortedWith(compareBy(
            { it.key.aerialDistanceTo(closestReachableEnemyCity) },
            { it.value.totalMovement }
        )).firstOrNull()

        return tileToMoveTo?.key
    }
}
