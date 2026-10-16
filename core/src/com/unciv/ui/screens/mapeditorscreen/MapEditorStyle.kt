package com.unciv.ui.screens.mapeditorscreen

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.badlogic.gdx.scenes.scene2d.utils.Drawable
import com.badlogic.gdx.scenes.scene2d.utils.BaseDrawable
import com.badlogic.gdx.graphics.g2d.Batch
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.setFontSize
import com.unciv.ui.components.input.onActivation
import com.unciv.ui.components.widgets.UiNavigation
import com.unciv.ui.screens.basescreen.BaseScreen
import kotlin.math.roundToInt

/** Page-scoped colors; the rest of the game retains its theme. */
object MapEditorStyle {
    val unit get() = UiNavigation.minimumTouchSize() / 44f
    val panel = Color.valueOf("173d53")
    val raised = Color.valueOf("254e65")
    val selected = Color.valueOf("365e74")
    val text = Color.valueOf("f4f7f8")
    val muted = Color.valueOf("c0d3de")
    val accent = Color.valueOf("edca87")
    fun background(color: Color): Drawable = com.unciv.ui.images.ImageGetter.getWhiteDotDrawable().tint(color)
    fun button(caption: String, primary: Boolean = false, action: ()->Unit): TextButton {
        val style = TextButton.TextButtonStyle(BaseScreen.skin.get(TextButton.TextButtonStyle::class.java)).apply {
            up = background(if (primary) accent else raised)
            down = background(selected)
            checked = background(if (primary) accent else selected)
            over = down
            disabled = background(raised)
            fontColor = if (primary) Color.valueOf("253346") else text
            checkedFontColor = if (primary) Color.valueOf("253346") else text
            disabledFontColor = muted
        }
        return object : TextButton(caption.tr(), style) {
            override fun getMinHeight() = 44 * unit
            override fun getMinWidth() = maxOf(super.getMinWidth(), 44 * unit)
        }.apply {
            label.setFontSize((16*unit).roundToInt())
            labelCell.pad(8*unit)
            onActivation { action() }
        }
    }
    fun outlined(): Drawable = object : BaseDrawable() {
        private val surface = background(selected)
        private val edge = background(accent)
        override fun draw(batch: Batch, x: Float, y: Float, width: Float, height: Float) {
            surface.draw(batch, x, y, width, height)
            val line = unit
            edge.draw(batch, x, y, width, line); edge.draw(batch, x, y+height-line, width, line)
            edge.draw(batch, x, y, line, height); edge.draw(batch, x+width-line, y, line, height)
        }
    }
    fun select(button: TextButton, selected: Boolean) {
        button.isChecked = selected
        // A selected tool gets an explicit outline as well as the filled state.
        button.style = TextButton.TextButtonStyle(button.style).apply { checked = outlined() }
    }
}
