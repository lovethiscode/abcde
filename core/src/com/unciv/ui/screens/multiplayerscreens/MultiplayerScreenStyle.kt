package com.unciv.ui.screens.multiplayerscreens

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.scenes.scene2d.ui.Image
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.badlogic.gdx.scenes.scene2d.utils.BaseDrawable
import com.badlogic.gdx.scenes.scene2d.utils.Drawable
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable
import com.unciv.models.skins.ExpeditionBlueTheme
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.setFontSize
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.screens.basescreen.BaseScreen
import kotlin.math.atan2
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Approved multiplayer layout using the shared iOS surface palette. */
internal class MultiplayerScreenStyle(val unit: Float) {
    val background = ExpeditionBlueTheme.color(Color.valueOf("102d43"), ExpeditionBlueTheme.background)
    val panel = ExpeditionBlueTheme.color(Color.valueOf("17394f"), ExpeditionBlueTheme.panel)
    val control = ExpeditionBlueTheme.color(Color.valueOf("21465d"), ExpeditionBlueTheme.raised)
    val selected = ExpeditionBlueTheme.color(Color.valueOf("234f68"), ExpeditionBlueTheme.hero)
    val text = Color.valueOf("f1f4f3")
    val muted = Color.valueOf("b2c9d4")
    val border = Color.valueOf("3c5e73")
    val gold = Color.valueOf("edcc87")
    val ink = Color.valueOf("183248")
    val positive = Color.valueOf("a7e6cc")
    val positiveSurface = Color.valueOf("264e50")
    val warning = Color.valueOf("f0d18e")
    val warningSurface = Color.valueOf("554a31")
    val danger = Color.valueOf("ffc2c1")
    val dangerSurface = Color.valueOf("553b46")
    fun px(value: Float) = value * unit
    fun font(value: Int) = (value * unit).roundToInt().coerceAtLeast(1)
    fun surface(color: Color, radius: Float = 10f): Drawable {
        val dot = ImageGetter.getWhiteDotDrawable().tint(color)
        val circle = ImageGetter.getCircleDrawable().tint(color)
        return object : BaseDrawable() {
            override fun draw(batch: Batch, x: Float, y: Float, width: Float, height: Float) {
                val r = minOf(px(radius), width / 2, height / 2)
                circle.draw(batch, x, y, r * 2, r * 2)
                circle.draw(batch, x + width - r * 2, y, r * 2, r * 2)
                circle.draw(batch, x, y + height - r * 2, r * 2, r * 2)
                circle.draw(batch, x + width - r * 2, y + height - r * 2, r * 2, r * 2)
                dot.draw(batch, x + r, y, (width - r * 2).coerceAtLeast(0f), height)
                dot.draw(batch, x, y + r, width, (height - r * 2).coerceAtLeast(0f))
            }
        }
    }
    fun outline(color: Color = background): Drawable {
        val edge = surface(border)
        val fill = surface(color, 9f)
        return object : BaseDrawable(fill) {
            override fun draw(batch: Batch, x: Float, y: Float, width: Float, height: Float) {
                edge.draw(batch, x, y, width, height)
                fill.draw(batch, x + unit, y + unit, (width - 2 * unit).coerceAtLeast(0f), (height - 2 * unit).coerceAtLeast(0f))
            }
        }
    }

    fun buttonStyle(primary: Boolean = false, outlined: Boolean = false, ghost: Boolean = false) =
        TextButton.TextButtonStyle(BaseScreen.skin.get(TextButton.TextButtonStyle::class.java)).apply {
            up = when { primary -> surface(gold); outlined -> outline(); ghost -> null; else -> surface(control) }
            down = surface(if (primary) gold.cpy().mul(.9f, .9f, .9f, 1f) else selected)
            over = down
            checked = surface(selected)
            disabled = surface(control)
            fontColor = if (primary) ink else text
            checkedFontColor = text
            downFontColor = fontColor
            overFontColor = fontColor
            disabledFontColor = muted
        }

    fun decorate(button: TextButton, icon: MultiplayerIcon? = null, primary: Boolean = false,
                 outlined: Boolean = false, ghost: Boolean = false, fontSize: Int = 14): TextButton {
        button.style = buttonStyle(primary, outlined, ghost)
        button.label.setFontSize(font(fontSize))
        button.label.setEllipsis(true)
        button.clearChildren()
        button.pad(0f, px(14f), 0f, px(14f))
        if (icon != null) button.add(image(icon, if (primary) ink else text)).size(px(20f))
        if (icon == null || button.text.isNotEmpty()) button.add(button.label).minWidth(0f).padLeft(if (icon == null) 0f else px(8f))
        return button
    }

    fun button(caption: String, icon: MultiplayerIcon? = null, primary: Boolean = false,
               outlined: Boolean = false, ghost: Boolean = false, fontSize: Int = 14) =
        decorate(TextButton(caption.tr(), buttonStyle()), icon, primary, outlined, ghost, fontSize)

    fun image(icon: MultiplayerIcon, tint: Color = text) = Image(object : BaseDrawable() {
        override fun draw(batch: Batch, x: Float, y: Float, width: Float, height: Float) {
            val oldColor = batch.packedColor
            batch.setColor(tint.r, tint.g, tint.b, batch.color.a)
            val region = ImageGetter.getWhiteDotDrawable().region
            val stroke = width * 1.7f / 24f
            for (line in icon.lines) {
                val x1 = x + line[0] * width / 24f
                val y1 = y + (24f - line[1]) * height / 24f
                val dx = (line[2] - line[0]) * width / 24f
                val dy = (line[1] - line[3]) * height / 24f
                val length = sqrt(dx * dx + dy * dy)
                batch.draw(region, x1, y1 - stroke / 2, 0f, stroke / 2, length, stroke,
                    1f, 1f, atan2(dy, dx) * 180f / Math.PI.toFloat())
            }
            batch.packedColor = oldColor
        }
    })
}

/** Countable line geometry, matching the comp's single stroke icon family. */
internal enum class MultiplayerIcon(vararg val lines: FloatArray) {
    Back(floatArrayOf(14f,6f,8f,12f), floatArrayOf(8f,12f,14f,18f)),
    Plus(floatArrayOf(12f,5f,12f,19f), floatArrayOf(5f,12f,19f,12f)),
    More(floatArrayOf(4f,12f,6f,12f), floatArrayOf(11f,12f,13f,12f), floatArrayOf(18f,12f,20f,12f)),
    Link(floatArrayOf(8f,16f,16f,8f), floatArrayOf(6f,9f,2f,13f), floatArrayOf(2f,13f,2f,17f),
        floatArrayOf(2f,17f,6f,21f), floatArrayOf(6f,21f,10f,17f), floatArrayOf(14f,7f,18f,3f),
        floatArrayOf(18f,3f,22f,7f), floatArrayOf(22f,7f,22f,11f), floatArrayOf(22f,11f,18f,15f)),
    Download(floatArrayOf(12f,3f,12f,15f), floatArrayOf(7f,10f,12f,15f), floatArrayOf(12f,15f,17f,10f),
        floatArrayOf(5f,16f,5f,21f), floatArrayOf(5f,21f,19f,21f), floatArrayOf(19f,21f,19f,16f)),
    Share(floatArrayOf(12f,15f,12f,3f), floatArrayOf(8f,7f,12f,3f), floatArrayOf(12f,3f,16f,7f),
        floatArrayOf(5f,10f,4f,10f), floatArrayOf(4f,10f,4f,21f), floatArrayOf(4f,21f,20f,21f),
        floatArrayOf(20f,21f,20f,10f), floatArrayOf(20f,10f,19f,10f)),
    Refresh(floatArrayOf(4f,9f,7f,5f), floatArrayOf(7f,5f,15f,5f), floatArrayOf(15f,5f,20f,10f),
        floatArrayOf(20f,5f,20f,10f), floatArrayOf(20f,10f,15f,10f), floatArrayOf(20f,15f,17f,19f),
        floatArrayOf(17f,19f,9f,19f), floatArrayOf(9f,19f,4f,14f), floatArrayOf(4f,19f,4f,14f), floatArrayOf(4f,14f,9f,14f)),
    Check(floatArrayOf(5f,12f,9f,16f), floatArrayOf(9f,16f,19f,6f)),
    Clock(floatArrayOf(12f,3f,18f,6f), floatArrayOf(18f,6f,21f,12f), floatArrayOf(21f,12f,18f,18f),
        floatArrayOf(18f,18f,12f,21f), floatArrayOf(12f,21f,6f,18f), floatArrayOf(6f,18f,3f,12f),
        floatArrayOf(3f,12f,6f,6f), floatArrayOf(6f,6f,12f,3f), floatArrayOf(12f,7f,12f,12f), floatArrayOf(12f,12f,15f,14f)),
    Temple(floatArrayOf(3f,8f,12f,3f), floatArrayOf(12f,3f,21f,8f), floatArrayOf(21f,8f,3f,8f),
        floatArrayOf(5f,10f,5f,18f), floatArrayOf(10f,10f,10f,18f), floatArrayOf(14f,10f,14f,18f),
        floatArrayOf(19f,10f,19f,18f), floatArrayOf(3f,18f,21f,18f), floatArrayOf(3f,21f,21f,21f)),
    Games(floatArrayOf(4f,4f,20f,4f), floatArrayOf(20f,4f,20f,20f), floatArrayOf(20f,20f,4f,20f),
        floatArrayOf(4f,20f,4f,4f), floatArrayOf(8f,9f,12f,9f), floatArrayOf(10f,7f,10f,11f),
        floatArrayOf(14f,17f,16f,15f)),
    Users(floatArrayOf(6f,4f,12f,4f), floatArrayOf(12f,4f,13f,8f), floatArrayOf(13f,8f,10f,11f),
        floatArrayOf(10f,11f,6f,8f), floatArrayOf(6f,8f,6f,4f), floatArrayOf(3f,21f,3f,16f),
        floatArrayOf(3f,16f,7f,13f), floatArrayOf(7f,13f,12f,13f), floatArrayOf(12f,13f,16f,16f),
        floatArrayOf(16f,16f,16f,21f), floatArrayOf(17f,4f,20f,7f), floatArrayOf(20f,7f,17f,11f),
        floatArrayOf(19f,14f,22f,17f), floatArrayOf(22f,17f,22f,21f));
}
