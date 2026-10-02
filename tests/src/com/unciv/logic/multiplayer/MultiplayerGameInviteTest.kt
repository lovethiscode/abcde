package com.unciv.logic.multiplayer

import com.badlogic.gdx.Application
import com.badlogic.gdx.Gdx
import com.unciv.UncivGame
import com.unciv.logic.IdChecker
import com.unciv.logic.UncivShowableException
import com.unciv.logic.files.UncivFiles
import com.unciv.logic.multiplayer.storage.*
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import com.unciv.utils.PlatformCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import java.nio.file.Files

@RunWith(BaseTestRunner::class)
class MultiplayerGameInviteTest {
    @Before fun initializeGameForValidationErrors() { TestGame() }

    @Test fun copiedInvitationRetainsGameAndSourceServer() {
        val url = MultiplayerGameInvite.createLink(ID, SERVER)
        assertEquals(MultiplayerGameInvite(ID, SERVER), MultiplayerGameInvite.parse(url))
        assertEquals("$PAGE?gameId=$ID&server=https%3A%2F%2Fexample.com", url)
    }

    @Test fun serverPathAndPortRoundTripWithoutChangingGlobalServer() {
        val server = "https://example.com:8443/multiplayer"
        assertEquals(server, MultiplayerGameInvite.parse(MultiplayerGameInvite.createLink(ID, server)).serverUrl)
    }

    @Test fun upstreamAndroidGameLinkIsRecognizedWithoutGrantingPlayerIdentity() {
        val link = IdChecker.uncivUrlPrefix + "G/G-$ID-${IdChecker.getCheckDigit(ID)}"
        assertEquals(MultiplayerGameInvite(ID, null), MultiplayerGameInvite.parse(link))
    }

    @Test fun friendAndUnrelatedLinksAreRejected() {
        for (link in listOf(
            IdChecker.uncivUrlPrefix + "P/P-$ID-${IdChecker.getCheckDigit(ID)}?name=friend",
            "unciv4ios://friend/$ID?server=https%3A%2F%2Fexample.com",
            "unciv4ios://game/$ID?server=https%3A%2F%2Fexample.com",
            "https://other.example/G/$ID", "mailto:friend@example.com",
        )) assertThrows(UncivShowableException::class.java) { MultiplayerGameInvite.parse(link) }
    }

    @Test fun malformedGameIdsAndAmbiguousRoutesAreRejected() {
        for (link in listOf(
            "$PAGE?gameId=invalid&server=https%3A%2F%2Fexample.com",
            "$PAGE/extra?gameId=$ID&server=https%3A%2F%2Fexample.com",
            "$PAGE?gameId=$ID&server=https%3A%2F%2Fexample.com&server=https%3A%2F%2Fb.example",
            "$PAGE?gameId=$ID&server=https%3A%2F%2Fexample.com&playerId=$ID",
            "$PAGE?gameId=$ID&gameId=$ID&server=https%3A%2F%2Fexample.com",
            "$PAGE?gameId=$ID#fragment", "$PAGE?gameId=$ID",
            "https://jerry8870.github.io:443/Unciv-Wiki/invite.html?gameId=$ID&server=https%3A%2F%2Fexample.com",
            "https://user@jerry8870.github.io/Unciv-Wiki/invite.html?gameId=$ID&server=https%3A%2F%2Fexample.com",
            "$PAGE?gameId=%ZZ&server=anything",
            "$PAGE?gameId=$ID&server=https%3A%2F%2Flocalhost",
            "$PAGE?gameId=$ID&server=http%3A%2F%2Fexample.com",
            "$PAGE?gameId=$ID&server=https%3A%2F%2Fexample.com&",
            "$PAGE?gameId=$ID&server=https%3A%2F%2Fexample.com#fragment",
            "$PAGE?gameId=$ID&server=https%253A%252F%252Fexample.com",
            "$PAGE?gameId=$ID&server=",
        )) assertThrows(UncivShowableException::class.java) { MultiplayerGameInvite.parse(link) }
    }

    @Test fun queryOrderDoesNotChangeInvitationAndUnrelatedHostsAreRejected() {
        assertEquals(MultiplayerGameInvite(ID, SERVER), MultiplayerGameInvite.parse(
            "$PAGE?server=https%3A%2F%2Fexample.com&gameId=$ID"))
        assertThrows(UncivShowableException::class.java) {
            MultiplayerGameInvite.parse("https://other.example/Unciv-Wiki/invite.html?gameId=$ID&server=https%3A%2F%2Fexample.com")
        }
    }

    @Test fun insecurePrivateOrCredentialBearingServersAreRejected() {
        for (server in listOf(
            "http://example.com", "https://localhost", "https://127.0.0.1", "https://10.0.0.1",
            "https://user:password@example.com", "https://example.com?password=secret", "https://example.com#fragment",
        )) assertThrows(UncivShowableException::class.java) { MultiplayerGameInvite.createLink(ID, server) }
    }

    @Test fun invitationDownloadsOnlyItsPinnedServerAndIsRecordedOnce() = runBlocking {
        val app = mock(Application::class.java)
        doAnswer { it.getArgument<Runnable>(0).run(); null }.`when`(app).postRunnable(any(Runnable::class.java))
        Gdx.app = app
        val fixture = TestGame()
        val player = fixture.addCiv(fixture.ruleset.nations.getValue("Rome"), isPlayer = true)
        val save = fixture.gameInfo.apply {
            gameId = ID
            currentPlayer = player.civID
            gameParameters.isOnlineMultiplayer = true
            gameParameters.multiplayerServerUrl = "https://untrusted-save.example"
        }
        val directory = Files.createTempDirectory("unciv-invite-")
        val settings = UncivGame.Current.settings
        UncivGame.Current = object : UncivGame() {
            override val platformCapabilities = PlatformCapabilities(multiplayerServerRequiresHttps = true)
        }.apply {
            this.settings = settings
            files = UncivFiles(Gdx.files, directory.toString())
        }
        settings.multiplayer.setServer("https://global.example")
        val requests = mutableListOf<MultiplayerV1Request>()
        val transport = object : MultiplayerV1Transport {
            override suspend fun execute(request: MultiplayerV1Request): MultiplayerV1Response {
                requests += request
                return MultiplayerV1Response(200, UncivFiles.gameInfoToString(save, forceZip = true), emptyMap(), request.url)
            }
        }
        val multiplayer = Multiplayer(transport, Dispatchers.Unconfined)
        try {
            repeat(2) {
                val loaded = multiplayer.downloadInvitedGame(MultiplayerGameInvite(ID, SERVER))
                assertEquals(SERVER, loaded.gameParameters.multiplayerServerUrl)
                assertTrue(loaded.isUpToDate)
            }
            assertEquals(1, multiplayer.games.size)
            assertEquals(ID, multiplayer.games.single().preview!!.gameId)
            assertEquals(SERVER, multiplayer.games.single().preview!!.gameParameters.multiplayerServerUrl)
            assertEquals("https://global.example", settings.multiplayer.getServer())
            assertTrue(requests.all { it.url == "$SERVER/files/$ID" && it.method == MultiplayerV1HttpMethod.GET })
        } finally {
            multiplayer.pause()
            directory.toFile().deleteRecursively()
            UncivGame.Current = UncivGame()
        }
    }

    private companion object {
        const val ID = "4e2a8b72-3f04-4fa1-a7a9-81d6e3e0a092"
        const val SERVER = "https://example.com"
        const val PAGE = "https://jerry8870.github.io/Unciv-Wiki/invite.html"
    }
}
