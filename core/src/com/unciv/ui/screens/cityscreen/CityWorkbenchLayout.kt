package com.unciv.ui.screens.cityscreen

import com.badlogic.gdx.math.Rectangle

/** Point-sized layout shared by rendering and the device-size regression tests. */
class CityWorkbenchLayout(val width: Float, val height: Float) {
    val portrait = height > width
    val tablet = minOf(width, height) >= 600f
    val margin = if (tablet) 24f else if (portrait) 16f else 0f
    val gap = if (tablet) 16f else 12f
    val yieldsHeight = if (portrait && !tablet) 88f else 48f
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
            portrait -> {
                val mapHeight = minOf(204f, usableHeight * .34f)
                map.set(margin, contentTop - mapHeight, usableWidth, mapHeight)
                management.set(margin, 12f, usableWidth, usableHeight - mapHeight - gap)
            }
            else -> {
                val panelWidth = minOf(324f, usableWidth * .46f)
                management.set(width - margin - panelWidth, 12f, panelWidth, usableHeight)
                map.set(margin, 12f, usableWidth - panelWidth - gap, usableHeight)
            }
        }
    }
}
