package com.unciv.logic.endless

import kotlin.random.Random

/** A stage owns exactly five plans. Looking at a preview never changes its owner. */
object EndlessStagePlanner {
    private val counts = listOf(listOf(3,4,4,5,5), listOf(5,5,6,6,7), listOf(6,7,7,8,8),
        listOf(8,8,9,9,10), listOf(10,10,11,11,12))
    private val tutorial = listOf(listOf(3,0,0,0), listOf(4,0,0,0), listOf(2,2,0,0), listOf(3,1,1,0), listOf(2,1,1,1))
    private val templates = listOf("Frontal", "Flank", "Siege")
    private val weights = mapOf("Frontal" to listOf(55,24,10,0), "Flank" to listOf(21,12,16,0), "Siege" to listOf(80,32,20,25))
    val tiers = listOf(listOf("Spearman","Archer","Horseman","Catapult"),
        listOf("Swordsman","Composite Bowman","Horseman","Catapult"),
        listOf("Longswordsman","Crossbowman","Knight","Trebuchet"))

    fun interval(stage: Long) = (minOf(stage, 8) + 2).toInt()
    fun duration(stage: Long): Int = interval(stage).let { 4 * it + maxOf(8, 2 * it) }
    fun tier(stage: Long) = if (stage >= 5) 3 else if (stage >= 3) 2 else 1
    @yairm210.purity.annotations.Readonly
    fun pressure(stage: Long) = EndlessCounter.toInt(5L * EndlessCounter.toInt((stage - 5).coerceAtLeast(0)))
    fun numbers(stage: Long) = counts[minOf(stage - 1, 4).toInt()]

    fun composition(number: Int, template: String): List<Int> {
        val count = mutableListOf(2,1,if (template == "Flank") 1 else 0,if (template == "Siege") 1 else 0)
        val weight = weights.getValue(template)
        require(number >= count.sum())
        while (count.sum() < number) {
            val role = count.indices.filter { weight[it] > 0 }
                .maxBy { weight[it] * number - count[it] * weight.sum() }
            count[role]++
        }
        return count
    }

    fun plan(s: EndlessState, stage: Long, start: Int): ArrayList<EndlessWave> {
        require(stage >= 1)
        val result = arrayListOf<EndlessWave>()
        for (j in 0..4) {
            val number = EndlessCounter.toInt(EndlessCounter.add(5L * EndlessCounter.toInt(stage - 1), j.toLong() + 1))
            fun random(purpose: Long) = Random(s.seed xor (number.toLong() * 104729) xor purpose)
            val wave = EndlessWave().apply {
                this.number = number
                round = EndlessCounter.add(start, j * interval(stage))
                template = if (stage == 1L) if (j == 4) "Siege" else "Frontal" else if (j == 4) "Siege" else {
                    if (s.templateBag.isEmpty()) {
                        s.bagNumber = EndlessCounter.add(s.bagNumber, 1)
                        val bag = templates.shuffled(Random(s.seed xor (s.bagNumber.toLong() * 65537) xor 11)).toMutableList()
                        if (bag.first() == s.lastTemplate) java.util.Collections.swap(bag,0,1)
                        s.templateBag.addAll(bag)
                    }
                    s.templateBag.removeAt(0).also { s.lastTemplate = it }
                }
                mainSector = (0..5).filter { it != s.lastMainSector }.random(random(23))
                secondarySector = (mainSector + listOf(2,3,4).random(random(29))) % 6
            }
            val composition = if (stage == 1L) tutorial[j] else composition(numbers(stage)[j],wave.template)
            val tier = tier(stage)
            for (role in listOf(3,0,1,2)) repeat(composition[role]) {
                wave.members.add(EndlessMember().apply {
                    index = wave.members.size
                    this.role = EndlessWavePlanner.roles[role]
                    name = tiers[tier - 1][role]
                })
            }
            if (j == 0 && stage in setOf(3L,5L)) {
                val changing = wave.members.filter { it.name != tiers[tier - 2][EndlessWavePlanner.roles.indexOf(it.role)] }
                    .shuffled(random(31))
                changing.drop((changing.size + 1) / 2).forEach { it.name = tiers[tier - 2][EndlessWavePlanner.roles.indexOf(it.role)] }
            }
            val mainCount = if (stage == 1L) wave.members.size else (2 * wave.members.size + 2) / 3
            wave.members.forEachIndexed { i, m -> m.sector = if (i < mainCount) wave.mainSector else wave.secondarySector }
            val elite = if (stage >= 3) 2 else if (stage == 2L) 1 else 0
            val eliteRandom = random(37)
            wave.members.filter { it.role != "S" }.shuffled(eliteRandom).take(elite).forEach {
                it.promotion = if (it.role == "R") listOf("Accuracy I","Barrage I").random(eliteRandom)
                    else listOf("Shock I","Drill I").random(eliteRandom)
            }
            s.lastMainSector = wave.mainSector
            result.add(wave)
        }
        return result
    }
}
