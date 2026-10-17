package com.unciv.ui.screens.cityscreen

import com.badlogic.gdx.math.Rectangle
import org.junit.Assert.*
import org.junit.Test

class CityWorkbenchLayoutTest {
    @Test fun prototypeDeviceSizesKeepEveryPaneInsideTheContentArea() {
        for ((width, height) in listOf(874f to 402f, 402f to 780f, 1194f to 790f, 834f to 1146f, 600f to 600f)) {
            val layout = CityWorkbenchLayout(width, height)
            val area = Rectangle(0f, 0f, width, layout.contentTop)
            val panes = listOf(layout.map, layout.management) + if (layout.tablet) listOf(layout.production) else emptyList()
            for (pane in panes) {
                assertTrue("$width x $height: $pane is clipped", pane.x >= 0f && pane.y >= 0f && pane.x + pane.width <= area.width + .1f && pane.y + pane.height <= area.height + .1f)
                assertTrue("Map and controls need usable room", pane.width >= 200f && pane.height >= 160f)
            }
            for (first in panes.indices) for (second in first + 1 until panes.size)
                assertFalse("Panels must not obscure the map or each other", panes[first].overlaps(panes[second]))
        }
    }

    @Test fun phoneRotationChangesCompositionWithoutHidingTheMap() {
        val portrait = CityWorkbenchLayout(402f, 780f)
        assertFalse(portrait.tablet)
        assertTrue(portrait.map.y >= portrait.management.y + portrait.management.height)
        val landscape = CityWorkbenchLayout(766f, 380f)
        assertFalse(landscape.tablet)
        assertTrue(landscape.map.x + landscape.map.width < landscape.management.x)
        assertEquals(landscape.map.height, landscape.management.height, .1f)
    }

    @Test fun ipadKeepsProductionVisibleInBothOrientations() {
        val landscape = CityWorkbenchLayout(1194f, 790f)
        assertTrue(landscape.production.x < landscape.map.x)
        assertTrue(landscape.map.x < landscape.management.x)
        val portrait = CityWorkbenchLayout(834f, 1146f)
        assertEquals(portrait.production.y, portrait.management.y, .1f)
        assertTrue(portrait.map.y > portrait.production.y)
        assertEquals(portrait.production.width, portrait.management.width, .1f)
    }

    @Test fun cityNavigationNoLongerReservesAFullWidthMapRow() {
        for ((width, height) in listOf(874f to 402f, 1194f to 790f)) {
            val layout = CityWorkbenchLayout(width, height)
            assertEquals(height - layout.yieldsHeight, layout.contentTop, .1f)
            assertEquals(layout.contentTop, layout.map.y + layout.map.height, .1f)
            assertEquals(height - layout.yieldsHeight - 12f, layout.map.height, .1f)
            assertTrue(layout.management.height - 52f >= 160f)
        }
    }
}
