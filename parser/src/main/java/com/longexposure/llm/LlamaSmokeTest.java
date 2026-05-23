package com.longexposure.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;

/**
 * Standalone CLI for iterating on LLM prompts against real
 * {@code selected_events} rows without needing Temporal activities
 * or the full pipeline. Triggered by env var {@code IEX_LLM_SMOKE}.
 *
 * <p>Usage modes:
 * <ul>
 *   <li>{@code IEX_LLM_SMOKE=ping} — minimal "are you there" round-trip
 *   <li>{@code IEX_LLM_SMOKE=event:<event_id>} — narrate one scored event
 *   <li>{@code IEX_LLM_SMOKE=scorer:<scorer_id>:<date>} — narrate the
 *       top selected event for a (scorer_id, trading_date), e.g.
 *       {@code scorer:halt:2026-05-08}
 *   <li>{@code IEX_LLM_SMOKE=all:<date>} — narrate the top selected
 *       event for every scorer registered on that date, in one run.
 * </ul>
 *
 * <p>Run from host:
 * <pre>
 *   docker compose -f docker-compose.dev.yml run --rm \
 *       -e IEX_LLM_SMOKE=scorer:halt:2026-05-08 worker
 * </pre>
 */
public final class LlamaSmokeTest {

    private LlamaSmokeTest() {}

    public static void run(final String arg) throws Exception {
        System.out.println("== LLM smoke test ==");
        System.out.println("arg:        " + arg);

        LlamaClient llama = LlamaClient.fromEnv();
        System.out.println("endpoint:   " + System.getenv().getOrDefault("LLAMA_URL", "<default>"));
        System.out.println();

        if ("ping".equals(arg)) {
            runPing(llama);
            return;
        }
        if (arg.startsWith("event:")) {
            long eventId = Long.parseLong(arg.substring("event:".length()).trim());
            narrateEventById(llama, eventId);
            return;
        }
        if (arg.startsWith("scorer:")) {
            String rest = arg.substring("scorer:".length());
            int colon = rest.indexOf(':');
            if (colon <= 0) {
                System.err.println("expected scorer:<scorer_id>:<YYYY-MM-DD>");
                return;
            }
            String scorerId = rest.substring(0, colon);
            String date     = rest.substring(colon + 1);
            narrateTopForScorer(llama, scorerId, date);
            return;
        }
        if (arg.startsWith("all:")) {
            String date = arg.substring("all:".length());
            narrateAllScorersForDate(llama, date);
            return;
        }
        System.err.println("Unrecognized IEX_LLM_SMOKE value. See LlamaSmokeTest javadoc.");
    }

    private static void narrateAllScorersForDate(final LlamaClient llama, final String date) throws Exception {
        // Enumerate distinct scorer_ids that have selected events on this date.
        java.util.List<String> scorerIds = new java.util.ArrayList<>();
        try (Connection conn = openConnection();
             PreparedStatement st = conn.prepareStatement(
                     "SELECT DISTINCT scorer_id FROM selected_events WHERE trading_date = ? ORDER BY scorer_id")) {
            st.setObject(1, java.time.LocalDate.parse(date));
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) scorerIds.add(rs.getString(1));
            }
        }

        System.out.println("scorer_ids found: " + scorerIds);
        System.out.println();

        for (String scorerId : scorerIds) {
            System.out.println();
            System.out.println("════════════════════════════════════════════════════════════════════");
            System.out.println("  " + scorerId);
            System.out.println("════════════════════════════════════════════════════════════════════");
            try {
                narrateTopForScorer(llama, scorerId, date);
            } catch (Exception e) {
                System.err.println("FAILED for scorer=" + scorerId + ": " + e.getMessage());
            }
        }
    }

    // ─── modes ───────────────────────────────────────────────────────────────

    private static void runPing(final LlamaClient llama) {
        System.out.println("== ping ==");
        String resp = llama.chat(
                "You are a brief assistant. Reply in one sentence.",
                "Say 'hello from llama-large' and identify the model you are.");
        System.out.println("response: " + resp);
    }

    private static void narrateEventById(final LlamaClient llama, final long eventId) throws Exception {
        try (Connection conn = openConnection()) {
            try (PreparedStatement st = conn.prepareStatement(
                    "SELECT scorer_id, symbol, trading_date, ts, score, breakdown::text "
                  + "FROM selected_events WHERE selected_id = ?")) {
                st.setLong(1, eventId);
                try (ResultSet rs = st.executeQuery()) {
                    if (!rs.next()) {
                        System.err.println("no selected_event with selected_id=" + eventId);
                        return;
                    }
                    narrateRow(llama, rs);
                }
            }
        }
    }

    private static void narrateTopForScorer(final LlamaClient llama, final String scorerId, final String date) throws Exception {
        try (Connection conn = openConnection()) {
            try (PreparedStatement st = conn.prepareStatement(
                    "SELECT scorer_id, symbol, trading_date, ts, score, breakdown::text "
                  + "FROM selected_events WHERE scorer_id = ? AND trading_date = ? "
                  + "ORDER BY narration_rank ASC LIMIT 1")) {
                st.setString(1, scorerId);
                st.setObject(2, java.time.LocalDate.parse(date));
                try (ResultSet rs = st.executeQuery()) {
                    if (!rs.next()) {
                        System.err.println("no selected_events for scorer=" + scorerId + " date=" + date);
                        return;
                    }
                    narrateRow(llama, rs);
                }
            }
        }
    }

    private static void narrateRow(final LlamaClient llama, final ResultSet rs) throws Exception {
        String scorerId    = rs.getString("scorer_id");
        String symbol      = rs.getString("symbol");
        String tradingDate = rs.getString("trading_date");
        String ts          = rs.getString("ts");
        double score       = rs.getDouble("score");
        String breakdown   = rs.getString("breakdown");

        System.out.println("== input ==");
        System.out.println("scorer_id:    " + scorerId);
        System.out.println("symbol:       " + symbol);
        System.out.println("trading_date: " + tradingDate);
        System.out.println("ts:           " + ts);
        System.out.println("score:        " + score);
        System.out.println("breakdown:    " + breakdown);
        System.out.println();

        // Pretty-print the breakdown JSON
        ObjectMapper json = new ObjectMapper();
        JsonNode parsed = json.readTree(breakdown);
        String prettyBreakdown = json.writerWithDefaultPrettyPrinter().writeValueAsString(parsed);

        // Minimal one-shot prompt to start the iteration. We'll layer the
        // two-pass extract/render/verify design on top once we see what
        // the model does with raw input.
        String system =
                "You are a market-data journalist writing for the Long Exposure site, " +
                "a daily column on IEX exchange activity. Your style is factual, " +
                "concise, and uses plain English over financial jargon when possible. " +
                "Each event you narrate is one observed market microstructure event " +
                "with a structured set of facts.\n\n" +
                "STRICT GROUNDING RULE: every quantitative claim or named entity in your output " +
                "must trace to a field in the structured input below. Do not invent numbers, " +
                "do not speculate on intent, do not reference market context outside the input. " +
                "If a field is missing or null, do not narrate that aspect.\n\n" +
                "Output 2-3 sentences of prose. No bullet points, no headers, no preamble.";

        // NOTE: deliberately NOT including the internal `score` value here.
        // It leaked into prose as "scored at X within the IEX scoring system"
        // — internal metric, not narratable.
        // ts comes in as a Postgres timestamp string with a +00 offset; parse
        // it back to an Instant so we can produce an ET anchor for the prompt.
        Instant anchorUtc = java.time.OffsetDateTime
                .parse(ts.replace(" ", "T").replace("+00", "+00:00"))
                .toInstant();
        String anchorEt = com.longexposure.scoring.BreakdownFmt.toEtTime(anchorUtc);

        String user =
                "Event type: " + scorerId + "\n" +
                "Symbol: " + symbol + "\n" +
                "Trading date: " + tradingDate + "\n" +
                "Event start: " + anchorEt + " (" + ts + " UTC)\n\n" +
                "Structured facts (this is the ground truth — every claim must trace to here. " +
                "When both raw and human-readable versions of a value are present, prefer the human-readable one):\n" +
                prettyBreakdown;

        System.out.println("== prompt (system) ==");
        System.out.println(system);
        System.out.println();
        System.out.println("== prompt (user) ==");
        System.out.println(user);
        System.out.println();
        System.out.println("== response ==");
        long t0 = System.nanoTime();
        String response = llama.chat(system, user);
        long ms = (System.nanoTime() - t0) / 1_000_000L;
        System.out.println(response);
        System.out.println();
        System.out.println("(elapsed " + ms + " ms)");
    }

    private static Connection openConnection() throws Exception {
        String host = System.getenv().getOrDefault("POSTGRES_HOST", "localhost");
        String port = System.getenv().getOrDefault("POSTGRES_PORT", "5432");
        String db   = System.getenv().getOrDefault("POSTGRES_DB", "longexposure");
        String user = System.getenv().getOrDefault("POSTGRES_USER", "leuser");
        String pwd  = System.getenv().getOrDefault("POSTGRES_PASSWORD", "lepass");
        String url = "jdbc:postgresql://" + host + ":" + port + "/" + db;
        return DriverManager.getConnection(url, user, pwd);
    }
}
