package com.unciv.logic.achievements

import com.unciv.json.json
import java.util.UUID

/** Immutable once submitted. The enclosing profile stores its original JSON, including future fields. */
class AchievementRecord {
    var recordId = UUID.randomUUID().toString()
    var persistedAt = 0L
    var gameId = ""
    var civilization = ""
    var difficulty = ""
    var victoryRoute = ""
    var achievementId = ""
    var turn = 0
    // Fixed missing-field defaults must not grant older records a newer catalog epoch.
    var catalogVersion = 0
    var ruleVersion = 0
    var recordingVersion = 0
    var shareTemplateVersion = 0
    var facts = HashMap<String, String>()
}

/** Independent of GameInfo: loading an earlier game cannot remove an award or a disqualification. */
class AchievementProfile {
    var storageVersion = 1
    var unlocks = HashMap<String, String>()
    var firstTerminals = HashMap<String, String>() // V4 first official outcome per game, including defeats/time victories.
    var victories = HashMap<String, String>() // Preserved V1 records; V2 does not use victory collections.
    var v2BuiltWonders = HashSet<String>()
    var wonderGameIds = HashSet<String>()
    var disqualifiedGames = HashSet<String>()
    var reportedIds = HashSet<String>()
    var claimedByAccount = ""

    fun clone() = AchievementProfile().also {
        it.storageVersion = storageVersion
        it.unlocks.putAll(unlocks)
        it.victories.putAll(victories)
        it.firstTerminals.putAll(firstTerminals)
        it.v2BuiltWonders.addAll(v2BuiltWonders)
        it.wonderGameIds.addAll(wonderGameIds)
        it.disqualifiedGames.addAll(disqualifiedGames)
        it.reportedIds.addAll(reportedIds)
        it.claimedByAccount = claimedByAccount
    }

    fun recordUnlock(record: AchievementRecord): Boolean {
        require(record.achievementId in AchievementCatalog.knownIds)
        if (record.achievementId in unlocks) return false
        unlocks[record.achievementId] = json().toJson(record)
        return true
    }

    /** This device keeps its first persisted candidate, even if a later branch wins differently. */
    fun recordVictory(record: AchievementRecord): Boolean {
        require(record.gameId.isNotEmpty() && record.victoryRoute in AchievementCatalog.victoryRoutes)
        if (record.gameId in victories) return false
        victories[record.gameId] = json().toJson(record)
        return true
    }

    /** Commutative cloud conflict resolution. Delivery acknowledgements remain local. */
    fun mergedWith(other: AchievementProfile) = clone().apply {
        storageVersion = maxOf(storageVersion, other.storageVersion)
        mergeRecords(unlocks, other.unlocks)
        mergeRecords(victories, other.victories)
        mergeRecords(firstTerminals, other.firstTerminals)
        disqualifiedGames.addAll(other.disqualifiedGames)
        v2BuiltWonders.addAll(other.v2BuiltWonders)
        wonderGameIds.addAll(other.wonderGameIds)
    }

    fun victoryTypes(): Set<String> = firstTerminals.mapNotNull { (gameId, value) ->
        runCatching { decode(value) }.getOrNull()?.takeIf {
            it.gameId == gameId && it.recordingVersion in 4..AchievementCatalog.recordingVersion &&
                it.catalogVersion in 4..AchievementCatalog.version && it.ruleVersion in 4..AchievementCatalog.version && it.facts["outcome"] == "win" &&
                it.victoryRoute in AchievementCatalog.victoryRoutes - "Time"
        }?.victoryRoute
    }.toSet()

    companion object {
        fun decode(value: String): AchievementRecord = json().fromJson(AchievementRecord::class.java, value)

        private fun mergeRecords(target: HashMap<String, String>, incoming: HashMap<String, String>) {
            for ((key, value) in incoming) {
                val previous = target[key]
                if (previous == null || compareRecords(value, previous) < 0) target[key] = value
            }
        }

        private fun compareRecords(a: String, b: String): Int {
            val left = decode(a)
            val right = decode(b)
            return compareValuesBy(left, right, { it.persistedAt }, { it.recordId })
                .takeIf { it != 0 } ?: a.compareTo(b)
        }
    }
}
