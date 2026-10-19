@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package com.unciv.ui.screens.savescreens

import com.badlogic.gdx.Gdx
import com.unciv.UncivGame
import com.unciv.models.metadata.GameSettings
import com.unciv.models.metadata.LocaleCode
import com.unciv.models.translations.TranslationFileReader
import com.unciv.models.translations.getPlaceholderText
import com.unciv.models.translations.getPlaceholderParameters
import com.unciv.models.translations.tr
import com.unciv.testing.BaseTestRunner
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale
import java.util.TimeZone

@RunWith(BaseTestRunner::class)
class RefactoredPageLocalizationTest {
    private val keys = javaClass.getResourceAsStream("/refactored-page-translation-keys.txt")!!
        .bufferedReader(Charsets.UTF_8).use { reader ->
            reader.readLines().filter { it.isNotBlank() && !it.startsWith('#') }
                .map { it.replace("\\n", "\n") }
        }

    @Test fun pageCopyCoversEverySupportedLanguageWithoutConflictingKeysOrLostParameters() {
        assertEquals(keys.size, keys.map { it.getPlaceholderText() }.distinct().size)
        for (language in LocaleCode.getSupportedLanguages()) {
            val file = Gdx.files.internal("jsons/translations/$language.properties")
            val entries = TranslationFileReader.read(file)
            val byKey = entries.entries.associateBy { it.key.getPlaceholderText() }
            val counts = file.readString("UTF-8").lineSequence()
                .filter { !it.startsWith('#') && it.contains(" = ") }
                .map { it.substringBefore(" = ").replace("\\n", "\n").getPlaceholderText() }
                .groupingBy { it }.eachCount()
            for (key in keys) {
                val entry = byKey[key.getPlaceholderText()]
                assertNotNull("$language missing $key", entry)
                assertFalse("$language blank $key", entry!!.value.isBlank())
                assertEquals("$language duplicate $key", 1, counts[key.getPlaceholderText()])
                assertEquals("$language changed parameters for $key",
                    entry.key.getPlaceholderParameters().sorted().toList(),
                    entry.value.getPlaceholderParameters().sorted().toList())
            }
        }
    }

    @Test fun countsAndDownloadProgressRenderUsingTheSelectedLanguage() {
        val samples = listOf("[3] mods", "Local saves [3]", "[3] multiplayer matches",
            "[2] cities · [12] population", "Downloading unupdated mods - [2]/[3]",
            "Downloaded [Example Mod]", "Updating mods: [Example Mod]")
        for (language in LocaleCode.getSupportedLanguages()) {
            useLanguage(language)
            val entries = TranslationFileReader.read(Gdx.files.internal("jsons/translations/$language.properties"))
            for (sample in samples) {
                assertTrue("$language missing runtime key $sample",
                    entries.keys.any { it.getPlaceholderText() == sample.getPlaceholderText() })
                val rendered = sample.tr(hideIcons = true)
                assertFalse("$language unresolved $rendered", rendered.any { it in "[]{}" })
                for (number in Regex("\\d+").findAll(sample).map { it.value }) {
                    val digits = rendered.filter { Character.isDigit(it) }
                        .map { Character.digit(it, 10) }.joinToString("")
                    assertTrue("$language lost $number in $rendered", number in digits)
                }
                if ("Example Mod" in sample) assertTrue("$language changed mod name", "Example Mod" in rendered)
            }
        }
    }

    @Test fun chineseSettingsDoNotFallBackToEnglishAndHistoricalSaveDatesStayUnambiguous() {
        useLanguage("Simplified_Chinese")
        assertEquals("按距离循环选择空闲单位", "Cycle idle units by distance".tr(hideIcons = true))
        assertEquals("自动为新城市分配专家", "Auto-assign specialists in new cities".tr(hideIcons = true))
        val previousLocale = Locale.getDefault()
        val previousTimeZone = TimeZone.getDefault()
        try {
            Locale.setDefault(Locale.US)
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            useLanguage("German")
            val timestamp = 1_593_561_600_000L
            assertEquals("2020-07-01", compactSaveDate(timestamp))
        } finally {
            Locale.setDefault(previousLocale)
            TimeZone.setDefault(previousTimeZone)
        }
    }

    private fun useLanguage(language: String) {
        UncivGame.Current = UncivGame()
        UncivGame.Current.settings = GameSettings().apply { this.language = language }
        UncivGame.Current.translations.createTranslations(language,
            TranslationFileReader.read(Gdx.files.internal("jsons/translations/$language.properties")), noDiacritics = true)
    }
}
