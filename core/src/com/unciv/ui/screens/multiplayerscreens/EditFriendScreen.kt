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
import com.unciv.ui.components.input.onClick
import com.unciv.ui.components.widgets.UncivTextField
import com.unciv.ui.popups.ConfirmPopup
import com.unciv.ui.popups.ToastPopup
import com.unciv.ui.screens.pickerscreens.PickerScreen
import com.unciv.utils.isUUID

class EditFriendScreen(selectedFriend: FriendList.Friend) : PickerScreen() {
    init {
        val unit = UiNavigation.minimumTouchSize() / 44f
        val formWidth = object : Value() {
            override fun get(context: Actor?) = minOf(stage.width - 32f * unit, 560f * unit).coerceAtLeast(1f)
        }
        val friendNameTextField = UncivTextField("Please input a name for your friend!", selectedFriend.name)
        val pastePlayerIDButton = "Player ID from clipboard".toTextButton()
        val playerIDTextField = UncivTextField("Please input a player ID for your friend!", selectedFriend.playerID)
        val deleteFriendButton = "Delete".toTextButton()
        val friendlist = FriendList()

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

        //CloseButton Setup
        closeButton.setText(UiNavigation.backCaption)
        closeButton.onClick {
            goBack()
        }

        //RightSideButton Setup
        rightSideButton.setText("Save".tr())
        rightSideButton.enable()
        rightSideButton.onClick {
            // if no edits have been made, go back to friends list
            if (selectedFriend.name == friendNameTextField.text && selectedFriend.playerID == playerIDTextField.text) {
                goBack()
            }
            if (friendlist.isFriendNameInFriendList(friendNameTextField.text) == FriendList.ErrorType.ALREADYINLIST
                    && friendlist.isFriendIDInFriendList(playerIDTextField.text) == FriendList.ErrorType.ALREADYINLIST) {
                ToastPopup("Player name already used!", this)
                return@onClick
            }
            if (friendlist.isFriendIDInFriendList(playerIDTextField.text) == FriendList.ErrorType.ALREADYINLIST
                    && friendlist.isFriendNameInFriendList(friendNameTextField.text) == FriendList.ErrorType.ALREADYINLIST) {
                ToastPopup("Player ID already used!", this)
                return@onClick
            }
            val friend = IdChecker.checkAndReturnPlayerUuid(playerIDTextField.text)
            if (friend?.playerID?.isUUID() != true) {
                ToastPopup("Player ID is incorrect", this)
                return@onClick
            }
            if (friendNameTextField.text.isEmpty() && friend.name.isNotEmpty())
                friendNameTextField.text = friend.name
            
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
