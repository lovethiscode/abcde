package com.unciv.logic.achievements

import com.unciv.Constants
import com.unciv.logic.GameInfo
import com.unciv.logic.battle.CityCombatant
import com.unciv.logic.battle.ICombatant
import com.unciv.logic.battle.MapUnitCombatant
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.logic.trade.Trade
import com.unciv.logic.trade.TradeOfferType
import com.unciv.models.ruleset.Building
import com.unciv.models.ruleset.tile.ResourceType
import com.unciv.models.ruleset.unique.UniqueType

/** Source events only. Does not write permanent honors, synthesize actions, or expose unexplored tiles. */
object AchievementExtendedRules {
    fun state(game: GameInfo) = game.achievements?.takeIf {
        it.recordingVersion >= 4 && !it.ended && it.gameId == game.gameId && game.endless == null && game.campaign == null
    }
    private fun state(civ: Civilization) = state(civ.gameInfo)?.takeIf { it.playerId == civ.civID }
    private fun facts(civ: Civilization) = state(civ)?.history?.extended
    private fun done(civ: Civilization, id: String) { state(civ)?.history?.completed?.add(id) }
    private fun player(game: GameInfo) = state(game)?.let { s -> game.civilizations.firstOrNull { it.civID == s.playerId } }

    fun harborConnected(city: City): Boolean {
        val capital = city.civ.getCapital() ?: return false
        val continent = city.getCenterTile().getContinent()
        return !city.isCapital() && city.foundingCivObject == city.civ && continent >= 0 &&
            capital.getCenterTile().getContinent() >= 0 && continent != capital.getCenterTile().getContinent() &&
            city.containsBuildingUnique(UniqueType.ConnectTradeRoutes) &&
            city.civ.cache.citiesConnectedToCapitalToMediums[city].orEmpty().any { it.name.startsWith("Harbor") }
    }
    fun evaluate(game: GameInfo, atTurnEnd: Boolean): Set<String> {
        val player = player(game) ?: return emptySet()
        return if (atTurnEnd && player.cities.any(::harborConnected)) setOf("F04") else emptySet()
    }

    private fun domestic(civ: Civilization, resource: String) = civ.detailedCivResources.filter {
        it.resource.name == resource && it.origin != "Trade" && it.origin != Constants.cityStates && it.amount > 0
    }.sumOf { it.amount }
    private fun exports(civ: Civilization, resource: String) = civ.diplomacy.values.sumOf { diplomacy ->
        diplomacy.trades.sumOf { trade -> trade.ourOffers.filter {
            it.type == TradeOfferType.Luxury_Resource && it.name == resource && it.amount > 0
        }.sumOf { it.amount } }
    }
    class LuxuryExchange(val player: Civilization, val exported: String, val imported: String)
    fun beforeTrade(first: Civilization, second: Civilization, trade: Trade): List<LuxuryExchange> {
        if (!first.isMajorCiv || !second.isMajorCiv) return emptyList()
        first.cache.updateCivResources(); second.cache.updateCivResources()
        if (!com.unciv.logic.trade.TradeEvaluation().isTradeValid(trade, first, second)) return emptyList()
        val result = arrayListOf<LuxuryExchange>()
        for ((civ, offers) in listOf(first to trade, second to trade.reverse())) {
            if (state(civ) == null) continue
            civ.cache.updateCivResources()
            fun luxury(name: String) = civ.gameInfo.ruleset.tileResources[name]?.resourceType == ResourceType.Luxury
            val outgoing = offers.ourOffers.filter { it.type == TradeOfferType.Luxury_Resource && it.amount > 0 && luxury(it.name) }.groupBy { it.name }
            val incoming = offers.theirOffers.filter { it.type == TradeOfferType.Luxury_Resource && it.amount > 0 && luxury(it.name) }
            for ((name, entries) in outgoing) if (domestic(civ, name) - exports(civ, name) - entries.sumOf { it.amount } >= 1)
                for (entry in incoming) if (civ.getResourceAmount(entry.name) <= 0)
                    result.add(LuxuryExchange(civ, name, entry.name))
        }
        return result
    }
    fun afterTrade(candidates: List<LuxuryExchange>) {
        for (candidate in candidates) {
            val civ = candidate.player
            if (domestic(civ, candidate.exported) - exports(civ, candidate.exported) >= 1 &&
                civ.getResourceAmount(candidate.exported) > 0 && civ.getResourceAmount(candidate.imported) > 0)
                done(civ, "F05")
        }
    }

    fun captureStarted(unit: MapUnit, city: City) {
        val f = facts(unit.civ) ?: return
        val support = f.siege[city.id]
        f.captures[city.id] = AchievementNewCapture().apply {
            actor = unit.civ.civID; defender = city.civ.civID; previouslyOwned = city.id in f.ownedCities
            warEpoch = f.warEpochs[defender] ?: 0
            coordinated = city.civ.isMajorCiv && unit.civ.isAtWarWith(city.civ) && unit.baseUnit.isMelee() &&
                support != null && support.defender == defender && support.warEpoch == warEpoch &&
                support.ownershipEpoch == (f.ownershipEpochs[city.id] ?: 0) && support.supporters.any { it != unit.id.toString() }
        }
    }
    fun captureSettled(city: City) {
        val f = facts(city.civ) ?: return
        val capture = f.captures.remove(city.id) ?: return
        f.ownedCities.add(city.id)
        if (capture.coordinated && capture.warEpoch == (f.warEpochs[capture.defender] ?: 0)) done(city.civ, "F08")
        if (city.foundingCivObject?.let { it != city.civ && it.isMajorCiv } == true)
            f.recovery[city.id] = AchievementRecovery().apply { if (!city.isInResistance()) baseline = city.population.population }
    }
    fun liberated(city: City, conqueror: Civilization) {
        val f = facts(conqueror) ?: return
        val capture = f.captures.remove(city.id) ?: return
        if (!capture.previouslyOwned && capture.actor == conqueror.civID && city.civ == city.foundingCivObject &&
            city.civ != conqueror && (city.civ.isMajorCiv || city.civ.isCityState)) done(conqueror, "F06")
    }
    fun ownershipChanged(city: City, from: Civilization, to: Civilization?) {
        com.unciv.logic.endless.EndlessAchievementRules.cityLost(from.gameInfo, city.id, from.civID, to?.civID)
        val player = player(from.gameInfo) ?: return
        val f = facts(player) ?: return
        f.ownershipEpochs[city.id] = (f.ownershipEpochs[city.id] ?: 0) + 1
        f.siege.remove(city.id)
        if (from == player && to != player) {
            f.relief.remove(city.id); f.recovery.remove(city.id); f.romanProduction.remove(city.id)
            // A pending choice cannot carry support through an unrelated transfer.
            f.captures[city.id]?.coordinated = false
        }
        if (to == player && city.id !in f.captures) f.ownedCities.add(city.id)
    }
    fun founded(city: City) { facts(city.civ)?.ownedCities?.add(city.id) }
    fun peace(first: Civilization, second: Civilization) {
        for ((civ, opponent) in listOf(first to second, second to first)) facts(civ)?.let { f ->
            f.warEpochs[opponent.civID] = (f.warEpochs[opponent.civID] ?: 0) + 1
            f.siege.entries.removeAll { it.value.defender == opponent.civID }
        }
    }
    private fun enemies(city: City, player: Civilization) = city.getCenterTile().neighbors.mapNotNull { it.militaryUnit }
        .filter { it.civ.isMajorCiv && player.isAtWarWith(it.civ) && it.isVisibleTo(player) }.map { it.id.toString() }.toSet()
    fun visibleTurnStart(civ: Civilization) {
        val f = facts(civ) ?: return
        if (f.observedTurn == civ.gameInfo.turns) { resistanceChanged(civ); return }
        finishWindows(civ)
        f.observedTurn = civ.gameInfo.turns
        for (city in civ.cities.filter { it.foundingCivObject == civ }) {
            val observed = enemies(city, civ)
            if (observed.isNotEmpty()) f.relief[city.id] = AchievementRelief().apply {
                turn = civ.gameInfo.turns; enemies.addAll(observed)
            }
        }
        resistanceChanged(civ)
    }
    fun finishWindows(civ: Civilization, terminalVictory: Boolean = false) {
        val f = facts(civ) ?: return
        val turn = civ.gameInfo.turns
        for ((id, window) in f.relief.toMap()) if (window.turn < turn || terminalVictory) {
            val city = civ.cities.firstOrNull { it.id == id }
            if (city != null && window.defeated && enemies(city, civ).isEmpty()) done(civ, "F07")
            f.relief.remove(id)
        }
        for ((windows, id) in listOf(f.chuKoNu to "R34", f.keshik to "F14"))
            for ((unitId, window) in windows.toMap()) if (window.turn < turn || terminalVictory) {
                val unit = civ.units.getUnitById(unitId.toInt())
                if (unit != null && !unit.isDestroyed && (if (id == "R34") window.attacks >= 2 else window.attacks >= 1 && window.moved)) done(civ, id)
                windows.remove(unitId)
            }
    }
    fun militaryDefeated(civ: Civilization, victim: Int, major: Boolean) {
        if (!major || !civ.isCurrentPlayer()) return
        facts(civ)?.relief?.values?.filter { it.turn == civ.gameInfo.turns && victim.toString() in it.enemies }?.forEach { it.defeated = true }
    }
    fun activeAttack(attacker: ICombatant, defender: ICombatant, damage: Int) {
        val combatant = attacker as? MapUnitCombatant ?: return
        val unit = combatant.unit; val civ = unit.civ; val f = facts(civ) ?: return
        if (damage <= 0 || !civ.isCurrentPlayer() || !defender.getCivInfo().isMajorCiv || !civ.isAtWarWith(defender.getCivInfo())) return
        if (defender is CityCombatant && (unit.baseUnit.unitType == "Siege" || unit.baseUnit.unitType == "Ranged Water")) {
            val city = defender.city
            val support = f.siege.getOrPut(city.id) { AchievementSiege().apply {
                this.defender = city.civ.civID; warEpoch = f.warEpochs[this.defender] ?: 0; ownershipEpoch = f.ownershipEpochs[city.id] ?: 0
            } }
            support.supporters.add(unit.id.toString())
        }
        if (defender !is CityCombatant && (defender !is MapUnitCombatant || !defender.unit.isMilitary())) return
        if (unit.isDestroyed) return
        val windows = when (unit.name) { "Chu-Ko-Nu" -> f.chuKoNu; "Keshik" -> f.keshik; else -> return }
        val window = windows.getOrPut(unit.id.toString()) { AchievementAttackWindow().apply { turn = civ.gameInfo.turns } }
        if (window.turn == civ.gameInfo.turns) window.attacks = minOf(2, window.attacks + 1)
    }
    fun voluntaryMove(unit: MapUnit, origin: Tile) {
        if (unit.name != "Keshik" || !unit.civ.isCurrentPlayer() || unit.isDestroyed || unit.getTile() == origin) return
        facts(unit.civ)?.keshik?.get(unit.id.toString())?.takeIf { it.turn == unit.civ.gameInfo.turns && it.attacks > 0 }?.moved = true
    }
    fun unitLost(unit: MapUnit) { facts(unit.civ)?.let { it.chuKoNu.remove(unit.id.toString()); it.keshik.remove(unit.id.toString()) } }

    fun resistanceChanged(civ: Civilization) {
        val f = facts(civ) ?: return
        for (city in civ.cities) f.recovery[city.id]?.let { if (it.baseline < 0 && !city.isInResistance()) it.baseline = city.population.population }
    }
    fun foodGrowth(city: City, before: Int) {
        val f = facts(city.civ) ?: return
        val recovery = f.recovery[city.id] ?: return
        if (recovery.baseline >= 0 && !city.isInResistance() && city.population.population > before &&
            city.population.population > recovery.baseline && city.isConnectedToCapital()) done(city.civ, "F09")
    }
    fun romanBonus(city: City, building: Building): Boolean = !city.isCapital() && city.foundingCivObject == city.civ &&
        city.civ.nation.name == "Rome" && !building.isAnyWonder() &&
        city.civ.getCapital()?.cityConstructions?.isBuilt(building.name) == true &&
        city.getMatchingUniques(UniqueType.PercentProductionBuildingsInCapital).any { it.params[0].toFloat() > 0 }
    fun productionAdded(city: City, production: Int, stats: com.unciv.models.stats.Stats) {
        val f = facts(city.civ) ?: return
        val name = city.cityConstructions.currentConstructionName()
        val building = city.civ.gameInfo.ruleset.buildings[name] ?: return
        if (city.cityConstructions.getWorkDone(name) == 0) f.romanProduction[city.id]?.remove(name)
        if (production > 0 && stats === city.cityStats.currentCityStats && city.cityStats.romanBonusConstruction == name && romanBonus(city, building)) f.romanProduction.getOrPut(city.id) { hashSetOf() }.add(name)
    }
    fun productionReset(city: City, name: String) { facts(city.civ)?.romanProduction?.get(city.id)?.remove(name) }
    fun buildingProduced(city: City, building: Building) {
        val f = facts(city.civ) ?: return
        if (f.romanProduction[city.id]?.remove(building.name) == true && romanBonus(city, building)) {
            f.romanCities.add(city.id)
            if (f.romanCities.size >= 2) done(city.civ, "R28")
        }
    }
    private fun actualTile(tile: Tile) = tile.tileMap.hasGameInfo() && tile.tileMap[tile.position] === tile
    fun improvementChanged(tile: Tile) {
        if (actualTile(tile)) state(tile.tileMap.gameInfo)?.history?.extended?.terraces?.remove(AchievementGameState.tileKey(tile))
    }
    fun improvementBuilt(tile: Tile, unit: MapUnit?) {
        if (!actualTile(tile)) return
        improvementChanged(tile)
        if (unit?.civ?.nation?.name != "Inca" || tile.improvement != "Terrace farm" || tile.neighbors.count { it.baseTerrain == "Mountain" } < 2) return
        facts(unit.civ)?.terraces?.add(AchievementGameState.tileKey(tile))
    }
    fun foodWorked(city: City) {
        val f = facts(city.civ) ?: return
        if (city.foundingCivObject == city.civ && city.getWorkedTiles().any {
            it.getOwner() == city.civ && it.improvement == "Terrace farm" && !it.improvementIsPillaged &&
                it.neighbors.count { neighbor -> neighbor.baseTerrain == "Mountain" } >= 2 && AchievementGameState.tileKey(it) in f.terraces
        }) done(city.civ, "F13")
    }
}
