package com.unciv.ui.popups.options

import com.badlogic.gdx.Gdx
import com.unciv.GUI
import com.unciv.UncivGame
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.RulesetCache
import com.unciv.ui.components.extensions.areSecretKeysPressed
import com.unciv.ui.images.ImageGetter

enum class OptionsPopupPages(
    val label: String,
    internal val iconPath: String,
    internal val getContent: OptionsPopup.() -> OptionsPopupTab,
    internal val description: String = ""
) {
    Display("Display", "UnitPromotionIcons/Scouting", { DisplayTab(this) }, "Screen, graphics and map information"),
    Gameplay("Gameplay", "OtherIcons/Options", { GameplayTab(this) }, "Unit controls and turn pacing"),
    Automation("Automation", "OtherIcons/NationSwap", { AutomationTab(this) }, "Automatic behavior for cities, workers and units"),
    Language("Language", "FlagIcons/", { LanguageTab(this) }, "Choose your preferred language") {
        override fun getIcon(language: String) =
            if (ImageGetter.imageExists(iconPath + language)) ImageGetter.getImage(iconPath + language)
            else ImageGetter.getImage("OtherIcons/ExclamationMark")
    },
    Sound("Sound", "OtherIcons/Speaker", { SoundTab(this) }, "Sound effects, ambience and music"),
    Multiplayer("Multiplayer", "OtherIcons/Multiplayer", { MultiplayerTab(this) }, "Turn reminders, synchronization and servers") {
        override fun visible(withDebug: Boolean) =
            !UncivGame.isCurrentInitialized() || UncivGame.Current.platformCapabilities.onlineMultiplayer
    },
    Keys("Keys", "OtherIcons/Keyboard", { KeyBindingsTab(this, tabMinWidth - 40f) }) {   // 40 = padding
        override fun visible(withDebug: Boolean) = GUI.keyboardAvailable
    },
    Advanced("Advanced", "OtherIcons/Settings", { AdvancedTab(this) }, "Saves, fonts and interaction details"),
    ModCheck("Locate mod errors", "OtherIcons/Mods", { ModCheckTab(this) }) {
        override fun visible(withDebug: Boolean) = RulesetCache.size > BaseRuleset.entries.size
    },
    Debug("Debug", "OtherIcons/SecretOptions", { DebugTab(this) }) {
        override fun visible(withDebug: Boolean) = withDebug || Gdx.input.areSecretKeysPressed()
    },
    ;

    internal open fun visible(withDebug: Boolean) = true
    internal open fun getIcon(language: String) =
        if (iconPath.endsWith(".png")) ImageGetter.getExternalImage(iconPath)
        else ImageGetter.getImage(iconPath)

    companion object {
        operator fun get(ordinal: Int) = entries.first { it.ordinal == ordinal }
    }
}
