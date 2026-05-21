package com.longexposure.scoring;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Formatters that turn wire-format numbers (nanos / millis / raw
 * seconds) and UTC instants into strings a narrator can drop into prose
 * directly. The contract is: breakdown JSONs surfaced to the LLM should
 * use these strings instead of raw integer durations or bare UTC ISO
 * timestamps.
 *
 * <p>Phase B of the narration plan (see
 * {@code docs/scoring-and-narration.md}). First-pass narrations exposed
 * that the model faithfully recites "20,044 seconds" or "18,128,957
 * nanoseconds" because that's what's in the breakdown — fix is upstream.
 */
public final class Humanize {

    private static final ZoneId ET = ZoneId.of("America/New_York");

    /** Time-of-day in ET with millisecond precision, e.g. {@code "07:08:14.994"}. */
    private static final DateTimeFormatter ET_TIME_FMT =
            DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ET);

    private Humanize() {}

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

    /** Two-decimal shorthand — the common case for rates and percentages. */
    public static double round2(final double v) {
        return round(v, 2);
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
}
