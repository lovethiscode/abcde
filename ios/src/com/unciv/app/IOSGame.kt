package com.unciv.app

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.backends.iosrobovm.IOSApplication
import com.unciv.UncivGame
import com.unciv.logic.multiplayer.MultiplayerGameInvite
import com.unciv.logic.multiplayer.storage.MultiplayerAuthException
import com.unciv.ui.popups.AuthPopup
import com.unciv.ui.popups.Popup
import com.unciv.ui.screens.LoadingScreen
import com.unciv.ui.screens.savescreens.LoadGameScreen
import com.unciv.utils.Concurrency
import com.unciv.utils.launchOnGLThread
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import com.unciv.logic.files.cloud.CloudSaveSync
import com.unciv.logic.files.cloud.CloudSyncTrigger
import com.unciv.logic.multiplayer.storage.MultiplayerV1Transport
import com.unciv.logic.multiplayer.chat.ChatWebSocket
import com.unciv.utils.MultiplayerBackgroundTask
import com.unciv.utils.PlatformCapabilities
import com.unciv.utils.Display
import com.unciv.models.translations.tr
import com.unciv.ui.popups.ToastPopup
import org.robovm.apple.dispatch.DispatchQueue
import org.robovm.apple.foundation.NSArray
import org.robovm.apple.foundation.NSObject
import org.robovm.apple.foundation.NSString
import org.robovm.apple.foundation.NSURL
import org.robovm.apple.uikit.UIActivity
import org.robovm.apple.uikit.UIActivityViewController
import org.robovm.apple.foundation.NSBundle
import java.util.Locale

class IOSGame @JvmOverloads constructor(
    private val runtimeFeatures: IOSRuntimeFeatures = DefaultIOSRuntimeFeatures,
    private val multiplayerTransport: MultiplayerV1Transport? = null,
) : UncivGame() {
    private var cloudSaveService: CloudSaveSync? = null
    private val gameInvites = IOSGameInviteQueue()
    private var invitationError: String? = null
    // LibGDX does not call resume() on its first, already-active graphics instance.
    private var invitationForeground = true
    private var invitationDisposed = false
    private var invitationJob: Job? = null

    override val voluntarySupportAvailable get() = runtimeFeatures.voluntarySupportAvailable
    override val achievementsAvailable get() = runtimeFeatures.achievementsAvailable
    override val campaignSharingAvailable get() = runtimeFeatures.campaignSharingAvailable
    override fun showCampaignShare(data: com.unciv.logic.campaign.CampaignShareData) = runtimeFeatures.showCampaignShare(data)
    override val endlessSharingAvailable get() = runtimeFeatures.endlessSharingAvailable
    override val multiplayerLobbyServerUrl get() = runtimeFeatures.multiplayerLobbyServerUrl
    override fun showAchievements() = runtimeFeatures.showAchievements()
    override fun showAchievementGuide() = runtimeFeatures.showAchievementGuide()
    override fun showEndlessShare(data: com.unciv.logic.endless.EndlessShareData) = runtimeFeatures.showEndlessShare(data)

    override val sourceCodeUrl = "${com.unciv.Constants.uncivRepoURL}tree/${IOSSourceRevision.COMMIT}/"

    override val displayBuildNumber: String?
        get() = NSBundle.getMainBundle()
            .getInfoDictionaryObject("CFBundleVersion")
            ?.toString()
            ?.takeIf { it.isNotBlank() }

    override val platformCapabilities = PlatformCapabilities(
        onlineMultiplayer = true,
        multiplayerChat = true,
        defaultMusicDownload = false,
        onlineModManagement = true,
        localModTransfer = true,
        multiplayerServerRequiresHttps = true,
        multiplayerApiV1Only = true,
        secureMultiplayerServerPasswords = runtimeFeatures.secureMultiplayerPasswords,
        multiplayerAdvancedActions = true,
        multiplayerFriends = false,
        multiplayerInviteLinks = true,
        oggAudio = runtimeFeatures.oggAudioAvailable,
    )

    override fun getMultiplayerServerPassword(serverUrl: String) =
        runtimeFeatures.getMultiplayerPassword(serverUrl)

    override fun setMultiplayerServerPassword(serverUrl: String, password: String) =
        runtimeFeatures.setMultiplayerPassword(serverUrl, password)

    override fun createMultiplayerV1Transport(): MultiplayerV1Transport =
        multiplayerTransport ?: IOSMultiplayerV1Transport()

    override fun createModHttpClientEngine() = IOSModHttpClientEngine()

    override fun createChatTransport() = IOSChatTransport()

    override fun createLobbyTransport() = IOSLobbyTransport()

    override fun beginMultiplayerUploadBackgroundTask(onExpired: () -> Unit): MultiplayerBackgroundTask =
        IOSMultiplayerBackgroundTask(onExpired)

    override fun showVoluntarySupport() = runtimeFeatures.showVoluntarySupport()

    override fun shareMultiplayerGame(gameName: String, gameId: String, invitationLink: String) {
        val message = "Join my Unciv game".tr() + ": " + gameName +
            "\n" + "Game ID".tr() + ": " + gameId + "\n" +
            "Open the invitation link on iOS, or copy it and paste it in Multiplayer.".tr()
        DispatchQueue.getMainQueue().async {
            try {
                var presenter = (Gdx.app as IOSApplication).uiViewController
                while (presenter.presentedViewController != null) presenter = presenter.presentedViewController
                val activity = UIActivityViewController(
                    NSArray<NSObject>(NSString(message), NSURL(invitationLink)), NSArray<UIActivity>())
                activity.popoverPresentationController?.let {
                    it.sourceView = presenter.view
                    it.sourceRect = presenter.view.bounds
                }
                presenter.presentViewController(activity, true, null)
            } catch (_: Exception) {
                Concurrency.runOnGLThread {
                    getScreen()?.let { ToastPopup("Could not open sharing. Please copy the invitation link instead.", it) }
                }
            }
        }
    }

    override fun create() {
        removeObsoletePocMusic()
        super.create()
        cloudSaveService = runtimeFeatures.createCloudSaveSync(files).also {
            files.cloudSaveSync = it
            it.requestSync(CloudSyncTrigger.Startup)
        }
        files.campaignProgressSync = runtimeFeatures.createCampaignProgressSync(files).also { it.requestSync() }
        runtimeFeatures.initialize()
    }

    override fun resume() {
        super.resume()
        invitationForeground = true
        ChatWebSocket.resume()
        (Display.platform as IOSDisplay).restoreOrientation()
        cloudSaveService?.requestSync(CloudSyncTrigger.Foreground)
        files.campaignProgressSync.requestSync()
        runtimeFeatures.onForeground()
    }

    override fun pause() {
        invitationForeground = false
        invitationJob?.cancel()
        ChatWebSocket.pause()
        super.pause()
    }

    override fun render() {
        (Display.platform as IOSDisplay).updateVisibleArea((screen as? com.unciv.ui.screens.basescreen.BaseScreen)?.stage)
        super.render()
        openPendingGameInvite()
    }

    /** Called on the Gdx thread; startup may still be loading resources. */
    fun openGameInvite(url: String) {
        try {
            gameInvites.offer(MultiplayerGameInvite.parse(url))
            invitationError = null
        } catch (ex: Exception) {
            invitationError = LoadGameScreen.getLoadExceptionMessage(ex).first
        }
    }

    private fun openPendingGameInvite() {
        if (!isInitialized || settings.isFreshlyCreated || !invitationForeground) return
        invitationError?.let { message ->
            invitationError = null
            Popup(getScreen()!!).apply {
                addGoodSizedLabel(message).row()
                addCloseButton()
                open()
            }
        }
        val invitation = gameInvites.take() ?: return
        if (invitation.lobby) {
            com.unciv.ui.screens.multiplayerscreens.MultiplayerLobbyScreen.openInvite(invitation)
            gameInvites.finish()
            return
        }
        val serverUrl = invitation.serverUrl ?: settings.multiplayer.getServer()
        val pinnedInvite = invitation.copy(serverUrl = serverUrl)
        gameInfo?.let { files.autosaves.requestAutoSave(it) }
        pushScreen(onScreenCreated = { loadGameInvite(pinnedInvite) }) { LoadingScreen(getScreen()) }
    }

    private fun loadGameInvite(pinnedInvite: MultiplayerGameInvite) {
        var retryAfterCancellation = false
        invitationJob = Concurrency.runOnNonDaemonThreadPool("Open game invitation") {
            try {
                val latest = onlineMultiplayer.downloadInvitedGame(pinnedInvite)
                loadGame(latest)
            } catch (ex: CancellationException) {
                retryAfterCancellation = true
                return@runOnNonDaemonThreadPool
            } catch (ex: Exception) {
                launchOnGLThread {
                    if (invitationDisposed) return@launchOnGLThread
                    val returnScreen = if (getScreen() is LoadingScreen) popScreen(silentQuit = true) else getScreen()
                    if (returnScreen == null) { gameInvites.finish(); return@launchOnGLThread }
                    if (ex is MultiplayerAuthException) {
                        AuthPopup(returnScreen, onlineMultiplayer.serverFor(pinnedInvite.serverUrl)) { success ->
                            gameInvites.finish(if (success) pinnedInvite else null)
                        }.open(true)
                    } else {
                        val (message) = LoadGameScreen.getLoadExceptionMessage(ex)
                        Popup(returnScreen).apply {
                            addGoodSizedLabel(message).row()
                            addCloseButton()
                            open()
                        }
                    }
                    if (ex !is MultiplayerAuthException) gameInvites.finish()
                }
                return@runOnNonDaemonThreadPool
            }
            launchOnGLThread { gameInvites.finish() }
        }.also { job ->
            job.invokeOnCompletion {
                if (job.isCancelled || retryAfterCancellation) Concurrency.runOnGLThread {
                    if (invitationDisposed) return@runOnGLThread
                    if (getScreen() is LoadingScreen) popScreen(silentQuit = true)
                    // This also handles cancellation before the download coroutine starts.
                    gameInvites.finish(pinnedInvite)
                }
            }
        }
    }

    override fun dispose() {
        invitationDisposed = true
        invitationJob?.cancel()
        ChatWebSocket.stop()
        files.cloudSaveSync = CloudSaveSync.None
        cloudSaveService?.close()
        files.campaignProgressSync.close()
        cloudSaveService = null
        runtimeFeatures.dispose()
        super.dispose()
    }

    private fun removeObsoletePocMusic() {
        for (name in listOf("Unciv POC Ambient.mp3", "Unciv POC Ambient.ogg")) {
            val obsoleteFile = Gdx.files.local("music/$name")
            if (obsoleteFile.exists()) obsoleteFile.delete()
        }
    }

    override fun getLocaleFromLanguageTag(languageTag: String): Locale {
        val parts = languageTag.substringBefore("-u-").split('-')
        return Locale(parts[0], parts.getOrElse(1) { "" })
    }

    override fun getGcCount(): Int = 0
}
