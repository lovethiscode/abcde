package com.unciv.logic

import com.unciv.logic.civilization.Civilization
import yairm210.purity.annotations.Readonly

/** Optional external human-player control. No binding means ordinary engine behavior. */
object PlayerDecisionControl {
    @Volatile private var binding: Pair<GameInfo, String>? = null

    @Synchronized fun acquire(game: GameInfo, civId: String) {
        require(game.getCivilization(civId).isHuman())
        binding = game to civId
    }

    @Synchronized fun release() { binding = null }

    /** Only the real next-turn UI path transfers control; arbitrary planning clones do not. */
    @Synchronized fun followNextTurn(original: GameInfo, next: GameInfo) {
        val current = binding ?: return
        if (current.first === original) binding = next to current.second
    }

    @Readonly fun controls(civ: Civilization): Boolean {
        val current = binding ?: return false
        return current.first === civ.gameInfo && current.second == civ.civID && civ.isHuman()
    }
}
