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
    var stageState: EndlessStageState? = null
    @Transient var actionDepth = 0
    @Transient var settling = false
    @Transient var inTurn = false
    @Transient var saveBlocked = false
    @Transient var pendingRewardCandidate: com.unciv.logic.GameInfo? = null
    @Transient var pendingRewardDigest = ""
    @Transient var pendingRewardChoice = ""

    val round get() = stageState?.resultRound?.takeIf { ended && it > 0 } ?: EndlessCounter.add(completedRounds, 1)
    val ended get() = status != "Active"
    val nextWaveRound get() = if (stageState != null) waves.firstOrNull { !it.announced }?.round
        ?: stageState!!.nextStageRound.takeIf { it > 0 } ?: EndlessCounter.add(stageState!!.deadline, 1)
        else if (waveNumber == 0) 6 else
        if (earlyWaveRound > 0) minOf(lastWaveRound + 8, earlyWaveRound) else lastWaveRound + 8
    val pendingCount get() = if (stageState == null) waves.sumOf { w -> w.members.count { it.unitId == 0 } }
        else waves.filter { it.announced }.sumOf { w -> w.members.count { it.lifecycle == "Pending" } }
    val activeCount get() = if (stageState == null) waves.sumOf { w -> w.members.count { it.unitId != 0 } }
        else waves.sumOf { w -> w.members.count { it.lifecycle == "Active" } }
    val recordKey get() = if (modeVersion == 2) "$modeId:$modeVersion:$mapVersion:$difficultyId:${stageState?.pace}:$balanceVersion:$scoringVersion"
        else "$modeId:$mapVersion:$difficultyId:$balanceVersion:$scoringVersion"

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
    var announced = false
    var cleared = false
}

class EndlessMember : IsPartOfGameInfoSerialization {
    var index = 0
    var role = "M"
    var name = "Spearman"
    var sector = 0
    var promotion = ""
    var unitId = 0
    var lifecycle = "Future"
    var idleRounds = 0
    var stallReason = ""
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
    var stages = 0L
    var partialWaves = 0
    var combatRounds = 0L
    var endReason = ""
}

/** Explicit v2 facts. An absent group always remains a legacy save. */
class EndlessStageState : IsPartOfGameInfoSerialization {
    var pace = ""
    var phase = ""
    var currentStage = 0L
    var completedStages = -1L
    var startRound = 0
    var deadline = 0
    var clearedAt = 0
    var nextStageRound = 0
    var combatRounds = -1L
    var corePosition: com.unciv.logic.map.HexCoord? = null
    var preparedRound = -1
    var prepareStep = -1
    var normalStartedRound = -1
    var resultRound = 0
    var endReason = ""
    var preview: EndlessWave? = null

}

class EndlessRecords {
    var schemaVersion = 1
    var best = hashMapOf<String, EndlessRecord>()
    var recent = arrayListOf<EndlessRecord>()
}
