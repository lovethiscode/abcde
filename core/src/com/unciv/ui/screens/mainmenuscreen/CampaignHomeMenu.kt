package com.unciv.ui.screens.mainmenuscreen

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.Image
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.Stack
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.unciv.UncivGame
import com.unciv.models.translations.tr
import com.unciv.models.skins.ExpeditionBlueTheme
import com.unciv.ui.components.UncivTooltip.Companion.addTooltip
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.widgets.UiNavigation
import com.unciv.ui.components.input.KeyboardBinding
import com.unciv.ui.components.input.onActivation
import com.unciv.ui.components.input.onLongPress
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.popups.Popup
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.mapeditorscreen.MapEditorScreen
import com.unciv.ui.screens.modmanager.ModManagementScreen
import com.unciv.ui.screens.multiplayerscreens.MultiplayerScreen
import com.unciv.ui.screens.newgamescreen.NewGameScreen
import com.unciv.ui.screens.savescreens.LoadGameScreen
import com.unciv.utils.Concurrency
import com.unciv.utils.ONLINE_MULTIPLAYER_UNAVAILABLE
import com.unciv.utils.launchOnGLThread
import kotlin.math.roundToInt

/** iOS home layout; game creation, loading and tools keep their existing implementations. */
internal class CampaignHomeMenu(
    private val screen: MainMenuScreen,
    private val resumeGame: () -> Unit,
    private val quickstartNewGame: () -> Unit,
    private val stopBackground: () -> Unit
) : Table() {
    private val ink = ExpeditionBlueTheme.color(Color.valueOf("1b3542"), ExpeditionBlueTheme.text)
    private val muted = ExpeditionBlueTheme.color(Color.valueOf("5c737c"), ExpeditionBlueTheme.muted)
    private val blue = ExpeditionBlueTheme.color(Color.valueOf("24536b"), ExpeditionBlueTheme.hero)
    private val gold = ExpeditionBlueTheme.color(Color.valueOf("ecd19a"), ExpeditionBlueTheme.gold)
    private val surface = ExpeditionBlueTheme.color(Color.valueOf("f8faf7"), ExpeditionBlueTheme.raised)
    private val iconColor = ExpeditionBlueTheme.color(blue, gold)
    private val game = screen.game

    init {
        name = "ui-campaign-home"
        background = ImageGetter.getWhiteDotDrawable().tint(ExpeditionBlueTheme.color(Color.valueOf("e5edf0"), ExpeditionBlueTheme.background))
        val touchSize = UiNavigation.minimumTouchSize()
        val pointScale = touchSize / 44f
        pad(28f).padBottom(12f * pointScale)
        val portrait = screen.isPortrait()
        val width = (screen.stage.width - 56f).coerceAtMost(1600f)
        val coverWidth = if (portrait) width else width * .55f
        val actionsWidth = if (portrait) width else width - coverWidth - 22f
        val toolNames = listOfNotNull("Civilopedia", "Mods", if (game.achievementsAvailable) "Achievements" else null,
            "Map editor")
        val toolCount = toolNames.size
        val columns = if (portrait && toolCount > 3) 2 else toolCount
        val toolbarHeight = ((toolCount + columns - 1) / columns) * touchSize + 12f * pointScale
        val sectionGap = 16f * pointScale

        val header = Table()
        header.add("Build a city. Shape your world.".toLabel(muted, 18).apply {
            name = "ui-home-tagline"
            setEllipsis(true)
        }).expandX().fillX().minWidth(0f).left().padRight(12f * pointScale)
        val version = game.displayBuildNumber?.let { "v${UncivGame.VERSION.text} ($it)" }
            ?: "v${UncivGame.VERSION.text}"
        header.add(version.toLabel(muted, (13f * pointScale).roundToInt()).apply {
            setText(version)
            name = "ui-home-version"
        }).right().top().padTop(2f)
        val options = Table().apply {
            name = "ui-home-options"
            background = BaseScreen.skinStrings.getUiBackground("", BaseScreen.skinStrings.roundedEdgeRectangleShape,
                surface.cpy().apply { a = .45f })
            add(ImageGetter.getImage("OtherIcons/Options", iconColor)).size(24f * pointScale)
            touchable = Touchable.enabled
            onActivation(binding = KeyboardBinding.MainMenuOptions) { stopBackground(); screen.openOptionsPopup() }
            onLongPress { screen.openOptionsPopup(withDebug = true) }
            addTooltip("Options")
        }
        header.add(options).size(touchSize).padLeft(16f * pointScale).right().top()
        header.pack()
        add(header).width(width).padBottom(sectionGap).row()
        val bodyHeight = if (portrait) (screen.stage.height * .48f).coerceAtMost(400f) else
            (screen.stage.height - padTop - padBottom - header.prefHeight - sectionGap * 2f - toolbarHeight)
                .coerceAtLeast(220f)
        val secondaryHeight = maxOf(bodyHeight * .20f, touchSize)
        val actionHeight = (bodyHeight - secondaryHeight - 42f) / 3f

        val actions = Table()
        val hasSave = game.files.autosaves.autosaveExists()
        val resume = menuButton("Resume", if (hasSave) "Your latest autosave" else "No game to continue",
            "OtherIcons/Resume", actionsWidth, KeyboardBinding.Resume) { if (hasSave) resumeGame() }
        resume.name = "ui-resume"
        if (!hasSave) {
            resume.touchable = Touchable.disabled
            resume.color.a = .6f
        } else loadResumeSubtitle(resume.findActor("subtitle"))
        actions.add(resume).width(actionsWidth).height(actionHeight).padBottom(16f).row()
        actions.add(menuButton("Free play", "Quickstart or customize a singleplayer game",
            "OtherIcons/Nations", actionsWidth, action = ::showFreePlay)).width(actionsWidth)
            .height(actionHeight).padBottom(16f).row()

        actions.add(menuButton("Endless survival", "Defense hall",
            "OtherIcons/Shield", actionsWidth) {
            game.pushScreen { com.unciv.ui.screens.endless.EndlessScreen() }
        }.apply { name = "ui-endless-open" }).width(actionsWidth).height(actionHeight).padBottom(10f).row()

        val secondary = Table()
        secondary.add(menuButton("Load game", "", "OtherIcons/Load", (actionsWidth - 16f) / 2,
            KeyboardBinding.MainMenuLoad) { game.pushScreen { LoadGameScreen() } })
            .width((actionsWidth - 16f) / 2).height(secondaryHeight).padRight(16f)
        secondary.add(menuButton("Multiplayer", "", "OtherIcons/Multiplayer", (actionsWidth - 16f) / 2,
            KeyboardBinding.Multiplayer, action = ::showMultiplayer)).width((actionsWidth - 16f) / 2).height(secondaryHeight)
        actions.add(secondary).width(actionsWidth)

        val body = Table()
        body.add(campaignCover(coverWidth, bodyHeight)).size(coverWidth, bodyHeight)
        if (portrait) body.row() else body.add().width(22f)
        body.add(actions).width(actionsWidth).padTop(if (portrait) 20f else 0f)
        add(body).width(width).padBottom(sectionGap).row()

        val tools = Table().apply { padTop(6f * pointScale).padBottom(6f * pointScale) }
        val iconSize = 24f * pointScale
        val iconGap = 8f * pointScale
        val toolLabels = toolNames.associateWith { it.toLabel(ink, (17f * pointScale).roundToInt()) }
        val spareWidth = (width - toolLabels.values.sumOf { it.prefWidth.toDouble() }.toFloat()) / toolCount
        var added = 0
        fun tool(text: String, icon: String, binding: KeyboardBinding = KeyboardBinding.None, action: () -> Unit): Table {
            val label = toolLabels.getValue(text)
            val toolWidth = if (portrait) width / columns - 6f else label.prefWidth + spareWidth - 6f
            val button = Table().apply {
                background = BaseScreen.skinStrings.getUiBackground("", BaseScreen.skinStrings.roundedEdgeRectangleShape,
                    surface.cpy().apply { a = .45f })
                pad(0f, 8f, 0f, 8f)
                add(ImageGetter.getImage(icon, iconColor)).size(iconSize).padRight(iconGap)
                val labelWidth = toolWidth - 16f - iconSize - iconGap
                add(label.apply {
                    if (prefWidth > labelWidth) setFontScale(fontScaleX * labelWidth / prefWidth)
                }).width(labelWidth)
                touchable = Touchable.enabled
                onActivation(binding = binding) { stopBackground(); action() }
            }
            button.name = "ui-home-${text.lowercase().replace(' ', '-')}"
            tools.add(button).width(toolWidth).height(touchSize).padLeft(3f).padRight(3f)
            if (++added % columns == 0) tools.row()
            return button
        }
        tool("Civilopedia", "OtherIcons/Book", KeyboardBinding.Civilopedia) { screen.openCivilopedia() }
        tool("Mods", "OtherIcons/Mods", KeyboardBinding.ModManager) {
            if (game.platformCapabilities.onlineModManagement) game.pushScreen { ModManagementScreen() }
        }.apply { if (!game.platformCapabilities.onlineModManagement) { touchable = Touchable.disabled; color.a = .6f } }
        if (game.achievementsAvailable) tool("Achievements", "OtherIcons/AchievementTrophy") { game.showAchievements() }
        tool("Map editor", "OtherIcons/MapEditor", KeyboardBinding.MapEditor) { game.pushScreen { MapEditorScreen() } }
        add(tools).width(width).row()
        pack()
    }

    private fun menuButton(text: String, subtitle: String, icon: String, width: Float,
                           binding: KeyboardBinding = KeyboardBinding.None, primary: Boolean = false, action: () -> Unit): Table {
        val foreground = if (primary) ExpeditionBlueTheme.color(Color.valueOf("1b3542"), ExpeditionBlueTheme.primaryInk) else ink
        val button = Table().apply {
            background = BaseScreen.skinStrings.getUiBackground("", BaseScreen.skinStrings.roundedEdgeRectangleShape, if (primary) gold else surface)
            pad(16f)
            add(ImageGetter.getImage(icon, if (primary) foreground else iconColor)).size(30f).padRight(14f)
            val copy = Table()
            val title = text.toLabel(foreground, if (subtitle.isEmpty()) 22 else 30).apply { wrap = true }
            copy.add(title).width((width - 76f).coerceAtLeast(60f)).left().row()
            if (subtitle.isNotEmpty()) copy.add(subtitle.toLabel(muted, 20).apply {
                name = "subtitle"
                wrap = true
            }).width((width - 76f).coerceAtLeast(60f)).left().padTop(5f)
            add(copy).expandX().left()
            touchable = Touchable.enabled
        }
        button.name = when (text) {
            "Free play" -> "usage-free-play"
            "Quickstart" -> "usage-quickstart"
            "Singleplayer" -> "usage-new-game"
            "Load game" -> "usage-load-game"
            "Multiplayer" -> "usage-multiplayer"
            else -> button.name
        }
        button.onActivation(binding = binding) { stopBackground(); action() }
        return button
    }

    private fun campaignCover(width: Float, height: Float): Stack {
        val map = Table().apply {
            right()
            background = BaseScreen.skinStrings.getUiBackground("", BaseScreen.skinStrings.roundedEdgeRectangleShape, blue)
            touchable = Touchable.disabled
            val hexes = Table()
            for (row in 0..3) {
                for (column in 0..2) {
                    val color = if ((row + column) % 3 == 0) gold.cpy() else Color.valueOf("7ca89b")
                    color.a = .14f
                    hexes.add(ImageGetter.getImage("OtherIcons/Hexagon", color)).size(height / 4.5f)
                        .pad(2f)
                }
                hexes.row()
            }
            add(hexes).padRight(12f)
        }
        val copy = Table().apply {
            pad(30f)
            top().left()
            add("New frontiers, new choices".toLabel(gold, 20)).left().row()
            add("Campaign challenges".toLabel(Color.WHITE, 48).apply { wrap = true })
                .width(width - 60f).left().padTop(18f).row()
            add("Designed situations. Your own strategy.".toLabel(Color.WHITE, 22).apply { wrap = true })
                .width(width * .68f).left().padTop(14f).row()
            add().expandY().row()
            val enter = menuButton("Enter campaigns", "", "OtherIcons/ArrowRight", width * .58f, primary = true) {
                game.pushScreen { com.unciv.ui.screens.campaign.CampaignScreen() }
            }.apply {
                name = "ui-enter-campaigns"
            }
            add(enter).width(width * .58f).height(80f).left()
        }
        // Decoration stays behind text, with no separate hit targets.
        val overlay = Table().apply {
            touchable = Touchable.childrenOnly
            add(copy).grow()
        }
        return Stack().apply {
            add(map)
            add(overlay)
            map.touchable = Touchable.disabled
        }
    }

    private fun loadResumeSubtitle(label: Label) {
        Concurrency.run("HomeSavePreview") {
            val preview = runCatching { game.files.loadGamePreviewFromFile(game.files.getSave("Autosave")) }
                .getOrNull() ?: return@run
            val nation = preview.civilizations.firstOrNull { it.civID == preview.currentPlayer }?.civName
                ?: return@run
            launchOnGLThread {
                if (game.getScreen() === screen) label.setText(nation.tr() + " · " + "Turn [${preview.turns}]".tr())
            }
        }
    }

    private fun showFreePlay() {
        Popup(screen).apply {
            addTitleWithCloseButton("Free play".toLabel(fontSize = 30))
            add(menuButton("Quickstart", "Start a game with the default settings", "OtherIcons/Quickstart",
                screen.stage.width * .50f, KeyboardBinding.Quickstart) { close(); quickstartNewGame() })
                .height(110f).padBottom(12f).row()
            add(menuButton("Singleplayer", "Choose your civilization, map and rules", "OtherIcons/New",
                screen.stage.width * .50f, KeyboardBinding.StartNewGame) { close(); game.pushScreen { NewGameScreen() } })
                .height(110f).row()
            open()
        }
    }

    private fun showMultiplayer() {
        if (game.platformCapabilities.onlineMultiplayer) game.pushScreen { MultiplayerScreen() }
        else Popup(screen).apply { addGoodSizedLabel(ONLINE_MULTIPLAYER_UNAVAILABLE).row(); addCloseButton(); open() }
    }

}
