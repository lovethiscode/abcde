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
class EndlessStageTranslationsTest {
    private val parameters = Regex("\\[[^]]*]")
    private lateinit var keys: List<String>
    @Before fun setup() {
        TestGame(); UncivGame.Current.translations.readAllLanguagesTranslation()
        keys = Gdx.files.internal("jsons/translations/template.properties").readString("UTF-8")
            .substringAfter("# Endless survival v2\n").substringBefore("# End endless survival v2")
            .lines().filter { " = " in it }.map { it.substringBefore(" = ") }
    }
    @After fun restore() { UncivGame.Current.settings.language = "English" }

    @Test fun `new rules text covers all forty seven languages without changing legacy keys`() {
        assertEquals(21,keys.size); assertEquals(keys.size,keys.distinct().size)
        val languages = LocaleCode.getSupportedLanguages().toList(); assertEquals(47,languages.size)
        for (language in languages) {
            val file = Gdx.files.internal("jsons/translations/$language.properties")
            val entries = TranslationFileReader.read(file)
            val counts = file.readString("UTF-8").lines().filter { !it.startsWith('#') && " = " in it }
                .groupingBy { it.substringBefore(" = ") }.eachCount()
            for (key in keys) {
                val value = entries[key] ?: error("$language missing $key")
                assertTrue(value.isNotBlank()); assertEquals("$language duplicate $key",1,counts[key])
                assertEquals(parameters.findAll(key).map { it.value }.sorted().toList(),parameters.findAll(value).map { it.value }.sorted().toList())
                assertEquals(Regex("\\d+").findAll(key).map { it.value }.sorted().toList(),Regex("\\d+").findAll(value).map { it.value }.sorted().toList())
            }
        }
    }

    @Test fun `stage time progress and queue values render in all languages and Navajo follows English`() {
        for (language in LocaleCode.getSupportedLanguages()) {
            UncivGame.Current.settings.language = language
            for (key in keys) {
                var number = 10
                val sample = parameters.replace(key) { "[${++number}]" }
                val translated = sample.tr(hideIcons = true)
                assertFalse("$language unresolved $translated",translated.any { it in "[]{}" })
                assertEquals("$language $key => $translated",digits(sample),digits(translated))
                if (language == "Navajo") {
                    UncivGame.Current.settings.language = "English"
                    assertEquals(sample.tr(hideIcons = true),translated)
                    UncivGame.Current.settings.language = language
                }
            }
        }
    }

    private fun digits(value: String) = Regex("\\p{Nd}+").findAll(value).map { match ->
        match.value.map { Character.digit(it,10) }.joinToString("")
    }.sorted().toList()
}
