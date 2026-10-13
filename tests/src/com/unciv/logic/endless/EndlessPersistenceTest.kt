package com.unciv.logic.endless

import com.badlogic.gdx.Gdx
import com.unciv.UncivGame
import com.unciv.logic.challenges.ChallengeTelemetry
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
class EndlessPersistenceTest {
    @get:Rule val temporary = TemporaryFolder()
    @Before fun setup() { TestGame() }
    private fun game() = legacyEndlessAttempt(1101).also { UncivGame.Current.gameInfo = it }
    private fun store() = EndlessStore(UncivFiles(Gdx.files, temporary.root.path))

    @Test fun `reward telemetry is emitted only after a persisted successful claim`() {
        val game = game(); val store = store()
        game.endless!!.clearedPrefix = 5; EndlessRules.settleBenefits(game); store.start(game, null)
        val claims = arrayListOf<Pair<Int, String>>()
        ChallengeTelemetry.observer = { action, snapshot, detail ->
            if (action == "reward") {
                assertEquals(snapshot.rewardStage, store.saved()!!.endless!!.claimedStages)
                claims.add(snapshot.rewardStage to detail)
            }
        }
        try {
            val obstruction = store.slot.sibling(".current.json.unciv-tmp")
            obstruction.mkdirs(); obstruction.child("occupied").writeString("test obstruction", false)
            assertTrue(runCatching { store.claim(game, "Gold") }.isFailure)
            assertTrue(claims.isEmpty())
            obstruction.deleteDirectory(); store.save(game)
            store.claim(game, "Gold")
            assertEquals(listOf(1 to "Gold"), claims)
        } finally { ChallengeTelemetry.observer = null }
    }

    @Test fun `failed reward publication keeps prior bytes and gives no live benefit`() {
        val game = game(); val store = store()
        game.endless!!.clearedPrefix = 5; EndlessRules.settleBenefits(game); store.start(game, null)
        val bytes = store.slot.readBytes(); val gold = EndlessRules.player(game).gold
        val obstruction = store.slot.sibling(".current.json.unciv-tmp")
        obstruction.mkdirs(); obstruction.child("occupied").writeString("test obstruction", false)
        assertTrue(runCatching { store.claim(game, "Gold") }.isFailure)
        assertArrayEquals(bytes, store.slot.readBytes())
        assertEquals(gold, EndlessRules.player(game).gold)
        assertEquals(0, game.endless!!.claimedStages); assertTrue(game.endless!!.saveBlocked)
        val round = game.endless!!.round; game.nextTurn(); assertEquals(round, game.endless!!.round)
        obstruction.deleteDirectory()
        store.save(game)
        assertFalse(game.endless!!.saveBlocked)
        val claimed = store.claim(game, "Gold")
        assertEquals(gold + 80, EndlessRules.player(claimed).gold)
        assertEquals(1, store.resume().endless!!.claimedStages)
    }

    @Test fun `result journal survives failed record write and restart repairs it once`() {
        val game = game(); val store = store(); store.start(game, null)
        val records = store.slot.sibling("records.json")
        records.mkdirs(); records.child("occupied").writeString("test obstruction", false)
        game.endless!!.completedRounds = 27
        EndlessRules.loseCore(game, game.endless!!.coreId)
        assertTrue(runCatching { store.save(game) }.isFailure)
        assertEquals("Defeated", store.saved()!!.endless!!.status)
        records.deleteDirectory()
        store.resume(); store.resume()
        assertEquals(1, store.records().recent.size)
        assertEquals(27, store.records().best.getValue(game.endless!!.recordKey).rounds)
    }

    @Test fun `replacement records a quit and rejects an expired confirmation`() {
        val first = game(); val store = store(); store.start(first, null)
        first.endless!!.completedRounds = 9; store.save(first)
        val next = game(); store.start(next, first.endless!!.attemptId)
        assertEquals("Quit", store.records().recent.single().status)
        assertEquals(9, store.records().recent.single().rounds)
        assertTrue(store.records().best.isEmpty())
        val bytes = store.slot.readBytes()
        assertTrue(runCatching { store.start(game(), first.endless!!.attemptId) }.isFailure)
        assertArrayEquals(bytes, store.slot.readBytes())
    }

    @Test fun `newer snapshot wins and unstable boundaries cannot be written`() {
        val game = game(); val store = store(); store.start(game, null)
        val first = store.snapshot(game)
        game.endless!!.completedRounds = 1
        val second = store.snapshot(game)
        store.saveSnapshot(second); store.saveSnapshot(first)
        assertEquals(1, store.resume().endless!!.completedRounds)
        val bytes = store.slot.readBytes()
        game.endless!!.inTurn = true
        assertTrue(runCatching { store.snapshot(game) }.isFailure)
        game.endless!!.inTurn = false; game.endless!!.actionDepth = 1
        assertTrue(runCatching { store.snapshot(game) }.isFailure)
        assertArrayEquals(bytes, store.slot.readBytes())
    }

    @Test fun `atomic backup recovery restores only the complete previous snapshot`() {
        val game = game(); val store = store(); store.start(game, null)
        val bytes = store.slot.readBytes()
        val backup = store.slot.sibling(".current.json.unciv-backup")
        assertTrue(store.slot.file().renameTo(backup.file()))
        store.slot.sibling(".current.json.unciv-tmp").writeString("partial", false)
        assertEquals(game.endless!!.attemptId, store.resume().endless!!.attemptId)
        assertArrayEquals(bytes, store.slot.readBytes())
    }

    @Test fun `recent retention does not discard an older better record or merge rule versions`() {
        val store = store(); var previous: String? = null
        repeat(23) { index ->
            val g = game(); store.start(g, previous)
            g.endless!!.completedRounds = if (index == 0) 100 else index
            if (index == 22) g.endless!!.balanceVersion = 999
            EndlessRules.loseCore(g, g.endless!!.coreId); store.save(g)
            previous = g.endless!!.attemptId
        }
        val records = store.records()
        assertEquals(20, records.recent.size); assertEquals(2, records.best.size)
        assertEquals(100, records.best.getValue(EndlessCatalog.legacyRecordKey).rounds)
        assertFalse(records.recent.any { it.rounds == 100 })
    }

    @Test fun `save isolation and mode clone preserve bounded pending identities`() {
        val files = UncivFiles(Gdx.files, temporary.root.path)
        val normal = files.getLocalFile("SaveFiles/Autosave"); normal.parent().mkdirs(); normal.writeString("ordinary", false)
        val campaign = files.getLocalFile("Campaigns/untouched"); campaign.parent().mkdirs(); campaign.writeString("campaign", false)
        val g = game(); g.endless!!.recordEligible = false
        for (round in 1..1000) {
            g.endless!!.completedRounds = round - 1
            EndlessRules.prepareRound(g)
        }
        val store = EndlessStore(files); store.start(g, null)
        files.saveGame(g, "QuickSave")
        val copy = store.resume()
        assertEquals(com.unciv.json.json().toJson(g.endless), com.unciv.json.json().toJson(copy.endless))
        assertTrue(copy.endless!!.activeCount <= 40); assertTrue(copy.endless!!.pendingCount <= 16)
        assertTrue(com.unciv.json.json().toJson(copy.endless).toByteArray().size < 256 * 1024)
        assertEquals("ordinary", normal.readString()); assertEquals("campaign", campaign.readString())
        assertFalse(files.getLocalFile("SaveFiles/QuickSave").exists())
    }

    @Test fun `invalid registered member faults with the original bytes kept separately`() {
        val g = game(); val store = store()
        g.endless!!.completedRounds = 5; EndlessRules.prepareRound(g)
        g.endless!!.waves.first().members.first().unitId = 999999
        store.start(g, null)
        val original = store.slot.readBytes()
        assertTrue(runCatching { store.resume() }.isFailure)
        val saved = store.saved()!!.endless!!
        assertEquals("Faulted", saved.status); assertFalse(saved.recordEligible)
        assertArrayEquals(original, store.slot.sibling("Diagnostics").child("${saved.attemptId}.json").readBytes())
        assertTrue(store.records().recent.isEmpty())
    }

    @Test fun `omitted legacy balance field remains version one`() {
        val state = com.unciv.json.json().fromJson(EndlessState::class.java, "{}")
        assertEquals(1, state.balanceVersion)
        val g = game(); val store = store(); store.start(g, null)
        assertEquals(EndlessCatalog.balanceVersion, store.resume().endless!!.balanceVersion)
        assertNotEquals(state.recordKey, EndlessCatalog.legacyRecordKey)
    }
}
