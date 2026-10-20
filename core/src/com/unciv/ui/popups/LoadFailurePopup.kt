package com.unciv.ui.popups

import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.ui.Value
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.fonts.TextRole
import com.unciv.ui.components.fonts.UiTypography
import com.unciv.ui.components.widgets.ExpanderTab
import com.unciv.ui.screens.basescreen.BaseScreen

/** The caller retains the selected file and controls retry; this dialog never rewrites a save. */
class LoadFailurePopup(screen: BaseScreen, message: String, retry: () -> Unit) : Popup(screen, ordinaryShell = true) {
    init {
        ordinaryPreferredWidth = 480f
        addDialogTitle("Unable to load game", "Load failed")
        addGoodSizedLabel("The game was not loaded. Try again, or return to choose another save.").row()
        val details = ExpanderTab("View error details", fontSize = UiTypography.size(TextRole.Control, popupUnit),
            startsOutOpened = false, defaultPad = 0f, headerPad = 12f * popupUnit,
            onChange = { refreshOrdinaryPopupLayout() }) { content ->
            content.add(message.toLabel(TextRole.Body, unit = popupUnit).apply { wrap = true })
                .minWidth(0f).width(object : Value() {
                    override fun get(context: Actor?) = (popupContentWidth - 24f * popupUnit).coerceAtLeast(1f)
                }).growX()
        }
        add(details).growX().row()
        addCloseButton("Back")
        addOKButton("Retry") { retry() }
        open()
    }
}
