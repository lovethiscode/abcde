package com.unciv.ui.screens.campaign

import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.unciv.models.skins.ExpeditionBlueTheme as Theme
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.images.ImageGetter

/** Separate actors keep earned stars gold without tinting titles or status text. */
internal fun campaignStars(earned: Int, size: Float = 18f) = Table().apply {
    for (i in 0..2) {
        if (i < earned) add(ImageGetter.getImage("OtherIcons/Capital").apply { color = Theme.gold })
            .size(size).padRight(3f)
        else add("☆".toLabel(Theme.muted, size.toInt())).size(size).padRight(3f)
    }
}
