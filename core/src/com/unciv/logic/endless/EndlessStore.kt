package com.unciv.logic.endless

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
    private val recordsFile get() = root.child("records.json")

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
                val archive = root.child("Diagnostics").also { folder -> folder.mkdirs() }.child("${it.endless!!.attemptId}.json")
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
                val archive = root.child("Archive").also { it.mkdirs() }.child("${old.endless!!.attemptId}.json")
                SaveFileWriter.write(archive) { target -> FileConversions.writeJson(target, old, true) }
            }
            if (old.endless?.ended == false && runCatching { EndlessCatalog.requireCompatible(old) }.isSuccess) {
                old.endless!!.apply {
                    status = "Quit"
                    endedAt = System.currentTimeMillis()
                    offer = null
                    saveSequence = reserve(saveSequence)
                }
                write(old)
                reconcile(old)
            }
        }
        game.endless!!.saveSequence = 1
        write(game)
    }

    fun snapshot(game: GameInfo): GameInfo = SaveFileLocks.withLock(slot) {
        val s = requireNotNull(game.endless)
        check(!s.inTurn && s.actionDepth == 0) { "Only a stable endless boundary can be saved." }
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
        try { write(candidate) }
        catch (ex: Exception) { s.saveBlocked = true; throw ex }
        candidate
    }

    fun quit(game: GameInfo) {
        val s = requireNotNull(game.endless)
        check(!s.inTurn && !s.saveBlocked)
        if (!s.ended) { s.status = "Quit"; s.endedAt = System.currentTimeMillis(); s.offer = null }
        save(game)
    }

    private fun write(game: GameInfo) = SaveFileWriter.write(slot) { FileConversions.writeJson(it, game, true) }

    fun records(): EndlessRecords = SaveFileLocks.withLock(recordsFile) {
        SaveFileWriter.recover(recordsFile)
        if (!recordsFile.exists()) EndlessRecords() else requireNotNull(FileConversions.readJson(recordsFile, EndlessRecords::class.java)).also {
            require(it.schemaVersion == 1)
        }
    }

    fun reconcile(game: GameInfo) {
        val s = game.endless ?: return
        if (!s.ended || !s.recordEligible || s.status == "Faulted") return
        SaveFileLocks.withLock(recordsFile) {
            val records = records()
            if (records.recent.any { it.attemptId == s.attemptId } || records.best.values.any { it.attemptId == s.attemptId }) return@withLock
            val record = EndlessRecord().apply {
                attemptId = s.attemptId; key = s.recordKey; seed = s.seed
                rounds = s.completedRounds; waves = s.clearedWaves; kills = s.kills
                status = s.status; endedAt = s.endedAt
            }
            val best = records.best[record.key]
            val order = compareBy<EndlessRecord>({ it.rounds }, { it.waves }, { it.kills }, { -it.endedAt })
            if (record.status == "Defeated" && (best == null || order.compare(record, best) > 0)) records.best[record.key] = record
            records.recent.add(0, record)
            while (records.recent.size > 20) records.recent.removeAt(records.recent.lastIndex)
            SaveFileWriter.write(recordsFile) { FileConversions.writeJson(it, records, false) }
        }
    }
}
