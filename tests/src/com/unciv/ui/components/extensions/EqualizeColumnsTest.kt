package com.unciv.ui.components.extensions

import com.badlogic.gdx.Files
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.ui.Table
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock

class EqualizeColumnsTest {
    private val oldFiles = Gdx.files

    @Before fun setup() {
        Gdx.files = mock(Files::class.java)
    }

    @After fun cleanup() {
        Gdx.files = oldFiles
    }

    private fun table(vararg widths: Float) = Table().apply {
        for (width in widths) add(Actor()).minWidth(width).minHeight(10f)
    }

    @Test fun emptyCityDetailsDoNotPreventHeaderAndTotalsAlignment() {
        val header = table(30f, 60f)
        val details = Table()
        val totals = table(50f, 40f)

        equalizeColumns(details, header, totals)
        header.pack()
        totals.pack()

        assertEquals(0, details.columns)
        assertEquals(50f, header.getColumnWidth(0), 0.001f)
        assertEquals(60f, totals.getColumnWidth(1), 0.001f)
    }

    @Test fun detailsCanBeClearedAndPopulatedAgain() {
        val header = table(30f, 60f)
        val details = table(50f, 40f)
        val totals = table(40f, 70f)

        equalizeColumns(details, header, totals)
        details.clear()
        equalizeColumns(details, header, totals)
        details.add(Actor()).minWidth(80f).minHeight(10f)
        details.add(Actor()).minWidth(40f).minHeight(10f)
        equalizeColumns(details, header, totals)
        for (table in arrayOf(header, details, totals)) {
            table.pack()
            assertEquals(80f, table.getColumnWidth(0), 0.001f)
            assertEquals(70f, table.getColumnWidth(1), 0.001f)
        }
    }
}
