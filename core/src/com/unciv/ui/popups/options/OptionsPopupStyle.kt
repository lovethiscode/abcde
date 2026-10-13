package com.unciv.ui.popups.options

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Group
import com.badlogic.gdx.scenes.scene2d.ui.CheckBox
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.List.ListStyle
import com.badlogic.gdx.scenes.scene2d.ui.SelectBox
import com.badlogic.gdx.scenes.scene2d.ui.TextField
import com.badlogic.gdx.scenes.scene2d.utils.BaseDrawable
import com.unciv.Constants
import com.unciv.models.skins.ExpeditionBlueTheme
import com.unciv.ui.components.extensions.setFontSize
import com.unciv.ui.components.fonts.Fonts
import com.unciv.ui.screens.basescreen.BaseScreen

/** Local styles for the options panel; other checkboxes keep their existing appearance. */
internal object OptionsPopupStyle {
    val sectionBackground = Color.valueOf("1D3D48")
    val selectedNavigation = Color.valueOf("254550")

    fun enlargeText(actor: Actor, formFont: BitmapFont) {
        when (actor) {
            is Label -> {
                val size = if (actor.fontScaleX == 1f) Constants.defaultFontSize.toFloat()
                    else actor.fontScaleX * Fonts.ORIGINAL_FONT_SIZE
                if (size < 22f) actor.setFontSize(22)
            }
            is SelectBox<*> -> if (actor.style.font !== formFont) {
                actor.style = SelectBox.SelectBoxStyle(actor.style).apply {
                    font = formFont
                    listStyle = ListStyle(listStyle).apply { font = formFont }
                }
            }
            is TextField -> if (actor.style.font !== formFont)
                actor.style = TextField.TextFieldStyle(actor.style).apply { font = formFont }
        }
        if (actor is Group) actor.children.forEach { enlargeText(it, formFont) }
    }

    fun selectedNavigationDrawable() = object : BaseDrawable() {
        private val fill = BaseScreen.skin.newDrawable("Rectangle", selectedNavigation)
        private val accent = BaseScreen.skin.newDrawable("Rectangle", ExpeditionBlueTheme.gold)

        override fun draw(batch: Batch, x: Float, y: Float, width: Float, height: Float) {
            fill.draw(batch, x, y, width, height)
            accent.draw(batch, x, y + 10f, 2f, height - 20f)
        }
    }

    fun styleSwitch(checkbox: CheckBox) {
        checkbox.style = CheckBox.CheckBoxStyle(checkbox.style).apply {
            checkboxOff = switchDrawable(false)
            checkboxOn = switchDrawable(true)
            checkboxOver = checkboxOff
            checkboxOnOver = checkboxOn
            checkedFontColor = ExpeditionBlueTheme.text
            checkedOverFontColor = ExpeditionBlueTheme.text
            checkedDownFontColor = ExpeditionBlueTheme.text
        }
        checkbox.label.wrap = true
        checkbox.clearChildren()
        checkbox.add(checkbox.label).minWidth(0f).growX().left().padRight(16f)
        checkbox.add(checkbox.image).size(44f, 26f)
        checkbox.pad(10f, 12f, 10f, 12f)
    }

    private fun switchDrawable(checked: Boolean) = object : BaseDrawable() {
        private val trackColor = if (checked) ExpeditionBlueTheme.gold else ExpeditionBlueTheme.border
        private val track = BaseScreen.skin.newDrawable("Rectangle", trackColor)
        private val trackEnd = BaseScreen.skin.newDrawable("Circle", trackColor)
        private val thumb = BaseScreen.skin.newDrawable("Circle",
            if (checked) ExpeditionBlueTheme.primaryInk else ExpeditionBlueTheme.text)

        init { minWidth = 44f; minHeight = 26f }

        override fun draw(batch: Batch, x: Float, y: Float, width: Float, height: Float) {
            track.draw(batch, x + height / 2f, y, width - height, height)
            trackEnd.draw(batch, x, y, height, height)
            trackEnd.draw(batch, x + width - height, y, height, height)
            val size = height - 8f
            thumb.draw(batch, if (checked) x + width - size - 4f else x + 4f, y + 4f, size, size)
        }
    }
}
