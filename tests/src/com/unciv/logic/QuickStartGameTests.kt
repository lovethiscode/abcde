package com.unciv.logic

import com.badlogic.gdx.Gdx
import com.unciv.UncivGame
import com.unciv.json.json
import com.unciv.logic.civilization.Civilization
import com.unciv.models.metadata.GameSettings
import com.unciv.models.metadata.GameSetupInfo
import com.unciv.models.ruleset.RulesetCache
import com.unciv.models.ruleset.nation.Nation
import com.unciv.testing.BaseTestRunner
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(BaseTestRunner::class)
class QuickStartGameTests {
    @Test fun `defeat can be checked during map generation before game info is assigned`() {
        val civilization = Civilization().apply { setNation(Nation().apply { name = "Rome" }) }
        assertTrue(civilization.isDefeated())
    }

    @Test fun `quick start creates a playable game with remembered rectangular world wrap settings`() {
        val previousApp = Gdx.app
        val previousGame = if (UncivGame.isCurrentInitialized()) UncivGame.Current else UncivGame()
        try {
            Gdx.app = null // Do not write settings while generating this test game.
            RulesetCache.loadRulesets(noMods = true)
            UncivGame.Current = UncivGame().apply {
                settings = GameSettings().apply {
                    lastGameSetup = json().fromJson(GameSetupInfo::class.java, """
                        {"mapParameters":{"seed":42,"worldWrap":true,"mapSize":{"height":29,"width":44,"radius":20,"name":"Medium"},"shape":"Rectangular"},
                        "gameParameters":{"victoryTypes":["Scientific","Cultural","Domination","Diplomatic","Time"],"espionageEnabled":true,"players":[{"playerType":"Human"},{},{},{}],"difficulty":"Chieftain"}}
                    """)
                }
            }
            val setup = GameSetupInfo.fromSettings("Chieftain")
            setup.mapParameters.seed = 42L
            val game = GameStarter.startNewGame(setup, reservedGameId = "quick-start-regression")
            assertEquals(0, game.turns)
            assertEquals(1, game.civilizations.count { it.isHuman() })
            assertEquals(4, game.civilizations.count { it.isMajorCiv })
            assertTrue(game.getCurrentPlayerCivilization().units.getCivUnits().any())
        } finally {
            Gdx.app = previousApp
            UncivGame.Current = previousGame
        }
    }
}
