package com.unciv.ui.screens.mainmenuscreen

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Group
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.Button
import com.badlogic.gdx.scenes.scene2d.ui.Cell
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.Stack
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.utils.Layout
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
import com.unciv.ui.components.widgets.UiNavigation
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
    private val touchSize = px(if (UiNavigation.isAndroid) 48f else 44f)
    private var density = 0
    private val compact get() = density > 0
    private val minimal get() = density > 1
    private var layoutWidth = 0f
    private var layoutHeight = 0f
    private var rebuildRequired = true
    private var resumeSubtitle: String? = null
    private fun px(points: Float) = points * unit
    private fun label(text: String, role: TextRole, color: Color = ink) =
        text.toLabel(color, if (role == TextRole.Display) UiTypography.displaySize(compact, unit = unit)
            else UiTypography.size(role, unit)).apply { wrap = true }

    /** Keep words (and short CJK action labels) whole when choosing columns. */
    private fun minimumLabelWidth(text: String, role: TextRole = TextRole.Control): Float {
        val probe = label(text, role).apply { wrap = false }
        val words = probe.text.toString().split(Regex("[\\s\\-]+"))
        return words.maxOf { word -> probe.setText(word); probe.prefWidth } + px(2f)
    }

    init {
        name = "ui-campaign-home"
        background = ImageGetter.getWhiteDotDrawable().tint(ExpeditionBlueTheme.background)
        setFillParent(true)
        setSize(screen.stage.width, screen.stage.height)
        rebuildMenu()
        if (game.files.autosaves.autosaveExists()) loadResumeSubtitle()
    }

    override fun layout() {
        if (rebuildRequired || width != layoutWidth || height != layoutHeight) rebuildMenu()
        super.layout()
    }

    private fun rebuildMenu() {
        layoutWidth = width
        layoutHeight = height
        rebuildRequired = false
        clearChildren()
        // A home layout must fit without a ScrollPane. Measure wrapped content before
        // selecting a density; never hide overflow or shrink the player's chosen font.
        var content: Table? = null
        var selectedDensity = 0
        val splits = if (width > height) listOf(null, .5f, .4f, .6f) else listOf(null)
        candidates@ for (candidate in 0..2) {
            density = candidate
            for (split in splits) {
                val measured = buildMenu(split)
                if (content == null || measured.prefHeight < content.prefHeight) {
                    content = measured
                    selectedDensity = candidate
                }
                // Scene2D rounds cell edges in Stage units. Sub-point rounding must
                // not remove an entire level of content from an otherwise fitting home.
                if (measured.prefHeight <= height + px(1f) && measured.prefWidth <= width + px(1f)) {
                    content = measured
                    selectedDensity = candidate
                    break@candidates
                }
            }
        }
        density = selectedDensity
        content?.let { add(it).width(it.prefWidth.coerceAtMost(width)).minHeight(it.prefHeight).growY() }
    }

    private fun buildMenu(heroFraction: Float?): Table {
        val viewportWidth = layoutWidth / unit
        val viewportHeight = layoutHeight / unit
        val landscape = viewportWidth > viewportHeight
        val tabletPortrait = !landscape && viewportWidth >= 760f
        val wideLandscape = landscape && viewportWidth >= 1000f
        val margin = px(when {
            minimal -> 8f
            compact -> if (landscape) 8f else 12f
            tabletPortrait || wideLandscape -> 32f
            landscape -> 10f
            else -> 18f
        })
        val gap = px(if (minimal) 8f else if (tabletPortrait || wideLandscape) 16f else 12f)
        val width = (layoutWidth - margin * 2f).coerceAtMost(px(1160f))
        val content = Table().top().pad(margin)

        val header = Table()
        val markWidth = px(if (!compact && (tabletPortrait || wideLandscape)) 48f else 38f)
        val headingWidth = width - markWidth - px(10f) - touchSize
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
        }).size(touchSize)
        content.add(header).width(width).minHeight(maxOf(px(48f), touchSize)).padBottom(gap).row()

        val body = Table().top()
        val heroCell: Cell<Stack>
        if (landscape) {
            val heroWidth = (width - gap) * (heroFraction ?: if (wideLandscape) .6f else .5f)
            val supportWidth = width - heroWidth - gap
            val support = support(supportWidth, landscape = true, tabletPortrait = false, gap = gap)
            heroCell = body.add(hero(heroWidth, landscape)).width(heroWidth)
                .minHeight(px(if (compact) 0f else if (wideLandscape) 460f else 214f)).growY()
            body.add().width(gap)
            body.add(support).width(supportWidth).growY()
            body.row()
        } else {
            heroCell = body.add(hero(width, landscape = false)).width(width)
                .minHeight(px(if (compact) 0f else if (tabletPortrait) 420f else 244f)).growY().padBottom(gap)
            body.row()
            body.add(support(width, landscape = false, tabletPortrait = tabletPortrait, gap = gap))
                .width(width).padBottom(gap).row()
        }
        val dividerCell = body.add(ImageGetter.getWhiteDot().apply { color = ExpeditionBlueTheme.border })
            .width(width).height(px(1f)).padTop(gap / 2f).padBottom(gap / 2f)
        if (landscape) dividerCell.colspan(3)
        body.row()
        val toolsCell = body.add(tools(width, landscape || tabletPortrait)).width(width)
        if (landscape) toolsCell.colspan(3)
        content.add(body).width(width).growY()
        measureContent(content)
        val overflow = (content.prefHeight - layoutHeight).coerceAtLeast(0f)
        // Artwork gives up its decorative minimum before any supporting copy is omitted.
        heroCell.minHeight(maxOf(heroCell.actor.prefHeight, heroCell.minHeight - overflow))
        content.invalidateHierarchy()
        measureContent(content)
        return content
    }

    /** Settle wrapped content inside the real viewport, rather than packing an
     * unconstrained tree. A descendant's new height must reach all ancestor caches
     * before a density can be judged. */
    private fun measureContent(content: Table) {
        fun resolve(actor: Actor) {
            (actor as? Layout)?.let { it.invalidate(); it.validate() }
            if (actor is Group) actor.children.forEach(::resolve)
            (actor as? Layout)?.invalidate()
        }
        content.setSize(layoutWidth, layoutHeight)
        var previousHeight = -1f
        repeat(8) {
            resolve(content)
            val measuredHeight = content.prefHeight
            if (kotlin.math.abs(measuredHeight - previousHeight) < .5f) return
            previousHeight = measuredHeight
        }
    }

    private fun hero(width: Float, landscape: Boolean): Stack {
        val inset = px(if (minimal) 8f else if (compact) 12f else if (width / unit >= 500f) 28f else 18f)
        val copyWidth = width - inset * 2f
        val content = Table().top().left().pad(inset)
        val title = Table()
        if (!minimal) title.add(ImageGetter.getImage("OtherIcons/Nations", gold)).size(px(28f)).padRight(px(10f))
        title.add(label("Solo free play", TextRole.Display)).width(copyWidth - if (minimal) 0f else px(38f)).left()
        content.add(title).width(copyWidth).left().row()
        if (!compact) content.add(label("Play at your own pace, from one city to a new civilization.", TextRole.Supporting, muted))
            .width(copyWidth.coerceAtMost(px(340f))).left().padTop(px(10f)).row()
        content.add().growY().minHeight(px(if (compact) 4f else if (landscape) 12f else 22f)).row()

        val actionGap = px(8f)
        val actionMinimum = maxOf(minimumLabelWidth("Quickstart"), minimumLabelWidth("Customize game")) +
            px(if (minimal) 16f else 46f)
        val stackActions = (copyWidth - actionGap) / 2f < actionMinimum
        val actionWidth = if (stackActions) copyWidth else (copyWidth - actionGap) / 2f
        val actions = Table()
        actions.add(heroAction("Quickstart", actionWidth, true, KeyboardBinding.Quickstart, "usage-quickstart", quickstartNewGame))
            .width(actionWidth).minHeight(maxOf(touchSize, px(if (compact) 44f else 50f)))
        if (stackActions) actions.row() else actions.add().width(actionGap)
        actions.add(heroAction("Customize game", actionWidth, false, KeyboardBinding.StartNewGame, "usage-new-game") {
            game.pushScreen { NewGameScreen() }
        }).width(actionWidth).minHeight(maxOf(touchSize, px(if (compact) 44f else 50f)))
            .padTop(if (stackActions) actionGap else 0f)
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
        val inset = px(if (minimal) 8f else 12f)
        pad(px(if (compact) 6f else 9f), inset, px(if (compact) 6f else 9f), inset)
        val textWidth = width - inset * 2f - if (minimal) 0f else px(22f)
        add(label(text, TextRole.Control, if (primary) ExpeditionBlueTheme.primaryInk else gold))
            .width(textWidth).left()
        if (!minimal) add(ImageGetter.getImage("OtherIcons/ArrowRight", if (primary) ExpeditionBlueTheme.primaryInk else gold))
            .size(px(16f)).padLeft(px(6f))
    }

    private fun support(width: Float, landscape: Boolean, tabletPortrait: Boolean, gap: Float): Table {
        val table = Table()
        val recentHeight = if (compact) touchSize else px(if (tabletPortrait) 100f else if (landscape) 56f else 60f)
        val multiHeight = if (compact) touchSize else px(if (tabletPortrait) 100f else if (landscape) 68f else 72f)
        if (tabletPortrait) {
            val half = (width - gap) / 2f
            table.add(recent(half)).width(half).minHeight(recentHeight).fillY()
            table.add().width(gap)
            table.add(multiplayer(half)).width(half).minHeight(multiHeight).fillY()
            table.row()
        } else {
            table.add(recent(width)).width(width).minHeight(recentHeight).growY().padBottom(gap).row()
            table.add(multiplayer(width)).width(width).minHeight(multiHeight).growY().padBottom(gap).row()
        }
        if (tabletPortrait) {
            table.add().colspan(3).height(gap)
            table.row()
        }
        val modeMinimum = maxOf(minimumLabelWidth("Campaign challenges"), minimumLabelWidth("Endless survival")) +
            px(if (minimal) 40f else 48f)
        val stackModes = (width - gap) / 2f < modeMinimum
        val modeWidth = if (stackModes) width else (width - gap) / 2f
        val modes = Table()
        modes.add(modeCard("Campaign challenges", "OtherIcons/Scenarios", modeWidth, "ui-enter-campaigns") {
            game.pushScreen { com.unciv.ui.screens.campaign.CampaignScreen() }
        }).width(modeWidth).minHeight(if (compact) touchSize else px(if (tabletPortrait) 84f else 64f)).fillY()
        if (stackModes) modes.row() else modes.add().width(gap)
        modes.add(modeCard("Endless survival", "OtherIcons/Shield", modeWidth, "ui-endless-open") {
            game.pushScreen { com.unciv.ui.screens.endless.EndlessScreen() }
        }).width(modeWidth).minHeight(if (compact) touchSize else px(if (tabletPortrait) 84f else 64f)).fillY()
            .padTop(if (stackModes) gap else 0f)
        val modesCell = table.add(modes).width(width).growY()
        if (tabletPortrait) modesCell.colspan(3)
        return table
    }

    private fun recent(width: Float): Table {
        val hasSave = game.files.autosaves.autosaveExists()
        val loadMinimum = minimumLabelWidth("Load game") + px(if (minimal) 12f else 16f)
        val resumeMinimum = minimumLabelWidth("Resume") + px(if (minimal) 40f else 44f)
        val stacked = width < loadMinimum + resumeMinimum + px(8f)
        val loadWidth = if (stacked) width else maxOf(loadMinimum, px(if (width / unit < 330f) 88f else 108f))
            .coerceAtMost(maxOf(loadMinimum, width - px(8f) - resumeMinimum))
        val resumeWidth = if (stacked) width else width - loadWidth - px(8f)
        val table = Table()
        if (hasSave) {
            val resume = button("Resume", KeyboardBinding.Resume, resumeGame).apply {
                name = "ui-resume"
                style = Button.ButtonStyle(style).apply {
                    up = homeStyle.outline(ExpeditionBlueTheme.background, ExpeditionBlueTheme.border)
                }
                pad(px(if (minimal) 6f else 8f))
                add(ImageGetter.getImage("OtherIcons/Resume", gold)).size(px(20f)).padRight(px(8f))
                val copyWidth = resumeWidth - px(if (minimal) 40f else 44f)
                val copy = Table()
                copy.add(label("Resume", TextRole.Control)).width(copyWidth).left().row()
                if (!minimal) copy.add(label("Your latest autosave", TextRole.Supporting, muted).apply {
                    name = "ui-home-save-subtitle"
                    resumeSubtitle?.let { setText(it) }
                }).width(copyWidth).left()
                add(copy).width(copyWidth).left()
            }
            table.add(resume).width(resumeWidth).minHeight(if (compact) touchSize else px(56f)).fillY()
        } else {
            val empty = Table().apply {
                background = homeStyle.outline(ExpeditionBlueTheme.background, ExpeditionBlueTheme.border)
                pad(px(if (compact) 6f else 10f))
                add(label("No game to continue yet", TextRole.Supporting, muted))
                    .width(resumeWidth - px(if (compact) 12f else 20f)).left()
            }
            table.add(empty).width(resumeWidth).minHeight(if (compact) touchSize else px(56f)).fillY()
        }
        if (stacked) {
            table.row()
            table.add().height(px(4f)).row()
        } else table.add().width(px(8f))
        table.add(button("Load game", KeyboardBinding.MainMenuLoad) {
            game.pushScreen { LoadGameScreen() }
        }.apply {
            name = "usage-load-game"
            style = Button.ButtonStyle(style).apply {
                up = homeStyle.outline(ExpeditionBlueTheme.background, ExpeditionBlueTheme.border)
            }
            val copy = Table()
            if (!minimal) copy.add(ImageGetter.getImage("OtherIcons/Load", gold)).size(px(20f)).row()
            copy.add(label("Load game", TextRole.Control).apply { setAlignment(Align.center) })
                .width(loadWidth - px(if (minimal) 12f else 8f)).padTop(if (minimal) 0f else px(3f))
            add(copy)
        }).width(loadWidth).minHeight(if (compact) touchSize else px(56f)).fillY()
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
        val inset = px(if (minimal) 6f else if (compact) 8f else 12f)
        pad(inset)
        add(ImageGetter.getImage("OtherIcons/Multiplayer", gold)).size(px(24f)).padRight(px(12f))
        val copyWidth = width - inset * 2f - px(36f)
        val copy = Table()
        copy.add(label("Multiplayer", TextRole.SectionTitle)).width(copyWidth).left().row()
        if (!compact || !game.platformCapabilities.onlineMultiplayer) copy.add(label(if (game.platformCapabilities.onlineMultiplayer) "Enter the asynchronous turn lobby"
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
            pad(px(if (minimal) 6f else 10f))
            add(ImageGetter.getImage(icon, gold)).size(px(20f)).padRight(px(8f))
            add(label(text, TextRole.Control)).width(width - px(if (minimal) 40f else 48f)).left()
        }

    private fun tools(width: Float, wide: Boolean): Table {
        val table = Table()
        val count = if (game.achievementsAvailable) 4 else 3
        val gap = px(8f)
        val minimumWidth = listOf("Civilopedia", "Mods", "Map editor")
            .let { if (game.achievementsAvailable) it + "Achievements" else it }
            .maxOf { minimumLabelWidth(it) } + px(39f)
        val columns = ((if (wide) count else 2) downTo 1).first {
            (width - gap * (it - 1)) / it >= minimumWidth || it == 1
        }
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
            table.add(tool).width(toolWidth).minHeight(touchSize).fillY()
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

    private fun loadResumeSubtitle() {
        Concurrency.run("HomeSavePreview") {
            val preview = runCatching { game.files.loadGamePreviewFromFile(game.files.getSave("Autosave")) }
                .getOrNull() ?: return@run
            val nation = preview.civilizations.firstOrNull { it.civID == preview.currentPlayer }?.civName
                ?: return@run
            launchOnGLThread {
                if (game.getScreen() === screen) {
                    resumeSubtitle = nation.tr(hideIcons = true) + " · " + "Turn [${preview.turns}]".tr(hideIcons = true)
                    rebuildRequired = true
                    invalidateHierarchy()
                }
            }
        }
    }

    override fun dispose() = artwork.dispose()
}
