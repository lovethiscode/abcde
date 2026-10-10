package com.unciv.logic.campaign

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.files.FileHandle
import com.unciv.UncivGame
import com.unciv.logic.GameInfo
import com.unciv.logic.UncivShowableException
import com.unciv.logic.files.FileConversions
import java.security.MessageDigest
import java.util.UUID
import kotlin.random.Random

/** Stage-two catalog: only real, playable content is exposed. */
object CampaignCatalog {
    const val construction = "beginner-construction"
    private const val folder = "campaigns/beginner/construction"

    fun definition(): CampaignDefinition = requireNotNull(
        FileConversions.readJson(Gdx.files.internal("$folder/level.json"), CampaignDefinition::class.java)
    ).also {
        require(it.id == construction && it.requiredProduction.isNotEmpty() && it.population > 0)
    }

    fun template(): GameInfo = requireNotNull(
        FileConversions.readJson(Gdx.files.internal("$folder/initial-state.json"), GameInfo::class.java)
    ).also { it.setTransients() }

    fun newAttempt(): GameInfo {
        val definition = definition()
        val game = template()
        require(game.campaign == null && game.turns == 0 && !game.gameParameters.isOnlineMultiplayer)
        require(game.gameParameters.mods.isEmpty() && game.gameParameters.noBarbarians)
        val player = game.civilizations.single { it.isHuman() }
        require(game.civilizations.size == 1 && game.gameParameters.victoryTypes.isEmpty())
        require(definition.requiredProduction.all { it in game.ruleset.buildings || it in game.ruleset.units })
        require(definition.worker in game.ruleset.units)
        game.gameId = UUID.randomUUID().toString()
        game.customSaveLocation = null
        game.achievements = null
        game.campaign = CampaignState().apply {
            this.definition = definition
            attemptId = UUID.randomUUID().toString()
            seed = Random.nextLong()
            engineVersion = UncivGame.VERSION.toString()
            rulesVersion = rulesFingerprint()
            playerId = player.civID
            capitalId = requireNotNull(player.getCapital()).id
        }
        game.tileMap.mapParameters.seed = game.campaign!!.seed
        return game
    }

    fun requireCompatible(game: GameInfo) {
        val state = game.campaign ?: return
        if (state.schemaVersion != 1 || state.engineVersion != UncivGame.VERSION.toString() ||
            state.rulesVersion != rulesFingerprint() || state.definition.id != construction)
            throw UncivShowableException("This campaign needs a compatibility update. Your save and results have been kept.")
    }

    private fun rulesFingerprint(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fun add(file: FileHandle) {
            if (file.isDirectory) file.list().sortedBy { it.name() }.forEach(::add)
            else if (file.extension() == "json") {
                digest.update(file.path().toByteArray(Charsets.UTF_8))
                digest.update(file.readBytes())
            }
        }
        add(Gdx.files.internal("jsons/Civ V - Gods & Kings"))
        add(Gdx.files.internal("jsons/Civ V - Vanilla"))
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
