package com.unciv.logic.endless

import org.junit.Assert.*
import org.junit.Test

class EndlessWavePlannerTest {
    @Test fun `first five waves have the prescribed learning composition`() {
        val s = EndlessState()
        val expected = listOf(listOf(3, 0, 0, 0), listOf(4, 0, 0, 0), listOf(2, 2, 0, 0), listOf(3, 1, 1, 0), listOf(2, 1, 1, 1))
        for (counts in expected) {
            val wave = EndlessWavePlanner.plan(s)
            assertEquals(counts, EndlessWavePlanner.roles.map { role -> wave.members.count { it.role == role } })
            assertTrue(wave.members.all { it.sector == wave.mainSector && it.promotion.isEmpty() })
            s.waveNumber++
        }
    }

    @Test fun `thousands of wave plans keep budgets counts elites directions and bag boundaries valid`() {
        for (seed in 1101L..1108L) {
            val s = EndlessState().apply { this.seed = seed }
            var previous = -1
            var lastBagTemplate = ""
            val bag = arrayListOf<String>()
            repeat(1000) {
                val w = EndlessWavePlanner.plan(s)
                assertNotEquals(previous, w.mainSector); previous = w.mainSector
                assertTrue(w.members.size <= 16)
                assertTrue(w.members.sumOf { EndlessWavePlanner.costs[EndlessWavePlanner.roles.indexOf(it.role)] } <= w.budget)
                if (w.number > 5) {
                    assertTrue((w.secondarySector - w.mainSector).mod(6) in 2..4)
                    assertEquals(if (w.number > 10) 2 else 1, w.members.count { it.promotion.isNotEmpty() })
                    assertTrue(w.members.filter { it.role == "S" }.all { it.promotion.isEmpty() })
                    if (w.number % 5 == 0) assertEquals("Siege", w.template)
                    else {
                        if (bag.isEmpty()) assertNotEquals(lastBagTemplate, w.template)
                        bag.add(w.template)
                        if (bag.size == 3) { assertEquals(3, bag.toSet().size); lastBagTemplate = bag.last(); bag.clear() }
                    }
                }
                s.waveNumber = w.number; s.completedRounds += 8
            }
        }
    }

    @Test fun `skipped time thresholds advance only one tier and half the changing members`() {
        val s = EndlessState().apply { completedRounds = EndlessWavePlanner.finalTierRound - 1 }
        val first = EndlessWavePlanner.plan(s)
        assertEquals(2, s.tier); assertEquals(2, first.members.count { it.name == "Swordsman" })
        s.waveNumber++
        val second = EndlessWavePlanner.plan(s)
        assertEquals(3, s.tier); assertEquals(2, second.members.count { it.name == "Longswordsman" })
        s.waveNumber++
        val third = EndlessWavePlanner.plan(s)
        assertTrue(third.members.all { it.name in setOf("Longswordsman", "Crossbowman") })
    }

    @Test fun `continuous siege uses the frozen additive schedule`() {
        assertEquals(0, EndlessWavePlanner.pressure(120)); assertEquals(5, EndlessWavePlanner.pressure(121))
        assertEquals(5, EndlessWavePlanner.pressure(132)); assertEquals(10, EndlessWavePlanner.pressure(133))
    }
}
