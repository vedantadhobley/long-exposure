package com.longexposure.temporal.workflows;

import io.temporal.workflow.ChildWorkflowOptions;
import io.temporal.workflow.Workflow;
import org.slf4j.Logger;

import java.time.LocalDate;

public final class LlmDayWorkflowImpl implements LlmDayWorkflow {

    private static final Logger LOG = Workflow.getLogger(LlmDayWorkflowImpl.class);

    @Override
    public long run(final LocalDate date) {
        String wfid = Workflow.getInfo().getWorkflowId();
        long t0 = Workflow.currentTimeMillis();

        NarrateWorkflow narrate = Workflow.newChildWorkflowStub(
                NarrateWorkflow.class,
                ChildWorkflowOptions.newBuilder()
                        .setWorkflowId("llm-narrate-" + date + "-" + wfid)
                        .build());
        long narrated = narrate.run(date);
        LOG.info("narrate done  date={} narrated={}", date, narrated);

        InterpretWorkflow interpret = Workflow.newChildWorkflowStub(
                InterpretWorkflow.class,
                ChildWorkflowOptions.newBuilder()
                        .setWorkflowId("llm-interpret-" + date + "-" + wfid)
                        .build());
        long interpreted = interpret.run(date);
        LOG.info("interpret done  date={} interpreted={}", date, interpreted);

        SynthesizeDayWorkflow synth = Workflow.newChildWorkflowStub(
                SynthesizeDayWorkflow.class,
                ChildWorkflowOptions.newBuilder()
                        .setWorkflowId("llm-synth-" + date + "-" + wfid)
                        .build());
        synth.run(date);
        LOG.info("synth done  date={} elapsed_ms={}", date, Workflow.currentTimeMillis() - t0);

        return 1;
    }
}
