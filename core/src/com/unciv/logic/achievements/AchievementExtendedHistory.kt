package com.unciv.logic.achievements

import com.unciv.logic.IsPartOfGameInfoSerialization

/** V4 evidence. Missing groups carry no recording capability; the opening state supplies that capability. */
class AchievementExtendedHistory : IsPartOfGameInfoSerialization {
    var observedTurn = -1
    var ownedCities = HashSet<String>()
    var ownershipEpochs = HashMap<String, Int>()
    var warEpochs = HashMap<String, Int>()
    var siege = HashMap<String, AchievementSiege>()
    var captures = HashMap<String, AchievementNewCapture>()
    var relief = HashMap<String, AchievementRelief>()
    var recovery = HashMap<String, AchievementRecovery>()
    var romanProduction = HashMap<String, HashSet<String>>()
    var romanCities = HashSet<String>()
    var terraces = HashSet<String>()
    var chuKoNu = HashMap<String, AchievementAttackWindow>()
    var keshik = HashMap<String, AchievementAttackWindow>()
}
class AchievementSiege : IsPartOfGameInfoSerialization {
    var defender = ""
    var warEpoch = 0
    var ownershipEpoch = 0
    var supporters = HashSet<String>()
}
class AchievementNewCapture : IsPartOfGameInfoSerialization {
    var actor = ""
    var defender = ""
    var previouslyOwned = false
    var coordinated = false
    var warEpoch = 0
}
class AchievementRelief : IsPartOfGameInfoSerialization {
    var turn = -1
    var enemies = HashSet<String>()
    var defeated = false
}
class AchievementRecovery : IsPartOfGameInfoSerialization {
    var baseline = -1 // Unknown until resistance ends; never infer an earlier baseline on load.
}
class AchievementAttackWindow : IsPartOfGameInfoSerialization {
    var turn = -1
    var attacks = 0
    var moved = false
}
