package com.unciv.logic.achievements

import com.unciv.logic.map.mapunit.PossibleUpgrade
import com.unciv.logic.map.mapunit.UpgradeAvailability
import com.unciv.models.Counter

import com.unciv.json.json
import com.unciv.logic.GameInfo
import com.unciv.logic.civilization.PlayerType
import com.unciv.logic.map.MapType
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(BaseTestRunner::class)
class AchievementFoundationTest {
    private fun newGame(aiCount: Int = 4): TestGame = TestGame().apply {
        makeHexagonalMap(3)
        setDifficulty("Emperor")
        addCiv(ruleset.nations.getValue("Rome"), isPlayer = true)
        listOf("Greece", "China", "Egypt", "America").take(aiCount).forEach { addCiv(ruleset.nations.getValue(it)) }
        gameInfo.gameParameters.victoryTypes.addAll(AchievementCatalog.victoryRoutes)
    }

    private fun state(testGame: TestGame) = AchievementGameState.create(testGame.gameInfo, true, false)!!

    @Test fun catalogExtendsOriginalCluesWithIndependentLocalOnlyRules() {
        assertTrue(AchievementCatalog.localPreview)
        assertEquals(4, AchievementCatalog.version)
        assertEquals(4, AchievementCatalog.recordingVersion)
        assertEquals((1..40).map { "N%02d".format(it) }, AchievementCatalog.definitions.filter { it.id in AchievementCatalog.originalIds }.map { it.id })
        assertEquals(listOf(6, 12, 14, 8), AchievementTier.entries.map { tier ->
            AchievementCatalog.definitions.count { it.tier == tier && it.id in AchievementCatalog.originalIds }
        })
        assertEquals(AchievementCatalog.originalIds - setOf("N01", "N02", "N03", "N05", "N06", "N09", "N40"),
            AchievementCatalog.definitions.filter { it.minimumDifficulty != null }.map { it.id }.toSet())
        assertEquals(setOf("Egypt", "Rome", "China", "Persia", "Inca", "Mongolia"), AchievementCatalog.civilizationAchievements.values.toSet())
        assertEquals(8, AchievementCatalog.civilizationAchievements.size)
        assertEquals(AchievementCatalog.originalIds, AchievementCatalog.reportableIds)
        assertTrue(AchievementCatalog.legacyIds.none { it in AchievementCatalog.reportableIds })
        assertEquals("com.aishuati.unciv.achievement.n01", AchievementCatalog.gameCenterId("N01"))
        assertThrows(IllegalArgumentException::class.java) { AchievementCatalog.gameCenterId("A01") }
    }

    @Test fun difficultyUsesBuiltInOrderAndRejectsUnknownValues() {
        assertTrue(AchievementCatalog.meetsDifficulty("Deity", "Prince"))
        assertFalse(AchievementCatalog.meetsDifficulty("King", "Emperor"))
        assertFalse(AchievementCatalog.meetsDifficulty("王子", null))
        assertFalse(AchievementCatalog.meetsDifficulty("Deity", "Unknown"))
        assertTrue(AchievementCatalog.meetsDifficulty("Settler", null))
    }

    @Test fun newSinglePlayerGameFreezesCatalogAndActualOpponents() {
        val game = newGame()
        game.gameInfo.gameParameters.numberOfCityStates = 8
        val state = state(game)
        assertEquals(game.gameInfo.gameId, state.gameId)
        assertEquals("Rome", state.civilization)
        assertEquals(4, state.aiOpponents)
        assertEquals(0, state.cityStates) // Real placed civilizations, not the requested setting.
        assertEquals(AchievementCatalog.byId.keys, state.availableIds)
        assertNull(AchievementCatalog.ineligibility(AchievementCatalog.byId.getValue("N28"), state))
    }

    @Test fun globalInvalidSettingsNeverProduceARecord() {
        val mutations: List<(GameInfo) -> Unit> = listOf(
            { it.gameParameters.mods.add("Example mod") },
            { it.gameParameters.baseRuleset = "Example base mod" },
            { it.gameParameters.isOnlineMultiplayer = true },
            { it.civilizations[1].playerType = PlayerType.Human },
            { it.civilizations[0].playerType = PlayerType.AI },
            { it.gameParameters.startingEra = "Industrial era" },
            { it.gameParameters.godMode = true },
            { it.simulateUntilWin = true },
            { it.difficulty = "Custom" },
            { it.tileMap.mapParameters.type = MapType.empty }
        )
        for ((index, mutate) in mutations.withIndex()) {
            val game = newGame().gameInfo
            mutate(game)
            assertNull("Invalid case $index", AchievementGameState.create(game, true, false))
        }
        val game = newGame().gameInfo
        assertNull(AchievementGameState.create(game, false, false))
        assertNull(AchievementGameState.create(game, true, true))
        assertNull(AchievementGameState.create(newGame(0).gameInfo, true, false))
    }

    @Test fun ordinaryAchievementsAcceptLowestDifficultyOneOpponentAndDisabledVictoryRoutes() {
        val game = newGame(1)
        game.gameInfo.difficulty = "Settler"
        game.gameInfo.gameParameters.victoryTypes.clear()
        val state = state(game)
        for (definition in AchievementCatalog.definitions.filter { it.minimumDifficulty == null && it.mode == "free" }) {
            state.civilization = definition.civilization ?: "Rome"
            assertNull(definition.id, AchievementCatalog.ineligibility(definition, state))
        }
    }

    @Test fun everyAdvancedClueChecksItsOwnOpponentAndDifficultyGate() {
        for (id in AchievementCatalog.definitions.filter { it.minimumDifficulty != null }.map { it.id }) {
            val definition = AchievementCatalog.byId.getValue(id)
            val state = state(newGame(2)).apply { difficulty = "Deity"; oneCityChallenge = true }
            assertNotNull(id, AchievementCatalog.ineligibility(definition, state))
            state.aiOpponents = definition.minimumOpponents
            state.civilization = definition.civilization ?: "Rome"
            state.enabledVictories.clear()
            state.enabledVictories.add("Scientific")
            assertNull(id, AchievementCatalog.ineligibility(definition, state))
            state.difficulty = AchievementCatalog.difficulties[AchievementCatalog.difficulties.indexOf(definition.minimumDifficulty) - 1]
            assertNotNull(id, AchievementCatalog.ineligibility(definition, state))
        }
    }

    @Test fun civilizationOneCityAndNewCatalogIdsAreNotInferred() {
        val state = state(newGame()).apply { difficulty = "Emperor" }
        for (id in listOf("N27", "N34", "N35", "N38", "N37"))
            assertNotNull(id, AchievementCatalog.ineligibility(AchievementCatalog.byId.getValue(id), state))
        state.oneCityChallenge = true
        assertNull(AchievementCatalog.ineligibility(AchievementCatalog.byId.getValue("N38"), state))
        assertNotNull(AchievementCatalog.ineligibility(AchievementDefinition("N41", AchievementTier.Simple), state))
    }

    @Test fun missingUnitOrDisabledReligionIsInapplicable() {
        val state = state(newGame()).apply { civilization = "China"; availableUnits.remove("Chu-Ko-Nu") }
        assertNotNull(AchievementCatalog.ineligibility(AchievementCatalog.byId.getValue("N34"), state))
        state.religionEnabled = false
        for (id in listOf("N14", "N24", "N33"))
            assertNotNull(AchievementCatalog.ineligibility(AchievementCatalog.byId.getValue(id), state))
        assertNull(AchievementCatalog.ineligibility(AchievementCatalog.byId.getValue("N01"), state))
    }

    @Test fun legacySaveAndCloneDoNotCreateEligibility() {
        val legacy = json().fromJson(GameInfo::class.java, "{gameId:legacy}")
        assertNull(legacy.achievements)
        assertNull(legacy.clone().achievements)
        legacy.gameId = "renamed-or-imported"
        assertNull(json().fromJson(GameInfo::class.java, json().toJson(legacy)).achievements)
    }

    @Test fun saveRoundTripAndUndoCloneKeepIndependentState() {
        val game = newGame().gameInfo
        game.achievements = AchievementGameState.create(game, true, false)
        game.achievements!!.targetLandmasses.add(hashSetOf("0,0", "1,0"))
        val copy = game.clone()
        copy.achievements!!.availableIds.remove("N01")
        copy.achievements!!.targetLandmasses[0].clear()
        copy.achievements!!.ended = true
        assertTrue("N01" in game.achievements!!.availableIds)
        assertEquals(2, game.achievements!!.targetLandmasses[0].size)
        assertFalse(game.achievements!!.ended)
        val loaded = json().fromJson(GameInfo::class.java, json().toJson(game))
        assertEquals(game.gameId, loaded.achievements!!.gameId)
        assertEquals(game.achievements!!.availableIds, loaded.achievements!!.availableIds)
        assertEquals(game.achievements!!.targetLandmasses, loaded.achievements!!.targetLandmasses)
    }

    @Test fun developmentRecordingCannotEarnAwards() {
        val game = newGame().gameInfo
        val state = AchievementGameState.create(game, true, false)!!
        assertTrue(state.canContribute(game, AchievementProfile()))
        for (oldVersion in listOf(0, 1, 2)) {
            state.recordingVersion = oldVersion
            assertFalse(state.canContribute(game, AchievementProfile()))
        }
        val missingVersions = json().fromJson(AchievementGameState::class.java, "{gameId:old}")
        assertEquals(0, missingVersions.catalogVersion)
        assertEquals(0, missingVersions.recordingVersion)
        state.recordingVersion = AchievementCatalog.recordingVersion + 1
        assertFalse(state.canContribute(game, AchievementProfile()))
    }

    @Test fun actualUpgradeAndFailedUpgradeKeepUnitIdentity() {
        val game = newGame()
        val civ = game.gameInfo.civilizations.first()
        val unit = game.addUnit("Warrior", civ, game.getTile(0, 0))
        val id = unit.id
        unit.upgrade.performUpgrade(PossibleUpgrade(game.ruleset.units.getValue("Spearman"), null, false, UpgradeAvailability.AvailableForFree, 0, Counter()), isFree = true)
        val upgraded = civ.units.getUnitById(id)!!
        assertEquals("Spearman", upgraded.name)
        upgraded.health = 71
        upgraded.upgrade.performUpgrade(PossibleUpgrade(game.ruleset.units.getValue("Trireme"), null, false, UpgradeAvailability.AvailableForFree, 0, Counter()), isFree = true)
        val restored = civ.units.getUnitById(id)!!
        assertEquals("Spearman", restored.name)
        assertEquals(71, restored.health)
        assertEquals(1, civ.units.getCivUnits().count())
    }
    @Test fun bothBuiltInRulesetsSupportTheCatalogWithReligionExplicitlyScoped() {
        for (base in com.unciv.models.metadata.BaseRuleset.entries) {
            val f = AchievementTestFixture(baseRuleset = base)
            f.state.recordingVersion = AchievementCatalog.recordingVersion
            for (definition in AchievementCatalog.definitions.filter { it.mode == "free" }) {
                f.state.difficulty = "Deity"
                f.state.oneCityChallenge = true
                f.state.civilization = definition.civilization ?: "Rome"
                val reason = AchievementCatalog.ineligibility(definition, f.state)
                if (definition.requiresReligion && base == com.unciv.models.metadata.BaseRuleset.Civ_V_Vanilla)
                    assertNotNull("${base.name} ${definition.id}", reason)
                else assertNull("${base.name} ${definition.id}", reason)
                definition.civilization?.let { assertTrue(it in f.test.ruleset.nations) }
                definition.requiredUnit?.let { assertTrue(it in f.test.ruleset.units) }
            }
            assertTrue(f.test.ruleset.buildings.keys.containsAll(listOf("Monument", "Granary", "Utopia Project")))
            assertTrue(f.test.ruleset.tileImprovements.keys.containsAll(listOf("Farm", "Mine", "Pasture")))
            assertTrue(f.test.ruleset.victories.keys.containsAll(AchievementCatalog.victoryRoutes))
            assertTrue(f.test.ruleset.buildings.values.count { it.isWonder } >= 8)
            assertEquals(1, f.history.foundedCities.size)
            f.city(0, 0)
            assertTrue("N02" in f.results())
            f.player.goldenAges.enterGoldenAge()
            assertFalse("N04" in f.results())
            for (route in AchievementCatalog.victoryRoutes) assertTrue("N01" in f.results(route = route))
        }
    }

}
