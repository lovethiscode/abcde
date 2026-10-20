package com.unciv.logic.endless

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.files.FileHandle
import com.unciv.logic.GameInfo
import com.unciv.logic.UncivShowableException
import com.unciv.logic.files.FileConversions
import java.security.MessageDigest
import java.util.UUID
import kotlin.random.Random

object EndlessCatalog {
    const val balanceVersion = 2
    const val stageBalanceVersion = 2
    val legacyRecordKey get() = EndlessState().apply { balanceVersion = EndlessCatalog.balanceVersion }.recordKey
    val recordKey get() = newRecordKey("casual")
    fun newRecordKey(pace: String) = "endless-survival:2:2:standard:$pace:$stageBalanceVersion:2"
    fun newAttempt(seed: Long = Random.nextLong(), pace: String = "casual"): GameInfo {
        require(pace in setOf("casual", "wild"))
        val game = requireNotNull(FileConversions.readJson(Gdx.files.internal("endless-v2/initial-state.json"), GameInfo::class.java))
        game.endless = EndlessState().apply {
            schemaVersion = 2; modeVersion = 2; mapVersion = 2; balanceVersion = stageBalanceVersion; scoringVersion = 2
            honors = EndlessAchievementHistory().apply { recordingVersion = 1 }
            stageState = EndlessStageState().apply {
                this.pace = pace; corePosition = game.getCities().first().location
                phase = "Preparation"; currentStage = 1; completedStages = 0; combatRounds = 0
                startRound = 6; deadline = 25; preparedRound = 0; prepareStep = 0; normalStartedRound = 1
            }
            this.seed = seed
            attemptId = UUID.randomUUID().toString()
            fingerprint = fingerprint(2)
        }
        game.gameId = UUID.randomUUID().toString()
        game.customSaveLocation = null
        game.achievements = null
        game.tileMap.mapParameters.seed = seed
        game.setTransients()
        EndlessRules.prepareRound(game)
        validate(game)
        return game
    }

    fun requireCompatible(game: GameInfo) {
        val s = requireNotNull(game.endless)
        val version = if (s.modeVersion == 1) s.schemaVersion == 1 && s.mapVersion == 1 && s.balanceVersion == balanceVersion && s.scoringVersion == 1 && s.stageState == null
            else s.modeVersion == 2 && s.schemaVersion == 2 && s.mapVersion == 2 && s.balanceVersion == stageBalanceVersion && s.scoringVersion == 2 &&
                s.stageState?.pace in setOf("casual", "wild") && s.stageState?.corePosition != null
        if (game.campaign != null || s.modeId != "endless-survival" || s.difficultyId != "standard" || !version ||
            s.engineCompatibility != 1 || s.fingerprint != fingerprint(s.modeVersion))
            throw UncivShowableException("This endless save needs a compatibility update. Your save and records have been kept.")
    }

    fun validate(game: GameInfo) {
        val s = requireNotNull(game.endless)
        require(game.campaign == null && !game.gameParameters.isOnlineMultiplayer && game.gameParameters.mods.isEmpty())
        require(game.gameParameters.baseRuleset == "Civ V - Gods & Kings" && game.gameParameters.speed == "Standard" && game.difficulty == "Prince")
        require(game.gameParameters.victoryTypes.isEmpty() && game.civilizations.size == 2)
        require(game.tileMap.values.size == 331 && !game.tileMap.mapParameters.worldWrap)
        if (s.modeVersion == 2) EndlessStageRules.validate(game) else {
            require(s.waves.size <= 41 && s.activeCount <= 40 && s.pendingCount <= 16)
            require(s.waves.count { w -> w.members.any { it.unitId == 0 } } <= 1)
        }
        require(s.waves.all { w -> w.members.all { it.name in EndlessStagePlanner.tiers.flatten() && it.role in EndlessWavePlanner.roles } })
        require(s.completedRounds >= 0 && s.lastPreparedRound <= s.round)
        require(s.status in setOf("Active", "Defeated", "Quit", "Faulted"))
        val ids = s.waves.flatMap { it.members }.filter { it.unitId != 0 }.map { it.unitId }
        require(ids.size == ids.toSet().size)
        if (!s.ended) require(ids.toSet() == EndlessRules.invaders(game).units.getCivUnits().map { it.id }.toSet())
        require(EndlessRules.technologies.all { it in game.ruleset.technologies })
        require(EndlessRules.units.all { it in game.ruleset.units } && EndlessRules.buildings.all { it in game.ruleset.buildings })
        if (!s.ended) require(EndlessRules.core(game)?.civ?.civID == s.playerId)
    }

    fun fingerprint(modeVersion: Int = 1): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update((if (modeVersion == 1) "endless-survival:balance-$balanceVersion" else "endless-survival:mode-2:map-2:balance-$stageBalanceVersion:scoring-2").toByteArray(Charsets.UTF_8))
        fun add(file: FileHandle, logicalPath: String) {
            if (file.isDirectory) file.list().sortedBy { it.name() }.forEach { add(it,"$logicalPath/${it.name()}") }
            else if (file.extension() == "json") {
                digest.update(logicalPath.toByteArray(Charsets.UTF_8))
                digest.update(file.readBytes())
            }
        }
        val prefix = if (modeVersion == 1) "endless-legacy/" else ""
        for (path in listOf("jsons/Civ V - Gods & Kings", "jsons/Civ V - Vanilla")) add(Gdx.files.internal(prefix + path),path)
        val content = if (modeVersion == 1) "endless" else "endless-v2"
        add(Gdx.files.internal(prefix + content), content)
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
