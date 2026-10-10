package com.unciv.logic.campaign

import com.unciv.logic.GameInfo
import com.unciv.logic.city.City
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.models.stats.Stat

object CampaignRules {
    data class Evaluation(val main: Boolean, val worker: Boolean, val population: Boolean, val failed: Boolean) {
        val stars get() = if (failed || !main) 0 else 1 + (if (worker) 1 else 0) + (if (population) 1 else 0)
    }

    fun productionCompleted(city: City, name: String, unit: MapUnit?) {
        val state = city.civ.gameInfo.campaign ?: return
        if (state.playerId != city.civ.civID || state.resultStars > 0 || state.failed) return
        // Sets make re-delivery of the same production fact harmless.
        if (name in state.definition.requiredProduction) state.produced.add(name)
        if (name == state.definition.worker && unit != null) state.producedWorkers.add(unit.id)
    }

    @yairm210.purity.annotations.Readonly
    fun purchaseBlocked(city: City, name: String, stat: Stat): Boolean {
        val state = city.civ.gameInfo.campaign ?: return false
        return state.playerId == city.civ.civID && stat == Stat.Gold && name in state.definition.requiredProduction
    }

    fun evaluate(game: GameInfo): Evaluation {
        val state = requireNotNull(game.campaign)
        val player = game.civilizations.firstOrNull { it.civID == state.playerId }
        val failed = state.failed || player == null || player.isDefeated()
        if (state.resultStars > 0)
            return Evaluation(true, state.resultWorker, state.resultPopulation, false)
        return Evaluation(
            state.definition.requiredProduction.all { it in state.produced },
            player?.units?.getCivUnits()?.any { it.id in state.producedWorkers && it.name == state.definition.worker } == true,
            player?.cities?.any { it.id == state.capitalId && it.population.population >= state.definition.population } == true,
            failed,
        )
    }

    /** Called once after all civilizations have ended the round, before the human's next startTurn. */
    fun roundCompleted(game: GameInfo) {
        val state = game.campaign ?: return
        if (state.resultStars > 0 || state.failed) return
        state.completedRounds = maxOf(state.completedRounds, game.turns)
        state.failed = evaluate(game).failed
    }
}
