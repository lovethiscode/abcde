package com.unciv.logic.endless

import yairm210.purity.annotations.Readonly

/** Checked counters without the Java 8 Math methods absent from RoboVM. */
internal object EndlessCounter {
    @Readonly fun toInt(value: Long): Int {
        if (value < Int.MIN_VALUE || value > Int.MAX_VALUE) throw ArithmeticException("Integer overflow")
        return value.toInt()
    }

    @Readonly fun add(a: Int, b: Int): Int = toInt(a.toLong() + b)

    @Readonly fun add(a: Long, b: Long): Long {
        val result = a + b
        if (((a xor result) and (b xor result)) < 0) throw ArithmeticException("Long overflow")
        return result
    }
}
