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
    val recordKey get() = EndlessState().apply { balanceVersion = EndlessCatalog.balanceVersion }.recordKey
    fun newAttempt(seed: Long = Random.nextLong()): GameInfo {
        val game = requireNotNull(FileConversions.readJson(Gdx.files.internal("endless/initial-state.json"), GameInfo::class.java))
        game.endless = EndlessState().apply {
            balanceVersion = EndlessCatalog.balanceVersion
            this.seed = seed
            attemptId = UUID.randomUUID().toString()
            fingerprint = fingerprint()
        }
        game.gameId = UUID.randomUUID().toString()
        game.customSaveLocation = null
        game.achievements = null
        game.tileMap.mapParameters.seed = seed
        game.setTransients()
        validate(game)
        EndlessRules.prepareRound(game)
        return game
    }

    fun requireCompatible(game: GameInfo) {
        val s = requireNotNull(game.endless)
        if (game.campaign != null || s.modeId != "endless-survival" || s.difficultyId != "standard" || s.schemaVersion != 1 || s.modeVersion != 1 || s.mapVersion != 1 ||
            s.balanceVersion != balanceVersion || s.scoringVersion != 1 || s.engineCompatibility != 1 || s.fingerprint != fingerprint())
            throw UncivShowableException("This endless save needs a compatibility update. Your save and records have been kept.")
    }

    fun validate(game: GameInfo) {
        val s = requireNotNull(game.endless)
        require(game.campaign == null && !game.gameParameters.isOnlineMultiplayer && game.gameParameters.mods.isEmpty())
        require(game.gameParameters.baseRuleset == "Civ V - Gods & Kings" && game.gameParameters.speed == "Standard" && game.difficulty == "Prince")
        require(game.gameParameters.victoryTypes.isEmpty() && game.civilizations.size == 2)
        require(game.tileMap.values.size == 331 && !game.tileMap.mapParameters.worldWrap)
        require(s.waves.size <= 41 && s.activeCount <= 40 && s.pendingCount <= 16)
        require(s.waves.count { w -> w.members.any { it.unitId == 0 } } <= 1)
        require(s.waves.all { w -> w.members.all { it.name in EndlessRules.units && it.role in EndlessWavePlanner.roles } })
        require(s.completedRounds >= 0 && s.lastPreparedRound <= s.round)
        require(s.status in setOf("Active", "Defeated", "Quit", "Faulted"))
        val ids = s.waves.flatMap { it.members }.filter { it.unitId != 0 }.map { it.unitId }
        require(ids.size == ids.toSet().size)
        if (!s.ended) require(ids.toSet() == EndlessRules.invaders(game).units.getCivUnits().map { it.id }.toSet())
        require(EndlessRules.technologies.all { it in game.ruleset.technologies })
        require(EndlessRules.units.all { it in game.ruleset.units } && EndlessRules.buildings.all { it in game.ruleset.buildings })
        if (!s.ended) require(EndlessRules.core(game)?.civ?.civID == s.playerId)
    }

    fun fingerprint(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update("endless-survival:balance-$balanceVersion".toByteArray(Charsets.UTF_8))
        fun add(file: FileHandle) {
            if (file.isDirectory) file.list().sortedBy { it.name() }.forEach(::add)
            else if (file.extension() == "json") {
                digest.update(file.path().toByteArray(Charsets.UTF_8))
                digest.update(file.readBytes())
            }
        }
        add(Gdx.files.internal("jsons/Civ V - Gods & Kings"))
        add(Gdx.files.internal("jsons/Civ V - Vanilla"))
        add(Gdx.files.internal("endless"))
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
