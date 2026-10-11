package com.unciv.logic.campaign

import com.badlogic.gdx.Gdx
import com.unciv.logic.GameInfo
import com.unciv.logic.automation.unit.BattleHelper
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.models.ruleset.Ruleset
import com.unciv.models.ruleset.RulesetCache
import com.unciv.models.stats.Stat
import yairm210.purity.annotations.Readonly

/** Rules for this one authored scenario; never installed as a user-selectable mod. */
object CivilWarCampaign {
    const val id = "civil-war-east"
    const val folder = "campaigns/civil-war"
    val harpersFerry = com.unciv.logic.map.HexMath.getTileCoordsFromColumnRow(-10, 11)
    val technologies = setOf("Military Reorganization", "Artillery Improvement", "Field Medicine", "Military Transport")
    val militaryUnits = setOf("Rifleman", "Cavalry", "Cannon")
    val recruitmentCities = setOf("cw-washington", "cw-baltimore", "cw-richmond", "cw-petersburg")
    val cities = linkedMapOf("cw-washington" to "Washington", "cw-baltimore" to "Baltimore",
        "cw-alexandria" to "Alexandria", "cw-richmond" to "Richmond", "cw-petersburg" to "Petersburg",
        "cw-fredericksburg" to "Fredericksburg", "cw-manassas" to "Manassas")

    @Readonly fun isActive(game: GameInfo) = game.campaign?.definition?.id == id

    @Readonly fun hasEnded(game: GameInfo) = isActive(game) && game.campaign!!.let { it.failed || it.resultStars > 0 }

    fun ruleset(game: GameInfo): Ruleset {
        require(game.gameParameters.mods.isEmpty()) { "Campaign rules cannot be combined with mods" }
        val base = RulesetCache.getComplexRuleset(game.gameParameters)
        val overlay = Ruleset().apply {
            name = "Civil War campaign"
            load(Gdx.files.internal("$folder/rules"))
        }
        return RulesetCache.getComplexRuleset(base, listOf(overlay))
    }

    @Readonly fun constructionAllowed(city: City, name: String): Boolean {
        if (!isActive(city.civ.gameInfo)) return true
        if (name == "Gold" || name == "Nothing" || name.isEmpty()) return true
        if (name == "Worker" || name in militaryUnits)
            return !city.isPuppet && !city.isInResistance() && city.id in cities &&
                (name == "Worker" || city.id in recruitmentCities)
        return city.id in cities && name in setOf("${cities[city.id]} Depot", "Market", "Courthouse", "Walls")
    }

    @Readonly fun purchaseBlocked(city: City, name: String, stat: Stat): Boolean {
        if (!isActive(city.civ.gameInfo)) return false
        if (stat != Stat.Gold || city.isPuppet || city.isInResistance() || !constructionAllowed(city, name)) return true
        val building = city.civ.gameInfo.ruleset.buildings[name] ?: return true
        val cost = building.getStatBuyCost(city, stat) ?: return true
        return !building.isBuildable(city.cityConstructions) || city.civ.gold < cost
    }

    @Readonly fun researchBlocked(civ: Civilization, name: String) = isActive(civ.gameInfo) && name !in technologies

    /** Defend in depth without sending the capital's reserve to every damaged front-line city. */
    fun holdDefensivePosition(unit: MapUnit): Boolean {
        val civ = unit.civ
        if (!isActive(civ.gameInfo) || !civ.isAI() || civ.civName != civ.gameInfo.campaign!!.definition.enemy ||
            unit.name !in setOf("Rifleman", "Cannon")) return false
        val available = civ.units.getCivUnits().filter { it.name in setOf("Rifleman", "Cannon") }.toMutableList()
        for (id in listOf("cw-richmond", "cw-fredericksburg")) {
            val center = civ.cities.firstOrNull { it.id == id }?.getCenterTile() ?: continue
            val defenders = listOf("Rifleman", "Cannon").flatMap { name ->
                available.filter { it.name == name && it.currentTile.aerialDistanceTo(center) <= 5 }
                    .sortedWith(compareBy({ it.currentTile.aerialDistanceTo(center) }, { it.id }))
                    .take(if (id == "cw-richmond" && name == "Rifleman") 2 else 1)
            }
            available.removeAll(defenders)
            if (unit !in defenders) continue
            if (BattleHelper.tryAttackNearbyEnemy(unit, defenseCenter = center)) return true
            if (unit.currentTile.aerialDistanceTo(center) > 2) {
                center.getTilesInDistance(2).filter { unit.movement.canMoveTo(it) }
                    .sortedBy { it.aerialDistanceTo(unit.currentTile) }
                    .firstOrNull { unit.movement.canReach(it) }?.let { unit.movement.headTowards(it) }
            }
            if (unit.hasMovement()) unit.fortifyIfCan()
            return true
        }
        return false
    }

    @Readonly fun improvementAllowed(civ: Civilization, name: String, tile: com.unciv.logic.map.tile.Tile): Boolean {
        if (!isActive(civ.gameInfo)) return true
        if (name == "Cancel improvement order") return true
        if (name == "Repair") return tile.isPillaged()
        return name == "Road" || name == "Railroad" && civ.tech.isResearched("Military Transport")
    }

    fun evaluate(game: GameInfo): CampaignRules.Evaluation {
        val s = requireNotNull(game.campaign)
        val d = s.definition
        val player = game.civilizations.firstOrNull { it.civID == s.playerId }
        val capital = game.getCities().firstOrNull { it.id == s.capitalId }
        val target = game.getCities().firstOrNull { it.id == d.targetCity }
        val failure = when {
            s.failed -> s.failureReason.ifEmpty { "本次挑战失败" }
            player == null || player.isDefeated() -> "联邦已被击败"
            s.capitalLost || capital?.civ != player -> "华盛顿已失守"
            target == null -> "里士满已不存在"
            game.victoryData?.let { it.winningCiv != s.playerId } == true -> "对手已获胜"
            s.completedRounds >= d.hardTurnLimit -> "第 ${d.hardTurnLimit} 个完整回合已结束"
            else -> ""
        }
        val round = (s.completedRounds + 1).coerceAtMost(d.hardTurnLimit)
        val main = target?.civ == player && target?.id in s.capturedCities && !target!!.hasJustBeenConquered
        val efficient = round <= d.efficiencyTurn
        val preserved = s.lostMilitary.size <= d.lossLimit
        val details = listOf(
            "里士满：${if (main) "已占领" else "尚未占领"}；华盛顿：${if (capital?.civ == player && !s.capitalLost) "仍由联邦控制" else "已失守"}",
            "战役第 $round 回合 / ${d.hardTurnLimit}；效率要求不晚于第 ${d.efficiencyTurn} 回合",
            "部队损失 ${s.lostMilitary.size} / ${d.lossLimit}\n" + CampaignLossReason.entries.filter { it.counts }.joinToString(" · ") {
                "${it.label} ${s.lossReasons.values.count { reason -> reason == it.name }}"
            })
        return CampaignRules.Evaluation(main, efficient, preserved, failure.isNotEmpty(), details, failure,
            buildSet { if (!efficient) add(1); if (!preserved) add(2) })
    }
}

enum class CampaignLossReason(val label: String, val counts: Boolean) {
    Combat("战斗阵亡", true), Disband("主动解散", true), Debt("欠费解散", true),
    Captured("被俘或转移", true), Removed("其他移除", true), Replacement("正常替换", false)
}
