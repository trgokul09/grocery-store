package com.grocery.ai;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns natural phrases ("today", "yesterday", "last week", "this month", "last 7 days", "2026-11-02")
 * into concrete date ranges, plus the fair comparison range used for "vs previous" insights.
 */
public final class PeriodResolver {

    public record Period(LocalDate from, LocalDate to, String label, LocalDate prevFrom, LocalDate prevTo, String prevLabel) {
        public boolean singleDay() { return from.equals(to); }
        public long days() { return ChronoUnit.DAYS.between(from, to) + 1; }
        public String key() { return from + ".." + to; }
    }

    private static final Pattern LAST_N_DAYS = Pattern.compile("(?:last|past|previous)\\s+(\\d{1,3})\\s+days?");
    private static final Pattern ISO_DATE = Pattern.compile("(\\d{4}-\\d{2}-\\d{2})");
    private static final DateTimeFormatter NICE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

    private PeriodResolver() {}

    /** Returns the period mentioned in the text, or null if none is mentioned. */
    public static Period parse(String text, LocalDate today) {
        if (text == null) return null;
        String q = text.toLowerCase(Locale.ROOT);

        Matcher iso = ISO_DATE.matcher(q);
        if (iso.find()) {
            try {
                LocalDate d = LocalDate.parse(iso.group(1));
                return day(d, NICE.format(d), today);
            } catch (Exception ignored) { /* fall through */ }
        }
        Matcher lastN = LAST_N_DAYS.matcher(q);
        if (lastN.find()) {
            int n = Math.max(1, Math.min(366, Integer.parseInt(lastN.group(1))));
            return lastNDays(n, today);
        }
        if (q.contains("day before yesterday")) return day(today.minusDays(2), "the day before yesterday", today);
        if (q.contains("yesterday")) return day(today.minusDays(1), "yesterday", today);
        if (q.contains("today") || q.contains("so far today") || q.contains("tonight")) return day(today, "today", today);
        if (q.contains("last week") || q.contains("previous week")) {
            LocalDate start = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks(1);
            LocalDate end = start.plusDays(6);
            return new Period(start, end, "last week", start.minusWeeks(1), end.minusWeeks(1), "the week before");
        }
        if (q.contains("this week")) {
            LocalDate start = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            long span = ChronoUnit.DAYS.between(start, today);
            return new Period(start, today, "this week", start.minusWeeks(1), start.minusWeeks(1).plusDays(span), "the same days last week");
        }
        if (q.contains("last month") || q.contains("previous month")) {
            YearMonth ym = YearMonth.from(today).minusMonths(1);
            YearMonth before = ym.minusMonths(1);
            return new Period(ym.atDay(1), ym.atEndOfMonth(), "last month (" + ym.getMonth().getDisplayName(java.time.format.TextStyle.FULL, Locale.ENGLISH) + ")",
                before.atDay(1), before.atEndOfMonth(), before.getMonth().getDisplayName(java.time.format.TextStyle.FULL, Locale.ENGLISH));
        }
        if (q.contains("this month") || q.contains("month so far") || q.contains("monthly")) return thisMonth(today);
        if (q.contains("this year") || q.contains("year so far")) {
            LocalDate start = today.withDayOfYear(1);
            LocalDate prevStart = start.minusYears(1);
            return new Period(start, today, "this year", prevStart, today.minusYears(1), "the same period last year");
        }
        if (q.contains("last year")) {
            LocalDate start = today.withDayOfYear(1).minusYears(1);
            LocalDate end = start.plusYears(1).minusDays(1);
            return new Period(start, end, "last year", start.minusYears(1), end.minusYears(1), "the year before");
        }
        return null;
    }

    public static Period day(LocalDate d, String label, LocalDate today) {
        LocalDate prev = d.minusDays(1);
        String prevLabel = d.equals(today) ? "yesterday" : d.equals(today.minusDays(1)) ? "the day before" : NICE.format(prev);
        return new Period(d, d, label, prev, prev, prevLabel);
    }

    public static Period today(LocalDate today) { return day(today, "today", today); }

    public static Period thisMonth(LocalDate today) {
        LocalDate start = today.withDayOfMonth(1);
        LocalDate prevStart = start.minusMonths(1);
        LocalDate prevEnd = prevStart.plusDays(Math.min(today.getDayOfMonth(), YearMonth.from(prevStart).lengthOfMonth()) - 1);
        return new Period(start, today, "this month", prevStart, prevEnd, "the same days last month");
    }

    public static Period lastNDays(int n, LocalDate today) {
        LocalDate from = today.minusDays(n - 1L);
        return new Period(from, today, "the last " + n + " days", from.minusDays(n), from.minusDays(1), "the " + n + " days before that");
    }

    /** Builds a Period from a stored context (no comparison labels needed beyond defaults). */
    public static Period of(LocalDate from, LocalDate to, String label, LocalDate today) {
        if (from.equals(to)) return day(from, label, today);
        long n = ChronoUnit.DAYS.between(from, to) + 1;
        return new Period(from, to, label, from.minusDays(n), from.minusDays(1), "the previous " + n + " days");
    }

    public static String nice(LocalDate d) { return NICE.format(d); }
}
