package com.longexposure.narration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.longexposure.llm.LlamaClient;
import com.longexposure.llm.SamplingParams;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Layer-0 interpretation smoke test — produces ad-hoc LLM-driven
 * interpretation prose for a sample of existing narratives, prints to
 * stdout for read-cold review. Used to make the Option A vs Option B
 * architecture decision in the launch sprint (see
 * {@code docs/layer-0-design.md}).
 *
 * <p>Triggered by {@code IEX_INTERPRET_SMOKE=<YYYY-MM-DD>} env var
 * in {@link com.longexposure.Main}. Reads up to 3 narratives per
 * scorer type from the given trading date, calls the LLM once per
 * event, prints (Layer-2 prose ↔ Layer-0 interpretation) pairs.
 *
 * <p>Does NOT write to the database. The smoke test is for decision
 * input, not for production output. If the LLM-driven option wins
 * the decision, a proper {@code InterpretEventActivity} replaces this.
 */
public final class InterpretSmokeTest {

    private InterpretSmokeTest() {}

    /** Up to N events per scorer to sample. */
    private static final int SAMPLES_PER_SCORER = 3;

    private static final String SYSTEM_PROMPT = """
            You produce ONE interpretation sentence for a market microstructure event detected on the IEX exchange.

            INPUTS YOU WILL RECEIVE:
              - A Layer-2 narration describing what happened (subject, action, key numbers)
              - The catalog entry for the event's scorer, containing:
                  - mechanism: what the pattern IS at the wire level (factual)
                  - canonical_interpretation: the safe one-sentence prose

            YOUR JOB: write one sentence that interprets what THIS SPECIFIC event represents
            in market microstructure terms, drawing vocabulary EXCLUSIVELY from the catalog
            entry's mechanism + canonical_interpretation fields.

            RULES (load-bearing):
              - Vocabulary must come from the catalog entry. Do not introduce terms not in it.
              - No intent claims from wire data alone. If the catalog says "produced by X, Y,
                or (when documented) Z", you must preserve that "when documented" framing.
                Never assert intent (no "this was spoofing", "the trader was X").
              - Reference at least one specific fact from the Layer-2 narration (number,
                duration, count) to contextualize the interpretation to this event.
              - Length: one sentence, under 250 characters.
              - No preamble, no JSON, just the sentence.

            STYLE:
              - Financial-journalist register (FT or Bloomberg).
              - Acronyms (LULD, NBBO, MCB, CQI, VWAP, HFT) glossed once with full English
                expansion in parens on first use in your sentence.
            """;

    public static void run(final String dateStr) throws Exception {
        LocalDate date = LocalDate.parse(dateStr);
        System.out.println("== Layer-0 interpretation smoke test ==");
        System.out.println("trading_date: " + date);
        System.out.println("samples per scorer: " + SAMPLES_PER_SCORER);
        System.out.println();

        Map<String, List<Sample>> bucket = new HashMap<>();
        try (Connection conn = openConnection()) {
            String sql = """
                    SELECT
                      event_type, symbol, narrative,
                      score_breakdown::text AS breakdown_text,
                      ROW_NUMBER() OVER (PARTITION BY event_type ORDER BY score DESC) AS rn
                    FROM narratives
                    WHERE trading_date = ?
                      AND verifier_passed = true
                    """;
            try (PreparedStatement st = conn.prepareStatement(sql)) {
                st.setObject(1, date);
                try (ResultSet rs = st.executeQuery()) {
                    while (rs.next()) {
                        int rn = rs.getInt("rn");
                        if (rn > SAMPLES_PER_SCORER) continue;
                        String scorerId = rs.getString("event_type");
                        bucket.computeIfAbsent(scorerId, k -> new ArrayList<>()).add(
                                new Sample(scorerId,
                                           rs.getString("symbol"),
                                           rs.getString("narrative"),
                                           rs.getString("breakdown_text")));
                    }
                }
            }
        }

        LlamaClient llama = LlamaClient.fromEnv();
        ObjectMapper json = new ObjectMapper();

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
                String userPrompt = buildUserPrompt(s, entry);
                long callT0 = System.nanoTime();
                String interpretation = llama.chat(SYSTEM_PROMPT, userPrompt, SamplingParams.RENDER).trim();
                long callMs = (System.nanoTime() - callT0) / 1_000_000L;

                System.out.println("──────────────────────────────────────────────────────────────────────");
                System.out.printf("[%d] %s · %s%n", total, scorerId, s.symbol);
                System.out.println("LAYER-2:");
                System.out.println("  " + wrap(s.narrative, 72, "  "));
                System.out.println("LAYER-0 (interpretation, " + callMs + " ms):");
                System.out.println("  " + wrap(interpretation, 72, "  "));
                System.out.println();
            }
        }

        long elapsedSec = (System.nanoTime() - t0) / 1_000_000_000L;
        System.out.println("──────────────────────────────────────────────────────────────────────");
        System.out.printf("done — %d interpretations, %d sec total%n", total, elapsedSec);
    }

    private static String buildUserPrompt(final Sample s, final Catalog.Entry entry) {
        return  "Scorer: " + s.scorerId + "\n\n"
              + "Layer-2 narration:\n  " + s.narrative + "\n\n"
              + "Catalog entry for this scorer:\n"
              + "  mechanism: " + entry.mechanism() + "\n"
              + "  canonical_interpretation: " + entry.canonicalInterpretation() + "\n\n"
              + "Now write the one-sentence interpretation for this specific event.";
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

    private record Sample(String scorerId, String symbol, String narrative, String breakdownText) {}
}
