package com.unciv.ui.screens.diplomacyscreen

import org.junit.Assert.*
import org.junit.Test

class DiplomacyScreenLayoutTests {
    @Test fun `phone portrait stacks without reserving sidebar width`() {
        val l=DiplomacyScreenLayout(375f,760f)
        assertTrue(l.narrow);assertEquals(351f,l.detailWidth,.01f)
        assertTrue(l.columnWidth>300f)
    }
    @Test fun `phone landscape and tablet reserve both reading columns`() {
        for(width in listOf(838f,1194f)) {
            val l=DiplomacyScreenLayout(width,780f)
            assertFalse(l.narrow)
            assertTrue(l.columnWidth>=200f)
            assertEquals(width,l.padding*2+l.rosterWidth+l.gap+l.detailWidth,.01f)
        }
    }
    @Test fun `influence marker and threshold labels share one domain`() {
        assertEquals(0f,DiplomacyScreenLayout.influencePosition(-30f),.0001f)
        assertEquals(1f/3,DiplomacyScreenLayout.influencePosition(0f),.0001f)
        assertEquals(4f/9,DiplomacyScreenLayout.influencePosition(10f),.0001f)
        assertEquals(2f/3,DiplomacyScreenLayout.influencePosition(30f),.0001f)
        assertEquals(1f,DiplomacyScreenLayout.influencePosition(60f),.0001f)
    }
    @Test fun `extreme influence cannot draw outside the track`() {
        assertEquals(0f,DiplomacyScreenLayout.influencePosition(-120f),0f)
        assertEquals(1f,DiplomacyScreenLayout.influencePosition(250f),0f)
    }
}
