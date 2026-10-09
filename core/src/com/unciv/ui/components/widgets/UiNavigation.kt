package com.unciv.ui.components.widgets

import com.badlogic.gdx.Application
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.unciv.Constants
import com.unciv.UncivGame
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.setFontSize
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.utils.Display
import kotlin.math.roundToInt

/** iOS navigation controls use point-sized targets, independently of the selected UI scale. */
object UiNavigation {
    val isIos get() = Gdx.app?.type == Application.ApplicationType.iOS
    val backCaption get() = if (isIos) "‹ ${"Back".tr()}" else Constants.close.tr()

    fun minimumTouchSize(): Float {
        if (!isIos) return 44f
        val safe = Display.getSafeArea(Gdx.graphics.width, Gdx.graphics.height)
        return touchSize(UncivGame.Current.settings.screenSize.virtualHeight, minOf(safe.width, safe.height).toFloat())
    }

    fun touchSize(virtualSize: Float, safePoints: Float) = 44f * virtualSize / safePoints

    fun backButton(): TextButton = backCaption.toTextButton().apply {
        name = "ui-navigation-back"
        labelCell.pad(8f)
        setSize(maxOf(prefWidth, minimumTouchSize()), maxOf(prefHeight, minimumTouchSize()))
    }

    fun dismissButton(): TextButton = "×".toTextButton().apply {
        name = "ui-popup-close"
        label.setFontSize((18f * minimumTouchSize() / 44f).roundToInt())
        val size = maxOf(50f, minimumTouchSize())
        setSize(size, size)
    }
}
