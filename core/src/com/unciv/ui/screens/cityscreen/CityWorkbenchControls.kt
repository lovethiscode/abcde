package com.unciv.ui.screens.cityscreen

import com.badlogic.gdx.scenes.scene2d.ui.TextButton
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
