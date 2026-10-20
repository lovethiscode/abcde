package com.unciv.ui.screens.pickerscreens

import com.unciv.ui.components.fonts.UiTypography
import com.unciv.ui.components.fonts.TextRole
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
import kotlin.math.ceil
import kotlin.math.sqrt

/** Screen-local tokens from the approved A technology atlas. No change to world-screen TechButton. */
internal class TechnologyAtlasStyle(val unit: Float) {
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
    fun font(value: TextRole) = UiTypography.size(value, unit)
    fun label(value: String, size: TextRole = TextRole.Body, color: Color = text, wrap: Boolean = false): Label =
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

    /** Drawn after the adjacent panels, so their shared edge stays flat and the outside stays rounded. */
    fun drawFrame(batch: Batch, x: Float, y: Float, width: Float, height: Float) {
        val old = batch.packedColor
        val dot = ImageGetter.getWhiteDotDrawable().region
        val radius = minOf(u(12f), width / 2, height / 2)
        val thickness = u(1f)
        fun strip(color: Color, sx: Float, sy: Float, w: Float, h: Float) {
            if (w <= 0f || h <= 0f) return
            batch.color = color
            batch.draw(dot, sx, sy, w, h)
        }
        strip(quietLine, x, y+radius, thickness, height-2*radius)
        strip(quietLine, x+width-thickness, y+radius, thickness, height-2*radius)
        val slices = ceil(radius).toInt().coerceAtLeast(1)
        val step = radius / slices
        for (i in 0 until slices) {
            val d = (i+.5f)*step
            val outer = radius-sqrt((radius*radius-(radius-d)*(radius-d)).coerceAtLeast(0f))
            val inner = if (d < thickness) width/2 else
                radius-sqrt(((radius-thickness)*(radius-thickness)-(radius-d)*(radius-d)).coerceAtLeast(0f))
            for (sy in floatArrayOf(y+i*step,y+height-(i+1)*step)) {
                strip(background,x,sy,outer,step)
                strip(background,x+width-outer,sy,outer,step)
                strip(quietLine,x+outer,sy,inner-outer,step)
                strip(quietLine,x+width-inner,sy,inner-outer,step)
            }
        }
        batch.packedColor = old
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

    fun decorate(button: TextButton, primary: Boolean = false, outlined: Boolean = false, size: TextRole = TextRole.Control): TextButton {
        button.style = buttonStyle(primary, outlined)
        button.label.setFontSize(font(size))
        button.label.setEllipsis(true)
        button.clearChildren()
        button.add(button.label).minWidth(0f)
        button.pad(u(7f), u(10f), u(7f), u(10f))
        return button
    }
    fun button(text: String, primary: Boolean = false, outlined: Boolean = false, size: TextRole = TextRole.Control) =
        decorate(TextButton(text.tr(hideIcons = true), buttonStyle()), primary, outlined, size)
}

/** Compact graph geometry is independent of the fixed-size reading and action panels. */
internal object TechnologyAtlasGraph {
    const val nodeWidth = 176f
    const val nodeHeight = 56f
    const val columnStep = 208f
    const val rowStep = 68f
    const val inset = 16f
    const val topInset = 48f
    const val minZoom = .3f
    const val maxZoom = 2f
}

/** Point-equivalent layout inside SafeAreaViewport; no fabricated notch/home-indicator insets. */
internal class TechnologyAtlasLayout(val width: Float, val height: Float) {
    val portrait = height > width
    val compact = height < 600f && !portrait
    val stacked = portrait && width <= 700f
    val padding = if (compact) 10f else if (stacked) 16f else 24f
    val gap = if (compact) 8f else 12f
    val detailsWidth = when { compact -> (width * .31f).coerceIn(232f, 258f); width <= 1000f -> 290f; else -> 316f }
    val contentWidth = (width - padding * 2).coerceAtLeast(1f)
    val headerHeight = if (compact) 48f else 56f
}
