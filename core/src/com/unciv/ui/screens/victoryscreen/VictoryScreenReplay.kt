package com.unciv.ui.screens.victoryscreen

import com.unciv.ui.screens.overviewscreen.OverviewUi as U
import com.unciv.ui.screens.overviewscreen.OverviewUi.pt
import com.badlogic.gdx.scenes.scene2d.ui.Container
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.utils.Align
import com.badlogic.gdx.utils.Timer
import com.unciv.models.UncivSound
import com.unciv.models.translations.tr
import com.unciv.ui.components.YearTextUtil
import com.unciv.ui.components.extensions.setSize
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.fonts.Fonts
import com.unciv.ui.components.input.onClick
import com.unciv.ui.components.widgets.TabbedPager
import com.unciv.ui.components.widgets.UncivSlider
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.worldscreen.WorldScreen

class VictoryScreenReplay internal constructor(
    worldScreen: WorldScreen,
    private val state: VictoryPageState? = null,
    contentWidth: Float = 0f,
    contentHeight: Float = 0f
) : Table(BaseScreen.skin), TabbedPager.IPageExtensions {
    private val gameInfo = worldScreen.gameInfo

    private val finalTurn = gameInfo.turns
    private var replayTimer : Timer.Task? = null
    private val header = Table()

    private val yearLabel = if (state == null) "".toLabel() else U.plainLabel("", 13, U.muted)
    private val slider: UncivSlider
    private val replayMap: ReplayMap
    private val playImage = ImageGetter.getImage("OtherIcons/ForwardArrow")
    private val pauseImage = ImageGetter.getImage("OtherIcons/Pause")
    private val playPauseButton = Container(pauseImage)

    init {
        // yearLabel should be OK with 80f, Label("4000 BC").prefWidth is nearly 78f.
        // The 190f is twice (that plus space=15f). The minimum 120f should never happen, just in case.
        val firstTurn = gameInfo.historyStartTurn
        val maxSliderPercent = if (worldScreen.isPortrait()) 0.75f else 0.5f
        val sliderWidth = ((finalTurn - firstTurn) * 15f + 60f)
            .coerceAtMost(worldScreen.stage.width * maxSliderPercent)
            .coerceAtMost(worldScreen.stage.width - 190f)
            .coerceAtLeast(120f)
        slider = UncivSlider(
            firstTurn.toFloat(), finalTurn.toFloat(), 1f,
            plusMinus = state == null,
            initial = (state?.replayTurn ?: firstTurn).toFloat(),
            sound = UncivSound.Silent,
            tipType = UncivSlider.TipType.None,
            onChange = this::sliderChanged
        )
        replayMap = ReplayMap(
            gameInfo.tileMap,
            worldScreen.selectedGameView.civView.getCiv(),
            if (state == null) worldScreen.stage.width - 50 else contentWidth - pt(24f),
            if (state == null) worldScreen.stage.height - 250 else contentHeight - pt(84f)
        )

        playImage.setSize(24f)
        pauseImage.setSize(24f)
        playPauseButton.apply {
            // I decided against `align(Align.left)`: since the button is so much smaller than the year label, perfect symmetry is impossible
            setSize(26f, 26f)
            if (state == null) onClick(::togglePause)
        }
        yearLabel.setAlignment(Align.right)
        yearLabel.onClick {
            updateReplayTable(gameInfo.historyStartTurn)
            restartTimer()
        }

        if (state == null) {
            header.defaults().space(15f).fillX().padTop(15f)
            header.add(yearLabel).minWidth(80f).right()
            header.add(slider).width(sliderWidth)
            header.add(playPauseButton).minWidth(80f)
            add(replayMap).pad(10f)
        } else {
            val map = Table().apply {
                background = U.rounded(U.panel, 10f)
                add(replayMap).pad(pt(12f))
            }
            add(map).grow().minWidth(0f).minHeight(0f).row()
            val controls = Table().apply {
                val reset = U.button(flat = true) { resetTimer(); updateReplayTable(firstTurn) }.apply {
                    add(U.plainLabel("Reset", 13, U.accent)); name = "victory-replay-reset"
                }
                val step = U.button(flat = true) { resetTimer(); updateReplayTable((slider.value.toInt() + 1).coerceAtMost(finalTurn)) }.apply {
                    add(U.plainLabel("+1", 14)); name = "victory-replay-step"
                }
                val play = U.button { togglePause() }.apply {
                    add(playPauseButton).size(pt(24f)); name = "victory-replay-play"
                }
                add(reset).height(U.touch)
                add(step).size(U.touch)
                add(slider).growX().minWidth(pt(60f)).height(U.touch).padLeft(pt(8f)).padRight(pt(8f))
                add(play).size(U.touch)
            }
            add(controls).growX().minWidth(0f).padTop(pt(8f)).row()
            add(yearLabel).right().padTop(pt(4f))
            updateReplayTable(state.replayTurn ?: firstTurn)
            playPauseButton.actor = if (state.replayPlaying) pauseImage else playImage
        }
    }

    private fun togglePause() {
        if (replayTimer == null) restartTimer() else resetTimer()
    }

    private fun restartTimer() {
        state?.replayPlaying = true
        replayTimer?.cancel()
        val firstTurn = slider.value.toInt()
        replayTimer = Timer.schedule(
            object : Timer.Task() {
                private var nextTurn = firstTurn
                override fun run() {
                    updateReplayTable(nextTurn++)
                }
            }, 0.0f,
            // A game of 600 rounds will take one minute.
            0.1f,
            // End at the last turn.
            finalTurn - firstTurn
        )
        playPauseButton.actor = pauseImage
    }

    private fun resetTimer() {
        state?.replayPlaying = false
        replayTimer?.cancel()
        replayTimer = null
        playPauseButton.actor = playImage
    }

    private fun sliderChanged(value: Float) {
        resetTimer()
        updateReplayTable(value.toInt())
    }

    private fun updateReplayTable(turn: Int) {
        state?.replayTurn = turn
        val year = gameInfo.getYear(turn - finalTurn)
        yearLabel.setText(
            YearTextUtil.toYearText(
                year, gameInfo.currentPlayerCiv.isLongCountDisplay()
            ) + " / " + turn.tr() + Fonts.turn
        )
        slider.value = turn.toFloat()
        replayMap.update(turn)
        if (turn == finalTurn) resetTimer()
    }

    override fun activated(index: Int, caption: String, pager: TabbedPager) {
        if (state != null) pager.setScrollDisabled(true)
        if (state == null || state.replayPlaying) restartTimer()
    }

    override fun deactivated(index: Int, caption: String, pager: TabbedPager) {
        val wasPlaying = state?.replayPlaying
        resetTimer()
        if (wasPlaying != null) state?.replayPlaying = wasPlaying
        if (state != null) pager.setScrollDisabled(false)
    }

    override fun getFixedContent() = if (state == null) header else null
}
