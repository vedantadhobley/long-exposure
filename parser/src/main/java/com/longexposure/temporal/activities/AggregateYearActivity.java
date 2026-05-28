package com.longexposure.temporal.activities;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

import java.time.LocalDate;

/**
 * AGGREGATE year — the capstone "year in IEX microstructure" retrospective.
 * Mirror of {@link AggregateQuarterActivity} one tier up. Reads the year's
 * ≤4 quarterly rollups + prior yearly rollups for multi-year trend.
 *
 * <p>Sits deeply dormant: gated by {@code MIN_QUARTERS_FOR_YEAR=2} so the
 * activity will not call the LLM until at least 2 quarterly rollups exist
 * for the year. First non-dormant fire under the current launch timeline is
 * approximately Q3 2027 (when 2 quarters of weekly rollups have accumulated).
 *
 * <p>Reuses {@link com.longexposure.narration.SynthesisVerifier}.
 */
@ActivityInterface
public interface AggregateYearActivity {

    @ActivityMethod
    long aggregate(LocalDate anyDateInYear);
}
