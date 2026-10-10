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

/** Packaged scenarios are the sole source of starting assets. */
object CampaignCatalog {
    const val construction = "foundation-02"
    val chapters = listOf("建造城邦", "发展国家", "掌握战争", "赢得胜利")
    val futureThemes = listOf("科技", "文化", "外交", "经济", "海洋", "宗教")
    fun levelId(number: Int) = "foundation-" + number.toString().padStart(2, '0')
    fun definitions() = (1..16).map { definition(levelId(it)) }
    fun historicalDefinitions() = listOf(definition(CivilWarCampaign.id))
    private fun folder(id: String): String {
        if (id == CivilWarCampaign.id) return "${CivilWarCampaign.folder}/levels/$id"
        require(id in (1..16).map(::levelId))
        return "campaigns/foundation/levels/$id"
    }

    fun definition(id: String = construction): CampaignDefinition = requireNotNull(
        FileConversions.readJson(Gdx.files.internal("${folder(id)}/level.json"), CampaignDefinition::class.java)
    ).also {
        require(it.id == id && it.number in 1..16 && it.population > 0)
    }

    fun template(id: String = construction): GameInfo = requireNotNull(
        FileConversions.readJson(Gdx.files.internal("${folder(id)}/initial-state.json"), GameInfo::class.java)
    ).also { game ->
        if (id == CivilWarCampaign.id) game.campaign = CampaignState().apply { definition = definition(id) }
        game.setTransients()
        game.campaign = null
        // The ordinary loader updates stats via cities; settler-only scenes also need their base values.
        game.civilizations.forEach { it.updateStatsForNextTurn() }
    }

    fun newAttempt(id: String = construction): GameInfo {
        val definition = definition(id)
        val game = template(id)
        require(game.campaign == null && game.turns == 0 && !game.gameParameters.isOnlineMultiplayer)
        require(game.gameParameters.mods.isEmpty() && game.gameParameters.noBarbarians)
        val player = game.civilizations.single { it.isHuman() }
        require(game.gameParameters.numberOfCityStates == 0 && game.tileMap.mapParameters.noRuins)
        require(if (definition.id != CivilWarCampaign.id && definition.number >= 15) game.gameParameters.victoryTypes.toSet() == setOf("Domination") else game.gameParameters.victoryTypes.isEmpty())
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
            rulesVersion = rulesFingerprint(id)
            playerId = player.civID
            capitalId = player.getCapital()?.id.orEmpty()
            initialCities.addAll(player.cities.map { it.id })
            if (definition.number == 12) protectedUnits.addAll(player.units.getCivUnits()
                .filter { it.name in definition.siegeUnits }.map { it.id })
        }
        game.tileMap.mapParameters.seed = game.campaign!!.seed
        return game
    }

    fun requireCompatible(game: GameInfo) {
        val state = game.campaign ?: return
        if (state.schemaVersion != 1 || state.evaluatorVersion != 1 || state.engineVersion != UncivGame.VERSION.toString() ||
            state.rulesVersion != rulesFingerprint(state.definition.id) ||
            state.definition.id !in (1..16).map(::levelId) + CivilWarCampaign.id ||
            state.definition.id == CivilWarCampaign.id && (state.definition.configVersion != definition(state.definition.id).configVersion ||
                state.definition.scoringVersion != definition(state.definition.id).scoringVersion))
            throw UncivShowableException(if (state.definition.id == CivilWarCampaign.id)
                "此战役存档与当前版本不兼容，请重新开始。"
                else "This campaign needs a compatibility update. Your save and results have been kept.")
    }

    private fun rulesFingerprint(id: String): String {
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
        if (id == CivilWarCampaign.id) add(Gdx.files.internal(CivilWarCampaign.folder))
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
