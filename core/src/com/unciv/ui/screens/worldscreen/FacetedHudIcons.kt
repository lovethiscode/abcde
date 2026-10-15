package com.unciv.ui.screens.worldscreen

import com.badlogic.gdx.scenes.scene2d.Group
import com.badlogic.gdx.scenes.scene2d.ui.Image
import com.unciv.ui.components.extensions.centerX
import com.unciv.ui.images.ImageGetter
import com.unciv.view.MapUnitView

/** Full-color HUD artwork, separate from the nation-tinted flags on the map. */
internal object FacetedHudIcons {
    fun image(name: String, size: Float = 20f): Image? {
        if (!BattlefieldHud.enabled) return null
        val path = "FacetedHudIcons/$name"
        if (!ImageGetter.imageExists(path)) return null
        return ImageGetter.getImage(path).apply { setSize(size, size) }
    }

    fun unit(unit: MapUnitView, size: Float): Group? {
        val icon = image(unit.getBaseUnit().name, size) ?: return null
        return Group().apply {
            setSize(size, size)
            addActor(icon)
            if (unit.unitHealth < 100) {
                val health = ImageGetter.getHealthBar(unit.unitHealth.toFloat(), 100f, size * 0.78f)
                addActor(health)
                health.centerX(this)
            }
        }
    }
}
