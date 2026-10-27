package com.unciv.ui.screens.cityscreen

import com.badlogic.gdx.math.Rectangle

/** Point-sized layout shared by rendering and the device-size regression tests. */
class CityWorkbenchLayout(val width: Float, val height: Float, val expanded: Boolean = false, phoneHeaderHeight: Float = 44f) {
    val portrait = height > width
    val tablet = minOf(width, height) >= 600f
    val margin = if (tablet) 24f else if (portrait) 16f else 0f
    val gap = if (tablet) 16f else 12f
    val yieldsHeight = if (tablet) 48f else maxOf(44f, phoneHeaderHeight)
    val contentTop = height - yieldsHeight
    val map = Rectangle()
    val production = Rectangle()
    val management = Rectangle()

    init {
        val usableWidth = width - margin * 2
        val usableHeight = contentTop - 12f
        when {
            tablet && !portrait && width >= 960f -> {
                production.set(margin, 12f, 280f, usableHeight)
                management.set(width - margin - 310f, 12f, 310f, usableHeight)
                map.set(production.x + production.width + gap, 12f,
                    usableWidth - 280f - 310f - gap * 2, usableHeight)
            }
            tablet -> {
                val mapHeight = minOf(360f, usableHeight * .42f)
                map.set(margin, contentTop - mapHeight, usableWidth, mapHeight)
                val columnWidth = (usableWidth - gap) / 2
                production.set(margin, 12f, columnWidth, usableHeight - mapHeight - gap)
                management.set(margin + columnWidth + gap, 12f, columnWidth, production.height)
            }
            expanded -> management.set(margin, 0f, usableWidth, contentTop)
            portrait -> {
                val mapHeight = minOf(144f, contentTop * .3f)
                map.set(margin, contentTop - mapHeight, usableWidth, mapHeight)
                management.set(margin, 0f, usableWidth, contentTop - mapHeight - 8f)
            }
            else -> {
                val panelWidth = minOf(380f, usableWidth * .56f)
                management.set(width - margin - panelWidth, 0f, panelWidth, contentTop)
                map.set(margin, 0f, usableWidth - panelWidth - gap, contentTop)
            }
        }
    }
}
