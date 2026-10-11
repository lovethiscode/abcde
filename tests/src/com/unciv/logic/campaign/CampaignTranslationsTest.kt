package com.unciv.logic.campaign

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
class CampaignTranslationsTest {
    private val languages = LocaleCode.getSupportedLanguages().toList()
    private val placeholders = Regex("\\[[^]]*]")
    private val han = Regex("[\\u4e00-\\u9fff]")
    private val cjk = setOf("Simplified_Chinese", "Traditional_Chinese", "Japanese", "Korean")

    @Before fun setup() {
        TestGame()
        UncivGame.Current.translations.readAllLanguagesTranslation()
    }

    @After fun restoreLanguage() { UncivGame.Current.settings.language = "English" }

    @Test fun `campaign entries exist in every supported language with intact placeholders`() {
        val keys = Gdx.files.internal("jsons/translations/template.properties").readString("UTF-8")
            .substringAfterLast("# Campaign mode").lines().filter { " = " in it }
            .map { it.substringBefore(" = ").replace("\\n", "\n") }
        assertTrue(keys.size > 500)
        for (language in languages) {
            val translations = TranslationFileReader.read(Gdx.files.internal("jsons/translations/$language.properties"))
            for (key in keys) {
                val value = translations[key]
                assertFalse("$language missing $key", value.isNullOrBlank())
                assertEquals("$language placeholders in $key", placeholders.findAll(key).map { it.value }.sorted().toList(),
                    placeholders.findAll(value!!).map { it.value }.sorted().toList())
                if (language !in cjk) assertFalse("$language has Chinese text: $value", han.containsMatchIn(value))
            }
        }
    }

    @Test fun `all packaged objectives hints and restrictions are translated without changing thresholds`() {
        val definitions = CampaignCatalog.definitions() + CampaignCatalog.historicalDefinitions()
        val numbers = Regex("\\p{Nd}+")
        for (language in languages) {
            UncivGame.Current.settings.language = language
            for (definition in definitions) {
                val texts = listOf(definition.title, definition.description) + definition.objectives +
                    definition.hints + definition.restrictions + definition.areas.map { it.title }
                for (text in texts) {
                    val translated = text.tr(hideIcons = true)
                    if (language !in cjk) assertFalse("$language ${definition.id}: $translated", han.containsMatchIn(translated))
                    fun digits(value: String) = numbers.findAll(value).map { match ->
                        match.value.map { Character.digit(it, 10) }.joinToString("")
                    }.sorted().toList()
                    if (text in definition.objectives)
                        assertTrue("$language thresholds: $text => $translated", digits(translated).toSet().containsAll(digits(text)))
                }
            }
        }
    }

    @Test fun `live progress for every scenario renders in every language`() {
        for (definition in CampaignCatalog.definitions() + CampaignCatalog.historicalDefinitions()) {
            val game = CampaignCatalog.newAttempt(definition.id)
            game.campaign!!.completedRounds = 2
            UncivGame.Current.gameInfo = game
            val evaluation = CampaignRules.evaluate(game)
            for (language in languages) {
                UncivGame.Current.settings.language = language
                for (detail in evaluation.details + evaluation.failureReason) {
                    val text = detail.trCampaign()
                    assertFalse("$language ${definition.id}: $text", text.any { it in "[]{}" })
                    if (language !in cjk) assertFalse("$language ${definition.id}: $text", han.containsMatchIn(text))
                }
            }
        }
    }

    @Test fun `old rendered result text translates without mutating the saved result`() {
        val game = CampaignCatalog.newAttempt(CivilWarCampaign.id)
        val state = game.campaign!!
        val oldDetails = listOf(
            "里士满：尚未占领；华盛顿：仍由联邦控制",
            "战役第 3 回合 / 50；效率要求不晚于第 30 回合",
            "部队损失 1 / 6\n战斗阵亡 1 · 主动解散 0 · 欠费解散 0 · 被俘或转移 0 · 其他移除 0",
            "文字科技：未研究\n开局首都的图书馆：未建成",
            "收入与幸福度已连续达标 2 / 3 轮\n净收入：3；幸福度：4；金币：90 / 100",
            "\ue000纪念碑: 尚未生产完成\n\ue001斥候: 已生产完成"
        )
        state.resultStars = 2
        state.resultDetails.addAll(oldDetails)
        for (language in languages) {
            UncivGame.Current.settings.language = language
            for (detail in state.resultDetails) {
                val text = detail.trCampaign()
                assertFalse("$language old result: $text", text.any { it in "[]{}" })
                if (language !in cjk) assertFalse("$language old result: $text", han.containsMatchIn(text))
            }
        }
        assertEquals(oldDetails, state.resultDetails)
        assertEquals(2, state.resultStars)
        UncivGame.Current.settings.language = "English"
        assertTrue(oldDetails[1].trCampaign().contains("50"))
        assertTrue(oldDetails[2].trCampaign().contains("1 / 6"))
        assertTrue(oldDetails[5].trCampaign().startsWith("\ue000"))
    }

    @Test fun `Navajo campaign descriptions use the requested English translation`() {
        for (definition in CampaignCatalog.definitions() + CampaignCatalog.historicalDefinitions()) {
            val texts = listOf(definition.description) + definition.objectives + definition.hints + definition.restrictions
            for (text in texts) {
                UncivGame.Current.settings.language = "English"
                val english = text.tr(hideIcons = true)
                UncivGame.Current.settings.language = "Navajo"
                assertEquals(english, text.tr(hideIcons = true))
            }
        }
    }
}
