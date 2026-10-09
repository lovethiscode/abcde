package com.unciv.ui.screens.mainmenuscreen

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.input.KeyboardBinding
import com.unciv.ui.components.input.onActivation
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.basescreen.RecreateOnResize

/** Temporary destination while campaign content and rules are discussed. */
class CampaignPlaceholderScreen : BaseScreen(), RecreateOnResize {
    init {
        val root = Table().apply {
            name = "ui-campaign-placeholder"
            setFillParent(true)
            background = ImageGetter.getWhiteDotDrawable().tint(Color.valueOf("e5edf0"))
            pad(36f)
            val back = "Back to home".toTextButton().apply {
                name = "ui-campaign-back"
                onActivation { game.popScreen() }
            }
            add(back).left().height(70f).row()
            add().expandY().row()
            add(ImageGetter.getImage("OtherIcons/Scenarios", Color.valueOf("24536b")))
                .size(90f).padBottom(24f).row()
            add("Original campaigns".toLabel(Color.valueOf("1b3542"), 44)).row()
            add("Campaigns are being prepared".toLabel(Color.valueOf("5c737c"), 26).apply { wrap = true })
                .width(this@CampaignPlaceholderScreen.stage.width * .72f).padTop(18f).row()
            add("Return home to continue your game or start free play.".toLabel(Color.valueOf("5c737c"), 22).apply { wrap = true })
                .width(this@CampaignPlaceholderScreen.stage.width * .72f).padTop(12f).row()
            add().expandY()
        }
        stage.addActor(root)
        globalShortcuts.add(KeyboardBinding.QuitMainMenu) { game.popScreen() }
    }

    override fun recreate(): BaseScreen = CampaignPlaceholderScreen()
}
