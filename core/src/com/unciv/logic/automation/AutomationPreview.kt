package com.unciv.logic.automation

import yairm210.purity.annotations.Readonly
import com.unciv.json.json
import com.unciv.models.ruleset.nation.Personality
import com.unciv.models.ruleset.tech.Technology
import com.unciv.logic.GameInfo
import com.unciv.logic.battle.ICombatant
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.city.City
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile

/** Explicit, thread-confined decision capture on an independent game copy. Absent in normal play. */
object AutomationPreview {
    interface Sink {
        fun move(unit: MapUnit, destination: Tile)
        fun attack(attacker: ICombatant, target: Tile, from: Tile)
        fun choice(civ: Civilization, action: String, value: String, unit: MapUnit?, city: City?, tile: Tile?)
        fun finished(unit: MapUnit)
        fun checkpoint(civ: Civilization)
    }
    private class Session(val game: GameInfo, val sink: Sink, val militaryVictory: String?,
                          val personality: Personality?, val defeatedCivs: Map<String, Boolean>, var unit: MapUnit? = null)
    private class SelectedAction : RuntimeException()
    private val session = ThreadLocal<Session>()
    val active: Boolean get() = session.get() != null

    fun capture(game: GameInfo, sink: Sink, militaryVictory: String? = null,
                defeatedCivs: Map<String, Boolean> = emptyMap(), block: () -> Unit) {
        check(!active) { "Nested AI preview" }
        val personality = if (militaryVictory == null) null else {
            val original = game.getCurrentPlayerCivilization().getPersonality()
            json().fromJson(Personality::class.java, json().toJson(original)).apply {
                military = 9f
                aggressive = 9f
                declareWar = 9f
                production = production.coerceAtLeast(7f)
                science = science.coerceAtLeast(7f)
                happiness = happiness.coerceAtLeast(7f)
                preferredVictoryType = militaryVictory
            }
        }
        session.set(Session(game, sink, militaryVictory, personality, defeatedCivs))
        try { block() } finally { session.remove() }
    }
    @Readonly private fun militarySession(civ: Civilization): Session? = session.get()?.takeIf {
        it.militaryVictory != null && it.game === civ.gameInfo && it.game.currentPlayer == civ.civID
    }
    fun militaryGoal(civ: Civilization): Boolean = militarySession(civ) != null
    fun includesPeace(trade: com.unciv.logic.trade.Trade): Boolean =
        (trade.ourOffers + trade.theirOffers).any {
            it.type == com.unciv.logic.trade.TradeOfferType.PeaceProposal ||
                (it.type == com.unciv.logic.trade.TradeOfferType.Treaty && it.name == com.unciv.Constants.peaceTreaty)
        }
    @Readonly fun personality(civ: Civilization): Personality? = militarySession(civ)?.personality
    @Readonly fun preferredVictory(civ: Civilization): String? = militarySession(civ)?.militaryVictory
    @Readonly fun defeated(civ: Civilization): Boolean? = session.get()?.takeIf {
        it.game === civ.gameInfo
    }?.defeatedCivs?.get(civ.civID)
    fun researchWeight(civ: Civilization, tech: Technology): Float {
        val weight = tech.getWeightForAiDecision(civ.state)
        if (!militaryGoal(civ)) return weight
        val unlocksMilitary = civ.gameInfo.ruleset.units.values.any {
            it.requiredTech == tech.name && it.isMilitary
        }
        return weight * if (unlocksMilitary) 3f else 1f
    }

    fun withUnit(unit: MapUnit, reportIdle: Boolean = true, block: () -> Unit) {
        val current = session.get()
        if (current == null || current.game !== unit.civ.gameInfo) { block(); return }
        val previous = current.unit
        current.unit = unit
        try { block() } catch (_: SelectedAction) {
            // A chosen UI action ends this unit's preview before its closure can touch global UI.
        } finally {
            current.unit = previous
            if (reportIdle) current.sink.finished(unit)
        }
    }
    fun wrapAction(title: String, action: (() -> Unit)?, option: String = title): (() -> Unit)? {
        val current = session.get() ?: return action
        val unit = current.unit ?: return action
        if (action == null) return null
        return {
            check(session.get() === current)
            current.sink.choice(unit.civ, if (title == "Upgrade") "upgrade" else "unit_action", option, unit, null, unit.currentTile)
            unit.currentMovement = 0f
            throw SelectedAction()
        }
    }
    fun move(unit: MapUnit, destination: Tile): Boolean {
        val current = session.get() ?: return false
        if (current.game !== unit.civ.gameInfo) return false
        current.sink.move(unit, destination)
        return true
    }
    fun attack(attacker: ICombatant, target: Tile, from: Tile = attacker.getTile()): Boolean {
        val current = session.get() ?: return false
        if (current.game !== attacker.getCivInfo().gameInfo) return false
        current.sink.attack(attacker, target, from)
        return true
    }
    fun choice(civ: Civilization, action: String, value: String, unit: MapUnit? = null,
               city: City? = null, tile: Tile? = null) {
        val current = session.get() ?: return
        if (current.game === civ.gameInfo) current.sink.choice(civ, action, value, unit, city, tile)
    }
    fun tradeChoice(civ: Civilization, action: String, request: com.unciv.logic.trade.TradeRequest) {
        val current = session.get() ?: return
        if (current.game === civ.gameInfo) current.sink.choice(civ, action, json().toJson(request), null, null, null)
    }
    fun counterTradeChoice(civ: Civilization, request: com.unciv.logic.trade.TradeRequest,
                          counteroffer: com.unciv.logic.trade.TradeRequest) {
        val current = session.get() ?: return
        if (current.game === civ.gameInfo) current.sink.choice(civ, "counter_trade",
            json().toJson(arrayOf(request, counteroffer)), null, null, null)
    }
    fun checkpoint(civ: Civilization) {
        val current = session.get() ?: return
        if (current.game === civ.gameInfo) current.sink.checkpoint(civ)
    }
}
