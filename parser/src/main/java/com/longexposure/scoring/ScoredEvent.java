package com.longexposure.scoring;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.LocalDate;

/**
 * One row destined for the {@code scored_events} table. See
 * {@code docs/scoring-and-narration.md} "The schema" section for the
 * column mapping.
 *
 * @param tradingDate the day the event belongs to (UTC date)
 * @param symbol      the ticker
 * @param ts          event anchor time (the trade time, the halt start, etc.)
 * @param tsEnd       null for instantaneous events; set for durational (halt span, sweep span)
 * @param scorerId    matches the {@link EventScorer#id()} that produced this event
 * @param score       higher = more significant. Domain conventions per scorer; the selector ranks across all scorers on raw score
 * @param breakdown   transparency JSON. Every claim in eventual narration must trace to a field here
 * @param sourceRefs  array of pointers back to hypertable rows, shape {@code [{"table":"trades","ts_nanos":...,"trade_id":...}, ...]}
 */
public record ScoredEvent(
        LocalDate  tradingDate,
        String     symbol,
        Instant    ts,
        Instant    tsEnd,
        String     scorerId,
        double     score,
        JsonNode   breakdown,
        JsonNode   sourceRefs
) {}
