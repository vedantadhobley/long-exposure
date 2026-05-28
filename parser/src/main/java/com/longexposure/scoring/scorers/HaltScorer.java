package com.longexposure.scoring.scorers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.longexposure.scoring.EventScorer;
import com.longexposure.scoring.BreakdownFmt;
import com.longexposure.scoring.ScoredEvent;
import com.longexposure.scoring.ScoringContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.function.Consumer;

/**
 * Halt detector. Reads {@code status_events} for {@code event_kind='H'}
 * (Trading Status messages) on the trading date and emits one
 * {@link ScoredEvent} per halt → resume pair.
 *
 * <p>Detection: a {@code sub_type='H'} row starts a halt for the symbol;
 * the next {@code status_events} row for that same symbol with
 * {@code sub_type IN ('T','O','P')} closes it. The halt duration is
 * {@code resume.ts - halt.ts}.
 *
 * <p>Score for v1: halt duration in seconds. Liquidity-tier weighting is
 * a TODO — needs a symbol-metadata source we don't have wired yet.
 *
 * <p>Breakdown JSON shape:
 * <pre>
 *   {
 *     "halt_reason":      "T1",
 *     "halt_duration_s":  243,
 *     "halt_start_iso":   "2026-05-08T14:23:14.847291000Z",
 *     "halt_end_iso":     "2026-05-08T14:27:17.521003000Z",
 *     "halt_end_sub_type": "T"
 *   }
 * </pre>
 *
 * <p>Source refs: a single entry pointing at the halt-start row in
 * {@code status_events}, keyed by {@code (symbol, ts_nanos)}.
 */
public final class HaltScorer implements EventScorer {

    private static final Logger LOG = LoggerFactory.getLogger(HaltScorer.class);

    @Override
    public String id() { return "halt"; }

    @Override
    public void score(final ScoringContext ctx, final Consumer<ScoredEvent> emit) {
        // Window-function pairing of halt rows with their resume rows
        // (next non-'H' status for the same symbol). NULL halt_end means
        // the halt was still open at the next session boundary; for v1
        // we emit those with halt_duration_s = null and let the narrator
        // handle it (score = 0 → low-ranked, but visible).
        String sql = """
                SELECT halt_symbol,
                       halt_ts,
                       halt_ts_nanos,
                       halt_reason,
                       next_ts,
                       next_sub_type
                FROM (
                    SELECT symbol      AS halt_symbol,
                           ts          AS halt_ts,
                           ts_nanos    AS halt_ts_nanos,
                           reason      AS halt_reason,
                           sub_type    AS halt_sub_type,
                           LEAD(ts)       OVER (PARTITION BY symbol ORDER BY ts) AS next_ts,
                           LEAD(sub_type) OVER (PARTITION BY symbol ORDER BY ts) AS next_sub_type
                    FROM status_events
                    WHERE feed_source = 'DPLS'
                      AND event_kind  = 'H'
                      AND symbol IS NOT NULL
                      AND ts >= ? AND ts < ?
                ) s
                WHERE halt_sub_type = 'H'
                ORDER BY halt_ts
                """;

        Timestamp from = Timestamp.from(ctx.tradingDate().atStartOfDay().toInstant(ZoneOffset.UTC));
        Timestamp to   = Timestamp.from(ctx.tradingDate().plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC));

        long emitted = 0;
        try (PreparedStatement st = ctx.conn().prepareStatement(sql)) {
            st.setTimestamp(1, from);
            st.setTimestamp(2, to);
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    emit.accept(buildEvent(ctx, rs));
                    emitted++;
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("HaltScorer query failed for date=" + ctx.tradingDate(), e);
        }

        LOG.info("HaltScorer  date={} halts_emitted={}", ctx.tradingDate(), emitted);
    }

    private static ScoredEvent buildEvent(final ScoringContext ctx, final ResultSet rs) throws Exception {
        String symbol      = rs.getString("halt_symbol");
        Instant haltStart  = rs.getTimestamp("halt_ts").toInstant();
        long    haltNanos  = rs.getLong("halt_ts_nanos");
        String  reason     = rs.getString("halt_reason");
        Timestamp nextTsTs = rs.getTimestamp("next_ts");
        String  nextSub    = rs.getString("next_sub_type");

        Instant haltEnd = (nextTsTs != null) ? nextTsTs.toInstant() : null;
        Long durationS  = (haltEnd != null) ? (haltEnd.getEpochSecond() - haltStart.getEpochSecond()) : null;

        ObjectMapper json = ctx.json();
        ObjectNode breakdown = json.createObjectNode();
        breakdown.put("halt_reason",       reason);
        // Pre-formatted noun-phrase the LLM can use directly. When halt_reason
        // is a known IEX/NMS code we render the documented kind; when it's
        // missing/NA/null we emit the safe generic "trading halt" — never
        // "regulatory" or "circuit-breaker" or "news halt", since we have no
        // wire-data basis for those classifications.
        //
        // Why this is structural and not a prompt rule: the breakdown carries
        // a pre-computed safe label; INTERPRET reads it verbatim instead of
        // inferring from halt_reason="NA" (which leaked "regulatory
        // suspension" on SSCP — observed 2026-05-28 audit). The data layer
        // never carries the reason-specific qualifier when the reason is
        // unknown, so the LLM has nothing to mis-elaborate.
        breakdown.put("halt_reason_label", haltReasonLabel(reason));
        breakdown.put("halt_duration",     durationS != null ? BreakdownFmt.durationSec(durationS) : "unbounded");
        breakdown.put("halt_start_et",     BreakdownFmt.toEtTime(haltStart));
        if (haltEnd != null)   breakdown.put("halt_end_et", BreakdownFmt.toEtTime(haltEnd)); else breakdown.putNull("halt_end_et");
        breakdown.put("halt_resumed",      "T".equals(nextSub));     // boolean — "did it resume cleanly?"

        // ─── derived fields (L1 enrichment, 2026-05-22) ──────────────────
        // Pre-compute every quantity the LLM might want to mention so it
        // never has to do arithmetic at inference time (the failure mode
        // observed in the MOBI 20%-vs-86% smoke test, 2026-05-22).
        if (durationS != null) {
            breakdown.put("halt_duration_seconds", durationS);
            breakdown.put("halt_duration_pct_of_regular_session",
                          BreakdownFmt.round(durationS * 100.0 / BreakdownFmt.REGULAR_SESSION_SECONDS, 1));
            breakdown.put("halt_duration_bucket", durationBucket(durationS));
        }
        breakdown.put("halt_start_session_phase",  BreakdownFmt.sessionPhase(haltStart));
        breakdown.put("halt_start_phase_label",    BreakdownFmt.sessionPhaseLabel(haltStart));
        if (haltEnd != null) {
            breakdown.put("halt_end_session_phase",  BreakdownFmt.sessionPhase(haltEnd));
            breakdown.put("halt_end_phase_label",    BreakdownFmt.sessionPhaseLabel(haltEnd));
        }

        com.longexposure.scoring.SymbolFields.apply(breakdown, ctx, symbol);

        ArrayNode sourceRefs = json.createArrayNode();
        ObjectNode ref = json.createObjectNode();
        ref.put("table",    "status_events");
        ref.put("symbol",   symbol);
        ref.put("ts_nanos", haltNanos);
        sourceRefs.add(ref);

        // Score = log10(duration_seconds + 1). Linear duration over-weighted
        // marathon halts: a 4 h halt scored 48× a 5 min halt's score, so
        // selection chose 1-2 multi-hour halts at the expense of multiple
        // short-but-interesting halts. Log compresses the ratio: 4 h vs
        // 5 min becomes 4.2 vs 2.5 (≈1.7× ratio), keeping ordinal order
        // intact while letting selection see a mix of durations.
        // The +1 prevents log10(0) for instantaneous-end halts. Unbounded
        // halts (no resume in the window) still score 0 — they're visible
        // but ranked low until we figure out the right policy.
        // 2026-05-28 evening (R4 / option C).
        double score = (durationS != null) ? Math.log10(durationS.doubleValue() + 1.0) : 0.0;

        return new ScoredEvent(
                ctx.tradingDate(),
                symbol,
                haltStart,
                haltEnd,
                "halt",
                score,
                breakdown,
                sourceRefs);
    }

    /**
     * Classify halt duration into a qualitative bucket the LLM can lean on
     * without inventing comparative phrasing. Thresholds derived from
     * NMS / IEX practice: LULD pauses are typically 5 min; news halts
     * commonly run 30 min – 2 hr; IPO / regulatory halts can span a
     * session or longer.
     */
    private static String durationBucket(final long seconds) {
        if (seconds < 300)               return "under_5min";       // sub-LULD
        if (seconds < 1_800)             return "5_to_30min";       // LULD-to-news-typical
        if (seconds < 7_200)             return "30min_to_2h";      // news-pending-typical
        if (seconds < BreakdownFmt.REGULAR_SESSION_SECONDS / 2) return "2h_to_half_session";
        if (seconds < BreakdownFmt.REGULAR_SESSION_SECONDS)    return "half_to_full_session";
        return "exceeds_full_session";
    }

    /**
     * Pre-formatted noun-phrase for the halt classification. Reads the
     * IEX/NMS code from {@code halt_reason} and returns a safe descriptive
     * label the LLM can use verbatim. When the reason is missing/NA/null,
     * returns the GENERIC label ({@code "trading halt"}) — never a
     * reason-specific qualifier like "regulatory" or "circuit-breaker" or
     * "news halt", because those classifications have no wire-data basis
     * when the reason code itself is absent.
     *
     * <p>Why this is structural (not a prompt rule): INTERPRET / DESCRIBE
     * prompts simply read this field; they have no way to invent a
     * classification not in the data. The 05-12 SSCP audit observation
     * ("this regulatory suspension on SSCP" with halt_reason="NA") becomes
     * impossible by construction — the data carries the safe label.
     *
     * <p>Reason codes per IEX-TP TOPS 1.66 §6.5 + NMS practice. T1 = news
     * pending; T2 = news dissemination; IPO1/2/3/D = IPO sub-states;
     * MCB1/2/3 = market-wide circuit breaker tiers; "NA" = code not
     * provided (older TOPS records / non-classified halts).
     */
    private static String haltReasonLabel(final String reason) {
        if (reason == null) return "trading halt";
        switch (reason.trim().toUpperCase()) {
            case "T1":   return "news-pending halt";
            case "T2":   return "news-dissemination halt";
            case "IPO1": return "IPO opening-auction halt";
            case "IPO2": return "IPO order-acceptance halt";
            case "IPO3": return "IPO pre-launch halt";
            case "IPOD": return "IPO deferred halt";
            case "MCB1": return "market-wide circuit-breaker level-1 halt";
            case "MCB2": return "market-wide circuit-breaker level-2 halt";
            case "MCB3": return "market-wide circuit-breaker level-3 halt";
            case "LUDP": return "LULD price-band halt";
            case "":
            case "NA":
            default:     return "trading halt";
        }
    }
}
