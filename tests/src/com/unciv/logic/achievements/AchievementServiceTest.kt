package com.unciv.logic.achievements

import com.unciv.logic.VictoryData
import com.unciv.testing.BaseTestRunner
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File

@RunWith(BaseTestRunner::class)
class AchievementServiceTest {
    @get:Rule val temporary = TemporaryFolder()
    @After fun clearService() { AchievementTracker.service = null }

    @Test fun v2OfflineUnlockSurvivesRestartAndQueuesItsOwnBackendId() {
        val f = AchievementTestFixture()
        val directory = temporary.newFolder()
        val service = AchievementService(directory)
        service.record(f.game, setOf("N01"))
        assertEquals(setOf("N01"), AchievementService(directory).completed())
        assertEquals(setOf("N01"), service.pending())
        service.acknowledge(service.accountKey, setOf("N01"))
        assertTrue("N01" in AchievementStore(File(directory, "unbound")).load().reportedIds)
        assertTrue(AchievementService(directory).pending().isEmpty())
        service.record(f.game, setOf("N01"))
        assertEquals(setOf("N01"), service.completed())
    }

    @Test fun retiredAwardsRetainEvidenceButNeverEnterTheNewQueue() {
        val directory = temporary.newFolder()
        val legacy = AchievementRecord().apply { achievementId = "A01"; catalogVersion = 1; facts["kills"] = "3" }
        AchievementStore(File(directory, "unbound")).update { it.recordUnlock(legacy) }
        val service = AchievementService(directory)
        assertTrue(service.pending().isEmpty())
        service.record(AchievementTestFixture().game, setOf("N01"))
        assertEquals(setOf("A01", "N01"), service.completed())
        assertEquals(setOf("N01"), service.pending())
        val saved = AchievementStore(File(directory, "unbound")).load()
        assertEquals("3", AchievementProfile.decode(saved.unlocks.getValue("A01")).facts["kills"])
        service.acknowledge(service.accountKey, setOf("A01"))
        assertEquals(setOf("N01"), AchievementService(directory).pending())
        assertFalse("A01" in AchievementStore(File(directory, "unbound")).load().reportedIds)
    }

    @Test fun firstOfflineAccountIsClaimedOnceAndSwitchingDoesNotMoveV2Records() {
        val directory = temporary.newFolder()
        val service = AchievementService(directory)
        val guestGame = AchievementTestFixture().game
        service.record(guestGame, setOf("N01"))
        val first = service.bindAccount("player-one")
        AchievementTracker.service = service
        val ownedGame = AchievementTestFixture().game
        assertEquals(first, ownedGame.achievements!!.accountKey)
        val second = service.bindAccount("player-two")
        service.record(guestGame, setOf("N02"))
        service.record(ownedGame, setOf("N03"))
        service.restoreCompleted(first, setOf("A04"))
        assertTrue(service.completed().isEmpty())
        service.bindAccount("player-one")
        service.record(guestGame, setOf("N02"))
        service.record(ownedGame, setOf("N03"))
        assertEquals(setOf("N01", "N02", "N03"), service.completed())
        service.acknowledge(second, setOf("N01"))
        assertEquals(first, AchievementService(directory).accountKey)
    }

    @Test fun remoteRestorationAcceptsTheNewBackendCatalogAndIgnoresRetiredIds() {
        val service = AchievementService(temporary.newFolder())
        val key = service.bindAccount("player")
        service.restoreCompleted(key, AchievementCatalog.reportableIds + AchievementCatalog.legacyIds + "N99")
        assertEquals(AchievementCatalog.reportableIds, service.completed())
        assertTrue(service.pending().isEmpty())
        assertTrue(service.cloudFacts().v2BuiltWonders.isEmpty())
        assertTrue(service.cloudFacts().unlocks.isEmpty())
    }

    @Test fun twelveDistinctWorldWondersNeedThreeQualifiedGamesAndIgnoreDuplicateReloads() {
        val directory = temporary.newFolder()
        val service = AchievementService(directory)
        val first = AchievementTestFixture()
        val wonders = first.test.ruleset.buildings.values.filter { it.isWonder }.take(12)
        first.history.builtWonders.putAll(wonders.take(4).associate { it.name to "city" })
        service.record(first.game, emptySet())
        service.record(first.game.clone(), emptySet())
        assertEquals(4, service.cloudFacts().v2BuiltWonders.size)
        assertFalse("N26" in service.completed())
        val second = AchievementTestFixture()
        second.history.builtWonders.putAll(wonders.subList(4, 8).associate { it.name to "city" })
        second.state.recordingVersion = 1
        service.record(second.game, emptySet())
        assertEquals(4, service.cloudFacts().v2BuiltWonders.size)
        second.state.recordingVersion = AchievementCatalog.recordingVersion
        service.record(second.game, emptySet())
        assertFalse("N26" in service.completed())
        val third = AchievementTestFixture()
        third.history.builtWonders.putAll(wonders.takeLast(4).associate { it.name to "city" })
        service.record(third.game, emptySet())
        assertTrue("N26" in AchievementService(directory).completed())
        assertEquals(12, service.cloudFacts().v2BuiltWonders.size)
        assertEquals(3, service.cloudFacts().wonderGameIds.size)
        assertEquals(setOf("N26"), service.pending())
    }

    @Test fun mergingWonderFactsIsIdempotentAndAccountBound() {
        val service = AchievementService(temporary.newFolder())
        val key = service.bindAccount("player")
        val facts = AchievementProfile().apply {
            repeat(12) { v2BuiltWonders.add("wonder-$it") }
            repeat(3) { wonderGameIds.add("game-$it") }
        }
        service.mergeCloudFacts("stale-account", facts)
        assertTrue(service.completed().isEmpty())
        service.mergeCloudFacts(key, facts)
        service.mergeCloudFacts(key, facts)
        assertEquals(setOf("N26"), service.completed())
        assertEquals(12, service.cloudFacts().v2BuiltWonders.size)
    }

    @Test fun encyclopediaRequiresTheOtherThirtyNineAndNeverItselfOrLegacyAwards() {
        val directory = temporary.newFolder()
        val store = AchievementStore(File(directory, "unbound"))
        store.update { profile ->
            for (id in AchievementCatalog.legacyIds + (1..38).map { "N%02d".format(it) })
                profile.recordUnlock(AchievementRecord().apply { achievementId = id })
        }
        val service = AchievementService(directory)
        val key = service.bindAccount("player")
        assertFalse("N40" in service.completed())
        AchievementTracker.service = service
        val f = AchievementTestFixture().apply { state.difficulty = "Emperor" }
        service.record(f.game, setOf("N39"), "Cultural")
        assertTrue(service.completed().containsAll(AchievementCatalog.originalIds))
        assertEquals(40, service.completed().count { it.startsWith("N") })
        service.restoreCompleted(key, setOf("A01"))
        assertEquals(40, AchievementService(directory).completed().count { it.startsWith("N") })
    }

    @Test fun permanentDisqualificationSurvivesEarlierSaveAndRestartButPreservesEarnedAwards() {
        val directory = temporary.newFolder()
        val f = AchievementTestFixture()
        val oldBranch = f.game.clone()
        val service = AchievementService(directory)
        service.record(f.game, setOf("N01"))
        AchievementTracker.service = service
        AchievementTracker.disqualify(f.game)
        val restarted = AchievementService(directory)
        assertFalse(restarted.canContribute(oldBranch))
        restarted.record(oldBranch, setOf("N02"))
        assertEquals(setOf("N01"), restarted.completed())
    }

    @Test fun terminalOperationRecordsProcessStateAndVictoryBeforeClosingContribution() {
        val f = AchievementTestFixture()
        val service = AchievementService(temporary.newFolder())
        AchievementTracker.service = service
        f.history.completed.add("N32")
        f.capital.population.setPopulation(15)
        f.capital.cityConstructions.addBuilding("Library")
        f.capital.cityConstructions.addBuilding("University")
        f.game.victoryData = VictoryData(f.player.civID, "Scientific", f.game.turns)
        AchievementTracker.settle(f.game)
        assertTrue(f.state.ended)
        assertTrue(service.completed().containsAll(setOf("N01", "N11", "N17", "N32")))
        f.history.completed.add("N04")
        AchievementTracker.settle(f.game)
        assertFalse("N04" in service.completed())
    }

    @Test fun failedPersistenceRetriesTheTerminalFactsWithoutAnnouncingSuccess() {
        val directory = temporary.newFolder()
        val f = AchievementTestFixture()
        val service = AchievementService(directory)
        AchievementTracker.service = service
        val blocker = File(directory, "unbound")
        blocker.writeText("unavailable directory")
        f.history.completed.add("N07")
        f.game.victoryData = VictoryData(f.player.civID, "Time", f.game.turns)
        AchievementTracker.settle(f.game)
        assertFalse(f.state.ended)
        assertTrue(service.completed().isEmpty())
        assertTrue(blocker.delete())
        AchievementTracker.settle(f.game)
        assertTrue(f.state.ended)
        assertTrue(AchievementService(directory).completed().containsAll(setOf("N01", "N07")))
    }

    @Test fun importedAccountPathsAndOldCatalogCannotContribute() {
        val f = AchievementTestFixture()
        f.state.accountKey = "../../other-player"
        val service = AchievementService(temporary.newFolder())
        assertFalse(service.canContribute(f.game))
        service.record(f.game, setOf("N01"))
        assertTrue(service.completed().isEmpty())
        f.state.accountKey = ""
        for (version in listOf(0, 1, 2)) {
            f.state.catalogVersion = version
            assertFalse(service.canContribute(f.game))
        }
    }
}
