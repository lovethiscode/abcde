package com.unciv.logic.multiplayer.lobby

import com.badlogic.gdx.Gdx
import com.unciv.UncivGame
import com.unciv.json.json
import com.unciv.logic.UncivShowableException
import com.unciv.logic.civilization.PlayerType
import com.unciv.logic.multiplayer.lobby.protocol.*
import com.unciv.logic.multiplayer.storage.MultiplayerServer
import com.unciv.models.metadata.GameSetupInfo
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class LobbyConnectionException(val code: String, message: String) : UncivShowableException(message) {
    val terminal get() = code in setOf("PROTOCOL_MISMATCH", "SERVER_MISMATCH", "RULES_MISMATCH",
        "ROOM_CLOSED", "ROOM_NOT_FOUND", "MEMBERSHIP_EXPIRED", "JOIN_RESULT_UNKNOWN", "CONNECTION_REPLACED")
}

/** Room IO has no HTTP implementation or fallback. Receive and heartbeat continue during uploads. */
class LobbyClient(val roomApiUrl: String, private val transport: LobbyTransport = UncivGame.Current.createLobbyTransport(),
                  private val requestTimeoutMillis: Long = 10_000L,
                  private val heartbeatMillis: Long = LobbyProtocol.HEARTBEAT_MILLIS) {
    companion object {
        fun rulesHash() = LobbyProtocol.fingerprint { path -> Gdx.files.internal(path).takeIf { it.exists() }?.readBytes() }
        fun socketUrl(apiUrl: String): String {
            val url = MultiplayerServer.validateServerUrl(apiUrl, UncivGame.Current.platformCapabilities)
            return when {
                url.startsWith("https://") -> "wss://" + url.removePrefix("https://") + "/ws"
                else -> "ws://" + url.removePrefix("http://") + "/ws"
            }
        }
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val sendMutex = Mutex()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<LobbyMessage>>()
    @Volatile private var connection: LobbyConnection? = null
    private var reader: Job? = null
    private var heartbeat: Job? = null
    private var watcher: Job? = null
    private var failure = CompletableDeferred<Throwable>()
    private var session: LobbySession? = null
    private var latestVersion = -1L
    var onRoom: (LobbyRoom) -> Unit = {}
    var onConnection: (Boolean, String) -> Unit = { _, _ -> }
    val isConnected get() = connection?.isActive == true && reader?.isActive == true

    suspend fun open(current: LobbySession, join: Boolean = false): LobbyRoom {
        disconnect()
        session = current
        latestVersion = -1L
        if (current.playerId.isEmpty()) current.playerId = UncivGame.Current.settings.multiplayer.getUserId()
        current.saveIfCurrent()
        failure = CompletableDeferred()
        val socket = withTimeout(requestTimeoutMillis) { transport.connect(socketUrl(roomApiUrl)) }
        try {
            val hello = withTimeout(requestTimeoutMillis) { LobbyProtocol.decode(socket.receive()) }
            if (hello.type != "hello" || hello.protocolVersion != LobbyProtocol.VERSION)
                throw LobbyConnectionException("PROTOCOL_MISMATCH","The room connection version does not match. Please try again later.")
            if (hello.gameServerUrl != current.gameServerUrl)
                throw LobbyConnectionException("SERVER_MISMATCH","The invitation game server differs from this room.")
            if (hello.rulesHash != rulesHash())
                throw LobbyConnectionException("RULES_MISMATCH","The room rules differ from this app. Update the app or server.")
            connection = socket
            reader = scope.launch {
                try {
                    while (isActive) {
                        val message = LobbyProtocol.decode(socket.receive())
                        when (message.type) {
                            "check" -> send(LobbyMessage().apply { type = "checkReply"; gameId = message.gameId; checkId = message.checkId })
                            "result", "pong" -> pending[message.requestId]?.complete(message)
                            "snapshot" -> message.room?.let(::accept)
                            "terminated" -> {
                                message.room?.let(::accept)
                                throw LobbyConnectionException(message.code,message.message)
                            }
                            else -> throw LobbyConnectionException("PROTOCOL_MISMATCH","Invalid room request")
                        }
                    }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { fail(e) }
            }
            val room = resolve(current,join)
            accept(room)
            if (current.occupancy.isNotEmpty() && room.state != "CLOSED") heartbeat = scope.launch {
                try {
                    while (isActive) {
                        delay(heartbeatMillis)
                        request(current.gameId,"heartbeat",identity())
                    }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { fail(e) }
            }
            return room
        } catch (e: Exception) { socket.close(); disconnect(); throw e }
    }
    private fun identity() = LobbyCommand().apply {
        val current = checkNotNull(session)
        playerId = current.playerId; occupancy = current.occupancy
        gameServerUrl = current.gameServerUrl; rulesHash = rulesHash()
    }
    private suspend fun resolve(current: LobbySession, join: Boolean): LobbyRoom {
        if (current.pendingAction == "join") return command(current.gameId,"recoverJoin",identity().apply { originalRequestId = current.pendingRequestId })
        if (current.pendingAction == "create") {
            val c = LobbyProtocol.json().fromJson(LobbyCommand::class.java,current.pendingCommand)
            return request("","create",c,current.pendingRequestId).room!!
        }
        if (current.pendingAction == "leave") {
            try {
                command(current.gameId,"resume",identity())
                return command(current.gameId,"leave",LobbyProtocol.json().fromJson(LobbyCommand::class.java,current.pendingCommand))
            }
            catch (e: LobbyConnectionException) {
                if (e.code in setOf("MEMBERSHIP_EXPIRED","ROOM_NOT_FOUND","ROOM_CLOSED")) current.clearIfCurrent()
                throw e
            }
        }
        if (current.gameId.isEmpty()) return create(json().fromJson(GameSetupInfo::class.java,current.setup),current.createKey)
        if (join && current.occupancy.isEmpty()) return command(current.gameId,"join",identity())
        return command(current.gameId,"resume",identity())
    }
    fun watch(current: LobbySession, join: Boolean = false) {
        if (watcher?.isActive == true) return
        val previous = watcher
        watcher = scope.launch {
            previous?.join()
            var attempt = 0
            while (isActive) {
                try {
                    onConnection(false,if (attempt == 0) "Connecting to room..." else "Connection failed. Retrying...")
                    val room = open(current,join)
                    attempt = 0
                    if (room.state == "CLOSED" || current.occupancy.isEmpty()) break
                    onConnection(true,"")
                    throw failure.await()
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    onConnection(false,if (e is LobbyConnectionException) e.message ?: "Room request failed. Please retry." else "Connection failed. Retrying...")
                    if (e is LobbyConnectionException && e.code != "RETRY") {
                        if (e.code in setOf("MEMBERSHIP_EXPIRED","ROOM_NOT_FOUND","ROOM_CLOSED")) current.clearIfCurrent()
                        break
                    }
                } finally { withContext(NonCancellable) { disconnect() } }
                val base = listOf(1_000L,2_000L,4_000L,8_000L,15_000L)[minOf(attempt++,4)]
                delay(base + (Math.random() * base * 0.2).toLong())
            }
        }
    }
    private fun fail(error: Throwable) {
        failure.complete(error)
        pending.values.forEach { it.completeExceptionally(error) }
    }
    private fun accept(room: LobbyRoom) {
        val current = session ?: return
        if (room.gameServerUrl != current.gameServerUrl || (current.gameId.isNotEmpty() && current.gameId != room.gameId)) return
        if (room.version < latestVersion) return
        latestVersion = room.version
        current.confirmed(room)
        onRoom(room)
    }
    private suspend fun send(message: LobbyMessage) = sendMutex.withLock {
        val socket = connection ?: throw IOException("Connection failed. Retrying...")
        socket.send(LobbyProtocol.encode(message))
    }
    private suspend fun request(gameId: String, action: String, c: LobbyCommand,
                                requestId: String = UUID.randomUUID().toString()): LobbyMessage {
        val completion = CompletableDeferred<LobbyMessage>()
        check(pending.putIfAbsent(requestId,completion) == null)
        try {
            if (failure.isCompleted) throw failure.await()
            val response = withTimeout(if (action == "complete") 60_000L else requestTimeoutMillis) {
                send(LobbyMessage().apply { type = "command"; this.gameId = gameId; this.action = action; this.requestId = requestId; command = c })
                completion.await()
            }
            if (response.code.isNotEmpty()) {
                val current = session
                if (response.code !in setOf("RETRY","JOIN_RESULT_UNKNOWN")) {
                    current?.rejectPending(if (action == "recoverJoin") c.originalRequestId else requestId)
                }
                val error = LobbyConnectionException(response.code,response.message)
                if (error.terminal || error.code == "RETRY") fail(error)
                throw error
            }
            response.room?.let(::accept)
            return response
        } catch (e: TimeoutCancellationException) {
            val error = IOException("Connection failed. Retrying...",e)
            fail(error)
            throw error
        } finally { pending.remove(requestId,completion) }
    }
    suspend fun create(setup: GameSetupInfo, key: String): LobbyRoom {
        val current = checkNotNull(session)
        val c = identity().apply {
            createKey = key; baseRuleset = setup.gameParameters.baseRuleset
            this.setup = json().toJson(setup)
            seats = ArrayList(setup.gameParameters.players.map { p -> LobbySeat().apply {
                human = p.playerType == PlayerType.Human; civilization = p.chosenCiv
            } })
        }
        val id = UUID.randomUUID().toString()
        current.pending("create",c,id)
        return request("","create",c,id).room!!
    }
    suspend fun command(gameId: String, action: String, c: LobbyCommand): LobbyRoom {
        val verb = action.removePrefix("start/")
        val id = UUID.randomUUID().toString()
        if (verb in setOf("join","leave")) session?.pending(verb,c,id)
        return request(gameId,verb,c,id).room ?: throw IOException("Room request failed. Please retry.")
    }
    fun stop() {
        val watching = watcher
        if (watching != null) watching.cancel() else scope.launch { disconnect() }
    }
    suspend fun close() { watcher?.cancelAndJoin(); watcher = null; disconnect() }
    private suspend fun disconnect() {
        val socket = connection; connection = null
        heartbeat?.cancelAndJoin(); heartbeat = null
        reader?.cancelAndJoin(); reader = null
        pending.values.forEach { it.completeExceptionally(IOException("Connection failed. Retrying...")) }
        socket?.close()
    }
}
