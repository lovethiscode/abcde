package com.unciv.ui.screens.mainmenuscreen

import com.badlogic.gdx.Gdx
import com.unciv.UncivGame
import com.unciv.models.metadata.GameSettings
import com.unciv.models.metadata.LocaleCode
import com.unciv.models.translations.TranslationFileReader
import com.unciv.models.translations.getPlaceholderText
import com.unciv.models.translations.tr
import com.unciv.testing.BaseTestRunner
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(BaseTestRunner::class)
class HomeMenuLocalizationTest {
    private val keys = listOf("Build a city. Shape your world.", "Every step opens a new world.",
        "Campaign challenges", "Designed situations. Your own strategy.", "Enter campaigns", "Resume",
        "Your latest autosave", "Start new game", "Choose your civilization, map and rules", "Free play",
        "Quickstart or customize a singleplayer game", "Endless survival", "Defense hall", "Load game",
        "Choose a save and continue your civilization", "Multiplayer",
        "Create a room and share its invitation, or join a friend's game.", "Civilopedia", "Mods",
        "Achievements", "Map editor", "Options", "Quickstart", "Start a game with the default settings",
        "Singleplayer", "Turn [113]")

    @Test fun homeAndFreePlayCopyCoverEverySupportedLanguage() {
        for (language in LocaleCode.getSupportedLanguages()) {
            val file = Gdx.files.internal("jsons/translations/$language.properties")
            val entries = TranslationFileReader.read(file)
            val byKey = entries.entries.associateBy { it.key.getPlaceholderText() }
            val counts = file.readString("UTF-8").lineSequence()
                .filter { !it.startsWith('#') && it.contains(" = ") }
                .map { it.substringBefore(" = ").getPlaceholderText() }.groupingBy { it }.eachCount()
            UncivGame.Current = UncivGame().apply { settings = GameSettings().apply { this.language = language } }
            UncivGame.Current.translations.createTranslations(language, entries, noDiacritics = true)
            for (key in keys) {
                val entry = byKey[key.getPlaceholderText()]
                assertNotNull("$language missing $key", entry)
                assertFalse("$language blank $key", entry!!.value.isBlank())
                assertEquals("$language duplicate $key", 1, counts[key.getPlaceholderText()])
                assertFalse("$language unresolved $key", key.tr(hideIcons = true).any { it in "[]{}" })
                if (language !in listOf("English", "Navajo") && key in listOf(
                        "Every step opens a new world.", "Build a city. Shape your world.", "Achievements"))
                    assertNotEquals("$language English placeholder for $key", key, entry.value)
            }
            val turn = "Turn [113]".tr(hideIcons = true).filter { Character.isDigit(it) }
                .map { Character.digit(it, 10) }.joinToString("")
            assertEquals("$language lost autosave turn", "113", turn)
        }
    }
}
