package com.unciv.logic.challenges

import com.unciv.logic.GameInfo
import com.unciv.logic.endless.EndlessCounter

/** Optional platform observer. Contains aggregate facts only, never a seed or a save. */
object ChallengeTelemetry {
    @Volatile var observer: ((String, Snapshot, String) -> Unit)? = null

    data class Snapshot(val mode: String, val attempt: String, val level: String, val rules: String,
                        val rounds: Int, val waves: Int = 0, val stages: Int = 0,
                        val result: String = "", val stars: Int = 0, val reason: String = "",
                        val nextLevel: String = "", val rewardStage: Int = 0)

    fun snapshot(game: GameInfo): Snapshot? {
        game.campaign?.let { s ->
            return Snapshot("campaign", s.attemptId, s.definition.id,
                "${s.definition.configVersion}:${s.definition.scoringVersion}:${s.evaluatorVersion}", s.completedRounds,
                result = if (s.resultStars > 0) "cleared" else if (s.failed) "failed" else "",
                stars = s.resultStars, reason = if (!s.failed) "" else when {
                    s.capitalLost -> "capital_lost"
                    s.protectedUnitLost -> "protected_unit_lost"
                    s.definition.hardTurnLimit > 0 && s.completedRounds >= s.definition.hardTurnLimit -> "round_limit"
                    else -> "objective_failed"
                }, nextLevel = s.definition.nextLevel)
        }
        game.endless?.let { s ->
            return Snapshot("endless", s.attemptId, s.modeId,
                "${s.modeVersion}:${s.mapVersion}:${s.balanceVersion}:${s.scoringVersion}:${s.difficultyId}" + (s.stageState?.pace?.let { ":$it" } ?: ""),
                s.completedRounds, s.clearedWaves, s.stageState?.let { EndlessCounter.toInt(it.completedStages) } ?: (s.clearedPrefix / 5),
                when (s.status) { "Defeated" -> "defeated"; "Quit" -> "quit"; "Faulted" -> "faulted"; else -> "" },
                reason = s.stageState?.endReason ?: "", rewardStage = s.claimedStages)
        }
        return null
    }

    fun error(mode: String, reason: String, level: String = "") {
        try { observer?.invoke("error", Snapshot(mode, "", level, "", 0), reason) }
        catch (_: Exception) { /* Failure reporting must preserve the original error. */ }
    }

    fun report(action: String, game: GameInfo, detail: String = "") {
        if (observer == null) return
        try { snapshot(game)?.let { observer?.invoke(action, it, detail) } }
        catch (_: Exception) { /* Telemetry must not affect game or save success. */ }
    }
}
