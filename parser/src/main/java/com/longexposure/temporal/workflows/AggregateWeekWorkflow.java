package com.longexposure.temporal.workflows;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

import java.time.LocalDate;

/**
 * AGGREGATE workflow — one LLM call per week producing the week's themes
 * paragraph from that week's daily syntheses. The level above
 * {@link SynthesizeDayWorkflow}.
 *
 * <p>Single activity call, no fan-out. LLM-bearing, so it shares the
 * JVM-wide {@code LlamaClient} 2-slot cap with NARRATE / INTERPRET /
 * SYNTHESIZE — per the operational rule, only one LLM-bearing workflow
 * runs at a time.
 *
 * <p>Intended cadence: a weekly cron (e.g. Saturday, after the week's last
 * daily pipeline) — not yet wired to a schedule. For now, ad-hoc:
 * <pre>
 *   docker exec long-exposure-dev-temporal temporal workflow start \
 *     --task-queue long-exposure-daily-pipeline \
 *     --type AggregateWeekWorkflow --workflow-id aggregate-YYYYMMDD \
 *     --input '[YYYY,M,D]'
 * </pre>
 * The input is any date in the target week; the activity resolves it to the
 * week's Monday.
 */
@WorkflowInterface
public interface AggregateWeekWorkflow {

    @WorkflowMethod
    long run(LocalDate anyDateInWeek);
}
