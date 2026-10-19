package com.unciv.ui.screens.worldscreen.mainmenu

import com.unciv.ui.components.fonts.UiTypography
import com.unciv.ui.components.fonts.TextRole
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.badlogic.gdx.scenes.scene2d.utils.BaseDrawable
import com.unciv.models.skins.ExpeditionBlueTheme
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.screens.basescreen.BaseScreen

/** Point-sized controls for the approved grouped menu, without changing the shared skin. */
internal class WorldScreenMenuStyle(private val unit: Float) {
    val background = ExpeditionBlueTheme.color(BaseScreen.skinStrings.skinConfig.baseColor, ExpeditionBlueTheme.background)
    val text = ExpeditionBlueTheme.color(Color.WHITE, ExpeditionBlueTheme.text)
    val muted = ExpeditionBlueTheme.color(Color.LIGHT_GRAY, ExpeditionBlueTheme.muted)
    val border = ExpeditionBlueTheme.color(Color.GRAY, ExpeditionBlueTheme.border)
    private val raised = ExpeditionBlueTheme.color(background.cpy().lerp(Color.WHITE, .12f), ExpeditionBlueTheme.panel)
    private val gold = ExpeditionBlueTheme.color(Color.GOLD, ExpeditionBlueTheme.gold)
    private val ink = ExpeditionBlueTheme.color(Color.BLACK, ExpeditionBlueTheme.primaryInk)
    fun px(value: Float) = value * unit
    fun font(value: TextRole) = UiTypography.size(value, unit)

    fun navigationStyle() = TextButton.TextButtonStyle(buttonStyle()).apply {
        val rule = ImageGetter.getWhiteDotDrawable().tint(border)
        up = object : BaseDrawable() {
            override fun draw(batch: Batch, x: Float, y: Float, width: Float, height: Float) {
                rule.draw(batch, x, y, width, unit)
            }
        }
    }

    fun surface(color: Color, radius: Float = 8f): BaseDrawable {
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

    fun buttonStyle(primary: Boolean = false, outline: Boolean = false) =
        TextButton.TextButtonStyle(BaseScreen.skin.get(TextButton.TextButtonStyle::class.java)).apply {
            up = when {
                primary -> surface(gold)
                outline -> ExpeditionBlueTheme.outlinedControl(surface(background), surface(border))
                else -> surface(raised)
            }
            down = surface(if (primary) gold.cpy().lerp(ink, .1f) else raised.cpy().lerp(Color.WHITE, .12f))
            over = down
            checked = up
            fontColor = if (primary) ink else text
            downFontColor = fontColor
            overFontColor = fontColor
            checkedFontColor = fontColor
        }
}
