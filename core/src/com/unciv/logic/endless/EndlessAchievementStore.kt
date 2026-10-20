package com.unciv.logic.endless

import com.unciv.logic.GameInfo
import com.unciv.logic.files.FileConversions
import com.unciv.logic.files.SaveFileLocks
import com.unciv.logic.files.SaveFileWriter
import com.unciv.logic.files.UncivFiles

/** Permanent local honors, separate from the bounded recent-results list and free-play accounts. */
class EndlessAchievementStore(private val files: UncivFiles) {
    private val file get() = files.getLocalFile("Endless/achievements.json")
    fun read(): EndlessHonorProfile = SaveFileLocks.withLock(file) {
        SaveFileWriter.recover(file)
        if (!file.exists()) EndlessHonorProfile() else requireNotNull(FileConversions.readJson(file, EndlessHonorProfile::class.java)).also {
            require(it.schemaVersion == 1)
            require(it.unlocks.all { (id, honor) -> id in EndlessAchievementRules.ids && honor.id == id &&
                honor.attemptId.isNotEmpty() && honor.recordingVersion == 1 && honor.pace in setOf("casual", "wild") &&
                honor.stage > 0 && honor.round > 0 && (id != "E01" || honor.stage == 1L) &&
                (id != "E03" || honor.cityIds.size == 3) && (id != "E04" || honor.pace == "wild" && honor.stage >= 3) })
        }
    }
    /** The caller has already committed this candidate in the stable game snapshot. */
    internal fun reconcileCommitted(game: GameInfo) = SaveFileLocks.withLock(file) {
        val s = game.endless ?: return@withLock
        if (game.gameParameters.godMode || game.simulateUntilWin || !EndlessAchievementRules.eligible(s)) return@withLock
        val profile = read()
        val candidates = s.honors!!.candidates.filter { (id, record) ->
            id in EndlessAchievementRules.ids && record.id == id && record.attemptId == s.attemptId &&
                record.recordingVersion == 1 && record.pace == s.stageState?.pace && record.stage > 0 &&
                record.stage <= (s.stageState?.completedStages ?: 0) && record.round > 0 &&
                (id != "E01" || record.stage == 1L) && (id != "E03" || record.cityIds.size == 3) &&
                (id != "E04" || record.pace == "wild" && record.stage >= 3)
        }
        if (candidates.keys.all { it in profile.unlocks }) return@withLock
        for ((id, record) in candidates) if (id !in profile.unlocks) profile.unlocks[id] =
            com.unciv.json.json().let { it.fromJson(EndlessHonor::class.java, it.toJson(record)) }.apply { persistedAt = System.currentTimeMillis() }
        file.parent().mkdirs()
        SaveFileWriter.write(file) { FileConversions.writeJson(it, profile, false) }
    }
}
