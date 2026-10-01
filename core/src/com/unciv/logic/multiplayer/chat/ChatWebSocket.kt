package com.unciv.logic.multiplayer.chat

import com.unciv.UncivGame
import com.unciv.logic.multiplayer.chat.ChatWebSocket.job
import com.unciv.logic.multiplayer.chat.ChatWebSocket.start
import com.unciv.utils.Concurrency
import io.ktor.http.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit
import kotlin.time.TimeSource

// used when sending a message
@Serializable
sealed class Message {
    @Serializable
    @SerialName("chat")
    data class Chat(
        val civName: String, val message: String, val gameId: String
    ) : Message()

    @Serializable
    @SerialName("join")
    data class Join(
        val gameIds: List<String>
    ) : Message()

    @Serializable
    @SerialName("leave")
    data class Leave(
        val gameIds: List<String>
    ) : Message()
}

// used when receiving a message
@Serializable
sealed class Response {
    @Serializable
    @SerialName("chat")
    data class Chat(
        val civName: String, val message: String, val gameId: String? = null
    ) : Response()

    @Serializable
    @SerialName("joinSuccess")
    data class JoinSuccess(
        val gameIds: List<String>
    ) : Response()

    @Serializable
    @SerialName("error")
    data class Error(
        val message: String
    ) : Response()
}

class ChatRestartException : CancellationException("Chat restart requested")
class ChatStopException : CancellationException("Chat stop requested")

object ChatWebSocket {
    private const val MAX_RECONNECTION_ATTEMPTS = 100
    private val INITIAL_RECONNECT_TIME = 1.seconds
    private val MAX_RECONNECT_TIME = 64.seconds
    private val INITIAL_SESSION_WAIT_FOR_TIME = 5.seconds

    @Volatile private var isStarted = false
    @Volatile private var isPaused = false

    private var lastRetry = TimeSource.Monotonic.markNow()
    private var reconnectionAttempts = 0
    private var reconnectTime = INITIAL_RECONNECT_TIME

    private var job: Job? = null
    private var restartJob: Job? = null
    @Volatile private var session: ChatConnection? = null
    private val transport by lazy { UncivGame.Current.createChatTransport() }

    private fun resetExponentialBackoff() {
        lastRetry = TimeSource.Monotonic.markNow()

        reconnectionAttempts = 0
        reconnectTime = INITIAL_RECONNECT_TIME
    }

    private fun getChatUrl(serverUrl: String): Url = URLBuilder(serverUrl).apply {
        appendPathSegments("chat")
        protocol = if (protocol.isSecure()) URLProtocol.WSS else URLProtocol.WS
    }.build()

    /**
     * Only requests a message to be sent.
     * Does not guarantee delivery.
     * Failures are mosly ignored.
     *
     * The server will relay it back if a delivery was acknowledged and that is when we should display it.
     *
     * Also calls [start] to start the connection in case it was not done beforehand.
     */
    fun requestMessageSend(message: Message) {
        if (isPaused) return
        start()
        Concurrency.run("MultiplayerChatSendMessage") {
            withTimeoutOrNull(INITIAL_SESSION_WAIT_FOR_TIME) {
                while (session == null) {
                    delay(100.milliseconds)
                }
            }
            if (!isStarted || isPaused) return@run
            session?.runCatching {
                this.send(message)
            }
        }
    }

    private fun handleWebSocketThrowables(t: Throwable) {
        print("ChatError: ${t.message}. Reconnecting...")
        ChatStore.updatePasswordRequired(t.message?.contains("401") == true)

        if (reconnectionAttempts == 0) {
            lastRetry = TimeSource.Monotonic.markNow()
        } else {
            print(" (Last retry was ${lastRetry.elapsedNow().toString(DurationUnit.SECONDS, 2)} ago)")
            lastRetry = TimeSource.Monotonic.markNow()
        }

        println()
        restart(dueToError = true)
    }

    private suspend fun startSession() {
        var connection: ChatConnection? = null
        try {
            session?.close()
            val serverUrl = UncivGame.Current.onlineMultiplayer.multiplayerServer
                .getServerUrl().trimEnd('/')
            connection = transport.connect(
                getChatUrl(serverUrl).toString(),
                UncivGame.Current.settings.multiplayer.getAuthHeader(serverUrl),
                UncivGame.getUserAgent("Chat"),
            )

            connection.runCatching {
                if (isActive) {
                    println("ChatLog: Connected to WebSocket.")
                    ChatStore.updatePasswordRequired(false)
                    resetExponentialBackoff()
                }

                val currentServer = serverUrl
                val gameIds = UncivGame.Current.onlineMultiplayer.games.mapNotNull { savedGame ->
                    savedGame.preview?.takeIf { preview ->
                        preview.gameParameters.multiplayerServerUrl
                            ?.trimEnd('/')
                            ?.let { it == currentServer }
                            ?: true
                    }?.gameId
                }.toMutableSet()
                UncivGame.Current.worldScreen?.gameInfo?.takeIf { gameInfo ->
                    gameInfo.gameParameters.multiplayerServerUrl
                        ?.trimEnd('/')
                        ?.let { it == currentServer }
                        ?: true
                }?.gameId?.let(gameIds::add)
                this.send(Message.Join(gameIds.toList()))
                session = this

                while (this.isActive) {
                    val response = receive()
                    when (response) {
                        is Response.Chat -> ChatStore.relayChatMessage(response, serverUrl)

                        is Response.Error -> ChatStore.relayGlobalMessage(
                            "Error: [${response.message}]", "Server"
                        )

                        is Response.JoinSuccess -> Unit // TODO
                    }
                }
            }
                .onSuccess { restart() }
                .onFailure {
                    if (it is CancellationException) throw it
                    handleWebSocketThrowables(it)
                }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleWebSocketThrowables(e)
        } finally {
            if (session === connection) session = null
            withContext(NonCancellable) { connection?.close() }
        }
    }

    @Synchronized private fun start() {
        if (isPaused) return
        if (!isStarted) {
            isStarted = true
            job = Concurrency.run("MultiplayerChat") { startSession() }
        }
    }

    /**
     * Stops the socket and clears all type of chat from [ChatStore]
     */
    @Synchronized fun stop() {
        isStarted = false
        restartJob?.cancel()
        ChatStore.clear()
        job?.cancel(ChatStopException())
    }

    /** iOS suspends foreground sockets without discarding the current in-memory chat. */
    @Synchronized fun pause() {
        isPaused = true
        restartJob?.cancel()
        job?.cancel(ChatStopException())
    }

    @Synchronized fun resume() {
        isPaused = false
        if (isStarted) restart(force = true)
    }

    /**
     * By default, this gets autocancelled if the [job] is still running.
     * Force mode will cancel the previous [job] and reassign a new one.
     * This is helpfull when we need to reset [job] due to events.
     */
    @OptIn(DelicateCoroutinesApi::class)
    @Synchronized
    fun restart(dueToError: Boolean = false, force: Boolean = false) {
        if (!isStarted || isPaused) return
        if (dueToError) {
            if (++reconnectionAttempts > MAX_RECONNECTION_ATTEMPTS) {
                return
            }
        } else resetExponentialBackoff()

        if (force) println("ChatLog: A force restart seems to be requested!")

        restartJob?.cancel()
        restartJob = GlobalScope.launch {
            if (!force) {
                // exponential backoff same as described here: https://cloud.google.com/memorystore/docs/redis/exponential-backoff
                delay(Random.nextLong(1000).milliseconds + reconnectTime)
                reconnectTime = (reconnectTime * 2).coerceAtMost(MAX_RECONNECT_TIME)
                if (job?.isActive == true) return@launch
            }

            synchronized(this@ChatWebSocket) {
                if (!isStarted || isPaused) return@launch
                job?.cancel(ChatRestartException())
                job = Concurrency.run("MultiplayerChat") { startSession() }
            }
        }
    }
}
