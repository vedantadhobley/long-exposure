package com.longexposure.scoring;

import com.longexposure.scoring.scorers.HaltScorer;
import com.longexposure.scoring.scorers.IcebergScorer;
import com.longexposure.scoring.scorers.LargeTradeScorer;
import com.longexposure.scoring.scorers.LayeringScorer;
import com.longexposure.scoring.scorers.LiquidityWithdrawalScorer;
import com.longexposure.scoring.scorers.PostCancelClusterScorer;
import com.longexposure.scoring.scorers.SweepScorer;
import com.longexposure.scoring.scorers.TimeInBookDriftScorer;
import com.longexposure.scoring.scorers.VolumeDeviationScorer;

import java.util.List;

/**
 * Single source of truth for which scorers run in
 * {@code ScoreEventsActivity}. Adding a new scorer is one edit here +
 * one new class in {@code scorers/}.
 *
 * <p>Order matters only for log readability — the activity calls each in
 * sequence. Output gets stored in {@code scored_events} regardless of
 * scorer order; the selector ranks across all rows.
 */
public final class EventScorerRegistry {

    private EventScorerRegistry() {}

    public static final List<EventScorer> ALL = List.of(
            new HaltScorer(),
            new LargeTradeScorer(),
            new SweepScorer(),
            new PostCancelClusterScorer(),
            new LayeringScorer(),
            new IcebergScorer(),
            new LiquidityWithdrawalScorer(),
            // interday scorers — read beyond a single day via the durable
            // per-symbol baselines (daily_volume_by_symbol cagg +
            // daily_lifetime_by_symbol table) over the trailing window:
            new VolumeDeviationScorer(),
            new TimeInBookDriftScorer()
    );
}
