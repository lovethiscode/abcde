package com.unciv.logic

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
class PolicyCodexTranslationsTest {
    private val languages = LocaleCode.getSupportedLanguages().toList()
    private val placeholders = Regex("\\[[^]]*]")
    private lateinit var keys: List<String>

    @Before fun setup() {
        TestGame()
        UncivGame.Current.translations.readAllLanguagesTranslation()
        keys = Gdx.files.internal("jsons/translations/template.properties").readString("UTF-8")
            .substringAfter("# Policy codex (C+A adaptive layout)\n")
            .lineSequence().filter { !it.startsWith('#') && " = " in it }
            .map { it.substringBefore(" = ") }.toList()
    }

    @After fun restoreLanguage() { UncivGame.Current.settings.language = "English" }

    @Test fun `policy entries cover every supported language without duplicate keys`() {
        assertEquals(37, keys.size)
        assertEquals(keys.size, keys.distinct().size)
        for (language in languages) {
            val file = Gdx.files.internal("jsons/translations/$language.properties")
            val translations = TranslationFileReader.read(file)
            val counts = file.readString("UTF-8").lineSequence()
                .filter { !it.startsWith('#') && " = " in it }
                .groupingBy { it.substringBefore(" = ") }.eachCount()
            for (key in keys) {
                val value = translations[key]
                assertFalse("$language missing $key", value.isNullOrBlank())
                assertEquals("$language duplicate $key", 1, counts[key])
                assertEquals("$language placeholders: $key", tokens(key), tokens(value!!))
            }
            if (language !in setOf("English", "Navajo"))
                assertNotEquals(language, "Policy codex", translations["Policy codex"])
        }
    }

    @Test fun `policy costs counts and branch parameters render in every language`() {
        for (language in languages) {
            UncivGame.Current.settings.language = language
            for (key in keys) {
                var number = 100
                val sample = placeholders.replace(key) { "[${++number}]" }
                val rendered = sample.tr(hideIcons = true)
                assertFalse("$language unresolved parameter: $rendered", rendered.any { it in "[]{}" })
                val renderedNumbers = Regex("\\p{Nd}+").findAll(rendered).map { match ->
                    match.value.map { Character.digit(it, 10) }.joinToString("")
                }.toList()
                for (parameter in 101..number)
                    assertTrue("$language lost parameter $parameter: $key", parameter.toString() in renderedNumbers)
            }
        }
    }

    @Test fun `Navajo policy interface uses the approved English fallback`() {
        for (key in keys) {
            UncivGame.Current.settings.language = "English"
            val english = key.tr(hideIcons = true)
            UncivGame.Current.settings.language = "Navajo"
            assertEquals(key, english, key.tr(hideIcons = true))
        }
    }

    private fun tokens(value: String) = placeholders.findAll(value).map { it.value }.sorted().toList()
}
