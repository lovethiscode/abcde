package com.unciv.logic.campaign

import com.badlogic.gdx.files.FileHandle
import com.unciv.logic.GameInfo
import com.unciv.logic.files.FileConversions
import com.unciv.logic.files.SaveFileLocks
import com.unciv.logic.files.SaveFileWriter
import com.unciv.logic.files.UncivFiles

/** Full games stay outside SaveFiles. Permanent progress is a separate, atomic document. */
class CampaignStore(private val files: UncivFiles) {
    private val root get() = files.getLocalFile("Campaigns").also { it.mkdirs() }
    private val progressFile get() = root.child("progress.json")

    fun slot(level: String): FileHandle {
        require(level.matches(Regex("[a-z0-9-]+")))
        return root.child("$level.json")
    }

    fun saved(level: String): GameInfo? = SaveFileLocks.withLock(slot(level)) {
        SaveFileWriter.recover(slot(level))
        if (!slot(level).exists()) null else requireNotNull(FileConversions.readJson(slot(level), GameInfo::class.java))
    }

    fun resume(level: String): GameInfo {
        val game = requireNotNull(saved(level))
        CampaignCatalog.requireCompatible(game)
        game.setTransients()
        reconcile(game)
        remember(level)
        return game
    }

    /** The confirmation refers to an exact previous attempt. Failed writes preserve its bytes. */
    fun start(game: GameInfo, replacingAttempt: String?) {
        val state = requireNotNull(game.campaign)
        SaveFileLocks.withLock(slot(state.definition.id)) {
            val old = saved(state.definition.id)
            check(old?.campaign?.attemptId == replacingAttempt) { "Campaign save changed; please reopen the level." }
            old?.let { previous ->
                reconcile(previous)
                try { CampaignCatalog.requireCompatible(previous) }
                catch (_: com.unciv.logic.UncivShowableException) {
                    if (state.definition.id != CivilWarCampaign.id) {
                        val archive = root.child("Archive").also { it.mkdirs() }
                            .child("${state.definition.id}-${previous.campaign!!.attemptId.replace(Regex("[^a-zA-Z0-9-]"), "_")}.json")
                        SaveFileWriter.write(archive) { FileConversions.writeJson(it, previous, zip = true) }
                    }
                }
            }
            write(game)
            remember(state.definition.id)
        }
    }

    companion object {
        // One entry per level, bounded by the catalog; persistent sequence survives process restarts.
        private val sequences = java.util.concurrent.ConcurrentHashMap<String, Long>()
    }

    fun snapshot(game: GameInfo): GameInfo {
        val state = requireNotNull(game.campaign)
        return SaveFileLocks.withLock(slot(state.definition.id)) {
            val persisted = saved(state.definition.id)?.campaign?.saveSequence ?: 0L
            state.saveSequence = maxOf(persisted, state.saveSequence, sequences[state.definition.id] ?: 0L) + 1
            sequences[state.definition.id] = state.saveSequence
            game.clone()
        }
    }

    fun save(game: GameInfo) = saveSnapshot(snapshot(game))

    fun saveSnapshot(game: GameInfo) {
        val state = requireNotNull(game.campaign)
        SaveFileLocks.withLock(slot(state.definition.id)) {
            val previous = saved(state.definition.id)
            val old = previous?.campaign
            // A delayed autosave may arrive after a restart or submission; never roll either back.
            if (old != null && (old.attemptId != state.attemptId || old.resultStars > 0 || old.failed ||
                    old.saveSequence >= state.saveSequence)) {
                reconcile(previous)
                return@withLock
            }
            write(game)
            reconcile(game)
        }
    }

    private fun write(game: GameInfo) {
        val target = slot(game.campaign!!.definition.id)
        SaveFileWriter.write(target) { FileConversions.writeJson(it, game, zip = true) }
    }

    fun progress(): CampaignProgress = SaveFileLocks.withLock(progressFile) {
        SaveFileWriter.recover(progressFile)
        if (!progressFile.exists()) CampaignProgress()
        else requireNotNull(FileConversions.readJson(progressFile, CampaignProgress::class.java)).also {
            it.validate()
        }
    }

    fun submit(game: GameInfo): Int {
        val state = requireNotNull(game.campaign)
        return SaveFileLocks.withLock(slot(state.definition.id)) {
            val prior = saved(state.definition.id)?.campaign
            check(prior == null || prior.attemptId == state.attemptId) { "This attempt has been replaced." }
            if (prior != null && prior.resultStars > 0) {
                game.campaign = prior
                reconcile(game)
                return@withLock prior.resultStars
            }
            if (prior?.failed == true) {
                game.campaign = prior
                error("This attempt has already ended. Start a new challenge.")
            }
            val result = CampaignRules.evaluate(game)
            check(result.stars > 0) { "Complete the main objective before submitting." }
            val snapshot = game.clone()
            CampaignRules.freeze(snapshot.campaign!!, result)
            // The frozen game is the recovery journal. Never expose a score before it is durable.
            write(snapshot)
            game.campaign = snapshot.campaign
            reconcile(game)
            result.stars
        }
    }

    /** Idempotent replay also recovers a crash between storing the result and storing progress. */
    fun reconcile(game: GameInfo) {
        val state = game.campaign ?: return
        if (state.resultStars == 0) return
        val changed = SaveFileLocks.withLock(progressFile) {
            val progress = progress()
            val before = progress.permanentCopy()
            progress.cleared.add(state.definition.id)
            if (state.definition.nextLevel.isNotEmpty()) progress.unlocked.add(state.definition.nextLevel)
            val key = "${state.definition.id}:${state.definition.scoringVersion}"
            progress.bestStars[key] = maxOf(progress.bestStars[key] ?: 0, state.resultStars)
            completeDerivedProgress(progress)
            if (before.samePermanentFacts(progress)) false else {
                SaveFileWriter.write(progressFile) { FileConversions.writeJson(it, progress, zip = false) }
                true
            }
        }
        if (changed) try { files.campaignProgressSync.onProgressSaved() }
        catch (ex: Exception) { com.unciv.utils.Log.error("Cannot schedule campaign progress sync", ex) }
    }

    /** Merge under the same lock as local settlements; never read or replace any resume slot. */
    fun mergeProgress(remote: CampaignProgress): CampaignProgress = SaveFileLocks.withLock(progressFile) {
        remote.validate()
        val progress = progress()
        val before = progress.permanentCopy()
        progress.merge(remote)
        completeDerivedProgress(progress)
        if (!before.samePermanentFacts(progress))
            SaveFileWriter.write(progressFile) { FileConversions.writeJson(it, progress, zip = false) }
        progress
    }

    private fun completeDerivedProgress(progress: CampaignProgress) {
        val definitions = CampaignCatalog.definitions()
        definitions.filter { it.id in progress.cleared }.forEach {
            if (it.nextLevel.isNotEmpty()) progress.unlocked.add(it.nextLevel)
        }
        for ((chapter, levels) in definitions.chunked(4).withIndex()) {
            if (levels.all { stars(it, progress) == 3 }) progress.medals.add(medalKey(chapter, levels))
        }
    }

    private fun remember(level: String) = SaveFileLocks.withLock(progressFile) {
        val progress = progress()
        progress.lastPlayed = level
        SaveFileWriter.write(progressFile) { FileConversions.writeJson(it, progress, zip = false) }
    }

    fun isUnlocked(definition: CampaignDefinition, progress: CampaignProgress): Boolean =
        definition.id == CivilWarCampaign.id || definition.number == 1 || definition.id in progress.cleared || definition.id in progress.unlocked ||
            CampaignCatalog.levelId(definition.number - 1) in progress.cleared

    fun stars(definition: CampaignDefinition, progress: CampaignProgress) =
        progress.bestStars["${definition.id}:${definition.scoringVersion}"] ?: 0

    fun medalKey(chapter: Int, definitions: List<CampaignDefinition>) =
        "$chapter:" + definitions.joinToString("-") { it.scoringVersion.toString() }
}
