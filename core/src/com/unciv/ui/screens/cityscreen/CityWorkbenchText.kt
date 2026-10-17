package com.unciv.ui.screens.cityscreen

import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Group
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.unciv.ui.components.fonts.FontRulesetIcons
import com.unciv.ui.components.fonts.Fonts

/** Apply only to workbench controls, never to the map's compact tile yields. */
internal fun spaceCityWorkbenchIcons(actor: Actor) {
    if (actor is Label) {
        val spaced = cityWorkbenchIconText(actor.text)
        if (spaced != actor.text.toString()) actor.setText(spaced)
    }
    if (actor is Group) for (child in actor.children) spaceCityWorkbenchIcons(child)
}

/** Keep font icons apart from adjacent text/numbers without changing color markup. */
internal fun cityWorkbenchIconText(text: CharSequence): String = buildString {
    fun isIcon(character: Char) = character in Fonts.allSymbols ||
        character in FontRulesetIcons.charToRulesetImageActor

    var previous: Char? = null
    var index = 0
    while (index < text.length) {
        val character = text[index]
        if (character == '[') {
            if (index + 1 < text.length && text[index + 1] == '[') {
                append("[[")
                previous = '['
                index += 2
                continue
            }
            val end = text.indexOf(']', index + 1)
            if (end >= 0) {
                append(text, index, end + 1)
                index = end + 1
                continue
            }
        }
        val before = previous
        if (before != null && !before.isWhitespace() && !character.isWhitespace() &&
            (isIcon(before) || isIcon(character))) append('\u2002')
        append(character)
        previous = character
        index++
    }
}
