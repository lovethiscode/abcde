package com.unciv.logic.multiplayer

import com.badlogic.gdx.Application
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.files.FileHandle
import com.unciv.Constants
import com.unciv.UncivGame
import com.unciv.logic.CompatibilityVersion
import com.unciv.logic.GameInfo
import com.unciv.logic.GameInfoPreview
import com.unciv.logic.GameStarter
import com.unciv.logic.map.MapSize
import com.unciv.logic.map.MapType
import com.unciv.models.metadata.GameSetupInfo
import com.unciv.models.metadata.Player
import com.unciv.logic.UncivShowableException
import com.unciv.logic.civilization.PlayerType
import com.unciv.logic.files.UncivFiles
import com.unciv.logic.multiplayer.storage.*
import com.unciv.models.metadata.GameSettings
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import com.unciv.ui.audio.SoundPlayer
import com.unciv.utils.MultiplayerBackgroundTask
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

@RunWith(BaseTestRunner::class)
class MultiplayerPlayerActionsTest {
    private lateinit var directory: Path
    private lateinit var multiplayer: Multiplayer
    private lateinit var transport: MemoryTransport
    private lateinit var save: GameInfo
    private lateinit var game: MultiplayerGamePreview

    @Before fun setUp() {
        directory = Files.createTempDirectory("unciv-player-actions-")
        val app = mock(Application::class.java)
        doAnswer { it.getArgument<Runnable>(0).run(); null }.`when`(app).postRunnable(any(Runnable::class.java))
        Gdx.app = app
        val fixture = TestGame()
        fixture.makeHexagonalMap(3)
        val rome = fixture.addCiv(fixture.ruleset.nations.getValue("Rome"), isPlayer = true).apply { playerId = "rome-player" }
        val egypt = fixture.addCiv(fixture.ruleset.nations.getValue("Egypt"), isPlayer = true).apply { playerId = "egypt-player" }
        fixture.addCity(rome, fixture.tileMap.values.first())
        fixture.addCity(egypt, fixture.tileMap.values.last())
        save = fixture.gameInfo.apply {
            gameId = "game-id"
            currentPlayer = rome.civID
            currentTurnStartTime = System.currentTimeMillis() - 5 * 60_000L
            turns = 11
            gameParameters.isOnlineMultiplayer = true
            gameParameters.multiplayerServerUrl = SERVER
            gameParameters.minutesUntilSkipTurn = 1
            rome.playerMinutesBeforeForceResign = 2
            version = CompatibilityVersion.CURRENT_COMPATIBILITY_VERSION
            setTransients()
            checksum = calculateChecksum()
        }
        UncivGame.Current.files = UncivFiles(Gdx.files, directory.toString())
        UncivGame.Current.settings.multiplayer.setUserId("egypt-player")
        transport = MemoryTransport(save)
        multiplayer = Multiplayer(transport, Dispatchers.Unconfined)
        game = MultiplayerGamePreview(FileHandle(directory.resolve("preview").toFile()), save.asPreview())
    }

    @After fun tearDown() {
        multiplayer.pause()
        directory.toFile().deleteRecursively()
        UncivGame.Current = UncivGame()
    }

    @Test fun downloadFailureWithoutMessageIsNotSuccess() {
        transport.getFailure = IOException()
        assertThrows(IOException::class.java) { runBlocking { act(MultiplayerPlayerAction.SkipTurn) } }
        assertEquals(0, transport.puts.size)
    }

    @Test fun resignTransfersOnlyOwnCivilizationToAi() = runBlocking {
        UncivGame.Current.settings.multiplayer.setUserId("rome-player")
        act(MultiplayerPlayerAction.Resign)
        assertTransferredToAi()
        assertTrue(transport.storedGame().civilizations.last().notifications.any { "resigned and" in it.text })
    }

    @Test fun forceResignTransfersTimedOutOpponentToAi() = runBlocking {
        act(MultiplayerPlayerAction.ForceResign)
        assertTransferredToAi()
        assertTrue(transport.storedGame().civilizations.last().notifications.any { "forcibly resigned" in it.text })
    }

    @Test fun skipAdvancesOnlyOneTurnAndKeepsPlayerHuman() = runBlocking {
        act(MultiplayerPlayerAction.SkipTurn)
        val uploaded = transport.storedGame()
        assertEquals(PlayerType.Human, uploaded.civilizations.first().playerType)
        assertEquals("rome-player", uploaded.civilizations.first().playerId)
        assertEquals(save.civilizations.last().civID, uploaded.currentPlayer)
        assertEquals(save.turns, uploaded.turns)
        assertFalse(pending().requiresRecovery())
    }

    @Test fun spectatorsAndOutsidersCannotAct() {
        val preview = save.asPreview()
        preview.civilizations.last().civName = Constants.spectator
        for (action in MultiplayerPlayerAction.entries) {
            assertNotNull(playerActionError(action, preview, preview, "egypt-player"))
            assertNotNull(playerActionError(action, save.asPreview(), save.asPreview(), "outsider"))
            assertNotNull(playerActionError(action, save.asPreview(), save.asPreview(), ""))
        }
    }

    @Test fun timeoutBoundaryAndSeparateForceResignLimitAreEnforced() {
        val preview = save.asPreview()
        val start = preview.currentTurnStartTime
        assertNotNull(playerActionError(MultiplayerPlayerAction.SkipTurn, preview, preview, "egypt-player", start + 60_000))
        assertNull(playerActionError(MultiplayerPlayerAction.SkipTurn, preview, preview, "egypt-player", start + 60_001))
        assertNotNull(playerActionError(MultiplayerPlayerAction.ForceResign, preview, preview, "egypt-player", start + 120_000))
        assertNull(playerActionError(MultiplayerPlayerAction.ForceResign, preview, preview, "egypt-player", start + 120_001))
        assertNotNull(playerActionError(MultiplayerPlayerAction.SkipTurn, preview, preview, "egypt-player", start - 1))
    }

    @Test fun ownTurnAllowsOnlyResignAndOpponentCannotResignForUs() {
        val preview = save.asPreview()
        assertNull(playerActionError(MultiplayerPlayerAction.Resign, preview, preview, "rome-player"))
        assertNotNull(playerActionError(MultiplayerPlayerAction.Resign, preview, preview, "egypt-player"))
        for (action in listOf(MultiplayerPlayerAction.SkipTurn, MultiplayerPlayerAction.ForceResign))
            assertNotNull(playerActionError(action, preview, preview, "rome-player"))
    }

    @Test fun lastHumanCannotResignIntoEndlessAiSimulation() {
        val preview = save.asPreview()
        preview.civilizations.last().playerType = PlayerType.AI
        assertNotNull(playerActionError(MultiplayerPlayerAction.Resign, preview, preview, "rome-player"))
    }

    @Test fun changedTurnCounterOrStartTimeWithSamePlayerBlocksWrite() = runBlocking {
        for (changed in listOf(save.clone().apply { turns++ }, save.clone().apply { currentTurnStartTime++ })) {
            game.preview = save.asPreview()
            transport.full = UncivFiles.gameInfoToString(changed, forceZip = true)
            assertThrows(UncivShowableException::class.java) { runBlocking { act(MultiplayerPlayerAction.SkipTurn) } }
        }
        assertEquals(0, transport.puts.size)
    }

    @Test fun serverAdvanceBeforeFirstWriteDiscardsComputedAction() = runBlocking {
        var reads = 0
        transport.beforeGet = {
            if (++reads == 2)
                transport.full = UncivFiles.gameInfoToString(save.clone().apply { turns++ }, forceZip = true)
        }
        assertThrows(UncivShowableException::class.java) { runBlocking { act(MultiplayerPlayerAction.SkipTurn) } }
        assertEquals(0, transport.puts.size)
        assertFalse(pending().requiresRecovery())
    }

    @Test fun anotherPendingTurnBlocksNewActions() {
        PendingTurnUploadState.forRemoteGame(save.gameId, SERVER, UncivGame.Current.files)
            .getOrCreate(save) { save.clone().apply { turns++ } }
        assertThrows(UncivShowableException::class.java) { runBlocking { act(MultiplayerPlayerAction.SkipTurn) } }
        assertEquals(0, transport.puts.size)
        assertTrue(pending().requiresRecovery())
    }

    @Test fun latestSavePermissionsAndTimeoutOverrideStalePreview() = runBlocking {
        for (changed in listOf(
            save.clone().apply { civilizations.last().playerId = "someone-else" },
            save.clone().apply { currentTurnStartTime = System.currentTimeMillis() },
            save.clone().apply { civilizations.first().playerMinutesBeforeForceResign = 100 },
        )) {
            game.preview = save.asPreview()
            transport.full = UncivFiles.gameInfoToString(changed, forceZip = true)
            assertThrows(UncivShowableException::class.java) { runBlocking { act(MultiplayerPlayerAction.ForceResign) } }
        }
        assertEquals(0, transport.puts.size)
    }

    @Test fun duplicateClickDoesNotRunASecondAction() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        transport.beforeGet = { entered.complete(Unit); release.await() }
        val first = async { act(MultiplayerPlayerAction.SkipTurn) }
        entered.await()
        assertThrows(UncivShowableException::class.java) { runBlocking { act(MultiplayerPlayerAction.SkipTurn) } }
        transport.beforeGet = null
        release.complete(Unit)
        first.await()
        assertEquals(1, transport.puts.count { !it.url.endsWith("_Preview") })
    }

    @Test fun unknownFullUploadIsReconciledWithoutUploadingOrAutomatingAgain() = runBlocking {
        transport.failFullAfterStore = true
        assertThrows(MultiplayerFullGameUploadOutcomeUnknownException::class.java) {
            runBlocking { act(MultiplayerPlayerAction.SkipTurn) }
        }
        val payload = transport.full
        assertTrue(pending().requiresRecovery())
        multiplayer = Multiplayer(transport, Dispatchers.Unconfined) // Process restart restores exact intent.
        assertEquals(MultiplayerPlayerAction.SkipTurn, pending().get()!!.playerAction)
        assertEquals("egypt-player", pending().get()!!.initiatingPlayerId)
        multiplayer.retryPlayerAction(game)
        assertEquals(payload, transport.full)
        assertEquals(1, transport.puts.count { !it.url.endsWith("_Preview") })
        assertFalse(pending().requiresRecovery())
    }

    @Test fun rejectedFullUploadRetriesExactPersistedPayload() = runBlocking {
        transport.failFullBeforeStore = true
        assertThrows(MultiplayerFullGameUploadOutcomeUnknownException::class.java) { runBlocking { act(MultiplayerPlayerAction.ForceResign) } }
        val firstPayload = transport.puts.single().body
        transport.failFullBeforeStore = false
        multiplayer.retryPlayerAction(game)
        assertEquals(firstPayload, transport.puts.filter { !it.url.endsWith("_Preview") }.last().body)
        assertTransferredToAi()
    }

    @Test fun previewFailureRepairsOnlyPreview() = runBlocking {
        transport.failPreview = true
        assertThrows(MultiplayerPreviewUploadException::class.java) { runBlocking { act(MultiplayerPlayerAction.ForceResign) } }
        transport.failPreview = false
        multiplayer.retryPlayerAction(game)
        assertEquals(1, transport.puts.count { !it.url.endsWith("_Preview") })
        assertTransferredToAi()
    }

    @Test fun authenticationAndCancellationArePropagatedWithoutWriting() {
        for (ex in listOf(MultiplayerAuthException(null), CancellationException())) {
            transport.getFailure = ex
            assertThrows(ex.javaClass) { runBlocking { act(MultiplayerPlayerAction.SkipTurn) } }
        }
        assertEquals(0, transport.puts.size)
    }

    @Test fun uploadAuthenticationKeepsIntentAndDoesNotRecomputeOnRetry() = runBlocking {
        transport.fullFailure = MultiplayerAuthException(null)
        assertThrows(MultiplayerAuthException::class.java) { runBlocking { act(MultiplayerPlayerAction.Resign.also {
            UncivGame.Current.settings.multiplayer.setUserId("rome-player")
        }) } }
        transport.fullFailure = null
        multiplayer.retryPlayerAction(game)
        assertEquals(transport.puts[0].body, transport.puts[1].body)
        assertTransferredToAi()
    }

    @Test fun changedServerStateDiscardsPendingWithoutOverwriting() = runBlocking {
        transport.failFullBeforeStore = true
        assertThrows(MultiplayerFullGameUploadOutcomeUnknownException::class.java) { runBlocking { act(MultiplayerPlayerAction.SkipTurn) } }
        transport.full = UncivFiles.gameInfoToString(save.clone().apply { turns += 2 }, forceZip = true)
        assertThrows(UncivShowableException::class.java) { runBlocking { multiplayer.retryPlayerAction(game) } }
        assertEquals(1, transport.puts.size)
        assertFalse(pending().requiresRecovery())
        assertEquals(save.turns + 2, game.preview!!.turns)
    }

    @Test fun backgroundExpirationBeforeWriteRetainsIntentAndRetryWorks() = runBlocking {
        val previous = UncivGame.Current
        var finished = false
        UncivGame.Current = object : UncivGame() {
            override fun beginMultiplayerUploadBackgroundTask(onExpired: () -> Unit): MultiplayerBackgroundTask {
                onExpired()
                return object : MultiplayerBackgroundTask {
                    override val isExpired = true
                    override fun finish() { finished = true }
                }
            }
        }.apply { settings = previous.settings; files = previous.files }
        assertThrows(UncivShowableException::class.java) { runBlocking { act(MultiplayerPlayerAction.SkipTurn) } }
        assertTrue(finished)
        assertEquals(0, transport.puts.size)
        assertTrue(pending().requiresRecovery())
        UncivGame.Current = previous
        multiplayer.retryPlayerAction(game)
        assertFalse(pending().requiresRecovery())
        assertEquals(1, transport.puts.count { !it.url.endsWith("_Preview") })
    }

    @Test fun retryWithAnotherIdentityCannotUploadPendingAction() = runBlocking {
        transport.failFullBeforeStore = true
        assertThrows(MultiplayerFullGameUploadOutcomeUnknownException::class.java) { runBlocking { act(MultiplayerPlayerAction.SkipTurn) } }
        UncivGame.Current.settings.multiplayer.setUserId("rome-player")
        assertThrows(UncivShowableException::class.java) { runBlocking { multiplayer.retryPlayerAction(game) } }
        assertEquals(1, transport.puts.size)
        assertTrue(pending().requiresRecovery())
    }

    @Test fun cancelledUploadRetainsExactIntentForRetry() = runBlocking {
        transport.fullFailure = CancellationException()
        assertThrows(CancellationException::class.java) { runBlocking { act(MultiplayerPlayerAction.SkipTurn) } }
        assertTrue(pending().requiresRecovery())
        transport.fullFailure = null
        multiplayer.retryPlayerAction(game)
        assertEquals(transport.puts[0].body, transport.puts[1].body)
        assertFalse(pending().requiresRecovery())
    }

    @Test fun backgroundExpirationDuringWriteDoesNotCancelItOrRepeatConfirmedTurn() = runBlocking {
        val previous = UncivGame.Current
        var expired = false
        var finished = false
        UncivGame.Current = object : UncivGame() {
            override fun beginMultiplayerUploadBackgroundTask(onExpired: () -> Unit): MultiplayerBackgroundTask {
                transport.beforePut = { expired = true; onExpired() }
                return object : MultiplayerBackgroundTask {
                    override val isExpired get() = expired
                    override fun finish() { finished = true }
                }
            }
        }.apply { settings = previous.settings; files = previous.files }
        assertThrows(UncivShowableException::class.java) { runBlocking { act(MultiplayerPlayerAction.SkipTurn) } }
        assertEquals(2, transport.puts.size)
        assertTrue(finished)
        assertTrue(pending().requiresRecovery())
        transport.beforePut = null
        UncivGame.Current = previous
        multiplayer.retryPlayerAction(game)
        assertEquals(2, transport.puts.size)
        assertFalse(pending().requiresRecovery())
    }

    @Test fun staleWireChecksumDoesNotPreventSafeContentReconciliation() = runBlocking {
        val changed = save.clone().apply { civilizations.first().addNotification("server notice", com.unciv.logic.civilization.NotificationCategory.General) }
        transport.full = UncivFiles.gameInfoToString(changed, forceZip = true)
        act(MultiplayerPlayerAction.SkipTurn)
        assertFalse(pending().requiresRecovery())
    }

    @Test fun skipFreshGameWithStartingUnitsKeepsTheServerSnapshotStable() = runBlocking {
        multiplayer.pause()
        save = GameStarter.startNewGame(GameSetupInfo().apply {
            gameParameters.baseRuleset = "Civ V - Gods & Kings"
            gameParameters.isOnlineMultiplayer = true
            gameParameters.numberOfCityStates = 0
            gameParameters.players = arrayListOf(
                Player("Germany", PlayerType.Human, "germany-player"),
                Player("Greece", PlayerType.Human, "greece-player"),
            )
            mapParameters.mapSize = MapSize.Tiny
            mapParameters.type = MapType.pangaea
            mapParameters.seed = 1017L
        }, reservedGameId = "fresh-multiplayer-skip").apply {
            gameParameters.multiplayerServerUrl = SERVER
            currentPlayer = "Greece"
            currentTurnStartTime = System.currentTimeMillis() - 3 * 86_400_000L
            setTransients()
        }
        UncivGame.Current.settings.multiplayer.setUserId("germany-player")
        transport = MemoryTransport(save)
        multiplayer = Multiplayer(transport, Dispatchers.Unconfined)
        game = MultiplayerGamePreview(FileHandle(directory.resolve("fresh-preview").toFile()), save.asPreview())

        try {
            act(MultiplayerPlayerAction.SkipTurn)

            val uploaded = transport.storedGame()
            assertEquals("Germany", uploaded.currentPlayer)
            assertEquals(PlayerType.Human, uploaded.civilizations.first { it.civName == "Greece" }.playerType)
            assertEquals(2, transport.puts.size)
            assertFalse(pending().requiresRecovery())
        } finally {
            SoundPlayer.clearCache()
        }
    }

    private suspend fun act(action: MultiplayerPlayerAction) = multiplayer.performPlayerAction(game, game.preview!!, action)
    private fun pending() = PendingTurnUploadState.forRemoteGame(save.gameId, SERVER, UncivGame.Current.files)
    private fun assertTransferredToAi() {
        val uploaded = transport.storedGame()
        assertEquals(PlayerType.AI, uploaded.civilizations.first().playerType)
        assertEquals("", uploaded.civilizations.first().playerId)
        assertEquals("egypt-player", uploaded.civilizations.last().playerId)
        assertEquals(save.civilizations.last().civID, uploaded.currentPlayer)
        assertFalse(pending().requiresRecovery())
    }

    private class MemoryTransport(save: GameInfo) : MultiplayerV1Transport {
        var full = UncivFiles.gameInfoToString(save, forceZip = true)
        var preview = UncivFiles.gameInfoToString(save.asPreview())
        val puts = mutableListOf<MultiplayerV1Request>()
        var getFailure: Exception? = null
        var fullFailure: Exception? = null
        var beforeGet: (suspend () -> Unit)? = null
        var beforePut: (() -> Unit)? = null
        var failFullBeforeStore = false
        var failFullAfterStore = false
        var failPreview = false
        override suspend fun execute(request: MultiplayerV1Request): MultiplayerV1Response {
            val isPreview = request.url.endsWith("_Preview")
            if (request.method == MultiplayerV1HttpMethod.GET) {
                beforeGet?.invoke()
                getFailure?.let { throw it }
            } else {
                beforePut?.invoke()
                puts += request
                if (isPreview) {
                    if (failPreview) throw IOException()
                    preview = request.body!!
                } else {
                    fullFailure?.let { throw it }
                    if (failFullBeforeStore) throw IOException()
                    full = request.body!!
                    if (failFullAfterStore) { failFullAfterStore = false; throw IOException() }
                }
            }
            return MultiplayerV1Response(200, if (isPreview) preview else full, emptyMap(), request.url)
        }
        fun storedGame() = UncivFiles.gameInfoFromString(full)
    }

    private companion object { const val SERVER = "https://example.com" }
}
