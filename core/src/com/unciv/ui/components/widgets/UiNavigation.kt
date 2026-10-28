package com.unciv.ui.components.widgets

import com.unciv.ui.components.fonts.UiTypography
import com.unciv.ui.components.fonts.TextRole
import com.badlogic.gdx.Application
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.graphics.g2d.NinePatch
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.ui.Image
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.badlogic.gdx.scenes.scene2d.utils.BaseDrawable
import com.badlogic.gdx.scenes.scene2d.utils.NinePatchDrawable
import com.badlogic.gdx.utils.Align
import com.unciv.UncivGame
import com.unciv.models.skins.ExpeditionBlueTheme
import com.unciv.models.translations.tr
import com.unciv.ui.components.UncivTooltip.Companion.addTooltip
import com.unciv.ui.components.extensions.setFontSize
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.utils.Display
import kotlin.math.atan2
import kotlin.math.sqrt

/** Navigation changes presentation only; callers retain their actions and keyboard bindings. */
object UiNavigation {
    val isIos get() = Gdx.app?.type == Application.ApplicationType.iOS
    val isAndroid get() = Gdx.app?.type == Application.ApplicationType.Android
    val isMobile get() = isIos || isAndroid
    val backCaption get() = "Back".tr()

    fun minimumTouchSize(): Float {
        if (isAndroid) return 48f * UiTypography.unitsPerPoint()
        if (!isIos) return 44f
        val safe = Display.getSafeArea(Gdx.graphics.width, Gdx.graphics.height)
        return touchSize(UncivGame.Current.settings.screenSize.getHeight(), minOf(safe.width, safe.height).toFloat())
    }

    fun touchSize(virtualSize: Float, safePoints: Float) = 44f * virtualSize / safePoints

    private fun rounded(color: Color, radius: Float, unit: Float) =
        (BaseScreen.skin.getDrawable("RoundedEdgeRectangle") as NinePatchDrawable).let { source ->
            NinePatchDrawable(NinePatch(source.patch, color).apply {
                scale(radius * unit / leftWidth, radius * unit / topHeight)
                setPadding(0f, 0f, 0f, 0f)
            }).apply { minWidth = 0f; minHeight = 0f }
        }

    /** Shared point-sized surface geometry for ordinary popup controls. */
    fun roundedSurface(color: Color, radius: Float, unit: Float): BaseDrawable = rounded(color, radius, unit)

    fun buttonStyle(unit: Float = minimumTouchSize() / 44f): TextButton.TextButtonStyle =
        TextButton.TextButtonStyle(BaseScreen.skin.get(TextButton.TextButtonStyle::class.java)).apply {
            if (!ExpeditionBlueTheme.enabled) return@apply
            fun surface(fill: Color, edge: Color): BaseDrawable {
                val outside = rounded(edge, 8f, unit)
                val inside = rounded(fill, 7f, unit)
                return object : BaseDrawable() {
                    override fun draw(batch: Batch, x: Float, y: Float, width: Float, height: Float) {
                        outside.draw(batch, x, y, width, height)
                        inside.draw(batch, x + unit, y + unit,
                            (width - 2 * unit).coerceAtLeast(0f), (height - 2 * unit).coerceAtLeast(0f))
                    }
                }
            }
            up = surface(Color.valueOf("193743"), Color.valueOf("577786"))
            over = surface(Color.valueOf("2B4B58"), ExpeditionBlueTheme.controlBorder)
            down = surface(Color.valueOf("355966"), ExpeditionBlueTheme.controlBorder)
            checked = up
            checkedOver = over
            checkedDown = down
            disabled = surface(ExpeditionBlueTheme.background, ExpeditionBlueTheme.border)
            fontColor = Color.valueOf("ECF2F3")
            overFontColor = fontColor; downFontColor = fontColor
            checkedFontColor = fontColor; checkedOverFontColor = fontColor; checkedDownFontColor = fontColor
            disabledFontColor = ExpeditionBlueTheme.muted
        }

    private fun button(unit: Float): TextButton = object : TextButton("", buttonStyle(unit)) {
        override fun getMinWidth() = maxOf(super.getMinWidth(), 44f * unit)
        override fun getMinHeight() = maxOf(super.getMinHeight(), 44f * unit)
        override fun getPrefWidth() = maxOf(super.getPrefWidth(), 44f * unit)
        override fun getPrefHeight() = maxOf(super.getPrefHeight(), 44f * unit)
    }

    /** [unit] is 1 for parents (such as CityWorkbench) that already apply point scaling. */
    fun backButton(caption: String = "Back", unit: Float = minimumTouchSize() / 44f): TextButton =
        button(unit).apply {
            name = "ui-navigation-back"
            if (isMobile) styleBackButton(this, caption, unit)
            else {
                setText(caption.tr())
                labelCell.pad(8f)
                setSize(prefWidth, prefHeight)
            }
        }

    fun styleBackButton(button: TextButton, caption: String = "Back", unit: Float = minimumTouchSize() / 44f): TextButton {
        styleTextButton(button, unit)
        button.setText(caption.tr())
        if (isMobile) {
            button.clearChildren()
            button.add(icon(button, close = false)).size(20f * unit).padRight(7f * unit)
            button.add(button.label).minWidth(0f)
        }
        button.setSize(button.prefWidth.coerceAtLeast(44f * unit), button.prefHeight.coerceAtLeast(44f * unit))
        return button
    }

    /** Remove navigation artwork when a picker repurposes its back button as a workflow action. */
    fun styleTextButton(button: TextButton, unit: Float = minimumTouchSize() / 44f): TextButton = button.apply {
        style = buttonStyle(unit)
        clearChildren()
        pad(0f, 13f * unit, 0f, 13f * unit)
        label.setFontSize(UiTypography.size(TextRole.Control, unit))
        add(label).minWidth(0f)
    }

    fun dismissButton(unit: Float = minimumTouchSize() / 44f): TextButton = button(unit).apply {
        name = "ui-popup-close"
        if (!isMobile) {
            setText("×")
            label.setFontSize(18)
            setSize(50f, 50f)
            return@apply
        }
        clearChildren()
        pad(0f)
        add(icon(this, close = true)).size(20f * unit)
        setSize(44f * unit, 44f * unit)
        addTooltip("Close")
    }

    /** Quiet paging controls use the same surface and stroke weight as back navigation. */
    fun directionButton(forward: Boolean, caption: String, unit: Float = minimumTouchSize() / 44f): TextButton =
        button(unit).apply {
            name = if (forward) "ui-navigation-next" else "ui-navigation-previous"
            clearChildren()
            pad(0f)
            add(icon(this, close = false, forward = forward)).size(20f * unit)
            setSize(44f * unit, 44f * unit)
            addTooltip(caption)
        }

    /** The same two-stroke control geometry is used for every back arrow and dismiss mark. */
    private fun icon(button: TextButton, close: Boolean, forward: Boolean? = null): Image {
        val lines = if (close) arrayOf(floatArrayOf(6f, 6f, 18f, 18f), floatArrayOf(18f, 6f, 6f, 18f))
            else if (forward == true) arrayOf(floatArrayOf(8.5f, 5f, 15.5f, 12f), floatArrayOf(15.5f, 12f, 8.5f, 19f))
            else if (forward == false) arrayOf(floatArrayOf(15.5f, 5f, 8.5f, 12f), floatArrayOf(8.5f, 12f, 15.5f, 19f))
            else arrayOf(floatArrayOf(14f, 5f, 7f, 12f), floatArrayOf(7f, 12f, 14f, 19f))
        val drawable = object : BaseDrawable() {
            override fun draw(batch: Batch, x: Float, y: Float, width: Float, height: Float) {
                val region = ImageGetter.getWhiteDotDrawable().region
                val stroke = width * 1.8f / 24f
                for (line in lines) {
                    val dx = (line[2] - line[0]) * width / 24f
                    val dy = (line[1] - line[3]) * height / 24f
                    batch.draw(region, x + line[0] * width / 24f, y + (24f - line[1]) * height / 24f - stroke / 2,
                        0f, stroke / 2, sqrt(dx * dx + dy * dy), stroke, 1f, 1f, atan2(dy, dx) * 180f / Math.PI.toFloat())
                }
            }
        }
        return object : Image(drawable) {
            override fun draw(batch: Batch, parentAlpha: Float) {
                color = (if (button.isDisabled) button.style.disabledFontColor else button.style.fontColor) ?: Color.WHITE
                super.draw(batch, parentAlpha)
            }
        }
    }

    fun popupHeader(title: Actor, closeButton: Actor): Table {
        if (title is Label) {
            title.setAlignment(Align.center)
            title.wrap = true
        }
        if (title is Table) title.center()
        return Table(BaseScreen.skin).apply {
            add().width(closeButton.width).padRight(12f)
            add(title).minWidth(0f).growX().center()
            add(closeButton).size(closeButton.width, closeButton.height).padLeft(12f)
        }
    }
}
