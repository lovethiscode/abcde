package com.unciv.logic.automation

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
    private class Session(val game: GameInfo, val sink: Sink, var unit: MapUnit? = null)
    private class SelectedAction : RuntimeException()
    private val session = ThreadLocal<Session>()
    val active: Boolean get() = session.get() != null

    fun capture(game: GameInfo, sink: Sink, block: () -> Unit) {
        check(!active) { "Nested AI preview" }
        session.set(Session(game, sink))
        try { block() } finally { session.remove() }
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
    fun wrapAction(title: String, action: (() -> Unit)?): (() -> Unit)? {
        val current = session.get() ?: return action
        val unit = current.unit ?: return action
        if (action == null) return null
        return {
            check(session.get() === current)
            current.sink.choice(unit.civ, "unit_action", title, unit, null, unit.currentTile)
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
    fun checkpoint(civ: Civilization) {
        val current = session.get() ?: return
        if (current.game === civ.gameInfo) current.sink.checkpoint(civ)
    }
}
