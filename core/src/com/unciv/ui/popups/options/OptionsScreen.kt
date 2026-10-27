package com.unciv.ui.popups.options

import com.badlogic.gdx.Application.ApplicationType
import com.badlogic.gdx.Gdx
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

    override fun show() {
        super.show()
        if (!com.unciv.ui.components.widgets.UiNavigation.isMobile) return
        // Keep editing, glyph uploads and expander animations drawing while this screen is visible.
        Gdx.graphics.isContinuousRendering = true
    }

    override fun hide() {
        if (com.unciv.ui.components.widgets.UiNavigation.isMobile)
            Gdx.graphics.isContinuousRendering = game.settings.continuousRendering
        super.hide()
    }

    override fun dispose() {
        events.stopReceiving()
        super.dispose()
    }
}
