package com.unciv.ui.screens.mainmenuscreen

import com.unciv.ui.components.fonts.UiTypography
import com.unciv.ui.components.fonts.TextRole
import com.unciv.ui.components.input.keyShortcuts
import com.unciv.ui.components.widgets.UiNavigation
import com.badlogic.gdx.graphics.Color
import com.unciv.models.skins.ExpeditionBlueTheme
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.unciv.ui.components.extensions.toLabel
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
            background = ImageGetter.getWhiteDotDrawable().tint(ExpeditionBlueTheme.color(Color.valueOf("e5edf0"), ExpeditionBlueTheme.background))
            pad(36f)
            val back = UiNavigation.backButton().apply {
                name = "ui-campaign-back"
                onActivation { game.popScreen() }
                keyShortcuts.add(com.unciv.ui.components.input.KeyCharAndCode.BACK)
            }
            add(back).left().height(70f).row()
            add().expandY().row()
            add(ImageGetter.getImage("OtherIcons/Scenarios", ExpeditionBlueTheme.color(Color.valueOf("24536b"), ExpeditionBlueTheme.gold)))
                .size(90f).padBottom(24f).row()
            add("Original campaigns".toLabel(ExpeditionBlueTheme.color(Color.valueOf("1b3542"), ExpeditionBlueTheme.text), 44)).row()
            add("Campaigns are being prepared".toLabel(ExpeditionBlueTheme.color(Color.valueOf("5c737c"), ExpeditionBlueTheme.muted), UiTypography.size(TextRole.PageTitle, legacySize = 26)).apply { wrap = true })
                .width(this@CampaignPlaceholderScreen.stage.width * .72f).padTop(18f).row()
            add("Return home to continue your game or start free play.".toLabel(ExpeditionBlueTheme.color(Color.valueOf("5c737c"), ExpeditionBlueTheme.muted), UiTypography.size(TextRole.SectionTitle, legacySize = 22)).apply { wrap = true })
                .width(this@CampaignPlaceholderScreen.stage.width * .72f).padTop(12f).row()
            add().expandY()
        }
        stage.addActor(root)
        globalShortcuts.add(KeyboardBinding.QuitMainMenu) { game.popScreen() }
    }

    override fun recreate(): BaseScreen = CampaignPlaceholderScreen()
}
