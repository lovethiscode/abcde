package com.unciv.ui.popups

import com.unciv.Constants
import com.unciv.models.translations.tr
import com.unciv.ui.screens.LoadingScreen
import com.unciv.ui.screens.basescreen.BaseScreen


/**
 *  Mini popup just displays "Loading..." and opens itself.
 *
 *  Not to be confused with [LoadingScreen], which tries to preserve background as screenshot.
 *  That screen will use this once the screenshot is on-screen, though.
 */
class LoadingPopup(screen: BaseScreen) : Popup(screen,
    if (com.unciv.ui.components.widgets.UiNavigation.isIos &&
        com.unciv.UncivGame.getGameInfoOrNull()?.let { it.campaign == null && it.endless == null } != false)
        Scrollability.WithoutButtons else Scrollability.None, ordinaryShell = true) {
    init {
        if (ordinaryPresentation) {
            ordinaryPreferredWidth = 420f
            addDialogTitle("Loading game", dismissible = false)
            addGoodSizedLabel("Preparing the map and civilization data…".tr().replace("…", "...")).row()
            // Indeterminate progress only: no invented completion percentage.
            add(com.unciv.ui.components.widgets.LoadingImage(32f * popupUnit).apply { show() })
                .size(32f * popupUnit).padTop(12f * popupUnit)
        } else addGoodSizedLabel(Constants.loading)
        open(true)
    }
}
