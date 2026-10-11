package com.unciv.ui.screens.worldscreen.mainmenu

import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.ui.Cell
import com.unciv.ui.components.input.KeyboardBinding
import com.unciv.ui.components.input.onLongPress
import com.unciv.ui.popups.Popup
import com.unciv.ui.screens.savescreens.LoadGameScreen
import com.unciv.ui.screens.victoryscreen.VictoryScreen
import com.unciv.ui.screens.worldscreen.WorldScreen

/** The in-game menu called from the "Hamburger" button top-left
 *
 *  Popup automatically opens as soon as it's initialized
 */
class WorldScreenMenuPopup(
    val worldScreen: WorldScreen,
    expertMode: Boolean = false
) : Popup(worldScreen, scrollable = Scrollability.All) {
    private val singleColumn: Boolean
    private fun <T: Actor?> Cell<T>.nextColumn() {
        if (!singleColumn && column == 0) return
        row()
    }

    init {
        worldScreen.autoPlay.stopAutoPlay()
        defaults().fillX()

        val showSave = !worldScreen.gameInfo.gameParameters.isOnlineMultiplayer
        val showMusic = worldScreen.game.musicController.isMusicAvailable()
        val endless = worldScreen.gameInfo.endless != null
        val showConsole = showSave && expertMode && !endless
        val buttonCount = 7 + (if (showSave) 1 else 0) + (if (showMusic) 1 else 0) + (if (showConsole) 1 else 0) +
            (if (worldScreen.game.achievementsAvailable) 2 else 0)

        val emptyPrefHeight = this.prefHeight
        val firstCell = addButton("Main menu") {
            if (endless) { close(); com.unciv.ui.screens.endless.EndlessPopup(worldScreen).leave(toHall = false) }
            else worldScreen.game.goToMainMenu()
        }
        singleColumn = worldScreen.isCrampedPortrait() ||
            2 * prefWidth > maxPopupWidth ||  // Very coarse: Assume width of translated "Main menu" is representative
            buttonCount * (prefHeight - emptyPrefHeight) + emptyPrefHeight < maxPopupHeight
        firstCell.nextColumn()

        addButton("Civilopedia", KeyboardBinding.Civilopedia) {
            close()
            worldScreen.openCivilopedia()
        }.nextColumn()
        if (showSave)
            addButton("Save game", KeyboardBinding.SaveGame) {
                close()
                worldScreen.openSaveGameScreen()
            }.nextColumn()
        if (!endless) addButton("Load game", KeyboardBinding.LoadGame) {
            close()
            worldScreen.game.pushScreen{ LoadGameScreen() }
        }.nextColumn()
        if (!endless) addButton("Singleplayer", KeyboardBinding.NewGame) {
            close()
            worldScreen.openNewGameScreen()
        }.nextColumn()
        addButton(if (endless) "Defense status" else "Victory status", KeyboardBinding.VictoryScreen) {
            close()
            if (endless) com.unciv.ui.screens.endless.EndlessPopup(worldScreen)
            else worldScreen.game.pushScreen{ VictoryScreen(worldScreen) }
        }.nextColumn()
        if (worldScreen.game.achievementsAvailable) {
            addButton("Achievements") {
                close()
                worldScreen.game.showAchievements()
            }.nextColumn()
            addButton("Achievement guide") {
                close()
                worldScreen.game.showAchievementGuide()
            }.nextColumn()
        }
        val optionsCell = addButton("Options", KeyboardBinding.Options) {
            close()
            worldScreen.openOptionsPopup()
        }
        optionsCell.actor.onLongPress {
            close()
            worldScreen.openOptionsPopup(withDebug = true)
        }
        optionsCell.nextColumn()
        if (showMusic)
            addButton("Music", KeyboardBinding.MusicPlayer) {
                close()
                WorldScreenMusicPopup(worldScreen).open(force = true)
            }.nextColumn()

        if (showConsole)
            addButton("Developer Console", KeyboardBinding.DeveloperConsole) {
                close()
                worldScreen.openDeveloperConsole()
            }.nextColumn()

        addCloseButton().run { colspan(if (singleColumn || column == 1) 1 else 2) }
        pack()

        open(force = true)
    }
}
