package com.unciv.ui.screens.savescreens

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.scenes.scene2d.ui.Image
import com.badlogic.gdx.scenes.scene2d.ui.CheckBox
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.badlogic.gdx.scenes.scene2d.utils.BaseDrawable
import com.badlogic.gdx.scenes.scene2d.utils.Drawable
import com.unciv.models.skins.ExpeditionBlueTheme
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.setFontSize
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.screens.basescreen.BaseScreen
import kotlin.math.atan2
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Page-local realization of approved prototype A, expressed in safe-area points. */
internal class LoadGameScreenStyle(val unit: Float, private val fontMultiplier: Float) {
    val background = ExpeditionBlueTheme.color(BaseScreen.skinStrings.skinConfig.clearColor, ExpeditionBlueTheme.background)
    val panel = ExpeditionBlueTheme.color(BaseScreen.skinStrings.skinConfig.baseColor, ExpeditionBlueTheme.panel)
    val raised = ExpeditionBlueTheme.color(panel.cpy().lerp(Color.WHITE, .12f), ExpeditionBlueTheme.raised)
    val text = ExpeditionBlueTheme.color(Color.WHITE, ExpeditionBlueTheme.text)
    val muted = ExpeditionBlueTheme.color(Color.LIGHT_GRAY, ExpeditionBlueTheme.muted)
    val border = ExpeditionBlueTheme.color(Color.GRAY, ExpeditionBlueTheme.border)
    val controlBorder = ExpeditionBlueTheme.color(Color.LIGHT_GRAY, ExpeditionBlueTheme.controlBorder)
    val gold = ExpeditionBlueTheme.color(Color.GOLD, ExpeditionBlueTheme.gold)
    val ink = ExpeditionBlueTheme.color(Color.BLACK, ExpeditionBlueTheme.primaryInk)
    val danger = ExpeditionBlueTheme.color(Color.RED, ExpeditionBlueTheme.danger)
    fun px(value: Float) = unit * value
    fun font(value: Int) = (value * unit).roundToInt().coerceAtLeast(1)
    fun label(value: String, size: Int = 14, color: Color = text, wrap: Boolean = false): Label =
        value.toLabel(color, font(size)).apply { this.wrap = wrap }

    fun surface(color: Color, radius: Float = 12f): Drawable {
        val dot = ImageGetter.getWhiteDotDrawable().tint(color)
        val circle = ImageGetter.getCircleDrawable().tint(color)
        return object : BaseDrawable() {
            override fun draw(batch: Batch, x: Float, y: Float, width: Float, height: Float) {
                val r = minOf(px(radius), width / 2, height / 2)
                circle.draw(batch, x, y, r*2, r*2); circle.draw(batch, x+width-r*2, y, r*2, r*2)
                circle.draw(batch, x, y+height-r*2, r*2, r*2); circle.draw(batch, x+width-r*2, y+height-r*2, r*2, r*2)
                dot.draw(batch, x+r, y, (width-r*2).coerceAtLeast(0f), height)
                dot.draw(batch, x, y+r, width, (height-r*2).coerceAtLeast(0f))
            }
        }
    }
    private fun outlined(color: Color): Drawable {
        val outside = surface(controlBorder)
        val inside = surface(color, 11f)
        return object : BaseDrawable() {
            override fun draw(batch: Batch, x: Float, y: Float, width: Float, height: Float) {
                outside.draw(batch, x, y, width, height)
                inside.draw(batch, x+unit, y+unit, (width-2*unit).coerceAtLeast(0f), (height-2*unit).coerceAtLeast(0f))
            }
        }
    }
    fun buttonStyle(primary: Boolean = false, outline: Boolean = false, ghost: Boolean = false) =
        TextButton.TextButtonStyle(BaseScreen.skin.get(TextButton.TextButtonStyle::class.java)).apply {
            up = when { primary -> surface(gold); outline -> outlined(raised); ghost -> null; else -> surface(panel) }
            down = surface(if (primary) gold.cpy().lerp(ink, .1f) else raised)
            over = down; checked = surface(raised); disabled = surface(raised)
            fontColor = if (primary) ink else text
            downFontColor = fontColor; overFontColor = fontColor; checkedFontColor = gold; disabledFontColor = muted
        }
    fun decorate(button: TextButton, icon: SaveIcon? = null, primary: Boolean = false,
                 outline: Boolean = false, ghost: Boolean = false, size: Int = 14, tint: Color? = null): TextButton {
        button.style = buttonStyle(primary, outline, ghost)
        tint?.let { button.style.fontColor = it; button.style.downFontColor = it; button.style.overFontColor = it }
        button.label.setFontSize(font(size)); button.label.setEllipsis(true)
        button.clearChildren(); button.pad(0f, px(14f), 0f, px(14f))
        if (icon != null) button.add(icon.image(tint ?: if (primary) ink else text)).size(px(18f)).padRight(px(8f))
        if (button.text.isNotEmpty()) button.add(button.label).minWidth(0f)
        return button
    }
    fun button(value: String, icon: SaveIcon? = null, primary: Boolean = false,
               outline: Boolean = false, ghost: Boolean = false, size: Int = 14) =
        decorate(TextButton(value.tr(), buttonStyle()), icon, primary, outline, ghost, size)

    fun checkboxStyle() = CheckBox.CheckBoxStyle(BaseScreen.skin.get(CheckBox.CheckBoxStyle::class.java)).apply {
        fun box(checked: Boolean) = object : BaseDrawable() {
            init { minWidth = px(18f); minHeight = px(18f) }
            override fun draw(batch: Batch, x: Float, y: Float, width: Float, height: Float) {
                surface(if (checked) gold else controlBorder, 5f).draw(batch, x, y, width, height)
                if (checked) SaveIcon.Check.drawable(ink).draw(batch, x+px(1f), y+px(1f), width-px(2f), height-px(2f))
                else surface(background, 4f).draw(batch, x+unit, y+unit, width-2*unit, height-2*unit)
            }
        }
        checkboxOn = box(true); checkboxOff = box(false)
        checkboxOnOver = checkboxOn; checkboxOver = checkboxOff
        fontColor = muted; overFontColor = muted; downFontColor = muted
    }
}

/** One countable line family; no raster assets or font glyphs are used as controls. */
internal enum class SaveIcon(vararg val lines: FloatArray) {
    Back(floatArrayOf(14f,6f,8f,12f),floatArrayOf(8f,12f,14f,18f)),
    Down(floatArrayOf(6f,9f,12f,15f),floatArrayOf(12f,15f,18f,9f)),
    Flag(floatArrayOf(5f,21f,5f,3f),floatArrayOf(5f,4f,11f,3f),floatArrayOf(11f,3f,18f,5f),floatArrayOf(18f,5f,20f,4f),floatArrayOf(20f,4f,20f,14f),floatArrayOf(20f,14f,12f,13f),floatArrayOf(12f,13f,5f,14f)),
    Clock(floatArrayOf(12f,3f,18f,5f),floatArrayOf(18f,5f,21f,12f),floatArrayOf(21f,12f,18f,19f),floatArrayOf(18f,19f,12f,21f),floatArrayOf(12f,21f,5f,18f),floatArrayOf(5f,18f,3f,12f),floatArrayOf(3f,12f,5f,5f),floatArrayOf(5f,5f,12f,3f),floatArrayOf(12f,7f,12f,12f),floatArrayOf(12f,12f,15f,14f)),
    Check(floatArrayOf(5f,12f,9f,16f),floatArrayOf(9f,16f,19f,6f)),
    Play(floatArrayOf(9f,5f,19f,12f),floatArrayOf(19f,12f,9f,19f),floatArrayOf(9f,19f,9f,5f)),
    Import(floatArrayOf(12f,3f,12f,15f),floatArrayOf(8f,11f,12f,15f),floatArrayOf(12f,15f,16f,11f),floatArrayOf(5f,16f,5f,21f),floatArrayOf(5f,21f,19f,21f),floatArrayOf(19f,21f,19f,16f)),
    Copy(floatArrayOf(9f,8f,20f,8f),floatArrayOf(20f,8f,20f,21f),floatArrayOf(20f,21f,9f,21f),floatArrayOf(9f,21f,9f,8f),floatArrayOf(6f,17f,4f,17f),floatArrayOf(4f,17f,4f,3f),floatArrayOf(4f,3f,15f,3f),floatArrayOf(15f,3f,15f,5f)),
    Delete(floatArrayOf(4f,6f,20f,6f),floatArrayOf(9f,6f,9f,3f),floatArrayOf(9f,3f,15f,3f),floatArrayOf(15f,3f,15f,6f),floatArrayOf(6f,6f,7f,21f),floatArrayOf(7f,21f,17f,21f),floatArrayOf(17f,21f,18f,6f),floatArrayOf(10f,10f,10f,17f),floatArrayOf(14f,10f,14f,17f)),
    File(floatArrayOf(14f,3f,6f,3f),floatArrayOf(6f,3f,6f,21f),floatArrayOf(6f,21f,18f,21f),floatArrayOf(18f,21f,18f,7f),floatArrayOf(18f,7f,14f,3f),floatArrayOf(14f,3f,14f,8f),floatArrayOf(14f,8f,18f,8f)),
    Archive(floatArrayOf(3f,3f,21f,3f),floatArrayOf(21f,3f,21f,8f),floatArrayOf(21f,8f,3f,8f),floatArrayOf(3f,8f,3f,3f),floatArrayOf(4f,8f,4f,21f),floatArrayOf(4f,21f,20f,21f),floatArrayOf(20f,21f,20f,8f),floatArrayOf(9f,12f,15f,12f)),
    Cloud(floatArrayOf(7f,18f,18f,18f),floatArrayOf(18f,18f,21f,16f),floatArrayOf(21f,16f,21f,12f),floatArrayOf(21f,12f,18f,10f),floatArrayOf(18f,10f,16f,5f),floatArrayOf(16f,5f,10f,4f),floatArrayOf(10f,4f,7f,8f),floatArrayOf(7f,8f,3f,10f),floatArrayOf(3f,10f,2f,14f),floatArrayOf(2f,14f,4f,18f),floatArrayOf(4f,18f,7f,18f));

    fun image(tint: Color) = Image(drawable(Color.WHITE)).apply { color = tint }
    fun drawable(tint: Color): Drawable = object : BaseDrawable() {
        override fun draw(batch: Batch, x: Float, y: Float, width: Float, height: Float) {
            val old = batch.packedColor
            batch.setColor(tint.r * batch.color.r, tint.g * batch.color.g, tint.b * batch.color.b, batch.color.a)
            val region = ImageGetter.getWhiteDotDrawable().region
            val stroke = width * 1.7f / 24f
            for (line in lines) {
                val dx = (line[2]-line[0])*width/24f; val dy = (line[1]-line[3])*height/24f
                batch.draw(region, x+line[0]*width/24f, y+(24f-line[1])*height/24f-stroke/2,
                    0f, stroke/2, sqrt(dx*dx+dy*dy), stroke, 1f, 1f, atan2(dy,dx)*180f/Math.PI.toFloat())
            }
            batch.packedColor = old
        }
    }
}
