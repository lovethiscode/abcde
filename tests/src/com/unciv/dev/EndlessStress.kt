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

/** Record-ineligible V2 fixtures. Controlled kills and load resets are not legal survival. */
object EndlessStress {
    @JvmStatic fun main(args: Array<String>) {
        Gdx.files = HeadlessFiles(); TestGame()
        UncivGame.Current.settings.citiesAutoBombardAtEndOfTurn = false
        val root = File(args[0]); check(!root.exists()); root.mkdirs()
        try {
            continuous(root)
            for (round in listOf(100, 1000)) for (blocked in listOf(false, true)) fixedLoad(root, round, blocked)
        } finally { com.unciv.utils.Concurrency.stopThreadPools() }
    }

    private fun continuous(root: File) {
        val game = EndlessCatalog.newAttempt(1101)
        val state = game.endless!!; state.recordEligible = false
        UncivGame.Current.gameInfo = game
        val store = EndlessStore(UncivFiles(Gdx.files, File(root, "continuous-local").path)); store.start(game, null)
        File(root, "continuous.tsv").bufferedWriter().use { log ->
            log.appendLine("completed\tstage\twaves\tledger\tmodeBytes\theapBytes")
            repeat(1000) {
                AchievementTracker.action(game) {
                    for (unit in EndlessRules.invaders(game).units.getCivUnits().toList()) {
                        EndlessRules.combatDefeat(CityCombatant(EndlessRules.core(game)!!), unit)
                        unit.destroy()
                    }
                }
                if (state.offer != null) EndlessRules.applyReward(game, "Gold")
                game.nextTurn()
                check(!state.ended && state.activeCount <= 40 && state.activeCount + state.pendingCount <= 54 && state.waves.size == 5)
                if (state.completedRounds % 100 == 0) {
                    store.save(game); System.gc()
                    val bytes = com.unciv.json.json().toJson(state).toByteArray().size
                    check(bytes < 256 * 1024)
                    log.appendLine("${state.completedRounds}\t${state.stageState!!.completedStages}\t${state.waveNumber}\t${state.waves.size}\t$bytes\t${heap()}")
                }
            }
            check(state.completedRounds == 1000 && store.records(true).recent.isEmpty())
        }
    }

    private fun fixedLoad(root: File, firstRound: Int, blocked: Boolean) {
        val game = EndlessCatalog.newAttempt(1101)
        val state = game.endless!!; state.recordEligible = false
        UncivGame.Current.gameInfo = game
        state.completedRounds = firstRound - 1
        state.claimedStages = 4; state.waveNumber = 25
        val stage = state.stageState!!
        stage.currentStage = 5; stage.completedStages = 4; stage.combatRounds = 52; stage.phase = "Combat"
        stage.startRound = firstRound - 28; stage.deadline = stage.startRound + EndlessStagePlanner.duration(5) - 1
        stage.normalStartedRound = firstRound
        state.waves = EndlessStagePlanner.plan(state, 5, stage.startRound)
        for (wave in state.waves) { wave.announced = true; wave.members.forEach { it.lifecycle = "Pending" } }
        val player = EndlessRules.player(game); val core = EndlessRules.core(game)!!
        repeat(2) {
            val tile = core.getCenterTile().getTilesAtDistance(4).first { it.canBeSettled(player) && it.civilianUnit == null }
            val settler = requireNotNull(player.units.placeUnitNearTile(tile.position, "Settler"))
            check(settler.currentTile == tile)
            player.addCity(tile.position, settler)
        }
        while (player.units.getCivUnits().count { it.isMilitary() } < 24)
            check(player.units.placeUnitNearTile(core.location, "Spearman") != null)
        if (blocked) for (tile in game.tileMap.values.filter { it.aerialDistanceTo(core.getCenterTile()) in 8..10 }) {
            val worker = requireNotNull(player.units.placeUnitNearTile(tile.position, "Worker"))
            check(worker.currentTile == tile)
        }
        EndlessRules.prepareRound(game)
        check(state.activeCount == if (blocked) 0 else 40)
        check(state.pendingCount == if (blocked) 54 else 14)
        EndlessCatalog.validate(game)
        val origins = EndlessRules.invaders(game).units.getCivUnits().associateWith { it.currentTile }
        val label = "$firstRound-${if (blocked) "blocked" else "active"}"
        val store = EndlessStore(UncivFiles(Gdx.files, File(root, "$label-local").path)); store.start(game, null)
        val times = arrayListOf<Double>(); val saves = arrayListOf<Double>()
        File(root, "$label.tsv").bufferedWriter().use { log ->
            log.appendLine("round\tenemies\tpending\tcities\tplayerMilitary\tturnMs\tsaveMs\theapBytes")
            repeat(60) { sample ->
                // Fixed-load benchmark: restore positions, health and economy, and slide the same stage's clock.
                // The separate continuous run above advances the real unmodified stage clock for 1000 rounds.
                origins.keys.forEach { it.removeFromTile(); it.health = 100 }
                origins.forEach { (unit, tile) -> unit.putInTile(tile) }
                player.addGold(100000 - player.gold)
                player.cities.forEach { it.health = CityCombatant(it).getMaxHealth(); it.population.foodStored = 0 }
                player.units.getCivUnits().forEach { it.health = 100; it.fortifyIfCan() }
                stage.startRound++; stage.deadline++
                state.waves.forEach { it.round++ }
                val begin = System.nanoTime(); game.nextTurn(); val turnMs = (System.nanoTime() - begin) / 1e6
                val save = System.nanoTime(); store.save(game); val saveMs = (System.nanoTime() - save) / 1e6
                check(!state.ended && state.activeCount == if (blocked) 0 else 40)
                check(state.pendingCount == if (blocked) 54 else 14)
                check(player.cities.size == 3 && player.units.getCivUnits().count { it.isMilitary() } == 24)
                if (sample >= 10) { times.add(turnMs); saves.add(saveMs) }
                if (sample % 10 == 0) System.gc()
                log.appendLine("${state.round}\t${state.activeCount}\t${state.pendingCount}\t3\t24\t$turnMs\t$saveMs\t${heap()}")
            }
        }
        File(root, "$label-summary.txt").writeText("Synthetic JVM fixture; 10 warmup + 50 samples; turn P95=${p95(times)} ms; save P95=${p95(saves)} ms\n")
        check(store.records(true).recent.isEmpty())
    }

    private fun heap() = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
    private fun p95(values: List<Double>) = values.sorted()[kotlin.math.ceil(values.size * .95).toInt() - 1]
}
