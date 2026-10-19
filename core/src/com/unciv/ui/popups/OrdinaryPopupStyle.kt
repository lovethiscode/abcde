package com.unciv.ui.popups

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.badlogic.gdx.scenes.scene2d.utils.BaseDrawable
import com.unciv.models.skins.ExpeditionBlueTheme
import com.unciv.ui.components.widgets.UiNavigation
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.screens.basescreen.BaseScreen

/** The approved ordinary-dialog palette, isolated from campaign, defense and mod skins. */
internal class OrdinaryPopupStyle(private val unit: Float) {
    val background = ExpeditionBlueTheme.background
    val deep = Color.valueOf("102B35")
    val line = Color.valueOf("557783")
    private val separator = Color.valueOf("385965")
    private val danger = Color.valueOf("70434A")
    fun surface(color: Color, radius: Float = 9f) = UiNavigation.roundedSurface(color, radius, unit)

    /** One point edge, independent of the legacy skin's two world-unit inset. */
    fun outlined(fill: Color, edge: Color = line, radius: Float = 9f): BaseDrawable {
        val outside = surface(edge, radius)
        val inside = surface(fill, (radius - 1f).coerceAtLeast(0f))
        return object : BaseDrawable() {
            init { leftWidth = 12f * unit; rightWidth = leftWidth }
            override fun draw(batch: Batch, x: Float, y: Float, width: Float, height: Float) {
                outside.draw(batch, x, y, width, height)
                inside.draw(batch, x + unit, y + unit, (width - 2 * unit).coerceAtLeast(0f), (height - 2 * unit).coerceAtLeast(0f))
            }
        }
    }

    fun selectField(): BaseDrawable {
        val field = outlined(deep, radius = 8f)
        val arrow = ImageGetter.getDrawable("OtherIcons/ArrowRight").tint(ExpeditionBlueTheme.muted)
            as com.badlogic.gdx.scenes.scene2d.utils.TransformDrawable
        return object : BaseDrawable(field) {
            init { rightWidth = 36f * unit }
            override fun draw(batch: Batch, x: Float, y: Float, width: Float, height: Float) {
                field.draw(batch, x, y, width, height)
                val size = 14f * unit
                arrow.draw(batch, x + width - 26f * unit, y + (height - size) / 2,
                    size / 2, size / 2, size, size, 1f, 1f, -90f)
            }
        }
    }

    fun divider(atTop: Boolean, fill: Color? = null): BaseDrawable {
        val fillSurface = fill?.let { surface(it, 16f) }
        val fillTop = fill?.let { ImageGetter.getWhiteDotDrawable().tint(it) }
        val rule = ImageGetter.getWhiteDotDrawable().tint(separator)
        return object : BaseDrawable() {
            override fun draw(batch: Batch, x: Float, y: Float, width: Float, height: Float) {
                if (fillSurface != null) {
                    fillSurface.draw(batch, x, y, width, height)
                    fillTop!!.draw(batch, x, y + height / 2, width, height / 2)
                }
                rule.draw(batch, x, if (atTop) y + height - unit else y, width, unit)
            }
        }
    }

    fun button(primary: Boolean = false, destructive: Boolean = false): TextButton.TextButtonStyle =
        TextButton.TextButtonStyle(BaseScreen.skin.get(TextButton.TextButtonStyle::class.java)).apply {
            val fill = if (destructive) danger else if (primary) ExpeditionBlueTheme.gold else background
            val edge = if (destructive) ExpeditionBlueTheme.danger else line
            fun face(color: Color) = if (primary && !destructive) surface(color)
                else outlined(color, edge)
            up = face(fill)
            over = face(if (primary || destructive) fill else ExpeditionBlueTheme.raised)
            down = face(fill.cpy().lerp(Color.BLACK, .12f))
            checked = if (primary || destructive) over else
                outlined(ExpeditionBlueTheme.panel, ExpeditionBlueTheme.gold, 10f)
            checkedOver = checked; checkedDown = down
            disabled = face(fill.cpy().lerp(background, .5f))
            fontColor = if (destructive) Color.valueOf("FFF0ED") else if (primary) ExpeditionBlueTheme.primaryInk else ExpeditionBlueTheme.text
            overFontColor = fontColor; downFontColor = fontColor; checkedFontColor = fontColor
            checkedOverFontColor = fontColor; checkedDownFontColor = fontColor
            disabledFontColor = ExpeditionBlueTheme.muted
        }
}
