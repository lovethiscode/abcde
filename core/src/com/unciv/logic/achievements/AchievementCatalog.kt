package com.unciv.logic.achievements

import com.unciv.logic.GameInfo
import com.unciv.logic.map.MapType
import com.unciv.models.metadata.BaseRuleset

enum class AchievementTier { Simple, Intermediate, Hard, Extreme }

data class AchievementDefinition(
    val id: String,
    val tier: AchievementTier,
    val minimumDifficulty: String? = null,
    val civilization: String? = null,
    val oneCityChallenge: Boolean = false,
    val requiredUnit: String? = null,
    val requiresReligion: Boolean = false,
    val isCollection: Boolean = false,
    val minimumOpponents: Int = 3,
    val minimumRecordingVersion: Int = 3,
    val mode: String = "free"
) {
    // Avoid the generated Boolean.hashCode(boolean), which RoboVM does not provide.
    override fun hashCode(): Int = id.hashCode()
}

/** Stable rule identities. Display strings and artwork are separate from eligibility. */
object AchievementCatalog {
    const val version = 4
    const val localPreview = true

    // V1/V2 saves lack the source facts required by this preview. Never upgrade them on load.
    const val recordingVersion = 4
    const val firstCompleteRecordingVersion = 3

    val difficulties = listOf("Settler", "Chieftain", "Warlord", "Prince", "King", "Emperor", "Immortal", "Deity")
    val victoryRoutes = setOf("Scientific", "Cultural", "Domination", "Diplomatic", "Time")

    val definitions = listOf(
        AchievementDefinition("N01", AchievementTier.Simple),
        AchievementDefinition("N02", AchievementTier.Simple),
        AchievementDefinition("N03", AchievementTier.Simple),
        AchievementDefinition("N04", AchievementTier.Intermediate, minimumDifficulty = "Prince"),
        AchievementDefinition("N05", AchievementTier.Simple),
        AchievementDefinition("N06", AchievementTier.Simple),
        AchievementDefinition("N07", AchievementTier.Intermediate, minimumDifficulty = "Prince"),
        AchievementDefinition("N08", AchievementTier.Intermediate, minimumDifficulty = "Prince"),
        AchievementDefinition("N09", AchievementTier.Simple),
        AchievementDefinition("N10", AchievementTier.Intermediate, minimumDifficulty = "Prince"),
        AchievementDefinition("N11", AchievementTier.Intermediate, minimumDifficulty = "Prince"),
        AchievementDefinition("N12", AchievementTier.Intermediate, minimumDifficulty = "Prince"),
        AchievementDefinition("N13", AchievementTier.Intermediate, minimumDifficulty = "Prince"),
        AchievementDefinition("N14", AchievementTier.Intermediate, minimumDifficulty = "Prince", requiresReligion = true),
        AchievementDefinition("N15", AchievementTier.Intermediate, minimumDifficulty = "Prince"),
        AchievementDefinition("N16", AchievementTier.Intermediate, minimumDifficulty = "Prince"),
        AchievementDefinition("N17", AchievementTier.Hard, minimumDifficulty = "King"),
        AchievementDefinition("N18", AchievementTier.Hard, minimumDifficulty = "King"),
        AchievementDefinition("N19", AchievementTier.Hard, minimumDifficulty = "King"),
        AchievementDefinition("N20", AchievementTier.Hard, minimumDifficulty = "King"),
        AchievementDefinition("N21", AchievementTier.Intermediate, minimumDifficulty = "Prince"),
        AchievementDefinition("N22", AchievementTier.Intermediate, minimumDifficulty = "Prince"),
        AchievementDefinition("N23", AchievementTier.Hard, minimumDifficulty = "King"),
        AchievementDefinition("N24", AchievementTier.Hard, minimumDifficulty = "King", requiresReligion = true),
        AchievementDefinition("N25", AchievementTier.Hard, minimumDifficulty = "King"),
        AchievementDefinition("N26", AchievementTier.Hard, minimumDifficulty = "King", isCollection = true),
        AchievementDefinition("N27", AchievementTier.Hard, minimumDifficulty = "King", civilization = "Egypt"),
        AchievementDefinition("N28", AchievementTier.Hard, minimumDifficulty = "King", civilization = "Rome"),
        AchievementDefinition("N29", AchievementTier.Hard, minimumDifficulty = "King"),
        AchievementDefinition("N30", AchievementTier.Extreme, minimumDifficulty = "Emperor", minimumOpponents = 4),
        AchievementDefinition("N31", AchievementTier.Hard, minimumDifficulty = "King"),
        AchievementDefinition("N32", AchievementTier.Hard, minimumDifficulty = "King"),
        AchievementDefinition("N33", AchievementTier.Hard, minimumDifficulty = "King", requiresReligion = true),
        AchievementDefinition("N34", AchievementTier.Extreme, minimumDifficulty = "Emperor", civilization = "China", requiredUnit = "Chu-Ko-Nu", minimumOpponents = 4),
        AchievementDefinition("N35", AchievementTier.Extreme, minimumDifficulty = "Emperor", civilization = "Persia", minimumOpponents = 4),
        AchievementDefinition("N36", AchievementTier.Extreme, minimumDifficulty = "Emperor", minimumOpponents = 4),
        AchievementDefinition("N37", AchievementTier.Extreme, minimumDifficulty = "Deity", minimumOpponents = 4),
        AchievementDefinition("N38", AchievementTier.Extreme, minimumDifficulty = "Emperor", oneCityChallenge = true, minimumOpponents = 4),
        AchievementDefinition("N39", AchievementTier.Extreme, minimumDifficulty = "Emperor", minimumOpponents = 4),
        AchievementDefinition("N40", AchievementTier.Extreme, isCollection = true),
        AchievementDefinition("F04", AchievementTier.Simple, minimumRecordingVersion = 4),
        AchievementDefinition("F05", AchievementTier.Simple, minimumRecordingVersion = 4),
        AchievementDefinition("F06", AchievementTier.Simple, minimumRecordingVersion = 4),
        AchievementDefinition("F07", AchievementTier.Simple, minimumRecordingVersion = 4),
        AchievementDefinition("F08", AchievementTier.Simple, minimumRecordingVersion = 4),
        AchievementDefinition("F09", AchievementTier.Simple, minimumRecordingVersion = 4),
        AchievementDefinition("R28", AchievementTier.Simple, civilization = "Rome", minimumRecordingVersion = 4),
        AchievementDefinition("R34", AchievementTier.Simple, civilization = "China", requiredUnit = "Chu-Ko-Nu", minimumRecordingVersion = 4),
        AchievementDefinition("F13", AchievementTier.Simple, civilization = "Inca", minimumRecordingVersion = 4),
        AchievementDefinition("F14", AchievementTier.Simple, civilization = "Mongolia", requiredUnit = "Keshik", minimumRecordingVersion = 4),
        AchievementDefinition("F15", AchievementTier.Simple, isCollection = true, minimumRecordingVersion = 4),
        AchievementDefinition("E01", AchievementTier.Simple, minimumRecordingVersion = 4, mode = "endless"),
        AchievementDefinition("E03", AchievementTier.Simple, minimumRecordingVersion = 4, mode = "endless"),
        AchievementDefinition("E04", AchievementTier.Simple, minimumRecordingVersion = 4, mode = "endless")
    )
    val byId = definitions.associateBy { it.id }
    /** A closed collection: adding a new rule must never expand the original encyclopedia. */
    val originalDependencies = (1..39).map { "N%02d".format(it) }.toSet()
    val originalIds = originalDependencies + "N40"

    // Retired V1 awards remain readable, but only the independent V2 catalog is submitted.
    val legacyIds = (1..40).mapTo(HashSet()) { "A%02d".format(it) }
    val reportableIds = originalIds
    val knownIds = byId.keys + legacyIds
    fun gameCenterId(id: String): String {
        require(id in reportableIds) { "This achievement has no Game Center mapping" }
        return "com.aishuati.unciv.achievement.${id.lowercase()}"
    }
    val civilizationAchievements = definitions.filter { it.civilization != null }.associate { it.id to it.civilization!! }

    fun meetsDifficulty(actual: String, minimum: String?): Boolean {
        val rank = difficulties.indexOf(actual)
        if (rank < 0) return false
        if (minimum == null) return true
        val minimumRank = difficulties.indexOf(minimum)
        return minimumRank >= 0 && rank >= minimumRank
    }

    /** Only called for a newly created game, before any save/load or compatibility migration. */
    fun newGameIneligibility(game: GameInfo, generatedMap: Boolean, hadMods: Boolean): String? {
        val parameters = game.gameParameters
        return when {
            hadMods || parameters.mods.isNotEmpty() -> "Mods are not supported"
            BaseRuleset.entries.none { it.fullName == parameters.baseRuleset } -> "A built-in ruleset is required"
            parameters.isOnlineMultiplayer -> "Single-player is required"
            game.civilizations.count { it.isHuman() } != 1 -> "Exactly one human player is required"
            game.civilizations.none { it.isHuman() && it.isMajorCiv } -> "Spectators cannot earn achievements"
            game.civilizations.none { it.isAI() && it.isMajorCiv } -> "At least one AI opponent is required"
            !generatedMap || game.tileMap.mapParameters.type !in MapType.allValues -> "A generated map is required"
            parameters.startingEra != "Ancient era" -> "An Ancient era start is required"
            parameters.godMode -> "God mode is not supported"
            game.simulateUntilWin -> "AI simulation is not supported"
            game.difficulty !in difficulties -> "A built-in difficulty is required"
            else -> null
        }
    }

    /** Initial per-achievement applicability; ordinary branch history is checked by its evaluator. */
    fun ineligibility(definition: AchievementDefinition, state: AchievementGameState): String? = when {
        state.recordingVersion < definition.minimumRecordingVersion -> "Start a new game to attempt this achievement"
        definition.mode != "free" -> "This achievement requires Endless defense"
        state.aiOpponents < 1 -> "At least one AI opponent is required"
        definition.id !in state.availableIds -> "Start a new game to attempt this achievement"
        !meetsDifficulty(state.difficulty, definition.minimumDifficulty) -> "The difficulty is too low"
        definition.minimumDifficulty != null && state.aiOpponents < definition.minimumOpponents -> "Too few AI opponents"
        definition.civilization != null && definition.civilization != state.civilization -> "A different civilization is required"
        definition.oneCityChallenge && !state.oneCityChallenge -> "One City Challenge is required"
        definition.requiredUnit != null && definition.requiredUnit !in state.availableUnits -> "This ruleset does not support the required unit"
        definition.requiresReligion && !state.religionEnabled -> "Religion must be enabled"
        else -> null
    }

    /** Read-only setup guidance, not an unlock predicate or a promise of future completion. */
    fun currentGameIneligibility(definition: AchievementDefinition, game: GameInfo?): String? {
        if (definition.id == "N40") return null // The collection belongs to the profile, across games.
        val state = game?.achievements
        if (state == null || state.recordingVersion !in firstCompleteRecordingVersion..recordingVersion || state.catalogVersion !in 3..version ||
            state.gameId != game.gameId)
            return "Start a new eligible single-player game"
        if (state.ended) return "This game no longer records achievements"
        ineligibility(definition, state)?.let { return it }
        val id = definition.id
        if (state.oneCityChallenge && id in setOf("N02", "N10", "N12", "N14", "N28", "N29", "F04", "R28"))
            return "One City Challenge prevents the required expansion"
        if (id == "N07" && game.gameParameters.noBarbarians) return "Barbarians are disabled"
        if (id == "N16" && game.tileMap.values.mapNotNull { it.naturalWonder }.toSet().size < 3)
            return "This map has fewer than three different natural wonders"
        val cityStates = game.civilizations.filter { it.isCityState }
        if (id == "N13" && cityStates.map { it.cityStateType }.toSet().size < 3 ||
            id == "N23" && cityStates.size < 4 ||
            id == "N25" && (cityStates.size < 5 || cityStates.map { it.cityStateType }.toSet().size < 3))
            return "This game has too few city-states or city-state types"
        val routes = when (id) {
            "N01" -> victoryRoutes
            "N17", "N38" -> setOf("Scientific")
            "N18", "N39" -> setOf("Cultural")
            "N19" -> setOf("Domination")
            "N20" -> setOf("Diplomatic")
            "N36", "N37" -> victoryRoutes - "Time"
            else -> emptySet()
        }
        if (routes.isNotEmpty() && routes.none { it in state.enabledVictories })
            return "The required victory route is disabled"
        return null
    }
}
