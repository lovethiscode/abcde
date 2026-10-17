@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package com.unciv.ui.screens.cityscreen

import com.unciv.ui.components.fonts.Fonts
import org.junit.Assert.assertEquals
import org.junit.Test

class CityWorkbenchTextTest {
    @Test fun separatesTranslatedLabelsAndConstructionNumbers() {
        assertEquals("${Fonts.production}\u2002产能", cityWorkbenchIconText("${Fonts.production}产能"))
        assertEquals("31\u2002${Fonts.production}\u20021\u2002${Fonts.turn}", cityWorkbenchIconText("31${Fonts.production}1${Fonts.turn}"))
    }

    @Test fun preservesMarkupAndDoesNotAccumulateSpacingOnRefresh() {
        val source = "[GOLD]${Fonts.gold}[][WHITE]金钱[]"
        val expected = "[GOLD]${Fonts.gold}[][WHITE]\u2002金钱[]"
        assertEquals(expected, cityWorkbenchIconText(source))
        assertEquals(expected, cityWorkbenchIconText(expected))
        assertEquals("${Fonts.food}  食物", cityWorkbenchIconText("${Fonts.food}  食物"))
    }

    @Test fun preservesOrdinaryTextAndLineBreaks() {
        val source = "[[来源]\n${Fonts.science}\n科研 62"
        assertEquals(source, cityWorkbenchIconText(source))
        assertEquals("城市管理", cityWorkbenchIconText("城市管理"))
    }
}
