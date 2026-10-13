package com.unciv.logic.endless

/** Immutable display facts only: a share never contains a save, seed or player identity. */
class EndlessShareData private constructor(
    val rounds: Int,
    val waves: Int,
    val kills: Int,
    val status: String,
    val previousRules: Boolean,
    val pace: String? = null,
    val stages: Long = 0,
    val partialWaves: Int = 0,
    val combatRounds: Long = 0,
) {
    companion object {
        fun fromState(state: EndlessState): EndlessShareData? {
            if (!state.recordEligible || state.saveBlocked || state.inTurn || state.actionDepth != 0 || state.settling) return null
            if (state.modeVersion == 2 && state.stageState == null) return null
            val stage = state.stageState
            return create(state.completedRounds, state.clearedWaves, state.kills, state.status, state.recordKey,
                stage?.completedStages ?: 0, if (stage?.phase == "Combat") state.waves.count { it.cleared } else 0,stage?.combatRounds ?: 0)
        }

        fun fromRecord(record: EndlessRecord): EndlessShareData? {
            if (record.status !in setOf("Defeated", "Quit")) return null
            return create(record.rounds, record.waves, record.kills, record.status, record.key,record.stages,record.partialWaves,record.combatRounds)
        }

        private fun create(rounds: Int, waves: Int, kills: Int, status: String, key: String,
                           stages: Long = 0, partialWaves: Int = 0, combatRounds: Long = 0): EndlessShareData? {
            if (rounds < 1 || waves < 0 || kills < 0 || status !in setOf("Active", "Defeated", "Quit")) return null
            val rules = key.split(':')
            if (rules.size == 7) {
                if (rules[0] != "endless-survival" || rules[1] != "2" || rules[3] != "standard" || rules[4] !in setOf("casual","wild") ||
                    listOf(2,5,6).any { (rules[it].toIntOrNull() ?: 0) < 1 } || stages < 0 || partialWaves !in 0..4 || combatRounds < 0 || stages == 0L && combatRounds != 0L) return null
                return EndlessShareData(rounds,waves,kills,status,key != EndlessCatalog.newRecordKey(rules[4]),rules[4],stages,partialWaves,combatRounds)
            }
            if (rules.size != 5 || rules[0] != "endless-survival" || rules[2] != "standard" ||
                listOf(1, 3, 4).any { (rules[it].toIntOrNull() ?: 0) < 1 }) return null
            return EndlessShareData(rounds, waves, kills, status, true)
        }
    }
}
