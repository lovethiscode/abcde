package com.unciv.app

import com.unciv.logic.multiplayer.chat.ChatConnection
import com.unciv.logic.multiplayer.chat.ChatProtocol
import com.unciv.logic.multiplayer.chat.ChatTransport
import com.unciv.logic.multiplayer.chat.Message
import com.unciv.logic.multiplayer.chat.Response
import com.unciv.logic.multiplayer.storage.MultiplayerServer
import com.unciv.utils.PlatformCapabilities
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.robovm.apple.foundation.NSMutableURLRequest
import org.robovm.apple.foundation.NSHTTPURLResponse
import org.robovm.apple.foundation.NSOperationQueue
import org.robovm.apple.foundation.NSURL
import org.robovm.apple.foundation.NSURLRequestCachePolicy
import org.robovm.apple.foundation.NSURLRequest
import org.robovm.apple.foundation.NSURLSession
import org.robovm.apple.foundation.NSURLSessionConfiguration
import org.robovm.apple.foundation.NSURLSessionWebSocketMessage
import org.robovm.apple.foundation.NSURLSessionWebSocketMessageType
import org.robovm.apple.foundation.NSError
import org.robovm.apple.foundation.NSURLSessionWebSocketTask
import org.robovm.apple.foundation.NSURLSessionTask
import org.robovm.apple.foundation.NSURLSessionWebSocketDelegateAdapter
import org.robovm.objc.block.VoidBlock1
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/** Native WebSockets avoid CIO's unsupported RoboVM JDK APIs. */
class IOSChatTransport internal constructor(
    private val pingIntervalMillis: Long = 30_000,
    private val openSocket: (String, String, String) -> IOSChatSocket,
) : ChatTransport {
    constructor() : this(openSocket = ::NativeIOSChatSocket)

    override suspend fun connect(url: String, authentication: String, userAgent: String): ChatConnection {
        require(url.startsWith("wss://")) { "iOS chat requires WSS" }
        MultiplayerServer.validateServerUrl(
            "https://" + url.removePrefix("wss://"),
            PlatformCapabilities(multiplayerServerRequiresHttps = true),
        )
        val socket = openSocket(url, authentication, userAgent)
        return Connection(socket, pingIntervalMillis).apply { open() }
    }

    private class Connection(private val socket: IOSChatSocket, private val pingIntervalMillis: Long) : ChatConnection {
        private val closed = AtomicBoolean()
        private var keepAlive: Job? = null
        override val isActive get() = !closed.get()

        suspend fun open() {
            try {
                suspendCancellableCoroutine<Unit> { continuation ->
                    continuation.invokeOnCancellation { closeSocket() }
                    if (continuation.isActive) socket.open { result ->
                        if (continuation.isActive) continuation.resumeWith(result)
                    }
                }
            } catch (exception: Exception) {
                closeSocket()
                throw exception
            }
            if (!isActive) return
            keepAlive = CoroutineScope(Dispatchers.Default).launch {
                try {
                    while (isActive) {
                        delay(pingIntervalMillis)
                        suspendCancellableCoroutine<Unit> { continuation ->
                            if (continuation.isActive && isActive) socket.ping { result ->
                                if (continuation.isActive) continuation.resumeWith(result)
                            }
                        }
                    }
                } catch (exception: CancellationException) {
                    throw exception
                } catch (_: Exception) {
                    closeSocket()
                }
            }
        }

        override suspend fun send(message: Message) {
            check(isActive) { "Chat connection is closed" }
            val text = ChatProtocol.encode(message)
            suspendCancellableCoroutine<Unit> { continuation ->
                continuation.invokeOnCancellation { closeSocket() }
                socket.send(text) { result ->
                    if (continuation.isActive) continuation.resumeWith(result)
                }
            }
        }

        override suspend fun receive(): Response {
            check(isActive) { "Chat connection is closed" }
            val text = suspendCancellableCoroutine<String> { continuation ->
                continuation.invokeOnCancellation { closeSocket() }
                socket.receive { result ->
                    if (continuation.isActive) continuation.resumeWith(result)
                }
            }
            return ChatProtocol.decode(text)
        }

        override suspend fun close() = closeSocket()

        private fun closeSocket() {
            if (closed.compareAndSet(false, true)) {
                keepAlive?.cancel()
                socket.close()
            }
        }
    }
}

internal interface IOSChatSocket {
    fun limitMessageSize(bytes: Long) {}
    fun open(completion: (Result<Unit>) -> Unit)
    fun send(message: String, completion: (Result<Unit>) -> Unit)
    fun receive(completion: (Result<String>) -> Unit)
    fun ping(completion: (Result<Unit>) -> Unit)
    fun close()
}

internal class NativeIOSChatSocket(url: String, authentication: String, userAgent: String) : IOSChatSocket {
    private var opening: ((Result<Unit>) -> Unit)? = null
    private val delegate = object : NSURLSessionWebSocketDelegateAdapter() {
        override fun didOpen(session: NSURLSession, task: NSURLSessionWebSocketTask, protocol: String?) {
            finishOpening(Result.success(Unit))
        }

        override fun didComplete(session: NSURLSession, task: NSURLSessionTask, error: NSError?) {
            val status = (task.response as? NSHTTPURLResponse)?.statusCode
            finishOpening(Result.failure(IOException("Chat handshake failed ($status): ${error?.localizedDescription}")))
        }

        override fun willPerformHTTPRedirection(
            session: NSURLSession, task: NSURLSessionTask, response: NSHTTPURLResponse,
            newRequest: NSURLRequest, completionHandler: VoidBlock1<NSURLRequest>,
        ) {
            // Never forward the server-specific Authorization header to a redirected endpoint.
            completionHandler.invoke(null)
        }
    }
    private val queue = NSOperationQueue()
    private val session = NSURLSession(NSURLSessionConfiguration.getEphemeralSessionConfiguration().apply {
        setShouldSetHTTPCookies(false)
        setWaitsForConnectivity(false)
    }, delegate, queue)
    private val task = session.newWebSocket(NSMutableURLRequest(
        NSURL(url), NSURLRequestCachePolicy.ReloadIgnoringLocalCacheData, 60.0,
    ).apply {
        setShouldHandleHTTPCookies(false)
        if (authentication.isNotEmpty()) setHTTPHeaderField("Authorization", authentication)
        setHTTPHeaderField("User-Agent", userAgent)
    })

    override fun open(completion: (Result<Unit>) -> Unit) {
        opening = completion
        task.resume()
    }
    override fun limitMessageSize(bytes: Long) { task.maximumMessageSize = bytes }

    private fun finishOpening(result: Result<Unit>) {
        val completion = opening ?: return
        opening = null
        completion(result)
    }

    override fun send(message: String, completion: (Result<Unit>) -> Unit) {
        task.sendMessage(NSURLSessionWebSocketMessage(message)) { error ->
            completion(if (error == null) Result.success(Unit)
                else Result.failure(IOException(error.localizedDescription)))
        }
    }

    override fun receive(completion: (Result<String>) -> Unit) {
        task.receiveMessage { message, error ->
            val result = when {
                error != null -> Result.failure(IOException(error.localizedDescription))
                message == null -> Result.failure(IOException("Missing chat message"))
                message.type == NSURLSessionWebSocketMessageType.String -> Result.success(message.string)
                else -> Result.success(String(message.data.bytes, Charsets.UTF_8))
            }
            completion(result)
        }
    }

    override fun ping(completion: (Result<Unit>) -> Unit) {
        task.sendPing { error ->
            completion(if (error == null) Result.success(Unit)
                else Result.failure(IOException(error.localizedDescription)))
        }
    }

    override fun close() = session.invalidateAndCancel()
}
