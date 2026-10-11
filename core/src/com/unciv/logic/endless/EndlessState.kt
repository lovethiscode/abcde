package com.unciv.logic.endless

import com.unciv.logic.IsPartOfGameInfoSerialization

/** All mode facts travel with the game; no process-global wave or reward ledger. */
class EndlessState : IsPartOfGameInfoSerialization {
    var schemaVersion = 1
    var modeId = "endless-survival"
    var difficultyId = "standard"
    var modeVersion = 1
    var mapVersion = 1
    // Keep the serialized default stable so an omitted v1 field never adopts a newer balance.
    var balanceVersion = 1
    var scoringVersion = 1
    var engineCompatibility = 1
    var fingerprint = ""
    var attemptId = ""
    var seed = 0L
    var playerId = "endless-guard"
    var invaderId = "endless-invaders"
    var coreId = "endless-core-city"
    var completedRounds = 0
    var lastPreparedRound = 0
    var waveNumber = 0
    var lastWaveRound = 0
    var earlyWaveRound = 0
    var clearedPrefix = 0
    var clearedWaves = 0
    var kills = 0
    var militaryLosses = 0
    var civilianLosses = 0
    var lastMainSector = -1
    var tier = 1
    var lastTemplate = ""
    var templateBag = arrayListOf<String>()
    var bagNumber = 0
    var waves = arrayListOf<EndlessWave>()
    var pendingGold = 0
    var claimedStages = 0
    var offer: EndlessReward? = null
    var reinforcement: EndlessReinforcement? = null
    var coreLost = false
    var status = "Active"
    var fault = ""
    var recordEligible = true
    var saveSequence = 0L
    var endedAt = 0L
    @Transient var actionDepth = 0
    @Transient var settling = false
    @Transient var inTurn = false
    @Transient var saveBlocked = false

    val round get() = completedRounds + 1
    val ended get() = status != "Active"
    val nextWaveRound get() = if (waveNumber == 0) 6 else
        if (earlyWaveRound > 0) minOf(lastWaveRound + 8, earlyWaveRound) else lastWaveRound + 8
    val pendingCount get() = waves.sumOf { w -> w.members.count { it.unitId == 0 } }
    val activeCount get() = waves.sumOf { w -> w.members.count { it.unitId != 0 } }
    val recordKey get() = "$modeId:$mapVersion:$difficultyId:$balanceVersion:$scoringVersion"

    fun clone(): EndlessState = com.unciv.json.json().let {
        it.fromJson(EndlessState::class.java, it.toJson(this))
    }
}

class EndlessWave : IsPartOfGameInfoSerialization {
    var number = 0
    var round = 0
    var budget = 0
    var template = ""
    var mainSector = 0
    var secondarySector = 0
    var members = arrayListOf<EndlessMember>()
}

class EndlessMember : IsPartOfGameInfoSerialization {
    var index = 0
    var role = "M"
    var name = "Spearman"
    var sector = 0
    var promotion = ""
    var unitId = 0
}

class EndlessReward : IsPartOfGameInfoSerialization {
    var stage = 0
    var gold = 0
    var unit = "Spearman"
}

class EndlessReinforcement : IsPartOfGameInfoSerialization {
    var unit = "Spearman"
    var expiresRound = 0
    var fallbackGold = 0
}

class EndlessRecord {
    var attemptId = ""
    var key = ""
    var seed = 0L
    var rounds = 0
    var waves = 0
    var kills = 0
    var status = ""
    var endedAt = 0L
}

class EndlessRecords {
    var schemaVersion = 1
    var best = hashMapOf<String, EndlessRecord>()
    var recent = arrayListOf<EndlessRecord>()
}
