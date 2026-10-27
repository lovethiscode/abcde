package com.unciv.ui.popups

import com.unciv.ui.components.fonts.UiTypography
import com.unciv.ui.components.fonts.Fonts
import com.unciv.ui.components.fonts.TextRole
import com.unciv.ui.components.widgets.UiNavigation
import com.unciv.models.skins.ExpeditionBlueTheme
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.ui.Button
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.unciv.ui.components.extensions.setFontSize
import com.unciv.ui.components.extensions.isEnabled
import com.unciv.ui.components.widgets.UncivTextField
import com.unciv.ui.components.input.onChange
import com.unciv.ui.components.input.onClick
import com.unciv.ui.components.extensions.surroundWithCircle
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.extensions.toStringSigned
import com.unciv.ui.images.IconCircleGroup
import com.unciv.ui.images.ImageGetter

/** Simple class for showing a prompt for a positive integer to the user
 * @param stageToShowOn The stage of the previous screen the user was on
 * @param label A line of text shown to the user
 * @param icon Icon at the top, should have size 80f
 * @param defaultValue The number that should be in the prompt at the start
 * @param amountButtons Buttons that when clicked will add/subtract these amounts to the number
 * @param bounds The bounds in which the number must lie. Defaults to [Int.MIN_VALUE, Int.MAX_VALUE]
 * @param errorText Text that will be shown when an error is detected
 * @param validate Function that should return `true` when a valid input is detected
 * @param actionOnOk Lambda that will be executed after pressing 'OK'.
 */

class AskNumberPopup(
    stageToShowOn: Stage,
    label: String,
    icon: IconCircleGroup = ImageGetter.getImage("OtherIcons/Pencil").apply { this.color = ImageGetter.CHARCOAL }.surroundWithCircle(80f),
    defaultValue: Int? = null,
    amountButtons: List<Int> = listOf(),
    bounds: IntRange = IntRange(Int.MIN_VALUE, Int.MAX_VALUE),
    errorText: String = "Invalid input! Please enter a valid number.",
    validate: (input: Int) -> Boolean = { true },
    actionOnOk: (input: Int) -> Unit = { },
): Popup(stageToShowOn) {
    init {
        val wrapper = Table()
        wrapper.add(icon).padRight(10f)
        wrapper.add(label.toLabel(TextRole.Body))
        if (ordinaryPresentation) {
            addDialogTitle(if (label == "Enter the amount of gold") "Set trade amount" else label,
                if (label == "Enter the amount of gold") "Gold · Trade offer" else "")
            addGoodSizedLabel(if (label == "Enter the amount of gold") "Amount" else label,
                UiTypography.size(TextRole.Control, popupUnit)).apply { actor.name = "ui-form-field-label" }.row()
        }
        else add(wrapper).colspan(2).row()

        val valueField = UncivTextField.Integer(label, defaultValue)
        if (ordinaryPresentation) {
            // Step buttons use the text setter too. Revalidate those edits just
            // like keyboard input, including recovery from an out-of-range value.
            valueField.setProgrammaticChangeEvents(true)
            valueField.style = com.badlogic.gdx.scenes.scene2d.ui.TextField.TextFieldStyle(valueField.style).apply {
                font = Fonts.getUiFont(UiTypography.size(TextRole.Control, popupUnit))
                if (ExpeditionBlueTheme.enabled) background = OrdinaryPopupStyle(popupUnit).let {
                    it.outlined(it.deep, radius = 8f)
                }
            }
        }
        valueField.name = "ui-number-input"

        val centerTable = Table(skin)

        fun addValueButton(delta: Int) {
            centerTable.add(
                (if (ordinaryPresentation) com.badlogic.gdx.scenes.scene2d.ui.TextButton(delta.toStringSigned(), OrdinaryPopupStyle(popupUnit).button()).apply {
                    this.label.setFontSize(UiTypography.size(TextRole.Control, popupUnit))
                } else Button(delta.toStringSigned().toLabel(TextRole.Body), skin)).apply {
                    onClick {
                        val value = valueField.intValue ?: return@onClick
                        valueField.intValue = if (ordinaryPresentation)
                            (value.toLong() + delta).coerceIn(bounds.first.toLong(), bounds.last.toLong()).toInt()
                        else (value + delta).coerceIn(bounds)
                    }
                }
            ).pad(5f).apply { if (ordinaryPresentation) minSize(44f * popupUnit).growX() }
        }

        if (ordinaryPresentation) {
            add(valueField).width(popupContentWidth).minHeight(48f * popupUnit).colspan(2).row()
            addGoodSizedLabel("Enter an integer from [${bounds.first}] to [${bounds.last}].",
                UiTypography.size(TextRole.Supporting, popupUnit)).row()
        }
        for (value in amountButtons.reversed()) {
            addValueButton(-value)
        }

        if (!ordinaryPresentation) centerTable.add(valueField).growX().pad(10f)

        add(centerTable).colspan(2).row()

        for (value in amountButtons) {
            addValueButton(value)
        }

        val errorLabel = errorText.toLabel(if (ordinaryPresentation) TextRole.Supporting else TextRole.Body)
        if (ordinaryPresentation) errorLabel.wrap = true
        errorLabel.color = ExpeditionBlueTheme.color(Color.RED, ExpeditionBlueTheme.danger)

        addCloseButton(if (UiNavigation.isMobile) com.unciv.Constants.cancel else com.unciv.Constants.close)
        val okButton = addOKButton(
            text = if (ordinaryPresentation && label == "Enter the amount of gold") "Add to offer" else com.unciv.Constants.OK,
            validate = {
                val errorFound = valueField.intValue?.let { (!ordinaryPresentation || it in bounds) && validate(it) } != true
                if (errorFound && (!ordinaryPresentation || errorLabel.parent == null)) {
                    if (ordinaryPresentation) add(errorLabel).colspan(2).width(popupContentWidth).row()
                    else add(errorLabel).colspan(2).center()
                }
                !errorFound
            }
        ) {
            actionOnOk(valueField.intValue!!)
        }.actor
        okButton.name = "ui-number-confirm"
        equalizeLastTwoButtonWidths()

        fun updateValidity() {
            val valid = valueField.intValue?.let { it in bounds && (!ordinaryPresentation || validate(it)) } == true
            if (ordinaryPresentation) okButton.isDisabled = !valid
            else okButton.isEnabled = valid
            if (ordinaryPresentation) {
                if (!valid && errorLabel.parent == null) add(errorLabel).colspan(2).width(popupContentWidth).row()
                errorLabel.isVisible = !valid
            }
        }
        valueField.onChange { updateValidity() }
        if (ordinaryPresentation) updateValidity()

        keyboardFocus = valueField
    }
}
