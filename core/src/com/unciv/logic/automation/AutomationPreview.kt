package com.unciv.logic.automation

import com.unciv.json.json
import com.unciv.logic.GameInfo
import com.unciv.logic.battle.ICombatant
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.city.City
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import yairm210.purity.annotations.Readonly

/** Optional, thread-confined capture on an independent copy. Ordinary play has no session. */
object AutomationPreview {
    interface Sink {
        fun move(unit: MapUnit, destination: Tile)
        fun attack(attacker: ICombatant, target: Tile, from: Tile)
        fun choice(civ: Civilization, action: String, value: String, unit: MapUnit?, city: City?, tile: Tile?)
        fun finished(unit: MapUnit)
        fun checkpoint(civ: Civilization)
    }

    private class Session(val game: GameInfo, val sink: Sink, var unit: MapUnit? = null)
    private class SelectedAction : RuntimeException()
    private val session = ThreadLocal<Session>()
    val active get() = session.get() != null

    @Readonly fun controls(civ: Civilization) = session.get()?.game === civ.gameInfo &&
        civ.gameInfo.currentPlayer == civ.civID
    fun capture(game: GameInfo, sink: Sink, block: () -> Unit) {
        check(!active) { "Nested AI action capture" }
        session.set(Session(game, sink))
        try { block() } finally { session.remove() }
    }

    fun withUnit(unit: MapUnit, reportIdle: Boolean = true, block: () -> Unit) {
        val current = session.get()
        if (current == null || !controls(unit.civ)) { block(); return }
        val previous = current.unit
        current.unit = unit
        try { block() } catch (_: SelectedAction) {
            // The selected human UI action is captured rather than opening global UI.
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
            current.sink.choice(unit.civ, when (title) {
                "Upgrade" -> "upgrade"
                "CreateImprovement" -> "create_improvement"
                else -> "unit_action"
            }, option, unit, null, unit.currentTile)
            unit.currentMovement = 0f
            throw SelectedAction()
        }
    }

    fun move(unit: MapUnit, destination: Tile): Boolean {
        val current = session.get()?.takeIf { controls(unit.civ) } ?: return false
        current.sink.move(unit, destination)
        return true
    }
    fun attack(attacker: ICombatant, target: Tile, from: Tile = attacker.getTile()): Boolean {
        val current = session.get()?.takeIf { controls(attacker.getCivInfo()) } ?: return false
        current.sink.attack(attacker, target, from)
        return true
    }
    fun choice(civ: Civilization, action: String, value: String, unit: MapUnit? = null,
        city: City? = null, tile: Tile? = null) {
        if (controls(civ)) session.get()?.sink?.choice(civ, action, value, unit, city, tile)
    }
    fun tradeChoice(civ: Civilization, action: String, request: com.unciv.logic.trade.TradeRequest) =
        choice(civ, action, json().toJson(request))
    fun counterTradeChoice(civ: Civilization, request: com.unciv.logic.trade.TradeRequest,
        counteroffer: com.unciv.logic.trade.TradeRequest) =
        choice(civ, "counter_trade", json().toJson(arrayOf(request, counteroffer)))
    fun checkpoint(civ: Civilization) {
        if (controls(civ)) session.get()?.sink?.checkpoint(civ)
    }
}
