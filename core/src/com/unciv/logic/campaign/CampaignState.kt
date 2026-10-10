package com.unciv.logic.campaign

import com.unciv.logic.IsPartOfGameInfoSerialization
import com.unciv.logic.map.HexCoord

/** The entire definition is snapshotted in an attempt; updates never silently rescore it. */
class CampaignDefinition : IsPartOfGameInfoSerialization {
    var id = ""
    var title = ""
    var number = 2
    var configVersion = 1
    var scoringVersion = 1
    var description = ""
    var objectives = arrayListOf<String>()
    var hints = arrayListOf<String>()
    var restrictions = arrayListOf<String>()
    var requiredProduction = arrayListOf<String>()
    var worker = "Worker"
    var population = 4
    var nextLevel = ""
    var targetCity = ""
    var enemy = "Germany"
    var areas = arrayListOf<CampaignArea>()
    var targetTiles = arrayListOf<HexCoord>()
    var meleeUnits = arrayListOf("Warrior", "Spearman", "Swordsman", "Pikeman", "Longswordsman", "Musketman", "Rifleman", "Great War Infantry", "Infantry", "Mechanized Infantry", "Legion", "Lancer", "Anti-Tank Gun", "Helicopter Gunship")
    var rangedUnits = arrayListOf("Archer", "Composite Bowman", "Crossbowman", "Gatling Gun", "Machine Gun", "Bazooka")
    var siegeUnits = arrayListOf("Catapult", "Trebuchet", "Cannon", "Artillery", "Rocket Artillery", "Ballista")
    var observeRounds = 3
    var efficiencyTurn = 4
    var hardTurnLimit = 0
    var defendRounds = 20
    var populationMain = 3
    var productionTarget = 8
    var goldTarget = 100
    var incomeTarget = 3
    var lossLimit = 0
    var maxCities = 0
    var forbidFounding = false
    var forbidWar = false
    var lockedWar = false
    var automatic = false
}

class CampaignArea : IsPartOfGameInfoSerialization {
    var title = ""
    var tiles = arrayListOf<HexCoord>()
}

class CampaignState : IsPartOfGameInfoSerialization {
    var schemaVersion = 1
    var evaluatorVersion = 1
    var attemptId = ""
    var seed = 0L
    var engineVersion = ""
    var rulesVersion = ""
    var definition = CampaignDefinition()
    var playerId = ""
    var capitalId = ""
    var initialCities = hashSetOf<String>()
    var foundedCities = hashSetOf<String>()
    var capturedCities = hashSetOf<String>()
    var protectedUnits = hashSetOf<Int>()
    var protectedUnitLost = false
    var capitalLost = false
    var foundedTurn = 0
    var scoutedBeforeFounding = false
    var completedRounds = 0
    var lastSampleTurn = 0
    var incomeStreak = 0
    var stableStreak = 0
    var saveSequence = 0L
    var produced = hashSetOf<String>()
    var producedWorkers = hashSetOf<Int>()
    var producedUnits = hashSetOf<Int>()
    var killedUnits = hashSetOf<Int>()
    var lostMilitary = hashSetOf<Int>()
    var killRoles = hashSetOf<String>()
    var enemyEliminated = false
    var hintsDismissed = false
    var briefingSeen = false
    /** Zero means still playing or failed. A positive result is frozen before progress is written. */
    var resultStars = 0
    // Retain the original serialized names for construction-prototype save compatibility.
    var resultWorker = false
    var resultPopulation = false
    var failed = false
    var failureReason = ""
    var resultDetails = arrayListOf<String>()
    @Transient var actionDepth = 0
    @Transient var settling = false

    fun clone(): CampaignState = com.unciv.json.json().let {
        it.fromJson(CampaignState::class.java, it.toJson(this))
    }
}

class CampaignProgress {
    var schemaVersion = 1
    var cleared = hashSetOf<String>()
    var unlocked = hashSetOf<String>()
    var lastPlayed = ""
    /** Maximum from one attempt per scoring version, never a union of bonus flags. */
    var bestStars = hashMapOf<String, Int>()
    var medals = hashSetOf<String>()
}
