package com.unciv.logic.multiplayer.chat

import com.badlogic.gdx.Application
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.utils.Base64Coder
import com.unciv.UncivGame
import com.unciv.logic.files.UncivFiles
import com.unciv.logic.UncivShowableException
import com.unciv.logic.multiplayer.Multiplayer
import com.unciv.models.metadata.GameSettings
import com.unciv.testing.BaseTestRunner
import com.unciv.ui.screens.worldscreen.chat.ChatPopup
import com.unciv.utils.PlatformCapabilities
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import java.io.IOException
import java.nio.file.Files
import java.util.UUID

@RunWith(BaseTestRunner::class)
class ChatLifecycleTest {
    @Test
    fun pauseClosesSocketResumeRejoinsAndStopCancelsReconnect() = runBlocking {
        val previousApp = Gdx.app
        val directory = Files.createTempDirectory("unciv-chat-lifecycle-")
        val opened = Channel<Connection>(Channel.UNLIMITED)
        val game = object : UncivGame() {
            override val platformCapabilities = PlatformCapabilities(secureMultiplayerServerPasswords = true)
            private val passwords = mutableMapOf<String, String>()
            var acceptsPasswordWrites = true
            override fun getMultiplayerServerPassword(serverUrl: String) = passwords[serverUrl]
            override fun setMultiplayerServerPassword(serverUrl: String, password: String): Boolean {
                if (!acceptsPasswordWrites) return false
                passwords[serverUrl] = password
                return true
            }
            override fun createChatTransport() = object : ChatTransport {
                override suspend fun connect(url: String, authentication: String, userAgent: String): ChatConnection {
                    assertEquals("wss://example.com/chat", url)
                    return Connection(authentication).also { opened.send(it) }
                }
            }
        }
        val app = Mockito.mock(Application::class.java)
        Mockito.doAnswer { invocation ->
            invocation.getArgument<Runnable>(0).run()
            null
        }.`when`(app).postRunnable(Mockito.any(Runnable::class.java))
        Gdx.app = app
        UncivGame.Current = game
        game.settings = GameSettings().apply { multiplayer.setServer("https://example.com") }
        game.files = UncivFiles(Gdx.files, directory.toString())
        UncivGame::class.java.getDeclaredField("onlineMultiplayerInstance").apply {
            isAccessible = true
            set(game, Multiplayer())
        }
        try {
            ChatWebSocket.resume()
            val gameId = UUID.randomUUID()
            val chat = ChatStore.getChatByGameId(gameId, "https://example.com")
            chat.addMessage("Rome", "in-memory message")
            ChatWebSocket.requestMessageSend(Message.Join(listOf(gameId.toString())))
            val first = withTimeout(3_000) { opened.receive() }
            assertEquals(Message.Join(emptyList()), withTimeout(3_000) { first.sent.receive() })
            assertEquals(Message.Join(listOf(gameId.toString())), withTimeout(3_000) { first.sent.receive() })

            ChatWebSocket.pause()
            withTimeout(3_000) { first.closed.await() }
            delay(1_200)
            assertTrue(opened.tryReceive().isFailure)
            assertSame(chat, ChatStore.getChatByGameId(gameId, "https://example.com"))
            assertEquals(2, chat.length)

            ChatWebSocket.resume()
            val resumed = withTimeout(3_000) { opened.receive() }
            assertEquals(Message.Join(emptyList()), withTimeout(3_000) { resumed.sent.receive() })
            val popup = Mockito.mock(ChatPopup::class.java)
            ChatStore.chatPopup = popup
            resumed.incoming.send(Result.failure(IOException("Unauthorized: 401")))
            withTimeout(3_000) { resumed.closed.await() }
            assertTrue(ChatStore.passwordRequired)
            Mockito.verify(popup).refreshPasswordReminder()
            // First password setup must reconnect immediately, even with a canonicalized URL.
            game.settings.multiplayer.setPassword("https://example.com/", "first-password")
            val reconnected = withTimeout(900) { opened.receive() }
            assertEquals("Basic ${Base64Coder.encodeString(":first-password")}", reconnected.authentication)
            assertEquals(Message.Join(emptyList()), withTimeout(3_000) { reconnected.sent.receive() })
            assertFalse(ChatStore.passwordRequired)
            Mockito.verify(popup, Mockito.times(2)).refreshPasswordReminder()
            Mockito.verify(popup, Mockito.never()).addMessage(
                Mockito.anyString(), Mockito.anyString(), Mockito.anyString(), Mockito.anyBoolean()
            )
            ChatStore.chatPopup = null
            val globalMessages = mutableListOf<String>()
            ChatStore.pollGlobalMessages { _, message -> globalMessages.add(message) }
            assertTrue(globalMessages.isEmpty())
            assertEquals(2, chat.length)

            game.settings.multiplayer.setPassword("https://other.example", "other-password")
            assertTrue(opened.tryReceive().isFailure)
            assertFalse(reconnected.closed.isCompleted)

            game.acceptsPasswordWrites = false
            assertThrows(UncivShowableException::class.java) {
                game.settings.multiplayer.setCurrentServerPassword("rejected-password")
            }
            assertEquals("first-password", game.settings.multiplayer.getCurrentServerPassword())
            assertFalse(reconnected.closed.isCompleted)
            game.acceptsPasswordWrites = true

            game.settings.multiplayer.setCurrentServerPassword("changed-password")
            val updated = withTimeout(900) { opened.receive() }
            withTimeout(3_000) { reconnected.closed.await() }
            assertEquals("Basic ${Base64Coder.encodeString(":changed-password")}", updated.authentication)
            assertEquals(Message.Join(emptyList()), withTimeout(3_000) { updated.sent.receive() })
            assertSame(chat, ChatStore.getChatByGameId(gameId, "https://example.com"))
            assertEquals(2, chat.length)

            updated.incoming.send(Result.failure(IOException("connection lost")))
            withTimeout(3_000) { updated.closed.await() }
            ChatWebSocket.stop()
            delay(2_100)
            assertTrue(opened.tryReceive().isFailure)
        } finally {
            ChatStore.chatPopup = null
            ChatWebSocket.stop()
            ChatWebSocket.resume()
            Gdx.app = previousApp
            UncivGame.Current = UncivGame()
            directory.toFile().deleteRecursively()
        }
    }

    private class Connection(val authentication: String) : ChatConnection {
        val sent = Channel<Message>(Channel.UNLIMITED)
        val incoming = Channel<Result<Response>>(Channel.UNLIMITED)
        val closed = CompletableDeferred<Unit>()
        override val isActive get() = !closed.isCompleted
        override suspend fun send(message: Message) { sent.send(message) }
        override suspend fun receive(): Response = incoming.receive().getOrThrow()
        override suspend fun close() { closed.complete(Unit) }
    }
}
