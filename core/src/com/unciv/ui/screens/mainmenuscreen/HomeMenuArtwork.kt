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
    private val icon = Texture(Gdx.files.internal("ExtraImages/Icons/AppIcon.png")).apply {
        setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear)
    }
    private val scrim = Pixmap(2, 1, Pixmap.Format.RGBA8888).let {
        it.setColor(ExpeditionBlueTheme.background.cpy().apply { a = .97f }); it.drawPixel(0, 0)
        it.setColor(ExpeditionBlueTheme.background.cpy().apply { a = .55f }); it.drawPixel(1, 0)
        Texture(it).apply { setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear); it.dispose() }
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
                        if (shaded) 0f else v + dv * top,
                        if (shaded) 1f - left else u + du * (1f - left),
                        if (shaded) 1f else v + dv * (1f - bottom / height))
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
