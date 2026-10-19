package com.unciv.ui.popups

import com.unciv.ui.components.fonts.UiTypography
import com.unciv.ui.components.fonts.Fonts
import com.unciv.ui.components.fonts.TextRole
import com.unciv.ui.components.widgets.UiNavigation
import com.unciv.models.skins.ExpeditionBlueTheme
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.Group
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextField
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.components.widgets.UncivTextField
import com.unciv.ui.components.extensions.surroundWithCircle
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.input.onChange

/** Simple class for showing a prompt for a string to the user
 * @param screen The previous screen the user was on
 * @param label A line of text shown to the user
 * @param icon Icon at the top, should have size 80f
 * @param defaultText The text that should be in the prompt at the start
 * @param errorText Text that will be shown when an error is detected
 * @param maxLength The maximal amount of characters the user may input
 * @param validate Function that should return `true` when a valid input is entered, false otherwise
 * @param actionOnOk Lambda that will be executed after pressing 'OK'.
 * Gets the text the user inputted as a parameter.
 */
class AskTextPopup(
    screen: BaseScreen,
    label: String = "Please enter some text",
    icon: Group = ImageGetter.getImage("OtherIcons/Pencil").apply { this.color = ImageGetter.CHARCOAL }.surroundWithCircle(80f),
    defaultText: String = "",
    errorText: String = "Invalid input! Please enter a different string.",
    maxLength: Int = 32,
    dialogTitle: String = label,
    fieldLabel: String = label,
    subtitle: String = "",
    confirmText: String = com.unciv.Constants.OK,
    validate: (input: String) -> Boolean = { true },
    actionOnOk: (input: String) -> Unit = {},
) : Popup(screen) {

    val illegalChars = "[]{}\"\\<>"

    init {
        val wrapper = Table()
        wrapper.add(icon).padRight(10f)
        wrapper.add(label.toLabel(TextRole.Body))
        if (ordinaryPresentation) {
            addDialogTitle(dialogTitle, subtitle)
            addGoodSizedLabel(fieldLabel, UiTypography.size(TextRole.Control, popupUnit)).row()
        }
        else add(wrapper).colspan(2).row()

        val nameField = UncivTextField(label, defaultText)
        if (ordinaryPresentation) {
            nameField.style = com.badlogic.gdx.scenes.scene2d.ui.TextField.TextFieldStyle(nameField.style).apply {
                font = Fonts.getUiFont(UiTypography.size(TextRole.Control, popupUnit))
                if (ExpeditionBlueTheme.enabled) background = OrdinaryPopupStyle(popupUnit).let {
                    it.outlined(it.deep, radius = 8f)
                }
            }
        }
        nameField.textFieldFilter = TextField.TextFieldFilter { _, char -> char !in illegalChars }
        nameField.maxLength = maxLength

        add(nameField).growX().colspan(2).apply { if (ordinaryPresentation) minHeight(48f * popupUnit).width(popupContentWidth) }.row()

        val errorLabel = (if (ordinaryPresentation && dialogTitle == "Rename city") "Enter a city name." else errorText)
            .toLabel(if (ordinaryPresentation) TextRole.Supporting else TextRole.Body)
        if (ordinaryPresentation) errorLabel.wrap = true
        errorLabel.color = ExpeditionBlueTheme.color(Color.RED, ExpeditionBlueTheme.danger)

        addCloseButton(if (UiNavigation.isIos) com.unciv.Constants.cancel else com.unciv.Constants.close)
        val confirmButton = addOKButton(
            text = if (ordinaryPresentation) confirmText else com.unciv.Constants.OK,
            validate = {
                val errorFound = !validate(nameField.text) || (ordinaryPresentation && dialogTitle == "Rename city" && nameField.text.isBlank())
                if (errorFound && (!ordinaryPresentation || errorLabel.parent == null)) {
                    row()
                    add(errorLabel).colspan(2).apply { if (ordinaryPresentation) width(popupContentWidth) }.center()
                }
                !errorFound
            }
        ) {
            actionOnOk(nameField.text)
        }.actor
        if (ordinaryPresentation) {
            fun updateValidity() {
                val valid = validate(nameField.text) && (!ordinaryPresentation || dialogTitle != "Rename city" || nameField.text.isNotBlank())
                confirmButton.isDisabled = !valid
                if (!valid && errorLabel.parent == null) add(errorLabel).colspan(2).width(popupContentWidth).row()
                errorLabel.isVisible = !valid
            }
            nameField.onChange { updateValidity() }
            updateValidity()
        }
        equalizeLastTwoButtonWidths()
        keyboardFocus = nameField
    }
}
