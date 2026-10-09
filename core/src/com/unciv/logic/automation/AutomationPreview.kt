package com.unciv.logic.automation

import com.unciv.json.json
import com.unciv.logic.GameInfo
import com.unciv.logic.battle.ICombatant
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.city.City
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.models.ruleset.IConstruction
import com.unciv.models.ruleset.nation.Personality
import com.unciv.models.ruleset.tech.Technology
import com.unciv.models.stats.Stats
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

    /** Preferences belong to the caller; all defaults preserve the engine's decisions. */
    interface Policy {
        fun personality(civ: Civilization): Personality? = null
        fun preferredVictory(civ: Civilization): String? = null
        fun researchWeight(civ: Civilization, technology: Technology, original: Float): Float = original
        fun preferredResearch(civ: Civilization, technologies: List<Technology>): Technology? = null
        fun productionWeight(city: City, construction: IConstruction, benefits: Stats?): Float = 1f
        fun preferredProduction(city: City, choices: List<IConstruction>): IConstruction? = null
        fun allowConstruction(city: City, construction: IConstruction): Boolean = true
        fun allowPurchase(city: City, construction: IConstruction, currency: com.unciv.models.stats.Stat, price: Int): Boolean = true
        fun allowUpgrade(unit: MapUnit, upgrade: com.unciv.models.ruleset.unit.BaseUnit, price: Int): Boolean = true
        fun preferredWar(civ: Civilization): Civilization? = null
        fun allowDeclaration(civ: Civilization, target: Civilization): Boolean = true
        fun allowTrade(civ: Civilization, trade: com.unciv.logic.trade.Trade): Boolean = true
        fun allowPeace(civ: Civilization): Boolean = true
        fun allowExpansion(civ: Civilization): Boolean = true
        fun automateUnit(unit: MapUnit): Boolean = false
        fun unitPriority(unit: MapUnit): Int = 0
        fun keepCity(civ: Civilization, city: City): Boolean = false
    }

    private class Session(val game: GameInfo, val sink: Sink, val policy: Policy?,
        val defeated: Map<String, Boolean>, val wonders: Set<String>?,
        val tradeValidity: Map<String, Boolean>, var unit: MapUnit? = null)
    private class SelectedAction : RuntimeException()
    private val session = ThreadLocal<Session>()
    val active get() = session.get() != null

    @Readonly fun controls(civ: Civilization) = session.get()?.game === civ.gameInfo &&
        civ.gameInfo.currentPlayer == civ.civID
    @Readonly fun policy(civ: Civilization): Policy? = if (controls(civ)) session.get()?.policy else null
    @Readonly @Suppress("purity") fun personality(civ: Civilization) = policy(civ)?.personality(civ)
    @Readonly @Suppress("purity") fun preferredVictory(civ: Civilization) = policy(civ)?.preferredVictory(civ)
    @Readonly fun defeated(civ: Civilization) = session.get()?.takeIf { it.game === civ.gameInfo }?.defeated?.get(civ.civID)
    @Readonly fun wonderBuilt(game: GameInfo, name: String) = session.get()?.takeIf { it.game === game }?.wonders?.contains(name)
    fun tradeValid(civ: Civilization, request: com.unciv.logic.trade.TradeRequest): Boolean? =
        if (controls(civ)) session.get()?.tradeValidity?.get(json().toJson(request)) else null

    fun capture(game: GameInfo, sink: Sink, policy: Policy? = null,
        defeatedCivs: Map<String, Boolean> = emptyMap(), builtWonders: Set<String>? = null,
        tradeValidity: Map<String, Boolean> = emptyMap(), block: () -> Unit) {
        check(!active) { "Nested AI preview" }
        session.set(Session(game, sink, policy, defeatedCivs, builtWonders, tradeValidity))
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
