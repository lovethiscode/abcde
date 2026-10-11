package com.unciv.logic.endless

/** Immutable display facts only: a share never contains a save, seed or player identity. */
class EndlessShareData private constructor(
    val rounds: Int,
    val waves: Int,
    val kills: Int,
    val status: String,
    val previousRules: Boolean,
) {
    companion object {
        fun fromState(state: EndlessState): EndlessShareData? {
            if (!state.recordEligible || state.saveBlocked || state.inTurn || state.actionDepth != 0 || state.settling) return null
            return create(state.completedRounds, state.clearedWaves, state.kills, state.status, state.recordKey)
        }

        fun fromRecord(record: EndlessRecord): EndlessShareData? {
            if (record.status !in setOf("Defeated", "Quit")) return null
            return create(record.rounds, record.waves, record.kills, record.status, record.key)
        }

        private fun create(rounds: Int, waves: Int, kills: Int, status: String, key: String): EndlessShareData? {
            if (rounds < 1 || waves < 0 || kills < 0 || status !in setOf("Active", "Defeated", "Quit")) return null
            val rules = key.split(':')
            if (rules.size != 5 || rules[0] != "endless-survival" || rules[2] != "standard" ||
                listOf(1, 3, 4).any { (rules[it].toIntOrNull() ?: 0) < 1 }) return null
            return EndlessShareData(rounds, waves, kills, status, key != EndlessCatalog.recordKey)
        }
    }
}
