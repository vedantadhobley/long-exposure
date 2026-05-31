package com.longexposure.narration;

import com.longexposure.llm.LlamaClient;
import com.longexposure.llm.SamplingParams;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Layer-2 interpretation smoke test (original concept variant).
 *
 * <p>This variant implements the original {@code concepts.md} §5 / §10C
 * "Layer-0 expansion" concept: for each event, query the surrounding
 * wire data (trades on the same symbol in the ±60-sec window around the
 * event) and feed a summary alongside the breakdown so the LLM can
 * identify sequential / causal context — "the layering came just before
 * an 8K-share market buy at the same price" — rather than only
 * paraphrasing the catalog applied to the event's own measurements.
 *
 * <p>Triggered by {@code IEX_INTERPRET_SMOKE=<YYYY-MM-DD>}. Reads up to
 * 3 narratives per scorer type from the given trading date, queries the
 * surrounding trade window per event, calls the LLM once per event,
 * prints (Layer-2 prose ↔ surrounding-context summary ↔ Layer-2-interp)
 * to stdout. Does NOT write to the database.
 */
public final class InterpretSmokeTest {

    private InterpretSmokeTest() {}

    /** Up to N events per scorer to sample. */
    private static final int SAMPLES_PER_SCORER = 3;

    /** Half-window around the event boundaries to query trade context for. */
    private static final long WINDOW_SECONDS = 60L;

    private static final String SYSTEM_PROMPT = """
            You write ONE observation about what was happening in the IEX market
            AROUND a specific detected event. Your goal is sequential / causal
            context — the framing that explains why this event mattered in the
            flow of trading on the day, not a textbook description of the
            pattern type.

            INPUTS YOU RECEIVE:
              - Layer-2 description (what the event was)
              - Breakdown JSON (the event's own measurements)
              - Surrounding wire context: trades on the same symbol in the
                  pre-event (60 sec before event start) and post-event
                  (60 sec after event end) windows, pre-aggregated to:
                  trade count, total shares, total notional, VWAP,
                  price range, first/last timestamp
              - Catalog entry (vocabulary for the pattern type)

            YOUR JOB: write 1-2 sentences that identify the sequential context
            the surrounding data reveals. The catalog supplies vocabulary;
            the surrounding data supplies the narrative.

            EXAMPLES of what we want (illustrative shape, not from real data):
              - "The layering at $429.50 preceded a 8,200-share post-window
                 execution at $431.00 within 47 seconds — wire data shows
                 aggressive consumption arriving immediately after the
                 layering closed."
              - "The liquidity withdrawal opened a window where 47 trades
                 totaling 198,300 shares executed at a VWAP of $24.97 in the
                 60 seconds following — depth contraction did not stop
                 incoming flow."
              - "No notable trading flanks this event in either ±60-sec window
                 (0 trades pre, 2 trades post for 200 shares); the pattern
                 appears in isolation."

            WHAT YOU MAY DO:
              - Reference numbers from the breakdown OR the surrounding context.
              - Note sequence: pre vs post.
              - State explicitly if the surrounding context is empty or quiet
                — that's a valid observation, not a failure.
              - Use catalog vocabulary for pattern terminology.

            WHAT YOU MAY NOT DO:
              - Claim intent ("the trader was X-ing", "this was spoofing").
              - Introduce numbers not in the breakdown or surrounding context.
              - Speculate about unobserved data ("likely on NYSE too",
                "the news must have").
              - Editorialize ("striking", "unusual", "remarkable").

            STYLE:
              - Quantitative, financial-journalist register.
              - 1-2 sentences. Hard cap 350 chars.
              - Acronyms (LULD, NBBO, MCB, VWAP, HFT) glossed once.
              - No preamble. Just the observation.
            """;

    public static void run(final String dateStr) throws Exception {
        LocalDate date = LocalDate.parse(dateStr);
        System.out.println("== Layer-2 interpretation smoke test (surrounding-context variant) ==");
        System.out.println("trading_date: " + date);
        System.out.println("samples per scorer: " + SAMPLES_PER_SCORER);
        System.out.println("window: ±" + WINDOW_SECONDS + " sec around event");
        System.out.println();

        Map<String, List<Sample>> bucket = new HashMap<>();
        try (Connection conn = openConnection()) {
            // Pull top-N per scorer from narratives + ts/ts_end from selected_events.
            String sql = """
                    SELECT
                      n.event_type,
                      n.symbol,
                      n.narrative,
                      n.score_breakdown::text AS breakdown_text,
                      se.ts AS event_ts,
                      se.ts_end AS event_ts_end,
                      ROW_NUMBER() OVER (PARTITION BY n.event_type ORDER BY n.score DESC) AS rn
                    FROM narratives n
                    JOIN selected_events se ON se.selected_id = n.selected_id
                    WHERE n.trading_date = ?
                      AND n.verifier_passed = true
                    """;
            try (PreparedStatement st = conn.prepareStatement(sql)) {
                st.setObject(1, date);
                try (ResultSet rs = st.executeQuery()) {
                    while (rs.next()) {
                        int rn = rs.getInt("rn");
                        if (rn > SAMPLES_PER_SCORER) continue;
                        String scorerId = rs.getString("event_type");
                        Timestamp tsStart = rs.getTimestamp("event_ts");
                        Timestamp tsEnd = rs.getTimestamp("event_ts_end");
                        if (tsEnd == null) tsEnd = tsStart;
                        bucket.computeIfAbsent(scorerId, k -> new ArrayList<>()).add(
                                new Sample(scorerId,
                                           rs.getString("symbol"),
                                           rs.getString("narrative"),
                                           rs.getString("breakdown_text"),
                                           tsStart,
                                           tsEnd));
                    }
                }
            }

            LlamaClient llama = LlamaClient.fromEnv();

            int total = 0;
            long t0 = System.nanoTime();
            for (Map.Entry<String, List<Sample>> e : bucket.entrySet()) {
                String scorerId = e.getKey();
                Catalog.Entry entry = Catalog.forScorer(scorerId);
                if (entry == null) {
                    System.out.println("[skip] no catalog entry for scorer: " + scorerId);
                    continue;
                }
                for (Sample s : e.getValue()) {
                    total++;

                    // Pre window: [event_ts - 60s, event_ts)
                    Timestamp preFrom = new Timestamp(s.eventTs.getTime() - WINDOW_SECONDS * 1000L);
                    TradeWindow pre = queryTradeWindow(conn, s.symbol, preFrom, s.eventTs);
                    // Post window: [event_ts_end, event_ts_end + 60s)
                    Timestamp postTo = new Timestamp(s.eventTsEnd.getTime() + WINDOW_SECONDS * 1000L);
                    TradeWindow post = queryTradeWindow(conn, s.symbol, s.eventTsEnd, postTo);

                    String userPrompt = buildUserPrompt(s, entry, pre, post);
                    long callT0 = System.nanoTime();
                    String interpretation = llama.chat(SYSTEM_PROMPT, userPrompt, SamplingParams.RENDER).trim();
                    long callMs = (System.nanoTime() - callT0) / 1_000_000L;

                    System.out.println("──────────────────────────────────────────────────────────────────────");
                    System.out.printf("[%d] %s · %s%n", total, scorerId, s.symbol);
                    System.out.println("EVENT WINDOW: " + s.eventTs + " → " + s.eventTsEnd);
                    System.out.println("LAYER-2 (description):");
                    System.out.println("  " + wrap(s.narrative, 72, "  "));
                    System.out.println("SURROUNDING WIRE CONTEXT (same symbol, DPLS feed):");
                    System.out.println("  PRE  (" + WINDOW_SECONDS + "s before event start): " + formatWindow(pre));
                    System.out.println("  POST (" + WINDOW_SECONDS + "s after event end):    " + formatWindow(post));
                    System.out.println("LAYER-2-INTERPRETATION (" + callMs + " ms):");
                    System.out.println("  " + wrap(interpretation, 72, "  "));
                    System.out.println();
                }
            }

            long elapsedSec = (System.nanoTime() - t0) / 1_000_000_000L;
            System.out.println("──────────────────────────────────────────────────────────────────────");
            System.out.printf("done — %d interpretations, %d sec total%n", total, elapsedSec);
        }
    }

    /** Aggregate trades on a symbol within [from, to). */
    private static TradeWindow queryTradeWindow(final Connection conn,
                                                 final String symbol,
                                                 final Timestamp from,
                                                 final Timestamp to) throws Exception {
        String sql = """
                SELECT
                  COUNT(*)                                AS trade_count,
                  COALESCE(SUM(size), 0)                  AS total_shares,
                  COALESCE(SUM(size::bigint * price_raw), 0) AS total_notional_raw,
                  MIN(price_raw)                          AS min_price_raw,
                  MAX(price_raw)                          AS max_price_raw,
                  MIN(ts)                                 AS first_ts,
                  MAX(ts)                                 AS last_ts
                FROM trades
                WHERE symbol = ?
                  AND ts >= ?
                  AND ts <  ?
                  AND feed_source = 'DPLS'
                """;
        try (PreparedStatement st = conn.prepareStatement(sql)) {
            st.setString(1, symbol);
            st.setTimestamp(2, from);
            st.setTimestamp(3, to);
            try (ResultSet rs = st.executeQuery()) {
                if (!rs.next()) return TradeWindow.empty();
                long count = rs.getLong("trade_count");
                long totalShares = rs.getLong("total_shares");
                long totalNotionalRaw = rs.getLong("total_notional_raw");
                Long minPriceRaw = (Long) rs.getObject("min_price_raw");
                Long maxPriceRaw = (Long) rs.getObject("max_price_raw");
                Timestamp firstTs = rs.getTimestamp("first_ts");
                Timestamp lastTs = rs.getTimestamp("last_ts");
                return new TradeWindow(count, totalShares, totalNotionalRaw,
                                       minPriceRaw, maxPriceRaw, firstTs, lastTs);
            }
        }
    }

    private static String formatWindow(final TradeWindow w) {
        if (w.tradeCount == 0) return "no trades";
        double totalNotionalDollars = w.totalNotionalRaw / 10_000.0;
        double vwap = (w.totalShares > 0) ? totalNotionalDollars / w.totalShares : 0.0;
        double minPrice = (w.minPriceRaw != null) ? w.minPriceRaw / 10_000.0 : 0.0;
        double maxPrice = (w.maxPriceRaw != null) ? w.maxPriceRaw / 10_000.0 : 0.0;
        return String.format("%d trades, %,d shares, $%,.2f notional, VWAP $%.4f, range $%.4f–$%.4f",
                w.tradeCount, w.totalShares, totalNotionalDollars, vwap, minPrice, maxPrice);
    }

    private static String buildUserPrompt(final Sample s,
                                          final Catalog.Entry entry,
                                          final TradeWindow pre,
                                          final TradeWindow post) {
        StringBuilder drivers = new StringBuilder();
        for (String d : entry.documentedDrivers()) drivers.append("  - ").append(d).append('\n');
        return  "Scorer: " + s.scorerId + "\n\n"
              + "Pattern at the wire level:\n  " + entry.mechanism() + "\n\n"
              + "Documented drivers (multiple legitimate causes — never a single intent claim):\n"
              + drivers + "\n"
              + "Layer-2 narration (the description, for context — do not paraphrase):\n  "
              + s.narrative + "\n\n"
              + "Breakdown JSON (the event's own measurements):\n"
              + s.breakdownText + "\n\n"
              + "Surrounding wire context (same symbol, DPLS feed, ±" + WINDOW_SECONDS + " sec around event):\n"
              + "  PRE  window [event_ts - " + WINDOW_SECONDS + "s, event_ts):    " + formatWindow(pre) + "\n"
              + "  POST window [event_ts_end, event_ts_end + " + WINDOW_SECONDS + "s): " + formatWindow(post) + "\n\n"
              + "Now write 1-2 sentences identifying the sequential / causal context "
              + "the surrounding data reveals. If both PRE and POST windows are empty / "
              + "quiet, say so — isolation is a valid observation. Stay grounded — every "
              + "number must come from the breakdown or the surrounding context.";
    }

    /** Simple 72-column word-wrap for printable output. */
    private static String wrap(final String text, final int width, final String continuationIndent) {
        if (text == null) return "";
        StringBuilder out = new StringBuilder();
        int col = 0;
        for (String word : text.split("\\s+")) {
            if (col == 0) {
                out.append(word);
                col = word.length();
            } else if (col + 1 + word.length() <= width) {
                out.append(' ').append(word);
                col += 1 + word.length();
            } else {
                out.append('\n').append(continuationIndent).append(word);
                col = word.length();
            }
        }
        return out.toString();
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

    private record Sample(String scorerId,
                          String symbol,
                          String narrative,
                          String breakdownText,
                          Timestamp eventTs,
                          Timestamp eventTsEnd) {}

    private record TradeWindow(long tradeCount,
                                long totalShares,
                                long totalNotionalRaw,
                                Long minPriceRaw,
                                Long maxPriceRaw,
                                Timestamp firstTs,
                                Timestamp lastTs) {
        static TradeWindow empty() {
            return new TradeWindow(0, 0, 0, null, null, null, null);
        }
    }
}
