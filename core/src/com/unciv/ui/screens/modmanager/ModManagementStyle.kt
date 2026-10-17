package com.unciv.ui.screens.modmanager

import com.unciv.ui.components.fonts.UiTypography
import com.unciv.ui.components.fonts.TextRole
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.scenes.scene2d.ui.Image
import com.badlogic.gdx.scenes.scene2d.utils.BaseDrawable
import com.unciv.ui.components.widgets.UiNavigation
import kotlin.math.atan2
import kotlin.math.sqrt
import com.unciv.models.skins.ExpeditionBlueTheme
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.unciv.ui.components.extensions.setFontSize
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.screens.basescreen.BaseScreen

/** Shared presentation for the Mod list, details and controls. */
internal object ModManagementStyle {
    val background get() = ExpeditionBlueTheme.color(Color.valueOf("101b2b"), ExpeditionBlueTheme.background)
    val surface get() = ExpeditionBlueTheme.color(Color.valueOf("172538"), ExpeditionBlueTheme.panel)
    val raised get() = ExpeditionBlueTheme.color(Color.valueOf("203249"), ExpeditionBlueTheme.raised)
    val selected get() = ExpeditionBlueTheme.color(Color.valueOf("343a3b"), ExpeditionBlueTheme.hero)
    val text get() = ExpeditionBlueTheme.color(Color.valueOf("edf1f6"), ExpeditionBlueTheme.text)
    val muted get() = ExpeditionBlueTheme.color(Color.valueOf("adbdce"), ExpeditionBlueTheme.muted)
    val accent get() = ExpeditionBlueTheme.color(Color.valueOf("e1bd78"), ExpeditionBlueTheme.gold)
    val line get() = ExpeditionBlueTheme.color(Color.valueOf("33465b"), ExpeditionBlueTheme.border)
    val danger get() = ExpeditionBlueTheme.color(Color.valueOf("f1a8a2"), ExpeditionBlueTheme.danger)

    val touchHeight get() = maxOf(64f, UiNavigation.minimumTouchSize())

    fun fill(color: Color) = ImageGetter.getWhiteDotDrawable().tint(color)

    fun rounded(color: Color) = BaseScreen.skinStrings.getUiBackground(
        "ModManagementScreen/Control",
        BaseScreen.skinStrings.roundedEdgeRectangleSmallShape,
        tintColor = color
    )

    fun label(value: String, size: TextRole = TextRole.Body, color: Color = text): Label =
        value.toLabel(size, color).apply { wrap = true }

    fun control(color: Color) = if (ExpeditionBlueTheme.enabled)
        ExpeditionBlueTheme.outlinedControl(rounded(color),
            rounded(if (color == accent) accent else ExpeditionBlueTheme.controlBorder)) else rounded(color)

    fun button(value: String, primary: Boolean = false): TextButton =
        value.toTextButton().also { styleButton(it, primary) }

    fun styleButton(button: TextButton, primary: Boolean = false) {
        button.style = TextButton.TextButtonStyle(button.style).apply {
            up = control(if (primary) accent else raised)
            down = control(selected)
            over = control(selected)
            disabled = control(ExpeditionBlueTheme.color(raised, ExpeditionBlueTheme.background))
            fontColor = if (primary) background else text
            downFontColor = text
            overFontColor = text
            disabledFontColor = muted
        }
        button.label.setFontSize(UiTypography.size(TextRole.Control, legacySize = 22))
        button.label.wrap = true
        button.labelCell.minWidth(0f).growX()
        button.pad(13f, 18f, 13f, 18f)
    }
}


/** Small, countable line icons, rendered through the game's existing white-dot texture. */
internal enum class ModIcon(vararg val lines: FloatArray) {
    Plus(floatArrayOf(12f,5f,12f,19f), floatArrayOf(5f,12f,19f,12f)),
    Filter(floatArrayOf(4f,7f,20f,7f), floatArrayOf(4f,17f,20f,17f),
        floatArrayOf(9f,4f,9f,10f), floatArrayOf(15f,14f,15f,20f)),
    Chevron(floatArrayOf(9f,6f,15f,12f), floatArrayOf(15f,12f,9f,18f)),
    Box(floatArrayOf(12f,3f,21f,8f), floatArrayOf(21f,8f,21f,17f), floatArrayOf(21f,17f,12f,22f),
        floatArrayOf(12f,22f,3f,17f), floatArrayOf(3f,17f,3f,8f), floatArrayOf(3f,8f,12f,3f),
        floatArrayOf(3f,8f,12f,13f), floatArrayOf(12f,13f,21f,8f), floatArrayOf(12f,13f,12f,22f));

    fun image(tint: Color = ModManagementStyle.accent) = Image(object : BaseDrawable() {
        override fun draw(batch: Batch, x: Float, y: Float, width: Float, height: Float) {
            val old = batch.packedColor
            batch.setColor(tint.r, tint.g, tint.b, batch.color.a)
            val region = ImageGetter.getWhiteDotDrawable().region
            val stroke = width * 1.7f / 24f
            for (line in lines) {
                val dx = (line[2] - line[0]) * width / 24f
                val dy = (line[1] - line[3]) * height / 24f
                batch.draw(region, x + line[0] * width / 24f, y + (24f-line[1]) * height / 24f-stroke/2,
                    0f, stroke/2, sqrt(dx*dx+dy*dy), stroke, 1f, 1f, atan2(dy,dx)*180f/Math.PI.toFloat())
            }
            batch.packedColor = old
        }
    })
}
