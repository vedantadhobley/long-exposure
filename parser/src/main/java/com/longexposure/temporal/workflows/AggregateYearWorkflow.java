package com.longexposure.temporal.workflows;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

import java.time.LocalDate;

/**
 * AGGREGATE-year workflow — one LLM call per year producing the capstone
 * retrospective from that year's quarterly rollups. Mirror of
 * {@link AggregateQuarterWorkflow} one tier up. Deeply dormant until
 * ≥{@code MIN_QUARTERS_FOR_YEAR} quarterly rollups exist (~Q3 2027 first
 * non-dormant fire under the current launch timeline).
 */
@WorkflowInterface
public interface AggregateYearWorkflow {

    @WorkflowMethod
    long run(LocalDate anyDateInYear);
}
