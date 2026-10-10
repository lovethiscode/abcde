package com.unciv.ui.screens

import com.badlogic.gdx.Application.ApplicationType
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.scenes.scene2d.ui.Image
import com.badlogic.gdx.utils.viewport.ScreenViewport
import com.unciv.ui.components.extensions.center
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.screens.basescreen.BaseScreen

class GameStartScreen : BaseScreen() {
    private val isIOS = Gdx.app.type == ApplicationType.iOS
    private val startupTextures = mutableListOf<Texture>()
    private var background: Image? = null
    private var mark: Image? = null

    init {
        if (isIOS) {
            // Branding uses UIKit points, independent of the player's game UI scale and cutout setting.
            stage.viewport = ScreenViewport()
            background = startupImage("LaunchBackground@3x.png")
            mark = startupImage("LaunchMark@3x.png")
            resize(Gdx.graphics.width, Gdx.graphics.height)
        } else {
            val logoImage = ImageGetter.getExternalImage("banner.png")
            logoImage.center(stage)
            stage.addActor(logoImage)
        }
    }

    private fun startupImage(name: String): Image {
        val texture = Texture(Gdx.files.internal("ExtraImages/$name"))
        texture.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear)
        startupTextures.add(texture)
        return Image(texture).also { stage.addActor(it) }
    }

    override fun resize(width: Int, height: Int) {
        if (!isIOS) return super.resize(width, height)
        if (width <= 0 || height <= 0) return
        stage.viewport.update(width, height, true)
        background?.setBounds(0f, 0f, stage.width, stage.height)
        val markWidth = minOf(252f, stage.width * .8f, stage.height * .72f * 252f / 214f)
        mark?.setSize(markWidth, markWidth * 214f / 252f)
        mark?.center(stage)
    }

    override fun dispose() {
        super.dispose()
        startupTextures.forEach { it.dispose() }
    }
}
