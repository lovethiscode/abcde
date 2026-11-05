package com.unciv.logic.achievements

import com.unciv.logic.GameInfo
import com.unciv.logic.civilization.Civilization

/** Evaluates source facts and fully settled state. Old saves never acquire missing V2 history. */
object AchievementRules {
    const val worldWonder = "worldWonder"

    fun evaluate(game: GameInfo, atTurnEnd: Boolean = false,
                 winner: Civilization? = null, victoryRoute: String? = null): Set<String> {
        val state = game.achievements ?: return emptySet()
        if (state.ended || state.recordingVersion !in AchievementCatalog.firstCompleteRecordingVersion..AchievementCatalog.recordingVersion ||
            state.catalogVersion !in 3..AchievementCatalog.version || state.gameId != game.gameId) return emptySet()
        val player = game.civilizations.firstOrNull { it.civID == state.playerId } ?: return emptySet()
        val history = state.history
        val result = HashSet(history.completed)
        val ownCities = player.cities.filter { it.foundingCivObject == player }
        fun add(id: String, fulfilled: Boolean) { if (fulfilled) result.add(id) }
        fun hasBuildings(city: com.unciv.logic.city.City, vararg names: String): Boolean =
            names.all { city.cityConstructions.containsBuildingOrEquivalent(it) }
        val livingUnits = player.units.getCivUnits().filter { it.isMilitary() && !it.isDestroyed }.toList()
        val mostWondersInOwnedCity = player.cities.maxOfOrNull { city ->
            history.builtWonders.values.count { it == AchievementTracker.cityKey(city) }
        } ?: 0

        add("N02", history.foundedCities.size >= 2)
        add("N04", history.goldenAgeWonderCities.size >= 2)
        add("N05", history.builtWonders.isNotEmpty())
        add("N07", livingUnits.any { (history.units[it.id.toString()]?.clearedEncampments?.size ?: 0) >= 3 })
        add("N15", history.tradePartners.size >= 3)
        add("N16", player.naturalWonders.toSet().size >= 3)
        add("N22", player.policies.completedBranches.size >= 2 && history.maximumCities <= 3)
        add("N27", mostWondersInOwnedCity >= 5)
        add("N30", mostWondersInOwnedCity >= 8)
        add("N31", livingUnits.any {
            val record = history.units[it.id.toString()]
            record != null && record.combatPromotions >= 5 && record.majorMilitaryKills >= 10
        })

        result.addAll(AchievementExtendedRules.evaluate(game, atTurnEnd))

        if (atTurnEnd) {
            add("N10", ownCities.count { !it.isCapital() && it.isConnectedToCapital() } >= 3)
            add("N11", ownCities.any { it.population.population >= 15 && hasBuildings(it, "Library", "University") })
            add("N12", player.gold >= 1000 && ownCities.count { hasBuildings(it, "Market", "Bank") } >= 3)
            val allies = game.civilizations.filter { it.isCityState && it.allyCiv == player && !it.isDefeated() }
            val allyTypes = allies.map { it.cityStateType.name }.toSet().size
            add("N13", allyTypes >= 3)
            add("N21", ownCities.any { it.population.population >= 25 } && history.maximumCities <= 3 && history.foreignCaptures.isEmpty())
            add("N23", player.gold >= 3000 && allies.size >= 4)
            add("N25", allies.size >= 5 && allyTypes >= 3)
            add("N28", ownCities.count { city ->
                city.population.population >= 10 && hasBuildings(city, "Monument", "Granary", "Library", "Barracks")
            } >= 5)
            add("N29", player.getHappiness() >= 10 && ownCities.count { it.population.population >= 20 } >= 4 && history.foreignCaptures.isEmpty())
            add("N34", livingUnits.any {
                val record = history.units[it.id.toString()]
                it.name == "Chu-Ko-Nu" && record != null && record.combatPromotions >= 4 && record.chuKoNuKillsThisTurn.size >= 2
            })
            if (game.isReligionEnabled()) {
                val religion = player.religionManager.religion
                if (religion != null && religion.foundingCivName == player.civID && religion.isMajorReligion()) {
                    val cities = game.getCities().filter { it.religion.getMajorityReligionName() == religion.name }.toList()
                    val foreign = cities.filter { it.civ != player && it.civ.isMajorCiv }
                    add("N14", cities.count { it in ownCities } >= 3)
                    add("N24", religion.isEnhancedReligion() && foreign.count { it.isCapital() } >= 3)
                    add("N33", cities.size >= 15 && foreign.size >= 5 && foreign.map { it.civ.civID }.toSet().size >= 3)
                }
            }
        }

        if (winner == player && victoryRoute in AchievementCatalog.victoryRoutes) {
            add("N01", true)
            add("N17", victoryRoute == "Scientific" && history.maximumCities <= 4 && history.foreignCaptures.isEmpty())
            add("N18", victoryRoute == "Cultural" && history.maximumCities <= 4)
            add("N19", victoryRoute == "Domination" && history.foundedCities.size <= 1)
            add("N20", victoryRoute == "Diplomatic" && history.builtWonders.size <= 2)
            add("N36", victoryRoute != "Time" && history.maximumCities <= 3 && history.foreignCaptures.isEmpty())
            add("N37", victoryRoute != "Time")
            add("N38", victoryRoute == "Scientific" && history.maximumCities <= 1)
            add("N39", victoryRoute == "Cultural" && worldWonder !in history.restrictions)
        }
        return result.filterTo(HashSet()) {
            val definition = AchievementCatalog.byId[it]
            definition != null && definition.mode == "free" && !definition.isCollection && AchievementCatalog.ineligibility(definition, state) == null
        }
    }
}
