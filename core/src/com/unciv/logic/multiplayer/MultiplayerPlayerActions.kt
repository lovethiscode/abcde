package com.unciv.logic.multiplayer

import com.badlogic.gdx.utils.JsonReader
import com.badlogic.gdx.utils.JsonValue
import com.badlogic.gdx.utils.JsonWriter
import com.unciv.json.json
import com.unciv.logic.GameInfo
import com.unciv.logic.files.FileConversions
import java.security.MessageDigest
import com.unciv.Constants
import com.unciv.UncivGame
import com.unciv.logic.GameInfoPreview
import com.unciv.logic.UncivShowableException
import com.unciv.logic.automation.civilization.NextTurnAutomation
import com.unciv.logic.civilization.NotificationCategory
import com.unciv.logic.civilization.PlayerType
import com.unciv.logic.multiplayer.storage.MultiplayerAuthException
import com.unciv.logic.multiplayer.storage.MultiplayerServer
import kotlinx.coroutines.CancellationException

enum class MultiplayerPlayerAction { Resign, SkipTurn, ForceResign }

/** Used for button availability and checked again against the downloaded full save. */
fun playerActionError(
    action: MultiplayerPlayerAction,
    expected: GameInfoPreview,
    latest: GameInfoPreview,
    userId: String,
    now: Long = System.currentTimeMillis(),
): String? {
    if (latest.gameId != expected.gameId || latest.currentPlayer != expected.currentPlayer
        || latest.turns != expected.turns || latest.currentTurnStartTime != expected.currentTurnStartTime
    ) return "The game changed. Refresh it before trying this action again."
    if (userId.isBlank()) return "Only a participating player can use this action."
    val actor = latest.civilizations.firstOrNull {
        it.playerId == userId && it.playerType == PlayerType.Human && it.civName != Constants.spectator
    } ?: return "Only a participating player can use this action."
    val target = latest.civilizations.firstOrNull { it.civID == latest.currentPlayer }
        ?: return "The current player is unavailable. Refresh the game."
    if (target.playerType != PlayerType.Human || target.civName == Constants.spectator)
        return "The current player is unavailable. Refresh the game."
    if (action == MultiplayerPlayerAction.Resign) {
        if (latest.civilizations.count { it.playerType == PlayerType.Human && it.civName != Constants.spectator } < 2)
            return "The last remaining player cannot resign."
        return if (target.playerId == userId) null else "You can only resign during your own turn."
    }
    if (target.playerId == actor.playerId) return "This action is only available for another player's turn."
    val minutes = if (action == MultiplayerPlayerAction.SkipTurn)
        latest.gameParameters.minutesUntilSkipTurn else target.playerMinutesBeforeForceResign
    if (latest.currentTurnStartTime <= 0 || now <= latest.currentTurnStartTime
        || now - latest.currentTurnStartTime <= minutes.toLong() * 60_000
    ) return "This player's time limit has not expired."
    return null
}

suspend fun Multiplayer.performPlayerAction(
    game: MultiplayerGamePreview,
    expected: GameInfoPreview,
    action: MultiplayerPlayerAction,
) {
    val state = pendingTurnUploadStateFor(expected.gameId, expected.gameParameters.multiplayerServerUrl)
    if (!state.tryStartTurnProcessing()) throw UncivShowableException("A multiplayer action is already in progress.")
    try {
        if (state.requiresRecovery()) throw UncivShowableException("Resolve the pending upload before starting another action.")
        val server = serverFor(expected.gameParameters.multiplayerServerUrl)
        val latest = server.tryDownloadGame(expected.gameId)
        val userId = UncivGame.Current.settings.multiplayer.getUserId()
        val error = playerActionError(action, expected, latest.asPreview(), userId)
        if (error != null) {
            game.updatePreview(latest.asPreview())
            throw UncivShowableException(error)
        }
        state.getOrCreate(latest, playerAction = action, initiatingPlayerId = userId) {
            val pending = latest.clone().apply { setTransients() }
            val target = pending.getCurrentPlayerCivilization()
            val targetName = target.civName
            val actorName = pending.civilizations.first {
                it.playerId == userId && it.playerType == PlayerType.Human && !it.isSpectator()
            }.civName
            val text = when (action) {
                MultiplayerPlayerAction.SkipTurn -> {
                    NextTurnAutomation.automateCivMoves(target, false)
                    pending.nextTurn()
                    "[$targetName]'s turn was skipped by [$actorName]"
                }
                MultiplayerPlayerAction.Resign, MultiplayerPlayerAction.ForceResign -> {
                    target.playerType = PlayerType.AI
                    target.playerId = ""
                    target.turnsPlayedAsHuman = 0
                    target.totalTurnTimeSeconds = 0
                    pending.nextTurn()
                    if (action == MultiplayerPlayerAction.Resign)
                        "[$targetName] resigned and is now controlled by AI"
                    else "[$targetName] was forcibly resigned by [$actorName] and is now controlled by AI"
                }
            }
            for (civ in pending.civilizations)
                civ.addNotification(text, NotificationCategory.General, targetName)
            pending
        }
        completePlayerAction(game, server, state)
    } catch (ex: Exception) {
        state.ensureUnconfirmed(if (ex is MultiplayerAuthException)
            TurnUploadUnconfirmedReason.AuthenticationCancelled else TurnUploadUnconfirmedReason.UploadFailed)
        throw ex
    } finally {
        state.finishTurnProcessing()
    }
}

suspend fun Multiplayer.retryPlayerAction(game: MultiplayerGamePreview) {
    val preview = game.preview ?: throw UncivShowableException("The current player is unavailable. Refresh the game.")
    val state = pendingTurnUploadStateFor(preview.gameId, preview.gameParameters.multiplayerServerUrl)
    if (!state.tryStartTurnProcessing()) throw UncivShowableException("A multiplayer action is already in progress.")
    try {
        val pending = state.get() ?: throw UncivShowableException("Resolve the pending upload before starting another action.")
        if (pending.playerAction == null || pending.initiatingPlayerId != UncivGame.Current.settings.multiplayer.getUserId())
            throw UncivShowableException("Resolve the pending upload before starting another action.")
        completePlayerAction(game, serverFor(preview.gameParameters.multiplayerServerUrl), state)
    } catch (ex: Exception) {
        state.ensureUnconfirmed(if (ex is MultiplayerAuthException)
            TurnUploadUnconfirmedReason.AuthenticationCancelled else TurnUploadUnconfirmedReason.UploadFailed)
        throw ex
    } finally {
        state.finishTurnProcessing()
    }
}

private suspend fun Multiplayer.completePlayerAction(
    game: MultiplayerGamePreview,
    server: MultiplayerServer,
    state: PendingTurnUploadState,
) {
    val pending = state.get()!!
    val serverGame = server.tryDownloadGame(pending.pendingGame.gameId)
    val serverPreview = try {
        server.tryDownloadGamePreview(serverGame.gameId)
    } catch (ex: CancellationException) {
        throw ex
    } catch (ex: MultiplayerAuthException) {
        throw ex
    } catch (_: Exception) {
        null // A missing/invalid preview can be repaired from the full save.
    }
    val resolution = pending.resolve(serverGame, serverPreview)
    when (resolution) {
        PendingTurnUploadResolution.ServerChanged -> {
            game.updatePreview(serverGame.asPreview())
            state.clear()
            throw UncivShowableException("The game changed on the server. The pending action was discarded.")
        }
        PendingTurnUploadResolution.InvalidServerGame ->
            throw UncivShowableException("The multiplayer server returned an invalid response.")
        PendingTurnUploadResolution.Confirmed -> Unit
        PendingTurnUploadResolution.RetryUpload, PendingTurnUploadResolution.RepairPreview -> {
            val task = UncivGame.Current.beginMultiplayerUploadBackgroundTask { state.markBackgroundTaskExpired() }
            var expired = false
            try {
                if (task?.isExpired != true) withTurnUploadContinuation {
                    if (resolution == PendingTurnUploadResolution.RepairPreview)
                        server.tryUploadGamePreview(pending.pendingGame.asPreview())
                    else server.uploadGame(pending.pendingGame, withPreview = true)
                }
            } finally {
                expired = task?.isExpired == true
                if (expired) state.markBackgroundTaskExpired()
                task?.finish()
            }
            if (expired)
                throw UncivShowableException("The action is not yet confirmed. Return to the game and retry the pending action.")
        }
    }
    game.updatePreview(pending.pendingGame.asPreview())
    state.clear()
}

/** API v1 may keep a stale checksum; compare all decoded fields with stable object-key ordering.
 * Arrays retain their order. This also avoids false conflicts after HashMap deserialization. */
internal fun GameInfo.playerActionContentFingerprint(): String {
    fun canonical(value: JsonValue): String = when {
        value.isObject -> value.toList().sortedBy { it.name }.joinToString(
            prefix = "{", postfix = "}", separator = ",",
        ) { JsonWriter.OutputType.json.quoteName(it.name) + ":" + canonical(it) }
        value.isArray -> value.joinToString(prefix = "[", postfix = "]", separator = ",") { canonical(it) }
        else -> value.toJson(JsonWriter.OutputType.json)
    }
    val content = JsonReader().parse(json().toJson(this)).apply { remove("checksum") }
    return FileConversions.encode(MessageDigest.getInstance("SHA-1").digest(canonical(content).toByteArray(Charsets.UTF_8)))
}
