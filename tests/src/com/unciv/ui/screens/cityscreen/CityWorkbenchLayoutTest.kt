package com.unciv.ui.screens.cityscreen

import com.badlogic.gdx.math.Rectangle
import org.junit.Assert.*
import org.junit.Test

class CityWorkbenchLayoutTest {
    @Test fun prototypeDeviceSizesKeepEveryPaneInsideTheContentArea() {
        for ((width, height) in listOf(734f to 372f, 393f to 759f, 1194f to 790f, 834f to 1146f, 600f to 600f)) {
            val layout = CityWorkbenchLayout(width, height)
            val area = Rectangle(0f, 0f, width, layout.contentTop)
            val panes = listOf(layout.map, layout.management) + if (layout.tablet) listOf(layout.production) else emptyList()
            for (pane in panes) {
                assertTrue("$width x $height: $pane is clipped", pane.x >= 0f && pane.y >= 0f && pane.x + pane.width <= area.width + .1f && pane.y + pane.height <= area.height + .1f)
                assertTrue("Map and controls need usable room", pane.width >= 200f && pane.height >= (if (pane == layout.map && !layout.tablet) 128f else 160f))
            }
            for (first in panes.indices) for (second in first + 1 until panes.size)
                assertFalse("Panels must not obscure the map or each other", panes[first].overlaps(panes[second]))
        }
    }

    @Test fun phoneRotationChangesCompositionWithoutHidingTheMap() {
        val portrait = CityWorkbenchLayout(393f, 759f)
        assertFalse(portrait.tablet)
        assertTrue(portrait.map.y >= portrait.management.y + portrait.management.height)
        val landscape = CityWorkbenchLayout(734f, 372f)
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
        for ((width, height) in listOf(734f to 372f, 1194f to 790f)) {
            val layout = CityWorkbenchLayout(width, height)
            assertEquals(height - layout.yieldsHeight, layout.contentTop, .1f)
            assertEquals(layout.contentTop, layout.map.y + layout.map.height, .1f)
            assertEquals(height - layout.yieldsHeight - (if (layout.tablet) 12f else 0f), layout.map.height, .1f)
            assertTrue(layout.management.height - 52f >= 160f)
        }
    }
    @Test fun expandedPhoneDetailsUseTheAvailableAreaWithoutKeepingAnInvisibleMapColumn() {
        for ((width, height) in listOf(734f to 372f, 393f to 759f)) {
            val layout = CityWorkbenchLayout(width, height, expanded = true)
            assertEquals(width - layout.margin * 2, layout.management.width, .1f)
            assertEquals(layout.contentTop, layout.management.height, .1f)
            assertEquals(0f, layout.map.width, .1f)
            assertTrue(layout.management.height - 44f - 44f >= 200f)
        }
    }

    @Test fun wrappedPhoneHeaderReservesSpaceWithoutOverlappingTheMapOrDetails() {
        for ((width, height) in listOf(734f to 372f, 393f to 759f)) {
            val compact = CityWorkbenchLayout(width, height)
            val wrapped = CityWorkbenchLayout(width, height, phoneHeaderHeight = 88f)
            assertEquals(44f, compact.management.height - wrapped.management.height, .1f)
            assertTrue(wrapped.map.y + wrapped.map.height <= height - 88f + .1f)
            assertTrue(wrapped.management.y + wrapped.management.height <= height - 88f + .1f)
            assertFalse(wrapped.map.overlaps(wrapped.management))
        }
        val tablet = CityWorkbenchLayout(1194f, 790f, phoneHeaderHeight = 88f)
        assertEquals(48f, tablet.yieldsHeight, .1f)
    }

}
