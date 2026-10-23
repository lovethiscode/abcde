package com.unciv.ui.screens.multiplayerscreens

import com.unciv.ui.components.fonts.TextRole
import com.unciv.models.skins.ExpeditionBlueTheme
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.utils.Align
import com.unciv.UncivGame
import com.unciv.logic.GameInfoPreview
import com.unciv.logic.event.EventBus
import com.unciv.logic.multiplayer.HasMultiplayerGameName
import com.unciv.logic.multiplayer.MultiplayerGameNameChanged
import com.unciv.logic.multiplayer.MultiplayerGamePreview
import com.unciv.logic.multiplayer.MultiplayerGameUpdateEnded
import com.unciv.logic.multiplayer.MultiplayerGameUpdateFailed
import com.unciv.logic.multiplayer.MultiplayerGameUpdateStarted
import com.unciv.logic.multiplayer.MultiplayerGameUpdateSucceeded
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.darken
import com.unciv.ui.components.extensions.formatShort
import com.unciv.ui.components.extensions.setFontColor
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.input.onClick
import com.unciv.ui.components.widgets.UiNavigation
import com.unciv.ui.screens.basescreen.BaseScreen

internal class GameList(
    private val onSelected: (String) -> Unit,
    private val appearance: MultiplayerScreenStyle = MultiplayerScreenStyle(1f),
) : Table() {
    private val gameDisplays = mutableMapOf<String, GameDisplay>()
    private val events = EventBus.EventReceiver()
    private var selectedName: String? = null
    var filter = MultiplayerGameFilter.All
        private set
    val refreshingNames = mutableSetOf<String>()
    var onContentChanged: (() -> Unit)? = null

    fun models() = UncivGame.Current.onlineMultiplayer.games.map { model(it) }
    private fun model(game: MultiplayerGamePreview) = buildMultiplayerGameUiModel(
        game.name, game.preview, game.error != null, game.getLastUpdate(),
        UncivGame.Current.settings.multiplayer.getUserId(), game.name in refreshingNames,
    )
    fun visibleNames() = models().filter(filter::matches)
        .sortedWith(compareByDescending<MultiplayerGameUiModel> { it.isUsersTurn }.thenBy { it.name })
        .map { it.name }
    fun setFilter(value: MultiplayerGameFilter) { filter = value; update() }


    init {
        top()
        events.receive(MultiplayerGameNameChanged::class) { update() }
        update()
    }

    fun update() {
        gameDisplays.values.forEach(GameDisplay::dispose)
        gameDisplays.clear()
        clearChildren()

        val userId = UncivGame.Current.settings.multiplayer.getUserId()
        val games = UncivGame.Current.onlineMultiplayer.games.sortedWith(
            compareByDescending<MultiplayerGamePreview> {
                buildMultiplayerGameUiModel(
                    it.name,
                    it.preview,
                    it.error != null,
                    it.getLastUpdate(),
                    userId,
                ).isUsersTurn
            }.thenBy { it.name }
        )
        for (game in games.filter { filter.matches(model(it)) }) {
            val gameDisplay = GameDisplay(game, userId, onSelected, appearance, game.name in refreshingNames)
            gameDisplays[game.name] = gameDisplay
            add(gameDisplay).growX().minWidth(0f).minHeight(appearance.px(if (UiNavigation.isIos) 52f else 62f))
                .padBottom(appearance.px(if (UiNavigation.isIos) 2f else 4f)).row()
        }
        if (gameDisplays.isEmpty()) {
            val emptyText = if (games.isEmpty()) "No multiplayer games yet" else "No games in this filter"
            add(emptyText.toLabel(appearance.muted, fontSize = appearance.font(TextRole.Supporting)).apply { wrap = true })
                .growX().minWidth(0f).pad(appearance.px(16f))
        }
        select(selectedName)
        onContentChanged?.invoke()
    }

    fun select(name: String?) {
        selectedName = name
        gameDisplays.forEach { (gameName, display) ->
            display.setSelected(gameName == name)
        }
    }

    fun dispose() {
        gameDisplays.values.forEach(GameDisplay::dispose)
        events.stopReceiving()
    }
}

private class GameDisplay(
    private val multiplayerGame: MultiplayerGamePreview,
    private val userId: String,
    private val onSelected: (String) -> Unit,
    private val appearance: MultiplayerScreenStyle,
    refreshing: Boolean,
) : Table() {
    private var preview: GameInfoPreview? = multiplayerGame.preview
    private var hasError = multiplayerGame.error != null
    private var isRefreshing = refreshing

    private val nameLabel = "".toLabel(fontSize = appearance.font(TextRole.Control)).apply { setEllipsis(true) }
    private val civilizationLabel = "".toLabel(fontSize = appearance.font(TextRole.Supporting), alignment = Align.center)
    private val civilizationBadge = Table().apply {
        add(appearance.image(MultiplayerIcon.Temple, appearance.gold)).size(appearance.px(21f))
    }
    private val metadataLabel = "".toLabel(appearance.muted, fontSize = appearance.font(TextRole.Metadata)).apply {
        setEllipsis(true)
    }
    private val statusLabel = "".toLabel(fontSize = appearance.font(TextRole.Supporting), alignment = Align.center).apply {
        setEllipsis(true)
    }
    private val statusBadge = Table()
    private val events = EventBus.EventReceiver()

    init {
        name = "multiplayer-game-${multiplayerGame.name}"
        touchable = Touchable.enabled
        pad(appearance.px(if (UiNavigation.isIos) 6f else 8f))
        nameLabel.setFontColor(appearance.text)

        val copy = Table().apply {
            add(nameLabel).growX().minWidth(0f).left().row()
            add(metadataLabel).growX().minWidth(0f).left().padTop(appearance.px(if (UiNavigation.isIos) 2f else 5f))
        }
        statusBadge.add(statusLabel).minWidth(0f).maxWidth(appearance.px(88f)).pad(appearance.px(4f), appearance.px(7f), appearance.px(4f), appearance.px(7f))
        add(civilizationBadge).size(appearance.px(36f)).padRight(appearance.px(10f))
        add(copy).growX().minWidth(0f).left()
        add(statusBadge).minWidth(0f).right().padLeft(appearance.px(8f))

        onClick { onSelected(multiplayerGame.name) }

        val isOurGame: (HasMultiplayerGameName) -> Boolean = { it.name == multiplayerGame.name }
        events.receive(MultiplayerGameUpdateStarted::class, isOurGame) {
            isRefreshing = true
            updateContent()
        }
        events.receive(MultiplayerGameUpdateEnded::class, isOurGame) {
            isRefreshing = false
            updateContent()
        }
        events.receive(MultiplayerGameUpdateSucceeded::class, isOurGame) {
            preview = it.preview
            hasError = false
            updateContent()
        }
        events.receive(MultiplayerGameUpdateFailed::class, isOurGame) {
            hasError = true
            updateContent()
        }

        updateContent()
        setSelected(false)
    }

    fun setSelected(selected: Boolean) {
        background = if (selected) appearance.surface(appearance.selected, 8f) else null
    }

    private fun updateContent() {
        val model = buildMultiplayerGameUiModel(
            multiplayerGame.name,
            preview,
            hasError,
            multiplayerGame.getLastUpdate(),
            userId,
            isRefreshing,
        )
        nameLabel.setText(model.name)
        civilizationLabel.setText(
            model.currentPlayer?.let { "{$it}".tr().take(1) } ?: "?"
        )

        val turn = model.turn?.let { "Turn [$it]".tr() } ?: "Turn unavailable".tr()
        val ownCivilization = model.ownCivilization?.let { "{$it}".tr(hideIcons = true) } ?: "Unknown".tr()
        val updated = "Updated [${model.updatedAgo.formatShort()}] ago".tr()
        metadataLabel.setText("$turn  ·  $ownCivilization")

        val (statusText, statusColor) = when (model.status) {
            MultiplayerGameUiStatus.Refreshing -> "Refreshing game" to appearance.warning
            MultiplayerGameUiStatus.YourTurn -> "Your turn to play" to appearance.positive
            MultiplayerGameUiStatus.WaitingForOpponent -> "Waiting for another player" to appearance.muted
            MultiplayerGameUiStatus.RefreshFailed -> "Refresh failed" to appearance.danger
            MultiplayerGameUiStatus.Unavailable -> "Unavailable" to appearance.muted
        }
        statusLabel.setText(statusText.tr())
        getCell(statusBadge).width(statusLabel.prefWidth.coerceAtMost(appearance.px(88f)) + appearance.px(14f))
        statusLabel.setFontColor(statusColor)
        statusBadge.background = appearance.surface(when (model.status) {
            MultiplayerGameUiStatus.YourTurn -> appearance.positiveSurface
            MultiplayerGameUiStatus.Refreshing -> appearance.warningSurface
            MultiplayerGameUiStatus.RefreshFailed -> appearance.dangerSurface
            else -> appearance.control
        }, 6f)
        civilizationBadge.background = appearance.surface(appearance.control, 8f)
    }

    fun dispose() {
        events.stopReceiving()
    }

}
