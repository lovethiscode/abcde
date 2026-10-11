package com.unciv.logic.endless

import com.badlogic.gdx.Gdx
import com.unciv.UncivGame
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
class EndlessShareTest {
    @get:Rule val temporary = TemporaryFolder()
    @Before fun setup() { TestGame() }
    private fun game() = EndlessCatalog.newAttempt(1101).also { UncivGame.Current.gameInfo = it }

    @Test fun `sharing counts only complete rounds and freezes the saved facts`() {
        val game = game(); val state = game.endless!!
        val store = EndlessStore(UncivFiles(Gdx.files, temporary.root.path))
        store.start(game, null)
        state.completedRounds = 13; state.clearedWaves = 2; state.kills = 9
        val card = store.shareSnapshot(game)
        assertEquals(14, state.round)
        assertEquals(13, card.rounds)
        assertEquals("Active", card.status)
        assertEquals(13, store.saved()!!.endless!!.completedRounds)
        state.completedRounds = 14; state.kills = 10
        assertEquals(13, card.rounds); assertEquals(9, card.kills)
        assertTrue(store.records().recent.isEmpty())
    }

    @Test fun `zero rounds invalid rules and unstable or faulty attempts cannot be shared`() {
        val state = game().endless!!
        assertNull(EndlessShareData.fromState(state))
        state.completedRounds = 1
        assertNotNull(EndlessShareData.fromState(state))
        for (bad in listOf<(EndlessState) -> Unit>(
            { it.inTurn = true }, { it.actionDepth = 1 }, { it.settling = true },
            { it.saveBlocked = true }, { it.recordEligible = false }, { it.status = "Faulted" },
            { it.kills = -1 }, { it.difficultyId = "unknown" }, { it.modeId = "other" },
        )) assertNull(EndlessShareData.fromState(state.clone().also(bad)))
    }

    @Test fun `a failed save cannot publish a share from newer live progress`() {
        val game = game(); val store = EndlessStore(UncivFiles(Gdx.files, temporary.root.path))
        game.endless!!.completedRounds = 2; store.start(game, null)
        val original = store.slot.readBytes()
        game.endless!!.completedRounds = 3
        val obstruction = store.slot.sibling(".current.json.unciv-tmp")
        obstruction.mkdirs(); obstruction.child("occupied").writeString("test obstruction", false)
        assertTrue(runCatching { store.shareSnapshot(game) }.isFailure)
        assertArrayEquals(original, store.slot.readBytes())
        assertTrue(game.endless!!.saveBlocked)
        obstruction.deleteDirectory(); store.save(game)
        assertEquals(3, store.shareSnapshot(game).rounds)
    }

    @Test fun `saved terminal result wins over a stale active snapshot without changing records`() {
        val game = game(); val store = EndlessStore(UncivFiles(Gdx.files, temporary.root.path))
        game.endless!!.completedRounds = 13; store.start(game, null)
        val stale = game.clone()
        store.quit(game)
        val before = store.slot.sibling("records.json").readBytes()
        val card = store.shareSnapshot(stale)
        assertEquals("Quit", card.status)
        assertEquals(13, card.rounds)
        assertArrayEquals(before, store.slot.sibling("records.json").readBytes())
        assertTrue(store.records().best.isEmpty())
    }

    @Test fun `history sharing preserves outcome and marks previous rules`() {
        val record = EndlessRecord().apply {
            key = EndlessCatalog.recordKey; rounds = 128; waves = 18; kills = 106; status = "Defeated"
        }
        val card = EndlessShareData.fromRecord(record)!!
        assertEquals("Defeated", card.status); assertFalse(card.previousRules)
        record.status = "Quit"; record.key = "endless-survival:1:standard:999:1"
        assertTrue(EndlessShareData.fromRecord(record)!!.previousRules)
        assertEquals("Defeated", card.status)
        record.status = "Active"; assertNull(EndlessShareData.fromRecord(record))
        record.status = "Faulted"; assertNull(EndlessShareData.fromRecord(record))
    }
}
