package com.unciv.ui.screens.campaign

import com.unciv.logic.campaign.CampaignShareData
import com.unciv.ui.popups.LoadingPopup
import com.unciv.ui.popups.ToastPopup
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.utils.Concurrency

/** Keep the underlying screen and both scroll panes intact while the native preview is open. */
internal fun showCampaignShare(screen: BaseScreen, snapshot: () -> CampaignShareData?) {
    val loading = LoadingPopup(screen)
    Concurrency.runOnGLThread {
        try {
            screen.game.files.autosaves.autoSaveJob?.join()
            val data = snapshot()
            if (data == null) ToastPopup("Campaign save changed; please reopen the level.", screen)
            else screen.game.showCampaignShare(data)
        } catch (ex: Exception) {
            com.unciv.utils.Log.error("Cannot prepare campaign share", ex)
            ToastPopup("Campaign save changed; please reopen the level.", screen)
        } finally { loading.close() }
    }
}
