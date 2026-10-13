package com.unciv.ui.screens.newgamescreen

import com.badlogic.gdx.Gdx
import com.unciv.models.translations.TranslationFileReader
import com.unciv.models.translations.squareBraceRegex
import com.unciv.testing.BaseTestRunner
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(BaseTestRunner::class)
class GameSetupOverviewLocalizationTest {
    @Test fun overviewCopyAndOpponentCountAreLocalized() {
        val messages = listOf("Quick skirmish", "Standard journey", "Custom setup", "Game pace", "Game rules",
            "Room", "Room rules", "[count] AI opponents", "A new civilization begins with you.",
            "Choose a setup, then shape your world.", "Choose the map and terrain for your game.",
            "Choose your civilization and opponents.", "Set the pace and victory conditions.",
            "This changes the map, pace and opponents. Other rules are kept.",
            "Changes are shared with everyone in the room.", "Only the host can change room rules.")
        for (language in listOf("English", "Simplified_Chinese", "Traditional_Chinese", "Spanish", "French",
            "German", "Brazilian_Portuguese", "Russian", "Japanese", "Korean")) {
            val file = Gdx.files.internal("jsons/translations/$language.properties")
            val entries = TranslationFileReader.read(file)
            for (message in messages) {
                val translated = entries[message]
                assertFalse("$language: $message", translated.isNullOrBlank())
                assertEquals("$language: $message",
                    squareBraceRegex.findAll(message).map { it.value }.toList(),
                    squareBraceRegex.findAll(translated!!).map { it.value }.toList())
                assertEquals("Duplicate $language: $message", 1,
                    file.readString("UTF-8").lineSequence().count { it.startsWith("$message = ") })
            }
        }
    }
}
