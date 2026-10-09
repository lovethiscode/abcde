package io.ktor.util.date;

import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.Locale;
import java.util.TimeZone;

/** iOS classpath replacement for Ktor 3.5's Math.floorDiv/floorMod date conversion. */
public final class DateJvmKt {
    private DateJvmKt() {}

    private static GregorianCalendar utcCalendar() {
        GregorianCalendar calendar = new GregorianCalendar(TimeZone.getTimeZone("GMT"), Locale.ROOT);
        calendar.setGregorianChange(new Date(Long.MIN_VALUE));
        return calendar;
    }

    public static GMTDate GMTDate(Long timestamp) {
        return toDate(utcCalendar(), timestamp);
    }

    public static GMTDate GMTDate$default(Long timestamp, int mask, Object unused) {
        return GMTDate((mask & 1) != 0 ? null : timestamp);
    }

    public static GMTDate GMTDate(int seconds, int minutes, int hours, int dayOfMonth, Month month, int year) {
        Calendar calendar = utcCalendar();
        calendar.clear();
        calendar.set(year, month.ordinal(), dayOfMonth, hours, minutes, seconds);
        return toDate(calendar, null);
    }

    public static GMTDate toDate(Calendar calendar, Long timestamp) {
        if (timestamp != null) calendar.setTimeInMillis(timestamp);
        return new GMTDate(calendar.get(Calendar.SECOND), calendar.get(Calendar.MINUTE),
            calendar.get(Calendar.HOUR_OF_DAY), WeekDay.Companion.from((calendar.get(Calendar.DAY_OF_WEEK) + 5) % 7),
            calendar.get(Calendar.DAY_OF_MONTH), calendar.get(Calendar.DAY_OF_YEAR),
            Month.Companion.from(calendar.get(Calendar.MONTH)), calendar.get(Calendar.YEAR),
            calendar.getTimeInMillis() + calendar.get(Calendar.ZONE_OFFSET) + calendar.get(Calendar.DST_OFFSET));
    }

    public static Date toJvmDate(GMTDate date) {
        return new Date(date.getTimestamp());
    }

    public static long getTimeMillis() {
        return System.currentTimeMillis();
    }
}
