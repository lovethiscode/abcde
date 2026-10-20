package com.unciv.ui.screens.worldscreen.mainmenu

import com.unciv.ui.components.fonts.TextRole
import com.unciv.ui.components.fonts.UiTypography
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.math.Rectangle
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.badlogic.gdx.utils.Align
import com.unciv.ui.components.extensions.setFontSize
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.input.KeyCharAndCode
import com.unciv.ui.components.input.KeyboardBinding
import com.unciv.ui.components.input.keyShortcuts
import com.unciv.ui.components.input.onActivation
import com.unciv.ui.components.input.onLongPress
import com.unciv.ui.components.widgets.AutoScrollPane
import com.unciv.ui.components.widgets.UiNavigation
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.popups.Popup
import com.unciv.ui.popups.OrdinaryPopupStyle
import com.unciv.ui.screens.savescreens.LoadGameScreen
import com.unciv.ui.screens.victoryscreen.VictoryScreen
import com.unciv.ui.screens.worldscreen.WorldScreen

/** The in-game menu, grouped by task and rebuilt when the safe viewport changes. */
class WorldScreenMenuPopup(
    val worldScreen: WorldScreen,
    private val expertMode: Boolean = false
) : Popup(worldScreen, scrollable = Scrollability.None) {
    init {
        worldScreen.autoPlay.stopAutoPlay()
        rebuild()
        open(force = true)
    }

    override fun onVisibleAreaChanged(visibleArea: Rectangle) {
        rebuild()
        super.onVisibleAreaChanged(visibleArea)
    }

    private fun rebuild() {
        innerTable.clearChildren()
        innerTable.pad(0f)
        innerTable.defaults().pad(0f)
        clickBehindToClose = true
        val ordinary = UiNavigation.isIos && worldScreen.gameInfo.campaign == null && worldScreen.gameInfo.endless == null
        val unit = when {
            ordinary -> UiTypography.unitsPerPoint(worldScreen.stage)
            UiNavigation.isIos -> worldScreen.stage.width / Gdx.graphics.width
            else -> 1f
        }
        val ui = WorldScreenMenuStyle(unit)
        val safe = worldScreen.safeAreaBoundsInWorld()
        ordinaryViewport = ordinary
        val width = minOf(safe.width - ui.px(32f), ui.px(if (ordinary) 820f else if (safe.height > safe.width) 650f else 800f))
        val portrait = if (ordinary) width / unit <= 620f else safe.height > safe.width
        val compactLandscape = ordinary && !portrait && safe.height / unit <= 520f
        val compactMenu = ordinary && (portrait || compactLandscape)
        val inset = ui.px(if (safe.height / unit < 480f) 20f else 24f)
        val contentWidth = width - 2 * inset
        innerTable.background = ui.surface(ui.background, 16f)
        innerTable.name = "world-menu-panel"

        val resume = "Continue playing".toTextButton(ui.buttonStyle(primary = true)).apply {
            name = "world-menu-resume"
            label.setFontSize(ui.font(TextRole.Control))
            label.setAlignment(Align.center)
            labelCell.minWidth(0f).growX()
            pad(ui.px(10f), ui.px(16f), ui.px(10f), ui.px(16f))
            onActivation { close() }
            keyShortcuts.add(KeyCharAndCode.BACK)
        }
        val title = Table().apply {
            add("Game menu".toLabel(ui.text, ui.font(TextRole.PageTitle)).apply { wrap = true })
                .minWidth(0f).growX().left().row()
            add("[${worldScreen.viewingCiv.civName}] · Turn [${worldScreen.gameInfo.turns}]"
                .toLabel(ui.muted, ui.font(TextRole.Supporting)).apply { wrap = true })
                .minWidth(0f).growX().padTop(ui.px(4f))
        }
        val stackedHeader = portrait && resume.prefWidth > contentWidth * .46f
        val resumeWidth = if (stackedHeader) contentWidth
            else resume.prefWidth.coerceIn(ui.px(108f), contentWidth * .46f)
        resume.label.wrap = true
        val header = Table().apply {
            val verticalInset = if (compactMenu) ui.px(12f) else inset
            pad(verticalInset, inset, verticalInset, inset)
            if (stackedHeader) {
                add(title).width(contentWidth).left().row()
                add(resume).width(resumeWidth).minHeight(ui.px(44f)).padTop(ui.px(16f))
            } else {
                add(title).width(contentWidth - resumeWidth - ui.px(16f)).left()
                add(resume).width(resumeWidth).minHeight(ui.px(44f)).padLeft(ui.px(16f))
            }
        }
        if (ordinary) {
            header.clearChildren()
            header.add(title).growX().minWidth(0f).left()
            header.add(UiNavigation.dismissButton(unit).apply {
                onActivation { close() }
            }).size(ui.px(44f)).top().padLeft(ui.px(12f))
        }
        innerTable.add(header).width(width).row()
        innerTable.add(ImageGetter.getWhiteDot().apply { color = ui.border }).height(unit).growX().row()

        val showSave = !worldScreen.gameInfo.gameParameters.isOnlineMultiplayer
        val endless = worldScreen.gameInfo.endless != null
        val showConsole = showSave && expertMode && !endless
        val naturalWidths = HashMap<TextButton, Float>()
        fun action(text: String, binding: KeyboardBinding? = null, quiet: Boolean = false,
                   run: () -> Unit): TextButton = text.toTextButton(ui.buttonStyle(outline = quiet)).apply {
            name = "world-menu-$text"
            label.setFontSize(ui.font(TextRole.Control))
            label.setAlignment(Align.left)
            if (ordinary) {
                style = ui.navigationStyle()
                add("›".toLabel(ui.muted, ui.font(TextRole.SectionTitle))).padLeft(ui.px(12f))
            }
            labelCell.minWidth(0f).growX()
            pad(ui.px(10f), ui.px(12f), ui.px(10f), ui.px(12f))
            naturalWidths[this] = prefWidth
            label.wrap = true
            if (binding == null) onActivation { run() }
            else onActivation(binding = binding) { run() }
        }
        val management = ArrayList<TextButton>()
        if (showSave) management += action("Save game", KeyboardBinding.SaveGame) {
            close(); worldScreen.openSaveGameScreen()
        }
        if (!endless) {
            management += action("Load game", KeyboardBinding.LoadGame) {
                close(); worldScreen.game.pushScreen { LoadGameScreen() }
            }
            management += action("Singleplayer", KeyboardBinding.NewGame, quiet = true) {
                close(); worldScreen.openNewGameScreen()
            }
        }
        management += action("Main menu", quiet = true) {
            if (endless) {
                close()
                com.unciv.ui.screens.endless.EndlessPopup(worldScreen).leave(toHall = false)
            } else worldScreen.game.goToMainMenu()
        }
        val progress = arrayListOf(action(if (endless) "Defense status" else "Victory status", KeyboardBinding.VictoryScreen) {
            close()
            if (endless) com.unciv.ui.screens.endless.EndlessPopup(worldScreen)
            else worldScreen.game.pushScreen { VictoryScreen(worldScreen) }
        })
        if (worldScreen.game.achievementsAvailable) progress += action("Achievements") {
            close(); worldScreen.game.showAchievements()
        }
        val help = arrayListOf(action("Civilopedia", KeyboardBinding.Civilopedia) {
            close(); worldScreen.openCivilopedia()
        })
        if (worldScreen.game.achievementsAvailable) help += action("Achievement guide") {
            close(); worldScreen.game.showAchievementGuide()
        }
        help += action("Options", KeyboardBinding.Options) {
            close(); worldScreen.openOptionsPopup()
        }.apply {
            onLongPress { close(); worldScreen.openOptionsPopup(withDebug = true) }
        }
        if (worldScreen.game.musicController.isMusicAvailable()) help += action("Music", KeyboardBinding.MusicPlayer) {
            close(); WorldScreenMusicPopup(worldScreen).open(force = true)
        }
        if (showConsole) help += action("Developer Console", KeyboardBinding.DeveloperConsole) {
            close(); worldScreen.openDeveloperConsole()
        }

        val groups = listOf("Game management" to management, "Civilization progress" to progress, "Help and settings" to help)
        val gap = ui.px(24f)
        val groupWidth = if (portrait) contentWidth else (contentWidth - 2 * gap) / 3
        val body = Table().apply {
            val verticalInset = if (compactMenu) ui.px(12f) else inset
            pad(verticalInset, inset, verticalInset, inset)
        }
        for ((index, entry) in groups.withIndex()) {
            val (heading, buttons) = entry
            // The iPhone portrait menu uses two columns so every group fits in one screen.
            // Labels retain the normal font size and grow their row when they wrap.
            val columns = when {
                ordinary && portrait -> 2
                !ordinary && portrait && buttons.all { naturalWidths.getValue(it) <= (groupWidth - ui.px(8f)) / 2 } -> 2
                else -> 1
            }
            val buttonWidth = (groupWidth - ui.px(8f) * (columns - 1)) / columns
            val group = Table().apply {
                // These are quiet task-group captions in the prototype, not content section titles.
                add(heading.toLabel(ui.muted, ui.font(TextRole.Supporting)).apply { wrap = true })
                    .width(groupWidth).colspan(columns).left().padBottom(ui.px(if (compactMenu) 6f else 10f)).row()
                for ((i, button) in buttons.withIndex()) {
                    // Measure at the actual width so wrapped text grows the row instead of clipping.
                    button.label.setSize(buttonWidth - ui.px(24f), 0f)
                    button.label.invalidate()
                    add(button).width(buttonWidth).minHeight(maxOf(ui.px(44f), button.label.prefHeight + ui.px(20f)))
                        .padBottom(if (compactMenu) 0f else ui.px(8f))
                        .padRight(if (i % columns == 0 && columns == 2) ui.px(8f) else 0f)
                    if ((i + 1) % columns == 0) row()
                }
            }
            if (!portrait && index > 0 && !ordinary) {
                body.add(ImageGetter.getWhiteDot().apply { color = ui.border })
                    .width(unit).growY().padLeft(gap / 2).padRight(gap / 2 - unit)
            }
            body.add(group).width(groupWidth).padLeft(if (!portrait && ordinary && index > 0) gap else 0f).top()
                .padBottom(if (portrait && index < 2) ui.px(if (compactMenu) 10f else 16f) else 0f)
            if (portrait) body.row()
        }
        val footer = if (ordinary) Table().apply {
            background = OrdinaryPopupStyle(unit).let { it.divider(atTop = true, fill = it.deep) }
            pad(ui.px(if (compactMenu) 10f else 14f), inset, ui.px(if (compactMenu) 12f else 18f), inset)
            right()
            resume.label.setSize((resumeWidth - ui.px(32f)).coerceAtLeast(1f), 0f)
            resume.label.invalidate()
            add(resume).width(resumeWidth).minHeight(maxOf(ui.px(44f), resume.label.prefHeight + ui.px(20f)))
        } else null
        // The custom body must fit the same height cap that Popup applies to the whole panel.
        val panelHeight = if (ordinary) minOf(safe.height - ui.px(32f), maxPopupHeight)
            else safe.height - ui.px(32f)
        val availableHeight = (panelHeight - header.prefHeight - unit - (footer?.prefHeight ?: 0f)).coerceAtLeast(ui.px(44f))
        if (ordinary) {
            // The grouped iPhone layout is sized to fit without scroll chrome or hidden items.
            innerTable.add(body).width(width).row()
        } else {
            val scroll = AutoScrollPane(body).apply {
                name = "world-menu-content"
                setScrollingDisabled(true, false)
                setOverscroll(false, false)
            }
            innerTable.add(scroll).width(width).height(minOf(body.prefHeight, availableHeight)).row()
        }
        if (footer != null) innerTable.add(footer).width(width)
        innerTable.pack()
        invalidateHierarchy()
    }
}
