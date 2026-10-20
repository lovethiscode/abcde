package com.unciv.logic.endless

import com.unciv.logic.GameInfo
import com.unciv.logic.IsPartOfGameInfoSerialization

class EndlessAchievementHistory : IsPartOfGameInfoSerialization {
    var recordingVersion = 0
    var observedStage = 0L
    var cities = HashSet<String>()
    var ownershipBroken = false
    var candidates = HashMap<String, EndlessHonor>()
}
class EndlessHonor {
    var id = ""
    var attemptId = ""
    var pace = ""
    var stage = 0L
    var round = 0
    var recordingVersion = 0
    var persistedAt = 0L
    var cityIds = HashSet<String>()
}
class EndlessHonorProfile {
    var schemaVersion = 1
    var unlocks = HashMap<String, EndlessHonor>()
}

/** Evidence is generated only at the mode's real first-wave and legal stage-completion boundaries. */
object EndlessAchievementRules {
    val ids = setOf("E01", "E03", "E04")
    fun eligible(s: EndlessState) = s.modeVersion == 2 && s.recordEligible && s.status != "Faulted" && s.honors?.recordingVersion == 1
    fun beforeFirstWave(game: GameInfo) {
        val s = game.endless ?: return; val stage = s.stageState ?: return
        val history = s.honors ?: return
        if (!eligible(s) || history.observedStage == stage.currentStage) return
        history.observedStage = stage.currentStage
        history.cities.clear(); history.ownershipBroken = false
        if (EndlessStageRules.capacity(s) >= 3) history.cities.addAll(EndlessRules.player(game).cities.map { it.id }.take(3))
    }
    fun cityLost(game: GameInfo, cityId: String, from: String, to: String?) {
        val s = game.endless ?: return; val history = s.honors ?: return
        if (from == s.playerId && to != from && cityId in history.cities) history.ownershipBroken = true
    }
    fun stageCompleted(game: GameInfo) {
        val s = game.endless ?: return; val stage = s.stageState ?: return; val history = s.honors ?: return
        if (!eligible(s) || s.coreLost || EndlessRules.core(game)?.civ?.civID != s.playerId ||
            s.round > stage.deadline || s.waves.size != 5 || !s.waves.all { it.announced && it.cleared }) return
        fun record(id: String) { if (id !in history.candidates) history.candidates[id] = EndlessHonor().apply {
            this.id = id; attemptId = s.attemptId; pace = stage.pace; this.stage = stage.completedStages
            round = s.round; recordingVersion = history.recordingVersion
            if (id == "E03") cityIds.addAll(history.cities)
        } }
        if (stage.completedStages == 1L) record("E01")
        if (stage.completedStages >= 3 && stage.pace == "wild") record("E04")
        if (EndlessStageRules.capacity(s) >= 3 && history.observedStage == stage.currentStage && history.cities.size == 3 && !history.ownershipBroken &&
            history.cities.all { id -> EndlessRules.player(game).cities.any { it.id == id } }) record("E03")
    }
}
