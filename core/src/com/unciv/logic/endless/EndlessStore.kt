package com.unciv.logic.endless

import com.unciv.logic.challenges.ChallengeTelemetry
import com.unciv.logic.GameInfo
import com.unciv.logic.files.FileConversions
import com.unciv.logic.files.SaveFileLocks
import com.unciv.logic.files.SaveFileWriter
import com.unciv.logic.files.UncivFiles
import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/** A local slot and result journal, independent of free play, campaign and cloud data. */
class EndlessStore(private val files: UncivFiles) {
    companion object {
        private var reservedSequence = 0L
        @Synchronized private fun reserve(after: Long): Long {
            reservedSequence = maxOf(reservedSequence, after) + 1
            return reservedSequence
        }
    }
    private val root get() = files.getLocalFile("Endless").also { it.mkdirs() }
    val slot get() = root.child("current.json")
    private fun recordsFile(v2: Boolean) = root.child(if (v2) "records-v2.json" else "records.json")

    fun saved(): GameInfo? = SaveFileLocks.withLock(slot) {
        SaveFileWriter.recover(slot)
        if (slot.exists()) readGame() else null
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun readGame(): GameInfo {
        val stored = slot.readString("UTF-8")
        // RoboVM's gzip reader can lose footer bytes when its inflater reads directly
        // through Kotlin's Base64 decoding stream. Decode that layer completely first.
        val text = if (stored.trimStart().startsWith("{")) stored else
            GZIPInputStream(ByteArrayInputStream(Base64.Default.decode(stored))).use {
                it.readBytes().toString(Charsets.UTF_8)
            }
        return requireNotNull(com.unciv.json.json().fromJson(GameInfo::class.java, text))
    }

    fun resume(): GameInfo = requireNotNull(saved()).also {
        EndlessCatalog.requireCompatible(it)
        try {
            it.setTransients()
            EndlessCatalog.validate(it)
        } catch (ex: Exception) {
            SaveFileLocks.withLock(slot) {
                check(saved()?.endless?.let { current -> current.attemptId == it.endless!!.attemptId && current.saveSequence == it.endless!!.saveSequence } == true)
                val archive = root.child("Diagnostics").also { folder -> folder.mkdirs() }.child(archiveName(it.endless!!.attemptId))
                if (!archive.exists()) SaveFileWriter.write(archive) { target -> target.writeBytes(slot.readBytes(), false) }
                EndlessRules.fault(it, "Invalid endless save state. The original snapshot has been preserved.")
                write(it)
            }
            throw com.unciv.logic.UncivShowableException("Invalid endless save state. The original snapshot has been preserved.", ex)
        }
        reconcile(it)
    }

    fun start(game: GameInfo, replacingAttempt: String?) = SaveFileLocks.withLock(slot) {
        val old = saved()
        check(old?.endless?.attemptId == replacingAttempt) { "The endless save changed. Reopen the hall." }
        if (old != null) {
            reconcile(old)
            // A replaced unfinished or incompatible attempt remains recoverable.
            if (old.endless?.ended != true || runCatching { EndlessCatalog.requireCompatible(old) }.isFailure) {
                val archive = root.child("Archive").also { it.mkdirs() }.child(archiveName(old.endless!!.attemptId))
                SaveFileWriter.write(archive) { target -> FileConversions.writeJson(target, old, true) }
            }
            if (old.endless?.ended == false && runCatching { EndlessCatalog.requireCompatible(old) }.isSuccess) {
                old.endless!!.apply {
                    status = "Quit"
                    stageState?.endReason = "Voluntary"
                    endedAt = System.currentTimeMillis()
                    offer = null
                    saveSequence = reserve(saveSequence)
                }
                write(old, "replaced")
                reconcile(old)
            }
        }
        game.endless!!.saveSequence = 1
        write(game)
    }

    fun snapshot(game: GameInfo): GameInfo = SaveFileLocks.withLock(slot) {
        val s = requireNotNull(game.endless)
        check(!s.inTurn && s.actionDepth == 0) { "Only a stable endless boundary can be saved." }
        check(s.pendingRewardCandidate == null) { "Retry the pending reward before saving another snapshot." }
        val old = saved()?.endless
        check(old == null || old.attemptId == s.attemptId) { "This endless attempt has been replaced." }
        s.saveSequence = reserve(maxOf(s.saveSequence, old?.saveSequence ?: 0))
        game.clone()
    }

    fun save(game: GameInfo) {
        try {
            saveSnapshot(snapshot(game))
            game.endless!!.saveBlocked = false
        } catch (ex: Exception) {
            game.endless!!.saveBlocked = true
            throw ex
        }
    }

    /** Share the durable snapshot, including a final result that already superseded the live game. */
    fun shareSnapshot(game: GameInfo): EndlessShareData = SaveFileLocks.withLock(slot) {
        requireNotNull(EndlessShareData.fromState(requireNotNull(game.endless)))
        save(game)
        val state = requireNotNull(saved()?.endless)
        check(state.attemptId == game.endless!!.attemptId)
        requireNotNull(EndlessShareData.fromState(state))
    }

    fun saveSnapshot(game: GameInfo) = SaveFileLocks.withLock(slot) {
        val s = requireNotNull(game.endless)
        val previous = saved()
        val old = previous?.endless
        if (old != null && (old.attemptId != s.attemptId || old.ended || old.saveSequence >= s.saveSequence)) {
            if (old.attemptId == s.attemptId && old.ended) reconcile(previous)
            return@withLock
        }
        write(game)
        reconcile(game)
    }

    /** Returning the candidate is the publication step. A failed write leaves the live game untouched. */
    fun claim(game: GameInfo, choice: String): GameInfo = SaveFileLocks.withLock(slot) {
        val s = requireNotNull(game.endless)
        check(!s.inTurn && !s.saveBlocked && !s.ended)
        val old = saved()?.endless
        check(old?.attemptId == s.attemptId && old.claimedStages == s.claimedStages && !old.ended)
        val candidate = game.clone()
        candidate.setTransients()
        EndlessRules.applyReward(candidate, choice)
        candidate.endless!!.saveSequence = reserve(maxOf(s.saveSequence, old.saveSequence))
        if (s.modeVersion == 2) {
            s.pendingRewardCandidate = candidate
            s.pendingRewardDigest = digest(candidate)
            s.pendingRewardChoice = choice
        }
        try { write(candidate) }
        catch (ex: Exception) { s.saveBlocked = true; throw ex }
        s.pendingRewardCandidate = null; s.pendingRewardDigest = ""; s.pendingRewardChoice = ""
        ChallengeTelemetry.report("reward", candidate, choice)
        candidate
    }

    /** Retry exactly the retained choice; a committed matching sequence is already successful. */
    fun retryReward(game: GameInfo): GameInfo = SaveFileLocks.withLock(slot) {
        val s = requireNotNull(game.endless)
        val candidate = requireNotNull(s.pendingRewardCandidate)
        val current = requireNotNull(saved())
        check(current.endless!!.attemptId == s.attemptId && !current.endless!!.ended)
        val target = candidate.endless!!
        if (current.endless!!.saveSequence >= target.saveSequence) {
            check(current.endless!!.saveSequence == target.saveSequence && digest(current) == s.pendingRewardDigest) { "The saved reward changed. Reopen the hall." }
        } else {
            check(current.endless!!.claimedStages == s.claimedStages && digest(candidate) == s.pendingRewardDigest)
            write(candidate)
        }
        ChallengeTelemetry.report("reward",candidate,s.pendingRewardChoice)
        s.pendingRewardCandidate = null; s.pendingRewardDigest = ""; s.pendingRewardChoice = ""; s.saveBlocked = false
        candidate
    }

    private fun digest(game: GameInfo): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(com.unciv.json.json().toJson(game).toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun archiveName(attempt: String): String = if (runCatching { java.util.UUID.fromString(attempt).toString() == attempt }.getOrDefault(false)) "$attempt.json"
        else java.security.MessageDigest.getInstance("SHA-256").digest(attempt.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) } + ".json"

    fun quit(game: GameInfo) {
        val s = requireNotNull(game.endless)
        check(!s.inTurn && !s.saveBlocked)
        if (!s.ended) { s.stageState?.apply { resultRound = s.round; endReason = "Voluntary" }; s.status = "Quit"; s.endedAt = System.currentTimeMillis(); s.offer = null }
        save(game)
    }

    private fun write(game: GameInfo, action: String = "saved") {
        SaveFileWriter.write(slot) { FileConversions.writeJson(it, game, true) }
        ChallengeTelemetry.report(action, game)
    }

    fun records(v2: Boolean = false): EndlessRecords = SaveFileLocks.withLock(recordsFile(v2)) {
        val recordsFile = recordsFile(v2)
        SaveFileWriter.recover(recordsFile)
        if (!recordsFile.exists()) EndlessRecords().apply { schemaVersion = if (v2) 2 else 1 } else requireNotNull(FileConversions.readJson(recordsFile, EndlessRecords::class.java)).also {
            require(it.schemaVersion == if (v2) 2 else 1)
        }
    }

    fun reconcile(game: GameInfo) {
        val s = game.endless ?: return
        if (!s.ended || !s.recordEligible || s.status == "Faulted") return
        val v2 = s.modeVersion == 2
        val recordsFile = recordsFile(v2)
        SaveFileLocks.withLock(recordsFile) {
            val records = records(v2)
            if (records.recent.any { it.attemptId == s.attemptId } || records.best.values.any { it.attemptId == s.attemptId }) return@withLock
            val record = EndlessRecord().apply {
                attemptId = s.attemptId; key = s.recordKey; seed = s.seed
                rounds = s.completedRounds; waves = s.clearedWaves; kills = s.kills
                status = s.status; endedAt = s.endedAt
                s.stageState?.let { state ->
                    stages = state.completedStages
                    partialWaves = if (state.phase == "Combat") s.waves.count { it.cleared } else 0
                    combatRounds = state.combatRounds; endReason = state.endReason
                }
            }
            val best = records.best[record.key]
            val order = if (v2) stageRecordOrder else compareBy<EndlessRecord>({ it.rounds }, { it.waves }, { it.kills }, { -it.endedAt })
            if (record.status == "Defeated" && (best == null || order.compare(record, best) > 0)) records.best[record.key] = record
            records.recent.add(0, record)
            while (records.recent.size > 20) records.recent.removeAt(records.recent.lastIndex)
            SaveFileWriter.write(recordsFile) { FileConversions.writeJson(it, records, false) }
        }
    }
}

val stageRecordOrder = compareBy<EndlessRecord>({ it.stages },{ it.partialWaves },{ -it.combatRounds })
