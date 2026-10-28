package com.unciv.ui.screens.campaign

import com.unciv.logic.campaign.CampaignShareData
import com.unciv.ui.popups.LoadingPopup
import com.unciv.ui.popups.Popup
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.utils.Concurrency

/** Keep the underlying screen and both scroll panes intact while the native preview is open. */
internal fun showCampaignShare(screen: BaseScreen, refresh: () -> Unit = {}, snapshot: () -> CampaignShareData?) {
    val loading = LoadingPopup(screen)
    Concurrency.runOnGLThread {
        try {
            screen.game.files.autosaves.autoSaveJob?.join()
            val data = snapshot()
            if (data == null) {
                refresh()
                Popup(screen, ordinaryShell = true).apply {
                    addGoodSizedLabel("Campaign save changed; please reopen the level.").row()
                    addCloseButton("Got it")
                    open()
                }
            }
            else screen.game.showCampaignShare(data)
        } catch (ex: Exception) {
            refresh()
            com.unciv.utils.Log.error("Cannot prepare campaign share", ex)
            Popup(screen, ordinaryShell = true).apply {
                    addGoodSizedLabel("Campaign save changed; please reopen the level.").row()
                    addCloseButton("Got it")
                    open()
                }
        } finally { loading.close() }
    }
}
