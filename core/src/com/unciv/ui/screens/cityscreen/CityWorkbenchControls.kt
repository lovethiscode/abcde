package com.unciv.ui.screens.cityscreen

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.scenes.scene2d.ui.SelectBox
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.badlogic.gdx.scenes.scene2d.utils.BaseDrawable
import com.badlogic.gdx.scenes.scene2d.utils.Drawable
import com.badlogic.gdx.scenes.scene2d.utils.TransformDrawable
import com.unciv.models.translations.tr
import com.unciv.ui.components.UncivTooltip.Companion.addTooltip
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.input.onActivation
import com.unciv.ui.components.widgets.UiNavigation
import com.unciv.ui.images.ImageGetter

/** Children of CityWorkbench are already in point coordinates. */
internal fun cityWorkbenchControl(text: String, id: String, enabled: Boolean = true, action: () -> Unit): TextButton =
    text.tr(hideIcons = true).toTextButton(UiNavigation.buttonStyle(1f), unit = 1f).apply {
        UiNavigation.styleTextButton(this, unit = 1f)
        name = id
        isDisabled = !enabled
        label.setEllipsis(false)
        onActivation { if (!isDisabled) action() }
    }

internal fun cityWorkbenchIcon(image: String, caption: String, id: String, enabled: Boolean = true, action: () -> Unit) =
    cityWorkbenchControl("", id, enabled, action).apply {
        clearChildren()
        pad(0f)
        add(ImageGetter.getImage(image).apply { if (!enabled) color.a = .4f }).size(20f)
        addTooltip(caption)
    }

/** Match the production toolbar's queue control without changing other city selectors. */
internal fun cityWorkbenchCategorySelectBox(): SelectBox<String> = cityWorkbenchSelectBox().apply {
    val button = UiNavigation.buttonStyle(unit = 1f)
    fun surface(body: Drawable, ink: Color, open: Boolean = false): Drawable {
        val stroke = ImageGetter.getWhiteDotDrawable().tint(ink) as TransformDrawable
        return object : BaseDrawable(body) {
            init {
                leftWidth = 13f
                rightWidth = 36f
                topHeight = 8f
                bottomHeight = 8f
                minWidth = 44f
                minHeight = 44f
            }

            override fun draw(batch: Batch, x: Float, y: Float, width: Float, height: Float) {
                body.draw(batch, x, y, width, height)
                // A two-stroke chevron sits in its own trailing space, clear of the text.
                val direction = if (open) -1f else 1f
                val left = x + width - 26f
                val middle = y + height / 2f
                stroke.draw(batch, left, middle + 2.5f * direction - .75f,
                    0f, .75f, 7.071f, 1.5f, 1f, 1f, -45f * direction)
                stroke.draw(batch, left + 5f, middle - 2.5f * direction - .75f,
                    0f, .75f, 7.071f, 1.5f, 1f, 1f, 45f * direction)
            }
        }
    }
    style = SelectBox.SelectBoxStyle(style).apply {
        fontColor = button.fontColor
        overFontColor = button.overFontColor ?: fontColor
        disabledFontColor = button.disabledFontColor ?: fontColor
        background = surface(button.up, fontColor)
        backgroundOver = surface(button.over ?: button.up, overFontColor)
        backgroundOpen = surface(button.down ?: button.up, fontColor, open = true)
        backgroundDisabled = surface(button.disabled ?: button.up, disabledFontColor)
    }
}

internal class CityWorkbenchPopup(screen: CityScreen, title: String, subtitle: String = "") : com.unciv.ui.popups.Popup(screen) {
    init { addDialogTitle(title, subtitle) }
}

internal fun cityWorkbenchMinus(id: String, enabled: Boolean, action: () -> Unit) =
    cityWorkbenchControl("", id, enabled, action).apply {
        clearChildren()
        pad(0f)
        add(ImageGetter.getWhiteDot().apply { if (!enabled) color.a = .4f }).size(16f, 2f)
        addTooltip("Unassign Specialist")
    }
