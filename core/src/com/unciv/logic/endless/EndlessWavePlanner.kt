package com.unciv.logic.endless

import kotlin.random.Random

/** Integer budgets and seeded choices keep queued units identical across a reload. */
object EndlessWavePlanner {
    const val finalTierRound = 100
    val roles = listOf("M", "R", "C", "S")
    val costs = listOf(4, 5, 6, 8)
    private val first = listOf(listOf(3, 0, 0, 0), listOf(4, 0, 0, 0),
        listOf(2, 2, 0, 0), listOf(3, 1, 1, 0), listOf(2, 1, 1, 1))
    private val templates = listOf("Frontal", "Flank", "Siege")
    private val shares = mapOf("Frontal" to listOf(55, 30, 15, 0),
        "Flank" to listOf(35, 25, 40, 0), "Siege" to listOf(40, 20, 15, 25))
    private val units = listOf(listOf("Spearman", "Archer", "Horseman", "Catapult"),
        listOf("Swordsman", "Composite Bowman", "Horseman", "Catapult"),
        listOf("Longswordsman", "Crossbowman", "Knight", "Trebuchet"))

    fun plan(s: EndlessState): EndlessWave {
        val number = s.waveNumber + 1
        val random = Random(s.seed xor (number.toLong() * 104729))
        val wave = EndlessWave().apply {
            this.number = number
            round = s.round
            budget = minOf(64L, 12L + 4L * (number.toLong() - 1)).toInt()
            template = if (number <= 4) "Frontal" else if (number % 5 == 0) "Siege" else {
                if (s.templateBag.isEmpty()) {
                    val bag = templates.shuffled(Random(s.seed xor (++s.bagNumber * 65537L))).toMutableList()
                    if (bag.first() == s.lastTemplate) java.util.Collections.swap(bag, 0, 1)
                    s.templateBag.addAll(bag)
                }
                s.templateBag.removeAt(0).also { s.lastTemplate = it }
            }
            mainSector = (0..5).filter { it != s.lastMainSector }.random(random)
            secondarySector = (mainSector + listOf(2, 3, 4).random(random)) % 6
        }
        val counts = if (number <= 5) first[number - 1].toMutableList() else {
            val count = mutableListOf(2, 1, if (wave.template == "Flank") 1 else 0,
                if (wave.template == "Siege") 1 else 0)
            var spent = count.indices.sumOf { count[it] * costs[it] }
            while (true) {
                val available = count.indices.filter { costs[it] <= wave.budget - spent && shares.getValue(wave.template)[it] > 0 }
                val role = available.maxByOrNull { shares.getValue(wave.template)[it] * wave.budget - 100 * count[it] * costs[it] } ?: break
                count[role]++
                spent += costs[role]
            }
            count
        }
        val desiredTier = when { s.round >= finalTierRound -> 3; s.round >= 36 -> 2; else -> 1 }
        val previousTier = s.tier
        s.tier = minOf(desiredTier, previousTier + 1)
        var index = 0
        // Siege claims the main sector first. Role order is otherwise stable.
        for (role in listOf(3, 0, 1, 2)) repeat(counts[role]) {
            wave.members.add(EndlessMember().apply {
                this.index = index++
                this.role = roles[role]
                name = units[previousTier - 1][role]
            })
        }
        if (s.tier > previousTier) {
            val changed = wave.members.filter { units[s.tier - 1][roles.indexOf(it.role)] != it.name }.shuffled(random)
            changed.take((changed.size + 1) / 2).forEach { it.name = units[s.tier - 1][roles.indexOf(it.role)] }
        } else wave.members.forEach { it.name = units[s.tier - 1][roles.indexOf(it.role)] }
        val mainCount = if (number <= 5) wave.members.size else (wave.members.size * 2 + 2) / 3
        wave.members.forEachIndexed { i, member -> member.sector = if (i < mainCount) wave.mainSector else wave.secondarySector }
        val eliteCount = when { number > 10 -> 2; number > 5 -> 1; else -> 0 }
        wave.members.filter { it.role != "S" }.shuffled(random).take(eliteCount).forEach {
            it.promotion = if (it.role == "R") listOf("Accuracy I", "Barrage I").random(random)
                else listOf("Shock I", "Drill I").random(random)
        }
        s.lastMainSector = wave.mainSector
        return wave
    }

    @yairm210.purity.annotations.Readonly
    fun pressure(round: Int) = if (round < 121) 0 else (5L * (1L + (round.toLong() - 121) / 12)).toInt()
}
