package com.unciv.logic.multiplayer

import com.unciv.logic.event.Event
import com.unciv.logic.event.EventBus
import com.unciv.utils.Concurrency
import kotlinx.coroutines.CancellationException

enum class MultiplayerOperation { Create, Join, Turn }

/** A completed player action, distinct from automatic preview refreshes and HTTP retries. */
class MultiplayerOperationCompleted(val operation: MultiplayerOperation, val succeeded: Boolean) : Event

fun reportMultiplayerOperation(operation: MultiplayerOperation, succeeded: Boolean) {
    Concurrency.runOnGLThread { EventBus.send(MultiplayerOperationCompleted(operation, succeeded)) }
}

suspend fun <T> observeMultiplayerOperation(operation: MultiplayerOperation, action: suspend () -> T): T {
    val result = try {
        action()
    } catch (ex: CancellationException) {
        throw ex
    } catch (ex: Exception) {
        reportMultiplayerOperation(operation, false)
        throw ex
    }
    reportMultiplayerOperation(operation, true)
    return result
}
