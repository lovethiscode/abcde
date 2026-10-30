package com.unciv.logic.achievements

import com.unciv.logic.GameInfo
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.logic.trade.Trade
import com.unciv.logic.trade.TradeOfferType
import com.unciv.models.ruleset.Building
import com.unciv.utils.Log
import java.io.IOException

/** Direct source hooks. Awards are persisted only after the enclosing operation settles. */
object AchievementTracker {
    var service: AchievementService? = null

    fun beginAction(game: GameInfo) {
        game.achievements?.let { it.actionDepth++ }
        game.campaign?.let { it.actionDepth++ }
        game.endless?.let { it.actionDepth++ }
    }
    fun endAction(game: GameInfo, successful: Boolean = true) {
        game.achievements?.let { it.actionDepth-- }
        game.campaign?.let { it.actionDepth-- }
        game.endless?.let { it.actionDepth-- }
        if (successful) settle(game)
    }

    inline fun <T> action(game: GameInfo, block: () -> T): T {
        beginAction(game)
        var successful = false
        try {
            val result = block()
            successful = true
            return result
        } finally { endAction(game, successful) }
    }

    fun settle(game: GameInfo, atTurnEnd: Boolean = false, winner: Civilization? = null, victoryRoute: String? = null) {
        if (game.endless != null) {
            com.unciv.logic.endless.EndlessRules.actionSettled(game)
            return
        }
        if (game.campaign != null) {
            com.unciv.logic.campaign.CampaignRules.actionSettled(game)
            return
        }
        val state = game.achievements ?: return
        if (state.ended || !state.initializationComplete || state.actionDepth != 0 ||
            state.recordingVersion !in AchievementCatalog.firstCompleteRecordingVersion..AchievementCatalog.recordingVersion) return
        val player = game.civilizations.firstOrNull { it.civID == state.playerId } ?: return
        if (game.gameParameters.godMode || game.simulateUntilWin) {
            disqualify(game)
            return
        }
        settleCaptures(game)
        val actualWinner = winner ?: game.victoryData?.let { victory -> game.civilizations.firstOrNull { it.civID == victory.winningCiv } }
        val actualRoute = victoryRoute ?: game.victoryData?.victoryType
        val terminal = actualWinner != null || player.isDefeated()
        if (actualWinner == player) AchievementExtendedRules.finishWindows(player, terminalVictory = true)
        val ids = AchievementRules.evaluate(game, atTurnEnd || terminal, actualWinner, actualRoute)
        state.history.completed.addAll(ids)
        try {
            val durable = service ?: return
            durable.record(game, ids, actualRoute, terminal = terminal, playerWon = actualWinner == player)
        } catch (error: IOException) {
            // Keep branch facts for retry; a disk failure must not interrupt a game or report an uncommitted award.
            Log.error("Could not persist achievements; will retry after the next game action", error)
            return
        }
        if (terminal) state.ended = true
    }

    fun disqualify(game: GameInfo) {
        if (game.achievements?.ended != false) return
        game.achievements?.ended = true
        try {
            service?.disqualify(game)
        } catch (error: IOException) {
            Log.error("Could not persist achievement disqualification; this session remains ineligible", error)
        }
    }

    private fun state(civ: Civilization): AchievementGameState? = civ.gameInfo.achievements?.takeIf {
        !it.ended && it.playerId == civ.civID && it.recordingVersion in AchievementCatalog.firstCompleteRecordingVersion..AchievementCatalog.recordingVersion
    }

    fun startTurn(civ: Civilization) { state(civ)?.history?.startTurn(civ.gameInfo.turns) }

    /** Event facts are kept on the save branch, including when a profile write must be retried. */
    fun eventCompleted(civ: Civilization, id: String) {
        // Only these V3 clues are fulfilled by a single event. Other hooks settle aggregate rules.
        if (id == "N03" || id == "N06" || id == "N09") state(civ)?.history?.completed?.add(id)
        settle(civ.gameInfo)
    }

    fun cityKey(city: City): String = city.civ.gameInfo.achievements?.history?.cityKeys
        ?.get(AchievementGameState.tileKey(city.getCenterTile())).orEmpty()

    fun cityFounded(city: City) {
        val state = city.civ.gameInfo.achievements ?: return
        if (state.ended || state.recordingVersion !in AchievementCatalog.firstCompleteRecordingVersion..AchievementCatalog.recordingVersion) return
        val history = state.history
        val key = (++history.nextCitySerial).toString()
        history.cityKeys[AchievementGameState.tileKey(city.getCenterTile())] = key
        if (city.civ.civID == state.playerId) history.foundedCities.add(key)
        cityAcquired(city)
        AchievementExtendedRules.founded(city)
    }

    fun cityAcquired(city: City) {
        val history = state(city.civ)?.history ?: return
        history.maximumCities = maxOf(history.maximumCities, city.civ.cities.size)
        if (city.cityConstructions.getBuiltBuildings().any { it.isWonder })
            history.restrictions.add(AchievementRules.worldWonder)
    }

    fun buildingOwned(city: City, building: Building) {
        if (building.isWonder) state(city.civ)?.history?.restrictions?.add(AchievementRules.worldWonder)
    }

    fun buildingProduced(city: City, building: Building, normalProduction: Boolean = false) {
        if (normalProduction) AchievementExtendedRules.buildingProduced(city, building)
        else AchievementExtendedRules.productionReset(city, building.name)
        if (!building.isWonder) return
        val history = state(city.civ)?.history ?: return
        history.builtWonders[building.name] = cityKey(city)
        if (city.civ.goldenAges.isGoldenAge()) history.goldenAgeWonderCities.add(cityKey(city))
    }

    fun encampmentCleared(unit: MapUnit, tile: Tile) {
        state(unit.civ)?.history?.unit(unit.id)?.clearedEncampments?.add(AchievementGameState.tileKey(tile))
        settle(unit.civ.gameInfo)
    }

    fun improvementBuilt(tile: Tile, unit: MapUnit?) {
        if (!tile.tileMap.hasGameInfo() || tile.tileMap[tile.position] !== tile || unit == null) return
        if (unit.name == "Worker" && tile.improvement in setOf("Farm", "Mine", "Pasture"))
            eventCompleted(unit.civ, "N09")
    }

    fun combatExperienceEarned(unit: MapUnit, amount: Int) {
        state(unit.civ)?.history?.unit(unit.id)?.let { it.combatExperience += amount }
    }

    fun promotionEarned(unit: MapUnit, experienceCost: Int) {
        state(unit.civ)?.history?.unit(unit.id)?.let {
            it.earnedPromotions++
            // Spend recorded combat XP first. A mixed training/combat payment is not a combat promotion.
            if (it.combatExperience >= experienceCost) it.combatPromotions++
            it.combatExperience = maxOf(0, it.combatExperience - experienceCost)
        }
    }

    fun killedMilitaryUnit(killer: MapUnit, victimId: Int, victimWasMajorMilitary: Boolean,
                           wasAttacker: Boolean) {
        val history = state(killer.civ)?.history ?: return
        history.completed.add("N06")
        if (victimWasMajorMilitary) history.unit(killer.id).majorMilitaryKills++
        if (!victimWasMajorMilitary || !wasAttacker || !killer.civ.isCurrentPlayer() || killer.name != "Chu-Ko-Nu") return
        val unit = history.unit(killer.id)
        if (unit.combatPromotions >= 4) unit.chuKoNuKillsThisTurn.add(victimId.toString())
    }

    fun tradeCompleted(first: Civilization, second: Civilization, trade: Trade) {
        if (!first.isMajorCiv || !second.isMajorCiv) return
        val qualifying = (trade.ourOffers + trade.theirOffers).any {
            (it.type in setOf(TradeOfferType.Gold, TradeOfferType.Gold_Per_Turn,
                TradeOfferType.Luxury_Resource, TradeOfferType.Strategic_Resource, TradeOfferType.Stockpiled_Resource)
                && it.amount > 0) || (it.type == TradeOfferType.Agreement && it.name == "Open Borders")
        }
        if (qualifying) {
            state(first)?.history?.tradePartners?.add(second.civID)
            state(second)?.history?.tradePartners?.add(first.civID)
            settle(first.gameInfo)
        }
    }

    fun cityBattleWon(unit: MapUnit, city: City) {
        AchievementExtendedRules.captureStarted(unit, city)
        val history = state(unit.civ)?.history ?: return
        history.pendingCaptures[cityKey(city)] = AchievementCapture().apply {
            unitId = unit.id
            combatPromotions = history.units[unit.id.toString()]?.combatPromotions ?: 0
            turn = unit.civ.gameInfo.turns
            foreign = city.foundingCivObject?.let { it != unit.civ && it.isMajorCiv } == true
        }
    }

    fun cityConquered(city: City) {
        state(city.civ)?.history?.pendingCaptures?.get(cityKey(city))?.ownershipSettled = true
    }

    /** Human conquest choices may finish after the battle, including after saving and loading. */
    fun settleCaptures(game: GameInfo) {
        val state = game.achievements ?: return
        val history = state.history
        val player = game.civilizations.firstOrNull { it.civID == state.playerId } ?: return
        for ((cityId, capture) in history.pendingCaptures.toMap()) {
            val city = game.getCities().firstOrNull { cityKey(it) == cityId }
            if (city == null) { history.pendingCaptures.remove(cityId); continue }
            if (city.civ != player || !capture.ownershipSettled) continue
            history.pendingCaptures.remove(cityId)
            AchievementExtendedRules.captureSettled(city)
            if (!capture.foreign) continue
            val captor = player.units.getUnitById(capture.unitId)
            if (captor != null && !captor.isDestroyed && captor.isMilitary() &&
                capture.combatPromotions >= 3)
                history.completed.add("N08")
            history.foreignCaptures.add(cityId)
            if (player.goldenAges.isGoldenAge()) {
                history.goldenAgeCaptures.add(cityId)
                history.goldenAgeCapturedCivilizations.add(city.foundingCivObject!!.civID)
                if (history.goldenAgeCaptures.size >= 4 && history.goldenAgeCapturedCivilizations.size >= 2)
                    history.completed.add("N35")
            }
            if (capture.turn == history.turn && player.isCurrentPlayer()) {
                history.capturedThisTurn.add(cityId)
                if (history.capturedThisTurn.size >= 3) history.completed.add("N32")
            }
        }
    }

    fun cityLiberated(city: City) {
        city.civ.gameInfo.achievements?.history?.pendingCaptures?.remove(cityKey(city))
    }

    fun goldenAgeEnded(civ: Civilization) {
        val history = state(civ)?.history ?: return
        history.goldenAgeCaptures.clear()
        history.goldenAgeCapturedCivilizations.clear()
        history.goldenAgeWonderCities.clear()
    }
}
