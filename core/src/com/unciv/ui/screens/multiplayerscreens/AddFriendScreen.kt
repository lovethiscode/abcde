package com.unciv.ui.screens.multiplayerscreens

import com.unciv.ui.components.fonts.TextRole
import com.unciv.ui.components.widgets.UiNavigation
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.ui.Value
import com.unciv.models.skins.ExpeditionBlueTheme
import com.unciv.UncivGame
import com.unciv.logic.IdChecker
import com.unciv.logic.multiplayer.FriendList
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.enable
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.input.onChange
import com.unciv.ui.components.input.onClick
import com.unciv.ui.components.widgets.UncivTextField
import com.unciv.ui.screens.pickerscreens.PickerScreen
import com.unciv.utils.isUUID

class AddFriendScreen(
    initialName: String = "",
    initialID: String = ""
) : PickerScreen() {
    init {
        keyboardAwareForm = true
        pickerPane.useContentSizedFooter = true
        val unit = UiNavigation.minimumTouchSize() / 44f
        val formWidth = object : Value() {
            override fun get(context: Actor?) = minOf(stage.width - 32f * unit, 560f * unit).coerceAtLeast(1f)
        }
        val friendNameTextField = UncivTextField("Please input a name for your friend!", initialName)
        val pastePlayerIDButton = "Paste player ID from clipboard".toTextButton()
        val playerIDTextField = UncivTextField("Please input a player ID for your friend!", initialID)
        val friendlist = FriendList()

        topTable.top()
        topTable.defaults().minWidth(0f)
        topTable.add("Friend name".toLabel(TextRole.Control)).width(formWidth).left().padTop(16f * unit).row()
        topTable.add(friendNameTextField).width(formWidth).minHeight(48f * unit).padTop(8f * unit).padBottom(20f * unit).row()

        pastePlayerIDButton.onClick {
            playerIDTextField.text = Gdx.app.clipboard.contents
        }

        topTable.add("Player ID".toLabel(TextRole.Control)).width(formWidth).left().row()
        topTable.add(playerIDTextField).width(formWidth).minHeight(48f * unit).padTop(8f * unit).row()
        topTable.add(pastePlayerIDButton).width(formWidth).minHeight(44f * unit).padTop(8f * unit).padBottom(20f * unit).row()

        val errorLabel = "".toLabel(TextRole.Supporting, fontColor = ExpeditionBlueTheme.danger).apply { wrap = true }
        topTable.row()
        topTable.add(errorLabel).width(formWidth).left().padTop(8f * unit).row()
        fun showError(message: String, field: UncivTextField) {
            errorLabel.setText(message.tr())
            stage.keyboardFocus = field
            Gdx.input.setOnscreenKeyboardVisible(false)
        }
        friendNameTextField.onChange { errorLabel.setText("") }
        playerIDTextField.onChange { errorLabel.setText("") }

        //CloseButton Setup
        closeButton.setText(UiNavigation.backCaption)
        setDefaultCloseAction()

        //RightSideButton Setup
        rightSideButton.setText("Add friend".tr())
        rightSideButton.enable()
        rightSideButton.onClick {
            val friend = IdChecker.checkAndReturnPlayerUuid(playerIDTextField.text)
            if (friend?.playerID?.isUUID() != true) {
                showError("Player ID is incorrect", playerIDTextField)
                return@onClick
            }
            if (friendNameTextField.text.isEmpty() && friend.name.isNotEmpty())
                friendNameTextField.text = friend.name

            when (friendlist.add(friendNameTextField.text, playerIDTextField.text)) {
                FriendList.ErrorType.NAME -> showError("Friend name is already in your friends list!", friendNameTextField)

                FriendList.ErrorType.ID -> showError("Player ID is already in your friends list!", playerIDTextField)

                FriendList.ErrorType.NONAME -> showError("You have to write a name for your friend!", friendNameTextField)

                FriendList.ErrorType.NOID -> showError("You have to write an ID for your friend!", playerIDTextField)

                FriendList.ErrorType.YOURSELF -> showError("You cannot add your own player ID in your friend list!", playerIDTextField)

                else -> {
                    val newScreen = UncivGame.Current.popScreen()
                    if (newScreen is ViewFriendsListScreen) {
                        newScreen.refreshFriendsList()
                    }
                }
            }
        }
    }
}
