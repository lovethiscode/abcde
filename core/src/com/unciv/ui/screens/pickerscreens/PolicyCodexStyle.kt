package com.unciv.ui.screens.pickerscreens

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g2d.Batch
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
import kotlin.math.roundToInt

/** Screen-local tokens shared with the expedition-blue UI, for the approved C+A policy codex. */
internal class PolicyCodexStyle(val unit: Float, val textScale: Float) {
    val background = ExpeditionBlueTheme.background
    val panel = ExpeditionBlueTheme.panel
    val raised = ExpeditionBlueTheme.raised
    val text = ExpeditionBlueTheme.text
    val muted = ExpeditionBlueTheme.muted
    val gold = ExpeditionBlueTheme.gold
    val ink = ExpeditionBlueTheme.primaryInk
    val good = Color.valueOf("b4d7ba")
    val danger = ExpeditionBlueTheme.danger
    val deep = Color.valueOf("102b35")
    val line = Color.valueOf("557783")
    val quietLine = Color.valueOf("385965")
    fun u(value: Float) = value * unit
    fun font(value: Int) = (value * unit * textScale).roundToInt().coerceAtLeast(1)
    fun label(value: String, size: Int = 13, color: Color = text, wrap: Boolean = false): Label =
        value.toLabel(color, font(size), hideIcons = true).apply { setWrap(wrap) }

    fun surface(color: Color, radius: Float = 10f): Drawable {
        val dot = ImageGetter.getWhiteDotDrawable().tint(color)
        val circle = ImageGetter.getCircleDrawable().tint(color)
        return object : BaseDrawable() {
            override fun draw(batch: Batch, x: Float, y: Float, width: Float, height: Float) {
                val r = minOf(u(radius), width / 2, height / 2)
                circle.draw(batch, x, y, 2*r, 2*r)
                circle.draw(batch, x+width-2*r, y, 2*r, 2*r)
                circle.draw(batch, x, y+height-2*r, 2*r, 2*r)
                circle.draw(batch, x+width-2*r, y+height-2*r, 2*r, 2*r)
                dot.draw(batch, x+r, y, (width-2*r).coerceAtLeast(0f), height)
                dot.draw(batch, x, y+r, width, (height-2*r).coerceAtLeast(0f))
            }
        }
    }

    fun outline(fill: Color = background, edge: Color = line, radius: Float = 10f, thickness: Float = 1f): Drawable {
        val border = surface(edge, radius)
        val center = surface(fill, radius-thickness)
        return object : BaseDrawable() {
            override fun draw(batch: Batch, x: Float, y: Float, width: Float, height: Float) {
                val inset = u(thickness)
                border.draw(batch, x, y, width, height)
                center.draw(batch, x+inset, y+inset, (width-2*inset).coerceAtLeast(0f), (height-2*inset).coerceAtLeast(0f))
            }
        }
    }

    fun buttonStyle(primary: Boolean = false, outlined: Boolean = false) =
        TextButton.TextButtonStyle(BaseScreen.skin.get(TextButton.TextButtonStyle::class.java)).apply {
            up = if (primary) surface(gold) else if (outlined) outline() else null
            down = surface(if (primary) Color.valueOf("f1dcaf") else raised)
            over = down
            checked = surface(if (primary) gold else raised)
            disabled = outline(panel, quietLine)
            fontColor = if (primary) ink else text
            downFontColor = fontColor
            overFontColor = fontColor
            checkedFontColor = fontColor
            disabledFontColor = muted
        }

    fun decorate(button: TextButton, primary: Boolean = false, outlined: Boolean = false, size: Int = 13): TextButton {
        button.style = buttonStyle(primary, outlined)
        button.label.setFontSize(font(size))
        button.label.setEllipsis(true)
        button.clearChildren()
        button.add(button.label).minWidth(0f)
        button.pad(u(7f), u(10f), u(7f), u(10f))
        return button
    }
    fun button(text: String, primary: Boolean = false, outlined: Boolean = false, size: Int = 13) =
        decorate(TextButton(text.tr(hideIcons = true), buttonStyle()), primary, outlined, size)
}


/** Sizes in point-equivalents inside BaseScreen's safe-area viewport. */
internal class PolicyCodexLayout(val width: Float, val height: Float) {
    val portrait = height > width
    val compact = !portrait && height < 600f
    val topInset = if (compact) 8f else 24f
    val bottomInset = if (compact) 8f else 24f
    val headerGap = if (compact) 6f else 18f
    val summaryGap = if (compact) 6f else 10f
    val summaryHeight = if (compact) 44f else 64f
    val docked = width >= 800f && height >= 600f || width >= 1100f
    val padding = if (compact) 12f else if (width < 700f) 16f else 24f
    val gap = if (compact) 12f else 16f
    val detailWidth = if (width <= 1000f) 270f else 300f
    val contentWidth = (width - padding * 2).coerceAtLeast(1f)
    val catalogWidth = (contentWidth - if (docked) detailWidth + 24f else 0f).coerceAtLeast(1f)
    val columns = if (portrait && width <= 1000f) 1 else
        ((catalogWidth + gap) / ((if (compact) 250f else 280f) + gap)).toInt().coerceAtLeast(1)
    val branchWidth = ((catalogWidth - gap * (columns - 1)) / columns).coerceAtLeast(1f)
}
