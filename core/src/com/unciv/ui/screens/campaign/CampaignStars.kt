package com.unciv.ui.screens.campaign

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.scenes.scene2d.ui.Image
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.utils.BaseDrawable
import com.unciv.models.skins.ExpeditionBlueTheme as Theme
import com.unciv.ui.images.ImageGetter

/** A's five-point symbol has the same silhouette for earned and outlined stars. */
internal fun campaignStars(earned: Int, size: Float = 18f, selected: Boolean = false) = Table().apply {
    for (i in 0..2) add(Image(object : BaseDrawable() {
        override fun draw(batch: Batch, x: Float, y: Float, width: Float, height: Float) {
            val points = floatArrayOf(12f, 22f, 15f, 16f, 22f, 15f, 17f, 10f, 18f, 3f,
                12f, 6f, 6f, 3f, 7f, 10f, 2f, 15f, 9f, 16f)
            val previous = Color(batch.color)
            val ink = if (selected) Theme.primaryInk else if (i < earned) Theme.gold else Theme.muted
            batch.setColor(previous.r * ink.r, previous.g * ink.g, previous.b * ink.b, previous.a)
            val region = ImageGetter.getWhiteDotDrawable().region
            val vertices = FloatArray(20)
            fun vertex(index: Int, vx: Float, vy: Float) {
                vertices[index * 5] = vx; vertices[index * 5 + 1] = vy
                vertices[index * 5 + 2] = batch.packedColor
                vertices[index * 5 + 3] = region.u; vertices[index * 5 + 4] = region.v
            }
            for (point in 0 until 10) {
                val next = (point + 1) % 10
                val ax = x + points[point * 2] / 24f * width
                val ay = y + points[point * 2 + 1] / 24f * height
                val bx = x + points[next * 2] / 24f * width
                val by = y + points[next * 2 + 1] / 24f * height
                if (i < earned) {
                    vertex(0, x + width / 2, y + height / 2)
                    vertex(1, ax, ay); vertex(2, bx, by); vertex(3, bx, by)
                    batch.draw(region.texture, vertices, 0, vertices.size)
                }
                campaignStroke(batch, ax, ay, bx, by, width * .065f)
            }
            batch.color = previous
        }
    })).size(size).padRight(size / 6f)
}
