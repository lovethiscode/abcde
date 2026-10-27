package com.unciv.app

import com.unciv.UncivGame
import com.unciv.logic.campaign.CampaignShareData
import com.unciv.logic.endless.EndlessShareData

/** Optional services supplied by a distribution, without coupling core to its account SDKs. */
interface AndroidRuntimeFeatures {
    val achievementsAvailable: Boolean get() = false
    val sharingAvailable: Boolean get() = false
    val lobbyServerUrl: String? get() = null
    val settingsAvailable: Boolean get() = false
    fun showSettings() {}
    fun initialize(game: UncivGame) {}
    fun resume() {}
    fun pause() {}
    fun render(game: UncivGame) {}
    fun showAchievements() {}
    fun showAchievementGuide() = showAchievements()
    fun shareCampaign(data: CampaignShareData) {}
    fun shareEndless(data: EndlessShareData) {}
    fun dispose() {}
}

object DefaultAndroidRuntimeFeatures : AndroidRuntimeFeatures
