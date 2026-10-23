package com.unciv.ui.screens.multiplayerscreens

import com.unciv.ui.components.fonts.UiTypography
import com.unciv.ui.components.fonts.TextRole
import com.unciv.ui.components.extensions.setFontSize
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.badlogic.gdx.scenes.scene2d.ui.VerticalGroup
import com.unciv.logic.multiplayer.FriendList
import com.unciv.ui.screens.newgamescreen.PlayerPickerTable
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.components.input.onClick
import com.unciv.ui.components.widgets.UiNavigation
import com.unciv.ui.components.extensions.toLabel

class FriendPickerList(
    playerPicker: PlayerPickerTable,
    onSelected: (String) -> Unit
) : VerticalGroup() {

    private val friendDisplays = mutableMapOf<String, FriendDisplay>()
    private var friendList: MutableList<FriendList.Friend>

    init {
        padTop(10f)
        padBottom(10f)

        friendList = playerPicker.getAvailableFriends().toMutableList()
        if (friendList.isEmpty() && UiNavigation.isIos)
            addActor("No friends added yet".toLabel(TextRole.Body))

        for (friend in friendList) {
            addFriend(friend, onSelected)
        }
    }

    private fun addFriend(friend: FriendList.Friend, onSelected: (String) -> Unit) {
        val friendDisplay = FriendDisplay(friend, onSelected)
        friendDisplays[friend.name] = friendDisplay
        addActor(friendDisplay)
    }
}

private class FriendDisplay(
    friend: FriendList.Friend,
    private val onSelected: (String) -> Unit
) : Table() {
    var friendName: String = friend.name
        private set
    val friendButton = TextButton(friendName, BaseScreen.skin).apply { label.setFontSize(UiTypography.size(TextRole.Control)) }

    init {
        padBottom(5f)

        add(friendButton).apply { if (UiNavigation.isIos) minHeight(UiNavigation.minimumTouchSize()).fillY() }
        onClick { onSelected(friendName) }
    }
}
