package com.unciv.ui.components.fonts

/** Legal CJK boundaries supplement whitespace wrapping without splitting Latin words. */
internal fun cjkWrapIndex(start: Int, charAt: (Int) -> Char): Int {
    val opening = "（［｛〈《「『【〔〖〘〚‘“"
    val closing = "、。，．！？：；％‰）］｝〉》」』】〕〗〙〛’”…"
    fun cjk(char: Char) = char in '\u2E80'..'\uA4CF' || char in '\uAC00'..'\uD7AF' || char in '\uF900'..'\uFAFF' || char in '\uFF01'..'\uFF60'
    for (index in start - 1 downTo 1) {
        val previous = charAt(index - 1)
        val next = charAt(index)
        if ((cjk(previous) || cjk(next)) && previous !in opening && next !in closing)
            return index
    }
    return 0
}
