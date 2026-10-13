package com.unciv.ui.screens.civilopediascreen

import com.badlogic.gdx.utils.Json
import com.unciv.UncivGame
import com.unciv.models.ruleset.Ruleset
import com.unciv.models.ruleset.unit.BaseUnit
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import com.unciv.ui.components.fonts.Fonts
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.lang.reflect.Modifier

@RunWith(GdxTestRunner::class)
class CivilopediaArticleTest {
    private lateinit var game: TestGame

    @Before
    fun setup() {
        game = TestGame()
        UncivGame.Current.settings.unitSet = ""
    }

    private fun assertEveryLineRetained(article: CivilopediaArticle) {
        val displayed = article.introduction + article.sections.values.flatten() + article.conclusion
        assertEquals(article.originalLines.size, displayed.size)
        for (line in article.originalLines)
            assertEquals("Lost or duplicated original line: $line",
                article.originalLines.count { it === line }, displayed.count { it === line })
    }

    @Test
    fun `all categories retain every assembled paragraph image and link`() {
        val covered = mutableSetOf<CivilopediaCategories>()
        var count = 0
        for (category in CivilopediaCategories.entries) {
            val entries = category.getCategoryIterator(game.ruleset, null)
            if (entries.isNotEmpty()) covered += category
            for (entry in entries) {
                val lines = entry.assembleCivilopediaText(game.ruleset).civilopediaText
                assertEveryLineRetained(CivilopediaArticle(lines))
                count++
            }
        }
        assertEquals(CivilopediaCategories.entries.toSet(), covered)
        assertTrue("Expected the complete ruleset, not just prototype samples", count > 500)
        println("Civilopedia content coverage: ${covered.size} categories, $count entries")
    }

    @Test
    fun `custom formatting and duplicate numeric prose are never treated as generated stats`() {
        val unit = game.ruleset.units.getValue("Swordsman")
        val duplicateStat = FormattedLine("14${Fonts.strength}, 2${Fonts.movement}", color = "#abc", starred = true)
        val picture = FormattedLine(extraImage = "ExtraImages/custom", imageSize = 1200f, centered = true)
        val linked = FormattedLine("Custom linked paragraph", "Technology/Iron Working", indent = 2, iconCrossed = true)
        val external = FormattedLine("External source", "https://example.org", padding = 14f, header = 4)
        val originalText = unit.civilopediaText
        val article = try {
            unit.civilopediaText = listOf(duplicateStat, picture, linked, external)
            CivilopediaArticle(unit.assembleCivilopediaText(game.ruleset).civilopediaText)
        } finally {
            unit.civilopediaText = originalText
        }
        assertEveryLineRetained(article)
        assertTrue(article.introduction.any { it === duplicateStat })
        assertTrue(article.introduction.any { it === picture })
        assertTrue(article.conclusion.any { it === linked })
        assertTrue(article.conclusion.any { it === external })
        assertFalse(article.sections["stats"]!!.any { it === duplicateStat })
    }

    @Test
    fun `swordsman values and all original relationship meanings remain available`() {
        val lines = game.ruleset.units.getValue("Swordsman").getCivilopediaTextLines(game.ruleset)
        val stats = lines.flatMap { it.civilopediaStats }
        assertEquals(listOf(14, 2, 75, 390), stats.map { it.value })
        assertEquals(listOf(Fonts.strength, Fonts.movement, Fonts.production, Fonts.gold), stats.map { it.icon })
        assertTrue(lines.any { it.text == "14${Fonts.strength}, 2${Fonts.movement}" })
        assertTrue(lines.any { it.text == "{Cost}: 75${Fonts.production}/390${Fonts.gold}" })
        for ((text, target) in listOf(
            "Required tech: [Iron Working]" to "Technology/Iron Working",
            "Can upgrade from [Warrior]" to "Unit/Warrior",
            "Upgrades to [Longswordsman]" to "Unit/Longswordsman",
            "Obsolete with [Steel]" to "Technology/Steel",
            "Legion" to "Unit/Legion",
            "Mohawk Warrior" to "Unit/Mohawk Warrior"
        )) assertTrue("Missing $text -> $target", lines.any { it.text == text && it.link == target })
        assertTrue(lines.any { it.link == "Resources/Iron" && it.color == "#F42" })
    }

    @Test
    fun `air ranged and zero cost units keep applicable attributes only`() {
        val fighter = game.ruleset.units.getValue("Fighter").getCivilopediaTextLines(game.ruleset)
        assertFalse(fighter.flatMap { it.civilopediaStats }.any { it.icon == Fonts.movement })
        assertTrue(fighter.any { it.text.startsWith("Air Intercept Range:") })
        val archer = game.ruleset.units.getValue("Archer").getCivilopediaTextLines(game.ruleset)
        assertTrue(archer.flatMap { it.civilopediaStats }.any { it.icon == Fonts.rangedStrength && it.value == 7 })
        assertTrue(archer.flatMap { it.civilopediaStats }.any { it.icon == Fonts.range && it.value == 2 })
        val unit = BaseUnit().apply {
            name = "Free unit"
            unitType = "Melee"
            cost = 0
            setRuleset(game.ruleset)
        }
        assertFalse(unit.getCivilopediaTextLines(game.ruleset).flatMap { it.civilopediaStats }
            .any { it.icon == Fonts.production || it.icon == Fonts.gold })
    }

    @Test
    fun `single mod search preserves references to resources from the base ruleset`() {
        val unit = game.ruleset.units.getValue("Swordsman")
        val lines = unit.getCivilopediaTextLines(Ruleset())
        assertTrue(lines.any { it.link == "Resources/Iron" && it.color == "#F42" })
        assertTrue(lines.any { it.link == "Technology/Iron Working" })
    }

    @Test
    fun `presentation hints are transient and default on ruleset JSON load`() {
        for (name in listOf("civilopediaSection", "civilopediaStats"))
            assertTrue(Modifier.isTransient(FormattedLine::class.java.getDeclaredField(name).modifiers))
        val restored = Json().fromJson(FormattedLine::class.java,
            """{"text":"original","link":"Unit/Warrior"}""")
        assertEquals("original", restored.text)
        assertEquals("Unit/Warrior", restored.link)
        assertNull(restored.civilopediaSection)
        assertTrue(restored.civilopediaStats.isEmpty())
    }
}
