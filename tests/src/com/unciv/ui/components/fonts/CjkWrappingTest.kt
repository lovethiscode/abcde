@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
package com.unciv.ui.components.fonts

import org.junit.Assert.assertEquals
import org.junit.Test

class CjkWrappingTest {
    private fun wrap(text: String, start: Int) = cjkWrapIndex(start) { text[it] }

    @Test fun avoidsLeadingPunctuationAndTrailingOpeningBracket() {
        assertEquals(1, wrap("文明，开始", 3))
        assertEquals(3, wrap("文明，开始", 4))
        assertEquals(2, wrap("文明（开始", 4))
        assertEquals(1, wrap("疑问？！继续", 4))
    }

    @Test fun preservesLatinWordsAndAllowsCjkBoundaries() {
        assertEquals(0, wrap("technology", 7))
        assertEquals(3, wrap("文明开始", 4))
        assertEquals(2, wrap("中文technology", 8))
    }
}
