package com.unciv.logic.endless

import com.badlogic.gdx.Gdx
import com.unciv.logic.GameInfo
import com.unciv.logic.battle.ICombatant
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.NotificationCategory
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.models.ruleset.Ruleset
import com.unciv.models.ruleset.RulesetCache
import yairm210.purity.annotations.Readonly
import kotlin.random.Random

object EndlessRules {
    val initialTechs = setOf("Agriculture", "Pottery", "Mining", "Archery", "Animal Husbandry", "Bronze Working")
    val technologies = setOf("Masonry", "Writing", "The Wheel", "Iron Working", "Construction", "Horseback Riding",
        "Mathematics", "Currency", "Engineering", "Metal Casting", "Steel", "Machinery", "Chivalry", "Physics")
    val units = setOf("Spearman", "Swordsman", "Longswordsman", "Archer", "Composite Bowman", "Crossbowman",
        "Horseman", "Knight", "Catapult", "Trebuchet", "Worker")
    val buildings = setOf("Granary", "Monument", "Walls", "Barracks", "Library", "Water Mill", "Stable", "Market", "Aqueduct", "Workshop", "Castle")
    private val improvements = setOf("Farm", "Mine", "Pasture", "Quarry", "Plantation", "Camp", "Lumber mill", "Road", "Fort")

    @Readonly fun isInvader(civ: Civilization) = civ.gameInfo.endless?.invaderId == civ.civID
    @Readonly fun blocked(game: GameInfo) = game.endless?.let { it.ended || it.saveBlocked || it.offer != null } == true
        || game.endless?.stageState?.phase == "RewardPending"
    @Readonly fun constructionAllowed(city: City, name: String): Boolean {
        val s = city.civ.gameInfo.endless ?: return true
        if (s.modeVersion == 2 && (s.ended || s.saveBlocked || !s.inTurn && s.stageState?.phase == "RewardPending")) return false
        return if (name == "Settler") EndlessStageRules.canTrainSettler(city.civ) else name in units || name in buildings || name in setOf("Gold", "Nothing", "")
    }
    @Readonly fun researchBlocked(civ: Civilization, name: String) = civ.gameInfo.endless != null && name !in technologies && name !in initialTechs
    @Readonly fun improvementAllowed(civ: Civilization, name: String): Boolean = civ.gameInfo.endless == null ||
        name in improvements || name in setOf("Repair", "Cancel improvement order", "Remove Road", "Remove Forest", "Remove Jungle", "Remove Marsh")
    @Readonly fun ownershipAllowed(city: City, tile: Tile): Boolean {
        val s = city.civ.gameInfo.endless ?: return true
        if (s.modeVersion == 2 && (s.ended || s.saveBlocked || !s.inTurn && s.stageState?.phase == "RewardPending")) return false
        return tile.aerialDistanceTo(s.stageState?.corePosition?.let { city.civ.gameInfo.tileMap[it] } ?: city.getCenterTile()) < 8
    }

    fun ruleset(game: GameInfo): Ruleset {
        require(game.campaign == null && game.gameParameters.mods.isEmpty() && !game.gameParameters.isOnlineMultiplayer)
        EndlessCatalog.requireCompatible(game)
        val old = game.endless!!.modeVersion == 1
        val overlay = Ruleset().apply { name = "Endless survival"; load(Gdx.files.internal(if (old) "endless-legacy/endless/rules" else "endless-v2/rules")) }
        val base = if (!old) RulesetCache.getComplexRuleset(game.gameParameters) else {
            val vanilla = Ruleset().apply { name = "Civ V - Vanilla"; load(Gdx.files.internal("endless-legacy/jsons/$name")) }
            Ruleset().apply { name = "Civ V - Gods & Kings"; load(Gdx.files.internal("endless-legacy/jsons/$name"), vanilla) }
        }
        return RulesetCache.getComplexRuleset(base, listOf(overlay))
    }

    @Readonly fun player(game: GameInfo) = game.getCivilization(game.endless!!.playerId)
    @Readonly fun invaders(game: GameInfo) = game.getCivilization(game.endless!!.invaderId)
    @Readonly fun core(game: GameInfo) = game.getCities().firstOrNull { it.id == game.endless!!.coreId }

    fun loseCore(game: GameInfo, cityId: String) {
        val s = game.endless ?: return
        if (cityId != s.coreId || s.ended) return
        s.stageState?.apply { resultRound = s.round; endReason = "CoreLost" }
        s.coreLost = true
        s.pendingGold = 0
        s.offer = null
        s.status = "Defeated"
        s.endedAt = System.currentTimeMillis()
    }

    fun fault(game: GameInfo, reason: String) {
        val s = game.endless ?: return
        if (s.ended) return
        s.stageState?.resultRound = s.round
        s.status = "Faulted"
        s.recordEligible = false
        s.fault = reason
        s.offer = null
        s.endedAt = System.currentTimeMillis()
    }

    fun unitLost(unit: MapUnit) {
        val s = unit.civ.gameInfo.endless ?: return
        if (unit.civ.civID != s.playerId || unit.isDestroyed) return
        if (unit.isMilitary()) s.militaryLosses++ else s.civilianLosses++
    }

    fun combatDefeat(killer: ICombatant, victim: MapUnit) {
        if (victim.civ.gameInfo.endless?.modeVersion == 2) { EndlessStageRules.combatDefeat(killer, victim); return }
        val game = victim.civ.gameInfo
        val s = game.endless ?: return
        if (s.ended || killer.getCivInfo().civID != s.playerId || victim.civ.civID != s.invaderId) return
        val wave = s.waves.firstOrNull { w -> w.members.any { it.unitId == victim.id } } ?: return
        wave.members.removeAll { it.unitId == victim.id }
        s.kills++
        if (wave.members.isEmpty()) {
            s.pendingGold += 20 + minOf(20, 2 * wave.number)
            s.clearedWaves++
            s.waves.remove(wave)
            s.clearedPrefix = (s.waves.minOfOrNull { it.number } ?: (s.waveNumber + 1)) - 1
            if (s.waves.isEmpty()) {
                s.earlyWaveRound = s.round + 3
                s.pendingGold += 10
            }
            notify(game, "Wave [${wave.number}] cleared: [${20 + minOf(20, 2 * wave.number)}] Gold.")
        }
    }

    fun actionSettled(game: GameInfo) {
        if (game.endless?.modeVersion == 2) { EndlessStageRules.actionSettled(game); return }
        val s = game.endless ?: return
        if (s.actionDepth != 0 || s.settling || s.ended) return
        s.settling = true
        try {
            if (core(game)?.civ?.civID != s.playerId) { loseCore(game, s.coreId); return }
            val actual = invaders(game).units.getCivUnits().map { it.id }.toSet()
            if (s.waves.any { w -> w.members.any { it.unitId != 0 && it.unitId !in actual } }) {
                fault(game, "An invader disappeared outside a confirmed battle.")
                return
            }
            if (!s.inTurn) settleBenefits(game)
        } finally { s.settling = false }
    }

    fun settleBenefits(game: GameInfo) {
        if (game.endless?.modeVersion == 2) { EndlessStageRules.settleBenefits(game); return }
        val s = game.endless ?: return
        if (s.ended) return
        player(game).addGold(s.pendingGold)
        s.pendingGold = 0
        if (s.offer == null && s.claimedStages < s.clearedPrefix / 5) {
            s.offer = EndlessReward().apply {
                stage = s.claimedStages + 1
                gold = minOf(160, 70 + 10 * stage)
                unit = reinforcementType(game)
            }
        }
    }

    /** Called after normal human start-turn processing, never by render or resume. */
    fun prepareRound(game: GameInfo) {
        if (game.endless?.modeVersion == 2) { EndlessStageRules.prepareRound(game); return }
        val s = game.endless ?: return
        if (s.ended || s.lastPreparedRound == s.round) return
        if (core(game)?.civ?.civID != s.playerId) { loseCore(game, s.coreId); return }
        s.lastPreparedRound = s.round
        deployPending(game)
        if (s.round >= s.nextWaveRound && s.pendingCount == 0) {
            val wave = EndlessWavePlanner.plan(s)
            s.waves.add(wave)
            s.waveNumber = wave.number
            s.lastWaveRound = s.round
            s.earlyWaveRound = 0
            val directions = listOf("West", "Southwest", "Southeast", "East", "Northeast", "Northwest")
            notify(game, "Wave [${wave.number}] is approaching from [${directions[wave.mainSector]}]!")
            deployPending(game)
        }
        if (s.round == 4) notify(game, "The first wave arrives in [2] rounds.")
        if (s.round in listOf(35, EndlessWavePlanner.finalTierRound - 1)) notify(game, "Stronger invaders may arrive with the next wave.")
        if (s.round >= 120 && (s.round - 120) % 12 == 0)
            notify(game, "Next round: continuous siege increases to [+${EndlessWavePlanner.pressure(s.round + 1)}]% enemy strength.")
        retryReinforcement(game)
        settleBenefits(game)
    }

    fun sector(tile: Tile): Int {
        val position = com.unciv.logic.map.HexMath.hex2WorldCoords(tile.position)
        val angle = kotlin.math.atan2(position.y.toDouble(), position.x.toDouble())
        return kotlin.math.floor((angle + Math.PI + Math.PI / 6) / (Math.PI / 3)).toInt().mod(6)
    }

    private fun deployPending(game: GameInfo) {
        val s = game.endless!!
        val civ = invaders(game)
        val center = core(game)!!.getCenterTile()
        var capacity = 40 - s.activeCount
        for (wave in s.waves) for (member in wave.members) {
            if (capacity == 0) return
            if (member.unitId != 0) continue
            val random = Random(s.seed xor (wave.number * 104729L))
            val candidates = game.tileMap.values.sortedWith(compareBy({ it.position.x }, { it.position.y }))
                .map { it to random.nextInt() }.filter { (t, _) ->
                    t.aerialDistanceTo(center) in 8..10 && sector(t) == member.sector &&
                        t.isLand && !t.isImpassible() && !t.isCityCenter() && t.militaryUnit == null && t.civilianUnit == null
                }.sortedWith(compareBy({ it.first.aerialDistanceTo(center) }, { it.second }, { it.first.position.x }, { it.first.position.y }))
            val tile = candidates.firstOrNull()?.first ?: continue
            val unit = civ.units.placeUnitNearTile(tile.position, member.name) ?: continue
            check(unit.currentTile == tile)
            member.unitId = unit.id
            if (member.promotion.isNotEmpty()) {
                unit.promotions.addPromotion(member.promotion, isFree = true)
                unit.promotions.numberOfPromotions = 1
            }
            unit.promotions.XP = 0
            capacity--
        }
    }

    private fun reinforcementType(game: GameInfo): String {
        val civ = player(game)
        val round = game.endless!!.round
        val chains = listOf(listOf("Spearman", "Swordsman", "Longswordsman"), listOf("Archer", "Composite Bowman", "Crossbowman"), listOf("Horseman", "Knight"))
        val available = chains.map { chain -> chain.filter { name ->
            val unit = game.ruleset.units.getValue(name)
            val timeAllowed = when (name) { "Longswordsman", "Crossbowman", "Knight" -> round >= EndlessWavePlanner.finalTierRound; "Swordsman", "Composite Bowman" -> round >= 36; else -> true }
            timeAllowed && (unit.requiredTech == null || civ.tech.isResearched(unit.requiredTech!!)) &&
                unit.getResourceRequirementsPerTurn(civ.state).all { (r, amount) -> (civ.getCivResourcesByName()[r] ?: 0) >= amount }
        }.lastOrNull() }
        val counts = chains.map { chain -> civ.units.getCivUnits().count { it.name in chain } }
        val role = (0..2).filter { available[it] != null }.minBy { counts[it] * if (it == 2) 2 else 1 }
        return available[role]!!
    }

    fun canRepair(game: GameInfo): Boolean = if (game.endless!!.modeVersion == 2) EndlessStageRules.canRepair(game)
        else core(game)!!.let { it.health < it.getMaxHealth() } || player(game).units.getCivUnits().any { it.isMilitary() && it.health < 100 }

    /** Mutates a detached candidate only; EndlessStore commits it before publication. */
    fun applyReward(game: GameInfo, choice: String) {
        if (game.endless?.modeVersion == 2) { EndlessStageRules.applyReward(game, choice); return }
        val s = game.endless!!
        check(!s.ended)
        val reward = requireNotNull(s.offer)
        when (choice) {
            "Gold" -> player(game).addGold(reward.gold)
            "Repair" -> {
                check(canRepair(game))
                val city = core(game)!!
                city.health = minOf(city.getMaxHealth(), city.health + city.getMaxHealth() * 30 / 100)
                player(game).units.getCivUnits().filter { it.isMilitary() }.forEach { it.health = minOf(100, it.health + 25) }
            }
            "Reinforcement" -> {
                check(s.reinforcement == null)
                s.reinforcement = EndlessReinforcement().apply {
                    unit = reward.unit; expiresRound = s.round + 3; fallbackGold = reward.gold
                }
                retryReinforcement(game)
            }
            else -> error("Unknown reward")
        }
        s.claimedStages = reward.stage
        s.offer = null
        settleBenefits(game)
    }

    private fun retryReinforcement(game: GameInfo) {
        val s = game.endless!!
        val pending = s.reinforcement ?: return
        val civ = player(game)
        val resourcesAvailable = game.ruleset.units.getValue(pending.unit).getResourceRequirementsPerTurn(civ.state)
            .all { (resource, amount) -> (civ.getCivResourcesByName()[resource] ?: 0) >= amount }
        val tile = if (!resourcesAvailable) null else core(game)!!.getCenterTile().getTilesInDistance(3)
            .filter { it.getOwner() == civ && it.militaryUnit == null && it.isLand && !it.isImpassible() }
            .sortedWith(compareBy({ it.aerialDistanceTo(core(game)!!.getCenterTile()) }, { it.position.x }, { it.position.y })).firstOrNull()
        if (tile != null) {
            val unit = civ.units.placeUnitNearTile(tile.position, pending.unit)!!
            check(unit.currentTile == tile)
            unit.currentMovement = 0f
            unit.attacksThisTurn = 1
            s.reinforcement = null
        } else if (s.round >= pending.expiresRound) {
            civ.addGold(pending.fallbackGold)
            s.reinforcement = null
        }
    }

    private fun notify(game: GameInfo, text: String) = player(game).addNotification(text, NotificationCategory.War)
}
