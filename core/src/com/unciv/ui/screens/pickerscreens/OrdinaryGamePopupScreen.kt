package com.unciv.ui.screens.pickerscreens

import com.unciv.GUI
import com.unciv.ui.popups.Popup

/** Keeps picker workflows on their own input stage while presenting the approved game dialog. */
open class OrdinaryGamePopupScreen : PickerScreen() {
    private var dialog: Popup? = null

    protected fun presentGameDialog(popup: Popup) {
        stage.actors.toList().forEach { it.isVisible = false }
        dialog = popup
        popup.open(force = true)
    }

    protected fun showPickerDetails() {
        dialog?.close()
        dialog = null
        stage.actors.toList().forEach { it.isVisible = true }
    }

    override fun dispose() {
        dialog?.close()
        dialog = null
        super.dispose()
    }

    override fun resize(width: Int, height: Int) {
        if (dialog != null && GUI.isWorldLoaded()) GUI.getWorldScreen().resize(width, height)
        super.resize(width, height)
    }

    override fun render(delta: Float) {
        val world = if (dialog != null && GUI.isWorldLoaded()) GUI.getWorldScreen() else null
        if (world == null) { super.render(delta); return }
        world.stage.viewport.apply()
        world.stage.draw()
        stage.viewport.apply()
        stage.act(delta)
        stage.draw()
    }
}
