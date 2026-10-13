package com.unciv.dev

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.backends.headless.HeadlessFiles
import com.unciv.UncivGame
import com.unciv.logic.GameInfo
import com.unciv.logic.PlayerDecisionControl
import com.unciv.logic.endless.EndlessCatalog
import com.unciv.logic.files.FileConversions
import com.unciv.testing.TestGame
import java.io.File
import com.unciv.dev.endless.EndlessRoutePlayer
import kotlinx.coroutines.runBlocking

/** Legal player orders against the real engine. No turn, money, health or army injection. */
object EndlessRoutes {
    @JvmStatic fun main(args: Array<String>) {
        Gdx.files = HeadlessFiles()
        TestGame()
        UncivGame.Current.settings.citiesAutoBombardAtEndOfTurn = false
        UncivGame.Current.settings.automatedUnitsMoveOnTurnStart = false
        val directory = File(args[0]).absoluteFile
        val seeds = (args.getOrNull(1) ?: "1101").split(',').map { it.toLong() }
        val routes = (args.getOrNull(2) ?: "economy,ranged,mobile").split(',')
        val limit = args.getOrNull(3)?.toInt() ?: 400
        try {
            for (seed in seeds) for (route in routes) {
                val initial = args.getOrNull(4)?.takeIf { it != "-" }?.let {
                    FileConversions.readJson(Gdx.files.absolute(it), GameInfo::class.java)!!.also { g -> g.setTransients() }
                } ?: EndlessCatalog.newAttempt(seed,args.getOrNull(7) ?: "casual")
                runBlocking { EndlessRoutePlayer.play(initial, File(directory, "$seed-$route"), route, limit, args.getOrNull(5), args.getOrNull(6) == "hold", args.getOrNull(8)?.toLong()) }
            }
        } finally { PlayerDecisionControl.release(); com.unciv.utils.Concurrency.stopThreadPools() }
    }

}
