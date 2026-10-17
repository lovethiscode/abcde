package com.unciv.ui.screens.victoryscreen

import com.unciv.ui.components.fonts.TextRole
import com.unciv.ui.components.input.onActivation
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.ui.Button
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.unciv.ui.screens.overviewscreen.OverviewUi
import com.unciv.ui.screens.overviewscreen.OverviewUi.pt
import com.unciv.ui.screens.overviewscreen.OverviewUi.font
import com.badlogic.gdx.scenes.scene2d.ui.VerticalGroup
import com.badlogic.gdx.utils.Align
import com.unciv.Constants
import com.unciv.UncivGame
import com.unciv.logic.GameInfo
import com.unciv.logic.VictoryData
import com.unciv.logic.civilization.Civilization
import com.unciv.models.metadata.GameSetupInfo
import com.unciv.models.ruleset.Victory
import com.unciv.models.translations.tr
import com.unciv.ui.audio.MusicMood
import com.unciv.ui.audio.MusicTrackChooserFlags
import com.unciv.ui.components.YearTextUtil
import com.unciv.ui.components.widgets.TabbedPager
import com.unciv.ui.components.widgets.UiNavigation
import com.unciv.ui.components.extensions.areSecretKeysPressed
import com.unciv.ui.components.extensions.enable
import com.unciv.ui.components.extensions.setFontSize
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.input.KeyCharAndCode
import com.unciv.ui.components.input.onClick
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.basescreen.RecreateOnResize
import com.unciv.ui.screens.newgamescreen.NewGameScreen
import com.unciv.ui.screens.pickerscreens.PickerScreen
import com.unciv.ui.screens.worldscreen.WorldScreen
import yairm210.purity.annotations.Readonly
import java.util.EnumSet

class VictoryScreen private constructor(
    private val worldScreen: WorldScreen,
    pageNumber: Int,
    private val pageState: VictoryPageState
) : PickerScreen(), RecreateOnResize {
    constructor(worldScreen: WorldScreen, pageNumber: Int = 0) : this(worldScreen, pageNumber, VictoryPageState())

    private val compact = stage.width < pt(640f)
    private val tablet = minOf(stage.width, stage.height) > pt(600f)
    private val contentWidth = stage.width - pt(if (tablet) 72f else 24f)
    private val contentHeight = stage.height - pt(if (compact) 192f else if (tablet) 172f else 144f) -
        (if (worldScreen.gameInfo.victoryData != null || worldScreen.selectedGameView.civView.getCiv().isDefeated()) pt(90f) else 0f)
    private val music get() = UncivGame.Current.musicController
    private val gameInfo = worldScreen.gameInfo
    private val playerCiv = worldScreen.selectedGameView.civView.getCiv()
    private val tabs = if (UiNavigation.isIos) TabbedPager(
        contentWidth, contentWidth, contentHeight, contentHeight,
        backgroundColor = OverviewUi.background, shortcutScreen = this, showHeader = false
    ).apply { background = OverviewUi.surface(OverviewUi.background) }
    else TabbedPager(separatorColor = Color.WHITE, shortcutScreen = this)

    internal class CivWithStat(val civ: Civilization, val value: Int) {
        constructor(civ: Civilization, category: RankingType) : this(civ, civ.getStatForRanking(category))
    }

    private enum class VictoryTabs(
        val key: Char,
        val caption: String? = null,
        val allowAsSecret: Boolean = false
    ) {
        OurStatus('O', caption = "Our status") {
            override fun getContent(parent: VictoryScreen) = VictoryScreenOurVictory(parent.worldScreen)
            override fun isHidden(playerCiv: Civilization) = playerCiv.isSpectator()
        },
        Global('G', caption = "Global status") {
            override fun getContent(parent: VictoryScreen) = VictoryScreenGlobalVictory(parent.worldScreen)
        },
        Illustration('I') {
            override fun getContent(parent: VictoryScreen) = VictoryScreenIllustrations(parent, parent.worldScreen)
            override fun isHidden(playerCiv: Civilization) = !VictoryScreenIllustrations.enablePage(playerCiv)
        },
        Demographics('D', allowAsSecret = true) {
            override fun getContent(parent: VictoryScreen) = VictoryScreenDemographics(parent.worldScreen)
            override fun isHidden(playerCiv: Civilization) =
                !playerCiv.isSpectator()
                    && !(playerCiv.gameInfo.gameParameters.showCivilizationStats == true && playerCiv.gameInfo.gameParameters.showDemographics)
                    && playerCiv.gameInfo.victoryData == null
        },
        Rankings('R', allowAsSecret = true) {
            override fun getContent(parent: VictoryScreen) = VictoryScreenCivRankings(parent.worldScreen)
            override fun isHidden(playerCiv: Civilization) =
                !playerCiv.isSpectator()
                    && !(playerCiv.gameInfo.gameParameters.showCivilizationStats == true && playerCiv.gameInfo.gameParameters.showRankings)
                    && playerCiv.gameInfo.victoryData == null
        },
        Charts('C') {
            override fun getContent(parent: VictoryScreen) = VictoryScreenCharts(parent.worldScreen)
            override fun isHidden(playerCiv: Civilization) =
                if (playerCiv.isSpectator())
                    playerCiv.gameInfo.civilizations.all { it.statsHistory.size < 2 }
                else
                    (playerCiv.statsHistory.size < 2
                        || !(playerCiv.gameInfo.gameParameters.showCivilizationStats == true && playerCiv.gameInfo.gameParameters.showCharts))
                    && playerCiv.gameInfo.victoryData == null
        },
        Replay('P', allowAsSecret = true) {
            override fun getContent(parent: VictoryScreen) = VictoryScreenReplay(parent.worldScreen)
            override fun isHidden(playerCiv: Civilization) =
                !playerCiv.isSpectator()
                    && playerCiv.gameInfo.victoryData == null
                    && playerCiv.isAlive()
                    // We show the replay after 5 turns. This is to ensure that the replay
                    // slider doesn't look weird.
                    && playerCiv.gameInfo.turns < 5
        };
        abstract fun getContent(parent: VictoryScreen): Table
        open fun isHidden(playerCiv: Civilization) = false
    }

    private val orderedTabs get() = if (UiNavigation.isIos) listOf(
        VictoryTabs.OurStatus, VictoryTabs.Global, VictoryTabs.Illustration, VictoryTabs.Rankings,
        VictoryTabs.Demographics, VictoryTabs.Charts, VictoryTabs.Replay
    ) else VictoryTabs.entries

    init {
        worldScreen.autoPlay.stopAutoPlay()
        //**************** Set up the tabs ****************
        splitPane.setFirstWidget(tabs)
        val iconSize = Constants.headingFontSize.toFloat()

        for (tab in orderedTabs) {
            val tabHidden = tab.isHidden(playerCiv)
            if (tabHidden && !(tab.allowAsSecret && Gdx.input.areSecretKeysPressed()))
                continue
            val icon = ImageGetter.getImage("VictoryScreenIcons/${tab.name}")
            tabs.addPage(
                tab.caption ?: tab.name,
                if (UiNavigation.isIos) nativeContent(tab) else tab.getContent(this),
                icon, iconSize,
                scrollAlign = Align.topLeft,
                shortcutKey = KeyCharAndCode(tab.key),
                secret = tabHidden
            )
        }
        tabs.selectPage(pageNumber)

        //**************** Set up bottom area - buttons and description label ****************
        when {
            gameInfo.victoryData != null ->
                displayWinner(gameInfo.victoryData!!)
            playerCiv.isDefeated() -> {
                displayWonOrLost(Victory().defeatString)
                music.chooseTrack(playerCiv.civName, MusicMood.Defeat, EnumSet.of(MusicTrackChooserFlags.SuffixMustMatch))
            }
            else -> {
                rightSideButton.isVisible = false
                setDefaultCloseAction()
            }
        }

        //**************** Set up floating info panels ****************
        // When horizontal screen space is scarce so they would overlap, insert
        // them into the scrolling portion of the TabbedPager header instead
        if (UiNavigation.isIos) buildNativeChrome() else {
            tabs.pack()
            val topRightPanel = VerticalGroup().apply {
                space(5f)
                align(Align.right)
                addActor("{Game Speed}: {${gameInfo.gameParameters.speed}}".toLabel(TextRole.Body))
                if ("Time" in gameInfo.gameParameters.victoryTypes)
                    addActor("{Max Turns}: ${gameInfo.gameParameters.maxTurns.tr()}".toLabel(TextRole.Body))
                pack()
            }
            val difficultyLabel = "{Difficulty}: {${gameInfo.difficulty}}".toLabel(TextRole.Body)
            val neededSpace = topRightPanel.width.coerceAtLeast(difficultyLabel.width) * 2 + tabs.getHeaderPrefWidth()
            if (UiNavigation.isIos || neededSpace > stage.width) {
                // Let additions take part in TabbedPager's header scrolling
                tabs.decorateHeader(difficultyLabel, leftSide = true, fixed = false)
                tabs.decorateHeader(topRightPanel, leftSide = false, fixed = false)
                tabs.headerScroll.fadeScrollBars = false
            } else {
                // Let additions float in the corners
                val panelY = stage.height - tabs.getRowHeight(0) * 0.5f
                stage.addActor(topRightPanel)
                topRightPanel.setPosition(stage.width - 10f, panelY, Align.right)
                stage.addActor(difficultyLabel)
                difficultyLabel.setPosition(10f, panelY, Align.left)
            }
        }
    }

    private fun nativeContent(tab: VictoryTabs): Table = when (tab) {
        VictoryTabs.OurStatus, VictoryTabs.Global -> VictoryProgressPage(worldScreen, pageState,
            tab == VictoryTabs.Global, compact, tablet, contentWidth, contentHeight)
        VictoryTabs.Rankings -> VictoryStatisticsPage(worldScreen, pageState, false, compact)
        VictoryTabs.Demographics -> VictoryStatisticsPage(worldScreen, pageState, true, compact)
        VictoryTabs.Charts -> VictoryScreenCharts(worldScreen, pageState, compact)
        VictoryTabs.Replay -> VictoryScreenReplay(worldScreen, pageState, contentWidth, contentHeight)
        else -> tab.getContent(this)
    }

    private fun buildNativeChrome() {
        pickerPane.remove()
        descriptionLabel.setFontSize(font(TextRole.Supporting))
        descriptionLabel.wrap = true
        for (button in listOf(closeButton, rightSideButton)) {
            button.label.setFontSize(font(TextRole.Control))
            button.style = com.badlogic.gdx.scenes.scene2d.ui.TextButton.TextButtonStyle(button.style).apply {
                up = OverviewUi.rounded(if (button == rightSideButton) OverviewUi.accent else OverviewUi.panel)
                down = OverviewUi.rounded(OverviewUi.selected)
                over = down
                fontColor = if (button == rightSideButton) com.unciv.models.skins.ExpeditionBlueTheme.primaryInk
                    else com.unciv.models.skins.ExpeditionBlueTheme.text
            }
            button.pad(0f, pt(14f), 0f, pt(14f))
        }
        val navigation = Table().apply { left() }
        val buttons = linkedMapOf<VictoryTabs, Button>()
        val navScroll = OverviewUi.scroll(navigation).apply { setScrollingDisabled(false, true) }
        fun refreshNavigation() {
            for (tab in orderedTabs) {
                val caption = tab.caption ?: tab.name
                if (tab in buttons || tabs.getPageIndex(caption) < 0) continue
                val button = OverviewUi.button(flat = true) { tabs.selectPage(caption) }.apply {
                    style = Button.ButtonStyle(style).apply { checked = OverviewUi.rounded(OverviewUi.raised) }
                    if (!compact) add(ImageGetter.getImage("VictoryScreenIcons/${tab.name}")).size(pt(18f)).padRight(pt(7f))
                    add(OverviewUi.plainLabel(caption, TextRole.Control))
                    pad(0f, pt(12f), 0f, pt(12f))
                    name = "victory-tab-${tab.name}"
                }
                buttons[tab] = button
                navigation.add(button).height(OverviewUi.touch).padRight(pt(4f))
            }
            for ((tab, button) in buttons) {
                button.isChecked = tabs.getPageIndex(tab.caption ?: tab.name) == tabs.activePage
                button.cells.last().actor.color = if (button.isChecked) OverviewUi.accent else com.unciv.models.skins.ExpeditionBlueTheme.text
            }
        }
        val back = UiNavigation.backButton("Map").apply { onActivation { game.popScreen() } }
        val header = Table().apply {
            add(back).height(OverviewUi.touch).padRight(pt(16f))
            add(OverviewUi.plainLabel("Victory status", TextRole.SectionTitle).apply { setEllipsis(true) }).growX().minWidth(0f).left()
            val context = Table().apply {
                add(ImageGetter.getNationPortrait(playerCiv.nation, pt(24f))).size(pt(24f)).padRight(pt(7f))
                add(OverviewUi.plainLabel(playerCiv.civName, TextRole.Supporting).apply { setEllipsis(true) }).minWidth(0f).padRight(pt(12f))
                val year = YearTextUtil.toYearText(gameInfo.getYear(), playerCiv.isLongCountDisplay())
                add(OverviewUi.plainLabel("${gameInfo.turns} {Turn} · $year", TextRole.Supporting, OverviewUi.muted))
            }
            if (compact) { row(); add(context).colspan(2).left().growX().minWidth(0f) } else add(context)
        }
        val metadata = Table().apply {
            val info = "{Difficulty}: {${gameInfo.difficulty}} · {Game Speed}: {${gameInfo.gameParameters.speed}}" +
                if ("Time" in gameInfo.gameParameters.victoryTypes) " · {Max Turns}: ${gameInfo.gameParameters.maxTurns.tr()}" else ""
            add(OverviewUi.plainLabel(info, TextRole.Supporting, OverviewUi.muted).apply { wrap = true }).growX().minWidth(0f).left()
            if (compact) row()
            val status = if (gameInfo.victoryData != null || playerCiv.isDefeated()) "Game ended" else "Game in progress"
            add(OverviewUi.plainLabel(status, TextRole.Supporting, OverviewUi.muted)).left().padLeft(if (compact) 0f else pt(12f))
        }
        val root = object : Table() {
            override fun act(delta: Float) {
                super.act(delta)
                if (tabs.pageCount() != buttons.size) refreshNavigation()
            }
        }.apply {
            background = OverviewUi.surface(OverviewUi.background)
            setFillParent(true)
            pad(pt(if (tablet) 24f else 8f), pt(if (tablet) 36f else 12f),
                pt(if (tablet) 24f else 12f), pt(if (tablet) 36f else 12f))
            add(header).growX().minWidth(0f).height(pt(if (compact) 76f else 48f)).row()
            add(ImageGetter.getWhiteDot().apply { color = OverviewUi.border }).growX().height(pt(1f)).row()
            add(navScroll).growX().minWidth(0f).height(pt(52f)).row()
            add(metadata).growX().minWidth(0f).padBottom(pt(9f)).row()
            add(tabs).grow().minWidth(0f).minHeight(0f).row()
            if (rightSideButton.isVisible) {
                val footer = Table().apply {
                    add(descriptionLabel).colspan(if (compact) 2 else 1).growX().minWidth(0f).padRight(pt(12f))
                    if (compact) row()
                    add(closeButton).height(OverviewUi.touch).padRight(pt(8f))
                    add(rightSideButton).height(OverviewUi.touch)
                }
                add(footer).growX().minWidth(0f).padTop(pt(10f))
            }
        }
        tabs.onSelection { _, _, _ ->
            refreshNavigation()
            buttons.values.firstOrNull { it.isChecked }?.let {
                root.validate(); navScroll.validate()
                navScroll.scrollTo(it.x, it.y, it.width, it.height, false, false)
                navScroll.updateVisualScroll()
            }
        }
        refreshNavigation()
        stage.addActor(root)
    }

    private fun displayWinner(victoryData: VictoryData) {
        // Undecided how to display victoryTurn
        val victoryType = victoryData.victoryType
        val winningCiv = victoryData.winningCivObject
        val victory = gameInfo.ruleset.victories[victoryType]
            ?: Victory()  // This contains our default victory/defeat texts
        if (winningCiv.civID == playerCiv.civID) {
            displayWonOrLost("You have won a [$victoryType] Victory!", victory.victoryString)
            if (!music.chooseTrack(victory.name, MusicMood.Victory, EnumSet.of(MusicTrackChooserFlags.PrefixMustMatch, MusicTrackChooserFlags.SuffixMustMatch))) {
                music.chooseTrack(playerCiv.civName, listOf(MusicMood.Victory, MusicMood.Theme), EnumSet.of(MusicTrackChooserFlags.SuffixMustMatch))
            }
        } else {
            displayWonOrLost("[${winningCiv.civName}] has won a [$victoryType] Victory!", victory.defeatString)
            if (!music.chooseTrack(victory.name, MusicMood.Defeat, EnumSet.of(MusicTrackChooserFlags.PrefixMustMatch, MusicTrackChooserFlags.SuffixMustMatch))) {
                music.chooseTrack(playerCiv.civName, MusicMood.Defeat, EnumSet.of(MusicTrackChooserFlags.SuffixMustMatch))
            }
        }
        worldScreen.autoPlay.stopAutoPlay()
    }

    private fun displayWonOrLost(vararg descriptions: String) {
        descriptionLabel.setText(descriptions.joinToString("\n") { it.tr() })

        rightSideButton.setText("Singleplayer".tr())
        rightSideButton.enable()
        rightSideButton.onClick {
            val newGameSetupInfo = GameSetupInfo(gameInfo)
            newGameSetupInfo.mapParameters.reseed()
            game.pushScreen{ NewGameScreen(newGameSetupInfo) }
        }

        pickerPane.useCloseButtonAsAction()
        closeButton.setText("One more turn...!".tr())
        closeButton.onClick {
            gameInfo.oneMoreTurnMode = true
            game.popScreen()
        }
    }

    override fun show() {
        super.show()
        tabs.askForPassword(secretHashCode = 2747985)
    }

    override fun dispose() {
        tabs.selectPage(-1)  // Tells Replay page to stop its timer
        super.dispose()
    }

    override fun recreate(): BaseScreen = VictoryScreen(worldScreen, tabs.activePage, pageState)

    companion object {
        @Readonly
        fun canViewCivStats(
            gameInfo: GameInfo,
            viewingCiv: Civilization,
            viewedCiv: Civilization
        ): Boolean = gameInfo.victoryData != null
            || viewingCiv.isSpectator()
            || !gameInfo.gameParameters.hideOtherCivilizationStats
            || viewedCiv == viewingCiv
    }
}
