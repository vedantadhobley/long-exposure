package com.longexposure.scoring;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.sql.Connection;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Per-scoring-run context handed to each {@link EventScorer}. Carries the
 * trading date, a JDBC {@link Connection} for reads (and writes if a
 * scorer chooses to do its own COPY), the optional pipeline run UUID to
 * tag scored_events.pipeline_run with, and a shared {@link ObjectMapper}
 * for JSON building.
 *
 * <p>Records are immutable. Scorers should NOT close the connection;
 * the activity owns its lifecycle.
 */
public record ScoringContext(
        LocalDate    tradingDate,
        Connection   conn,
        UUID         pipelineRunId,        // nullable
        ObjectMapper json
) {}
