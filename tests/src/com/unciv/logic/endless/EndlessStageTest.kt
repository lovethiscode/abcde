package com.unciv.logic.endless

import com.badlogic.gdx.Gdx
import com.unciv.UncivGame
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
class EndlessStageTest {
    @get:Rule val temporary = TemporaryFolder()
    @Before fun setup() { TestGame(); UncivGame.Current.settings.citiesAutoBombardAtEndOfTurn = false }
    private fun game(pace: String = "casual") = EndlessCatalog.newAttempt(1101,pace).also { UncivGame.Current.gameInfo = it }
    private fun store() = EndlessStore(UncivFiles(Gdx.files,temporary.root.path))
    private fun prepare(game: GameInfo, round: Int) {
        game.endless!!.completedRounds = round - 1
        EndlessRules.prepareRound(game)
    }
    private fun clear(game: GameInfo) = AchievementTracker.action(game) {
        for (unit in EndlessRules.invaders(game).units.getCivUnits().toList()) {
            EndlessRules.combatDefeat(CityCombatant(EndlessRules.core(game)!!),unit)
            unit.destroy()
        }
    }
    private fun clearStage(game: GameInfo) {
        for (wave in game.endless!!.waves.toList()) { prepare(game,wave.round); clear(game) }
    }

    @Test fun `counter limits fail explicitly without wrapping plans pressure or rounds`() {
        assertEquals(10, EndlessStagePlanner.interval(Long.MAX_VALUE))
        assertEquals(0, EndlessStagePlanner.pressure(5))
        assertEquals(25, EndlessStagePlanner.pressure(10))
        assertEquals(2147483645, EndlessStagePlanner.pressure(429496734))
        assertTrue(runCatching { EndlessStagePlanner.pressure(429496735) }.exceptionOrNull() is ArithmeticException)
        assertTrue(runCatching { EndlessStagePlanner.plan(EndlessState(), Long.MAX_VALUE, 6) }.exceptionOrNull() is ArithmeticException)
        assertTrue(runCatching { EndlessStagePlanner.plan(EndlessState(), 1, Int.MAX_VALUE) }.exceptionOrNull() is ArithmeticException)
        val state = EndlessState().apply { completedRounds = Int.MAX_VALUE }
        assertTrue(runCatching { state.round }.exceptionOrNull() is ArithmeticException)
    }

    @Test fun `new identities require explicit pace while old defaults remain unchanged`() {
        val g = game(); val s = g.endless!!
        assertEquals(2,s.modeVersion); assertEquals(2,s.schemaVersion); assertEquals("casual",s.stageState!!.pace)
        assertEquals("endless-survival:2:2:standard:casual:2:2",s.recordKey)
        assertEquals(1,EndlessState().modeVersion); assertEquals(1,EndlessState().balanceVersion)
        assertNull(EndlessState().stageState)
        assertTrue(runCatching { EndlessCatalog.newAttempt(1,"unknown") }.isFailure)
        s.stageState!!.pace = ""
        assertTrue(runCatching { EndlessCatalog.requireCompatible(g) }.isFailure)
    }

    @Test fun `new opening can construct both basic military roles under real technology gates`() {
        val g = game(); val city = EndlessRules.core(g)!!
        for (name in listOf("Spearman","Archer")) {
            val unit = g.ruleset.units.getValue(name)
            assertTrue("$name ${unit.getRejectionReasons(city.cityConstructions).toList()}",unit.isBuildable(city.cityConstructions))
        }
    }

    @Test fun `engine opens first planned wave at round six and reload does not duplicate it`() {
        val g = game(); repeat(5) { g.nextTurn() }
        val s = g.endless!!
        assertEquals(6,s.round); assertEquals(5,s.completedRounds); assertEquals(3,s.activeCount)
        assertEquals(listOf(6,9,12,15,18),s.waves.map { it.round }); assertEquals(25,s.stageState!!.deadline)
        val store = store(); store.start(g,null)
        val resumed = store.resume(); EndlessRules.prepareRound(resumed)
        assertEquals(s.activeCount,resumed.endless!!.activeCount)
        assertEquals(s.waves.map { it.members.map { m -> m.unitId } },resumed.endless!!.waves.map { it.members.map { m -> m.unitId } })
    }

    @Test fun `early clear stays on schedule and a surviving prior wave does not delay later waves`() {
        val g = game(); prepare(g,6); clear(g)
        assertEquals("Combat",g.endless!!.stageState!!.phase); assertNull(g.endless!!.offer)
        assertEquals(0,g.endless!!.activeCount); assertEquals(9,g.endless!!.nextWaveRound)
        prepare(g,8); assertEquals(1,g.endless!!.waveNumber)
        prepare(g,9); prepare(g,12)
        assertEquals(3,g.endless!!.waveNumber); assertEquals(2,g.endless!!.waves.count { it.announced && !it.cleared })
        assertEquals(25,g.endless!!.stageState!!.deadline)
    }

    @Test fun `all five clear exactly once and casual supplies three complete rest rounds`() {
        val g = game(); clearStage(g); val s = g.endless!!
        assertEquals(1L,s.stageState!!.completedStages); assertEquals(13L,s.stageState!!.combatRounds)
        assertEquals(18,s.stageState!!.clearedAt); assertEquals(22,s.stageState!!.nextStageRound)
        assertEquals("RewardPending",s.stageState!!.phase); assertNotNull(s.offer)
        repeat(3) { EndlessRules.actionSettled(g) }; assertEquals(1L,s.stageState!!.completedStages)
        val store = store(); store.start(g,null); val claimed = store.claim(g,"Gold")
        for (r in 19..21) { prepare(claimed,r); assertEquals(0,claimed.endless!!.activeCount); assertEquals("Rest",claimed.endless!!.stageState!!.phase) }
        prepare(claimed,22)
        assertEquals(2L,claimed.endless!!.stageState!!.currentStage); assertEquals(5,claimed.endless!!.activeCount)
        assertEquals(listOf(22,26,30,34,38),claimed.endless!!.waves.map { it.round })
    }

    @Test fun `wild reward resumes the suspended same round preparation and refuses repairs`() {
        val g = game("wild")
        for (wave in g.endless!!.waves.take(4).toList()) { prepare(g,wave.round); clear(g) }
        prepare(g,18); g.endless!!.inTurn = true; clear(g); g.endless!!.inTurn = false
        assertNull(g.endless!!.offer); assertEquals("RewardPending",g.endless!!.stageState!!.phase)
        prepare(g,19)
        assertNotNull(g.endless!!.offer); assertEquals(18,g.endless!!.lastPreparedRound)
        EndlessRules.core(g)!!.health = 1
        val store = store(); store.start(g,null)
        assertFalse(EndlessRules.canRepair(g)); assertTrue(runCatching { store.claim(g,"Repair") }.isFailure)
        val awarded = store.claim(g,"Gold")
        assertEquals(19,awarded.endless!!.round); assertEquals(19,awarded.endless!!.lastPreparedRound)
        assertEquals(2L,awarded.endless!!.stageState!!.currentStage)
        assertEquals(5,awarded.endless!!.activeCount)
        val reloaded = store.resume(); EndlessRules.prepareRound(reloaded)
        assertEquals(5,reloaded.endless!!.activeCount)
    }

    @Test fun `deadline fails after last enemy turn without starting another player round`() {
        val g = game(); prepare(g,25)
        val before = g.endless!!.normalStartedForTest()
        g.nextTurn()
        assertEquals("Defeated",g.endless!!.status); assertEquals("StageTimeout",g.endless!!.stageState!!.endReason)
        assertEquals(25,g.endless!!.completedRounds); assertEquals(25,g.endless!!.round)
        assertEquals(before,g.endless!!.normalStartedForTest()); assertNull(g.endless!!.offer)
        assertEquals(0L,g.endless!!.stageState!!.combatRounds)
    }

    @Test fun `last permitted clear succeeds but core loss always wins`() {
        val g = game(); prepare(g,25); clear(g)
        assertEquals("RewardPending",g.endless!!.stageState!!.phase)
        assertEquals(20L,g.endless!!.stageState!!.combatRounds)
        EndlessRules.loseCore(g,g.endless!!.coreId); EndlessRules.actionSettled(g)
        assertEquals("Defeated",g.endless!!.status); assertEquals("CoreLost",g.endless!!.stageState!!.endReason)
        assertNull(g.endless!!.offer); val c = g.endless!!.completedRounds; g.nextTurn(); assertEquals(c,g.endless!!.completedRounds)
    }

    @Test fun `fixed plans respect role totals transition mixing elites and long stage clocks`() {
        for (seed in 1101L..1108L) {
            val s = EndlessState().apply { this.seed = seed }
            var prior = -1
            for (stage in 1L..1000L) {
                val waves = EndlessStagePlanner.plan(s,stage,1)
                assertEquals(EndlessStagePlanner.numbers(stage),waves.map { it.members.size })
                assertTrue(waves.sumOf { it.members.size } <= 54)
                for (w in waves) {
                    assertNotEquals(prior,w.mainSector); prior = w.mainSector
                    assertEquals(if (stage >= 3) 2 else if (stage == 2L) 1 else 0,w.members.count { it.promotion.isNotEmpty() })
                }
                assertEquals("Siege",waves.last().template)
                assertTrue(EndlessStagePlanner.duration(stage) <= 60)
            }
        }
        for (template in listOf("Frontal","Flank","Siege")) for (n in 5..12) assertEquals(n,EndlessStagePlanner.composition(n,template).sum())
        assertEquals(5,EndlessStagePlanner.pressure(6)); assertEquals(10,EndlessStagePlanner.pressure(7)); assertEquals(25,EndlessStagePlanner.pressure(10))
    }

    @Test fun `old snapshot continues old rules even with changed live base cache`() {
        val g = legacyEndlessAttempt(); val store = store(); store.start(g,null)
        val fingerprint = g.endless!!.fingerprint
        assertEquals(EndlessCatalog.fingerprint(),fingerprint)
        val base = com.unciv.models.ruleset.RulesetCache["Civ V - Gods & Kings"]!!
        val cost = base.units.getValue("Settler").cost
        try {
            base.units.getValue("Settler").cost = 99999
            val old = store.resume()
            assertEquals(106,old.ruleset.units.getValue("Settler").cost)
            assertEquals(1,old.endless!!.modeVersion); assertNull(old.endless!!.stageState)
            assertFalse(EndlessStageRules.canTrainSettler(EndlessRules.player(old)))
            repeat(5) { old.nextTurn() }; store.save(old)
            assertEquals(fingerprint,store.resume().endless!!.fingerprint)
        } finally { base.units.getValue("Settler").cost = cost }
    }

    @Test fun `failed reward retains exact candidate and blocks original snapshots until retry`() {
        val g = game(); clearStage(g); val store = store(); store.start(g,null)
        val before = store.slot.readBytes(); val gold = EndlessRules.player(g).gold
        val obstruction = store.slot.sibling(".current.json.unciv-tmp")
        obstruction.mkdirs(); obstruction.child("owned").writeString("test",false)
        assertTrue(runCatching { store.claim(g,"Gold") }.isFailure)
        val candidate = g.endless!!.pendingRewardCandidate!!
        assertArrayEquals(before,store.slot.readBytes()); assertEquals(gold,EndlessRules.player(g).gold)
        assertTrue(runCatching { store.snapshot(g) }.isFailure)
        obstruction.deleteDirectory()
        assertSame(candidate,store.retryReward(g))
        assertEquals(gold + 80,EndlessRules.player(store.resume()).gold)
        assertNull(g.endless!!.pendingRewardCandidate); assertFalse(g.endless!!.saveBlocked)
        assertTrue(runCatching { store.retryReward(g) }.isFailure)
    }

    @Test fun `committed reward with lost publication is recovered by sequence and digest without applying twice`() {
        val g = game("wild"); clearStage(g); val store = store(); store.start(g,null)
        val gold = EndlessRules.player(g).gold
        val obstruction = store.slot.sibling(".current.json.unciv-tmp")
        obstruction.mkdirs(); obstruction.child("owned").writeString("test",false)
        assertTrue(runCatching { store.claim(g,"Gold") }.isFailure)
        val candidate = g.endless!!.pendingRewardCandidate!!
        obstruction.deleteDirectory(); store.saveSnapshot(candidate)
        val committed = store.slot.readBytes()
        assertSame(candidate,store.retryReward(g))
        assertArrayEquals(committed,store.slot.readBytes())
        assertEquals(gold + 80,EndlessRules.player(store.resume()).gold)
    }

    @Test fun `new required state fields cannot silently adopt defaults after omission`() {
        val g = game(); val store = store(); store.start(g,null)
        for (missing in listOf("pace","phase","currentStage","completedStages","combatRounds","corePosition")) {
            val data = com.badlogic.gdx.utils.JsonReader().parse(com.unciv.json.json().toJson(g))
            data.get("endless").get("stageState").remove(missing)
            store.slot.writeString(data.toJson(com.badlogic.gdx.utils.JsonWriter.OutputType.json),false)
            val bytes = store.slot.readBytes()
            assertTrue("$missing must be rejected",runCatching { store.resume() }.isFailure)
            if (missing in setOf("pace","corePosition")) assertArrayEquals(bytes,store.slot.readBytes())
        }
    }

    @Test fun `new records separate paces preserve old records and compare progress before efficiency`() {
        val store = store()
        val old = legacyEndlessAttempt(); store.start(old,null); store.quit(old)
        val oldBytes = store.slot.sibling("records.json").readBytes()
        for (pace in listOf("casual","wild")) {
            val g = game(pace); store.start(g,store.saved()!!.endless!!.attemptId)
            clearStage(g); EndlessRules.loseCore(g,g.endless!!.coreId); store.save(g)
        }
        assertEquals(2,store.records(true).best.size); assertEquals(2,store.records(true).recent.size)
        assertArrayEquals(oldBytes,store.slot.sibling("records.json").readBytes())
        fun record(stage: Long, wave: Int, time: Long) = EndlessRecord().apply { stages = stage; partialWaves = wave; combatRounds = time }
        assertTrue(stageRecordOrder.compare(record(4,0,130),record(3,4,50)) > 0)
        assertTrue(stageRecordOrder.compare(record(3,2,68),record(3,2,72)) > 0)
        assertEquals(0,stageRecordOrder.compare(record(3,2,68),record(3,2,68)))
    }

    @Test fun `all five waves remain pending when every outer tile is legally occupied`() {
        val g = game(); g.endless!!.recordEligible = false; repeat(4) { index ->
            clearStage(g); EndlessRules.applyReward(g,"Gold")
            val next = g.endless!!.stageState!!.nextStageRound
            if (index < 3) prepare(g,next)
        }
        for (t in g.tileMap.values.filter { it.aerialDistanceTo(EndlessRules.core(g)!!.getCenterTile()) in 8..10 && it.civilianUnit == null })
            EndlessRules.player(g).units.placeUnitNearTile(t.position,"Worker")
        prepare(g,g.endless!!.stageState!!.nextStageRound)
        for (w in g.endless!!.waves.toList()) prepare(g,w.round)
        assertEquals(5,g.endless!!.waves.count { it.announced }); assertEquals(0,g.endless!!.activeCount)
        assertEquals(54,g.endless!!.pendingCount)
        assertEquals(54,g.endless!!.waves.sumOf { it.members.size })
        val store = store(); store.start(g,null); val reloaded = store.resume()
        assertEquals(54,reloaded.endless!!.pendingCount)
        val deadline = reloaded.endless!!.stageState!!.deadline
        prepare(reloaded,deadline); EndlessStageRules.roundCompleted(reloaded)
        assertEquals("StageTimeout",reloaded.endless!!.stageState!!.endReason)
        assertEquals(4L,reloaded.endless!!.stageState!!.completedStages)
    }

    private fun EndlessState.normalStartedForTest() = stageState!!.normalStartedRound
}
