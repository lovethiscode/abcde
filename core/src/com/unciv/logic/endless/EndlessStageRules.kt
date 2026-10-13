package com.unciv.logic.endless

import com.unciv.logic.GameInfo
import com.unciv.logic.battle.ICombatant
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.NotificationCategory
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import yairm210.purity.annotations.Readonly
import kotlin.random.Random

/** V2 only; legacy hooks remain in EndlessRules. */
object EndlessStageRules {
    @Readonly fun capacity(s: EndlessState): Int = s.stageState?.let { 1 + (if (it.completedStages >= 2) 1 else 0) + (if (it.completedStages >= 4) 1 else 0) } ?: 1
    @Readonly fun canTrainSettler(civ: Civilization): Boolean = civ.gameInfo.endless?.let {
        it.modeVersion == 2 && !it.ended && civ.civID == it.playerId && civ.cities.size < capacity(it)
    } == true

    @Readonly fun canFound(civ: Civilization, tile: Tile, unit: MapUnit?): Boolean {
        val s = civ.gameInfo.endless ?: return true
        val state = s.stageState ?: return false
        return !EndlessRules.blocked(civ.gameInfo) && !s.inTurn && canTrainSettler(civ) &&
            civ.gameInfo.currentPlayer == civ.civID && unit != null && unit.civ == civ && unit.name == "Settler" &&
            !unit.isDestroyed && unit.currentTile == tile && unit.hasMovement() &&
            EndlessRules.core(civ.gameInfo)?.civ == civ && tile.aerialDistanceTo(civ.gameInfo.tileMap[state.corePosition!!]) <= 4 && tile.canBeSettled(civ)
    }

    fun validate(game: GameInfo) {
        val s = game.endless!!; val state = requireNotNull(s.stageState)
        require(runCatching { java.util.UUID.fromString(s.attemptId).toString() == s.attemptId }.getOrDefault(false))
        require(state.pace in setOf("casual","wild") && state.phase in setOf("Preparation","Combat","RewardPending","Rest"))
        require(state.currentStage >= 1 && state.completedStages >= 0 && state.combatRounds >= 0)
        require(state.combatRounds >= 13 * state.completedStages && state.preparedRound >= 0 && state.normalStartedRound >= 1)
        require(state.combatRounds <= s.completedRounds.toLong())
        require(state.corePosition != null && game.tileMap.contains(state.corePosition!!))
        require(s.waves.size == 5 && s.waves.sumOf { it.members.size } <= 54)
        require(s.activeCount <= 40 && s.activeCount + s.pendingCount <= 54)
        require(state.deadline == EndlessCounter.add(state.startRound, EndlessStagePlanner.duration(state.currentStage) - 1))
        require(state.prepareStep in 0..2 && state.preparedRound <= s.round && state.normalStartedRound <= s.round)
        require(s.claimedStages.toLong() in (state.completedStages - 1).coerceAtLeast(0)..state.completedStages)
        require(s.waves.map { it.number }.toSet().size == 5)
        for ((j,wave) in s.waves.withIndex()) {
            require(wave.number.toLong() == 5 * (state.currentStage - 1) + j + 1)
            require(wave.round == EndlessCounter.add(state.startRound,j * EndlessStagePlanner.interval(state.currentStage)))
            require(wave.members.size == EndlessStagePlanner.numbers(state.currentStage)[j])
            require(wave.mainSector in 0..5 && wave.secondarySector in 0..5)
            require(wave.members.map { it.index }.toSet().size == wave.members.size)
            require(wave.cleared == (wave.announced && wave.members.all { it.lifecycle == "Dead" }))
            require(!wave.announced || wave.round <= s.round)
            for (m in wave.members) {
                require(m.sector == wave.mainSector || state.currentStage > 1 && m.sector == wave.secondarySector)
                require(m.lifecycle in setOf("Future","Pending","Active","Dead"))
                require(wave.announced == (m.lifecycle != "Future"))
                require((m.lifecycle == "Active") == (m.unitId != 0))
                require(m.name in EndlessStagePlanner.tiers.map { it[EndlessWavePlanner.roles.indexOf(m.role)] })
                require(m.promotion.isEmpty() || m.promotion in game.ruleset.unitPromotions)
            }
        }
        if (!s.ended) {
            require(state.phase != "Preparation" || s.round < state.startRound && s.waves.none { it.announced })
            require(state.phase != "Combat" || s.round in state.startRound..state.deadline)
            require(state.phase !in setOf("RewardPending","Rest") || state.clearedAt >= state.startRound &&
                state.nextStageRound == EndlessCounter.add(state.clearedAt,if (state.pace == "wild") 1 else 4))
            require(EndlessRules.player(game).cities.size <= capacity(s))
            require(EndlessRules.core(game)?.location == state.corePosition)
            require(EndlessRules.player(game).cities.all { it.getCenterTile().aerialDistanceTo(game.tileMap[state.corePosition!!]) <= 4 })
            require(EndlessRules.player(game).cities.all { c -> c.getTiles().all { it.aerialDistanceTo(game.tileMap[state.corePosition!!]) <= 7 } })
            require(if (state.phase in setOf("RewardPending","Rest")) state.currentStage == state.completedStages && s.waves.all { it.cleared }
                else state.currentStage == state.completedStages + 1)
            require((state.phase == "RewardPending") == (s.claimedStages.toLong() < state.completedStages))
        }
    }

    fun combatDefeat(killer: ICombatant, victim: MapUnit) {
        val game = victim.civ.gameInfo; val s = game.endless!!
        if (s.ended || killer.getCivInfo().civID != s.playerId || victim.civ.civID != s.invaderId) return
        val wave = s.waves.firstOrNull { w -> w.members.any { it.lifecycle == "Active" && it.unitId == victim.id } } ?: return
        val m = wave.members.single { it.unitId == victim.id }
        m.lifecycle = "Dead"; m.unitId = 0; s.kills = EndlessCounter.add(s.kills,1)
        if (wave.members.all { it.lifecycle == "Dead" } && !wave.cleared) {
            wave.cleared = true; s.clearedWaves = EndlessCounter.add(s.clearedWaves,1)
            val gold = (20L + minOf(20L, 2L * wave.number)).toInt()
            s.pendingGold = EndlessCounter.add(s.pendingGold,gold)
            if (s.activeCount + s.pendingCount == 0) s.pendingGold = EndlessCounter.add(s.pendingGold,10)
            notify(game,"Wave [${wave.number}] cleared: [$gold] Gold.")
        }
    }

    fun actionSettled(game: GameInfo) {
        val s = game.endless!!; val state = s.stageState!!
        if (s.actionDepth != 0 || s.settling || s.ended) return
        s.settling = true
        try {
            if (EndlessRules.core(game)?.civ?.civID != s.playerId) { EndlessRules.loseCore(game,s.coreId); return }
            val actual = EndlessRules.invaders(game).units.getCivUnits().map { it.id }.toSet()
            val registered = s.waves.flatMap { it.members }.filter { it.lifecycle == "Active" }.map { it.unitId }.toSet()
            if (registered != actual) { EndlessRules.fault(game,"An invader disappeared outside a confirmed battle."); return }
            if (state.phase == "Combat" && s.waves.all { it.cleared }) {
                state.completedStages = EndlessCounter.add(state.completedStages,1)
                state.combatRounds = EndlessCounter.add(state.combatRounds,(s.round - state.startRound + 1).toLong())
                state.clearedAt = s.round
                state.nextStageRound = EndlessCounter.add(s.round,if (state.pace == "wild") 1 else 4)
                state.phase = "RewardPending"
                state.preview = EndlessStagePlanner.plan(s.clone(),state.completedStages + 1,state.nextStageRound).first().apply { members.clear() }
            }
            if (!s.inTurn) settleBenefits(game)
        } catch (ex: ArithmeticException) { EndlessRules.fault(game,"The endless counter exceeded the supported engine range.") }
        finally { s.settling = false }
    }

    /** This runs before the next human startTurn, including on the last permitted round. */
    fun roundCompleted(game: GameInfo) {
        val s = game.endless!!; val state = s.stageState!!
        if (s.ended) return
        val completed = s.round
        s.completedRounds = EndlessCounter.add(s.completedRounds,1)
        if (state.phase == "Combat" && completed >= state.deadline) {
            state.resultRound = completed; state.endReason = "StageTimeout"
            s.status = "Defeated"; s.offer = null; s.pendingGold = 0; s.endedAt = System.currentTimeMillis()
        }
    }

    fun settleBenefits(game: GameInfo) {
        val s = game.endless!!; val state = s.stageState!!
        if (s.ended || s.inTurn) return
        EndlessRules.player(game).addGold(s.pendingGold); s.pendingGold = 0
        if (state.phase == "RewardPending" && s.offer == null) s.offer = EndlessReward().apply {
            stage = EndlessCounter.toInt(state.completedStages)
            gold = minOf(160L,70L + 10L * state.completedStages).toInt()
            unit = reinforcementType(game)
        }
    }

    fun prepareRound(game: GameInfo) {
        val s = game.endless!!; val state = s.stageState!!
        if (s.ended || s.lastPreparedRound == s.round) return
        try {
            if (state.preparedRound != s.round) { state.preparedRound = s.round; state.prepareStep = 0 }
            if (s.waves.isEmpty()) s.waves = EndlessStagePlanner.plan(s,1,6)
            settleBenefits(game)
            if (state.phase == "RewardPending") return
            if (state.prepareStep == 0) { retryReinforcement(game); state.prepareStep = 1 }
            if (state.phase == "Rest" && s.round >= state.nextStageRound) {
                check(s.claimedStages.toLong() == state.completedStages)
                state.currentStage = EndlessCounter.add(state.completedStages,1)
                state.startRound = state.nextStageRound
                state.deadline = EndlessCounter.add(state.startRound,EndlessStagePlanner.duration(state.currentStage) - 1)
                val plan = EndlessStagePlanner.plan(s,state.currentStage,state.startRound)
                check(state.preview?.let { it.mainSector == plan.first().mainSector && it.template == plan.first().template } != false)
                s.waves = plan; state.preview = null; state.phase = "Combat"
            }
            if (state.phase == "Preparation" && s.round >= state.startRound) state.phase = "Combat"
            if (state.phase == "Combat") {
                for (wave in s.waves.filter { !it.announced && it.round <= s.round }) {
                    wave.announced = true; wave.members.forEach { it.lifecycle = "Pending" }
                    s.waveNumber = wave.number
                    notify(game,"Wave [${wave.number}] is approaching from [${directions[wave.mainSector]}]!")
                }
                deployPending(game)
                val remaining = state.deadline - s.round + 1
                if (remaining in 1..3) notify(game,"Stage [${state.currentStage}] · [$remaining] combat rounds remaining. Queued enemies do not extend the deadline.")
            }
            if (s.round == 4) notify(game,"The first wave arrives in [2] rounds.")
            state.prepareStep = 2; s.lastPreparedRound = s.round
        } catch (ex: ArithmeticException) { EndlessRules.fault(game,"The endless counter exceeded the supported engine range.") }
    }

    private fun deployPending(game: GameInfo) {
        val s = game.endless!!; var capacity = 40 - s.activeCount
        val core = game.tileMap[s.stageState!!.corePosition!!]
        for (wave in s.waves.filter { it.announced }) for (m in wave.members.filter { it.lifecycle == "Pending" }) {
            if (capacity <= 0) return
            val random = Random(s.seed xor (104729L * wave.number) xor (65537L * m.index))
            val candidates = game.tileMap.values.sortedWith(compareBy({it.position.x},{it.position.y})).map { it to random.nextInt() }
                .filter { (t,_) -> t.aerialDistanceTo(core) in 8..10 && EndlessRules.sector(t) == m.sector && t.isLand && !t.isImpassible() &&
                    !t.isCityCenter() && t.militaryUnit == null && t.civilianUnit == null }
                .sortedWith(compareBy({it.first.aerialDistanceTo(core)},{it.second},{it.first.position.x},{it.first.position.y}))
            val tile = candidates.firstOrNull()?.first ?: continue
            val unit = EndlessRules.invaders(game).units.placeUnitNearTile(tile.position,m.name) ?: continue
            if (unit.currentTile != tile) { unit.destroy(); EndlessRules.fault(game,"An invader spawned outside its announced approach."); return }
            m.unitId = unit.id; m.lifecycle = "Active"
            if (m.promotion.isNotEmpty()) {
                unit.promotions.addPromotion(m.promotion,isFree = true); unit.promotions.numberOfPromotions = 1
            }
            unit.promotions.XP = 0; capacity--
        }
    }

    private fun reinforcementType(game: GameInfo): String {
        val s = game.endless!!; val civ = EndlessRules.player(game)
        val maxTier = EndlessStagePlanner.tier(s.stageState!!.completedStages)
        val available = (0..2).map { role -> (0 until maxTier).map { EndlessStagePlanner.tiers[it][role] }.distinct().filter { name ->
            val u = game.ruleset.units.getValue(name)
            (u.requiredTech == null || civ.tech.isResearched(u.requiredTech!!)) && resourcesAvailable(game,name)
        }.lastOrNull() }
        val counts = (0..2).map { role -> civ.units.getCivUnits().count { it.name in EndlessStagePlanner.tiers.map { t -> t[role] } } }
        val role = (0..2).filter { available[it] != null }.minBy { counts[it] * if (it == 2) 2 else 1 }
        return available[role]!!
    }

    fun canRepair(game: GameInfo): Boolean = game.endless!!.stageState!!.pace == "casual" &&
        (EndlessRules.player(game).cities.any { it.health < it.getMaxHealth() } || EndlessRules.player(game).units.getCivUnits().any { it.isMilitary() && it.health < 100 })

    fun applyReward(game: GameInfo, choice: String) {
        val s = game.endless!!; val state = s.stageState!!; val offer = requireNotNull(s.offer)
        check(!s.ended && state.phase == "RewardPending" && offer.stage.toLong() == state.completedStages && s.claimedStages + 1 == offer.stage)
        when (choice) {
            "Gold" -> EndlessRules.player(game).addGold(offer.gold)
            "Repair" -> {
                check(canRepair(game))
                EndlessRules.player(game).cities.forEach { it.health = minOf(it.getMaxHealth(),it.health + it.getMaxHealth() * 30 / 100) }
                EndlessRules.player(game).units.getCivUnits().filter { it.isMilitary() }.forEach { it.health = minOf(100,it.health + 25) }
            }
            "Reinforcement" -> {
                check(s.reinforcement == null)
                s.reinforcement = EndlessReinforcement().apply { unit = offer.unit; expiresRound = EndlessCounter.add(s.round,3); fallbackGold = offer.gold }
                retryReinforcement(game)
            }
            else -> error("Unknown reward")
        }
        s.claimedStages = offer.stage; s.offer = null; state.phase = "Rest"
        prepareRound(game)
    }

    private fun resourcesAvailable(game: GameInfo, name: String) = EndlessRules.player(game).let { civ ->
        game.ruleset.units.getValue(name).getResourceRequirementsPerTurn(civ.state).all { (r,n) -> (civ.getCivResourcesByName()[r] ?: 0) >= n }
    }

    private fun retryReinforcement(game: GameInfo) {
        val s = game.endless!!; val pending = s.reinforcement ?: return
        val civ = EndlessRules.player(game); val core = game.tileMap[s.stageState!!.corePosition!!]
        val tile = if (!resourcesAvailable(game,pending.unit)) null else core.getTilesInDistance(3).filter {
            it.getOwner() == civ && it.isLand && !it.isImpassible() && it.militaryUnit == null && it.civilianUnit == null
        }.sortedWith(compareBy({it.aerialDistanceTo(core)},{it.position.x},{it.position.y})).firstOrNull()
        if (tile != null) {
            val unit = civ.units.placeUnitNearTile(tile.position,pending.unit) ?: return
            check(unit.currentTile == tile); unit.currentMovement = 0f; unit.attacksThisTurn = 1; s.reinforcement = null
        } else if (s.round >= pending.expiresRound) { civ.addGold(pending.fallbackGold); s.reinforcement = null }
    }

    private val directions = listOf("West","Southwest","Southeast","East","Northeast","Northwest")
    private fun notify(game: GameInfo, text: String) = EndlessRules.player(game).addNotification(text,NotificationCategory.War)
}
