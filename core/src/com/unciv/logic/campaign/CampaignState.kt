package com.unciv.logic.campaign

import com.unciv.logic.IsPartOfGameInfoSerialization

/** Saved conditions belong to this attempt, even when the packaged level is updated. */
class CampaignDefinition : IsPartOfGameInfoSerialization {
    var id = ""
    var title = ""
    var configVersion = 1
    var scoringVersion = 1
    var requiredProduction = arrayListOf<String>()
    var worker = "Worker"
    var population = 4
    var nextLevel = ""
}

class CampaignState : IsPartOfGameInfoSerialization {
    var schemaVersion = 1
    var attemptId = ""
    var seed = 0L
    var engineVersion = ""
    var rulesVersion = ""
    var definition = CampaignDefinition()
    var playerId = ""
    var capitalId = ""
    var completedRounds = 0
    var saveSequence = 0L
    var produced = hashSetOf<String>()
    var producedWorkers = hashSetOf<Int>()
    /** Zero means still playing; the result is frozen before permanent progress is written. */
    var resultStars = 0
    var resultWorker = false
    var resultPopulation = false
    var failed = false

    fun clone(): CampaignState = com.unciv.json.json().let {
        it.fromJson(CampaignState::class.java, it.toJson(this))
    }
}

class CampaignProgress {
    var schemaVersion = 1
    var cleared = hashSetOf<String>()
    var unlocked = hashSetOf<String>()
    /** A maximum from one attempt per scoring version, never a union of bonus flags. */
    var bestStars = hashMapOf<String, Int>()
}
