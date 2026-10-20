package com.unciv.ui.screens.mainmenuscreen

import com.unciv.ui.components.fonts.UiTypography
import com.unciv.ui.components.fonts.TextRole

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

/** Approved 2026-10-17 responsive home. All game creation, loading and tools retain their existing implementations. */
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
    private var density = 0
    private val compact get() = density > 0
    private val minimal get() = density > 1
    private val artwork = HomeMenuArtwork()
    private val homeStyle by lazy { HomeMenuStyle(unit) }
    private fun px(value: Float) = value * unit
    private fun label(text: String, size: TextRole, color: Color = ink) =
        text.toLabel(color, if (size == TextRole.Display) UiTypography.displaySize(compact, unit = unit) else UiTypography.size(size, unit)).apply { wrap = true }

    init {
        name = "ui-campaign-home"
        background = ImageGetter.getWhiteDotDrawable().tint(
            ExpeditionBlueTheme.color(Color.valueOf("e5edf0"), ExpeditionBlueTheme.background))
        for (candidate in 0..2) {
            density = candidate
            clearChildren()
            buildMenu()
            if (prefHeight <= screen.stage.height) break
        }
        findActor<Label>("ui-home-save-subtitle")?.let(::loadResumeSubtitle)
        setFillParent(true)
        setSize(screen.stage.width, screen.stage.height)
    }

    private fun buildMenu() {
        val viewWidth = screen.stage.width / unit
        val viewHeight = screen.stage.height / unit
        val landscape = viewWidth > viewHeight
        val tablet = viewWidth >= 760f && viewHeight >= 600f
        val sideBySide = tablet || landscape
        val largeText = game.settings.fontSizeMultiplier > 1.25f
        val shortPhone = !tablet && viewHeight < 760f
        val margin = px(if (minimal) 6f else if (compact) 10f else if (tablet) 32f else 18f)
        val gap = px(if (minimal) 4f else if (compact) 8f else if (landscape) 12f else if (shortPhone) 12f else 22f)
        val width = (screen.stage.width - margin * 2f).coerceAtMost(px(1100f))
        val content = Table().apply {
            pad(margin)
            if (landscape && !tablet && !compact) { padTop(px(10f)); padBottom(px(10f)) }
            if (!tablet) top()
        }
        val header = Table()
        header.add(artwork.icon()).size(px(if (tablet) 48f else 34f)).padRight(px(12f))
        val headingWidth = width - px(if (tablet) 120f else 108f)
        val heading = Table()
        if (!minimal) heading.add(label("Build a city. Shape your world.", TextRole.Supporting, muted).apply { name = "ui-home-tagline" })
            .width(headingWidth).left().row()
        val version = game.displayBuildNumber?.let { "v${UncivGame.VERSION.text} ($it)" }
            ?: "v${UncivGame.VERSION.text}"
        heading.add(label(version, TextRole.Metadata, muted).apply { setText(version); name = "ui-home-version" })
            .width(headingWidth).left()
        header.add(heading).expandX().left()
        header.add(button("Options", KeyboardBinding.MainMenuOptions) { screen.openOptionsPopup() }.apply {
            name = "ui-home-options"
            style = Button.ButtonStyle(style).apply { up = null; over = null }
            add(ImageGetter.getImage("OtherIcons/Options", gold)).size(px(22f))
            onLongPress { screen.openOptionsPopup(withDebug = true) }
        }).size(px(44f))
        content.add(header).width(width).minHeight(px(44f)).padBottom(gap).row()

        val coverWidth = if (sideBySide) (width - gap) * (if (minimal) .32f else if (tablet) .51f else .425f) else width
        val actionsWidth = if (sideBySide) width - coverWidth - gap else width
        val hasSave = game.files.autosaves.autosaveExists()
        val resume = menuButton(if (hasSave) "Resume" else "Start new game",
            if (hasSave) "Your latest autosave" else "Choose your civilization, map and rules",
            if (hasSave) "OtherIcons/Resume" else "OtherIcons/New", actionsWidth,
            KeyboardBinding.Resume, primary = true, compact = compact) {
                if (hasSave) resumeGame() else showFreePlay()
            }.apply { name = "ui-resume" }
        if (hasSave) resume.findActor<Label>("subtitle")?.name = "ui-home-save-subtitle"
        val resumeHeight = px(if (minimal) 44f else if (compact) 56f else if (tablet) 112f else if (landscape || shortPhone) 72f else 84f)
        val modeColumns = if (tablet || largeText && !landscape && !compact) 1 else 2
        val modeWidth = (actionsWidth - gap * (modeColumns - 1)) / modeColumns
        val modes = Table()
        val free = menuButton("Free play", "Quickstart or customize a singleplayer game", "OtherIcons/Nations", modeWidth,
            compact = compact, action = ::showFreePlay)
        val endless = menuButton("Endless survival", "Defense hall", "OtherIcons/Shield", modeWidth, compact = compact) {
            game.pushScreen { com.unciv.ui.screens.endless.EndlessScreen() }
        }.apply { name = "ui-endless-open" }
        for ((index, entry) in listOf(free, endless).withIndex()) {
            modes.add(entry).width(modeWidth).minHeight(px(if (minimal) 44f else if (compact) 56f else if (tablet) 120f else if (landscape || shortPhone) 84f else 100f))
                .growY().padTop(if (index > 0 && modeColumns == 1) px(12f) else 0f)
            if (index == 0) { if (modeColumns == 1) modes.row() else modes.add().width(gap) }
        }
        val secondary = Table()
        val secondaryColumns = if (tablet && !landscape && !compact) 1 else 2
        val secondaryWidth = (actionsWidth - gap * (secondaryColumns - 1)) / secondaryColumns
        val load = secondaryButton("Load game", "OtherIcons/Load", secondaryWidth, KeyboardBinding.MainMenuLoad) {
            game.pushScreen { LoadGameScreen() }
        }.apply { name = "usage-load-game" }
        val multi = secondaryButton("Multiplayer", "OtherIcons/Multiplayer", secondaryWidth, KeyboardBinding.Multiplayer, ::showMultiplayer)
            .apply { name = "usage-multiplayer" }
        for ((index, entry) in listOf(load, multi).withIndex()) {
            secondary.add(entry).width(secondaryWidth).minHeight(px(if (compact || landscape) 44f else if (tablet) 58f else 54f))
                .fillY().padTop(if (index > 0 && secondaryColumns == 1) px(12f) else 0f)
            if (index == 0) { if (secondaryColumns == 1) secondary.row() else secondary.add().width(gap) }
        }
        val cover = campaignCover(coverWidth, tablet)
        cover.pack()
        val coverMinHeight = px(if (minimal) 60f else if (compact) 128f else if (tablet) { if (landscape) 480f else 560f }
            else if (landscape) 224f else if (viewHeight >= 800f) 240f else 160f)
        var coverCell: com.badlogic.gdx.scenes.scene2d.ui.Cell<Stack>
        if (sideBySide) {
            val actions = Table()
            actions.add(resume).width(actionsWidth).minHeight(resumeHeight).padBottom(gap).row()
            actions.add(modes).width(actionsWidth).growY().padBottom(gap).row()
            actions.add(secondary).width(actionsWidth)
            actions.pack()
            val body = Table()
            coverCell = body.add(cover).width(coverWidth).minHeight(maxOf(cover.prefHeight, coverMinHeight, actions.prefHeight)).fillY()
            body.add().width(gap)
            body.add(actions).width(actionsWidth).fillY()
            content.add(body).width(width).padBottom(gap).row()
        } else {
            content.add(resume).width(width).minHeight(resumeHeight).padBottom(gap).row()
            coverCell = content.add(cover).width(width).minHeight(maxOf(cover.prefHeight, coverMinHeight)).fillY().padBottom(gap)
            content.row()
            content.add(modes).width(width).padBottom(gap).row()
            content.add(secondary).width(width).padBottom(gap).row()
        }
        content.add(ImageGetter.getWhiteDot().apply { color = ExpeditionBlueTheme.border })
            .width(width).height(px(1f)).padBottom(px(if (minimal) 4f else 12f)).row()
        val tools = Table()
        val toolCount = if (game.achievementsAvailable) 4 else 3
        // Phone portrait needs enough width for localized tool names at the normal text size too.
        // The second row fits in the space the portrait composition already leaves below the tools.
        val toolColumns = if (!sideBySide) 2 else toolCount
        val toolWidth = (width - px(8f) * (toolColumns - 1)) / toolColumns
        var added = 0
        fun tool(text: String, icon: String, binding: KeyboardBinding = KeyboardBinding.None, action: () -> Unit): Button {
            val inline = (tablet || landscape) && !largeText
            val title = label(text, TextRole.Control).apply {
                wrap = false; setAlignment(Align.center)
            }
            val titleWidth = if (inline) title.prefWidth.coerceAtMost(toolWidth - px(42f)) else toolWidth - px(8f)
            title.wrap = true
            val control = button(text, binding, action).apply {
                name = "ui-home-${text.lowercase().replace(' ', '-')}"
                style = Button.ButtonStyle(style).apply { up = null }
                pad(px(if (minimal) 4f else 8f), px(4f), px(if (minimal) 4f else 8f), px(4f))
                if (!minimal) {
                    add(ImageGetter.getImage(icon, gold)).size(px(22f))
                    if (!inline) row()
                }
                add(title)
                    .width(titleWidth).padLeft(if (inline) px(10f) else 0f).padTop(if (!inline && !minimal) px(8f) else 0f)
            }
            tools.add(control).width(toolWidth).minHeight(px(if (landscape || minimal) 44f else 64f)).fillY()
                .padRight(if ((added + 1) % toolColumns == 0) 0f else px(8f)).padTop(if (added >= toolColumns) px(8f) else 0f)
            if (++added % toolColumns == 0) tools.row()
            return control
        }
        tool("Civilopedia", "OtherIcons/Book", KeyboardBinding.Civilopedia) { screen.openCivilopedia() }
        tool("Mods", "OtherIcons/Mods", KeyboardBinding.ModManager) { game.pushScreen { ModManagementScreen() } }.apply {
            isDisabled = !game.platformCapabilities.onlineModManagement
            if (isDisabled) color.a = .5f
        }
        if (game.achievementsAvailable) tool("Achievements", "OtherIcons/AchievementTrophy") { game.showAchievements() }
        tool("Map editor", "OtherIcons/MapEditor", KeyboardBinding.MapEditor) { game.pushScreen { MapEditorScreen() } }
        content.add(tools).width(width)
        content.pack()
        // Shrink artwork before reducing supporting copy. The home never contains a scroll pane.
        val overflow = (content.prefHeight - screen.stage.height).coerceAtLeast(0f)
        coverCell.minHeight(maxOf(cover.prefHeight, coverCell.minHeight - overflow))
        content.invalidateHierarchy()
        content.pack()
        add(content).width(width + margin * 2f).minHeight(content.prefHeight).expandY().fillY()
    }


    private fun button(text: String, binding: KeyboardBinding = KeyboardBinding.None, action: () -> Unit): Button =
        Button(Button.ButtonStyle().apply {
            up = homeStyle.surface(surface)
            down = homeStyle.surface(ExpeditionBlueTheme.raised)
            over = down
            disabled = homeStyle.surface(surface.cpy().apply { a = .5f })
        }).apply {
            touchable = Touchable.enabled
            onActivation(binding = binding) { if (!isDisabled) { stopBackground(); action() } }
            addTooltip(text)
        }

    private fun menuButton(text: String, subtitle: String, icon: String, width: Float,
                           binding: KeyboardBinding = KeyboardBinding.None, primary: Boolean = false,
                           compact: Boolean = false, action: () -> Unit): Button {
        val foreground = if (primary) ExpeditionBlueTheme.primaryInk else ink
        val tablet = screen.stage.width / unit >= 760f && screen.stage.height / unit >= 600f
        val inset = if (minimal) 6f else if (compact) 10f else if (tablet) 22f else 14f
        val withIcon = !minimal
        val withArrow = primary && !minimal || tablet && screen.stage.width > screen.stage.height && !primary && !compact
        val copyWidth = width - px(inset * 2f + (if (withIcon) 36f else 0f) + (if (withArrow) 28f else 0f))
        return button(text, binding, action).apply {
            if (primary) style = Button.ButtonStyle(style).apply {
                up = homeStyle.surface(gold)
                down = homeStyle.surface(gold.cpy().mul(.9f, .9f, .9f, 1f))
                over = down
            }
            pad(px(inset))
            if (withIcon) add(ImageGetter.getImage(icon, if (primary) foreground else gold)).size(px(24f)).padRight(px(12f)).top()
            val copy = Table().apply {
                add(label(text, if (primary) TextRole.SectionTitle else TextRole.Control, foreground))
                    .width(copyWidth).left().row()
                if (!minimal && (primary || !compact) && subtitle.isNotEmpty()) add(label(subtitle, TextRole.Supporting,
                    if (primary) foreground else muted).apply { name = "subtitle" })
                    .width(copyWidth).left().padTop(px(5f))
            }
            add(copy).width(copyWidth).left()
            if (withArrow) add(ImageGetter.getImage("OtherIcons/ArrowRight", if (primary) foreground else gold)).size(px(18f)).padLeft(px(10f))
            name = if (text == "Free play") "usage-free-play" else name
        }
    }

    private fun secondaryButton(text: String, icon: String, width: Float, binding: KeyboardBinding, action: () -> Unit): Button =
        button(text, binding, action).apply {
            style = Button.ButtonStyle(style).apply { up = homeStyle.outline(surface = ExpeditionBlueTheme.background, edge = ExpeditionBlueTheme.border) }
            pad(px(if (minimal) 6f else 10f))
            val iconSpace = if (minimal) 0f else 32f
            if (!minimal) add(ImageGetter.getImage(icon, gold)).size(px(20f)).padRight(px(12f))
            add(label(text, TextRole.Control)).width(width - px((if (minimal) 12f else 20f) + iconSpace))
        }

    private fun campaignCover(width: Float, tablet: Boolean): Stack {
        val inset = px(if (minimal) 8f else if (compact) 16f else if (tablet) 28f else 20f)
        val copyWidth = width - inset * 2f
        val copy = Table().apply {
            pad(inset).top().left()
            if (!minimal) {
                add(label("Every step opens a new world.", TextRole.Display)).width(copyWidth).left().row()
                if (tablet && !compact) {
                    add(label("Campaign challenges", TextRole.Body)).width(copyWidth).left().padTop(px(24f)).row()
                    add(label("Designed situations. Your own strategy.", TextRole.Supporting, muted)).width(copyWidth).left().padTop(px(12f)).row()
                }
                add().minHeight(px(if (compact) 8f else 14f)).expandY().row()
            }
            val title = label("Enter campaigns", TextRole.Control, gold).apply { wrap = false }
            val enterWidth = (title.prefWidth + px(58f)).coerceAtLeast(px(150f)).coerceAtMost(copyWidth)
            title.wrap = true
            val enter = button("Enter campaigns") { game.pushScreen { com.unciv.ui.screens.campaign.CampaignScreen() } }.apply {
                name = "ui-enter-campaigns"
                style = Button.ButtonStyle(style).apply {
                    up = homeStyle.outline(ExpeditionBlueTheme.background, gold)
                }
                pad(px(8f), px(14f), px(8f), px(14f))
                add(title).width(enterWidth - px(58f)).left()
                add(ImageGetter.getImage("OtherIcons/ArrowRight", gold)).size(px(18f)).padLeft(px(12f))
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
        object : Popup(screen) {
          init {
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
            addTitleWithCloseButton(label("Free play", TextRole.PageTitle))
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
                        add(label(title, TextRole.Body, foreground)).width(copyWidth).left().row()
                        add(label(description, TextRole.Supporting, if (primary) foreground else muted))
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
          }
        }.open()
    }

    private fun showMultiplayer() {
        if (game.platformCapabilities.onlineMultiplayer) game.pushScreen { MultiplayerScreen() }
        else Popup(screen).apply { addGoodSizedLabel(ONLINE_MULTIPLAYER_UNAVAILABLE).row(); addCloseButton(); open() }
    }

    override fun dispose() = artwork.dispose()
}
