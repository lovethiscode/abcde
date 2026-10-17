package com.unciv.ui.components.fonts

import com.unciv.ui.components.fonts.TextRole
import com.badlogic.gdx.Application
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.scenes.scene2d.Stage
import com.unciv.UncivGame
import com.unciv.utils.Display
import kotlin.math.roundToInt

/** Design sizes in points, before the player's font multiplier is applied by [Fonts]. */
enum class TextRole(val points: Int, val legacySize: Int = points) {
    Display(32),
    PageTitle(24),
    SectionTitle(20),
    Body(17, 18),
    Control(16, 18),
    Supporting(14),
    CompactData(14),
    Metadata(12)
}

/** Shared type scale. World-map labels and exported posters keep their own coordinate systems. */
object UiTypography {
    private var currentStage: Stage? = null

    fun useStage(stage: Stage) { currentStage = stage }
    fun releaseStage(stage: Stage) { if (currentStage === stage) currentStage = null }

    private val isIos get() = Gdx.app?.type == Application.ApplicationType.iOS

    /** Pass the owning stage when available; construction-time widgets use the safe viewport. */
    fun unitsPerPoint(stage: Stage? = currentStage): Float {
        if (!isIos) return 1f
        if (stage != null && stage.viewport.screenHeight > 0)
            return stage.camera.viewportHeight / stage.viewport.screenHeight
        val safe = Display.getSafeArea(Gdx.graphics.width, Gdx.graphics.height)
        return UncivGame.Current.settings.screenSize.virtualHeight / minOf(safe.width, safe.height).coerceAtLeast(1)
    }

    /** [unit] is 1 inside a parent that already scales point coordinates (e.g. CityWorkbench). */
    fun size(role: TextRole, unit: Float = unitsPerPoint(), legacySize: Int = role.legacySize): Int =
        if (isIos) (role.points * unit).roundToInt().coerceAtLeast(1) else legacySize

    /** The only responsive size variants are for narrative/display titles. */
    fun displaySize(compact: Boolean, wide: Boolean = false, unit: Float = unitsPerPoint()): Int =
        ((if (compact) 28 else if (wide) 36 else TextRole.Display.points) * unit).roundToInt().coerceAtLeast(1)

    /** Shared by input fields and select lists, which cannot use Label.setFontScale. */
    fun controlFont(unit: Float = unitsPerPoint()): BitmapFont = Fonts.getUiFont(size(TextRole.Control, unit))
}
