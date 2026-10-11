package com.unciv.logic.endless

import com.badlogic.gdx.Gdx
import com.unciv.UncivGame
import com.unciv.models.metadata.LocaleCode
import com.unciv.models.translations.TranslationFileReader
import com.unciv.models.translations.tr
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(BaseTestRunner::class)
class EndlessTranslationsTest {
    private val languages = LocaleCode.getSupportedLanguages().toList()
    private val placeholders = Regex("\\[[^]]*]")
    private val numbers = Regex("\\p{Nd}+")
    private lateinit var keys: List<String>

    @Before fun setup() {
        TestGame()
        UncivGame.Current.translations.readAllLanguagesTranslation()
        keys = Gdx.files.internal("jsons/translations/template.properties").readString("UTF-8")
            .substringAfter("# Endless survival\n").substringBefore("# End endless survival")
            .lines().filter { " = " in it }.map { it.substringBefore(" = ") }
    }

    @After fun restoreLanguage() { UncivGame.Current.settings.language = "English" }

    @Test fun `endless entries cover the same supported languages as campaigns`() {
        assertEquals(122, keys.size)
        assertEquals(keys.size, keys.distinct().size)
        for (language in languages) {
            val file = Gdx.files.internal("jsons/translations/$language.properties")
            val translations = TranslationFileReader.read(file)
            val counts = file.readString("UTF-8").lines().filter { !it.startsWith('#') && " = " in it }
                .groupingBy { it.substringBefore(" = ") }.eachCount()
            for (key in keys) {
                val value = translations[key]
                assertFalse("$language missing $key", value.isNullOrBlank())
                assertEquals("$language duplicate $key", 1, counts[key])
                assertEquals("$language placeholders: $key", tokens(key), tokens(value!!))
                assertEquals("$language rule numbers: $key", digits(key), digits(value))
                if (language !in setOf("Simplified_Chinese", "Traditional_Chinese", "Japanese", "Korean"))
                    assertFalse("$language has untranslated Chinese: $value", Regex("[\\u4e00-\\u9fff]").containsMatchIn(value))
            }
            if (language !in setOf("English", "Navajo"))
                assertNotEquals(language, "Endless survival", translations["Endless survival"])
        }
    }

    @Test fun `all endless parameters render in every supported language`() {
        for (language in languages) {
            UncivGame.Current.settings.language = language
            for (key in keys) {
                var number = 10
                val sample = placeholders.replace(key) { "[${++number}]" }
                val translated = sample.tr(hideIcons = true)
                assertFalse("$language unresolved parameter: $translated", translated.any { it in "[]{}" })
                assertEquals("$language parameter values: $key", digits(sample), digits(translated))
            }
            val result = "[17] rounds · [9] waves · [24] kills · [${"Ended voluntarily".tr()}]".tr(hideIcons = true)
            assertFalse("$language unresolved result: $result", result.any { it in "[]{}" })
            assertEquals(listOf("17", "24", "9"), digits(result))
        }
    }

    @Test fun `Navajo uses the same English policy as campaign translations`() {
        for (key in keys) {
            UncivGame.Current.settings.language = "English"
            val english = key.tr(hideIcons = true)
            UncivGame.Current.settings.language = "Navajo"
            assertEquals(key, english, key.tr(hideIcons = true))
        }
    }

    private fun tokens(value: String) = placeholders.findAll(value).map { it.value }.sorted().toList()

    private fun digits(value: String) = numbers.findAll(value).map { match ->
        match.value.map { Character.digit(it, 10) }.joinToString("")
    }.sorted().toList()
}
