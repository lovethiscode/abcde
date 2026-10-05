package com.unciv.logic.multiplayer

import com.unciv.logic.event.Event
import com.unciv.logic.event.EventBus
import com.unciv.utils.Concurrency
import kotlinx.coroutines.CancellationException

enum class MultiplayerOperation { Create, Join, Turn, RoomCreate, RoomJoin, Start, TurnSubmit }

/** A completed player action, distinct from automatic preview refreshes and HTTP retries. */
class MultiplayerOperationCompleted(val operation: MultiplayerOperation, val succeeded: Boolean,
                                   val playerId: String? = null) : Event

fun reportMultiplayerOperation(operation: MultiplayerOperation, succeeded: Boolean, playerId: String? = null) {
    Concurrency.runOnGLThread { EventBus.send(MultiplayerOperationCompleted(operation, succeeded, playerId)) }
}

suspend fun <T> observeMultiplayerOperation(operation: MultiplayerOperation, playerId: String? = null, action: suspend () -> T): T {
    val result = try {
        action()
    } catch (ex: CancellationException) {
        throw ex
    } catch (ex: Exception) {
        reportMultiplayerOperation(operation, false, playerId)
        throw ex
    }
    reportMultiplayerOperation(operation, true, playerId)
    return result
}

/** One result for the whole user submission, including persistence and recovery failures. */
suspend fun observeMultiplayerTurnSubmission(enabled: Boolean, action: suspend () -> Boolean?): Boolean? {
    var result: Boolean? = null
    try {
        result = action()
        return result
    } catch (ex: CancellationException) {
        throw ex
    } catch (ex: Exception) {
        result = false
        throw ex
    } finally {
        if (enabled) result?.let { reportMultiplayerOperation(MultiplayerOperation.TurnSubmit, it) }
    }
}
