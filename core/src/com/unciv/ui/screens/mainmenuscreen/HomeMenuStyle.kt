package com.unciv.ui.screens.mainmenuscreen

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.graphics.g2d.NinePatch
import com.badlogic.gdx.scenes.scene2d.utils.BaseDrawable
import com.badlogic.gdx.scenes.scene2d.utils.Drawable
import com.badlogic.gdx.scenes.scene2d.utils.NinePatchDrawable
import com.unciv.ui.screens.basescreen.BaseScreen

/** Point-sized corners keep home buttons consistent across phone and tablet viewports. */
internal class HomeMenuStyle(private val unit: Float) {
    fun surface(color: Color, radius: Float = 12f): Drawable =
        (BaseScreen.skin.getDrawable("RoundedEdgeRectangle") as NinePatchDrawable).let { source ->
            NinePatchDrawable(NinePatch(source.patch, color).apply {
                scale(radius * unit / leftWidth, radius * unit / topHeight)
                setPadding(0f, 0f, 0f, 0f)
            }).apply { minWidth = 0f; minHeight = 0f }
        }

    fun outline(surface: Color, edge: Color): Drawable {
        val outer = surface(edge, 10f)
        val inner = surface(surface, 9f)
        return object : BaseDrawable() {
            override fun draw(batch: Batch, x: Float, y: Float, width: Float, height: Float) {
                outer.draw(batch, x, y, width, height)
                inner.draw(batch, x + unit, y + unit, (width - 2 * unit).coerceAtLeast(0f), (height - 2 * unit).coerceAtLeast(0f))
            }
        }
    }
}
