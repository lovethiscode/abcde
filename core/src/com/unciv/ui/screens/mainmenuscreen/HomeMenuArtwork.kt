package com.unciv.ui.screens.mainmenuscreen

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.scenes.scene2d.ui.Image
import com.badlogic.gdx.scenes.scene2d.utils.BaseDrawable
import com.badlogic.gdx.utils.Disposable
import com.unciv.models.skins.ExpeditionBlueTheme
import kotlin.math.max
import kotlin.math.sqrt

/** Screen-owned textures: the approved map art is cropped, never regenerated on the home screen. */
internal class HomeMenuArtwork : Disposable {
    private val map = Texture(Gdx.files.internal("ExtraImages/MainMenuMap.png")).apply {
        setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear)
    }
    private val icon = Texture(Gdx.files.internal("ExtraImages/LaunchMarkTransparent@3x.png")).apply {
        setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear)
    }
    private val scrim = Pixmap(256, 1, Pixmap.Format.RGBA8888).let { pixmap ->
        for (x in 0 until pixmap.width) {
            val position = x.toFloat() / (pixmap.width - 1)
            val opacity = if (position <= .45f) .96f + (.75f - .96f) * position / .45f
                else .75f + (.145f - .75f) * (position - .45f) / .55f
            pixmap.setColor(ExpeditionBlueTheme.background.cpy().apply { a = opacity })
            pixmap.drawPixel(x, 0)
        }
        Texture(pixmap).apply { setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear); pixmap.dispose() }
    }

    fun icon() = Image(icon)

    fun cover(radius: Float) = Image(object : BaseDrawable() {
        override fun draw(batch: Batch, x: Float, y: Float, width: Float, height: Float) {
            val scale = max(width / map.width, height / map.height)
            val u = (1f - width / (map.width * scale)) * .6f
            val v = (1f - height / (map.height * scale)) * .62f
            val du = width / (map.width * scale)
            val dv = height / (map.height * scale)
            // Horizontal strips preserve rounded corners without a stencil or a per-frame framebuffer.
            fun draw(texture: Texture, shaded: Boolean) {
                fun strip(bottom: Float, h: Float, inset: Float) {
                    val left = inset / width
                    val top = 1f - (bottom + h) / height
                    batch.draw(texture, x + inset, y + bottom, width - inset * 2f, h,
                        if (shaded) left else u + du * left,
                        if (shaded) 0f else v + dv * (1f - bottom / height),
                        if (shaded) 1f - left else u + du * (1f - left),
                        if (shaded) 1f else v + dv * top)
                }
                strip(radius, height - 2f * radius, 0f)
                val step = radius / 16f
                for (i in 0 until 16) {
                    val bottom = i * step
                    val dy = radius - bottom - step / 2f
                    val inset = radius - sqrt(radius * radius - dy * dy)
                    strip(bottom, step, inset)
                    strip(height - bottom - step, step, inset)
                }
            }
            draw(map, false)
            draw(scrim, true)
        }
    })

    override fun dispose() { map.dispose(); icon.dispose(); scrim.dispose() }
}
