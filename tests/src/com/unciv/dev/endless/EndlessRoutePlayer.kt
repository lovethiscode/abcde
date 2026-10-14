package com.unciv.dev.endless

import com.badlogic.gdx.Gdx
import com.unciv.UncivGame
import com.unciv.logic.GameInfo
import com.unciv.logic.PlayerDecisionControl
import com.unciv.logic.battle.Battle
import com.unciv.logic.battle.BattleDamage
import com.unciv.logic.battle.CityCombatant
import com.unciv.logic.battle.MapUnitCombatant
import com.unciv.logic.battle.TargetHelper
import com.unciv.logic.endless.EndlessCatalog
import com.unciv.logic.endless.EndlessRules
import com.unciv.logic.endless.EndlessStore
import com.unciv.logic.files.FileConversions
import com.unciv.logic.files.UncivFiles
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.logic.map.mapunit.actions.UnitActionsUpgrade
import java.io.File

/** Legal orders shared by JVM and simulator acceptance; never packaged in the normal app. */
object EndlessRoutePlayer {
    suspend fun play(initial: GameInfo, directory: File, route: String, limit: Int, rewardOverride: String?, holdLast: Boolean,
                     stopAfterStage: Long? = null, afterTurn: suspend (GameInfo) -> Unit = {}) {
        check(!directory.exists()) { "Keep prior evidence; choose a new output directory" }
        directory.mkdirs()
        val strategy = route.substringBefore(':')
        val allowExpansion = !route.endsWith(":single")
        var game = initial
        val files = UncivFiles(Gdx.files, File(directory, "local").path)
        val store = EndlessStore(files)
        store.start(game, null)
        fun snapshot(name: String) = FileConversions.writeJson(Gdx.files.absolute(File(directory, "$name.json").path), game, true)
        snapshot("initial")
        afterTurn(game)
        val log = File(directory, "actions.tsv").bufferedWriter()
        val turns = File(directory, "turns.tsv").bufferedWriter()
        fun record(action: String) { log.appendLine("${game.endless!!.round}\t$action"); log.flush() }
        turns.appendLine("round\tcompleted\twave\tcleared\tkills\tcityHP\tgold\tincome\tunits\tenemies\tpending\tturnMs\tsaveMs\ttechnologies\tproduction")
        val research = when (strategy) {
            "economy" -> listOf("Writing", "Masonry", "Iron Working", "The Wheel", "Mathematics", "Currency", "Construction", "Metal Casting", "Engineering", "Machinery", "Steel", "Horseback Riding", "Chivalry", "Physics")
            "mobile" -> listOf("Masonry", "The Wheel", "Horseback Riding", "Writing", "Mathematics", "Currency", "Chivalry", "Construction", "Engineering", "Machinery", "Iron Working", "Metal Casting", "Steel", "Physics")
            else -> listOf("Masonry", "Construction", "Writing", "Iron Working", "The Wheel", "Engineering", "Machinery", "Mathematics", "Currency", "Metal Casting", "Steel", "Horseback Riding", "Chivalry", "Physics")
        }
        val buildingOrder = when (strategy) {
            "economy" -> listOf("Library", "Water Mill", "Market", "Workshop", "Walls", "Aqueduct", "Barracks", "Castle")
            "mobile" -> listOf("Stable", "Barracks", "Walls", "Library", "Water Mill", "Market", "Workshop", "Castle", "Aqueduct")
            else -> listOf("Walls", "Barracks", "Library", "Water Mill", "Aqueduct", "Workshop", "Castle", "Market")
        }
        var minimumHealth = 200
        var maxCities = EndlessRules.player(game).cities.size
        var firstReward = true
        val start = System.nanoTime()
        try {
            record("identity\t${game.gameId}\t${game.endless!!.attemptId}\t${game.endless!!.seed}\t${game.endless!!.fingerprint}\t$route")
            while (!game.endless!!.ended && game.endless!!.round <= limit &&
                (stopAfterStage == null || game.endless!!.stageState!!.completedStages < stopAfterStage)) {
                UncivGame.Current.gameInfo = game
                var player = EndlessRules.player(game)
                PlayerDecisionControl.acquire(game, player.civID)
                val state = game.endless!!
                val city = EndlessRules.core(game)!!
                if (city.getCityFocus() != com.unciv.logic.city.CityFocus.ProductionGrowthFocus) {
                    city.setCityFocus(com.unciv.logic.city.CityFocus.ProductionGrowthFocus)
                    city.reassignPopulation(); record("city-focus\tProductionGrowthFocus")
                }
                minimumHealth = minOf(minimumHealth, city.health)
                maxCities = maxOf(maxCities, player.cities.size)
                if (state.offer != null) {
                    store.save(game)
                    snapshot("stage-${state.offer!!.stage}")
                    val damaged = player.units.getCivUnits().count { it.isMilitary() && it.health < 65 }
                    val choice = if (firstReward && rewardOverride != null && rewardOverride != "-") rewardOverride
                        else if (state.stageState?.pace != "wild" && (city.health < CityCombatant(city).getMaxHealth() * .7 || damaged >= 3)) "Repair"
                        else if (state.reinforcement == null && player.units.getCivUnits().count { it.isMilitary() } < 7 && player.gold >= 160) "Reinforcement" else "Gold"
                    firstReward = false
                    record("reward\t${state.offer!!.stage}\t$choice\t${state.offer!!.unit}")
                    val awardedStage = state.offer!!.stage
                    game = store.claim(game, choice)
                    snapshot("stage-$awardedStage-after-$choice")
                    afterTurn(game)
                    continue
                }
                if (player.tech.techsToResearch.isEmpty()) research.firstOrNull { player.tech.canBeResearched(it) }?.let {
                    player.tech.techsToResearch.add(it); record("research\t$it")
                }
                fun role(u: MapUnit) = when (u.name) {
                    "Spearman", "Swordsman", "Longswordsman" -> 0
                    "Archer", "Composite Bowman", "Crossbowman" -> 1
                    "Horseman", "Knight" -> 2
                    else -> 3
                }
                val military = player.units.getCivUnits().filter { it.isMilitary() }.toList()
                val targetCounts = if (strategy == "mobile") listOf(3, 5, 3) else if (strategy == "ranged") listOf(4, 8, 0) else listOf(4, 6, 1)
                val counts = (0..2).map { index -> military.count { role(it) == index } }
                val neededRole = (0..2).filter { targetCounts[it] > 0 && counts[it] < targetCounts[it] }
                    .minByOrNull { counts[it].toFloat() / targetCounts[it] }
                val chains = listOf(listOf("Longswordsman", "Swordsman", "Spearman"), listOf("Crossbowman", "Composite Bowman", "Archer"), listOf("Knight", "Horseman"))
                val unitToBuild = neededRole?.let { index -> chains[index].firstOrNull { game.ruleset.units.getValue(it).isBuildable(city.cityConstructions) } }
                    ?: "Archer".takeIf { military.size < 7 }
                val building = buildingOrder.firstOrNull { !city.cityConstructions.isBuilt(it) && game.ruleset.buildings.getValue(it).isBuildable(city.cityConstructions) }
                val readyArmy = if (strategy == "economy") 8 else 10
                val earlyBuilding = listOf("Walls").firstOrNull { !city.cityConstructions.isBuilt(it) &&
                    game.ruleset.buildings.getValue(it).isBuildable(city.cityConstructions) && (it == "Walls" || military.size >= 7) }
                val expansion = strategy == "economy" && allowExpansion && com.unciv.logic.endless.EndlessStageRules.canTrainSettler(player) &&
                    military.size >= 6 && player.units.getCivUnits().none { it.name == "Settler" } &&
                    game.ruleset.units.getValue("Settler").isBuildable(city.cityConstructions)
                val name = earlyBuilding ?: if (expansion) "Settler" else if (military.size < readyArmy || (state.activeCount > military.size && military.size < 12)) unitToBuild
                    else building ?: unitToBuild
                if (strategy == "economy" && allowExpansion && com.unciv.logic.endless.EndlessStageRules.canTrainSettler(player) &&
                    military.size >= 7 && player.units.getCivUnits().none { it.name == "Settler" } && city.population.population >= 2 && player.gold >= 450 &&
                    city.cityConstructions.purchaseConstruction("Settler",-1,false)) record("purchase\tSettler")
                for (side in player.cities.filter { it.id != state.coreId }) {
                    if (side.cityConstructions.isQueueEmptyOrIdle()) {
                        val next = (listOf("Walls","Granary","Monument","Library") + buildingOrder).firstOrNull {
                            !side.cityConstructions.isBuilt(it) && game.ruleset.buildings.getValue(it).isBuildable(side.cityConstructions)
                        } ?: "Archer"
                        val construction = game.ruleset.buildings[next] ?: game.ruleset.units.getValue(next)
                        if (construction.isBuildable(side.cityConstructions)) { side.cityConstructions.addToQueue(construction); record("side-production\t${side.id}\t$next") }
                    }
                }
                if ((city.cityConstructions.isQueueEmptyOrIdle() || city.cityConstructions.currentConstructionName() == "Gold") && name != null) {
                    val construction = game.ruleset.units[name] ?: game.ruleset.buildings.getValue(name)
                    city.cityConstructions.addToQueue(construction, addToTop = true)
                    record("production\t$name")
                }
                // A reserve pays upkeep and ordinary upgrades; emergency purchases use the same purchase gate as the UI.
                if (unitToBuild != null && (state.activeCount > military.size || military.size < readyArmy) && player.gold > 240) {
                    if (city.cityConstructions.purchaseConstruction(unitToBuild, -1, false)) record("purchase\t$unitToBuild")
                }
                for (unit in military.sortedBy { role(it) }) {
                    val upgrade = UnitActionsUpgrade.getUpgradeActions(unit).firstOrNull { it.action != null }
                    if (upgrade != null && player.gold > 80) {
                        upgrade.action!!.invoke(); record("upgrade\t${unit.id}\t${upgrade.title.replace('\n', ' ')}")
                    }
                }
                val visible = player.viewableTiles.flatMap { it.getUnits().toList() }.filter { it.civ != player && it.isMilitary() }
                val garrison = military.filter { role(it) == 0 }.minWithOrNull(compareBy({ it.currentTile.aerialDistanceTo(city.getCenterTile()) }, { -it.health }))?.id
                fun exposure(tile: Tile): Int = visible.filter { !it.isDestroyed && it.currentTile in player.viewableTiles }.sumOf {
                    when {
                        it.baseUnit.isRanged() && it.currentTile.aerialDistanceTo(tile) <= 2 -> 20
                        it.currentTile.aerialDistanceTo(tile) <= 1 -> 70
                        it.baseUnit.isMelee() && it.currentTile.aerialDistanceTo(tile) <= 2 -> 30
                        else -> 0
                    }
                }
                fun move(unit: MapUnit, destination: Tile) {
                    if (destination == unit.currentTile || !unit.hasMovement()) return
                    val before = unit.currentTile.position
                    unit.movement.headTowards(destination)
                    if (unit.currentTile.position != before) record("move\t${unit.id}\t$before\t${unit.currentTile.position}")
                }
                fun attack(unit: MapUnit): Boolean {
                    if (!unit.canAttack() || unit.id == garrison && state.activeCount > 1) return false
                    val cleanup = state.activeCount <= maxOf(2, military.size / 3) && state.pendingCount == 0
                    if (holdLast && state.activeCount == 1 && state.pendingCount == 0 &&
                        state.stageState?.let { state.waves.all { it.announced } && state.round < it.deadline - 2 } != false) return false
                    val attacker = MapUnitCombatant(unit)
                    val choice = TargetHelper.getAttackableEnemies(unit, unit.movement.getDistanceToTiles()).mapNotNull { target ->
                        if (target.tileToAttackFrom.aerialDistanceTo(city.getCenterTile()) > if (cleanup) 6 else if (strategy == "mobile") 3 else 2) return@mapNotNull null
                        if (unit.id == garrison && !cleanup && target.tileToAttackFrom != city.getCenterTile()) return@mapNotNull null
                        val defender = Battle.getMapCombatantOfTile(target.tileToAttack) ?: return@mapNotNull null
                        check(target.tileToAttack in player.viewableTiles)
                        val dealt = BattleDamage.calculateDamageToDefender(attacker, defender, target.tileToAttackFrom)
                        val taken = BattleDamage.calculateDamageToAttacker(attacker, defender, target.tileToAttackFrom)
                        val safeCavalryStrike = role(unit) == 2 && taken <= 25 && unit.health >= 75 && exposure(target.tileToAttackFrom) < 40
                        if (!unit.baseUnit.isRanged() && dealt < defender.getHealth() && !safeCavalryStrike) return@mapNotNull null
                        if (taken + 12 >= unit.health || (!unit.baseUnit.isRanged() && unit.health < 55 && dealt < defender.getHealth())) return@mapNotNull null
                        val importance = if (defender.getName() in setOf("Catapult", "Trebuchet")) 40 else 0
                        target to (dealt + importance + (if (dealt >= defender.getHealth()) 80 else 0) - taken * 2 - exposure(target.tileToAttackFrom) * (if (unit.baseUnit.isRanged()) 2 else 1))
                    }.maxByOrNull { it.second }?.first ?: return false
                    record("attack\t${unit.id}\t${choice.tileToAttack.position}\t${Battle.getMapCombatantOfTile(choice.tileToAttack)?.getName()}")
                    Battle.moveAndAttack(attacker, choice)
                    if (!unit.isDestroyed && role(unit) == 2 && unit.hasMovement() && !EndlessRules.blocked(game)) {
                        val safe = unit.movement.getDistanceToTiles().keys.filter { it == unit.currentTile || unit.movement.canMoveTo(it) }
                            .minByOrNull { exposure(it) * 4 + it.aerialDistanceTo(city.getCenterTile()) * 10 }
                        if (safe != null) move(unit,safe)
                    }
                    return true
                }
                if (state.waveNumber >= 5 && state.activeCount == 1 && !File(directory, "clear-branch.json").exists()) snapshot("clear-branch")
                for (battery in player.cities) if (!battery.attackedThisTurn && !(holdLast && state.activeCount == 1 && state.pendingCount == 0 &&
                        state.stageState?.let { state.waves.all { it.announced } && state.round < it.deadline - 2 } != false)) {
                    val tile = TargetHelper.getBombardableTiles(battery).minByOrNull { it.militaryUnit?.health ?: 101 }
                    if (tile != null) {
                        record("bombard\t${battery.id}\t${tile.position}")
                        Battle.attack(CityCombatant(battery), Battle.getMapCombatantOfTile(tile)!!)
                    }
                }
                for (unit in player.units.getCivUnits().toList().sortedWith(compareBy({ !it.baseUnit.isRanged() }, { it.id }))) {
                    if (EndlessRules.blocked(game)) break
                    if (unit.isDestroyed) continue
                    if (unit.isCivilian()) {
                        if (unit.name == "Settler") {
                            if (com.unciv.logic.endless.EndlessStageRules.canFound(player,unit.currentTile,unit)) {
                                val founded = player.addCity(unit.currentTile.position,unit)
                                record("found\t${founded.id}\t${founded.location}"); continue
                            }
                            val target = city.getCenterTile().getTilesAtDistance(4).filter { it.canBeSettled(player) && unit.movement.canMoveTo(it) }
                                .sortedWith(compareBy({it.aerialDistanceTo(unit.currentTile)},{it.position.x},{it.position.y})).firstOrNull { unit.movement.canReach(it) }
                            if (target != null) move(unit,target)
                            if (com.unciv.logic.endless.EndlessStageRules.canFound(player,unit.currentTile,unit)) {
                                val founded = player.addCity(unit.currentTile.position,unit)
                                record("found\t${founded.id}\t${founded.location}")
                            }
                            continue
                        }
                        // Repair and improve through ordinary worker automation.
                        unit.automated = true
                        unit.doAction()
                        record("worker\t${unit.id}\t${unit.currentTile.position}\t${unit.currentTile.improvementInProgress}")
                        continue
                    }
                    while (unit.promotions.canBePromoted()) {
                        val available = unit.promotions.getAvailablePromotions().toList()
                        val promotion = (if (unit.health < 60) available.firstOrNull { it.name == "Heal Instantly" } else null)
                            ?: available.firstOrNull { it.name != "Heal Instantly" } ?: break
                        unit.promotions.addPromotion(promotion.name); record("promote\t${unit.id}\t${promotion.name}")
                    }
                    if (attack(unit)) continue
                    if (unit.id == garrison) {
                        if (unit.currentTile != city.getCenterTile() && unit.movement.canMoveTo(city.getCenterTile()) && unit.movement.canReach(city.getCenterTile()))
                            move(unit, city.getCenterTile())
                    } else if (unit.health < 65) {
                        val safe = unit.movement.getDistanceToTiles().keys.filter { it == unit.currentTile || unit.movement.canMoveTo(it) }
                            .minByOrNull { exposure(it) * 4 + it.aerialDistanceTo(city.getCenterTile()) * 10 }
                        if (safe != null) move(unit, safe)
                    } else {
                        val target = visible.filter { !it.isDestroyed && it.currentTile in player.viewableTiles }.minByOrNull { it.currentTile.aerialDistanceTo(city.getCenterTile()) }?.currentTile
                        val desired = if (unit.baseUnit.isRanged()) 2 else 1
                        val goals = city.getCenterTile().getTilesInDistance(if (state.activeCount <= maxOf(2, military.size / 3) && state.pendingCount == 0) 6 else if (strategy == "mobile") 3 else 2)
                            .filter { it != city.getCenterTile() && (it == unit.currentTile || unit.movement.canMoveTo(it)) }
                            .sortedBy { tile ->
                                val enemyDistance = if (target == null) 0 else kotlin.math.abs(tile.aerialDistanceTo(target) - desired) * 12
                                enemyDistance + exposure(tile) / if (unit.baseUnit.isRanged()) 1 else 5 +
                                    tile.aerialDistanceTo(unit.currentTile) + kotlin.math.abs(tile.aerialDistanceTo(city.getCenterTile()) - desired) * 5
                            }
                        val goal = goals.firstOrNull { it == unit.currentTile || unit.movement.canReach(it) }
                        if (goal != null) move(unit, goal)
                    }
                    if (!unit.isDestroyed && !EndlessRules.blocked(game)) attack(unit)
                    if (!unit.isDestroyed && unit.hasMovement()) unit.fortifyIfCan()
                }
                if (EndlessRules.blocked(game)) continue
                record("end-turn\t${state.activeCount}\t${player.gold}\t${city.health}")
                val turnStart = System.nanoTime()
                game.nextTurn()
                val turnMs = (System.nanoTime() - turnStart) / 1e6
                val saveStart = System.nanoTime(); store.save(game)
                val saveMs = (System.nanoTime() - saveStart) / 1e6
                val s = game.endless!!
                turns.appendLine("${s.round}\t${s.completedRounds}\t${s.waveNumber}\t${s.clearedWaves}\t${s.kills}\t${city.health}\t${player.gold}\t${player.stats.statsForNextTurn.gold}\t${player.units.getCivUnits().count()}\t${s.activeCount}\t${s.pendingCount}\t$turnMs\t$saveMs\t${player.tech.techsResearched.joinToString(",")}\t${city.cityConstructions.currentConstructionName()}")
                turns.flush()
                if (s.round % 25 == 0 || s.ended) println("${s.seed}/$route R=${s.round} C=${s.completedRounds} wave=${s.waveNumber} cleared=${s.clearedWaves} city=${city.health} army=${player.units.getCivUnits().count()} gold=${player.gold} status=${s.status}")
                // A real save/reload midway exercises serialization without changing the position.
                if (s.round == 25) { game = store.resume(); record("resume\t${game.endless!!.attemptId}") }
                afterTurn(game)
            }
            snapshot("final")
            val state = game.endless!!
            File(directory, "result.json").writeText(com.unciv.json.json().toJson(linkedMapOf(
                "seed" to state.seed, "route" to route, "status" to state.status, "rounds" to state.completedRounds,
                "waves" to state.clearedWaves, "stages" to (state.stageState?.completedStages ?: state.claimedStages.toLong()), "kills" to state.kills,
                "pace" to (state.stageState?.pace ?: "legacy"), "endReason" to (state.stageState?.endReason ?: ""),
                "partialWaves" to (if (state.stageState?.phase == "Combat") state.waves.count { it.cleared } else 0),
                "combatRounds" to (state.stageState?.combatRounds ?: 0), "cities" to EndlessRules.player(game).cities.size,
                "maxCities" to maxCities, "minimumCityHealth" to minimumHealth, "seconds" to (System.nanoTime() - start) / 1e9,
                "armyProduction" to EndlessRules.player(game).units.getCivUnits().filter { it.isMilitary() }.sumOf { it.baseUnit.cost },
                "gold" to EndlessRules.player(game).gold, "cityHealth" to EndlessRules.core(game)?.health,
                "holdLast" to holdLast, "rewardOverride" to (rewardOverride ?: ""),
                "evidenceType" to "legal-headless-route", "fingerprint" to state.fingerprint)))
        } finally { log.close(); turns.close(); PlayerDecisionControl.release() }
    }
}
