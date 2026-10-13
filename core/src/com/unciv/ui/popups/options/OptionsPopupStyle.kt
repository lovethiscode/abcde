package com.unciv.ui.popups.options

import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.scenes.scene2d.ui.CheckBox
import com.badlogic.gdx.scenes.scene2d.utils.BaseDrawable
import com.unciv.models.skins.ExpeditionBlueTheme
import com.unciv.ui.screens.basescreen.BaseScreen

/** Local styles for the options panel; other checkboxes keep their existing appearance. */
internal object OptionsPopupStyle {
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
        private val track = BaseScreen.skin.newDrawable("RoundedEdgeRectangle",
            if (checked) ExpeditionBlueTheme.gold else ExpeditionBlueTheme.border)
        private val thumb = BaseScreen.skin.newDrawable("Circle",
            if (checked) ExpeditionBlueTheme.primaryInk else ExpeditionBlueTheme.text)

        init { minWidth = 44f; minHeight = 26f }

        override fun draw(batch: Batch, x: Float, y: Float, width: Float, height: Float) {
            track.draw(batch, x, y, width, height)
            val size = height - 8f
            thumb.draw(batch, if (checked) x + width - size - 4f else x + 4f, y + 4f, size, size)
        }
    }
}
