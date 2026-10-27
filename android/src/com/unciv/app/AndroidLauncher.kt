package com.unciv.app

import android.content.Intent
import android.media.MediaScannerConnection
import android.os.Bundle
import android.os.Build
import android.Manifest
import android.content.pm.PackageManager
import android.view.View
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.work.WorkManager
import com.badlogic.gdx.backends.android.AndroidApplication
import com.badlogic.gdx.backends.android.AndroidApplicationConfiguration
import com.unciv.logic.IdChecker
import com.unciv.logic.files.SAVE_FILES_FOLDER
import com.unciv.logic.files.UncivFiles
import com.unciv.ui.components.fonts.Fonts
import com.unciv.ui.screens.multiplayerscreens.AddFriendScreen
import com.unciv.utils.Concurrency.runOnGLThread
import com.unciv.utils.Dispatcher
import com.unciv.utils.Display
import com.unciv.utils.Log
import com.unciv.utils.launchOnGLThread
import java.io.File
import java.lang.Exception
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

open class AndroidLauncher : AndroidApplication() {

    private var game: AndroidGame? = null
    private lateinit var displayImpl: AndroidDisplay
    protected open fun createRuntimeFeatures(): AndroidRuntimeFeatures = DefaultAndroidRuntimeFeatures
    protected open fun createGame() = AndroidGame(this, createRuntimeFeatures())
    protected open fun prepareExternalFiles() {
        CoroutineScope(Dispatchers.IO).launch { createSaveFolder(); copyMods() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Setup Android logging
        Log.backend = AndroidLogBackend(this)

        // Setup Android display
        displayImpl = AndroidDisplay(this)
        Display.platform = displayImpl

        // Setup Android fonts
        Fonts.fontImplementation = AndroidFont()

        // Setup Android custom saver-loader
        UncivFiles.saverLoader = AndroidSaverLoader(this)
        UncivFiles.preferExternalStorage = true

        val settings = UncivFiles.getSettingsForPlatformLaunchers(filesDir.path)
        val config = AndroidApplicationConfiguration().apply { useImmersiveMode = settings.androidHideSystemUi }

        // Setup orientation
        displayImpl.setOrientation(settings.displayOrientation)

        // Create notification channels for Multiplayer notificator
        MultiplayerTurnCheckWorker.createNotificationChannels(applicationContext)

        prepareExternalFiles()

        game = createGame()
        initialize(game, config)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Setup display cutout AFTER libGDX initialize() — libGDX window setup resets layoutInDisplayCutoutMode
        displayImpl.setCutoutFromUiThread(settings.androidCutout)

        ViewCompat.setOnApplyWindowInsetsListener(window.decorView, ::insetsListener)
        // Force immediate insets dispatch — libGDX init triggers insets before listener is registered
        window.decorView.requestApplyInsets()
        if (Build.VERSION.SDK_INT < 30) {
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)
            window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            displayImpl.setSystemUiVisibilityFromUiThread(settings.androidHideSystemUi)
            window.decorView.viewTreeObserver.addOnGlobalLayoutListener {
                val frame = android.graphics.Rect()
                window.decorView.getWindowVisibleDisplayFrame(frame)
                val position = IntArray(2)
                window.decorView.getLocationOnScreen(position)
                val obscured = (window.decorView.height - (frame.bottom - position[1])).coerceAtLeast(0)
                val surface = (com.badlogic.gdx.Gdx.graphics as? com.badlogic.gdx.backends.android.AndroidGraphics)?.view
                displayImpl.updateLegacyKeyboard(if (surface != null && surface.height <= window.decorView.height - obscured + 2) 0 else obscured)
            }
        }

        // can be triggered via `adb shell am start -a android.intent.action.VIEW -d https://unciv.app/g/G-ef0f5e5a-f1db-4a54-9d94-92ca986afe8a-9 com.unciv.app`
        // or whatever your game id is
        game!!.setDeepLinkedGame(intent)
        if (Build.VERSION.SDK_INT >= 33) registerSystemBack()
        processPossibleFriendDeepLink(intent)
    }

    private fun insetsListener(view: View, insets: WindowInsetsCompat): WindowInsetsCompat {
        displayImpl.updateInsets(insets)
        return insets
    }

    @androidx.annotation.RequiresApi(33)
    private fun registerSystemBack() {
        onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT) {
            com.badlogic.gdx.Gdx.app.postRunnable {
                com.badlogic.gdx.Gdx.input.inputProcessor?.let {
                    it.keyDown(com.badlogic.gdx.Input.Keys.BACK)
                    it.keyUp(com.badlogic.gdx.Input.Keys.BACK)
                }
            }
        }
    }

    internal fun requestTurnNotifications() {
        if (Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        val preferences = getSharedPreferences("android-runtime", MODE_PRIVATE)
        if (preferences.getBoolean("notifications-requested", false)) return
        preferences.edit().putBoolean("notifications-requested", true).apply()
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1402)
    }

    /**
     * Copies mods from external data directory (where users can access) to the private one (where
     * libGDX reads from). Note: deletes all files currently in the private mod directory and
     * replaces them with the ones in the external folder!)
     */
    private fun copyMods() {
        // Mod directory in the internal app data (where Gdx.files.local looks)
        val internalModsDir = File("${filesDir.path}/mods")

        // Mod directory in the shared app data (where the user can see and modify)
        val externalPath = getExternalFilesDir(null)?.path ?: return
        val externalModsDir = File("$externalPath/mods")

        try { // Rarely we get a kotlin.io.AccessDeniedException, if so - no biggie
            // Copy external mod directory (with data user put in it) to internal (where it can be read)
            if (!externalModsDir.exists()) externalModsDir.mkdirs() // this can fail sometimes, which is why we check if it exists again in the next line
            if (externalModsDir.exists()) externalModsDir.copyRecursively(internalModsDir, true)
        } catch (ex: Exception) {}
    }

    // Blind attempt to prevent issues like #9604, #9847, #10302, #13113, #15029, #15219
    private fun createSaveFolder() {
        val parent = getExternalFilesDir(null) ?: filesDir
        val newFolder = File(parent, SAVE_FILES_FOLDER)
        if (!newFolder.mkdirs()) return
        // Force MediaStore update
        MediaScannerConnection.scanFile(context, arrayOf(newFolder.absolutePath), null, null)
    }

    override fun onPause() {
        val game = this.game!!
        if (game.isInitializedProxy()
                && game.gameInfo != null
                && game.settings.multiplayer.turnCheckerEnabled
                && game.files.getMultiplayerSaves().any()
        ) {
            CoroutineScope(Dispatchers.IO).launch {
                MultiplayerTurnCheckWorker.startTurnChecker(
                    applicationContext,
                    game.files,
                    game.gameInfo!!,
                    game.settings.multiplayer
                )
            }
        }
        super.onPause()
    }

    override fun onResume() {
        try {
            WorkManager.getInstance(applicationContext).cancelAllWorkByTag(MultiplayerTurnCheckWorker.WORK_TAG)
            with(NotificationManagerCompat.from(this)) {
                cancel(MultiplayerTurnCheckWorker.NOTIFICATION_ID_INFO)
                cancel(MultiplayerTurnCheckWorker.NOTIFICATION_ID_SERVICE)
            }
        } catch (ignore: Exception) {
            /* Sometimes this fails for no apparent reason - the multiplayer checker failing to
               cancel should not be enough of a reason for the game to crash! */
        }
        super.onResume()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent == null)
            return
        game?.setDeepLinkedGame(intent)
        processPossibleFriendDeepLink(intent)
    }
   
    private fun processPossibleFriendDeepLink(intent: Intent) {
        // can be triggered via
        // `adb shell am start -a android.intent.action.VIEW -d https://unciv.app/p/P-63971008-a533-47f6-ad6a-57b616626138-9?name=Yairm210 com.unciv.app`
        // or whatever your friend id and name are
        if (intent.data != null && IdChecker.isFriendDeepLink(intent.data.toString())) {
            val newFriend = IdChecker.checkAndReturnPlayerUuid(intent.data.toString())
            if (newFriend != null) runOnGLThread {
                game!!.pushScreen{ AddFriendScreen(newFriend.name, newFriend.playerID) }
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        val saverLoader = UncivFiles.saverLoader as AndroidSaverLoader
        saverLoader.onActivityResult(requestCode, if (resultCode == RESULT_OK) data else null)
        super.onActivityResult(requestCode, resultCode, data)
    }
}
