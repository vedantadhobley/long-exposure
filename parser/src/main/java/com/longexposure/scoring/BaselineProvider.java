package com.longexposure.scoring;

import java.time.LocalDate;
import java.util.Map;

/**
 * Read access to per-symbol historical baselines, decoupled from the SQL
 * that produces them. The first abstraction the inter-day scorers share:
 * {@link com.longexposure.scoring.scorers.VolumeDeviationScorer} reads its
 * trailing-volume baseline through this rather than inlining cagg SQL, and
 * future inter-day scorers (e.g. a time-in-book-drift detector) reuse the
 * same seam.
 *
 * <p>Backed by the {@code daily_volume_by_symbol} continuous aggregate, which
 * holds a rolling ~1 year of exact per-symbol daily volume (it outlives the
 * 2-week wire retention — see schema.sql + docs/tiered-baselines-design.md
 * §2.2). One instance per scoring run, sharing the activity's JDBC connection.
 *
 * <p>The methods are <b>bulk</b> (whole-universe maps) by design: volume
 * deviation is a set-scan ("which symbols surged today?"), not a per-symbol
 * point lookup. A per-symbol point method and a longer-reach
 * {@code monthlyNormVolume(...)} are the planned extensions (the monthly
 * numeric tier — design §2.4/§2.5); they'll be added when a point-lookup
 * consumer actually needs them, to avoid speculative dead code.
 */
public interface BaselineProvider {

    /** Per-symbol total volume + trade count on a single day (the cagg's day bucket). */
    Map<String, DayVolume> dayVolumes(LocalDate day);

    /**
     * Per-symbol trailing baseline over {@code [day - windowDays, day)} — the
     * <em>median</em> daily volume + trade count (median, not mean, so one
     * prior spike doesn't wash out the baseline) plus how many prior days were
     * actually present in the window.
     */
    Map<String, TrailingVolume> trailingVolumeBaselines(LocalDate day, int windowDays);

    /** A symbol's volume + trade count on one day. */
    record DayVolume(long volume, long tradeCount) {}

    /** A symbol's trailing-window median baseline + the day-count it was computed over. */
    record TrailingVolume(double medianVolume, double medianTradeCount, int dayCount) {}
}
