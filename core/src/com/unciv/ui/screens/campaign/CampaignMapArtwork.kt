package com.unciv.ui.screens.campaign

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.Image
import com.badlogic.gdx.scenes.scene2d.utils.BaseDrawable
import com.badlogic.gdx.utils.Disposable
import com.unciv.ui.images.ImageGetter
import kotlin.math.max
import kotlin.math.sqrt

/** Chapter geography uses the same packaged terrain as the approved A prototype, not a level preview. */
internal class CampaignMapArtwork : Disposable {
    private val terrain = Pixmap(Gdx.files.internal("ExtraImages/MainMenuMap.png")).let { pixels ->
        // Match A's saturation(.55) and brightness(.68) before applying its map shade.
        val color = Color()
        for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
            Color.rgba8888ToColor(color, pixels.getPixel(x, y))
            val luma = color.r * .213f + color.g * .715f + color.b * .072f
            color.set((luma + (color.r - luma) * .55f) * .68f,
                (luma + (color.g - luma) * .55f) * .68f,
                (luma + (color.b - luma) * .55f) * .68f, color.a)
            pixels.drawPixel(x, y, Color.rgba8888(color))
        }
        Texture(pixels).also { pixels.dispose() }.apply {
            setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear)
        }
    }
    private val symbols = Texture(Gdx.files.internal("ExtraImages/CampaignIcons.png")).apply {
        setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear)
    }
    fun icon(name: String, tint: Color): Image {
        val index = listOf("back", "arrow", "close", "lock", "check", "cloud", "share", "city", "compass", "flag", "book", "sun", "info", "refresh", "star").indexOf(name)
        return Image(com.badlogic.gdx.graphics.g2d.TextureRegion(symbols, index * 96, 0, 96, 96)).apply {
            color = tint; touchable = Touchable.disabled
        }
    }
    fun image(radius: Float, chapter: Int = -1) = Image(object : BaseDrawable() {
        override fun draw(batch: Batch, x: Float, y: Float, width: Float, height: Float) {
            val scale = max(width / terrain.width, height * (if (chapter < 0) 1.2f else 1.3f) / terrain.height)
            val du = width / (terrain.width * scale)
            val dv = height / (terrain.height * scale)
            val cropX = when (chapter) { 1 -> .15f; 2 -> .35f; 3 -> 1f; else -> .55f }
            val cropY = when (chapter) { 1, 3 -> .40f; 2 -> .90f; else -> .65f }
            val u = (1f - du) * cropX
            val v = (1f - dv) * cropY
            val previous = Color(batch.color)
            val r = minOf(radius, width / 2f, height / 2f)
            fun strip(bottom: Float, h: Float, inset: Float) {
                val left = inset / width
                batch.draw(terrain, x + inset, y + bottom, width - inset * 2f, h,
                    u + du * left, v + dv * (1f - bottom / height),
                    u + du * (1f - left), v + dv * (1f - (bottom + h) / height))
                val t = (bottom + h / 2f) / height
                val shade = if (t < .5f) (.5f - t) * 1.2f else (t - .5f) * .5f
                batch.setColor(.063f, .18f, .22f, previous.a * shade)
                ImageGetter.getWhiteDotDrawable().draw(batch, x + inset, y + bottom, width - inset * 2f, h)
                batch.color = previous
            }
            val middleHeight = height - r * 2f
            for (i in 0 until 24) strip(r + middleHeight * i / 24f, middleHeight / 24f, 0f)
            for (i in 0 until 12) {
                val step = r / 12f
                val bottom = i * step
                val dy = r - bottom - step / 2f
                val inset = r - sqrt(r * r - dy * dy)
                strip(bottom, step, inset)
                strip(height - bottom - step, step, inset)
            }
            batch.color = previous
        }
    }).apply { touchable = Touchable.disabled }
    override fun dispose() { terrain.dispose(); symbols.dispose() }
}

/** Fixed point-radius surfaces keep the map labels independent of the global game skin. */
internal fun campaignSurface(color: Color, radius: Float) = object : BaseDrawable() {
    override fun draw(batch: Batch, x: Float, y: Float, width: Float, height: Float) {
        val dot = ImageGetter.getWhiteDotDrawable()
        val previous = Color(batch.color)
        batch.setColor(previous.r * color.r, previous.g * color.g, previous.b * color.b, previous.a * color.a)
        val r = minOf(radius, width / 2f, height / 2f)
        dot.draw(batch, x, y + r, width, height - 2f * r)
        for (i in 0 until 12) {
            val step = r / 12f
            val dy = r - (i + .5f) * step
            val inset = r - sqrt(r * r - dy * dy)
            dot.draw(batch, x + inset, y + i * step, width - inset * 2f, step)
            dot.draw(batch, x + inset, y + height - (i + 1) * step, width - inset * 2f, step)
        }
        batch.color = previous
    }
}


internal fun campaignNodeSurface(fill: Color, border: Color, square: Boolean, dashed: Boolean, stroke: Float) = object : BaseDrawable() {
    override fun draw(batch: Batch, x: Float, y: Float, width: Float, height: Float) {
        if (square) {
            campaignSurface(border, width * .27f).draw(batch, x, y, width, height)
            campaignSurface(fill, width * .27f).draw(batch, x + stroke, y + stroke, width - stroke * 2, height - stroke * 2)
            return
        }
        val previous = Color(batch.color)
        batch.setColor(previous.r * fill.r, previous.g * fill.g, previous.b * fill.b, previous.a)
        ImageGetter.getCircleDrawable().draw(batch, x, y, width, height)
        batch.setColor(previous.r * border.r, previous.g * border.g, previous.b * border.b, previous.a)
        val radius = (minOf(width, height) - stroke) / 2f
        for (i in 0 until 64) {
            if (dashed && i % 6 >= 3) continue
            val a = i * kotlin.math.PI * 2 / 64
            val b = (i + 1) * kotlin.math.PI * 2 / 64
            campaignStroke(batch, x + width / 2 + kotlin.math.cos(a).toFloat() * radius,
                y + height / 2 + kotlin.math.sin(a).toFloat() * radius,
                x + width / 2 + kotlin.math.cos(b).toFloat() * radius,
                y + height / 2 + kotlin.math.sin(b).toFloat() * radius, stroke)
        }
        batch.color = previous
    }
}

internal fun campaignStroke(batch: Batch, x: Float, y: Float, endX: Float, endY: Float, width: Float) {
    val dx = endX - x
    val dy = endY - y
    batch.draw(ImageGetter.getWhiteDotDrawable().region, x, y - width / 2, 0f, width / 2,
        sqrt(dx * dx + dy * dy), width, 1f, 1f, kotlin.math.atan2(dy, dx) * 180f / kotlin.math.PI.toFloat())
}
