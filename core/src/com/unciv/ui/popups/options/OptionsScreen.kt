package com.unciv.ui.popups.options

import com.unciv.logic.event.EventBus
import com.unciv.ui.components.input.KeyCharAndCode
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.basescreen.RecreateOnResize
import com.unciv.ui.screens.basescreen.UncivStage

/** Full-page presentation of the existing settings content, with its own navigation stack entry. */
class OptionsScreen(
    internal val sourceScreen: BaseScreen,
    page: OptionsPopupPages = OptionsPopup.defaultPage,
    private val withDebug: Boolean = false,
    private val onClose: () -> Unit = {},
    private val subSelect: String? = null
) : BaseScreen(), RecreateOnResize {
    private val events = EventBus.EventReceiver()
    private val options = OptionsPopup(this, page, withDebug, subSelect = subSelect)
    private var closed = false

    init {
        options.mountOnScreen()
        globalShortcuts.add(KeyCharAndCode.BACK) { close() }
        events.receive(UncivStage.VisibleAreaChanged::class) {
            options.layoutScreen(it.visibleArea)
        }
    }

    override fun recreate(): OptionsScreen {
        val page = options.activePage
        val scroll = options.tabs.getPageScrollY(options.tabs.activePage)
        options.finishEditing()
        return OptionsScreen(sourceScreen, page, withDebug, onClose, subSelect).also {
            it.options.tabs.setPageScrollY(it.options.tabs.activePage, scroll)
        }
    }

    internal fun close() {
        if (closed) return
        closed = true
        options.finishEditing()
        game.popScreen()
        onClose()
    }

    override fun pause() {
        game.settings.save()
    }

    override fun dispose() {
        events.stopReceiving()
        options.disposeContentFont()
        super.dispose()
    }
}
