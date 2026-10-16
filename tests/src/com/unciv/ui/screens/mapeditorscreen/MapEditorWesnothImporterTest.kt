package com.unciv.ui.screens.mapeditorscreen

import com.badlogic.gdx.Gdx
import com.unciv.UncivGame
import com.unciv.logic.UncivShowableException
import com.unciv.logic.files.UncivFiles
import com.unciv.logic.map.TileMap
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.junit.rules.TemporaryFolder
import java.lang.reflect.InvocationTargetException

@RunWith(BaseTestRunner::class)
class MapEditorWesnothImporterTest {
    @get:Rule
    val writableData = TemporaryFolder()

    @Before
    fun setUp() {
        TestGame()
        // Like iOS: writable data contains no bundled jsons directory.
        UncivGame.Current.files = UncivFiles(Gdx.files, writableData.newFolder("empty-data").absolutePath)
    }

    // Exercise the real parser without starting the asynchronous file-picker UI.
    private fun parse(cells: List<List<String>>): TileMap {
        val data = "usage=map\nborder_size=1\n\n" + cells.joinToString("\n") { it.joinToString(",") }
        val importer = MapEditorWesnothImporter(mock(MapEditorScreen::class.java))
        val method = importer.javaClass.getDeclaredMethod("parse", String::class.java).apply { isAccessible = true }
        try {
            return method.invoke(importer, data) as TileMap
        } catch (ex: InvocationTargetException) {
            throw ex.cause!!
        }
    }

    @Test
    fun grassFixtureKeepsTerrainAndNumberedStart() {
        val rows = List(5) { r -> List(5) { c -> if (r == 2 && c == 2) "1 Gg" else "Gg" } }
        val map = parse(rows)
        assertEquals(12, map.values.size)
        assertTrue(map.values.all { it.baseTerrain == "Grassland" })
        assertEquals(1, map.startingLocations.size)
    }

    @Test
    fun scenarioPrefixesAndFeatureLayersStillParse() {
        val map = parse(List(5) { List(5) { "sceptre Gg^Fp" } })
        assertTrue(map.values.all { it.baseTerrain == "Grassland" })
        // The importer's extra staggered border row includes default tiles.
        assertTrue(map.values.any { "Forest" in it.terrainFeatures })
        assertTrue(map.startingLocations.isEmpty())
        assertTrue(parse(List(5) { List(5) { "Wo" } }).values.any { it.baseTerrain == "Ocean" })
    }

    @Test(expected = UncivShowableException::class)
    fun invalidTerrainIsRejected() {
        parse(List(5) { List(5) { "not-a-terrain!" } })
    }
}
