package com.unciv.ui.screens.multiplayerscreens

/** Values are point-equivalent; callers convert once to the safe-area stage's units. */
class MultiplayerScreenLayout(val width: Float, val height: Float, val phone: Boolean) {
    val horizontalPadding = if (phone && width > height) 1f else 16f
    val topPadding = 16f
    val bottomPadding = if (phone && width > height) 3f else 12f
    val contentWidth = (width - horizontalPadding * 2).coerceAtLeast(1f)
    val twoColumns = contentWidth >= 640f
    val columnGap = 16f
    val gamesWidth = if (twoColumns) (contentWidth - columnGap) * 320f / 820f else contentWidth
    val detailsWidth = if (twoColumns) contentWidth - columnGap - gamesWidth else contentWidth
}

enum class MultiplayerGameFilter {
    All, YourTurn, Waiting;
    fun matches(model: MultiplayerGameUiModel) = when (this) {
        All -> true
        YourTurn -> model.isUsersTurn
        Waiting -> model.hasPreview && model.currentTurnDuration != null && !model.isUsersTurn
    }
}
