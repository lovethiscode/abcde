package com.unciv.logic.automation

import yairm210.purity.annotations.Readonly
import com.unciv.json.json
import com.unciv.models.ruleset.nation.Personality
import com.unciv.models.ruleset.tech.Technology
import com.unciv.models.ruleset.MilestoneType
import com.unciv.logic.GameInfo
import com.unciv.logic.battle.ICombatant
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.city.City
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.ruleset.Building
import com.unciv.models.ruleset.IConstruction
import com.unciv.models.ruleset.RejectionReasonType
import com.unciv.models.ruleset.unit.BaseUnit
import com.unciv.models.stats.Stats
import com.unciv.logic.civilization.diplomacy.DiplomaticStatus

/** Explicit, thread-confined decision capture on an independent game copy. Absent in normal play. */
object AutomationPreview {
    interface Sink {
        fun move(unit: MapUnit, destination: Tile)
        fun attack(attacker: ICombatant, target: Tile, from: Tile)
        fun choice(civ: Civilization, action: String, value: String, unit: MapUnit?, city: City?, tile: Tile?)
        fun finished(unit: MapUnit)
        fun checkpoint(civ: Civilization)
    }
    private class Session(val game: GameInfo, val sink: Sink, val militaryVictory: String?,
                          val personality: Personality?, val defeatedCivs: Map<String, Boolean>,
                          val builtWonders: Set<String>?, val strategy: AutomationStrategy,
                          var stage: AutomationStage, var targetCityId: String?,
                          val reasons: MutableList<String>, val reassess: Boolean, var unit: MapUnit? = null) {
        val benefits = mutableMapOf<Pair<String, String>, Stats>()
        val paths = mutableMapOf<Pair<Int, String>, Int>()
        var defenders: Map<Int, City>? = null
    }
    private class SelectedAction : RuntimeException()
    private val session = ThreadLocal<Session>()
    val active: Boolean get() = session.get() != null
    @Readonly fun controls(civ: Civilization): Boolean = session.get()?.let {
        it.game === civ.gameInfo && it.game.currentPlayer == civ.civID
    } == true

    fun capture(game: GameInfo, sink: Sink, militaryVictory: String? = null,
                defeatedCivs: Map<String, Boolean> = emptyMap(), builtWonders: Set<String>? = null,
                strategy: AutomationStrategy = AutomationStrategy.Siege, stage: AutomationStage = AutomationStage.Preparing,
                targetCityId: String? = null, reasons: MutableList<String> = mutableListOf(),
                reassess: Boolean = false, block: () -> Unit) {
        check(!active) { "Nested AI preview" }
        val personality = if (militaryVictory == null) null else {
            val original = game.getCurrentPlayerCivilization().getPersonality()
            json().fromJson(Personality::class.java, json().toJson(original)).apply {
                if (strategy.combat != CombatPriority.Counterattack && stage != AutomationStage.Building) {
                    declareWar = declareWar.coerceAtLeast(1f)
                    if (strategy.production == ProductionPriority.Army) {
                        military = 9f
                        aggressive = 9f
                        declareWar = 9f
                    }
                }
                preferredVictoryType = militaryVictory
            }
        }
        session.set(Session(game, sink, militaryVictory, personality, defeatedCivs, builtWonders,
            strategy, stage, targetCityId, reasons, reassess))
        try { block() } finally { session.remove() }
    }
    @Readonly private fun militarySession(civ: Civilization): Session? = session.get()?.takeIf {
        it.militaryVictory != null && it.game === civ.gameInfo && it.game.currentPlayer == civ.civID
    }
    @Readonly fun militaryGoal(civ: Civilization): Boolean = militarySession(civ) != null
    @Readonly fun strategy(civ: Civilization): AutomationStrategy? = militarySession(civ)?.strategy
    @Readonly fun preparing(civ: Civilization): Boolean = militarySession(civ)?.stage?.let {
        it != AutomationStage.Building
    } == true
    @Readonly fun allowsOffense(civ: Civilization): Boolean = militarySession(civ)?.let {
        it.strategy.combat != CombatPriority.Counterattack && it.stage != AutomationStage.Building
    } ?: true
    @Readonly @Suppress("purity") // Records diagnostics in the thread-local preview, never in the game.
    fun decision(civ: Civilization, message: String) {
        militarySession(civ)?.reasons?.let { if (message !in it) it.add(message) }
    }
    fun selectedTarget(civ: Civilization): String? = militarySession(civ)?.targetCityId

    @Readonly fun reinforcementTarget(civ: Civilization): City? =
        if (!militaryGoal(civ) || !allowsOffense(civ) || !civ.isAtWar()) null
        else targetCities(civ, atWarOnly = true).firstOrNull()

    /** Reserve enough nearby defenders, rather than diverting every recruit to the same city. */
    @Readonly @Suppress("purity") // Assignments live only in this planning session.
    fun defenseCity(unit: MapUnit): City? {
        val current = militarySession(unit.civ) ?: return null
        if (reinforcementTarget(unit.civ) == null) return null
        if (current.defenders == null) {
            val assignments = mutableMapOf<Int, City>()
            val army = unit.civ.units.getCivUnits().filter {
                it.isMilitary() && it.baseUnit.isLandUnit && !it.isEmbarked() &&
                    it.type.name != "Scout" && !it.hasUnique(UniqueType.CannotAttack)
            }.toList()
            for (city in unit.civ.cities.sortedWith(compareBy<City> { it.health.toFloat() / it.getMaxHealth() }.thenBy { it.id })) {
                val center = city.getCenterTile()
                val enemies = center.getTilesInDistance(2).mapNotNull { it.militaryUnit }.filter {
                    it.civ.isAtWarWith(unit.civ) && it.isVisibleTo(unit.civ) &&
                        !it.hasUnique(UniqueType.CannotAttack)
                }.toList()
                if (enemies.isEmpty() && city.health == city.getMaxHealth()) continue
                val candidates = army.filter { ally -> ally.id !in assignments &&
                    (ally.health >= 50 || ally.currentTile == center && ally.baseUnit.isRanged()) &&
                    ally.movement.canReach(center)
                }.sortedWith(compareBy<MapUnit> { if (it.baseUnit.isProbablySiegeUnit()) 1 else 0 }
                    .thenBy { it.currentTile.aerialDistanceTo(center) }
                    .thenByDescending { it.health }.thenBy { it.id })
                val enemyForce = enemies.sumOf { it.baseUnit.getForceEvaluation().toDouble() * it.health / 100 }
                val selected = mutableListOf<MapUnit>()
                fun reserve(ally: MapUnit?) {
                    if (ally != null && ally !in selected) { selected.add(ally); assignments[ally.id] = city }
                }
                reserve(candidates.firstOrNull { it.baseUnit.isRanged() && !it.baseUnit.isProbablySiegeUnit() })
                if (enemies.isNotEmpty()) reserve(candidates.firstOrNull { isCaptureUnit(it.baseUnit) })
                val emergency = city.health < city.getMaxHealth() / 2
                for (ally in candidates) {
                    val force = selected.sumOf { it.baseUnit.getForceEvaluation().toDouble() * it.health / 100 }
                    if (selected.isNotEmpty() && force >= enemyForce * (if (emergency) 1.2 else 0.7)) break
                    reserve(ally)
                }
                decision(unit.civ, "defense reserve city=${city.name} units=${selected.map { it.id }} visibleEnemies=${enemies.size}")
            }
            current.defenders = assignments
        }
        return current.defenders!![unit.id]
    }

    @Readonly fun reinforcementPriority(unit: MapUnit): Int {
        if (reinforcementTarget(unit.civ) == null || !unit.baseUnit.isLandUnit || !unit.isMilitary()) return 0
        return when { unit.health < 50 -> -1; unit.baseUnit.isProbablySiegeUnit() -> 2
            unit.baseUnit.isRanged() -> 1; else -> 0 }
    }
    fun readyToPrepare(civ: Civilization): Boolean = strategy(civ)?.combat != CombatPriority.Counterattack &&
        economyReady(civ) && targetCities(civ).any { city ->
            val army = eligibleArmy(civ, city).filter { it.health >= 50 }
            army.count(::canCapture) >= 2 && army.count { it.baseUnit.isRanged() } >= 2 &&
                com.unciv.logic.automation.civilization.MotivationToAttackAutomation.hasAtLeastMotivationToAttack(civ, city.civ, 20f) >= 20f
        }
    fun includesPeace(trade: com.unciv.logic.trade.Trade): Boolean =
        (trade.ourOffers + trade.theirOffers).any {
            it.type == com.unciv.logic.trade.TradeOfferType.PeaceProposal ||
                (it.type == com.unciv.logic.trade.TradeOfferType.Treaty && it.name == com.unciv.Constants.peaceTreaty)
        }
    @Readonly fun personality(civ: Civilization): Personality? = militarySession(civ)?.personality
    @Readonly fun preferredVictory(civ: Civilization): String? = militarySession(civ)?.militaryVictory
    @Readonly fun warTargetPriority(civ: Civilization, target: Civilization): Float {
        val current = militarySession(civ) ?: return 0f
        val victory = civ.gameInfo.ruleset.victories[current.militaryVictory] ?: return 0f
        val advancesVictory = victory.milestoneObjects.any {
            when (it.type) {
                MilestoneType.CaptureAllCapitals -> target.isMajorCiv() && !target.isDefeated() &&
                    (target.cities.none { it.isOriginalCapital } || target.cities.any { city ->
                    city.isOriginalCapital && city.foundingCivObject?.isMajorCiv() == true
                })
                MilestoneType.DestroyAllPlayers -> target.isMajorCiv() && !target.isDefeated()
                else -> false
            }
        }
        return if (advancesVictory) 30f else 0f
    }
    @Readonly fun defeated(civ: Civilization): Boolean? = session.get()?.takeIf {
        it.game === civ.gameInfo
    }?.defeatedCivs?.get(civ.civID)
    @Readonly fun wonderBuilt(game: GameInfo, name: String): Boolean? = session.get()?.takeIf {
        it.game === game
    }?.builtWonders?.contains(name)
    fun researchWeight(civ: Civilization, tech: Technology): Float {
        val weight = tech.getWeightForAiDecision(civ.state)
        if (!militaryGoal(civ)) return weight
        val config = strategy(civ)!!
        val military = researchPriority(civ, tech) > 0
        val economy = config.research == ResearchPriority.Economy && civ.gameInfo.ruleset.buildings.values.any { building ->
            val destination = civ.gameInfo.ruleset.technologies[building.requiredTech] ?: return@any false
            !building.isAnyWonder() && (building.uniqueTo == null || building.uniqueTo == civ.civName) &&
                civ.tech.getRequiredTechsToDestination(destination).any { it.name == tech.name } &&
                civ.cities.any { city -> !city.cityConstructions.isBuilt(building.name) &&
                    buildingBenefits(city, building).let { it.production > 0 || it.gold > 0 || it.science > 0 } }
        }
        val multiplier = if (config.research == ResearchPriority.Military && military || economy) 2f else 1f
        if (multiplier > 1f) decision(civ, "research preference ${config.research} ${tech.name} weight=$weight multiplier=$multiplier")
        return weight * multiplier
    }
    /** A military prerequisite must not lose to an unrelated, cheaper research group. */
    fun preferredResearch(civ: Civilization, candidates: List<Technology>): Technology? {
        if (!militaryGoal(civ)) return null
        return candidates.filter { civ.tech.canBeResearched(it.name) && it.getWeightForAiDecision(civ.state) > 0f }
            .map { it to researchPriority(civ, it) }.filter { it.second >= 2 }
            .sortedWith(compareByDescending<Pair<Technology, Int>> { it.second }
                .thenBy { it.first.cost }.thenBy { it.first.name })
            .firstOrNull()?.first
    }
    private fun researchPriority(civ: Civilization, tech: Technology): Int {
        val target = if (preparing(civ)) targetCities(civ).firstOrNull() else null
        val army = target?.let { eligibleArmy(civ, it) }.orEmpty()
        val missingCapture = target != null && army.count(::canCapture) < 2
        val missingFirepower = target != null && army.count { it.baseUnit.isRanged() } < 2
        val landUnits = civ.gameInfo.ruleset.units.values.filter { unit ->
            unit.isMilitary && unit.isLandUnit && !unit.hasUnique(UniqueType.CannotAttack) &&
                (unit.uniqueTo == null || unit.uniqueTo == civ.civName)
        }
        val captureUnits = landUnits.filter(::isCaptureUnit)
        val firepowerUnits = landUnits.filter { it.isRanged() }
        fun available(units: List<BaseUnit>) = units.any { unit -> civ.cities.any { unit.isBuildable(it.cityConstructions) } }
        val roleUnits = (if (missingCapture && !available(captureUnits)) captureUnits else emptyList()) +
            (if (missingFirepower && !available(firepowerUnits)) firepowerUnits else emptyList())
        val firstRoleTech = roleUnits.mapNotNull { civ.gameInfo.ruleset.technologies[it.requiredTech] }
            .filterNot { civ.tech.isResearched(it.name) }
            .minByOrNull { civ.tech.getRequiredTechsToDestination(it).sumOf { prerequisite -> prerequisite.cost } }
        val unlocksMissingRole = firstRoleTech != null &&
            civ.tech.getRequiredTechsToDestination(firstRoleTech).any { it.name == tech.name }
        val inlandConquest = hasKnownInlandWarCapital(civ)
        val firstSiegeTech = if (!inlandConquest || civ.units.getCivUnits().count {
            it.baseUnit.isLandUnit && it.baseUnit.isProbablySiegeUnit()
        } >= 2) null else civ.gameInfo.ruleset.units.values.asSequence()
            .filter { it.isLandUnit && it.isProbablySiegeUnit() && (it.uniqueTo == null || it.uniqueTo == civ.civName) }
            .mapNotNull { civ.gameInfo.ruleset.technologies[it.requiredTech] }.distinct()
            .minByOrNull { it.cost }
        val unlocksSiege = firstSiegeTech != null && !civ.tech.isResearched(firstSiegeTech.name) &&
            civ.tech.getRequiredTechsToDestination(firstSiegeTech).any { it.name == tech.name }
        val unlocksMilitary = civ.gameInfo.ruleset.units.values.any {
            it.requiredTech == tech.name && it.isMilitary && !it.isWaterUnit
        }
        val unlocksArmyUpgrade = civ.units.getCivUnits().any { unit ->
            if (!unit.isMilitary() || unit.baseUnit.isWaterUnit) return@any false
            val upgrade = unit.baseUnit.upgradesTo?.let { civ.getEquivalentUnit(it) } ?: return@any false
            val destination = civ.gameInfo.ruleset.technologies[upgrade.requiredTech] ?: return@any false
            val route = civ.tech.getRequiredTechsToDestination(destination)
            route.size <= 3 && route.any { it.name == tech.name }
        }
        val happinessTech = if (civ.getHappiness() > 0) null else civ.gameInfo.ruleset.buildings.values
            .asSequence().filter { !it.isAnyWonder() }.mapNotNull { building ->
                val destination = civ.gameInfo.ruleset.technologies[building.requiredTech] ?: return@mapNotNull null
                if (civ.tech.isResearched(destination.name) || civ.cities.none { city ->
                        building.getStats(city).happiness > 0f &&
                            building.getRejectionReasons(city.cityConstructions).all { it.type == RejectionReasonType.RequiresTech }
                    }) return@mapNotNull null
                destination
            }.minByOrNull { destination -> civ.tech.getRequiredTechsToDestination(destination).sumOf { it.cost } }
        val unlocksHappiness = happinessTech != null &&
            civ.tech.getRequiredTechsToDestination(happinessTech).any { it.name == tech.name }
        return when { unlocksHappiness -> 4; unlocksMissingRole || unlocksSiege -> 3
            unlocksArmyUpgrade && strategy(civ)?.research == ResearchPriority.Military -> 2
            unlocksMilitary -> 1; else -> 0 }
    }
    @Readonly fun isConquestCapital(civ: Civilization, city: City): Boolean =
        militaryGoal(civ) && civ.isAtWarWith(city.civ) &&
            city.getCenterTile().isExplored(civ) && warTargetPriority(civ, city.civ) > 0f

    @Readonly fun hasConquestCaptureSupport(civ: Civilization, city: City): Boolean =
        city.getCenterTile().getTilesInDistance(3).flatMap { it.getUnits() }.any {
            it.civ == civ && it.baseUnit.isLandUnit && isCaptureUnit(it.baseUnit) && it.health >= 50 &&
                !it.isEmbarked() && !it.hasUnique(UniqueType.CannotCaptureCities) &&
                !it.hasUnique(UniqueType.CannotAttack)
        }

    /** Joining an active barrage is different from sending unsupported melee into bombardment. */
    @Readonly fun canJoinConquestAssault(unit: MapUnit, city: City): Boolean {
        if (!isConquestCapital(unit.civ, city) || unit.health < 50 || !city.getCenterTile().isVisible(unit.civ)) return false
        val defender = com.unciv.logic.battle.CityCombatant(city)
        val retaliation = com.unciv.logic.battle.BattleDamage.calculateDamageToAttacker(
            com.unciv.logic.battle.MapUnitCombatant(unit), defender)
        if (retaliation * 2 >= unit.health) return false
        val firepower = city.getCenterTile().getTilesInDistance(3).mapNotNull { it.militaryUnit }.filter {
            it.civ == unit.civ && it.baseUnit.isRanged() && it.health >= 50 && !it.isEmbarked() &&
                !it.hasUnique(UniqueType.CannotAttack) &&
                (!it.hasUnique(UniqueType.MustSetUp) || it.isSetUpForSiege()) &&
                it.currentTile.aerialDistanceTo(city.getCenterTile()) <= it.getRange() &&
                (it.hasUnique(UniqueType.IndirectFire) || city.getCenterTile() in
                    it.currentTile.tileMap.getViewableTiles(it.currentTile.position, it.getRange(), true))
        }.toList()
        return firepower.size >= 2 && firepower.sumOf {
            com.unciv.logic.battle.BattleDamage.calculateDamageToDefender(
                com.unciv.logic.battle.MapUnitCombatant(it), defender)
        } > 20 + city.health / 5
    }

    @Readonly fun hasKnownInlandWarCapital(civ: Civilization): Boolean {
        if (!militaryGoal(civ) || !preparing(civ)) return false
        return targetCities(civ).any { city -> !city.isCoastal() &&
            city.getCenterTile().getTilesInDistance(1).all { it.isExplored(civ) } }
    }
    @Readonly fun needsLandConquestProduction(civ: Civilization, includeQueued: Boolean = true): Boolean {
        if (!militaryGoal(civ) || !preparing(civ)) return false
        val city = targetCities(civ).firstOrNull() ?: return false
        val landArmy = eligibleArmy(civ, city).filter { it.health >= 50 }.map { it.baseUnit } + if (includeQueued) civ.cities.mapNotNull {
                civ.gameInfo.ruleset.units[it.cityConstructions.currentConstructionName()]
            }.filter { it.isMilitary && it.isLandUnit } else emptyList()
        val missingCapture = landArmy.count(::isCaptureUnit) < 2
        val availableSiege = civ.gameInfo.ruleset.units.values.any { it.isLandUnit && it.isProbablySiegeUnit() &&
            Automation.allowSpendingResource(civ, it) && civ.cities.any { home -> it.isBuildable(home.cityConstructions) } }
        val visibleFirepowerGap = city.getCenterTile().isVisible(civ) && availableSiege &&
            eligibleArmy(civ, city).filter { it.health >= 50 && it.baseUnit.isRanged() }.sumOf {
                com.unciv.logic.battle.BattleDamage.calculateDamageToDefender(
                    com.unciv.logic.battle.MapUnitCombatant(it), com.unciv.logic.battle.CityCombatant(city))
            } <= 20 + city.health / 5
        val missingSiege = landArmy.count { it.isRanged() } < 2 ||
            visibleFirepowerGap && (landArmy.count { it.isProbablySiegeUnit() } < 2 || militarySession(civ)?.reassess == true)
        if (!missingCapture && !missingSiege) return false
        if (!includeQueued) return true
        return civ.gameInfo.ruleset.units.values.any { unit ->
            unit.isMilitary && unit.isLandUnit && !unit.hasUnique(UniqueType.CannotAttack) &&
                ((missingCapture && isCaptureUnit(unit)) ||
                    (missingSiege && unit.isRanged())) &&
                Automation.allowSpendingResource(civ, unit) && civ.cities.any { unit.isBuildable(it.cityConstructions) }
        }
    }
    @Readonly fun needsMilitaryProduction(civ: Civilization): Boolean {
        if (!militaryGoal(civ)) return false
        if (!preparing(civ) || !economyReady(civ)) {
            decision(civ, "mobilization blocked stage=${militarySession(civ)!!.stage} happiness=${civ.getHappiness()} gold=${civ.gold} income=${civ.stats.statsForNextTurn.gold} supply=${civ.stats.getUnitSupplyDeficit()}")
            return false
        }
        if (needsLandConquestProduction(civ)) return true
        val target = targetCities(civ).firstOrNull() ?: run {
            decision(civ, "mobilization: known target or land route unavailable")
            return false
        }
        val army = eligibleArmy(civ, target)
        val ourForce = army.sumOf { it.baseUnit.getForceEvaluation().toDouble() * it.health / 100 }.toFloat()
        val enemyForce = target.civ.getStatForRanking(com.unciv.ui.screens.victoryscreen.RankingType.Force)
        val insufficient = ourForce < enemyForce
        if (insufficient) decision(civ, "military force gap available=${army.size} reference=${maxOf(6, civ.cities.size * 2)} own=$ourForce visibleEnemy=$enemyForce")
        return insufficient
    }

    @Readonly fun economyReady(civ: Civilization, cost: Int = 0, extraMaintenance: Float = 0f): Boolean {
        val remaining = civ.gold - cost
        val income = civ.stats.statsForNextTurn.gold - extraMaintenance
        return civ.getHappiness() > 0 && civ.stats.getUnitSupplyDeficit() == 0 && remaining >= 0 &&
            remaining >= maxOf(0f, -income) * 5f
    }

    fun allowsSpending(civ: Civilization, cost: Int, extraMaintenance: Float = 0f): Boolean =
        !militaryGoal(civ) || civ.cities.any { it.health < it.getMaxHealth() } || economyReady(civ, cost, extraMaintenance)

    fun extraUnitMaintenance(civ: Civilization, base: BaseUnit, tile: Tile, replaced: MapUnit? = null): Float {
        if (!militaryGoal(civ)) return 0f
        val fake = base.newMapUnit(civ, com.unciv.Constants.NO_ID).apply { currentTile = tile }
        val hypothetical = civ.units.getCivUnits().filter { it !== replaced } + sequenceOf(fake)
        return (civ.stats.getUnitMaintenance(hypothetical) - civ.stats.getUnitMaintenance()).toFloat()
    }

    @Readonly fun isCaptureUnit(unit: BaseUnit) = unit.isMelee() && unit.type.name != "Scout" &&
        !unit.hasUnique(UniqueType.CannotCaptureCities) && !unit.hasUnique(UniqueType.CannotAttack)

    @Readonly private fun canCapture(unit: MapUnit) = isCaptureUnit(unit.baseUnit)

    @Readonly fun allowsUnitProduction(civ: Civilization, unit: BaseUnit): Boolean {
        if (!militaryGoal(civ) || unit.type.name != "Scout") return true
        if (targetCities(civ).isNotEmpty()) return false
        return civ.units.getCivUnits().count { it.type.name == "Scout" } + civ.cities.count {
            civ.gameInfo.ruleset.units[it.cityConstructions.currentConstructionName()]?.type?.name == "Scout"
        } < 2
    }

    @Readonly fun keepConquestCity(civ: Civilization, city: City): Boolean {
        if (!militaryGoal(civ) || strategy(civ)?.combat != CombatPriority.Capital) return false
        return civ.getKnownCivs().flatMap { it.cities.asSequence() }.any {
            it.civ != civ && it.civ.isMajorCiv() && it.isOriginalCapital && it.getCenterTile().isExplored(civ) &&
                it.getCenterTile().aerialDistanceTo(city.getCenterTile()) <= 5
        }
    }

    /** Paths are evaluated on the isolated copy under the proposed war's traversal rules. */
    @Readonly @Suppress("purity") // Scoped copy only; restores hypothetical diplomacy and movement caches.
    fun attackPathCost(unit: MapUnit, city: City): Int {
        val current = militarySession(unit.civ) ?: return unit.movement.getShortestPath(city.getCenterTile()).size
        return current.paths.getOrPut(unit.id to city.id) {
            val manager = unit.civ.getDiplomacyManager(city.civ) ?: return@getOrPut Int.MAX_VALUE
            val old = manager.diplomaticStatus
            val movement = unit.currentMovement
            try {
                manager.diplomaticStatus = DiplomaticStatus.War
                unit.currentMovement = unit.getMaxMovement().toFloat()
                unit.movement.clearPathfindingCache()
                val path = unit.movement.getShortestPath(city.getCenterTile())
                if (path.isEmpty()) Int.MAX_VALUE else path.size
            } finally {
                manager.diplomaticStatus = old
                unit.currentMovement = movement
                unit.movement.clearPathfindingCache()
            }
        }
    }

    @Readonly fun eligibleArmy(civ: Civilization, city: City): List<MapUnit> = civ.units.getCivUnits().filter {
        it.isMilitary() && it.baseUnit.isLandUnit && !it.isEmbarked() && !it.hasUnique(UniqueType.CannotAttack) &&
            (!militaryGoal(civ) || it.type.name != "Scout") &&
            it.currentTile.getCity()?.let { home -> home.civ == civ && home.health < home.getMaxHealth() } != true &&
            attackPathCost(it, city) != Int.MAX_VALUE
    }.toList()

    @Readonly fun declarationReady(civ: Civilization, target: Civilization): Boolean {
        if (!militaryGoal(civ)) return true
        val city = targetCities(civ).firstOrNull { it.civ == target }
        val reason = when {
            !allowsOffense(civ) -> "offense disabled stage=${militarySession(civ)!!.stage}"
            !economyReady(civ) -> "economy/happiness/supply"
            civ.cities.any { it.health < it.getMaxHealth() } -> "city defense"
            city == null -> "target/path not known"
            else -> {
                val army = eligibleArmy(civ, city).filter { it.health >= 50 }
                when {
                    army.count(::canCapture) < 2 -> "capture role/health/path"
                    army.count { it.baseUnit.isRanged() } < 2 -> "firepower role/health/path"
                    else -> null
                }
            }
        }
        if (reason != null) decision(civ, "declaration blocked ${target.civID}: $reason")
        return reason == null
    }

    @Readonly @Suppress("purity") // Retains a target and path cache only in the current planning session.
    fun targetCities(civ: Civilization, atWarOnly: Boolean = false): List<City> {
        val current = militarySession(civ) ?: return emptyList()
        val candidates = civ.getKnownCivs().filter { enemy ->
            enemy.isMajorCiv() && !enemy.isDefeated() &&
                (!atWarOnly || civ.isAtWarWith(enemy)) &&
                (civ.isAtWarWith(enemy) || warTargetPriority(civ, enemy) > 0)
        }.flatMap { it.cities.asSequence() }.filter { it.getCenterTile().isExplored(civ) }.toList()
        val army = civ.units.getCivUnits().filter { it.isMilitary() && it.baseUnit.isLandUnit && !it.isEmbarked() }.toList().ifEmpty {
            val home = civ.cities.firstOrNull() ?: return@ifEmpty emptyList()
            val probe = civ.gameInfo.ruleset.units.values.firstOrNull { it.isMilitary && it.isLandUnit &&
                !it.hasUnique(UniqueType.CannotAttack) && it.isBuildable(home.cityConstructions) }
                ?: return@ifEmpty emptyList()
            listOf(probe.newMapUnit(civ, com.unciv.Constants.NO_ID).apply { currentTile = home.getCenterTile() })
        }
        fun cost(city: City) = army.map { attackPathCost(it, city) }.sorted().take(4).let {
            if (it.isEmpty() || it.first() == Int.MAX_VALUE) Int.MAX_VALUE else it.filter { n -> n != Int.MAX_VALUE }.sum()
        }
        val reachable = candidates.filter { cost(it) != Int.MAX_VALUE }
        val sorted = reachable.sortedWith(compareBy<City> {
            if (current.strategy.combat == CombatPriority.Capital && it.isOriginalCapital) 0 else 1
        }.thenBy { cost(it) }.thenBy { if (it.isOriginalCapital) 0 else 1 }.thenBy { it.id })
        val retained = sorted.firstOrNull { it.id == current.targetCityId }
        val discoveredCapital = current.strategy.combat == CombatPriority.Capital && retained?.isOriginalCapital != true &&
            sorted.firstOrNull()?.isOriginalCapital == true
        val reviewed = if (!current.reassess) null else sorted.firstOrNull { city ->
            com.unciv.logic.automation.civilization.MotivationToAttackAutomation.hasAtLeastMotivationToAttack(civ, city.civ, 0f) > 0f
        }
        val selected = if (reviewed != null) reviewed else if (discoveredCapital) sorted.firstOrNull() else retained ?: sorted.firstOrNull()
        if (selected != null) current.targetCityId = selected.id
        return (listOfNotNull(selected) + sorted).distinct()
    }

    fun productionWeight(civ: Civilization, choice: IConstruction, benefits: Stats? = null): Float {
        val priority = strategy(civ)?.production ?: return 1f
        val matches = when (priority) {
            ProductionPriority.Auto -> false
            ProductionPriority.Army -> choice is BaseUnit && choice.isMilitary && choice.type.name != "Scout"
            ProductionPriority.Production -> choice is Building && !choice.isAnyWonder() && (benefits?.production ?: 0f) > 0f
            ProductionPriority.Gold -> choice is Building && !choice.isAnyWonder() && (benefits?.gold ?: 0f) > 0f
            ProductionPriority.Science -> choice is Building && !choice.isAnyWonder() && (benefits?.science ?: 0f) > 0f
        }
        if (matches) decision(civ, "production preference $priority ${choice.name} multiplier=2")
        return if (matches) 2f else 1f
    }

    private fun buildingBenefits(city: City, building: Building): Stats {
        val current = militarySession(city.civ) ?: return Stats()
        return current.benefits.getOrPut(city.id to building.name) {
            try {
                val baseline = city.clone().apply { setTransients(city.civ); cityStats.update(updateCivStats = false, calculateGrowthModifiers = false) }
                val added = city.clone().apply {
                    setTransients(city.civ)
                    cityConstructions.builtBuildings.add(building.name)
                    cityConstructions.setTransients()
                    cityStats.update(updateCivStats = false, calculateGrowthModifiers = false)
                }
                added.cityStats.currentCityStats - baseline.cityStats.currentCityStats
            } finally { city.expansion.setTransients() }
        }
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
    fun tradeChoice(civ: Civilization, action: String, request: com.unciv.logic.trade.TradeRequest) {
        val current = session.get() ?: return
        if (current.game === civ.gameInfo) current.sink.choice(civ, action, json().toJson(request), null, null, null)
    }
    fun counterTradeChoice(civ: Civilization, request: com.unciv.logic.trade.TradeRequest,
                          counteroffer: com.unciv.logic.trade.TradeRequest) {
        val current = session.get() ?: return
        if (current.game === civ.gameInfo) current.sink.choice(civ, "counter_trade",
            json().toJson(arrayOf(request, counteroffer)), null, null, null)
    }
    fun checkpoint(civ: Civilization) {
        val current = session.get() ?: return
        if (current.game === civ.gameInfo) current.sink.checkpoint(civ)
    }
}
