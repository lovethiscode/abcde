package com.unciv.ui.screens.mapeditorscreen

import com.unciv.logic.map.TileMap
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.RulesetCache
import com.unciv.testing.GdxTestRunner
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(GdxTestRunner::class)
class MapEditorHistoryTest {
    private lateinit var map: TileMap
    @Before fun setup() {
        RulesetCache.loadRulesets(noMods = true)
        map = TileMap(2, RulesetCache[BaseRuleset.Civ_V_GnK.fullName]!!, false)
    }
    @Test fun oneStrokeRestoresTerrainRiversAndStartingLocationsTogether() {
        val history = MapEditorHistory().apply { reset(map); begin(map) }
        val tile = map.values.first()
        val original = tile.baseTerrain
        tile.baseTerrain = "Desert"
        tile.hasBottomRiver = true
        map.startingLocations.add(TileMap.StartingLocation(tile.position, "Rome", TileMap.StartingLocation.Usage.Human))
        assertTrue(history.commit(map))
        val undone = history.undo(map)!!
        assertEquals(original, undone.tileList.first().baseTerrain)
        assertFalse(undone.tileList.first().hasBottomRiver)
        assertTrue(undone.startingLocations.isEmpty())
        assertTrue(history.isSaved(undone))
        val redone = history.redo(undone)!!
        assertEquals("Desert", redone.tileList.first().baseTerrain)
        assertTrue(redone.tileList.first().hasBottomRiver)
        assertEquals(TileMap.StartingLocation.Usage.Human, redone.startingLocations.single().usage)
    }
    @Test fun noOpDoesNotConsumeHistoryOrDiscardRedo() {
        val history = MapEditorHistory().apply { reset(map); begin(map) }
        map.tileList.first().baseTerrain = "Desert"
        history.commit(map)
        map = history.undo(map)!!
        history.begin(map)
        assertFalse(history.commit(map))
        assertTrue(history.canRedo)
        history.begin(map); map.tileList.first().baseTerrain = "Snow"; history.commit(map)
        assertFalse(history.canRedo)
    }
    @Test fun aSavedRevisionBecomesCleanWhenUndoneBackToIt() {
        val history = MapEditorHistory().apply { reset(map); begin(map) }
        map.tileList.first().baseTerrain = "Desert"; history.commit(map); history.markSaved(map)
        history.begin(map); map.tileList.first().baseTerrain = "Snow"; history.commit(map)
        assertFalse(history.isSaved(map))
        assertTrue(history.isSaved(history.undo(map)!!))
    }
    @Test fun assigningContinentsForSaveDoesNotDirtyTheDocument() {
        val history = MapEditorHistory().apply { reset(map) }
        val savedCopy = map.clone().apply { setTransients(setUnitCivTransients = false); assignContinents(TileMap.AssignContinentsMode.Reassign) }
        history.markSaved(savedCopy)
        assertTrue(history.isSaved(map))
    }
    @Test fun boundedHistoryKeepsTheMostRecentTransactions() {
        val history = MapEditorHistory(maxEntries = 2).apply { reset(map) }
        for (terrain in listOf("Desert", "Snow", "Plains")) {
            history.begin(map); map.tileList.first().baseTerrain = terrain; history.commit(map)
        }
        map = history.undo(map)!!
        assertEquals("Snow", map.tileList.first().baseTerrain)
        map = history.undo(map)!!
        assertEquals("Desert", map.tileList.first().baseTerrain)
        assertNull(history.undo(map))
    }
}
