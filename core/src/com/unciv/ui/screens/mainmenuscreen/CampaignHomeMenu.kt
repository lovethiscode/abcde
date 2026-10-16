package com.unciv.ui.screens.mainmenuscreen

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.Button
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.Stack
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.utils.Align
import com.badlogic.gdx.utils.Disposable
import com.unciv.UncivGame
import com.unciv.models.skins.ExpeditionBlueTheme
import com.unciv.models.translations.tr
import com.unciv.ui.components.UncivTooltip.Companion.addTooltip
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.input.KeyboardBinding
import com.unciv.ui.components.input.onActivation
import com.unciv.ui.components.input.onLongPress
import com.unciv.ui.components.widgets.UiNavigation
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

/** Selected A layout. All game creation, loading and tools retain their existing implementations. */
internal class CampaignHomeMenu(
    private val screen: MainMenuScreen,
    private val resumeGame: () -> Unit,
    private val quickstartNewGame: () -> Unit,
    private val stopBackground: () -> Unit
) : Table(), Disposable {
    private val ink = ExpeditionBlueTheme.color(Color.valueOf("1b3542"), ExpeditionBlueTheme.text)
    private val muted = ExpeditionBlueTheme.color(Color.valueOf("5c737c"), ExpeditionBlueTheme.muted)
    private val gold = ExpeditionBlueTheme.gold
    private val surface = ExpeditionBlueTheme.color(Color.valueOf("f8faf7"), ExpeditionBlueTheme.panel)
    private val game = screen.game
    private val unit = UiNavigation.minimumTouchSize() / 44f
    private val compactLandscape = screen.stage.width > screen.stage.height && screen.stage.height / unit < 600f
    private val artwork = HomeMenuArtwork()
    private fun px(value: Float) = value * unit
    private fun label(text: String, size: Int, color: Color = ink) =
        text.toLabel(color, (size * unit).roundToInt()).apply { wrap = true }

    init {
        name = "ui-campaign-home"
        background = ImageGetter.getWhiteDotDrawable().tint(
            ExpeditionBlueTheme.color(Color.valueOf("e5edf0"), ExpeditionBlueTheme.background))
        val tablet = !compactLandscape && screen.stage.width / unit >= 700f
        val sideBySide = (tablet || compactLandscape) && screen.stage.width > screen.stage.height
        val margin = px(if (compactLandscape) 12f else if (tablet) 32f else 20f)
        val gap = px(if (compactLandscape) 8f else if (tablet) 24f else 16f)
        val width = (screen.stage.width - margin * 2f).coerceAtMost(px(1200f))
        pad(margin).top()
        val header = Table()
        header.add(artwork.icon()).size(px(36f)).padRight(px(12f))
        val heading = Table()
        val headingWidth = width - px(108f)
        heading.add(label("Build a city. Shape your world.", 13, muted).apply { name = "ui-home-tagline" })
            .width(headingWidth).left().row()
        val version = game.displayBuildNumber?.let { "v${UncivGame.VERSION.text} ($it)" }
            ?: "v${UncivGame.VERSION.text}"
        heading.add(label(version, 10, muted).apply { setText(version); name = "ui-home-version" })
            .width(headingWidth).left().padTop(px(3f))
        header.add(heading).expandX().left()
        val options = button("Options", KeyboardBinding.MainMenuOptions) { screen.openOptionsPopup() }.apply {
            name = "ui-home-options"
            add(ImageGetter.getImage("OtherIcons/Options", gold)).size(px(22f))
            onLongPress { screen.openOptionsPopup(withDebug = true) }
            addTooltip("Options")
        }
        header.add(options).size(px(44f)).padLeft(px(16f))
        add(header).width(width).padBottom(gap).row()

        val coverWidth = if (sideBySide) (width - gap) * .53f else width
        val actionsWidth = if (sideBySide) width - coverWidth - gap else width
        val actions = Table()
        val hasSave = game.files.autosaves.autosaveExists()
        val resume = menuButton(if (hasSave) "Resume" else "Start new game",
            if (hasSave) "Your latest autosave" else "Choose your civilization, map and rules",
            "OtherIcons/Resume", actionsWidth, KeyboardBinding.Resume, primary = true, compact = compactLandscape) {
            if (hasSave) resumeGame() else showFreePlay()
        }.apply { name = "ui-resume" }
        if (hasSave) loadResumeSubtitle(resume.findActor("subtitle"))
        actions.add(resume).width(actionsWidth).minHeight(px(if (compactLandscape) 64f else if (sideBySide) 104f else if (tablet) 88f else 76f)).padBottom(gap).row()

        val actionColumns = if (actionsWidth / unit < 600f && game.settings.fontSizeMultiplier > 1.25f) 1 else 2
        val cellWidth = (actionsWidth - px(2f) * (actionColumns - 1)) / actionColumns
        val entries = listOf(
            menuButton("Free play", "Quickstart or customize a singleplayer game", "OtherIcons/Nations", cellWidth,
                compact = compactLandscape, action = ::showFreePlay),
            menuButton("Endless survival", "Defense hall", "OtherIcons/Shield", cellWidth, compact = compactLandscape) {
                game.pushScreen { com.unciv.ui.screens.endless.EndlessScreen() }
            }.apply { name = "ui-endless-open" },
            menuButton("Load game", "Choose a save and continue your civilization", "OtherIcons/Load", cellWidth,
                KeyboardBinding.MainMenuLoad, compact = compactLandscape) { game.pushScreen { LoadGameScreen() } },
            menuButton("Multiplayer", "Create a room and share its invitation, or join a friend's game.",
                "OtherIcons/Multiplayer", cellWidth, KeyboardBinding.Multiplayer, compact = compactLandscape, action = ::showMultiplayer)
        )
        val grid = Table().apply {
            background = BaseScreen.skinStrings.getUiBackground("", BaseScreen.skinStrings.roundedEdgeRectangleShape, surface)
        }
        entries.forEach { it.style = Button.ButtonStyle(it.style).apply { up = null } }
        for ((index, entry) in entries.withIndex()) {
            grid.add(entry).width(cellWidth).minHeight(px(if (compactLandscape) 80f else if (sideBySide) 132f else if (tablet) 120f else 108f)).fillY()
            if ((index + 1) % actionColumns != 0) {
                grid.add(ImageGetter.getWhiteDot().apply { color = ExpeditionBlueTheme.border }).width(px(2f)).growY()
            } else {
                grid.row()
                if (index != entries.lastIndex) grid.add(ImageGetter.getWhiteDot().apply { color = ExpeditionBlueTheme.border })
                    .colspan(actionColumns * 2 - 1).growX().height(px(1f)).row()
            }
        }
        actions.add(grid).width(actionsWidth)
        actions.pack()
        val cover = campaignCover(coverWidth, tablet)
        // Resolve wrapped labels at the cover width before asking for their preferred height.
        cover.pack()
        val coverHeight = maxOf(cover.prefHeight, px(if (compactLandscape) 220f else if (sideBySide) 420f else if (tablet) 300f else 256f),
            if (sideBySide) actions.prefHeight else 0f)
        val body = Table()
        body.add(cover).width(coverWidth).minHeight(coverHeight).fillY()
        if (sideBySide) body.add().width(gap) else body.row()
        body.add(actions).width(actionsWidth).top().padTop(if (sideBySide) 0f else gap)
        add(body).width(width).padBottom(gap).row()

        add(ImageGetter.getWhiteDot().apply { color = ExpeditionBlueTheme.border })
            .width(width).height(px(1f)).padBottom(px(8f)).row()
        val tools = Table()
        val toolCount = if (game.achievementsAvailable) 4 else 3
        val toolColumns = if (width / unit < 900f && game.settings.fontSizeMultiplier > 1.25f) 2 else toolCount
        val toolWidth = (width - px(8f) * (toolColumns - 1)) / toolColumns
        var addedTools = 0
        fun tool(text: String, icon: String, binding: KeyboardBinding = KeyboardBinding.None, action: () -> Unit): Button {
            val title = label(text, if (tablet) 15 else 12).apply { wrap = false; setAlignment(Align.center) }
            val inline = tablet || compactLandscape
            val titleWidth = if (inline) title.prefWidth.coerceAtMost(toolWidth - px(44f)) else toolWidth - px(10f)
            title.wrap = true
            val control = button(text, binding, action).apply {
                name = "ui-home-${text.lowercase().replace(' ', '-')}"
                style = Button.ButtonStyle(style).apply { up = null }
                pad(px(10f), px(5f), px(10f), px(5f))
                add(ImageGetter.getImage(icon, gold)).size(px(22f))
                if (!inline) row()
                add(title).width(titleWidth)
                    .padTop(if (inline) 0f else px(6f)).padLeft(if (inline) px(6f) else 0f)
            }
            tools.add(control).width(toolWidth).minHeight(px(if (compactLandscape) 44f else 64f)).fillY()
                .padTop(if (addedTools >= toolColumns) px(8f) else 0f)
                .padRight(if ((addedTools + 1) % toolColumns == 0) 0f else px(8f))
            if (++addedTools % toolColumns == 0) tools.row()
            return control
        }
        tool("Civilopedia", "OtherIcons/Book", KeyboardBinding.Civilopedia) { screen.openCivilopedia() }
        tool("Mods", "OtherIcons/Mods", KeyboardBinding.ModManager) {
            game.pushScreen { ModManagementScreen() }
        }.apply {
            isDisabled = !game.platformCapabilities.onlineModManagement
            if (isDisabled) color.a = .5f
        }
        if (game.achievementsAvailable) tool("Achievements", "OtherIcons/AchievementTrophy") { game.showAchievements() }
        tool("Map editor", "OtherIcons/MapEditor", KeyboardBinding.MapEditor) { game.pushScreen { MapEditorScreen() } }
        add(tools).width(width)
        pack()
    }

    private fun button(text: String, binding: KeyboardBinding = KeyboardBinding.None, action: () -> Unit): Button {
        fun background(color: Color) = BaseScreen.skinStrings.getUiBackground("", BaseScreen.skinStrings.roundedEdgeRectangleShape, color)
        return Button(Button.ButtonStyle().apply {
            up = background(surface)
            down = background(ExpeditionBlueTheme.raised)
            over = down
            disabled = background(surface.cpy().apply { a = .5f })
        }).apply {
            touchable = Touchable.enabled
            onActivation(binding = binding) { if (!isDisabled) { stopBackground(); action() } }
            addTooltip(text)
        }
    }

    private fun menuButton(text: String, subtitle: String, icon: String, width: Float,
                           binding: KeyboardBinding = KeyboardBinding.None, primary: Boolean = false,
                           compact: Boolean = false, action: () -> Unit): Button {
        val foreground = if (primary) ExpeditionBlueTheme.primaryInk else ink
        return button(text, binding, action).apply {
            if (primary) style = Button.ButtonStyle(style).apply {
                up = BaseScreen.skinStrings.getUiBackground("", BaseScreen.skinStrings.roundedEdgeRectangleShape, gold)
                down = BaseScreen.skinStrings.getUiBackground("", BaseScreen.skinStrings.roundedEdgeRectangleShape, gold.cpy().mul(.85f, .85f, .85f, 1f))
                over = down
            }
            pad(px(if (compact) 8f else 16f))
            val copyWidth = width - px(if (primary) 70f else 32f)
            if (primary) add(ImageGetter.getImage(icon, foreground)).size(px(26f)).padRight(px(12f))
            val copy = Table().apply {
                if (!primary) {
                    add(ImageGetter.getImage(icon, gold)).size(px(if (compact) 18f else 22f)).left()
                        .padBottom(px(if (compact) 6f else 10f)).row()
                }
                add(label(text, if (primary && compact) 20 else if (primary) 22 else 17, foreground)).width(copyWidth).left().row()
                if (subtitle.isNotEmpty()) add(label(subtitle, 12, if (primary) foreground else muted).apply { name = "subtitle" })
                    .width(copyWidth).left().padTop(px(if (compact) 4f else 6f))
            }
            add(copy).growX().left()
            name = when (text) {
                "Free play" -> "usage-free-play"
                "Quickstart" -> "usage-quickstart"
                "Singleplayer" -> "usage-new-game"
                "Load game" -> "usage-load-game"
                "Multiplayer" -> "usage-multiplayer"
                else -> name
            }
        }
    }

    private fun campaignCover(width: Float, tablet: Boolean): Stack {
        val inset = px(if (compactLandscape) 16f else if (tablet) 28f else 24f)
        val copyWidth = width - inset * 2f
        val copy = Table().apply {
            pad(inset).top().left()
            add(label("Every step opens a new world.", if (compactLandscape) 28 else if (tablet) 42 else 32)).width(copyWidth).left().row()
            add(label("Campaign challenges", if (compactLandscape) 15 else 17)).width(copyWidth).left().padTop(px(if (compactLandscape) 8f else 14f)).row()
            add(label("Designed situations. Your own strategy.", if (compactLandscape) 12 else 13)).width(copyWidth).left().padTop(px(if (compactLandscape) 6f else 8f)).row()
            add().minHeight(px(if (compactLandscape) 12f else 22f)).expandY().row()
            val title = label("Enter campaigns", if (compactLandscape) 15 else 17, ExpeditionBlueTheme.primaryInk).apply { wrap = false }
            val enterWidth = (title.prefWidth + px(70f)).coerceAtLeast(px(172f)).coerceAtMost(copyWidth)
            title.wrap = true
            val enter = button("Enter campaigns") {
                game.pushScreen { com.unciv.ui.screens.campaign.CampaignScreen() }
            }.apply {
                name = "ui-enter-campaigns"
                style = Button.ButtonStyle(style).apply {
                    up = BaseScreen.skinStrings.getUiBackground("", BaseScreen.skinStrings.roundedEdgeRectangleShape, gold)
                    down = BaseScreen.skinStrings.getUiBackground("", BaseScreen.skinStrings.roundedEdgeRectangleShape, gold.cpy().mul(.85f, .85f, .85f, 1f))
                    over = down
                }
                pad(px(if (compactLandscape) 8f else 12f), px(18f), px(if (compactLandscape) 8f else 12f), px(18f))
                add(title).width(enterWidth - px(70f)).left()
                add(ImageGetter.getImage("OtherIcons/ArrowRight", ExpeditionBlueTheme.primaryInk))
                    .size(px(20f)).padLeft(px(14f))
            }
            add(enter).width(enterWidth).minHeight(px(44f)).left()
        }
        return Stack().apply { add(artwork.cover(px(16f))); add(copy) }
    }

    private fun loadResumeSubtitle(label: Label) {
        Concurrency.run("HomeSavePreview") {
            val preview = runCatching { game.files.loadGamePreviewFromFile(game.files.getSave("Autosave")) }
                .getOrNull() ?: return@run
            val nation = preview.civilizations.firstOrNull { it.civID == preview.currentPlayer }?.civName
                ?: return@run
            launchOnGLThread {
                if (game.getScreen() === screen) label.setText(nation.tr(hideIcons = true) + " · " + "Turn [${preview.turns}]".tr(hideIcons = true))
            }
        }
    }

    private fun showFreePlay() {
        val width = (screen.stage.width * .9f - px(40f)).coerceAtMost(px(640f))
        val columns = if (width / unit / game.settings.fontSizeMultiplier >= 560f) 2 else 1
        val gap = px(12f)
        val choiceWidth = (width - gap * (columns - 1)) / columns
        Popup(screen).apply {
            name = "ui-free-play-popup"
            innerTable.background = BaseScreen.skinStrings.getUiBackground("",
                BaseScreen.skinStrings.roundedEdgeRectangleShape,
                ExpeditionBlueTheme.color(Color.valueOf("e5edf0"), ExpeditionBlueTheme.background))
            bottomTable.pad(0f)
            (getScrollPane()?.actor as? Table)?.apply {
                pad(px(20f))
                defaults().pad(0f)
            }
            getScrollPane()?.setScrollingDisabled(true, false)
            addTitleWithCloseButton(label("Free play", 24))
            fun choice(title: String, description: String, icon: String,
                       binding: KeyboardBinding, primary: Boolean, action: () -> Unit): Button {
                val foreground = if (primary) ExpeditionBlueTheme.primaryInk else ink
                val copyWidth = choiceWidth - px(94f)
                return button(title, binding) { close(); action() }.apply {
                    name = if (primary) "usage-new-game" else "usage-quickstart"
                    if (primary) style = Button.ButtonStyle(style).apply {
                        up = BaseScreen.skinStrings.getUiBackground("", BaseScreen.skinStrings.roundedEdgeRectangleShape, gold)
                        down = BaseScreen.skinStrings.getUiBackground("", BaseScreen.skinStrings.roundedEdgeRectangleShape,
                            gold.cpy().mul(.85f, .85f, .85f, 1f))
                        over = down
                    }
                    pad(px(16f))
                    add(ImageGetter.getImage(icon, if (primary) foreground else gold))
                        .size(px(22f)).padRight(px(12f)).top()
                    val copy = Table().apply {
                        add(label(title, 18, foreground)).width(copyWidth).left().row()
                        add(label(description, 13, if (primary) foreground else muted))
                            .width(copyWidth).left().padTop(px(6f))
                    }
                    add(copy).width(copyWidth).left()
                    add(ImageGetter.getImage("OtherIcons/ArrowRight", if (primary) foreground else gold))
                        .size(px(16f)).padLeft(px(12f))
                }
            }
            val choices = Table()
            choices.add(choice("Quickstart", "Start a game with the default settings", "OtherIcons/Quickstart",
                KeyboardBinding.Quickstart, false, quickstartNewGame))
                .width(choiceWidth).minHeight(px(104f)).fillY()
            if (columns == 2) choices.add().width(gap) else choices.row()
            choices.add(choice("Singleplayer", "Choose your civilization, map and rules", "OtherIcons/New",
                KeyboardBinding.StartNewGame, true) { game.pushScreen { NewGameScreen() } })
                .width(choiceWidth).minHeight(px(104f)).fillY().padTop(if (columns == 1) gap else 0f)
            add(choices).width(width).row()
            open()
        }
    }

    private fun showMultiplayer() {
        if (game.platformCapabilities.onlineMultiplayer) game.pushScreen { MultiplayerScreen() }
        else Popup(screen).apply { addGoodSizedLabel(ONLINE_MULTIPLAYER_UNAVAILABLE).row(); addCloseButton(); open() }
    }

    override fun dispose() = artwork.dispose()
}
