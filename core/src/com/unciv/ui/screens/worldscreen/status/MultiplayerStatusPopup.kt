package com.unciv.ui.screens.worldscreen.status

import com.unciv.ui.components.widgets.UiNavigation
import com.unciv.UncivGame
import com.unciv.logic.multiplayer.MultiplayerGamePreview
import com.unciv.models.translations.tr
import com.unciv.ui.screens.multiplayerscreens.GameList
import com.unciv.ui.screens.multiplayerscreens.MultiplayerHelpers
import com.unciv.ui.screens.pickerscreens.PickerPane
import com.unciv.ui.popups.Popup
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.components.input.onClick

class MultiplayerStatusPopup(
    screen: BaseScreen,
) : Popup(screen) {

    val pickerPane = PickerPane()
    var selectedGame: MultiplayerGamePreview? = null
    private val gameList = GameList(::gameSelected)

    init {
        val empty = gameList.models().isEmpty()
        val preferredHeight = if (empty) minOf((if (UiNavigation.isMobile) 112f else 180f) * popupUnit, screen.stage.height * 0.45f)
            else screen.stage.height * 0.5f
        val pickerCell = add()
            .width(if (ordinaryPresentation) popupContentWidth else minOf(700f, screen.stage.width * .9f)).fillX().expandX()
            .height(preferredHeight)
            .maxHeight(screen.stage.height * 0.8f)

        pickerPane.topTable.add(gameList).growX().minWidth(0f).row()
        pickerPane.useContentSizedFooter = empty
        pickerPane.rightSideButton.setText("Load game".tr())
        pickerPane.setRightSideButtonEnabled(false)
        if (UiNavigation.isMobile) {
            pickerPane.closeButton.remove()
            addTitleWithCloseButton()
            clickBehindToClose = false
        } else {
            pickerPane.closeButton.onClick(::close)
        }
        pickerCell.setActor<PickerPane>(pickerPane)
        pickerPane.rightSideButton.onClick {
            close()
            val game = selectedGame
            if (game != null) {
                MultiplayerHelpers.loadMultiplayerGame(screen, game)
            }
        }
        closeListeners += gameList::dispose
    }

    private fun gameSelected(gameName: String) {
        val multiplayerGame = UncivGame.Current.onlineMultiplayer.multiplayerFiles.getGameByName(gameName)!!
        selectedGame = multiplayerGame
        gameList.select(gameName)
        pickerPane.setRightSideButtonEnabled(true)
        pickerPane.rightSideButton.setText("Load [$gameName]".tr())
        pickerPane.descriptionLabel.setText(MultiplayerHelpers.buildDescriptionText(multiplayerGame))
    }

}
