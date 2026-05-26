package com.longexposure.scoring;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.sql.Connection;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

/**
 * Per-scoring-run context handed to each {@link EventScorer}. Carries the
 * trading date, a JDBC {@link Connection} for reads (and writes if a
 * scorer chooses to do its own COPY), the optional pipeline run UUID to
 * tag scored_events.pipeline_run with, a shared {@link ObjectMapper}
 * for JSON building, a {@link HeartbeatCallback} the scorer's inner
 * loops invoke periodically to keep the host Temporal activity alive,
 * the in-memory {@link SymbolMetadata} cache that scorers consult to
 * enrich event breakdowns, and a {@link BaselineProvider} the inter-day
 * scorers read historical baselines through.
 *
 * <p>Records are immutable. Scorers should NOT close the connection;
 * the activity owns its lifecycle.
 *
 * <p>The {@code symbols} map is loaded ONCE per scoring run from the
 * {@code symbols} reference table by the host activity. Per-event
 * lookup is O(1) and incurs zero database calls.
 *
 * <p>{@code baselines} is built once per run too, sharing {@code conn}; it
 * decouples the inter-day scorers from the cagg SQL (see
 * {@link CaggBaselineProvider}).
 */
public record ScoringContext(
        LocalDate                    tradingDate,
        Connection                   conn,
        UUID                         pipelineRunId,    // nullable
        ObjectMapper                 json,
        HeartbeatCallback            heartbeat,
        Map<String, SymbolMetadata>  symbols,
        BaselineProvider             baselines
) {
    /** Heartbeat hook the scorer's hot loops should call every N rows. */
    @FunctionalInterface
    public interface HeartbeatCallback {
        /** Send a heartbeat to the host activity. The detail is freeform. */
        void send(String detail);
    }

    /**
     * Convenience lookup. Returns {@code null} if the symbol isn't in the
     * reference table (likely an obscure or newly-listed ticker that wasn't
     * in the last weekly refresh). Scorers should null-check.
     */
    public SymbolMetadata lookupSymbol(final String symbol) {
        return symbol == null ? null : symbols.get(symbol);
    }
}
