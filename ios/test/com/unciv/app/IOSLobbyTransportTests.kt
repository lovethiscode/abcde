package com.unciv.app

import com.unciv.logic.multiplayer.lobby.protocol.LobbyProtocol
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.junit.Assert.*
import org.junit.Test

class IOSLobbyTransportTests {
    private class Socket : IOSChatSocket {
        val receivers = Channel<(Result<String>) -> Unit>(Channel.UNLIMITED)
        val openers = Channel<(Result<Unit>) -> Unit>(Channel.UNLIMITED)
        var deferredOpen = false
        var closes = 0
        var limit = 0L
        val sent = arrayListOf<String>()
        override fun limitMessageSize(bytes: Long) { limit = bytes }
        override fun open(completion: (Result<Unit>) -> Unit) {
            if (deferredOpen) openers.trySend(completion) else completion(Result.success(Unit))
        }
        override fun send(message: String, completion: (Result<Unit>) -> Unit) { sent += message; completion(Result.success(Unit)) }
        override fun receive(completion: (Result<String>) -> Unit) { receivers.trySend(completion) }
        override fun ping(completion: (Result<Unit>) -> Unit) { fail("Room transport must use member heartbeats") }
        override fun close() { closes++ }
    }
    @Test fun nativeRoomSocketUsesNoChatAuthenticationAndPreservesUnicode() = runBlocking {
        val socket = Socket()
        val connection = IOSLobbyTransport { url, auth, agent ->
            assertEquals("wss://shuati.uk/unciv-lobby/ws",url); assertEquals("",auth); assertEquals("UncivLobby/1",agent); socket
        }.connect("wss://shuati.uk/unciv-lobby/ws")
        assertEquals(LobbyProtocol.MAX_MESSAGE_BYTES.toLong(),socket.limit)
        connection.send("{\"文明\":\"中华 🌍\"}")
        assertEquals("{\"文明\":\"中华 🌍\"}",socket.sent.single())
        val receive = async { connection.receive() }
        socket.receivers.receive().invoke(Result.success("中文 🌍"))
        assertEquals("中文 🌍",receive.await())
        connection.close(); connection.close(); assertEquals(1,socket.closes)
    }
    @Test fun cancelledReceiveClosesSocketAndIgnoresRepeatedLateCallback() = runBlocking {
        val socket = Socket()
        val connection = IOSLobbyTransport { _, _, _ -> socket }.connect("wss://example.com/ws")
        val receive = async { connection.receive() }
        val reply = socket.receivers.receive()
        receive.cancelAndJoin()
        repeat(2) { reply(Result.success("late")) }
        assertFalse(connection.isActive); assertEquals(1,socket.closes)
    }
    @Test fun cancelledHandshakeClosesSocketBeforeAnyCommand() = runBlocking {
        val socket = Socket().apply { deferredOpen = true }
        val opening = async { IOSLobbyTransport { _, _, _ -> socket }.connect("wss://example.com/ws") }
        val reply = socket.openers.receive()
        opening.cancelAndJoin(); reply(Result.success(Unit))
        assertEquals(1,socket.closes); assertTrue(socket.sent.isEmpty())
    }
    @Test fun insecureEndpointsAndOversizedUtf8MessagesAreRejected() = runBlocking {
        var opened = 0
        val socket = Socket()
        val transport = IOSLobbyTransport { _, _, _ -> opened++; socket }
        for (url in listOf("ws://example.com/ws","wss://localhost/ws","wss://127.0.0.1/ws"))
            assertTrue(runCatching { transport.connect(url) }.isFailure)
        assertEquals(0,opened)
        val connection = transport.connect("wss://example.com/ws")
        assertTrue(runCatching { connection.send("中".repeat(LobbyProtocol.MAX_MESSAGE_BYTES/2)) }.isFailure)
        assertTrue(socket.sent.isEmpty()); connection.close()
    }
}
