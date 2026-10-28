package com.unciv.ui.screens.multiplayerscreens

import com.unciv.ui.components.fonts.TextRole
import com.unciv.ui.components.widgets.UiNavigation
import com.unciv.ui.screens.basescreen.BaseScreen
import com.badlogic.gdx.scenes.scene2d.ui.TextButton.TextButtonStyle
import com.unciv.models.skins.ExpeditionBlueTheme
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.ui.Value
import com.unciv.UncivGame
import com.unciv.logic.IdChecker
import com.unciv.logic.multiplayer.FriendList
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.enable
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.input.KeyCharAndCode
import com.unciv.ui.components.input.keyShortcuts
import com.unciv.ui.components.input.onActivation
import com.unciv.ui.components.input.onChange
import com.unciv.ui.components.input.onClick
import com.unciv.ui.components.widgets.UncivTextField
import com.unciv.ui.popups.ConfirmPopup
import com.unciv.ui.screens.pickerscreens.PickerScreen
import com.unciv.utils.isUUID

class EditFriendScreen(selectedFriend: FriendList.Friend) : PickerScreen() {
    init {
        keyboardAwareForm = true
        pickerPane.useContentSizedFooter = true
        val unit = UiNavigation.minimumTouchSize() / 44f
        val formWidth = object : Value() {
            override fun get(context: Actor?) = minOf(stage.width - 32f * unit, 560f * unit).coerceAtLeast(1f)
        }
        val friendNameTextField = UncivTextField("Please input a name for your friend!", selectedFriend.name)
        val pastePlayerIDButton = "Player ID from clipboard".toTextButton()
        val playerIDTextField = UncivTextField("Please input a player ID for your friend!", selectedFriend.playerID)
        val deleteFriendButton = "Delete".toTextButton()
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

        deleteFriendButton.onClick {
            val askPopup = ConfirmPopup(
                this,
                "Are you sure you want to delete this friend?",
                "Delete"
            ) {
                friendlist.delete(selectedFriend)
                goBack()
            }
            askPopup.open()
        }.apply { if (ExpeditionBlueTheme.enabled) deleteFriendButton.style = BaseScreen.skin.get("negative", TextButtonStyle::class.java)
            else color = Color.RED }
        topTable.add(deleteFriendButton).width(formWidth).minHeight(44f * unit)

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
        closeButton.onActivation { goBack() }
        closeButton.keyShortcuts.add(KeyCharAndCode.BACK)

        //RightSideButton Setup
        rightSideButton.setText("Save".tr())
        rightSideButton.enable()
        rightSideButton.onClick {
            // if no edits have been made, go back to friends list
            if (selectedFriend.name == friendNameTextField.text && selectedFriend.playerID == playerIDTextField.text) {
                goBack()
                return@onClick
            }
            if (friendNameTextField.text != selectedFriend.name &&
                friendlist.isFriendNameInFriendList(friendNameTextField.text) == FriendList.ErrorType.ALREADYINLIST) {
                showError("Friend name is already in your friends list!", friendNameTextField)
                return@onClick
            }
            if (playerIDTextField.text != selectedFriend.playerID &&
                friendlist.isFriendIDInFriendList(playerIDTextField.text) == FriendList.ErrorType.ALREADYINLIST) {
                showError("Player ID is already in your friends list!", playerIDTextField)
                return@onClick
            }
            val friend = IdChecker.checkAndReturnPlayerUuid(playerIDTextField.text)
            if (friend?.playerID?.isUUID() != true) {
                showError("Player ID is incorrect", playerIDTextField)
                return@onClick
            }
            if (friendNameTextField.text.isEmpty() && friend.name.isNotEmpty())
                friendNameTextField.text = friend.name
            
            if (friendNameTextField.text.isBlank()) {
                showError("You have to write a name for your friend!", friendNameTextField)
                return@onClick
            }
            if (playerIDTextField.text == game.settings.multiplayer.getUserId()) {
                showError("You cannot add your own player ID in your friend list!", playerIDTextField)
                return@onClick
            }
            friendlist.edit(selectedFriend, friendNameTextField.text, playerIDTextField.text)
            goBack()
        }
    }
}

fun goBack() {
    val newScreen = UncivGame.Current.popScreen()
    if (newScreen is ViewFriendsListScreen) {
        newScreen.refreshFriendsList()
    }
}
