package com.longexposure.temporal.workflows;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

import java.time.LocalDate;

/**
 * AGGREGATE-quarter workflow — one LLM call per quarter producing the
 * quarter's themes paragraph from that quarter's weekly rollups. Mirror of
 * {@link AggregateWeekWorkflow} one tier up. Sits dormant (activity returns
 * 0 without an LLM call) until ≥{@code MIN_WEEKS_FOR_QUARTER} weekly rollups
 * exist for the quarter; first non-dormant fire is the DailyPipelineWorkflow
 * after enough weeks accumulate (~late September under the current launch
 * timeline).
 *
 * <p>LLM-bearing; shares the JVM-wide {@code LlamaClient} 2-slot cap with
 * NARRATE / INTERPRET / SYNTHESIZE / AggregateWeek — only one LLM-bearing
 * workflow runs at a time.
 */
@WorkflowInterface
public interface AggregateQuarterWorkflow {

    @WorkflowMethod
    long run(LocalDate anyDateInQuarter);
}
