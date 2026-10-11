package com.unciv.logic.campaign

import com.unciv.logic.GameInfo
import com.unciv.logic.battle.ICombatant
import com.unciv.logic.battle.MapUnitCombatant
import com.unciv.logic.city.City
import com.unciv.logic.civilization.AlertType
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.HexCoord
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.models.stats.Stat
import yairm210.purity.annotations.Readonly

/** Finite rules for the sixteen authored lessons. No scripts or arbitrary condition interpreter. */
object CampaignRules {
    class Evaluation(val main: Boolean, val worker: Boolean, val population: Boolean, val failed: Boolean,
                     val details: List<String> = emptyList(), val failureReason: String = "", val impossible: Set<Int> = emptySet()) {
        val stars get() = if (failed || !main) 0 else 1 + (if (worker) 1 else 0) + (if (population) 1 else 0)
    }

    private fun active(game: GameInfo) = game.campaign?.takeIf { it.resultStars == 0 && !it.failed }

    fun productionCompleted(city: City, name: String, unit: MapUnit?) {
        val state = active(city.civ.gameInfo) ?: return
        if (state.playerId != city.civ.civID) return
        if (name in state.definition.requiredProduction) state.produced.add(name)
        if (unit != null) state.producedUnits.add(unit.id)
        if (name == state.definition.worker && unit != null) state.producedWorkers.add(unit.id)
    }

    @Readonly fun purchaseBlocked(city: City, name: String, stat: Stat): Boolean {
        if (CivilWarCampaign.purchaseBlocked(city, name, stat)) return true
        val state = city.civ.gameInfo.campaign ?: return false
        return state.playerId == city.civ.civID && state.definition.number == 2 &&
            stat == Stat.Gold && name in state.definition.requiredProduction
    }

    @Readonly fun foundingBlocked(civ: Civilization): Boolean {
        val s = civ.gameInfo.campaign ?: return false
        if (CivilWarCampaign.isActive(civ.gameInfo)) return true
        if (civ.civID != s.playerId) return false
        return s.definition.forbidFounding || s.definition.maxCities > 0 && civ.cities.size >= s.definition.maxCities
    }

    @Readonly fun warBlocked(civ: Civilization): Boolean = civ.gameInfo.campaign?.let {
        it.playerId == civ.civID && it.definition.forbidWar
    } == true

    @Readonly fun peaceBlocked(first: Civilization, second: Civilization): Boolean {
        val s = first.gameInfo.campaign ?: return false
        return s.definition.lockedWar && ((first.civID == s.playerId && second.civName == s.definition.enemy) ||
            (second.civID == s.playerId && first.civName == s.definition.enemy))
    }

    /** Capture this BEFORE founding grants visibility; the founding action cannot scout its own site. */
    fun beforeFounding(civ: Civilization) {
        val s = active(civ.gameInfo) ?: return
        if (s.playerId != civ.civID || s.definition.number != 1 || s.foundedTurn != 0) return
        s.scoutedBeforeFounding = s.definition.areas.take(2).all { a -> a.tiles.all { civ.gameInfo.tileMap[it].isExplored(civ) } }
    }

    fun cityFounded(city: City) {
        val s = active(city.civ.gameInfo) ?: return
        if (s.playerId != city.civ.civID) return
        s.foundedCities.add(city.id)
        if (s.capitalId.isEmpty()) {
            s.capitalId = city.id
            s.foundedTurn = s.completedRounds + 1
        }
    }

    fun cityTransferred(city: City, old: Civilization, new: Civilization) {
        val s = active(old.gameInfo) ?: return
        if (city.id == s.capitalId && old.civID == s.playerId && new.civID != s.playerId) s.capitalLost = true
    }

    fun cityConquered(city: City, conqueror: Civilization) {
        val s = active(conqueror.gameInfo) ?: return
        if (s.playerId == conqueror.civID) s.capturedCities.add(city.id)
    }

    /** Protected losses are irreversible; produced-unit provenance remains a historical fact. */
    fun unitLost(unit: MapUnit, reason: CampaignLossReason = CampaignLossReason.Removed) {
        val s = active(unit.civ.gameInfo) ?: return
        if (s.playerId != unit.civ.civID) return
        if (unit.id in s.protectedUnits) s.protectedUnitLost = true
        if (CivilWarCampaign.isActive(unit.civ.gameInfo) && unit.name in CivilWarCampaign.militaryUnits && reason.counts) {
            s.lostMilitary.add(unit.id)
            if (unit.id.toString() !in s.lossReasons) s.lossReasons[unit.id.toString()] = reason.name
        }
    }

    fun combatDefeat(killer: ICombatant, victim: MapUnit) {
        val s = active(victim.civ.gameInfo) ?: return
        if (victim.civ.civID == s.playerId && victim.isMilitary()) {
            s.lostMilitary.add(victim.id)
            if (CivilWarCampaign.isActive(victim.civ.gameInfo)) s.lossReasons[victim.id.toString()] = CampaignLossReason.Combat.name
        }
        if (killer.getCivInfo().civID != s.playerId || victim.civ.civName != s.definition.enemy || !victim.isMilitary()) return
        s.killedUnits.add(victim.id)
        if (killer is MapUnitCombatant) {
            if (killer.unit.name in s.definition.meleeUnits) s.killRoles.add("melee")
            if (killer.unit.name in s.definition.rangedUnits) s.killRoles.add("ranged")
        }
    }

    fun enemyDefeated(enemy: Civilization, conqueror: Civilization) {
        val s = active(conqueror.gameInfo) ?: return
        if (s.playerId == conqueror.civID && enemy.civName == s.definition.enemy) s.enemyEliminated = true
    }

    @Readonly private fun kept(city: City?, player: Civilization): Boolean = city != null && city.civ == player &&
        !city.isBeingRazed && !city.hasJustBeenConquered &&
        player.popupAlerts.none { it.type == AlertType.CityConquered && it.value == city.id }
    @Readonly private fun has(city: City?, building: String) = city?.cityConstructions?.containsBuildingOrEquivalent(building) == true
    @Readonly private fun areaContains(s: CampaignState, location: HexCoord, index: Int = 0) =
        s.definition.areas.getOrNull(index)?.tiles?.contains(location) == true

    fun evaluate(game: GameInfo): Evaluation {
        val s = requireNotNull(game.campaign)
        if (s.resultStars > 0) return Evaluation(true, s.resultWorker, s.resultPopulation, false, s.resultDetails)
        if (CivilWarCampaign.isActive(game)) return CivilWarCampaign.evaluate(game)
        val p = game.civilizations.firstOrNull { it.civID == s.playerId }
            ?: return Evaluation(false, false, false, true, failureReason = "玩家文明不存在")
        val d = s.definition
        val cities = game.getCities().toList()
        val capital = cities.firstOrNull { it.id == s.capitalId }
        val target = cities.firstOrNull { it.id == d.targetCity }
        val initial = cities.filter { it.id in s.initialCities }
        val capKept = kept(capital, p)
        val targetKept = kept(target, p)
        val pop = if (capKept) capital!!.population.population else 0
        val units = p.units.getCivUnits().toList()
        val made = units.filter { it.id in s.producedUnits }
        val melee = made.count { it.name in d.meleeUnits }
        val ranged = made.count { it.name in d.rangedUnits }
        val siege = made.count { it.name in d.siegeUnits }
        val income = p.stats.statsForNextTurn.gold
        val happy = p.getHappiness()
        val rounds = d.observeRounds
        val stable = s.stableStreak >= rounds && income >= 0 && happy >= 0
        val losses = s.lostMilitary.size
        val second = p.cities.firstOrNull { it.id in s.foundedCities && it.id != s.capitalId && areaContains(s, it.location) }
        val connected = capKept && kept(second, p) && second!!.isConnectedToCapital()
        val explored = d.areas.map { a -> a.tiles.all { game.tileMap[it].isExplored(p) } }
        val improvements = d.targetTiles.map { game.tileMap[it] }
        val improved = improvements.size == 2 && improvements.withIndex().all { (i, t) ->
            t.getOwner() == p && t.getUnpillagedImprovement() == if (i == 0) "Farm" else "Mine" }
        val army = melee >= 2 && ranged >= 2
        val cityReady = targetKept && !target!!.isInResistance()
        var main = false
        var bonusA = false
        var bonusB = false
        val detail: List<String>
        fun done(value: Boolean) = if (value) "已完成" else "未完成"
        when (d.number) {
            1 -> {
                main = capKept && capital!!.id in s.foundedCities && d.areas.any { capital.location in it.tiles }
                bonusA = s.scoutedBeforeFounding && s.foundedTurn > 0
                bonusB = s.foundedTurn in 1..d.efficiencyTurn
                detail = listOf(
                    if (main) "首都已建在候选区，且仍属于你" else "任选粮食或产能候选区建城；结算时首都仍须属于你",
                    if (s.foundedTurn > 0) "建城前探索两处区域：[${done(s.scoutedBeforeFounding)}]" else
                        d.areas.joinToString("\n") { a -> "[${a.title}]：已探索 [${a.tiles.count { game.tileMap[it].isExplored(p) }}] / [${a.tiles.size}] 块地" },
                    if (s.foundedTurn > 0) "建城于第 [${s.foundedTurn}] 回合；要求不晚于第 [${d.efficiencyTurn}] 回合" else
                        "当前第 [${s.completedRounds + 1}] 回合；最晚第 [${d.efficiencyTurn}] 回合建城"
                )
            }
            2 -> {
                main = d.requiredProduction.all { it in s.produced }
                bonusA = units.any { it.id in s.producedWorkers && it.name == d.worker }
                bonusB = pop >= d.population
                detail = listOf(
                    d.requiredProduction.joinToString("\n") { "{${it}}: {${if (it in s.produced) "已生产完成" else "尚未生产完成"}}" },
                    "本次生产、目前仍属于你的工人：[${if (bonusA) 1 else 0}] / 1",
                    "开局首都人口：[$pop] / [${d.population}]"
                )
            }
            3 -> {
                main = improved
                bonusA = improved && capKept && improvements.all { it.position in capital!!.workedTiles }
                bonusB = capKept && capital!!.getWorkableTiles().any { it.getOwner() == p && it.position !in d.targetTiles && it.resource == null && it.getUnpillagedImprovement() in listOf("Farm", "Mine") }
                val resourceProgress = improvements.mapIndexed { i, tile ->
                    val expected = if (i == 0) "Farm" else "Mine"
                    val status = when {
                        tile.getOwner() != p -> "地块不属于你"
                        tile.improvement != expected -> "未建成"
                        tile.improvementIsPillaged -> "已被劫掠，需修复"
                        else -> "已完成"
                    }
                    "{${if (i == 0) "小麦农场" else "宝石矿山"}}: {$status}"
                }
                detail = listOf(
                    resourceProgress.joinToString("\n") + "\n{结算时，两处地块须属于你，且农场、矿山未被劫掠。}",
                    "首都已安排市民：[${if (capKept) improvements.count { it.position in capital!!.workedTiles } else 0}] / 2 处",
                    "额外农场或矿山：[${if (bonusB) 1 else 0}] / 1\n地块须在首都工作范围内、属于你且没有资源，设施未被劫掠。"
                )
            }
            4 -> {
                main = capKept && pop >= d.populationMain && has(capital, "Monument")
                bonusA = pop >= d.population
                bonusB = capKept && capital!!.cityStats.currentCityStats.production >= d.productionTarget && capital.cityStats.currentCityStats.food >= 0
                detail = listOf(
                    "开局首都人口：[$pop] / [${d.populationMain}]；纪念碑：[${if (has(capital, "Monument") && capKept) "已拥有" else "未拥有"}]",
                    "开局首都人口：[$pop] / [${d.population}]",
                    "每回合产能：[${capital?.takeIf { capKept }?.cityStats?.currentCityStats?.production ?: 0f}] / [${d.productionTarget}]\n粮食盈余：[${capital?.takeIf { capKept }?.cityStats?.currentCityStats?.food ?: 0f}]（须至少为 0）"
                )
            }
            5 -> {
                main = explored.take(3).size == 3 && explored.take(3).all { it }
                bonusA = p.getKnownCivs().any { it.civName == d.enemy }
                bonusB = explored.getOrNull(3) == true
                detail = listOf(
                    d.areas.take(3).joinToString("\n") { a -> "[${a.title}]：已探索 [${a.tiles.count { game.tileMap[it].isExplored(p) }}] / [${a.tiles.size}] 块地" },
                    "与[${d.enemy}]建立联系：[${done(bonusA)}]",
                    "支线高地：已探索 [${d.areas.getOrNull(3)?.tiles?.count { game.tileMap[it].isExplored(p) } ?: 0}] / [${d.areas.getOrNull(3)?.tiles?.size ?: 0}] 块地"
                )
            }
            6 -> {
                main = "Writing" in p.tech.techsResearched && capKept && has(capital, "Library")
                bonusA = "Animal Husbandry" in p.tech.techsResearched
                bonusB = pop >= d.population
                detail = listOf(
                    "文字科技：[${if ("Writing" in p.tech.techsResearched) "已研究" else "未研究"}]\n开局首都的图书馆：[${if (capKept && has(capital, "Library")) "已建成" else "未建成"}]",
                    "畜牧业科技：[${if (bonusA) "已研究" else "未研究"}]", "开局首都人口：[$pop] / [${d.population}]"
                )
            }
            7 -> {
                main = s.incomeStreak >= rounds && income >= 0
                bonusA = initial.size == 2 && initial.all { kept(it, p) && has(it, "Market") }
                bonusB = p.gold >= d.goldTarget && income >= d.incomeTarget
                detail = listOf(
                    "已连续不亏损 [${s.incomeStreak.coerceAtMost(rounds)}] / [$rounds] 轮；当前每回合净收入 [$income]",
                    "开局城市中，属于你且有市场的城市：[${initial.count { kept(it, p) && has(it, "Market") }}] / 2",
                    "金币：[${p.gold}] / [${d.goldTarget}]；每回合净收入：[$income] / +[${d.incomeTarget}]"
                )
            }
            8 -> {
                main = connected && s.stableStreak >= rounds && happy >= 0
                bonusA = kept(second, p) && has(second, "Granary")
                bonusB = connected && s.incomeStreak >= rounds && income >= d.incomeTarget
                detail = listOf(
                    "两城连接：[${if (connected) "已连接" else "未连接"}]；幸福度：[$happy]\n已连续达标 [${s.stableStreak.coerceAtMost(rounds)}] / [$rounds] 轮",
                    "新城粮仓：[${if (bonusA) "已建成且属于你" else "未完成"}]",
                    "每回合净收入：[$income] / +[${d.incomeTarget}]；已连续达标 [${s.incomeStreak.coerceAtMost(rounds)}] / [$rounds] 轮"
                )
            }
            9 -> {
                main = army && p.gold >= 0 && income >= 0
                bonusA = melee >= 3
                bonusB = army && s.incomeStreak >= rounds && income >= d.incomeTarget
                detail = listOf(
                    "自产部队：近战 [$melee] / 2，远程 [$ranged] / 2\n金币：[${p.gold}]；每回合净收入：[$income]（两项须至少为 0）",
                    "本次生产、目前仍归你的近战部队：[$melee] / 3",
                    "部队齐备且每回合收入至少 +[${d.incomeTarget}]：[${s.incomeStreak.coerceAtMost(rounds)}] / [$rounds] 轮\n当前每回合净收入：[$income]"
                )
            }
            10 -> {
                main = s.killedUnits.size >= 3 || s.enemyEliminated
                bonusA = losses == 0
                bonusB = s.killRoles.containsAll(listOf("melee", "ranged"))
                detail = listOf(
                    "已击败[${d.enemy}]军事单位 [${s.killedUnits.size}] / 3 支；通过战斗消灭[${d.enemy}]：[${done(s.enemyEliminated)}]",
                    "战斗中已损失 [$losses] 支军事单位（要求 0 支）",
                    "近战完成击杀：[${done("melee" in s.killRoles)}]；远程完成击杀：[${done("ranged" in s.killRoles)}]"
                )
            }
            11 -> {
                main = !s.capitalLost && capKept && s.completedRounds >= d.defendRounds
                bonusA = initial.size == 3 && initial.all { kept(it, p) }
                bonusB = losses <= d.lossLimit
                detail = listOf(
                    "首都：[${if (s.capitalLost || !capKept) "已失守" else "仍在坚守"}]；已守过 [${s.completedRounds}] / [${d.defendRounds}] 个完整回合",
                    "目前仍属于你的初始城市：[${initial.count { kept(it, p) }}] / 3",
                    "战斗中已损失 [$losses] 支军事单位（最多 [${d.lossLimit}] 支）"
                )
            }
            12 -> {
                main = targetKept && target!!.id in s.capturedCities && s.completedRounds + 1 <= d.hardTurnLimit
                bonusA = s.completedRounds + 1 <= d.efficiencyTurn
                bonusB = !s.protectedUnitLost && s.protectedUnits.size == 2 && units.count { it.id in s.protectedUnits } == 2
                detail = listOf(
                    "攻下并留住要塞：[${done(main)}]\n当前第 [${s.completedRounds + 1}] 回合；最晚第 [${d.hardTurnLimit}] 回合完成处置",
                    "当前第 [${s.completedRounds + 1}] 回合；须在第 [${d.efficiencyTurn}] 回合内通关",
                    if (s.protectedUnitLost) "已失去初始攻城部队，补造也无法恢复这颗星" else
                        "目前仍属于你的初始攻城部队：[${units.count { it.id in s.protectedUnits }}] / 2 支"
                )
            }
            13 -> {
                main = cityReady && stable
                bonusA = targetKept && target!!.isConnectedToCapital()
                bonusB = targetKept && has(target, "Market")
                detail = listOf(
                    "占领城市：[${if (!targetKept) "未由你控制" else if (cityReady) "抵抗已结束" else "仍在抵抗"}]\n净收入：[$income]；幸福度：[$happy]；已连续达标 [${s.stableStreak.coerceAtMost(rounds)}] / [$rounds] 轮",
                    "占领城市与首都：[${if (bonusA) "已连接" else "未连接"}]", "占领城市的市场：[${if (bonusB) "已建成且属于你" else "未完成"}]"
                )
            }
            14 -> {
                main = targetKept && target!!.id in s.capturedCities
                bonusA = siege >= 1
                bonusB = losses <= d.lossLimit
                detail = listOf(
                    "攻下目标首都并选择傀儡或吞并：[${done(main)}]；结算时城市仍须属于你",
                    "本次生产、目前仍归你的攻城部队：[$siege] / 1",
                    "战斗中已损失 [$losses] 支军事单位（最多 [${d.lossLimit}] 支）"
                )
            }
            15, 16 -> {
                main = game.victoryData?.let { it.winningCiv == p.civID && it.victoryType == "Domination" } == true
                bonusA = if (d.number == 15) losses <= d.lossLimit else s.completedRounds + 1 <= d.efficiencyTurn
                bonusB = stable && (d.number == 15 || p.gold >= d.goldTarget)
                detail = listOf(
                    if (main) "已取得征服胜利" else "尚未取得征服胜利；攻占并控制各文明最早建立的首都",
                    if (d.number == 15) "战斗中已损失 [$losses] 支军事单位（最多 [${d.lossLimit}] 支）" else
                        "当前第 [${s.completedRounds + 1}] 回合；最晚第 [${d.efficiencyTurn}] 回合获胜",
                    "收入与幸福度已连续达标 [${s.stableStreak.coerceAtMost(rounds)}] / [$rounds] 轮\n净收入：[$income]；幸福度：[$happy]".let { "{$it}" } +
                        if (d.number == 16) "{；金币：[${p.gold}] / [${d.goldTarget}]}" else ""
                )
            }
            else -> error("Unknown campaign lesson")
        }
        val failure = when {
            s.failed -> s.failureReason.ifEmpty { "本次挑战失败" }
            p.isDefeated() -> "玩家文明已被消灭"
            d.number == 11 && (s.capitalLost || !capKept) -> "初始首都已经失守"
            d.targetCity.isNotEmpty() && target == null -> "目标城市已被永久摧毁"
            d.number in listOf(2, 4, 6) && capital == null -> "初始首都已被永久摧毁"
            d.number == 12 && s.completedRounds >= d.hardTurnLimit && !main -> "攻城期限已结束"
            d.number >= 15 && game.victoryData?.let { it.winningCiv != p.civID } == true -> "对手已取得征服胜利"
            else -> ""
        }
        val impossible = hashSetOf<Int>()
        if (d.number == 1) {
            if (s.foundedTurn > 0 && !s.scoutedBeforeFounding) impossible.add(1)
            if (s.foundedTurn > d.efficiencyTurn || s.foundedTurn == 0 && s.completedRounds + 1 > d.efficiencyTurn) impossible.add(2)
        }
        if (d.number == 10 && losses > 0 || d.number == 15 && losses > d.lossLimit ||
            d.number == 16 && s.completedRounds + 1 > d.efficiencyTurn ||
            d.number == 12 && s.completedRounds + 1 > d.efficiencyTurn) impossible.add(1)
        if (d.number in listOf(11,14) && losses > d.lossLimit || d.number == 12 && s.protectedUnitLost) impossible.add(2)
        return Evaluation(main, bonusA, bonusB, failure.isNotEmpty(), detail, failure, impossible)
    }

    /** Does not advance observations. Safe after actions, loading, or a screen refresh. */
    fun actionSettled(game: GameInfo) {
        val s = active(game) ?: return
        if (s.actionDepth > 0 || s.settling) return
        s.settling = true
        try {
            if (game.civilizations.any { c -> c.popupAlerts.any { it.type == AlertType.CityConquered } } &&
                !(CivilWarCampaign.isActive(game) && evaluate(game).failed)) return
            if (s.definition.id != CivilWarCampaign.id && s.definition.number >= 15) game.checkForVictory()
            val r = evaluate(game)
            if (r.failed) {
                s.failed = true
                s.failureReason = r.failureReason
                s.resultDetails = ArrayList(r.details)
            } else if (s.definition.automatic && r.main) freeze(s, r)
        } finally { s.settling = false }
    }

    fun freeze(s: CampaignState, r: Evaluation) {
        s.resultStars = r.stars
        s.resultWorker = r.worker
        s.resultPopulation = r.population
        s.resultDetails = ArrayList(r.details)
    }

    /** One observation after every full round, never at initialization or deserialization. */
    fun roundCompleted(game: GameInfo) {
        val s = active(game) ?: return
        if (game.turns <= s.lastSampleTurn) { actionSettled(game); return }
        s.completedRounds = game.turns
        s.lastSampleTurn = game.turns
        val p = game.civilizations.firstOrNull { it.civID == s.playerId }
            ?: run { actionSettled(game); return }
        val d = s.definition
        val capital = p.cities.firstOrNull { it.id == s.capitalId }
        val second = p.cities.firstOrNull { it.id in s.foundedCities && it.id != s.capitalId && areaContains(s, it.location) }
        val expansionReady = kept(capital, p) && kept(second, p) && second!!.isConnectedToCapital()
        val cityReady = p.cities.any { it.id == d.targetCity && kept(it, p) && !it.isInResistance() }
        val made = p.units.getCivUnits().filter { it.id in s.producedUnits }.toList()
        val armyReady = made.count { it.name in d.meleeUnits } >= 2 && made.count { it.name in d.rangedUnits } >= 2
        val income = p.stats.statsForNextTurn.gold
        val happy = p.getHappiness()
        val incomeOK = when (d.number) {
            8 -> expansionReady && income >= d.incomeTarget
            9 -> armyReady && income >= d.incomeTarget
            else -> income >= 0
        }
        val stableOK = when (d.number) {
            8 -> expansionReady && happy >= 0
            13 -> cityReady && income >= 0 && happy >= 0
            else -> income >= 0 && happy >= 0
        }
        s.incomeStreak = if (incomeOK) s.incomeStreak + 1 else 0
        s.stableStreak = if (stableOK) s.stableStreak + 1 else 0
        actionSettled(game)
    }
}
