package com.unciv.app

import android.content.pm.ActivityInfo
import android.os.Build
import android.view.Display
import android.view.Display.Mode
import android.view.WindowManager
import androidx.annotation.RequiresApi
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.math.Rectangle
import com.badlogic.gdx.math.Vector2
import com.badlogic.gdx.scenes.scene2d.Stage
import com.unciv.UncivGame
import com.unciv.logic.event.EventBus
import com.unciv.ui.screens.basescreen.UncivStage
import com.badlogic.gdx.backends.android.AndroidApplication
import com.unciv.models.metadata.GameSettings
import com.unciv.models.translations.tr
import com.unciv.utils.PlatformDisplay
import com.unciv.utils.ScreenMode
import com.unciv.utils.ScreenOrientation
import com.unciv.utils.SafeInsets


class AndroidDisplay(private val activity: AndroidApplication) : PlatformDisplay {
    @Volatile private var safeInsets = SafeInsets()
    @Volatile private var keyboardBottom = 0
    @Volatile private var legacyKeyboardBottom = 0
    @Volatile private var edgeToEdge = false
    private var lastVisible: Rectangle? = null
    private var lastStage: Stage? = null

    override fun getSafeInsets() = safeInsets
    override fun isEdgeToEdgeEnabled() = edgeToEdge
    fun updateLegacyKeyboard(bottom: Int) { legacyKeyboardBottom = bottom }

    /** Android values are physical pixels; Scene2D converts them at the viewport boundary. */
    fun updateInsets(insets: WindowInsetsCompat) {
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        val next = SafeInsets(bars.left, bars.top, bars.right, bars.bottom)
        keyboardBottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
        if (next == safeInsets) return
        safeInsets = next
        refreshLayout()
    }

    private fun refreshLayout() {
        Gdx.app?.postRunnable {
            if (UncivGame.isCurrentInitialized())
                UncivGame.Current.screen?.resize(Gdx.graphics.width, Gdx.graphics.height)
        }
    }

    fun updateVisibleArea(stage: Stage?) {
        if (stage == null) return
        val keyboard = if (Build.VERSION.SDK_INT < 30) legacyKeyboardBottom else keyboardBottom
        val bottom = if (keyboard <= 0) 0f else stage.screenToStageCoordinates(
            Vector2(0f, (Gdx.graphics.height - keyboard).toFloat())).y.coerceIn(0f, stage.height)
        val visible = Rectangle(0f, bottom, stage.width, (stage.height - bottom).coerceAtLeast(0f))
        if (lastStage === stage && visible == lastVisible) return
        lastStage = stage
        lastVisible = Rectangle(visible)
        EventBus.send(UncivStage.VisibleAreaChanged(visible))
    }

    private var display: Display? = null
    private var displayModes: HashMap<Int, ScreenMode> = hashMapOf()

    init {

        // Fetch current display
        @Suppress("DEPRECATION") // M..P should use the deprecated API
        display = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> activity.display
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> activity.windowManager.defaultDisplay
            else -> null
        }

        // Add default mode
        displayModes[AndroidScreenMode.defaultId] = AndroidScreenMode.default

        // Add other supported modes
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            fetchScreenModes()
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun fetchScreenModes() {
        val display = display ?: return
        for (mode in display.supportedModes)
            displayModes[mode.modeId] = AndroidScreenMode(mode)
    }

    override fun getScreenModes(): Map<Int, ScreenMode> {
        return displayModes
    }

    override fun setScreenMode(id: Int, settings: GameSettings) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        activity.runOnUiThread {
            val params = activity.window.attributes
            params.preferredDisplayModeId = id
            activity.window.attributes = params
        }
    }

    override fun hasSystemUiVisibility() = true

    override fun setSystemUiVisibility(hide: Boolean) {
        activity.runOnUiThread {
            setSystemUiVisibilityFromUiThread(hide)
        }
    }
    internal fun setSystemUiVisibilityFromUiThread(hide: Boolean) {
        WindowCompat.setDecorFitsSystemWindows(activity.window, false)
        WindowInsetsControllerCompat(activity.window, activity.window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
            if (hide) hide(WindowInsetsCompat.Type.systemBars()) else show(WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun hasCutout(): Boolean {
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
                activity.display?.cutout != null
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                @Suppress("DEPRECATION")
                activity.windowManager.defaultDisplay.cutout != null
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ->
                activity.window.decorView.rootWindowInsets.displayCutout != null
            else -> false
        }
    }

    override fun setCutout(enabled: Boolean) {
        activity.runOnUiThread {
            setCutoutFromUiThread(enabled)
        }
    }
    internal fun setCutoutFromUiThread(enabled: Boolean) {
        val changed = edgeToEdge != enabled
        edgeToEdge = enabled
        WindowCompat.setDecorFitsSystemWindows(activity.window, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val params = activity.window.attributes
            params.layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                else WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            activity.window.attributes = params
        }
        if (changed) refreshLayout()
    }


    /*
    Sources for Info about current orientation in case need:
            val windowManager = (activity.getSystemService(Context.WINDOW_SERVICE) as WindowManager)
            val displayRotation = windowManager.defaultDisplay.rotation
            val currentOrientation = activity.resources.configuration.orientation
            const val ORIENTATION_UNDEFINED = 0
            const val ORIENTATION_PORTRAIT = 1
            const val ORIENTATION_LANDSCAPE = 2
    */

    override fun hasOrientation(): Boolean {
        return true
    }

    override fun setOrientation(orientation: ScreenOrientation) {
        val mode = when (orientation) {
            // Automatically adjust landscape based on sensors.
            ScreenOrientation.Landscape -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            // Automatically adjust the portrait based on the sensor.
            ScreenOrientation.Portrait -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            ScreenOrientation.Auto -> ActivityInfo.SCREEN_ORIENTATION_SENSOR
        }
        // Ensure ActivityTaskManager.getService().setRequestedOrientation isn't called unless necessary!
        if (activity.requestedOrientation != mode)
            activity.requestedOrientation = mode
    }

    class AndroidScreenMode private constructor(
        private val modeId: Int,
        private val name: String
    ) : ScreenMode {
        @RequiresApi(Build.VERSION_CODES.M)
        constructor(mode: Mode) : this(mode.modeId, "${mode.physicalWidth}x${mode.physicalHeight} (${mode.refreshRate.toInt()}Hz)")

        override fun getId(): Int {
            return modeId
        }

        override fun toString(): String {
            return name.tr()
        }

        companion object {
            const val defaultId = 0
            val default: AndroidScreenMode
                get() = AndroidScreenMode(defaultId, "Default")
        }
    }
}
