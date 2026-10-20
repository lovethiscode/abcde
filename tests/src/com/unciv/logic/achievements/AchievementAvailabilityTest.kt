package com.unciv.logic.achievements

import com.unciv.models.metadata.BaseRuleset
import com.unciv.testing.BaseTestRunner
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(BaseTestRunner::class)
class AchievementAvailabilityTest {
    private fun reason(f: AchievementTestFixture, id: String) =
        AchievementCatalog.currentGameIneligibility(AchievementCatalog.byId.getValue(id), f.game)

    @Test fun setupExclusionsExplainDifficultyOpponentsCivilizationAndReligion() {
        val f = AchievementTestFixture(aiCount = 1, difficulty = "Settler")
        assertNull(reason(f, "N02"))
        assertEquals("The difficulty is too low", reason(f, "N11"))
        f.state.difficulty = "Deity"
        assertEquals("Too few AI opponents", reason(f, "N11"))
        f.state.aiOpponents = 4
        assertEquals("A different civilization is required", reason(f, "N27"))
        assertEquals("One City Challenge is required", reason(f, "N38"))
        val vanilla = AchievementTestFixture(baseRuleset = BaseRuleset.Civ_V_Vanilla)
        for (id in listOf("N14", "N24", "N33")) assertEquals("Religion must be enabled", reason(vanilla, id))
        assertNull(reason(f, "N40"))
    }

    @Test fun mapGuidanceUsesActualDistinctWondersWithoutRevealingLocations() {
        val f = AchievementTestFixture()
        assertEquals("This map has fewer than three different natural wonders", reason(f, "N16"))
        for ((x, name) in listOf("Mount Fuji", "Mount Fuji", "Grand Mesa").withIndex())
            f.test.getTile(x, 0).naturalWonder = name
        assertNotNull(reason(f, "N16"))
        f.test.getTile(3, 0).naturalWonder = "Barringer Crater"
        assertNull(reason(f, "N16"))
        assertTrue(f.player.naturalWonders.isEmpty())
        f.game.gameParameters.noBarbarians = true
        assertEquals("Barbarians are disabled", reason(f, "N07"))
    }

    @Test fun cityStateGuidanceRequiresBothEnoughNationsAndEnoughTypes() {
        val f = AchievementTestFixture()
        for (id in listOf("N13", "N23", "N25")) assertNotNull(reason(f, id))
        val groups = f.test.ruleset.nations.values.filter { it.isCityState }.groupBy { it.cityStateType }.values
        val nations = groups.map { it.first() } + groups.flatMap { it.drop(1) }
        for (nation in nations.take(3)) f.test.addCiv(nation)
        assertNull(reason(f, "N13"))
        assertNotNull(reason(f, "N23"))
        for (nation in nations.drop(3).take(2)) f.test.addCiv(nation)
        assertNull(reason(f, "N23"))
        assertNull(reason(f, "N25"))
    }

    @Test fun incompatibleOneCityAndVictorySwitchesAreExplainedButCatalogIsIntact() {
        val f = AchievementTestFixture()
        f.state.oneCityChallenge = true
        for (id in listOf("N02", "N10", "N12", "N14", "N28", "N29"))
            assertEquals("One City Challenge prevents the required expansion", reason(f, id))
        f.state.enabledVictories = hashSetOf("Time")
        for (id in listOf("N17", "N18", "N19", "N20", "N36", "N38", "N39"))
            assertEquals("The required victory route is disabled", reason(f, id))
        assertNull(reason(f, "N01"))
        f.state.difficulty = "Deity"
        assertEquals("The required victory route is disabled", reason(f, "N37"))
        f.state.enabledVictories.add("Scientific")
        assertNull(reason(f, "N38"))
        assertEquals(54, f.state.availableIds.size)
        assertEquals(54, AchievementCatalog.byId.size)
    }

    @Test fun oldOrEndedGamesCannotSuggestFreshProgressButCollectionsStayVisible() {
        val f = AchievementTestFixture()
        f.state.ended = true
        assertEquals("This game no longer records achievements", reason(f, "N02"))
        assertNull(reason(f, "N40"))
        f.state.recordingVersion = 2
        assertEquals("Start a new eligible single-player game", reason(f, "N02"))
        assertNull(AchievementCatalog.currentGameIneligibility(AchievementCatalog.byId.getValue("N40"), null))
        assertEquals("Start a new eligible single-player game",
            AchievementCatalog.currentGameIneligibility(AchievementCatalog.byId.getValue("N02"), null))
    }
}
