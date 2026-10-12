package com.unciv.logic.campaign

import com.badlogic.gdx.Gdx
import com.unciv.logic.GameInfo
import com.unciv.logic.files.FileConversions
import com.unciv.logic.files.UncivFiles
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

@RunWith(BaseTestRunner::class)
class CampaignShareTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var files: UncivFiles
    private lateinit var store: CampaignStore
    @Before fun setup() {
        TestGame()
        files = UncivFiles(Gdx.files, temporary.root.path)
        store = CampaignStore(files)
    }
    private fun state(id: String = "foundation-03", a: Boolean = false, b: Boolean = false) = CampaignState().apply {
        definition = CampaignCatalog.definition(id)
        attemptId = "share-fixture"
        completedRounds = 12
        resultStars = 1 + (if (a) 1 else 0) + (if (b) 1 else 0)
        resultWorker = a; resultPopulation = b
    }
    private fun share(s: CampaignState) = CampaignShareData.result(s, s.definition.id, s.attemptId)
    private fun write(s: CampaignState) = FileConversions.writeJson(store.slot(s.definition.id), GameInfo().apply { campaign = s }, zip = true)

    @Test fun `all seventeen exact version mappings preserve goal meaning and round types`() {
        val entries = CampaignShareMetadata.entries()
        val definitions = CampaignCatalog.definitions() + CampaignCatalog.historicalDefinitions()
        assertEquals(17, entries.size)
        assertEquals(17, entries.map { Triple(it.id, it.configVersion, it.scoringVersion) }.toSet().size)
        for (d in definitions) {
            val mapping = entries.single { it.id == d.id && it.configVersion == d.configVersion && it.scoringVersion == d.scoringVersion }
            assertEquals(d.objectives, mapping.objectives)
            val s = state(d.id, true, true).apply { resultActionRound = 13 }
            val data = share(s)!!
            assertEquals(d.id, mapping.id)
            assertEquals(d.id == "foundation-11", data.fullRounds)
            assertEquals(if (data.fullRounds) 12 else 13, data.rounds)
            assertEquals(3, data.goals.size)
        }
    }
    @Test fun `one star both two star variants and three stars retain their own flags`() {
        val goals = CampaignCatalog.definition("foundation-03").objectives
        for (a in listOf(false, true)) for (b in listOf(false, true)) {
            val data = share(state(a = a, b = b))!!
            assertEquals(1 + (if (a) 1 else 0) + (if (b) 1 else 0), data.stars)
            assertEquals(listOfNotNull(goals[0], goals[1].takeIf { a }, goals[2].takeIf { b }), data.goals)
        }
    }
    @Test fun `in progress failed unstable corrupt and replaced attempts are rejected`() {
        for (s in listOf(state().apply { resultStars = 0 }, state().apply { failed = true },
            state().apply { actionDepth = 1 }, state().apply { settling = true }, state().apply { resultStars = 3 },
            state().apply { completedRounds = -1 }, state().apply { schemaVersion = 99 })) assertNull(share(s))
        assertNull(CampaignShareData.result(state(), "foundation-02", "share-fixture"))
        assertNull(CampaignShareData.result(state(), "foundation-03", "replaced"))
    }
    @Test fun `same scoring but different configuration cannot borrow objectives or rounds`() {
        val s = state().apply { definition.configVersion++ }
        val data = share(s)!!
        assertTrue(data.goals.isEmpty()); assertNull(data.rounds); assertTrue(data.previousRules)
        s.definition.scoringVersion++
        assertTrue(share(s)!!.goals.isEmpty())
    }
    @Test fun `old automatic action boundaries are omitted manual and full round boundaries remain known`() {
        assertEquals(13, share(state())!!.rounds)
        for (id in listOf("foundation-12", "foundation-15", "foundation-16", CivilWarCampaign.id)) assertNull(share(state(id))!!.rounds)
        assertEquals(12, share(state("foundation-11"))!!.rounds)
        assertEquals(1, share(state().apply { completedRounds = 0 })!!.rounds)
        assertNull(share(state().apply { completedRounds = Int.MAX_VALUE })!!.rounds)
    }
    @Test fun `frozen action round distinguishes player action from end of round without exceeding hard limit`() {
        for (id in listOf("foundation-12", "foundation-15", "foundation-16", CivilWarCampaign.id)) {
            val s = state(id).apply { resultActionRound = 12 }
            assertEquals(12, share(s)!!.rounds)
            s.resultActionRound = 13
            assertEquals(13, share(s)!!.rounds)
            s.resultActionRound = 14
            assertNull(share(s)!!.rounds)
        }
        val last = state(CivilWarCampaign.id).apply { completedRounds = 49; resultActionRound = 50 }
        assertEquals(50, share(last)!!.rounds)
        last.completedRounds = 50; last.resultActionRound = 51
        assertNull(share(last)!!.rounds)
    }
    @Test fun `cloud only best is a summary and never guesses which bonus earned two stars`() {
        val p = CampaignProgress().apply { bestStars["foundation-03:1"] = 2 }
        val data = CampaignShareData.best(p, "foundation-03", 1)!!
        assertEquals(2, data.stars); assertTrue(data.goals.isEmpty()); assertNull(data.rounds)
        assertFalse(store.slot("foundation-03").exists())
        p.bestStars["foundation-03:2"] = 3
        assertEquals(2, data.stars)
        assertTrue(CampaignShareData.best(p, "foundation-03", 2)!!.previousRules)
        assertNull(CampaignShareData.best(p, "unknown", 1))
        assertNull(CampaignShareData.best(p, "foundation-03", 9))
    }
    @Test fun `chapter inheritance and current mastery do not confuse old stars or medals`() {
        val p = CampaignProgress()
        for (d in CampaignCatalog.definitions().take(4)) {
            p.cleared.add(d.id); p.bestStars["${d.id}:99"] = 3
        }
        p.medals.add("0:99-99-99-99")
        var chapter = CampaignShareData.chapter(p, 0)!!
        assertEquals(4, chapter.cleared); assertEquals(0, chapter.stars); assertFalse(chapter.chapters.single().mastery)
        for (d in CampaignCatalog.definitions().take(4)) p.bestStars["${d.id}:1"] = 3
        chapter = CampaignShareData.chapter(p, 0)!!
        assertTrue(chapter.chapters.single().mastery)
        assertNull(CampaignShareData.chapter(p, 1))
    }
    @Test fun `journey counts exactly foundation catalog and current version only`() {
        val p = CampaignProgress().apply { cleared.add(CivilWarCampaign.id); bestStars["${CivilWarCampaign.id}:8"] = 3; cleared.add("future") }
        assertNull(CampaignShareData.journey(p))
        for (d in CampaignCatalog.definitions()) { p.cleared.add(d.id); p.bestStars["${d.id}:1"] = 3 }
        val data = CampaignShareData.journey(p)!!
        assertEquals(16, data.cleared); assertEquals(16, data.levels); assertEquals(48, data.stars); assertEquals(48, data.maxStars)
        p.bestStars.clear(); p.cleared.clear()
        assertEquals(48, data.stars); assertTrue(data.chapters.all { it.mastery })
    }
    @Test fun `share reads durable terminal over stale memory and never publishes progress`() {
        val terminal = state(a = true)
        write(terminal)
        val bytes = store.slot(terminal.definition.id).readBytes()
        val data = store.shareResult(terminal.definition.id, terminal.attemptId, true)!!
        assertEquals(2, data.stars); assertTrue(data.recent)
        assertTrue(store.progress().isEmpty())
        assertArrayEquals(bytes, store.slot(terminal.definition.id).readBytes())
        assertNull(store.shareResult(terminal.definition.id, "other"))
    }
    @Test fun `no durable terminal means no new report but permanent best remains available`() {
        val p = CampaignProgress().apply { bestStars["foundation-03:1"] = 3 }
        store.mergeProgress(p)
        write(state().apply { resultStars = 0 })
        assertNull(store.shareResult("foundation-03", "share-fixture"))
        assertEquals(3, CampaignShareData.best(store.progress(), "foundation-03", 1)!!.stars)
    }
    @Test fun `corrupt progress cannot block terminal sharing or get repaired by it`() {
        val s = state(); write(s)
        val progress = files.getLocalFile("Campaigns/progress.json")
        progress.writeString("invalid-json", false)
        assertNotNull(store.shareResult(s.definition.id, s.attemptId))
        assertEquals("invalid-json", progress.readString())
        assertThrows(Exception::class.java) { store.progress() }
    }
    @Test fun `failed permanent write preserves terminal sharing and old best until original recovery`() {
        val s = state(a = true, b = true)
        store.mergeProgress(CampaignProgress().apply { bestStars["foundation-03:1"] = 1 })
        val obstruction = files.getLocalFile("Campaigns/.progress.json.unciv-tmp")
        obstruction.mkdirs(); obstruction.child("occupied").writeString("owned test obstruction", false)
        assertThrows(Exception::class.java) { store.save(GameInfo().apply { campaign = s }) }
        assertEquals(3, store.shareResult(s.definition.id, s.attemptId)!!.stars)
        assertEquals(1, CampaignShareData.best(store.progress(), s.definition.id, 1)!!.stars)
        obstruction.deleteDirectory()
        store.reconcile(store.saved(s.definition.id)!!)
        assertEquals(3, CampaignShareData.best(store.progress(), s.definition.id, 1)!!.stars)
    }
    @Test fun `latest one star and best three stars stay separate across reads and replacement`() {
        val s = state(); write(s)
        store.mergeProgress(CampaignProgress().apply { bestStars["foundation-03:1"] = 3 })
        assertEquals(1, store.shareResult(s.definition.id, s.attemptId, true)!!.stars)
        assertEquals(3, CampaignShareData.best(store.progress(), s.definition.id, 1)!!.stars)
        write(state().apply { attemptId = "next"; resultStars = 0 })
        assertNull(store.shareResult(s.definition.id, s.attemptId, true))
        assertEquals(3, CampaignShareData.best(store.progress(), s.definition.id, 1)!!.stars)
    }
    @Test fun `result snapshot is immutable and survives new store reads`() {
        val s = state(a = true, b = true); write(s)
        val data = CampaignStore(files).shareResult(s.definition.id, s.attemptId)!!
        s.definition.objectives.clear(); s.resultStars = 0
        assertEquals(3, data.stars); assertEquals(3, data.goals.size)
        assertThrows(UnsupportedOperationException::class.java) { (data.goals as MutableList).clear() }
        assertEquals(13, data.rounds)
    }
}
