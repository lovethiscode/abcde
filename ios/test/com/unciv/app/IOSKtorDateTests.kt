package com.unciv.app

import io.ktor.util.date.DateJvmKt
import io.ktor.util.date.GMTDate
import io.ktor.util.date.Month
import io.ktor.util.date.WeekDay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class IOSKtorDateTests {
    @Test fun compatibilityClassHasOnlyOneRuntimeDefinition() {
        val definitions = DateJvmKt::class.java.classLoader.getResources("io/ktor/util/date/DateJvmKt.class")
        assertEquals(1, definitions.toList().size)
    }

    @Test fun epochAndNegativeMillisecondsHaveUtcFields() {
        val previous = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
            assertEquals(GMTDate(0, 0, 0, WeekDay.THURSDAY, 1, 1, Month.JANUARY, 1970, 0), DateJvmKt.GMTDate(0))
            assertEquals(GMTDate(59, 59, 23, WeekDay.WEDNESDAY, 31, 365, Month.DECEMBER, 1969, -1), DateJvmKt.GMTDate(-1))
        } finally { TimeZone.setDefault(previous) }
    }

    @Test fun leapDayRoundTripsAndDefaultCallKeepsCurrentTime() {
        val leapDay = DateJvmKt.GMTDate(45, 30, 12, 29, Month.FEBRUARY, 2024)
        assertEquals(WeekDay.THURSDAY, leapDay.dayOfWeek)
        assertEquals(60, leapDay.dayOfYear)
        assertEquals(leapDay, DateJvmKt.GMTDate(DateJvmKt.toJvmDate(leapDay).time))
        val start = System.currentTimeMillis()
        val now = DateJvmKt.`GMTDate$default`(0, 1, null).timestamp
        assertTrue(now in start..System.currentTimeMillis())
    }
}
