package com.unciv.ui.screens.multiplayerscreens

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.unciv.Constants
import com.unciv.logic.IdChecker
import com.unciv.logic.multiplayer.MultiplayerGameInvite
import com.unciv.logic.multiplayer.rethrowCancellationAfterCleanup
import com.unciv.ui.components.fonts.TextRole
import com.unciv.ui.components.fonts.UiTypography
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.extensions.setFontSize
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.input.KeyCharAndCode
import com.unciv.ui.components.input.onClick
import com.unciv.ui.components.widgets.UncivTextField
import com.unciv.ui.popups.Popup
import com.unciv.ui.popups.ToastPopup
import com.unciv.ui.screens.savescreens.LoadGameScreen
import com.unciv.utils.Concurrency
import com.unciv.utils.isUUID
import com.unciv.utils.launchOnGLThread
import kotlinx.coroutines.CancellationException

class AddMultiplayerGamePopup(
    private val multiplayerScreen: MultiplayerScreen,
) : Popup(multiplayerScreen, Scrollability.WithoutButtons, 0.9f) {
    private val gameIdTextField = UncivTextField("Paste the Game ID or invitation link")
    private val gameNameTextField = UncivTextField("For example: Weekend game")

    init {
        val fieldWidth = if (ordinaryPresentation) popupContentWidth else stageToShowOn.width * 0.55f
        val controlHeight = if (ordinaryPresentation) 46f * popupUnit else 46f
        defaults().growX().pad(4f)
        if (ordinaryPresentation) addDialogTitle("Join by invitation or Game ID")
        else addGoodSizedLabel("Join by invitation or Game ID", size = multiplayerTitleFontSize).row()
        addGoodSizedLabel(
            "Paste an invitation to join a waiting room, or a Game ID to add an existing game.",
            size = multiplayerSecondaryFontSize,
            color = Color.LIGHT_GRAY,
        ).row()

        add("Game ID or invitation link".toLabel(fontSize = multiplayerSecondaryFontSize)).left().padTop(8f).row()
        val pasteButton = "Paste ID or link".toTextButton()
        if (ordinaryPresentation) {
            pasteButton.label.setFontSize(UiTypography.size(TextRole.Control, popupUnit))
            styleOrdinaryButton(pasteButton)
        }
        pasteButton.onClick { gameIdTextField.text = Gdx.app.clipboard.contents }
        val gameIdRow = Table().apply {
            add(gameIdTextField).growX().height(controlHeight).padRight(6f * if (ordinaryPresentation) popupUnit else 1f)
            add(pasteButton).minWidth(controlHeight).height(controlHeight)
        }
        add(gameIdRow).width(fieldWidth).row()

        add("Local display name (optional)".toLabel(fontSize = multiplayerSecondaryFontSize)).left().padTop(6f).row()
        add(gameNameTextField).width(fieldWidth).height(controlHeight).row()

        addCloseButton("Cancel")
        val positiveButtonStyle = skin.get("positive", TextButton.TextButtonStyle::class.java)
        addButton("Join or add game", KeyCharAndCode.RETURN, positiveButtonStyle) { addGame() }
        showListeners += { stageToShowOn.keyboardFocus = gameIdTextField }
    }

    private fun addGame() {
        val invite = try {
            if (MultiplayerGameInvite.isLink(gameIdTextField.text))
                MultiplayerGameInvite.parse(gameIdTextField.text) else null
        } catch (ex: Exception) {
            ToastPopup(LoadGameScreen.getLoadExceptionMessage(ex).first, multiplayerScreen)
            return
        }
        if (invite?.lobby == true) {
            close()
            MultiplayerLobbyScreen.openInvite(invite)
            return
        }
        val gameId = invite?.gameId ?: IdChecker.checkAndReturnUuiId(gameIdTextField.text)
        if (gameId?.isUUID() != true) {
            ToastPopup("Invalid game ID!", multiplayerScreen)
            return
        }

        val workingPopup = Popup(multiplayerScreen)
        workingPopup.addGoodSizedLabel(Constants.working)
        workingPopup.open(force = true)

        Concurrency.run("AddMultiplayerGame") {
            try {
                multiplayerScreen.game.onlineMultiplayer.addGame(
                    gameId,
                    gameNameTextField.text.trim(),
                    invite?.serverUrl,
                )
                launchOnGLThread {
                    workingPopup.close()
                    close()
                    multiplayerScreen.onMultiplayerGameAdded()
                }
            } catch (ex: CancellationException) {
                rethrowCancellationAfterCleanup(ex) {
                    Concurrency.runOnGLThread { workingPopup.close() }
                }
            } catch (ex: Exception) {
                val (message) = LoadGameScreen.getLoadExceptionMessage(ex)
                launchOnGLThread { workingPopup.reuseWith(message, true) }
            }
        }
    }
}
