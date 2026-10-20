package com.unciv.logic.achievements

import com.unciv.json.json
import com.unciv.logic.IsPartOfGameInfoSerialization

/** Branch-local source facts. Legacy fields remain readable but are not used to award V2 medals. */
class AchievementHistory : IsPartOfGameInfoSerialization {
    var extended = AchievementExtendedHistory()
    var foundedCities = HashSet<String>()
    var maximumCities = 0
    var nextCitySerial = 0
    var cityKeys = HashMap<String, String>() // Current settlement at each coordinate, assigned only when founded.
    var restrictions = HashSet<String>()
    var units = HashMap<String, AchievementUnitHistory>()
    var builtWonders = HashMap<String, String>() // World wonder -> city ID at actual completion (including a city acquired earlier).
    var romanBuildings = HashMap<String, HashSet<String>>()
    var greatImprovements = HashMap<String, String>() // Tile -> improvement made by our Great Person.
    var foreignCaptures = HashSet<String>()
    var navalCaptures = HashSet<String>()
    var englishCaptures = HashSet<String>()
    var samuraiCapitals = HashMap<String, HashSet<String>>()
    var goldenAgeCaptures = HashSet<String>()
    var goldenAgeCapturedCivilizations = HashSet<String>()
    var goldenAgeWonderCities = HashSet<String>()
    var tradePartners = HashSet<String>()
    var pendingCaptures = HashMap<String, AchievementCapture>()
    var mountainParties = ArrayList<AchievementMountainParty>()
    var completed = HashSet<String>()
    var turn = -1
    var militaryLossThisTurn = false
    var capturedThisTurn = HashSet<String>()
    var wonderCitiesThisTurn = HashSet<String>()
    var cityDamageClasses = HashMap<String, HashSet<String>>()
    var shipOfTheLineDamage = HashSet<String>()

    fun unit(id: Int) = units.getOrPut(id.toString()) { AchievementUnitHistory() }

    fun startTurn(turnNumber: Int) {
        if (turn == turnNumber) return
        turn = turnNumber
        capturedThisTurn.clear()
        units.values.forEach { it.startTurn() }
    }

    fun clone(): AchievementHistory = json().fromJson(AchievementHistory::class.java, json().toJson(this))
}

class AchievementUnitHistory : IsPartOfGameInfoSerialization {
    var majorMilitaryKills = 0
    var clearedEncampments = HashSet<String>()
    var earnedPromotions = 0
    // Missing fields stay zero: earlier saves did not record where promotion XP came from.
    var combatExperience = 0
    var combatPromotions = 0
    var samuraiMilitaryKill = false
    var chuKoNuKillsThisTurn = HashSet<String>()
    var keshikAttacks = ArrayList<AchievementRetreat>()
    var mountainEntry = ""
    var crossedMountainThisTurn = false
    var cityCapturedAfterCrossing = false

    fun startTurn() {
        chuKoNuKillsThisTurn.clear()
    }
}

class AchievementRetreat : IsPartOfGameInfoSerialization {
    var origin = ""
    var moved = false
}

class AchievementMountainParty : IsPartOfGameInfoSerialization {
    var turn = 0
    var members = HashSet<String>()
}

/** Kept while the human's conquer/liberate choice is pending, including across a save. */
class AchievementCapture : IsPartOfGameInfoSerialization {
    var ownershipSettled = false
    var unitId = 0
    var combatPromotions = 0
    var turn = 0
    var foreign = false
    var originalCapital = false
    var coastal = false
    var militaryClass = ""
    var hadSamuraiKill = false
    var crossedMountain = false
}
