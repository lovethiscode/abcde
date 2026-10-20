package com.unciv.ui.popups

import com.unciv.ui.components.widgets.UiNavigation
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.math.Rectangle
import com.badlogic.gdx.utils.Align
import com.unciv.ui.components.extensions.addRoundCloseButton
import com.unciv.ui.components.extensions.top
import com.unciv.ui.components.input.ActivationTypes
import com.unciv.ui.components.input.clearActivationActions
import com.unciv.ui.components.widgets.ColorMarkupLabel
import com.unciv.ui.components.input.onClick
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.basescreen.SafeAreaViewport
import com.unciv.ui.components.fonts.TextRole
import com.unciv.ui.components.fonts.UiTypography
import com.unciv.ui.components.widgets.AutoScrollPane
import com.unciv.ui.components.input.onActivation
import com.unciv.UncivGame
import com.unciv.utils.Concurrency
import com.unciv.utils.launchOnGLThread
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay

/**
 * This is an unobtrusive popup which will close itself after a given amount of time.
 * - Will show on top of other Popups, but not on top of other ToastPopups
 * - Several calls in a short time will be shown sequentially, each "waiting their turn"
 * - The user can close a Toast by clicking it
 * - Supports color markup via [ColorMarkupLabel], using «» instead of Gdx's [].
 * @param time Duration in milliseconds, defaults to 2 seconds
 */
private fun ordinaryToast() = UiNavigation.isIos &&
    UncivGame.getGameInfoOrNull()?.let { it.campaign == null && it.endless == null } != false

class ToastPopup (message: String, stageToShowOn: Stage, val time: Long = 2000) :
    Popup(stageToShowOn, if (ordinaryToast()) Scrollability.None else Scrollability.WithoutButtons) {

    constructor(message: String, screen: BaseScreen, time: Long = 2000) : this(message, screen.stage, time)

    private var timerJob: Job? = null
    private val compact = ordinaryToast()
    private val messageLabel = ColorMarkupLabel(message)
    private var compactScroll: AutoScrollPane? = null
    private var dismiss: com.badlogic.gdx.scenes.scene2d.ui.TextButton? = null
    private var layoutSignature = ""

    init {
        //Make this popup unobtrusive
        setFillParent(false)
        messageLabel.apply {
            wrap = true
            setAlignment(if (compact) Align.left else Align.center)
        }
        if (compact) {
            background = null
            touchable = Touchable.childrenOnly
            compactScroll = AutoScrollPane(messageLabel).apply {
                setScrollingDisabled(true, false)
                setOverscroll(false, false)
            }
            add(compactScroll).minWidth(0f)
            dismiss = UiNavigation.dismissButton(popupUnit).apply {
                name = "ui-toast-close"
                onActivation { close() }
            }
            add(dismiss).top()
        } else {
            onClick(::stayVisible)
            add(messageLabel).width(stageToShowOn.width / 2)
        }

        open(force = stageToShowOn.actors.none { it is ToastPopup })

        // move it to the top so its not in the middle of the screen
        // has to be done after open() because open() centers the popup
        if (compact) layoutCompactToast()
        else y = stageToShowOn.height - (height + 20f)
    }

    private fun layoutCompactToast() {
        val unit = popupUnit
        val safe = (stageToShowOn.viewport as? SafeAreaViewport)?.safeAreaBoundsInWorld
            ?: Rectangle(0f, 0f, stageToShowOn.width, stageToShowOn.height)
        val available = (stageToShowOn as? com.unciv.ui.screens.basescreen.UncivStage)?.lastKnownVisibleArea ?: safe
        val bottom = maxOf(safe.y, available.y)
        val width = minOf(560f * unit, safe.width - 32f * unit).coerceAtLeast(1f)
        val bodyWidth = (width - 88f * unit).coerceAtLeast(1f)
        messageLabel.setFontScale(UiTypography.size(TextRole.Body, unit) / com.unciv.ui.components.fonts.Fonts.ORIGINAL_FONT_SIZE.toFloat())
        innerTable.background = OrdinaryPopupStyle(unit).surface(OrdinaryPopupStyle(unit).background, 12f)
        innerTable.pad(12f * unit, 16f * unit, 12f * unit, 16f * unit)
        compactScroll?.let {
            innerTable.getCell(it).width(bodyWidth).minWidth(0f)
                .maxHeight((safe.top - bottom - 64f * unit).coerceAtLeast(44f * unit))
        }
        dismiss?.let {
            it.style = UiNavigation.buttonStyle(unit)
            it.cells.firstOrNull()?.size(20f * unit)
            innerTable.getCell(it).size(44f * unit).padLeft(12f * unit)
        }
        innerTable.invalidateHierarchy()
        pack()
        x = safe.x + (safe.width - this.width) / 2
        y = bottom + 16f * unit
        layoutSignature = "${stageToShowOn.width}:${stageToShowOn.height}:$unit:$safe:$bottom"
    }

    override fun onVisibleAreaChanged(visibleArea: Rectangle) {
        if (compact) layoutCompactToast() else super.onVisibleAreaChanged(visibleArea)
    }

    override fun act(delta: Float) {
        super.act(delta)
        if (!compact) return
        val safe = (stageToShowOn.viewport as? SafeAreaViewport)?.safeAreaBoundsInWorld
            ?: Rectangle(0f, 0f, stageToShowOn.width, stageToShowOn.height)
        val bottom = maxOf(safe.y, (stageToShowOn as? com.unciv.ui.screens.basescreen.UncivStage)?.lastKnownVisibleArea?.y ?: 0f)
        if (layoutSignature != "${stageToShowOn.width}:${stageToShowOn.height}:$popupUnit:$safe:$bottom") layoutCompactToast()
    }

    private fun startTimer() {
        timerJob = Concurrency.run("ResponsePopup") {
            delay(time)
            timerJob = null
            launchOnGLThread { this@ToastPopup.close() }
        }
    }
    private fun stopTimer() {
        timerJob?.cancel()
        timerJob = null
    }

    override fun close() {
        stopTimer()
        super.close()
    }

    override fun setVisible(visible: Boolean) {
        if (visible)
            startTimer()
        super.setVisible(visible)
    }

    private fun stayVisible() {
        stopTimer()
        clearActivationActions(ActivationTypes.Tap, false)
        onClick(::close)
        if (UiNavigation.isIos) {
            addCloseButton()
            pack()
            y = stageToShowOn.height - (height + 20f)
        } else addRoundCloseButton(this, ::close)
    }
}
