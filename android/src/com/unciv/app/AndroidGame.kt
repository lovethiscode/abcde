package com.unciv.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Debug
import com.badlogic.gdx.Gdx
import com.unciv.models.translations.tr
import com.unciv.UncivGame
import com.unciv.logic.IdChecker
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.utils.Concurrency
import com.unciv.utils.isUUID
import com.unciv.utils.launchOnGLThread
import java.util.Locale

class AndroidGame(private val activity: Activity, private val runtimeFeatures: AndroidRuntimeFeatures = DefaultAndroidRuntimeFeatures) : UncivGame() {

    private val credentials = AndroidCredentials(activity)
    override val platformCapabilities get() = com.unciv.utils.PlatformCapabilities(
        localModTransfer = true,
        multiplayerInviteLinks = true,
        secureMultiplayerServerPasswords = credentials.available,
    )
    override val multiplayerLobbyServerUrl get() = runtimeFeatures.lobbyServerUrl
    override val achievementsAvailable get() = runtimeFeatures.achievementsAvailable
    override val platformSettingsAvailable get() = runtimeFeatures.settingsAvailable
    override fun showPlatformSettings() = runtimeFeatures.showSettings()
    override val campaignSharingAvailable get() = runtimeFeatures.sharingAvailable
    override val endlessSharingAvailable get() = runtimeFeatures.sharingAvailable
    override fun showAchievements() = runtimeFeatures.showAchievements()
    override fun showAchievementGuide() = runtimeFeatures.showAchievementGuide()
    override fun showCampaignShare(data: com.unciv.logic.campaign.CampaignShareData) = runtimeFeatures.shareCampaign(data)
    override fun showEndlessShare(data: com.unciv.logic.endless.EndlessShareData) = runtimeFeatures.shareEndless(data)
    override fun getMultiplayerServerPassword(serverUrl: String) = credentials.get(serverUrl)
    override fun setMultiplayerServerPassword(serverUrl: String, password: String) = credentials.set(serverUrl, password)
    override val displayBuildNumber get() = activity.packageManager.getPackageInfo(activity.packageName, 0).let {
        if (Build.VERSION.SDK_INT >= 28) it.longVersionCode.toString() else @Suppress("DEPRECATION") it.versionCode.toString()
    }

    override fun create() {
        val ambient = customDataDirectory?.let { Gdx.files.absolute(it).child("music/Unciv Ambient.ogg") }
            ?: Gdx.files.local("music/Unciv Ambient.ogg")
        if (!ambient.exists()) {
            ambient.parent().mkdirs()
            Gdx.files.internal("sounds/cityAncient.ogg").copyTo(ambient)
        }
        super.create()
        runtimeFeatures.initialize(this)
    }

    override fun render() {
        (com.unciv.utils.Display.platform as AndroidDisplay).updateVisibleArea((screen as? BaseScreen)?.stage)
        super.render()
        runtimeFeatures.render(this)
        openPendingInvite()
        val now = System.currentTimeMillis()
        if (!notificationRequested && isInitialized && now >= nextNotificationCheck) {
            nextNotificationCheck = now + 5_000
            if (settings.multiplayer.turnCheckerEnabled && files.getMultiplayerSaves().any()) {
                notificationRequested = true
                activity.runOnUiThread { (activity as? AndroidLauncher)?.requestTurnNotifications() }
            }
        }
    }

    override fun resume() {
        super.resume()
        foreground = true
        com.unciv.logic.multiplayer.chat.ChatWebSocket.resume()
        runtimeFeatures.resume()
    }
    override fun pause() {
        foreground = false
        invitationJob?.cancel()
        runtimeFeatures.pause()
        com.unciv.logic.multiplayer.chat.ChatWebSocket.pause()
        super.pause()
    }
    override fun dispose() {
        disposed = true
        invitationJob?.cancel()
        com.unciv.logic.multiplayer.chat.ChatWebSocket.stop()
        runtimeFeatures.dispose()
        super.dispose()
    }

    override fun shareMultiplayerGame(gameName: String, gameId: String, invitationLink: String) {
        val message = "Join my Unciv game".tr() + ": " + gameName + "\n" + invitationLink
        activity.runOnUiThread {
            try {
                activity.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, message)
                }, null))
            } catch (_: Exception) { showInviteError("Could not open sharing. Please copy the invitation link instead.") }
        }
    }

    private val invites = java.util.concurrent.ConcurrentLinkedQueue<com.unciv.logic.multiplayer.MultiplayerGameInvite>()
    @Volatile private var foreground = true
    @Volatile private var disposed = false
    private var invitationJob: kotlinx.coroutines.Job? = null
    private var openingInvite = false
    private var notificationRequested = false
    private var nextNotificationCheck = 0L
    private fun showInviteError(message: String) = Concurrency.runOnGLThread {
        getScreen()?.let { com.unciv.ui.popups.Popup(it).apply {
            addGoodSizedLabel(message); row(); addCloseButton(); open()
        } }
    }
    private fun openPendingInvite() {
        if (!isInitialized || settings.isFreshlyCreated || !foreground || openingInvite || disposed) return
        val invite = invites.poll() ?: return
        openingInvite = true
        if (invite.lobby) {
            com.unciv.ui.screens.multiplayerscreens.MultiplayerLobbyScreen.openInvite(invite,
                canOpen = { foreground && !disposed },
                onFinished = {
                    openingInvite = false
                    if (!foreground && !disposed) invites.offer(invite)
                })
            return
        }
        val pinned = invite.copy(serverUrl = invite.serverUrl ?: settings.multiplayer.getServer())
        openingInvite = true
        gameInfo?.let { files.autosaves.requestAutoSave(it) }
        pushScreen(onScreenCreated = {
            invitationJob = Concurrency.runOnNonDaemonThreadPool("Open Android invitation") {
                try {
                    loadGame(onlineMultiplayer.downloadInvitedGame(pinned))
                } catch (ex: kotlinx.coroutines.CancellationException) {
                    throw ex
                } catch (ex: Exception) {
                    launchOnGLThread {
                        if (!disposed) {
                            if (getScreen() is com.unciv.ui.screens.LoadingScreen) popScreen(silentQuit = true)
                            val target = getScreen()
                            if (ex is com.unciv.logic.multiplayer.storage.MultiplayerAuthException && target != null)
                                com.unciv.ui.popups.AuthPopup(target, onlineMultiplayer.serverFor(pinned.serverUrl)) {
                                    if (it) invites.offer(pinned)
                                }.open(true)
                            else showInviteError(com.unciv.ui.screens.savescreens.LoadGameScreen.getLoadExceptionMessage(ex).first)
                        }
                    }
                }
            }.also { job ->
                job.invokeOnCompletion { error ->
                    Concurrency.runOnGLThread {
                        openingInvite = false
                        if (error is kotlinx.coroutines.CancellationException && !disposed) {
                            if (!invites.contains(pinned)) invites.offer(pinned)
                            if (getScreen() is com.unciv.ui.screens.LoadingScreen) popScreen(silentQuit = true)
                        }
                    }
                }
            }
        }) { com.unciv.ui.screens.LoadingScreen(getScreen()) }
    }

    /** This is needed in onCreate _and_ onNewIntent to open links and notifications
     *  correctly even if the app was not running */
    fun setDeepLinkedGame(intent: Intent) {
        val link = intent.data?.toString().orEmpty()
        if (intent.action == Intent.ACTION_VIEW && link.contains(com.unciv.logic.multiplayer.MultiplayerGameInvite.INVITATION_HOST)) {
            try {
                val invite = com.unciv.logic.multiplayer.MultiplayerGameInvite.parse(link)
                if (invites.size < 8 && !invites.contains(invite)) invites.offer(invite)
            } catch (_: Exception) { showInviteError("This invitation link is invalid. Ask the creator for a new game invitation.") }
            return
        }
        if (intent.action != Intent.ACTION_VIEW) {
            deepLinkedMultiplayerGame = null
        }
        val uri: Uri? = intent.data
        val idParam = uri?.getQueryParameter("id") //legacy game url
        deepLinkedMultiplayerGame = 
            if (idParam != null && idParam.isUUID()) idParam
            else if (IdChecker.isGameDeepLink(uri.toString())) IdChecker.checkAndReturnUuiId(uri.toString())
            else null
    }

    fun isInitializedProxy() = super.isInitialized

    override fun getGcCount(): Int = 
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Debug.getRuntimeStat("art.gc.gc-count").toInt() else 0

    override fun getDefaultLocale(): Locale =
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) super.getDefaultLocale()
        else activity.resources.configuration.locales.get(0)
}
