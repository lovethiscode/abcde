package com.unciv.dev

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.backends.headless.HeadlessFiles
import com.unciv.UncivGame
import com.unciv.logic.achievements.AchievementTracker
import com.unciv.logic.battle.CityCombatant
import com.unciv.logic.endless.*
import com.unciv.logic.files.UncivFiles
import com.unciv.testing.TestGame
import java.io.File

/** Synthetic, record-ineligible fixtures. These do not demonstrate legal survival. */
object EndlessStress {
    @JvmStatic fun main(args: Array<String>) {
        Gdx.files = HeadlessFiles(); TestGame()
        UncivGame.Current.settings.citiesAutoBombardAtEndOfTurn = false
        val root = File(args[0]); check(!root.exists()); root.mkdirs()
        try {
            continuous(root)
            fixedLoad(root, 100)
            fixedLoad(root, 1000)
        } finally { com.unciv.utils.Concurrency.stopThreadPools() }
    }

    private fun continuous(root: File) {
        val g = EndlessCatalog.newAttempt(1101); g.endless!!.recordEligible = false
        UncivGame.Current.gameInfo = g
        val store = EndlessStore(UncivFiles(Gdx.files, File(root, "continuous-local").path)); store.start(g, null)
        File(root, "continuous.tsv").bufferedWriter().use { log ->
            log.appendLine("round\twaves\tledger\tmodeBytes\theapBytes")
            for (round in 1..1000) {
                val s = g.endless!!
                s.completedRounds = round - 1
                EndlessRules.prepareRound(g)
                // Model confirmed removal events inside one action, retaining this exact GameInfo throughout.
                if (round % 3 == 0) AchievementTracker.action(g) {
                    for (unit in EndlessRules.invaders(g).units.getCivUnits().toList()) {
                        unit.destroy(); EndlessRules.combatDefeat(CityCombatant(EndlessRules.core(g)!!), unit)
                    }
                }
                while (s.offer != null) EndlessRules.applyReward(g, "Gold")
                // Normal human start-turn also expires the prior turn's notifications.
                EndlessRules.player(g).notifications.clear()
                check(!s.ended && s.activeCount <= 40 && s.pendingCount <= 16 && s.waves.size <= 41)
                if (round % 100 == 0) {
                    store.save(g); System.gc()
                    val size = com.unciv.json.json().toJson(s).toByteArray().size
                    check(size < 256 * 1024)
                    log.appendLine("$round\t${s.waveNumber}\t${s.waves.size}\t$size\t${Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()}")
                }
            }
            check(store.records().recent.isEmpty())
        }
    }

    private fun fixedLoad(root: File, firstRound: Int) {
        val g = EndlessCatalog.newAttempt(1101); val s = g.endless!!; s.recordEligible = false
        UncivGame.Current.gameInfo = g
        for (round in 1..firstRound) { s.completedRounds = round - 1; EndlessRules.prepareRound(g) }
        check(s.activeCount == 40)
        s.waves.forEach { w -> w.members.removeAll { it.unitId == 0 } }
        s.waves.removeAll { it.members.isEmpty() }
        s.waveNumber++
        s.waves.add(EndlessWave().apply {
            number = s.waveNumber; round = firstRound; budget = 64
            repeat(16) { index -> members.add(EndlessMember().apply { this.index = index }) }
        })
        val city = EndlessRules.core(g)!!; val player = EndlessRules.player(g)
        while (player.units.getCivUnits().count { it.isMilitary() } < 24)
            check(player.units.placeUnitNearTile(city.location, "Spearman") != null)
        val origins = EndlessRules.invaders(g).units.getCivUnits().associateWith { it.currentTile }
        val store = EndlessStore(UncivFiles(Gdx.files, File(root, "fixed-$firstRound-local").path)); store.start(g, null)
        val turnTimes = arrayListOf<Double>(); val saveTimes = arrayListOf<Double>()
        File(root, "fixed-$firstRound.tsv").bufferedWriter().use { log ->
            log.appendLine("round\tenemies\tpending\tplayerMilitary\tturnMs\tsaveMs\theapBytes")
            repeat(60) { index ->
                // Reset only fixture positions/economy; never replace the GameInfo or its accumulating state.
                origins.keys.forEach { it.removeFromTile() }
                origins.forEach { (unit, tile) -> unit.putInTile(tile) }
                player.addGold(100000 - player.gold)
                player.populationFixture(city)
                val begin = System.nanoTime(); g.nextTurn(); val turnMs = (System.nanoTime() - begin) / 1e6
                val save = System.nanoTime(); store.save(g); val saveMs = (System.nanoTime() - save) / 1e6
                check(s.activeCount == 40 && s.pendingCount == 16 && !s.ended)
                check(player.units.getCivUnits().count { it.isMilitary() } == 24)
                if (index >= 10) { turnTimes.add(turnMs); saveTimes.add(saveMs) }
                if (index % 10 == 0) System.gc()
                log.appendLine("${s.round}\t${s.activeCount}\t${s.pendingCount}\t24\t$turnMs\t$saveMs\t${Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()}")
            }
        }
        fun p95(values: List<Double>) = values.sorted()[kotlin.math.ceil(values.size * .95).toInt() - 1]
        File(root, "fixed-$firstRound-summary.txt").writeText("Synthetic JVM fixture; 10 warmup + 50 samples; turn P95=${p95(turnTimes)} ms; save P95=${p95(saveTimes)} ms\n")
        check(store.records().recent.isEmpty())
    }

    private fun com.unciv.logic.civilization.Civilization.populationFixture(city: com.unciv.logic.city.City) {
        city.population.setPopulation(4); city.population.foodStored = 0
        city.reassignPopulation(); city.cityStats.update()
        units.getCivUnits().forEach { it.fortifyIfCan() }
    }
}
