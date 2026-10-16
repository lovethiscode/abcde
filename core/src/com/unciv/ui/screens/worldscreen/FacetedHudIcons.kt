package com.unciv.ui.screens.worldscreen

import com.badlogic.gdx.scenes.scene2d.Group
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.ui.Image
import com.unciv.ui.components.extensions.centerX
import com.unciv.ui.images.ImageGetter
import com.unciv.view.MapUnitView

/** Full-color HUD artwork, separate from the nation-tinted flags on the map. */
internal object FacetedHudIcons {
    private fun image(name: String, size: Float): Image? {
        if (!BattlefieldHud.enabled) return null
        val path = "FacetedHudIcons/$name"
        if (!ImageGetter.imageExists(path)) return null
        return ImageGetter.getImage(path).apply { setSize(size, size) }
    }

    fun stat(name: String, size: Float = 20f): Image? {
        if (ImageGetter.hasModImage("StatIcons/$name")) return null
        return image("Stats/$name", size)
    }

    fun resource(name: String, size: Float = 20f): Actor? {
        if (ImageGetter.hasModImage("ResourceIcons/$name") ||
            ImageGetter.hasModImage("ResourcePortraits/$name")) return null
        return image("Resources/$name", size)?.let { BattlefieldGeometry.icon(it) }
    }

    fun action(name: String, size: Float = 20f): Image? {
        val icon = when (name) {
            "SleepUntilHealed" -> "Sleep"
            "StopAutomation", "StopMovement", "StopExploration" -> "Stop"
            "SwapUnits" -> "Swap"
            "ConnectRoad" -> "RoadConnection"
            "ShowAdditionalActions" -> "ShowMore"
            else -> name
        }
        val original = if (name == "StopMovement") "StopMove" else icon
        if (ImageGetter.hasModImage("UnitActionIcons/$original") ||
            ImageGetter.hasModImage("UnitActionPortraits/$original")) return null
        return image("Actions/$icon", size)
    }

    fun unit(unit: MapUnitView, size: Float): Group? {
        val name = unit.getBaseUnit().name
        if (ImageGetter.hasModImage("UnitIcons/$name") ||
            ImageGetter.hasModImage("UnitPortraits/$name")) return null
        val icon = image("Units/$name", size) ?: return null
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
