package com.longexposure.scoring;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Formatters and classifiers that prepare values for inclusion in the
 * {@code breakdown} JSON each scorer writes. Wire-format quantities
 * (nanoseconds, raw integers, UTC instants) are not safe to expose to
 * the LLM directly — the model will copy them verbatim into prose
 * ("20,044 seconds", "18,128,957 nanoseconds", "143000000000Z"). Every
 * value the LLM might mention should pass through one of these helpers
 * first.
 *
 * <p>Renamed from {@code Humanize} on 2026-05-22; the previous name
 * undersold the class's role (it now also classifies session phases and
 * duration buckets, not just formats). The unifying purpose is
 * "prepare a value for the breakdown JSON so the LLM can drop it into
 * prose directly without arithmetic at inference time."
 */
public final class BreakdownFmt {

    private static final ZoneId ET = ZoneId.of("America/New_York");

    /** Time-of-day in ET with millisecond precision, e.g. {@code "07:08:14.994"}. */
    private static final DateTimeFormatter ET_TIME_FMT =
            DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ET);

    private BreakdownFmt() {}

    // ─── duration ────────────────────────────────────────────────────────────

    /**
     * Format an arbitrary duration into the most natural unit:
     * <ul>
     *   <li>&lt; 1 ms   → "{X.XX} μs"
     *   <li>&lt; 1 sec  → "{X.X} ms"
     *   <li>&lt; 60 sec → "{X.X} sec"
     *   <li>&lt; 1 hr   → "{X}m {Y}s"
     *   <li>≥ 1 hr      → "{X}h {Y}m"
     * </ul>
     */
    public static String durationNanos(final long nanos) {
        if (nanos < 0) return "unknown";
        if (nanos < 1_000)                 return String.format("%d ns", nanos);
        if (nanos < 1_000_000)             return String.format("%.2f μs", nanos / 1_000.0);
        if (nanos < 1_000_000_000L)        return String.format("%.1f ms", nanos / 1_000_000.0);
        if (nanos < 60L * 1_000_000_000L)  return String.format("%.1f sec", nanos / 1_000_000_000.0);
        long totalSec = nanos / 1_000_000_000L;
        if (totalSec < 3600) {
            long minutes = totalSec / 60;
            long seconds = totalSec % 60;
            return String.format("%dm %ds", minutes, seconds);
        }
        long hours   = totalSec / 3600;
        long minutes = (totalSec % 3600) / 60;
        return String.format("%dh %dm", hours, minutes);
    }

    public static String durationMs(final double ms) {
        return durationNanos((long) (ms * 1_000_000.0));
    }

    public static String durationSec(final long seconds) {
        return durationNanos(seconds * 1_000_000_000L);
    }

    /**
     * Render a duration in seconds as a HUMAN-readable phrase with proper
     * pluralization: "1 second", "2 seconds", "1 minute 5 seconds", "1 hour
     * 30 minutes". Distinct from {@link #durationSec} which produces the
     * abbreviated form ("1h 30m"). Use this for fields the LLM should
     * surface in prose directly — eliminates the "1 seconds" failure mode
     * observed 2026-05-28 (IWM liquidity_withdrawal).
     *
     * <p>Unit rules: seconds for &lt; 60 s; "{m}m {s}s" → "{M} minute(s)
     * {S} second(s)" for &lt; 1 h; "{H} hour(s) {M} minute(s)" for ≥ 1 h.
     * Singular form when count == 1; plural otherwise. The 0-component
     * units are dropped ("5 minutes" not "5 minutes 0 seconds").
     */
    public static String durationSecHumanized(final long seconds) {
        if (seconds < 0) return "unknown duration";
        if (seconds < 60) {
            return pluralize(seconds, "second");
        }
        if (seconds < 3600) {
            long mins = seconds / 60;
            long secs = seconds % 60;
            if (secs == 0) return pluralize(mins, "minute");
            return pluralize(mins, "minute") + " " + pluralize(secs, "second");
        }
        long hours = seconds / 3600;
        long mins = (seconds % 3600) / 60;
        if (mins == 0) return pluralize(hours, "hour");
        return pluralize(hours, "hour") + " " + pluralize(mins, "minute");
    }

    /** "{n} {unit}" + 's' suffix when n != 1. Pure formatter. */
    private static String pluralize(final long n, final String unit) {
        return n + " " + unit + (n == 1 ? "" : "s");
    }

    // ─── ET timestamps ───────────────────────────────────────────────────────

    /**
     * Format a UTC instant as time-of-day in Eastern Time with millisecond
     * precision. Returns e.g. {@code "07:08:14.994"} (handles DST
     * automatically; ET = EDT in May, EST in winter).
     *
     * <p>The {@code "ET"} suffix is intentionally omitted from the
     * output — breakdown fields are conventionally named with an
     * {@code _et} suffix (e.g. {@code halt_start_et}), so the zone is
     * already self-documenting. Keeping {@code "ET"} out of the string
     * value also prevents the LLM from copying it into prose and being
     * mistaken for a ticker by the grounding verifier.
     */
    public static String toEtTime(final Instant utc) {
        if (utc == null) return null;
        return ET_TIME_FMT.format(ZonedDateTime.ofInstant(utc, ET));
    }

    // ─── numeric ─────────────────────────────────────────────────────────────

    /**
     * Round a double to {@code decimals} places. Used by scorers when
     * putting derived ratios into the breakdown JSON so the LLM doesn't
     * see (and faithfully copy) values like {@code 220.3892538641666}.
     *
     * <p>{@link Math#round(double)} is sufficient — banker's rounding /
     * IEEE-754 edge cases don't matter here; we're producing display
     * values, not financial accounting numbers.
     */
    public static double round(final double v, final int decimals) {
        double scale = Math.pow(10, decimals);
        return Math.round(v * scale) / scale;
    }

    /**
     * Format an integer with thousand separators when ≥ 1000. Used by
     * scorers to pre-format counts (orders, shares, fills) before they
     * enter the breakdown JSON, so the LLM's narration reads "4,895
     * orders" instead of "4895 orders".
     *
     * <p>Returns a string because the breakdown JSON treats this as a
     * display-formatted value; downstream consumers needing the raw
     * integer should read the unformatted column from scored_events.
     *
     * <p>Examples:
     * <ul>
     *   <li>{@code formatCount(105)} → {@code "105"}
     *   <li>{@code formatCount(4895)} → {@code "4,895"}
     *   <li>{@code formatCount(2_377_027)} → {@code "2,377,027"}
     * </ul>
     */
    public static String formatCount(final long n) {
        return String.format("%,d", n);
    }

    /**
     * Format a dollar amount as {@code $X,XXX.XX} — leading {@code $},
     * thousand separators, exactly two decimals. Used by scorers to
     * pre-format notional values before they enter the breakdown JSON, so
     * the LLM renders "$1,047,129.40" instead of the raw double
     * "1047129.4" (which read as "notional value of 1047129.4 dollars").
     *
     * <p>For large notional amounts (always positive here). The two-decimal
     * form matches the grounding verifier's comma/precision handling, so the
     * formatted string still verifies against the prose. Share prices are NOT
     * routed through this — their sub-penny precision is left to the numeric
     * value.
     *
     * <p>Examples:
     * <ul>
     *   <li>{@code formatDollars(431.0)} → {@code "$431.00"}
     *   <li>{@code formatDollars(1_047_129.4)} → {@code "$1,047,129.40"}
     * </ul>
     */
    public static String formatDollars(final double v) {
        return "$" + String.format("%,.2f", v);
    }

    // ─── session phase ───────────────────────────────────────────────────────

    /** Length of the US-equity regular session in seconds (09:30 – 16:00 ET = 6.5 h). */
    public static final long REGULAR_SESSION_SECONDS = 23_400L;

    /**
     * Classify a UTC instant into a US-equity session phase using the
     * standard NMS clock in Eastern Time. Used by scorers + INTERPRET so
     * the LLM never has to compare HH:mm strings against trading-day
     * boundaries.
     *
     * <ul>
     *   <li>{@code "pre_market"}    — 04:00 – 09:30 ET
     *   <li>{@code "opening_5min"}  — 09:30 – 09:35 ET (high-attention window)
     *   <li>{@code "early_session"} — 09:35 – 11:30 ET
     *   <li>{@code "midday"}        — 11:30 – 14:00 ET
     *   <li>{@code "late_session"}  — 14:00 – 15:55 ET
     *   <li>{@code "closing_5min"}  — 15:55 – 16:00 ET (high-attention window)
     *   <li>{@code "post_market"}   — 16:00 – 20:00 ET
     *   <li>{@code "overnight"}     — outside the 04:00 – 20:00 ET extended window
     * </ul>
     */
    public static String sessionPhase(final Instant utc) {
        if (utc == null) return null;
        ZonedDateTime et = ZonedDateTime.ofInstant(utc, ET);
        int hh = et.getHour();
        int mm = et.getMinute();
        int minutes = hh * 60 + mm;
        if (minutes < 4 * 60)         return "overnight";
        if (minutes < 9 * 60 + 30)    return "pre_market";
        if (minutes < 9 * 60 + 35)    return "opening_5min";
        if (minutes < 11 * 60 + 30)   return "early_session";
        if (minutes < 14 * 60)        return "midday";
        if (minutes < 15 * 60 + 55)   return "late_session";
        if (minutes < 16 * 60)        return "closing_5min";
        if (minutes < 20 * 60)        return "post_market";
        return "overnight";
    }

    /**
     * Natural-English label for the session phase. The {@link #sessionPhase}
     * value is a stable enum-string for code / verifier; this is the prose
     * form the LLM can drop directly into a sentence.
     */
    public static String sessionPhaseLabel(final Instant utc) {
        String phase = sessionPhase(utc);
        if (phase == null) return null;
        return switch (phase) {
            case "overnight"     -> "outside regular trading hours";
            case "pre_market"    -> "in pre-market trading";
            case "opening_5min"  -> "in the opening minutes of regular trading";
            case "early_session" -> "in the early session";
            case "midday"        -> "around midday";
            case "late_session"  -> "in the late session";
            case "closing_5min"  -> "in the final minutes before the close";
            case "post_market"   -> "in post-market trading";
            default              -> phase;
        };
    }
}
