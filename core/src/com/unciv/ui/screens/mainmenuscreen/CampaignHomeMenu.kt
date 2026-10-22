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
import com.unciv.ui.components.fonts.TextRole
import com.unciv.ui.components.fonts.UiTypography
import com.unciv.ui.components.input.KeyboardBinding
import com.unciv.ui.components.input.onActivation
import com.unciv.ui.components.input.onLongPress
import com.unciv.ui.components.widgets.AutoScrollPane
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.screens.mapeditorscreen.MapEditorScreen
import com.unciv.ui.screens.modmanager.ModManagementScreen
import com.unciv.ui.screens.multiplayerscreens.MultiplayerScreen
import com.unciv.ui.screens.newgamescreen.NewGameScreen
import com.unciv.ui.screens.savescreens.LoadGameScreen
import com.unciv.utils.Concurrency
import com.unciv.utils.ONLINE_MULTIPLAYER_UNAVAILABLE
import com.unciv.utils.launchOnGLThread

/** Singleplayer-first iOS home. Existing destinations and keyboard shortcuts are preserved. */
internal class CampaignHomeMenu(
    private val screen: MainMenuScreen,
    private val resumeGame: () -> Unit,
    private val quickstartNewGame: () -> Unit,
    private val stopBackground: () -> Unit
) : Table(), Disposable {
    private val game = screen.game
    private val unit = UiTypography.unitsPerPoint(screen.stage)
    private val ink = ExpeditionBlueTheme.text
    private val muted = ExpeditionBlueTheme.muted
    private val gold = ExpeditionBlueTheme.gold
    private val surface = ExpeditionBlueTheme.panel
    private val artwork = HomeMenuArtwork()
    private val homeStyle by lazy { HomeMenuStyle(unit) }
    private fun px(points: Float) = points * unit
    private fun label(text: String, role: TextRole, color: Color = ink) =
        text.toLabel(color, if (role == TextRole.Display) UiTypography.displaySize(false, unit = unit)
            else UiTypography.size(role, unit)).apply { wrap = true }

    init {
        name = "ui-campaign-home"
        background = ImageGetter.getWhiteDotDrawable().tint(ExpeditionBlueTheme.background)
        buildMenu()
        findActor<Label>("ui-home-save-subtitle")?.let(::loadResumeSubtitle)
        setFillParent(true)
        setSize(screen.stage.width, screen.stage.height)
    }

    private fun buildMenu() {
        val viewportWidth = screen.stage.width / unit
        val viewportHeight = screen.stage.height / unit
        val landscape = viewportWidth > viewportHeight
        val tabletPortrait = !landscape && viewportWidth >= 760f
        val wideLandscape = landscape && viewportWidth >= 1000f
        val margin = px(if (tabletPortrait || wideLandscape) 32f else if (landscape) 10f else 18f)
        val gap = px(if (tabletPortrait || wideLandscape) 16f else 8f)
        val width = (screen.stage.width - margin * 2f).coerceAtMost(px(1160f))
        pad(margin)
        top()

        val header = Table()
        val markWidth = px(if (tabletPortrait || wideLandscape) 48f else 38f)
        val headingWidth = width - markWidth - px(10f + 44f)
        header.add(artwork.icon()).size(markWidth).padRight(px(10f))
        val heading = Table()
        heading.add(label("Unciv", TextRole.PageTitle)).width(headingWidth).left().row()
        val version = game.displayBuildNumber?.let { "v${UncivGame.VERSION.text} ($it)" }
            ?: "v${UncivGame.VERSION.text}"
        heading.add(label(version, TextRole.Metadata, muted).apply { name = "ui-home-version" })
            .width(headingWidth).left()
        header.add(heading).width(headingWidth).left()
        header.add(button("Options", KeyboardBinding.MainMenuOptions) { screen.openOptionsPopup() }.apply {
            name = "ui-home-options"
            style = Button.ButtonStyle(style).apply { up = homeStyle.outline(ExpeditionBlueTheme.background, ExpeditionBlueTheme.border) }
            add(ImageGetter.getImage("OtherIcons/Options", gold)).size(px(22f))
            onLongPress { screen.openOptionsPopup(withDebug = true) }
        }).size(px(44f))
        add(header).width(width).minHeight(px(48f)).padBottom(gap).row()

        val body = Table().top()
        if (landscape) {
            val heroWidth = (width - gap) * (if (wideLandscape) .64f else .55f)
            val supportWidth = width - heroWidth - gap
            val support = support(supportWidth, landscape = true, tabletPortrait = false, gap = gap)
            val bodyHeight = maxOf(support.prefHeight, px(if (wideLandscape) 460f else 214f))
            body.add(hero(heroWidth, landscape)).width(heroWidth).minHeight(bodyHeight).fillY()
            body.add().width(gap)
            body.add(support).width(supportWidth).fillY()
            body.row()
        } else {
            body.add(hero(width, landscape = false)).width(width)
                .minHeight(px(if (tabletPortrait) 420f else 244f)).fillY().padBottom(gap).row()
            body.add(support(width, landscape = false, tabletPortrait = tabletPortrait, gap = gap))
                .width(width).padBottom(gap).row()
        }
        val footerSpacer = if (landscape) null else body.add().width(width).height(0f).also { body.row() }
        val dividerCell = body.add(ImageGetter.getWhiteDot().apply { color = ExpeditionBlueTheme.border })
            .width(width).height(px(1f)).padTop(px(4f)).padBottom(px(4f))
        if (landscape) dividerCell.colspan(3)
        body.row()
        val toolsCell = body.add(tools(width, landscape || tabletPortrait)).width(width)
        if (landscape) toolsCell.colspan(3)
        body.pack()
        if (footerSpacer != null) {
            val headerHeight = maxOf(header.prefHeight, px(48f))
            val visibleBodyHeight = screen.stage.height - margin * 2f - headerHeight - gap
            footerSpacer.height((visibleBodyHeight - body.prefHeight).coerceAtLeast(0f))
            body.pack()
        }
        val scroll = AutoScrollPane(body).apply {
            setScrollingDisabled(true, false)
            setOverscroll(false, false)
            setFadeScrollBars(true)
            setScrollbarsVisible(false)
        }
        add(scroll).width(width).growY().top()
    }

    private fun hero(width: Float, landscape: Boolean): Stack {
        val inset = px(if (width / unit >= 500f) 28f else 18f)
        val copyWidth = width - inset * 2f
        val content = Table().top().left().pad(inset)
        val title = Table()
        title.add(ImageGetter.getImage("OtherIcons/Nations", gold)).size(px(28f)).padRight(px(10f))
        title.add(label("Solo free play", TextRole.Display)).width(copyWidth - px(38f)).left()
        content.add(title).width(copyWidth).left().row()
        content.add(label("Play at your own pace, from one city to a new civilization.", TextRole.Supporting, muted))
            .width(copyWidth.coerceAtMost(px(340f))).left().padTop(px(10f)).row()
        content.add().growY().minHeight(px(if (landscape) 12f else 22f)).row()

        val stackActions = copyWidth / unit < 300f || game.settings.fontSizeMultiplier > 1.25f
        val actionGap = px(8f)
        val actionWidth = if (stackActions) copyWidth else (copyWidth - actionGap) / 2f
        val actions = Table()
        actions.add(heroAction("Quickstart", actionWidth, true, KeyboardBinding.Quickstart, "usage-quickstart", quickstartNewGame))
            .width(actionWidth).minHeight(px(50f))
        if (stackActions) actions.row() else actions.add().width(actionGap)
        actions.add(heroAction("Customize game", actionWidth, false, KeyboardBinding.StartNewGame, "usage-new-game") {
            game.pushScreen { NewGameScreen() }
        }).width(actionWidth).minHeight(px(50f)).padTop(if (stackActions) actionGap else 0f)
        content.add(actions).width(copyWidth).left()
        return Stack().apply {
            name = "usage-free-play"
            add(artwork.cover(px(16f)))
            add(content)
        }
    }

    private fun heroAction(text: String, width: Float, primary: Boolean, binding: KeyboardBinding,
                           id: String, action: () -> Unit): Button = button(text, binding, action).apply {
        name = id
        style = Button.ButtonStyle(style).apply {
            up = if (primary) homeStyle.surface(gold, 10f)
                else homeStyle.outline(ExpeditionBlueTheme.background, gold)
            down = homeStyle.surface(ExpeditionBlueTheme.raised, 10f)
            over = down
        }
        pad(px(9f), px(12f), px(9f), px(12f))
        val textWidth = width - px(12f * 2f + 22f)
        add(label(text, TextRole.Control, if (primary) ExpeditionBlueTheme.primaryInk else gold))
            .width(textWidth).left()
        add(ImageGetter.getImage("OtherIcons/ArrowRight", if (primary) ExpeditionBlueTheme.primaryInk else gold))
            .size(px(16f)).padLeft(px(6f))
    }

    private fun support(width: Float, landscape: Boolean, tabletPortrait: Boolean, gap: Float): Table {
        val table = Table().top()
        val recentHeight = px(if (tabletPortrait) 100f else if (landscape) 56f else 60f)
        val multiHeight = px(if (tabletPortrait) 100f else if (landscape) 68f else 72f)
        if (tabletPortrait) {
            val half = (width - gap) / 2f
            table.add(recent(half)).width(half).minHeight(recentHeight).fillY()
            table.add().width(gap)
            table.add(multiplayer(half)).width(half).minHeight(multiHeight).fillY()
            table.row()
        } else {
            table.add(recent(width)).width(width).minHeight(recentHeight).fillY().padBottom(gap).row()
            table.add(multiplayer(width)).width(width).minHeight(multiHeight).fillY().padBottom(gap).row()
        }
        if (tabletPortrait) {
            table.add().colspan(3).height(gap)
            table.row()
        }
        val modeWidth = (width - gap) / 2f
        val modes = Table()
        modes.add(modeCard("Campaign challenges", "OtherIcons/Scenarios", modeWidth, "ui-enter-campaigns") {
            game.pushScreen { com.unciv.ui.screens.campaign.CampaignScreen() }
        }).width(modeWidth).minHeight(px(if (tabletPortrait) 84f else 64f)).fillY()
        modes.add().width(gap)
        modes.add(modeCard("Endless survival", "OtherIcons/Shield", modeWidth, "ui-endless-open") {
            game.pushScreen { com.unciv.ui.screens.endless.EndlessScreen() }
        }).width(modeWidth).minHeight(px(if (tabletPortrait) 84f else 64f)).fillY()
        val modesCell = table.add(modes).width(width)
        if (tabletPortrait) modesCell.colspan(3)
        return table
    }

    private fun recent(width: Float): Table {
        val hasSave = game.files.autosaves.autosaveExists()
        val loadWidth = px(if (width / unit < 330f) 88f else 108f)
        val resumeWidth = width - loadWidth - px(8f)
        val table = Table()
        if (hasSave) {
            val resume = button("Resume", KeyboardBinding.Resume, resumeGame).apply {
                name = "ui-resume"
                style = Button.ButtonStyle(style).apply {
                    up = homeStyle.outline(ExpeditionBlueTheme.background, ExpeditionBlueTheme.border)
                }
                pad(px(8f))
                add(ImageGetter.getImage("OtherIcons/Resume", gold)).size(px(20f)).padRight(px(8f))
                val copy = Table()
                copy.add(label("Resume", TextRole.Control)).width(resumeWidth - px(44f)).left().row()
                copy.add(label("Your latest autosave", TextRole.Supporting, muted).apply { name = "ui-home-save-subtitle" })
                    .width(resumeWidth - px(44f)).left()
                add(copy).width(resumeWidth - px(44f)).left()
            }
            table.add(resume).width(resumeWidth).minHeight(px(56f)).fillY()
        } else {
            val empty = Table().apply {
                background = homeStyle.outline(ExpeditionBlueTheme.background, ExpeditionBlueTheme.border)
                pad(px(10f))
                add(label("No game to continue yet", TextRole.Supporting, muted))
                    .width(resumeWidth - px(20f)).left()
            }
            table.add(empty).width(resumeWidth).minHeight(px(56f)).fillY()
        }
        table.add().width(px(8f))
        table.add(button("Load game", KeyboardBinding.MainMenuLoad) {
            game.pushScreen { LoadGameScreen() }
        }.apply {
            name = "usage-load-game"
            style = Button.ButtonStyle(style).apply {
                up = homeStyle.outline(ExpeditionBlueTheme.background, ExpeditionBlueTheme.border)
            }
            val copy = Table()
            copy.add(ImageGetter.getImage("OtherIcons/Load", gold)).size(px(20f)).row()
            copy.add(label("Load game", TextRole.Control).apply { setAlignment(Align.center) })
                .width(loadWidth - px(8f)).padTop(px(3f))
            add(copy)
        }).width(loadWidth).minHeight(px(56f)).fillY()
        return table
    }

    private fun multiplayer(width: Float): Button = button("Multiplayer", KeyboardBinding.Multiplayer) {
        if (game.platformCapabilities.onlineMultiplayer) game.pushScreen { MultiplayerScreen() }
    }.apply {
        name = "usage-multiplayer"
        style = Button.ButtonStyle(style).apply {
            up = homeStyle.outline(surface, ExpeditionBlueTheme.controlBorder)
            disabled = homeStyle.outline(surface.cpy().apply { a = .6f }, ExpeditionBlueTheme.border)
        }
        pad(px(12f))
        add(ImageGetter.getImage("OtherIcons/Multiplayer", gold)).size(px(26f)).padRight(px(12f))
        val copyWidth = width - px(12f * 2f + 26f + 12f)
        val copy = Table()
        copy.add(label("Multiplayer", TextRole.SectionTitle)).width(copyWidth).left().row()
        copy.add(label(if (game.platformCapabilities.onlineMultiplayer) "Enter the asynchronous turn lobby"
            else ONLINE_MULTIPLAYER_UNAVAILABLE, TextRole.Supporting, muted))
            .width(copyWidth).left().padTop(px(3f))
        add(copy).width(copyWidth).left()
        if (!game.platformCapabilities.onlineMultiplayer) {
            isDisabled = true
            addTooltip(ONLINE_MULTIPLAYER_UNAVAILABLE)
        }
    }

    private fun modeCard(text: String, icon: String, width: Float, id: String, action: () -> Unit): Button =
        button(text, action = action).apply {
            name = id
            style = Button.ButtonStyle(style).apply {
                up = homeStyle.outline(ExpeditionBlueTheme.background, ExpeditionBlueTheme.border)
            }
            pad(px(10f))
            add(ImageGetter.getImage(icon, gold)).size(px(20f)).padRight(px(8f))
            add(label(text, TextRole.Control)).width(width - px(48f)).left()
        }

    private fun tools(width: Float, wide: Boolean): Table {
        val table = Table()
        val count = if (game.achievementsAvailable) 4 else 3
        val columns = if (wide) count else 2
        val gap = px(8f)
        val toolWidth = (width - gap * (columns - 1)) / columns
        var index = 0
        fun addTool(text: String, icon: String, binding: KeyboardBinding = KeyboardBinding.None,
                    action: () -> Unit): Button {
            val tool = button(text, binding, action).apply {
                name = "ui-home-${text.lowercase().replace(' ', '-')}"
                style = Button.ButtonStyle(style).apply { up = null; over = homeStyle.surface(surface) }
                pad(px(6f))
                add(ImageGetter.getImage(icon, gold)).size(px(20f)).padRight(px(7f))
                add(label(text, TextRole.Control)).width(toolWidth - px(39f)).left()
            }
            table.add(tool).width(toolWidth).minHeight(px(44f)).fillY()
                .padRight(if ((index + 1) % columns == 0) 0f else gap)
                .padTop(if (index >= columns) gap else 0f)
            if (++index % columns == 0) table.row()
            return tool
        }
        addTool("Civilopedia", "OtherIcons/Book", KeyboardBinding.Civilopedia) { screen.openCivilopedia() }
        addTool("Mods", "OtherIcons/Mods", KeyboardBinding.ModManager) { game.pushScreen { ModManagementScreen() } }.apply {
            isDisabled = !game.platformCapabilities.onlineModManagement
            if (isDisabled) color.a = .5f
        }
        if (game.achievementsAvailable) addTool("Achievements", "OtherIcons/AchievementTrophy") { game.showAchievements() }
        addTool("Map editor", "OtherIcons/MapEditor", KeyboardBinding.MapEditor) { game.pushScreen { MapEditorScreen() } }
        return table
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

    override fun dispose() = artwork.dispose()
}
