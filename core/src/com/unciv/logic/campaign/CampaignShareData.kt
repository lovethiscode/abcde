package com.unciv.logic.campaign

import com.badlogic.gdx.Gdx
import com.unciv.logic.files.FileConversions
import java.util.Collections

/** Presentation-only mappings live outside scenario assets so they cannot change save compatibility. */
class CampaignShareMetadata {
    var id = ""
    var configVersion = 0
    var scoringVersion = 0
    var theme = ""
    var title = ""
    var objectives = arrayListOf<String>()
    var roundKind = ""

    companion object {
        fun entries(): List<CampaignShareMetadata> = requireNotNull(FileConversions.readJson(
            Gdx.files.internal("campaign-share/metadata.json"), Array<CampaignShareMetadata>::class.java)).toList()
    }
}

enum class CampaignShareKind { Result, Best, Chapter, Journey }

class CampaignShareChapter(val title: String, val theme: String, val cleared: Int, val levels: Int, val stars: Int) {
    val complete get() = cleared == levels
    val mastery get() = stars == levels * 3
}

/** Detached facts only: no game, account, attempt ID, save, map or random seed reaches UIKit. */
class CampaignShareData private constructor(
    val kind: CampaignShareKind,
    val title: String,
    val theme: String,
    val stars: Int,
    val maxStars: Int,
    val cleared: Int = 0,
    val levels: Int = 1,
    goals: List<String> = emptyList(),
    val rounds: Int? = null,
    val fullRounds: Boolean = false,
    val previousRules: Boolean = false,
    val recent: Boolean = false,
    chapters: List<CampaignShareChapter> = emptyList()
) {
    val goals: List<String> = Collections.unmodifiableList(ArrayList(goals))
    val chapters: List<CampaignShareChapter> = Collections.unmodifiableList(ArrayList(chapters))

    companion object {
        private fun current(id: String) = (CampaignCatalog.definitions() + CampaignCatalog.historicalDefinitions())
            .firstOrNull { it.id == id }
        private fun theme(d: CampaignDefinition) = if (d.id == CivilWarCampaign.id) "history"
            else listOf("city", "nation", "war", "victory")[(d.number - 1) / 4]

        fun result(state: CampaignState, level: String, attempt: String, recent: Boolean = false): CampaignShareData? {
            val d = state.definition
            val current = current(level) ?: return null
            if (state.schemaVersion != 1 || state.evaluatorVersion != 1 || state.actionDepth != 0 || state.settling ||
                d.id != level || attempt.isBlank() || state.attemptId != attempt || state.failed ||
                state.resultStars !in 1..3 || state.completedRounds < 0 || d.title.isBlank() ||
                d.configVersion <= 0 || d.scoringVersion <= 0 ||
                state.resultStars != 1 + (if (state.resultWorker) 1 else 0) + (if (state.resultPopulation) 1 else 0)) return null
            val mapping = CampaignShareMetadata.entries().singleOrNull {
                it.id == level && it.configVersion == d.configVersion && it.scoringVersion == d.scoringVersion
            }
            val rounds = if (mapping == null) null else if (mapping.roundKind == "full")
                state.completedRounds.takeIf { it > 0 }
            else (state.resultActionRound ?: if (!d.automatic && state.completedRounds < Int.MAX_VALUE)
                state.completedRounds + 1 else null)?.takeIf {
                it > 0 && (d.hardTurnLimit <= 0 || it <= d.hardTurnLimit) &&
                    (it.toLong() == state.completedRounds.toLong() || it.toLong() == state.completedRounds.toLong() + 1)
            }
            val flags = listOf(true, state.resultWorker, state.resultPopulation)
            val goals = mapping?.objectives?.takeIf { it.size == 3 }?.filterIndexed { i, _ -> flags[i] }.orEmpty()
            return CampaignShareData(CampaignShareKind.Result, d.title, theme(current), state.resultStars, 3,
                goals = goals, rounds = rounds, fullRounds = mapping?.roundKind == "full", recent = recent,
                previousRules = d.configVersion != current.configVersion || d.scoringVersion != current.scoringVersion)
        }

        fun best(progress: CampaignProgress, level: String, scoringVersion: Int): CampaignShareData? {
            progress.validate()
            val d = current(level) ?: return null
            if (scoringVersion <= 0) return null
            val stars = progress.bestStars["$level:$scoringVersion"] ?: return null
            if (stars !in 1..3) return null
            return CampaignShareData(CampaignShareKind.Best, d.title, theme(d), stars, 3,
                previousRules = scoringVersion != d.scoringVersion)
        }

        private fun chapters(progress: CampaignProgress): List<CampaignShareChapter> {
            progress.validate()
            return CampaignCatalog.definitions().chunked(4).mapIndexed { index, levels ->
                CampaignShareChapter(CampaignCatalog.chapters[index], theme(levels.first()),
                    levels.count { it.id in progress.cleared }, levels.size,
                    levels.sumOf { progress.bestStars["${it.id}:${it.scoringVersion}"] ?: 0 })
            }
        }

        fun chapter(progress: CampaignProgress, index: Int): CampaignShareData? {
            val chapter = chapters(progress).getOrNull(index)?.takeIf { it.complete } ?: return null
            return CampaignShareData(CampaignShareKind.Chapter, chapter.title, chapter.theme,
                chapter.stars, chapter.levels * 3, chapter.cleared, chapter.levels, chapters = listOf(chapter))
        }

        fun journey(progress: CampaignProgress): CampaignShareData? {
            val chapters = chapters(progress)
            val cleared = chapters.sumOf { it.cleared }
            if (cleared == 0) return null
            val levels = chapters.sumOf { it.levels }
            return CampaignShareData(CampaignShareKind.Journey, "My civilization journey", "victory",
                chapters.sumOf { it.stars }, levels * 3, cleared, levels, chapters = chapters)
        }
    }
}
