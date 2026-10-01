package com.unciv.logic.multiplayer.chat

import com.badlogic.gdx.Application
import com.badlogic.gdx.Gdx
import com.unciv.UncivGame
import com.unciv.logic.GameInfo
import com.unciv.logic.civilization.Civilization
import com.unciv.models.metadata.GameSettings
import com.unciv.testing.BaseTestRunner
import com.unciv.ui.screens.worldscreen.WorldScreen
import com.unciv.ui.screens.worldscreen.chat.ChatButton
import com.unciv.ui.screens.worldscreen.chat.ChatPopup
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Answers
import org.mockito.Mockito.*
import java.util.UUID

@RunWith(BaseTestRunner::class)
class ChatUnreadTest {
    private val previousApp = Gdx.app
    private val previousGame = if (UncivGame.isCurrentInitialized()) UncivGame.Current else UncivGame()
    private val server = "https://example.com"
    private lateinit var info: GameInfo
    private lateinit var chat: Chat
    private lateinit var button: ChatButton

    @Before fun setup() {
        val app = mock(Application::class.java)
        doAnswer { invocation -> invocation.getArgument<Runnable>(0).run(); null }
            .`when`(app).postRunnable(any(Runnable::class.java))
        Gdx.app = app
        ChatStore.clear()
        ChatStore.chatPopup = null
        ChatStore.hasGlobalMessage = false
        info = GameInfo().apply {
            gameId = UUID.randomUUID().toString()
            civilizations = arrayListOf(
                Civilization("Rome").apply { playerId = "local-player" },
                Civilization("Greece").apply { playerId = "other-player" },
            )
            currentPlayer = "Greece"
            gameParameters.isOnlineMultiplayer = true
            gameParameters.multiplayerServerUrl = server
        }
        button = mock(ChatButton::class.java)
        val world = mock(WorldScreen::class.java) { invocation ->
            when {
                invocation.method.name.startsWith("getChatButton") -> button
                invocation.method.name == "getGameInfo" -> info
                else -> Answers.RETURNS_DEFAULTS.answer(invocation)
            }
        }
        val game = UncivGame().apply {
            settings = GameSettings().apply { multiplayer.setUserId("local-player") }
            gameInfo = info
        }
        UncivGame::class.java.getDeclaredField("worldScreen").apply {
            isAccessible = true
            set(game, world)
        }
        UncivGame.Current = game
        chat = ChatStore.getChatByGameId(info.gameId, server)
    }

    @After fun cleanup() {
        ChatStore.chatPopup = null
        ChatStore.hasGlobalMessage = false
        ChatStore.clear()
        Gdx.app = previousApp
        UncivGame.Current = previousGame
    }

    @Test fun otherPlayersMessageNotifiesEvenDuringTheirTurn() {
        ChatStore.relayChatMessage(Response.Chat("Greece", "hello", info.gameId), server)
        ChatStore.relayChatMessage(Response.Chat("Greece", "again", info.gameId), server)
        assertEquals(2, chat.unreadCount)
        verify(button, times(2)).triggerChatIndication()
    }

    @Test fun ownMessageDoesNotNotifyDuringAnotherPlayersTurn() {
        ChatStore.relayChatMessage(Response.Chat("Rome", "hello", info.gameId), server)
        assertEquals(0, chat.unreadCount)
        verifyNoInteractions(button)
    }

    @Test fun openChatReceivesMessageWithoutUnreadNotification() {
        val popup = mock(ChatPopup::class.java)
        `when`(popup.chat).thenReturn(chat)
        ChatStore.chatPopup = popup
        ChatStore.relayChatMessage(Response.Chat("Greece", "hello", info.gameId), server)
        assertEquals(0, chat.unreadCount)
        verify(popup).addMessage("Greece", "hello", null, true)
        verifyNoInteractions(button)
    }

    @Test fun serverWelcomeDoesNotCreatePlayerMessageNotification() {
        ChatStore.relayGlobalMessage("Welcome", "Server")
        assertFalse(ChatStore.hasGlobalMessage)
        verifyNoInteractions(button)
    }
}
