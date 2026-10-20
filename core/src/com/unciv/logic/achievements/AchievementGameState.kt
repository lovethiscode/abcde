package com.unciv.logic.achievements

import com.unciv.logic.GameInfo
import com.unciv.logic.IsPartOfGameInfoSerialization
import com.unciv.logic.map.tile.Tile

/** Branch-local facts only. Permanent unlocks and disqualifications belong in the profile. */
class AchievementGameState : IsPartOfGameInfoSerialization {
    // Fixed defaults: Gdx omits default-valued fields, so loading must never upgrade a marker.
    var storageVersion = 0
    var catalogVersion = 0
    var recordingVersion = 0
    var gameId = ""
    var playerId = ""
    var civilization = ""
    var difficulty = ""
    var baseRuleset = ""
    var speed = ""
    var mapType = ""
    var aiOpponents = 0
    var cityStates = 0
    var religionEnabled = false
    var oneCityChallenge = false
    var availableIds = HashSet<String>()
    var enabledVictories = HashSet<String>()
    var availableUnits = HashSet<String>()
    var targetLandmasses = ArrayList<HashSet<String>>()
    var ended = false
    var history = AchievementHistory()
    var accountKey = ""
    var initializationComplete = false
    @Transient var actionDepth = 0

    fun clone() = AchievementGameState().also {
        it.storageVersion = storageVersion
        it.catalogVersion = catalogVersion
        it.recordingVersion = recordingVersion
        it.gameId = gameId
        it.playerId = playerId
        it.civilization = civilization
        it.difficulty = difficulty
        it.baseRuleset = baseRuleset
        it.speed = speed
        it.mapType = mapType
        it.aiOpponents = aiOpponents
        it.cityStates = cityStates
        it.religionEnabled = religionEnabled
        it.oneCityChallenge = oneCityChallenge
        it.availableIds.addAll(availableIds)
        it.enabledVictories.addAll(enabledVictories)
        it.availableUnits.addAll(availableUnits)
        targetLandmasses.mapTo(it.targetLandmasses) { land -> HashSet(land) }
        it.ended = ended
        it.history = history.clone()
        it.accountKey = accountKey
        it.initializationComplete = initializationComplete
    }

    fun canContribute(game: GameInfo, profile: AchievementProfile): Boolean =
        recordingVersion >= AchievementCatalog.firstCompleteRecordingVersion &&
            recordingVersion <= AchievementCatalog.recordingVersion &&
            catalogVersion in 3..AchievementCatalog.version && gameId == game.gameId && !ended && gameId !in profile.disqualifiedGames

    companion object {
        fun create(game: GameInfo, generatedMap: Boolean, hadMods: Boolean): AchievementGameState? {
            if (AchievementCatalog.newGameIneligibility(game, generatedMap, hadMods) != null) return null
            val player = game.civilizations.single { it.isHuman() }
            return AchievementGameState().apply {
                storageVersion = 1
                catalogVersion = AchievementCatalog.version
                recordingVersion = AchievementCatalog.recordingVersion
                gameId = game.gameId
                playerId = player.civID
                accountKey = AchievementTracker.service?.accountKey.orEmpty()
                civilization = player.nation.name
                difficulty = game.difficulty
                baseRuleset = game.gameParameters.baseRuleset
                speed = game.gameParameters.speed
                mapType = game.tileMap.mapParameters.type
                aiOpponents = game.civilizations.count { it.isAI() && it.isMajorCiv() }
                cityStates = game.civilizations.count { it.isCityState }
                religionEnabled = game.isReligionEnabled()
                oneCityChallenge = game.gameParameters.oneCityChallenge
                history.startTurn(game.turns)
                availableIds.addAll(AchievementCatalog.byId.keys)
                enabledVictories.addAll(game.gameParameters.victoryTypes)
                AchievementCatalog.definitions.mapNotNull { it.requiredUnit }
                    .filterTo(availableUnits) { it in game.ruleset.units }
            }
        }

        fun tileKey(tile: Tile) = "${tile.position.x},${tile.position.y}"

    }
}
