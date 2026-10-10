package com.unciv.logic.campaign

import com.badlogic.gdx.Gdx
import com.unciv.UncivGame
import com.unciv.logic.GameInfo
import com.unciv.logic.files.UncivFiles
import com.unciv.logic.files.FileConversions
import com.unciv.logic.files.cloud.CloudSaveSync
import com.unciv.models.stats.Stat
import com.unciv.models.translations.tr
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

@RunWith(BaseTestRunner::class)
class CampaignTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var files: UncivFiles
    private lateinit var store: CampaignStore

    @Before fun setup() {
        TestGame() // Initializes the real base ruleset and settings, not the campaign scene.
        files = UncivFiles(Gdx.files, temporary.root.path)
        store = CampaignStore(files)
    }

    @Test fun `Chinese objective uses the actual construction name`() {
        UncivGame.Current.translations.readAllLanguagesTranslation()
        UncivGame.Current.settings.language = "Simplified_Chinese"
        try {
            assertTrue("Campaign production: [Monument]".tr(hideIcons = true).contains("纪念碑"))
            assertTrue("Campaign production: [Scout]".tr(hideIcons = true).contains("斥候"))
        } finally { UncivGame.Current.settings.language = "English" }
    }

    @Test fun `packaged initial assets are exact and each attempt has fresh identity`() {
        val first = CampaignCatalog.newAttempt()
        val second = CampaignCatalog.newAttempt()
        val player = first.getCurrentPlayerCivilization()
        val capital = player.getCapital()!!
        assertEquals(150, player.gold)
        assertEquals(2, capital.population.population)
        assertEquals(setOf("Palace"), capital.cityConstructions.getBuiltBuildings().map { it.name }.toSet())
        assertEquals(listOf("Warrior"), player.units.getCivUnits().map { it.name }.toList())
        assertTrue(player.tech.isResearched("Pottery"))
        assertTrue(capital.cityConstructions.constructionQueue.isEmpty())
        assertEquals(0, first.campaign!!.completedRounds)
        assertNull(first.achievements)
        assertNotEquals(first.gameId, second.gameId)
        assertNotEquals(first.campaign!!.attemptId, second.campaign!!.attemptId)
        assertNotEquals(first.campaign!!.seed, second.campaign!!.seed)
        assertEquals(first.tileMap.values.map { it.baseTerrain }, second.tileMap.values.map { it.baseTerrain })
    }

    @Test fun `purchase cannot produce mission facts or spend gold even with enough funds`() {
        val game = CampaignCatalog.newAttempt()
        val capital = game.getCurrentPlayerCivilization().getCapital()!!
        val player = capital.civ
        player.addGold(5000)
        for (name in listOf("Monument", "Scout")) {
            val construction = (game.ruleset.buildings[name] ?: game.ruleset.units.getValue(name)) as com.unciv.models.ruleset.INonPerpetualConstruction
            val before = player.gold
            assertFalse(capital.cityConstructions.isConstructionPurchaseAllowed(construction, Stat.Gold, 1))
            assertFalse(capital.cityConstructions.purchaseConstruction(construction, -1, false))
            assertEquals(before, player.gold)
        }
        assertTrue(game.campaign!!.produced.isEmpty())
        assertFalse(CampaignRules.purchaseBlocked(capital, "Worker", Stat.Gold))
    }

    @Test fun `production is idempotent and worker and population use current ownership`() {
        val game = CampaignCatalog.newAttempt()
        val capital = game.getCurrentPlayerCivilization().getCapital()!!
        val unit = game.ruleset.units.getValue("Worker").construct(capital.cityConstructions, null)!!
        repeat(2) { CampaignRules.productionCompleted(capital, "Worker", unit) }
        assertEquals(1, game.campaign!!.producedWorkers.size)
        assertTrue(CampaignRules.evaluate(game).worker)
        unit.destroy()
        assertFalse(CampaignRules.evaluate(game).worker)
        val replacement = game.ruleset.units.getValue("Worker").construct(capital.cityConstructions, null)!!
        assertFalse(CampaignRules.evaluate(game).worker)
        CampaignRules.productionCompleted(capital, "Worker", replacement)
        assertTrue(CampaignRules.evaluate(game).worker)
        capital.population.setPopulation(4)
        assertTrue(CampaignRules.evaluate(game).population)
        capital.population.setPopulation(3)
        assertFalse(CampaignRules.evaluate(game).population)
    }

    @Test fun `clone and serialization preserve snapshot without sharing mutable facts`() {
        val game = CampaignCatalog.newAttempt()
        game.campaign!!.produced.add("Monument")
        store.start(game, null)
        val copy = game.clone()
        copy.campaign!!.produced.add("Scout")
        copy.campaign!!.definition.population = 99
        assertEquals(setOf("Monument"), game.campaign!!.produced)
        assertEquals(4, game.campaign!!.definition.population)
        val restored = store.resume(CampaignCatalog.construction)
        assertEquals(game.gameId, restored.gameId)
        assertEquals(game.campaign!!.seed, restored.campaign!!.seed)
        assertEquals(0, restored.campaign!!.completedRounds)
        assertEquals(game.campaign!!.produced, restored.campaign!!.produced)
    }

    @Test fun `manual and autosave paths never enter normal saves or cloud hooks`() {
        val game = CampaignCatalog.newAttempt()
        store.start(game, null)
        var cloudCalls = 0
        files.cloudSaveSync = object : CloudSaveSync by CloudSaveSync.None {
            override fun onLocalSave(file: com.badlogic.gdx.files.FileHandle, game: GameInfo) { cloudCalls++ }
        }
        files.saveGame(game, "Custom name")
        files.saveGame(game, files.getSave("QuickSave"))
        files.autosaves.autoSave(game, nextTurn = true)
        assertFalse(files.getSaves().any())
        assertFalse(files.autosaves.autosaveExists())
        assertEquals(0, cloudCalls)
        assertEquals(game.gameId, store.resume(CampaignCatalog.construction).gameId)
        files.saveGame(GameInfo(), "Normal")
        assertEquals(1, cloudCalls)
    }

    @Test fun `old snapshots cannot undo a result or replace a new attempt`() {
        val game = CampaignCatalog.newAttempt()
        store.start(game, null)
        val stale = game.clone()
        game.campaign!!.produced.addAll(listOf("Monument", "Scout"))
        assertEquals(1, store.submit(game))
        store.save(stale)
        assertEquals(1, store.saved(CampaignCatalog.construction)!!.campaign!!.resultStars)
        val next = CampaignCatalog.newAttempt()
        store.start(next, game.campaign!!.attemptId)
        store.save(stale)
        assertEquals(next.gameId, store.saved(CampaignCatalog.construction)!!.gameId)
        assertEquals(1, store.progress().bestStars["foundation-02:1"])
    }

    @Test fun `result journal recovers a failed progress write and never stitches stars`() {
        val game = CampaignCatalog.newAttempt()
        store.start(game, null)
        game.campaign!!.produced.addAll(listOf("Monument", "Scout"))
        game.getCurrentPlayerCivilization().getCapital()!!.population.setPopulation(4)
        val progress = files.getLocalFile("Campaigns/progress.json")
        progress.mkdirs() // A real publication failure, after the fixed result was written.
        assertThrows(Exception::class.java) { store.submit(game) }
        assertEquals(2, store.saved(CampaignCatalog.construction)!!.campaign!!.resultStars)
        assertTrue(progress.deleteDirectory())
        val recovered = store.resume(CampaignCatalog.construction)
        assertEquals(2, store.submit(recovered))
        val another = CampaignCatalog.newAttempt()
        store.start(another, recovered.campaign!!.attemptId)
        another.campaign!!.produced.addAll(listOf("Monument", "Scout"))
        val city = another.getCurrentPlayerCivilization().getCapital()!!
        val worker = another.ruleset.units.getValue("Worker").construct(city.cityConstructions, null)!!
        CampaignRules.productionCompleted(city, "Worker", worker)
        assertEquals(2, store.submit(another))
        assertEquals(2, store.progress().bestStars["foundation-02:1"])
        assertTrue("foundation-03" in store.progress().unlocked)
    }

    @Test fun `delayed autosave cannot overwrite newer spending within the same turn`() {
        val game = CampaignCatalog.newAttempt()
        store.start(game, null)
        val delayed = store.snapshot(game)
        game.getCurrentPlayerCivilization().addGold(-50)
        store.save(game)
        store.saveSnapshot(delayed)
        assertEquals(100, store.resume(CampaignCatalog.construction).getCurrentPlayerCivilization().gold)
    }

    @Test fun `incompatible run is kept and never silently reinitialized`() {
        val game = CampaignCatalog.newAttempt()
        game.campaign!!.rulesVersion = "incompatible"
        store.start(game, null)
        assertThrows(Exception::class.java) { store.resume(CampaignCatalog.construction) }
        assertEquals(game.gameId, store.saved(CampaignCatalog.construction)!!.gameId)
    }

    @Test fun `failed slot publication preserves the previous attempt and publishes no score`() {
        val game = CampaignCatalog.newAttempt()
        store.start(game, null)
        val slot = store.slot(CampaignCatalog.construction)
        val original = slot.readBytes()
        val temporary = slot.sibling(".${slot.name()}.unciv-tmp")
        temporary.mkdirs()
        temporary.child("blocker").writeString("Prevent temporary file creation", false)
        try {
            val replacement = CampaignCatalog.newAttempt()
            assertThrows(Exception::class.java) { store.start(replacement, game.campaign!!.attemptId) }
            assertArrayEquals(original, slot.readBytes())
            game.campaign!!.produced.addAll(listOf("Monument", "Scout"))
            assertThrows(Exception::class.java) { store.submit(game) }
            assertArrayEquals(original, slot.readBytes())
            assertEquals(0, game.campaign!!.resultStars)
            assertTrue(store.progress().cleared.isEmpty())
            assertTrue(store.progress().unlocked.isEmpty())
        } finally { temporary.deleteDirectory() }
        assertEquals(game.gameId, store.resume(CampaignCatalog.construction).gameId)
        assertEquals(1, store.submit(game))
    }

    @Test fun `defeat takes priority over completed objectives and survives saving`() {
        val game = CampaignCatalog.newAttempt()
        store.start(game, null)
        game.campaign!!.produced.addAll(listOf("Monument", "Scout"))
        // Deliberately constructed failure; this is not legal-route or combat evidence.
        game.civilizations.remove(game.getCurrentPlayerCivilization())
        assertTrue(CampaignRules.evaluate(game).failed)
        assertEquals(0, CampaignRules.evaluate(game).stars)
        CampaignRules.roundCompleted(game)
        assertTrue(game.campaign!!.failed)
        store.save(game)
        assertTrue(store.saved(CampaignCatalog.construction)!!.campaign!!.failed)
        assertThrows(Exception::class.java) { store.submit(game) }
        assertTrue(store.progress().cleared.isEmpty())
    }

    @com.unciv.testing.RedirectOutput(com.unciv.testing.RedirectPolicy.Show)
    @Test fun `two independent packaged starts reach all four star combinations by legal turns`() {
        repeat(2) { seedIndex ->
            val initial = CampaignCatalog.newAttempt()
            val evidence = System.getProperty("unciv.campaignEvidenceDir")?.let { Gdx.files.absolute(it).child("seed-$seedIndex") }
            evidence?.mkdirs()
            evidence?.let { FileConversions.writeJson(it.child("initial.json"), initial, false) }
            for ((worker, population) in listOf(false to false, true to false, false to true, true to true)) {
                val game = initial.clone().also { it.setTransients() }
                UncivGame.Current.gameInfo = game
                val routeStore = CampaignStore(UncivFiles(Gdx.files, temporary.newFolder("route-$seedIndex-$worker-$population").path))
                routeStore.start(game, null)
                val capital = game.getCurrentPlayerCivilization().getCapital()!!
                // Both are player-facing city controls; no production, population or treasury injection.
                capital.avoidGrowth = !population
                val queue = arrayListOf("Monument", "Scout")
                if (worker) queue.add("Worker")
                queue.forEach { capital.cityConstructions.addToQueue(it) }
                var rounds = 0
                while (true) {
                    val result = CampaignRules.evaluate(game)
                    if (result.main && result.worker == worker && result.population == population) break
                    check(rounds++ < 80) { "Route stalled: $worker/$population at ${game.turns}" }
                    game.nextTurn()
                    assertEquals(game.turns, game.campaign!!.completedRounds)
                }
                val stars = 1 + (if (worker) 1 else 0) + (if (population) 1 else 0)
                println("CAMPAIGN_ROUTE seed=${game.campaign!!.seed} gameId=${game.gameId} worker=$worker population=$population turns=${game.turns} stars=$stars")
                assertEquals(stars, CampaignRules.evaluate(game).stars)
                assertEquals(stars, routeStore.submit(game))
                assertEquals(stars, routeStore.progress().bestStars["foundation-02:1"])
                evidence?.let { FileConversions.writeJson(it.child("worker-$worker-population-$population.json"), game, false) }
            }
        }
    }
}
