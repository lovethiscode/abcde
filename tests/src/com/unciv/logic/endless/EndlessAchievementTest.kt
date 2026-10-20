package com.unciv.logic.endless

import com.badlogic.gdx.Gdx
import com.unciv.UncivGame
import com.unciv.json.json
import com.unciv.logic.GameInfo
import com.unciv.logic.achievements.AchievementTracker
import com.unciv.logic.battle.CityCombatant
import com.unciv.logic.files.UncivFiles
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

@RunWith(BaseTestRunner::class)
class EndlessAchievementTest {
    @get:Rule val temporary=TemporaryFolder()
    @Before fun setup(){TestGame();UncivGame.Current.settings.citiesAutoBombardAtEndOfTurn=false}
    private val files get()=UncivFiles(Gdx.files,temporary.root.path)
    private fun game(pace:String="casual")=EndlessCatalog.newAttempt(1201,pace).also{UncivGame.Current.gameInfo=it}
    private fun prepare(game:GameInfo,round:Int){game.endless!!.completedRounds=round-1;EndlessRules.prepareRound(game)}
    private fun clear(game:GameInfo)=AchievementTracker.action(game){
        for(unit in EndlessRules.invaders(game).units.getCivUnits().toList()){
            EndlessRules.combatDefeat(CityCombatant(EndlessRules.core(game)!!),unit);unit.destroy()
        }
    }
    private fun stage(game:GameInfo){for(w in game.endless!!.waves.toList()){prepare(game,w.round);clear(game)}}
    private fun reward(game:GameInfo){EndlessRules.applyReward(game,"Gold");prepare(game,game.endless!!.stageState!!.nextStageRound)}
    @Test fun candidateIsNotPermanentUntilAStableSnapshotCommitsAndSurvivesReload(){
        val g=game();val store=EndlessStore(files);store.start(g,null);stage(g)
        assertTrue("E01" in g.endless!!.honors!!.candidates)
        assertTrue(EndlessAchievementStore(files).read().unlocks.isEmpty())
        // Reconcile cannot publish an uncommitted live candidate.
        store.reconcile(g);assertTrue(EndlessAchievementStore(files).read().unlocks.isEmpty())
        store.save(g);assertEquals(setOf("E01"),EndlessAchievementStore(files).read().unlocks.keys)
        val record=EndlessAchievementStore(files).read().unlocks.getValue("E01")
        assertEquals(g.endless!!.attemptId,record.attemptId)
        store.resume();store.save(g);assertEquals(setOf("E01"),EndlessAchievementStore(files).read().unlocks.keys)
    }
    @Test fun brutalThreeStagesCountWithinOneAttemptAndCasualNeverAwardsE04(){
        for(pace in listOf("casual","wild")){
            val g=game(pace);repeat(3){i->stage(g);if(i<2)reward(g)}
            assertEquals(pace=="wild","E04" in g.endless!!.honors!!.candidates)
        }
    }
    private fun unlockThreeCities(g:GameInfo) {
        repeat(4) {
            if(g.endless!!.stageState!!.phase=="Rest") prepare(g,g.endless!!.stageState!!.nextStageRound)
            stage(g);EndlessRules.applyReward(g,"Gold")
        }
        val player=EndlessRules.player(g)
        for(point in listOf(com.unciv.logic.map.HexCoord(-4,-4),com.unciv.logic.map.HexCoord(4,4))) {
            val settler=player.units.placeUnitNearTile(point,"Settler")!!
            assertEquals(point,settler.currentTile.position)
            player.addCity(point,settler)
        }
        prepare(g,g.endless!!.stageState!!.nextStageRound)
        assertEquals(3,g.endless!!.honors!!.cities.size)
    }
    @Test fun threeCityWindowLocksBeforeWaveOneAndRemembersAnyOwnershipInterruption(){
        val g=game();unlockThreeCities(g)
        val city=EndlessRules.player(g).cities.first{it.id!=g.endless!!.coreId}
        EndlessAchievementRules.cityLost(g,city.id,g.endless!!.playerId,g.endless!!.invaderId)
        assertTrue(g.endless!!.honors!!.ownershipBroken)
        stage(g);assertFalse("E03" in g.endless!!.honors!!.candidates)
    }
    @Test fun eligibleThreeCityStageAwardsAndOldRecordingNeverInventsEvidence(){
        val g=game();unlockThreeCities(g);stage(g)
        assertTrue("E03" in g.endless!!.honors!!.candidates)
        val old=game();old.endless!!.honors=null;stage(old);assertNull(old.endless!!.honors)
    }
    @Test fun ledgerFailureKeepsDurableCandidateForResumeRetry(){
        val g=game();val store=EndlessStore(files);store.start(g,null);stage(g)
        val path=files.getLocalFile("Endless/achievements.json");path.mkdirs()
        assertTrue(runCatching{store.save(g)}.isFailure)
        assertTrue(store.saved()!!.endless!!.honors!!.candidates.containsKey("E01"))
        path.deleteDirectory();store.resume()
        assertTrue("E01" in EndlessAchievementStore(files).read().unlocks)
    }
    @Test fun rewardRetryPublishesTheSameCommittedCandidateAfterHonorLedgerFailure(){
        val g=game();val store=EndlessStore(files);store.start(g,null);stage(g)
        val path=files.getLocalFile("Endless/achievements.json");path.mkdirs()
        assertTrue(runCatching{store.claim(g,"Gold")}.isFailure)
        val candidate=g.endless!!.pendingRewardCandidate!!
        assertEquals(0L,candidate.endless!!.honors!!.candidates.getValue("E01").persistedAt)
        path.deleteDirectory();val published=store.retryReward(g)
        assertEquals(candidate.endless!!.claimedStages,published.endless!!.claimedStages)
        assertTrue(EndlessAchievementStore(files).read().unlocks.getValue("E01").persistedAt>0)
        assertEquals(0L,published.endless!!.honors!!.candidates.getValue("E01").persistedAt)
    }
    @Test fun faultedOrIneligibleSnapshotsNeverCreateNewPermanentHonors(){
        val g=game();val store=EndlessStore(files);store.start(g,null);stage(g)
        g.endless!!.recordEligible=false;store.save(g)
        assertTrue(EndlessAchievementStore(files).read().unlocks.isEmpty())
    }
    @Test fun historiesCloneWithoutSharingCandidatesOrOwnershipWindows(){
        val g=game();stage(g);val copy=g.clone()
        copy.endless!!.honors!!.candidates.clear()
        assertTrue("E01" in g.endless!!.honors!!.candidates)
        assertEquals(0,json().fromJson(EndlessAchievementHistory::class.java,"{}").recordingVersion)
    }
}
