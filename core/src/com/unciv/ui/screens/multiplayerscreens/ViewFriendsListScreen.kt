package com.unciv.ui.screens.multiplayerscreens

import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.unciv.ui.components.fonts.TextRole
import com.unciv.ui.components.widgets.UiNavigation
import com.unciv.ui.components.extensions.toLabel
import com.unciv.logic.multiplayer.FriendList
import com.unciv.ui.screens.pickerscreens.PickerScreen
import com.unciv.ui.popups.Popup
import com.unciv.ui.components.extensions.disable
import com.unciv.ui.components.extensions.enable
import com.unciv.ui.components.input.onClick
import com.unciv.ui.components.extensions.toTextButton

class ViewFriendsListScreen : PickerScreen() {
    private var friendsTable = Table()

    private val addFriendButton = "Add friend".toTextButton()
    private val editFriendButton = "Edit friend".toTextButton()

    private val friendsList = FriendList().getFriendsList()
    private var listOfFriendsButtons = arrayListOf<TextButton>()

    private lateinit var selectedFriend: FriendList.Friend

    init {
        setDefaultCloseAction()
        rightSideButton.remove()
        val unit = UiNavigation.minimumTouchSize() / 44f

        //Help Button Setup
        val helpButton = "Help".toTextButton()
        helpButton.onClick {
            val helpPopup = Popup(this)
            helpPopup.addGoodSizedLabel("To add a friend, ask him to send you his player ID.\nClick the 'Add friend' button.\nInsert his player ID and a name for him.\nThen click the 'Add friend' button again.\n\nAfter that you will see him in your friends list.\n\nA new button will appear when creating a new\nmultiplayer game, which allows you to select your friend.").row()

            helpPopup.addCloseButton()
            helpPopup.open()
        }
        if (UiNavigation.isIos) pickerPane.setNavigationContent(Table().apply {
            add("Friends".toLabel(TextRole.PageTitle)).growX().minWidth(0f).left()
            add(helpButton).minHeight(44f * unit).padLeft(8f * unit)
        }) else stage.addActor(Table().apply {
            add(helpButton)
            setPosition(stage.width - helpButton.width, stage.height - helpButton.height)
        })

        topTable.add(friendsTable).growX().minWidth(0f).pad(16f * unit).row()
        scrollPane.setScrollingDisabled(true, false)
        rightSideGroup.space(8f * unit)
        rightSideGroup.fill()

        addFriendButton.onClick {
            game.pushScreen{ AddFriendScreen() }
        }
        addFriendButton.setSize(maxOf(addFriendButton.prefWidth, 160f * unit), maxOf(addFriendButton.prefHeight, 44f * unit))
        rightSideGroup.addActor(addFriendButton)

        editFriendButton.onClick {
            game.pushScreen{ EditFriendScreen(selectedFriend) }
            editFriendButton.disable()
        }
        editFriendButton.setSize(maxOf(editFriendButton.prefWidth, 160f * unit), maxOf(editFriendButton.prefHeight, 44f * unit))
        rightSideGroup.addActor(editFriendButton)
        editFriendButton.disable()

        refreshFriendsList()
    }

    fun refreshFriendsList() {
        listOfFriendsButtons.clear()
        friendsTable.clear()

        if (friendsList.isEmpty()) {
            friendsTable.add("No friends added yet".toLabel(TextRole.Body).apply { wrap = true }).growX().minWidth(0f).pad(16f)
            return
        }

        for (index in friendsList.indices) {
            listOfFriendsButtons.add(friendsList[index].name.toTextButton())

            listOfFriendsButtons[index].onClick {
                selectedFriend = friendsList[index]
                editFriendButton.enable()
            }
            friendsTable.add(listOfFriendsButtons[index]).growX().minHeight(UiNavigation.minimumTouchSize()).padBottom(10f).row()
        }
    }
}
