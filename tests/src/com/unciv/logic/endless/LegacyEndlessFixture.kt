package com.unciv.logic.endless

import com.badlogic.gdx.Gdx
import com.unciv.logic.GameInfo
import com.unciv.logic.files.FileConversions
import java.util.UUID

/** Build an upgrade-before-save fixture without exposing an old-rules new-game UI. */
internal fun legacyEndlessAttempt(seed: Long = 1101): GameInfo {
    val game = requireNotNull(FileConversions.readJson(Gdx.files.internal("endless-legacy/endless/initial-state.json"),GameInfo::class.java))
    game.endless = EndlessState().apply {
        balanceVersion = 2; this.seed = seed; attemptId = UUID.randomUUID().toString(); fingerprint = EndlessCatalog.fingerprint()
    }
    game.gameId = UUID.randomUUID().toString(); game.customSaveLocation = null; game.achievements = null
    game.tileMap.mapParameters.seed = seed
    game.setTransients(); EndlessCatalog.validate(game); EndlessRules.prepareRound(game)
    return game
}
