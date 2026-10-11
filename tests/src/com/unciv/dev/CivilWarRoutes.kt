package com.unciv.dev

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.backends.headless.HeadlessFiles
import com.unciv.UncivGame
import com.unciv.logic.GameInfo
import com.unciv.logic.PlayerDecisionControl
import com.unciv.logic.achievements.AchievementTracker
import com.unciv.logic.battle.Battle
import com.unciv.logic.battle.BattleDamage
import com.unciv.logic.battle.MapUnitCombatant
import com.unciv.logic.battle.TargetHelper
import com.unciv.logic.campaign.CampaignCatalog
import com.unciv.logic.campaign.CampaignStore
import com.unciv.logic.campaign.CivilWarCampaign
import com.unciv.logic.civilization.AlertType
import com.unciv.logic.files.FileConversions
import com.unciv.logic.files.UncivFiles
import com.unciv.logic.map.HexMath
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.testing.TestGame
import java.io.File

/** Headless player orders through normal actions. Player identity and fog stay unchanged.
 * Constructed scoring/AI fixtures live in tests, never in this route driver.
 */
object CivilWarRoutes {
    @JvmStatic fun main(args: Array<String>) {
        Gdx.files = HeadlessFiles()
        TestGame()
        val root = File(requireNotNull(args.firstOrNull()) { "Evidence directory required" }).absoluteFile
        val groups = args.getOrNull(1)?.toInt() ?: 1
        val routes = args.getOrNull(2)?.split(',') ?: listOf("three")
        val variant = args.getOrNull(3) ?: "front"
        root.mkdirs()
        try {
            var allPassed = true
            for (group in ((args.getOrNull(5)?.toInt() ?: 1) - 1) until groups) {
                val groupDir = File(root, "group-${group + 1}").apply { mkdirs() }
                val initialFile = Gdx.files.absolute(File(groupDir, "initial.json").path)
                var initial = if (initialFile.exists()) requireNotNull(FileConversions.readJson(initialFile, GameInfo::class.java)).also { it.setTransients() }
                    else CampaignCatalog.newAttempt(CivilWarCampaign.id).also { FileConversions.writeJson(initialFile, it, true) }
                if (args.getOrNull(4) == "refresh-content") {
                    check(initial.turns == 0 && initial.campaign!!.completedRounds == 0)
                    val old = initial
                    val currentFile = Gdx.files.absolute(File(groupDir, "current-initial.json").path)
                    initial = if (currentFile.exists()) requireNotNull(FileConversions.readJson(currentFile, GameInfo::class.java)).also {
                        it.setTransients()
                        check(it.gameId == old.gameId && it.campaign!!.attemptId == old.campaign!!.attemptId && it.campaign!!.seed == old.campaign!!.seed)
                    } else CampaignCatalog.newAttempt(CivilWarCampaign.id).also {
                        it.gameId = old.gameId
                        it.campaign!!.attemptId = old.campaign!!.attemptId
                        it.campaign!!.seed = old.campaign!!.seed
                        it.tileMap.mapParameters.seed = old.campaign!!.seed
                        FileConversions.writeJson(currentFile, it, true)
                    }
                }
                for (specification in routes) {
                    val route = specification.substringBefore(':')
                    val strategy = specification.substringAfter(':', variant)
                    val g = initial.clone().also { it.setTransients() }
                    CampaignCatalog.requireCompatible(g)
                    if (!play(g, File(groupDir, "$route-$strategy"), route, strategy)) allPassed = false
                }
            }
            check(allPassed) { "One or more routes missed their required result; retained all evidence" }
        } finally { PlayerDecisionControl.release(); com.unciv.utils.Concurrency.stopThreadPools() }
    }

    private fun play(g: GameInfo, directory: File, route: String, variant: String): Boolean {
        check(!directory.exists()) { "Keep prior route evidence; choose a new directory" }
        directory.mkdirs()
        val files = UncivFiles(Gdx.files, File(directory, "local").path)
        check(files.campaignProgressSync === com.unciv.logic.campaign.CampaignProgressSync.None)
        val store = CampaignStore(files)
        store.start(g, null)
        UncivGame.Current.gameInfo = g
        val player = g.getCurrentPlayerCivilization()
        check(player.isHuman() && player.civName == "Union")
        PlayerDecisionControl.acquire(g, player.civID)
        val log = File(directory, "actions.tsv").bufferedWriter()
        val economy = File(directory, "turns.tsv").bufferedWriter()
        fun record(action: String) { log.appendLine("${g.campaign!!.completedRounds + 1}\t$action"); log.flush() }
        val healing = hashSetOf<Int>()
        val waypoints = hashMapOf<Int, Int>()
        var alexandriaLost = false
        var alexandriaRecovered = false
        var lowestIncome = Float.POSITIVE_INFINITY
        var worstSupplyPenalty = 0f
        var recoveryIncome = Float.NEGATIVE_INFINITY
        var economyRecoveryUntil = 0
        var transportCompleted = false
        val originalTechCosts = CivilWarCampaign.technologies.associateWith { g.ruleset.technologies.getValue(it).cost }
        if (variant.startsWith("fixed-")) CivilWarCampaign.technologies.forEach { g.ruleset.technologies.getValue(it).cost = 1_000_000 }
        fun stopped() = g.campaign!!.let { it.resultStars > 0 || it.failed }
        fun snapshot() {
            lowestIncome = minOf(lowestIncome, player.stats.statsForNextTurn.gold)
            worstSupplyPenalty = minOf(worstSupplyPenalty, player.stats.getUnitSupplyProductionPenalty())
            for (c in g.civilizations) {
                economy.appendLine("${g.campaign!!.completedRounds + 1}\t${c.civName}\t${c.gold}\t${c.stats.statsForNextTurn.gold}\t${c.getHappiness()}\t${c.units.getCivUnits().count()}\t${c.stats.getUnitSupply()}\t${c.stats.getUnitSupplyProductionPenalty()}\t${c.tech.techsResearched.intersect(CivilWarCampaign.technologies)}\t" +
                    c.cities.joinToString(";") { "${it.id}:${it.population.population}:${it.cityStats.currentCityStats.production}:${it.cityConstructions.currentConstructionName()}" })
            }
            economy.flush()
            FileConversions.writeJson(Gdx.files.absolute(File(directory, "turn-${g.campaign!!.completedRounds.toString().padStart(2, '0')}.json").path), g, true)
        }
        fun manageCaptures() {
            for (alert in player.popupAlerts.toList()) {
                if (alert.type == AlertType.CityConquered) {
                    val city = g.getCities().first { it.id == alert.value }
                    city.puppetCity(player)
                    if (city.id != "cw-richmond") city.annexCity()
                    record("occupy\t${city.id}")
                }
                player.popupAlerts.remove(alert)
            }
            AchievementTracker.settle(g)
        }
        fun visibleEnemies() = player.viewableTiles.asSequence().mapNotNull { it.militaryUnit }
            .filter { it.civ != player && player.isAtWarWith(it.civ) }.toList()
        fun exposure(u: MapUnit, tile: Tile): Int {
            val enemies = visibleEnemies()
            val support = player.units.getCivUnits().count { it != u && it.name == "Rifleman" && it.health >= 60 && it.currentTile.aerialDistanceTo(tile) <= 1 }
            val attacks = enemies.sumOf { e ->
                when (e.currentTile.aerialDistanceTo(tile)) {
                    0, 1 -> 32
                    2 -> if (e.name == "Cannon") 22 else 18
                    3 -> if (e.name == "Cavalry") 12 else 0
                    else -> 0
                }
            } + g.getCities().count { it.civ != player && it.getCenterTile().isVisible(player) && it.getCenterTile().aerialDistanceTo(tile) <= 2 } * 18
            return (attacks - support * 12).coerceAtLeast(0)
        }
        fun move(u: MapUnit, destination: Tile): Boolean {
            if (u.currentTile == destination || !u.hasMovement() || !u.movement.canReach(destination)) return false
            val from = u.currentTile
            u.movement.headTowards(destination)
            if (u.currentTile == from) return false
            record("move\t${u.id}\t${from.position}\t${u.currentTile.position}")
            manageCaptures()
            return true
        }
        fun attack(u: MapUnit, allowCapital: Boolean): Boolean {
            if (!u.hasMovement() || u.attacksThisTurn > 0) return false
            val attacker = MapUnitCombatant(u)
            val choice = TargetHelper.getAttackableEnemies(u, u.movement.getDistanceToTiles()).mapNotNull { target ->
                val defender = Battle.getMapCombatantOfTile(target.tileToAttack) ?: return@mapNotNull null
                if (!target.tileToAttack.isVisible(player)) error("Attack used hidden unit information")
                if (target.tileToAttack.getCity()?.id == "cw-richmond" && !allowCapital) return@mapNotNull null
                val dealt = BattleDamage.calculateDamageToDefender(attacker, defender, target.tileToAttackFrom)
                val taken = BattleDamage.calculateDamageToAttacker(attacker, defender, target.tileToAttackFrom)
                if (taken + 12 >= u.health) return@mapNotNull null
                if (target.tileToAttack.isCityCenter() && u.baseUnit.isMelee() && defender.getHealth() > dealt + 10) return@mapNotNull null
                val value = if (target.tileToAttack.isCityCenter()) {
                    if (u.baseUnit.isMelee() && defender.getHealth() <= dealt) 500 else dealt - 10 + (if (variant.contains("siege") && u.name == "Cannon") 100 else 0)
                } else dealt + (if (dealt >= defender.getHealth()) 90 else 25) - taken * 2
                target to value
            }.maxByOrNull { it.second }?.first ?: return false
            val health = u.health
            record("attack\t${u.id}\t${choice.tileToAttack.position}\t${Battle.getMapCombatantOfTile(choice.tileToAttack)?.getName()}")
            Battle.moveAndAttack(attacker, choice)
            manageCaptures()
            if (!u.isDestroyed && !stopped() && u.name == "Cavalry" && u.hasMovement() && u.health < if (variant.contains("withdraw")) 80 else 55) {
                val safe = u.movement.getDistanceToTiles().keys.filter { it == u.currentTile || u.movement.canMoveTo(it) }
                    .minByOrNull { exposure(u, it) * 3 + it.aerialDistanceTo(u.currentTile) }
                if (safe != null) move(u, safe)
            }
            return u.health != health || u.attacksThisTurn > 0 || u.isDestroyed
        }
        try {
            record("identity\t${g.gameId}\t${g.campaign!!.attemptId}\t${g.campaign!!.seed}\t${g.campaign!!.rulesVersion}\t${g.campaign!!.definition.scoringVersion}")
            if (variant.startsWith("fixed-")) record("prototype-research-freeze\tboth factions\tcost=1000000\treapply this test-only override when replaying")
            economy.appendLine("round\tfaction\tgold\tincome\thappiness\tunits\tsupply\tpenalty\ttechnologies\tcities")
            while (!stopped() && g.campaign!!.completedRounds < 50) {
                val round = g.campaign!!.completedRounds + 1
                val alexandria = g.getCities().first { it.id == "cw-alexandria" }
                if (alexandria.civ != player) alexandriaLost = true
                if (alexandriaLost && alexandria.civ == player) alexandriaRecovered = true
                snapshot()
                val repairedRoads = listOf(CivilWarCampaign.harpersFerry, HexMath.getTileCoordsFromColumnRow(-10, 10)).map { g.tileMap[it] }
                transportCompleted = player.tech.isResearched("Military Transport") && repairedRoads.all { !it.roadIsPillaged } &&
                    repairedRoads.any { it.roadStatus == com.unciv.logic.map.tile.RoadStatus.Railroad }
                if (route == "economy" && alexandriaRecovered && lowestIncome < 0 && recoveryIncome > 0 ||
                    route == "transport" && transportCompleted) {
                    record("partial-objective-complete\t$route\tsettlement=false")
                    break
                }
                if (variant.endsWith("west") || variant == "reclaim") {
                    player.units.getCivUnits().filter { it.name == "Great General" }.sortedBy { it.id }.drop(2).toList().forEach {
                        record("disband-extra-general\t${it.id}"); it.disband()
                    }
                }
                val firstTech = when { variant.endsWith("medicine") || variant.contains("battery") -> "Field Medicine"; variant.startsWith("transport") -> "Military Transport"; else -> "Artillery Improvement" }
                if (player.tech.techsToResearch.isEmpty()) {
                    (listOf(firstTech) + CivilWarCampaign.technologies).firstOrNull { player.tech.canBeResearched(it) }?.let {
                        player.tech.techsToResearch.add(it); record("research\t$it")
                    }
                }
                for (city in player.cities) {
                    if (city.isInResistance() || city.isPuppet) continue
                    if (variant == "reclaim" && player.stats.statsForNextTurn.gold < 0) economyRecoveryUntil = round + 3
                    val recovering = variant == "reclaim" && city.id == "cw-baltimore" && round <= economyRecoveryUntil
                    val name = if (route == "stress" && round >= 41 || recovering) "Gold"
                    else if (variant.endsWith("west") && city.id == "cw-alexandria" && !city.cityConstructions.isBuilt("Walls")) "Walls"
                    else if (city.id in CivilWarCampaign.recruitmentCities && player.units.getCivUnits().count { it.isMilitary() } < (if (route == "stress") 80 else 20) && player.gold > 60) {
                        if (player.units.getCivUnits().count { it.name == "Cannon" } < (if (variant.contains("battery")) 6 else 4)) "Cannon" else "Rifleman"
                    } else if (route == "stress" && round < 41) "Worker" else "Gold"
                    val construction = g.ruleset.units[name] ?: g.ruleset.buildings[name] ?: com.unciv.models.ruleset.PerpetualConstruction.Gold
                    if (recovering && city.cityConstructions.currentConstructionName() != "Gold") {
                        record("economy-deficit\tincome=${player.stats.statsForNextTurn.gold}")
                        city.cityConstructions.editQueue { clear(); add("Gold") }
                        city.cityStats.update(); player.updateStatsForNextTurn()
                        recoveryIncome = player.stats.statsForNextTurn.gold
                        record("economy-gold-production\t${city.id}\tincome=$recoveryIncome")
                    }
                    if (route == "stress" && round == 41) city.cityConstructions.editQueue { clear(); add("Gold") }
                    if (city.cityConstructions.isQueueEmptyOrIdle() || city.cityConstructions.currentConstructionName() == "Gold" && name != "Gold") {
                        if (construction.isBuildable(city.cityConstructions)) {
                            city.cityConstructions.addToQueue(construction, addToTop = true)
                            record("production\t${city.id}\t$name")
                        }
                    }
                }
                if (route == "stress" && round == 41) {
                    record("recovery-before\tincome=${player.stats.statsForNextTurn.gold}")
                    player.units.getCivUnits().filter { it.isMilitary() && it.id != 5 }.sortedBy { it.health }.take(7).toList().forEach {
                        record("disband\t${it.id}"); it.disband()
                    }
                    player.cities.forEach { it.cityStats.update() }; player.updateStatsForNextTurn()
                    recoveryIncome = player.stats.statsForNextTurn.gold
                    record("recovery-after\tincome=$recoveryIncome")
                }
                // One-star branch deliberately keeps Richmond until round 31 and reduces its own army legally.
                if (route == "one" && round >= 31 && g.campaign!!.lostMilitary.size < 7) {
                    val extra = 7 - g.campaign!!.lostMilitary.size
                    player.units.getCivUnits().filter { it.name in CivilWarCampaign.militaryUnits && it.id !in setOf(5, 9) }
                        .sortedByDescending { it.currentTile.aerialDistanceTo(g.getCities().first { city -> city.id == "cw-richmond" }.getCenterTile()) }
                        .take(extra).toList().forEach { record("disband\t${it.id}"); it.disband() }
                }
                val allowCapital = route != "stress" && (route != "one" || round >= 31)
                val destinationId = (if (variant.contains("flank")) listOf("cw-manassas", "cw-richmond") else if (variant.endsWith("west")) listOf("cw-petersburg", "cw-richmond") else listOf("cw-manassas", "cw-fredericksburg", "cw-richmond"))
                    .firstOrNull { id -> player.cities.none { it.id == id } } ?: "cw-richmond"
                val capital = player.cities.first { it.id == "cw-washington" }.getCenterTile()
                val destination = if (variant == "reclaim" && !alexandriaLost) capital
                    else if (variant == "reclaim" && !alexandriaRecovered) alexandria.getCenterTile()
                    else g.getCities().first { it.id == destinationId }.getCenterTile()
                val units = player.units.getCivUnits().toList().sortedWith(compareBy({ if (it.name == "Cannon") 0 else if (it.isMilitary()) 1 else 2 }, { it.currentTile.aerialDistanceTo(destination) }))
                for (u in units) {
                    if (stopped()) break
                    if (u.isDestroyed || u.civ != player) continue
                    while (u.promotions.canBePromoted()) {
                        val available = u.promotions.getAvailablePromotions()
                        val promotion = (if (u.health <= 65) available.firstOrNull { it.name == "Heal Instantly" }
                            ?: available.firstOrNull() else available.firstOrNull { it.name != "Heal Instantly" }) ?: break
                        record("promotion\t${u.id}\t${promotion.name}"); u.promotions.addPromotion(promotion.name)
                    }
                    if (u.isCivilian()) {
                        if (u.id == 14 && variant.endsWith("west")) {
                            val roads = listOf(CivilWarCampaign.harpersFerry, HexMath.getTileCoordsFromColumnRow(-10, 10)).map { g.tileMap[it] }
                            val repair = roads.firstOrNull { it.roadIsPillaged }
                            if (repair != null) {
                                if (u.currentTile != repair) move(u, repair)
                                if (u.currentTile == repair) com.unciv.ui.screens.worldscreen.unit.actions.UnitActionsFromUniques.getRepairAction(u)?.let { action ->
                                    if (action.action != null) { record("worker-repair\t${u.id}\t${repair.position}\t${action.title}"); action.action!!.invoke() }
                                }
                            } else if (player.tech.isResearched("Military Transport")) {
                                val rail = g.ruleset.tileImprovements.getValue("Railroad")
                                if (u.hasMovement() && u.currentTile.improvementInProgress == null && u.canBuildImprovement(rail) &&
                                    u.currentTile.improvementFunctions.canBuildImprovement(rail, com.unciv.models.ruleset.unique.GameContext(civInfo = player, unit = u, tile = u.currentTile))) {
                                    record("worker-railroad\t${u.id}\t${u.currentTile.position}")
                                    u.currentTile.startWorkingOnImprovement(rail, player, u)
                                }
                            }
                            continue
                        }
                        if (u.name == "Great General") {
                            val escort = player.units.getCivUnits().filter { it.name == "Rifleman" && it.health > 60 }.minByOrNull { it.currentTile.aerialDistanceTo(destination) }
                            if (escort != null) move(u, escort.currentTile)
                        }
                        continue
                    }
                    val enemies = visibleEnemies()
                    val westernPhase = variant.endsWith("west") && (!variant.contains("flank") || player.cities.any { it.id == "cw-manassas" })
                    val westernDefense = westernPhase && u.id in (if (variant.contains("flank")) setOf(5, 6, 12) else setOf(3, 4, 5, 6, 9, 12))
                    val reserve = u.id == 5 || variant.contains("reserve") && u.id == 6 && round <= 5
                    val goal = if (westernDefense) capital
                        else if (reserve) capital else if (!allowCapital && destinationId == "cw-richmond" && (route == "stress" || variant.contains("rest") || variant.contains("withdraw")))
                        player.cities.first { it.id == "cw-fredericksburg" }.getCenterTile() else destination
                    if (reserve) {
                        if (u.name == "Cannon" && attack(u, allowCapital)) continue
                        if (u.currentTile != goal) move(u, goal)
                        if (u.hasMovement()) u.fortifyIfCan()
                        continue
                    }
                    if (u.health < 45) healing.add(u.id)
                    if (u.health >= 80) healing.remove(u.id)
                    if (u.id in healing) {
                        if (exposure(u, u.currentTile) == 0 && u.getHealAmountForCurrentTile() >= 10) {
                            if (u.hasMovement()) u.fortifyIfCan()
                            record("heal\t${u.id}\t${u.health}")
                            continue
                        }
                        val tiles = u.movement.getDistanceToTiles().keys.filter { it == u.currentTile || u.movement.canMoveTo(it) }
                        val safest = tiles.minByOrNull { t ->
                            exposure(u, t) * 3 + (if (t.getOwner() == player) -20 else 0) + t.aerialDistanceTo(u.currentTile)
                        }
                        if (safest != null && safest != u.currentTile) move(u, safest)
                        else record("heal\t${u.id}\t${u.health}")
                        continue
                    }
                    if (variant == "reclaim" && !alexandriaLost) {
                        if (u.currentTile.aerialDistanceTo(capital) > 1) move(u, capital)
                        if (u.hasMovement()) u.fortifyIfCan()
                        continue
                    }
                    if (variant == "lost-cannon" && u.id == 10 && round <= 5) {
                        destination.neighbors.filter { u.movement.canMoveTo(it) }.firstOrNull { u.movement.canReach(it) }?.let { move(u, it) }
                        continue
                    }
                    if (attack(u, allowCapital) || stopped() || u.isDestroyed) continue
                    if (variant.startsWith("delay") && round <= (if (variant.contains("five")) 5 else if (variant.contains("four")) 4 else 3) && !reserve) { record("delay\t${u.id}"); continue }
                    var target = goal
                    if (westernPhase && !reserve && !westernDefense) {
                        val coordinates = if (variant.contains("flank")) listOf(-10 to 1, -10 to -4, -4 to -9) else listOf(-10 to 11, -11 to 3, -10 to -4, -4 to -9)
                        var step = waypoints[u.id] ?: 0
                        if (step < coordinates.size) {
                            val waypoint = g.tileMap[HexMath.getTileCoordsFromColumnRow(coordinates[step].first, coordinates[step].second)]
                            if (u.currentTile.aerialDistanceTo(waypoint) <= 2) { step++; waypoints[u.id] = step }
                            if (step < coordinates.size) target = g.tileMap[HexMath.getTileCoordsFromColumnRow(coordinates[step].first, coordinates[step].second)]
                        }
                    }
                    // Stand off with cannon; infantry screens them until a legal city assault can succeed.
                    val desiredDistance = if (u.name == "Cannon" && target == destination) 2 else if (target == destination) 1 else 0
                    val clearShot = u.name != "Cannon" || target in g.tileMap.getViewableTiles(u.currentTile.position, 2, true)
                    if (u.currentTile.aerialDistanceTo(target) > desiredDistance || !clearShot) {
                        val options = target.getTilesInDistance(if (desiredDistance == 0) 1 else 2)
                            .filter { t -> u.movement.canMoveTo(t) && t.aerialDistanceTo(target) >= desiredDistance }
                            .filter { t -> u.name != "Cannon" || target in g.tileMap.getViewableTiles(t.position, 2, true) }
                            .sortedBy { t -> t.aerialDistanceTo(target) * 10 + t.aerialDistanceTo(u.currentTile) +
                                (if (u.name == "Cannon") enemies.count { it.currentTile.aerialDistanceTo(t) <= 1 } * 50 else 0) }
                        options.firstOrNull { u.movement.canReach(it) }?.let { move(u, it) }
                    }
                    if (!stopped() && !u.isDestroyed) attack(u, allowCapital)
                    if (!u.isDestroyed && u.hasMovement()) u.fortifyIfCan()
                }
                manageCaptures()
                if (stopped()) break
                record("end-turn\tunits=${player.units.getCivUnits().count()}\tlosses=${g.campaign!!.lostMilitary.size}")
                g.nextTurn()
                store.save(g)
                println("$route/$variant round=$round gold=${player.gold} military=${player.units.getCivUnits().count { it.isMilitary() }} losses=${g.campaign!!.lostMilitary.size} cities=${player.cities.joinToString { it.name }}")
            }
            store.save(g); snapshot()
            val state = g.campaign!!
            val expected = when (route) { "one" -> 1; "three" -> 3; else -> -1 }
            val passed = (if (route == "stress") state.failed && state.completedRounds == 50 && worstSupplyPenalty < 0 && recoveryIncome > lowestIncome
                else if (route == "economy") !state.failed && alexandriaRecovered && lowestIncome < 0 && recoveryIncome > 0
                else if (route == "transport") !state.failed && transportCompleted
                else if (expected == -1) state.resultStars > 0 else state.resultStars == expected) &&
                (variant != "lost-cannon" || state.lossReasons["10"] == "Combat") &&
                (variant != "reclaim" || alexandriaRecovered)
            val result = "objective=$route expectedStars=$expected passed=$passed stars=${state.resultStars} failed=${state.failed} round=${(state.completedRounds + 1).coerceAtMost(50)} losses=${state.lostMilitary.size} reasons=${state.lossReasons} failure=${state.failureReason} lowestIncome=$lowestIncome worstSupplyPenalty=$worstSupplyPenalty recoveryIncome=$recoveryIncome alexandriaRecovered=$alexandriaRecovered transportCompleted=$transportCompleted"
            File(directory, "result.txt").writeText(result + "\n")
            println("RESULT $result")
            return passed
        } catch (e: Exception) {
            File(directory, "failure.txt").writeText(e.stackTraceToString())
            FileConversions.writeJson(Gdx.files.absolute(File(directory, "failure.json").path), g, true)
            throw e
        } finally {
            originalTechCosts.forEach { (name, cost) -> g.ruleset.technologies.getValue(name).cost = cost }
            log.close(); economy.close(); PlayerDecisionControl.release()
        }
    }
}
