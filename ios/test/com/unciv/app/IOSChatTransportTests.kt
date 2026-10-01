package com.unciv.app

import com.unciv.logic.multiplayer.chat.Message
import com.unciv.logic.multiplayer.chat.Response
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class IOSChatTransportTests {
    @Test
    fun usesExistingAuthenticationAndV1MessageFormat() = runBlocking {
        val socket = FakeSocket()
        var request: List<String>? = null
        val transport = IOSChatTransport { url, authentication, userAgent ->
            request = listOf(url, authentication, userAgent)
            socket
        }
        val connection = transport.connect("wss://example.com/chat", "Basic test", "Unciv/Chat")
        assertEquals(listOf("wss://example.com/chat", "Basic test", "Unciv/Chat"), request)
        connection.send(Message.Join(listOf("game-id")))
        connection.send(Message.Chat("Rome", "你好 🌍", "game-id"))
        connection.send(Message.Leave(listOf("game-id")))
        assertEquals(listOf(
            """{"type":"join","gameIds":["game-id"]}""",
            """{"type":"chat","civName":"Rome","message":"你好 🌍","gameId":"game-id"}""",
            """{"type":"leave","gameIds":["game-id"]}""",
        ), socket.sent)
        val incoming = async { connection.receive() }
        withTimeout(2_000) { socket.receivers.receive() }.invoke(Result.success(
            """{"type":"chat","civName":"Greece","message":"收到 🌍","gameId":"game-id"}"""
        ))
        assertEquals(Response.Chat("Greece", "收到 🌍", "game-id"), incoming.await())
        connection.close()
    }

    @Test
    fun cancellationClosesNativeSocketAndIgnoresLateCallback() = runBlocking {
        val socket = FakeSocket()
        val connection = IOSChatTransport { _, _, _ -> socket }
            .connect("wss://example.com/chat", "Basic test", "Unciv/Chat")
        val incoming = async { connection.receive() }
        val callback = withTimeout(2_000) { socket.receivers.receive() }
        incoming.cancelAndJoin()
        assertFalse(connection.isActive)
        assertEquals(1, socket.closeCount)
        callback(Result.failure(IOException("cancelled")))
        connection.close()
        assertEquals(1, socket.closeCount)
    }

    @Test
    fun propagatesReceiveAndSendFailures() = runBlocking {
        val socket = FakeSocket()
        val connection = IOSChatTransport { _, _, _ -> socket }
            .connect("wss://example.com/chat", "Basic test", "Unciv/Chat")
        val incoming = async { runCatching { connection.receive() } }
        withTimeout(2_000) { socket.receivers.receive() }
            .invoke(Result.failure(IOException("connection failed")))
        assertEquals("connection failed", incoming.await().exceptionOrNull()?.message)
        socket.sendFailure = IOException("send failed")
        val sent = runCatching { connection.send(Message.Join(emptyList())) }
        assertEquals("send failed", sent.exceptionOrNull()?.message)
        connection.close()
    }

    @Test
    fun failedHandshakeClosesSocketWithoutReturningAConnection() = runBlocking {
        val socket = FakeSocket().apply { openFailure = IOException("401 Unauthorized") }
        val result = runCatching {
            IOSChatTransport { _, _, _ -> socket }
                .connect("wss://example.com/chat", "Basic test", "Unciv/Chat")
        }
        assertEquals("401 Unauthorized", result.exceptionOrNull()?.message)
        assertEquals(1, socket.closeCount)
        assertTrue(socket.sent.isEmpty())
    }

    @Test
    fun cancellingHandshakeClosesSocketAndIgnoresLateOpen() = runBlocking {
        val socket = FakeSocket().apply { delayOpen = true }
        val opening = async {
            IOSChatTransport { _, _, _ -> socket }
                .connect("wss://example.com/chat", "Basic test", "Unciv/Chat")
        }
        val callback = withTimeout(2_000) { socket.openers.receive() }
        opening.cancelAndJoin()
        callback(Result.success(Unit))
        assertEquals(1, socket.closeCount)
    }

    @Test
    fun rejectsInsecureAndLocalServersBeforeOpeningSocket() = runBlocking {
        var opened = false
        val transport = IOSChatTransport { _, _, _ -> opened = true; FakeSocket() }
        for (url in listOf("ws://example.com/chat", "wss://localhost/chat", "wss://127.0.0.1/chat")) {
            assertTrue(runCatching { transport.connect(url, "Basic test", "Unciv/Chat") }.isFailure)
        }
        assertFalse(opened)
    }

    @Test
    fun sendsKeepAliveWithoutChatMessagesAndStopsWhenClosed() = runBlocking {
        val socket = FakeSocket()
        val connection = IOSChatTransport(30) { _, _, _ -> socket }
            .connect("wss://example.com/chat", "Basic test", "Unciv/Chat")
        repeat(2) { withTimeout(2_000) { socket.pings.receive() } }
        assertTrue(socket.sent.isEmpty())
        connection.close()
        while (socket.pings.tryReceive().isSuccess) { }
        delay(100)
        assertTrue(socket.pings.tryReceive().isFailure)
        assertEquals(1, socket.closeCount)
    }

    @Test
    fun failedKeepAliveClosesConnection() = runBlocking {
        val socket = FakeSocket().apply { pingFailure = IOException("ping failed") }
        val connection = IOSChatTransport(30) { _, _, _ -> socket }
            .connect("wss://example.com/chat", "Basic test", "Unciv/Chat")
        withTimeout(2_000) { while (connection.isActive) delay(10) }
        assertEquals(1, socket.closeCount)
        connection.close()
        assertEquals(1, socket.closeCount)
    }

    private class FakeSocket : IOSChatSocket {
        val sent = ArrayList<String>()
        val pings = Channel<Unit>(Channel.UNLIMITED)
        var pingFailure: IOException? = null
        val receivers = Channel<(Result<String>) -> Unit>(Channel.UNLIMITED)
        val openers = Channel<(Result<Unit>) -> Unit>(Channel.UNLIMITED)
        var closeCount = 0
        var sendFailure: IOException? = null
        var openFailure: IOException? = null
        var delayOpen = false

        override fun open(completion: (Result<Unit>) -> Unit) {
            if (delayOpen) openers.trySend(completion).getOrThrow()
            else completion(openFailure?.let { Result.failure(it) } ?: Result.success(Unit))
        }

        override fun send(message: String, completion: (Result<Unit>) -> Unit) {
            sent.add(message)
            completion(sendFailure?.let { Result.failure(it) } ?: Result.success(Unit))
        }

        override fun receive(completion: (Result<String>) -> Unit) {
            receivers.trySend(completion).getOrThrow()
        }

        override fun ping(completion: (Result<Unit>) -> Unit) {
            pings.trySend(Unit)
            completion(pingFailure?.let { Result.failure(it) } ?: Result.success(Unit))
        }

        override fun close() { closeCount++ }
    }
}
