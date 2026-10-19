package com.unciv.ui.popups

import com.unciv.ui.components.fonts.TextRole
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.ui.TextButton.TextButtonStyle
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.utils.Align
import com.unciv.Constants
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.input.KeyboardBinding
import com.unciv.ui.screens.basescreen.BaseScreen

/** Variant of [Popup] pre-populated with one label, plus confirm and cancel buttons
 * @param stageToShowOn Parent [Stage], see [Popup.stageToShowOn]
 * @param question The text for the label
 * @param confirmText The text for the "Confirm" button
 * @param isConfirmPositive If the action to be performed is positive or not (i.e. buy = positive, delete = negative), default false
 * @param action A lambda to execute when "Yes" is chosen
 * @param restoreDefault A lambda to execute when "No" is chosen
 */
open class ConfirmPopup(
    stageToShowOn: Stage,
    question: String,
    confirmText: String,
    isConfirmPositive: Boolean = false,
    restoreDefault: () -> Unit = {},
    presentation: Presentation? = null,
    action: () -> Unit
) : Popup(stageToShowOn) {

    data class Presentation(
        val title: String,
        val subtitle: String = "",
        val message: String = "",
        val facts: List<Pair<String, String>> = emptyList(),
        val details: String = "",
        val confirmCaption: String? = null
    )

    constructor(
        screen: BaseScreen,
        question: String,
        confirmText: String,
        isConfirmPositive: Boolean = false,
        restoreDefault: () -> Unit = {},
        presentation: Presentation? = null,
        action: () -> Unit
    ) : this(screen.stage, question, confirmText, isConfirmPositive, restoreDefault, presentation, action)

    /** The [Label][com.badlogic.gdx.scenes.scene2d.ui.Label] created for parameter `question` for optional layout tweaking */
    private val promptLabel = question.toLabel(TextRole.Body)

    init {
        if (ordinaryPresentation && presentation != null) {
            addDialogTitle(presentation.title, presentation.subtitle)
            if (presentation.message.isNotBlank()) addGoodSizedLabel(presentation.message).row()
            for ((name, value) in presentation.facts) addFactRow(name, value)
            if (presentation.details.isNotBlank()) add(com.unciv.ui.components.widgets.ExpanderTab("View effects",
                com.unciv.ui.components.fonts.UiTypography.size(TextRole.Control, popupUnit), startsOutOpened = false) {
                it.add(presentation.details.toLabel(TextRole.Body, unit = popupUnit).apply { wrap = true })
                    .width(popupContentWidth - 24f * popupUnit)
            }).width(popupContentWidth).padTop(12f * popupUnit).row()
        } else {
            if (ordinaryPresentation) addTitleWithCloseButton(confirmText.toLabel(TextRole.PageTitle))
            promptLabel.setAlignment(if (ordinaryPresentation) Align.left else Align.center)
            add(promptLabel).colspan(2).row()
            if (ordinaryPresentation) wrapPrompt(popupContentWidth)
        }
        addCloseButton(Constants.cancel, KeyboardBinding.Cancel, action = restoreDefault)
        val confirmStyleName = if (isConfirmPositive) "positive" else "negative"
        val confirmStyle = BaseScreen.skin.get(confirmStyleName, TextButtonStyle::class.java)
        addOKButton(if (ordinaryPresentation) presentation?.confirmCaption ?: confirmText else confirmText,
            KeyboardBinding.Confirm, confirmStyle, action = action).actor.name = "ui-confirm"
        equalizeLastTwoButtonWidths()
    }

    /** Opt-in wrapping for responsive callers; existing confirmation sizing stays unchanged. */
    fun wrapPrompt(width: Float) {
        promptLabel.wrap = true
        (promptLabel.parent as? Table)?.getCell(promptLabel)?.width(width.coerceAtLeast(1f))
        invalidateHierarchy()
    }

}
