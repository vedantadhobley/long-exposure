package com.longexposure.narration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Types;
import java.time.LocalDate;

/**
 * Re-runs {@link GroundingVerifier} against the rows already stored in
 * the {@code narratives} table for a given trading date, updating
 * {@code verifier_passed} and {@code verifier_notes} in place. No LLM
 * calls — the prose and blueprint are read from the existing rows.
 *
 * <p>Use after changing verifier logic: instead of paying for 25 min
 * of re-narration, this re-applies just the rubric in a few seconds.
 *
 * <p>Triggered by {@code IEX_REVERIFY=<YYYY-MM-DD>} env var in
 * {@link com.longexposure.Main}.
 */
public final class VerifierBackfill {

    private VerifierBackfill() {}

    public static void run(final String date) throws Exception {
        LocalDate trading = LocalDate.parse(date);
        System.out.println("== verifier backfill ==");
        System.out.println("trading_date: " + trading);
        System.out.println();

        GroundingVerifier verifier = new GroundingVerifier();
        ObjectMapper json = new ObjectMapper();

        int processed = 0, beforePass = 0, beforeFail = 0, afterPass = 0, afterFail = 0;
        int flipped = 0;   // was-fail-now-passes

        try (Connection conn = openConnection()) {
            String sql = """
                    SELECT event_hash, event_type, symbol, narrative,
                           blueprint::text AS blueprint_text,
                           score_breakdown::text AS breakdown_text,
                           verifier_passed AS old_passed
                    FROM narratives
                    WHERE trading_date = ?
                      AND blueprint IS NOT NULL
                    """;

            try (PreparedStatement st = conn.prepareStatement(sql)) {
                st.setObject(1, trading);
                try (ResultSet rs = st.executeQuery()) {
                    while (rs.next()) {
                        byte[] eventHash = rs.getBytes("event_hash");
                        String scorerId  = rs.getString("event_type");
                        String symbol    = rs.getString("symbol");
                        String prose     = rs.getString("narrative");
                        JsonNode blueprint = json.readTree(rs.getString("blueprint_text"));
                        JsonNode breakdown = json.readTree(rs.getString("breakdown_text"));
                        boolean oldPassed = rs.getBoolean("old_passed");

                        GroundingVerifier.Result r = verifier.verify(prose, blueprint, breakdown);
                        processed++;
                        if (oldPassed)  beforePass++; else beforeFail++;
                        if (r.passed()) afterPass++;  else afterFail++;
                        if (!oldPassed && r.passed()) {
                            flipped++;
                            System.out.printf("  FLIPPED  scorer=%-22s symbol=%-8s — old=fail, new=pass%n",
                                    scorerId, symbol);
                        } else if (oldPassed && !r.passed()) {
                            System.out.printf("  REGRESS  scorer=%-22s symbol=%-8s — old=pass, new=fail (%s)%n",
                                    scorerId, symbol, r.mismatches());
                        }

                        updateVerifierStatus(conn, eventHash, r);
                    }
                }
            }
        }

        System.out.println();
        System.out.println("== summary ==");
        System.out.printf("processed:      %d%n", processed);
        System.out.printf("before: %d pass, %d fail%n", beforePass, beforeFail);
        System.out.printf("after:  %d pass, %d fail%n", afterPass,  afterFail);
        System.out.printf("flipped fail→pass:  %d%n", flipped);
    }

    /**
     * Re-runs {@link InterpretationVerifier} against the rows already stored
     * in {@code interpretations} for a given trading date, updating
     * {@code verifier_passed} and {@code verifier_notes} in place. No LLM
     * calls — the prose + windows + breakdown are read from existing rows.
     *
     * <p>Use after changing {@link GroundingVerifier#cardinalWordNumbersIn}
     * or any other verifier-side logic that affects INTERPRET grounding.
     *
     * <p>Triggered by {@code IEX_REVERIFY_INTERP=<YYYY-MM-DD>} env var in
     * {@link com.longexposure.Main}.
     */
    public static void runInterpretations(final String date) throws Exception {
        LocalDate trading = LocalDate.parse(date);
        System.out.println("== interpretation verifier backfill ==");
        System.out.println("trading_date: " + trading);
        System.out.println();

        InterpretationVerifier verifier = new InterpretationVerifier();
        ObjectMapper json = new ObjectMapper();

        int processed = 0, beforePass = 0, beforeFail = 0, afterPass = 0, afterFail = 0;
        int flipped = 0;

        try (Connection conn = openConnection()) {
            // Join selected_events to recover the current breakdown + scorer_id.
            // For false-positive failures (the "two-sided" class) the breakdown
            // hasn't shifted since the interpretation was written, so this
            // re-verification produces the result the original call would have
            // gotten with the fixed verifier.
            String sql = """
                    SELECT i.interpretation_hash,
                           i.symbol,
                           i.interpretation,
                           i.pre_window_summary::text  AS pre_text,
                           i.post_window_summary::text AS post_text,
                           se.scorer_id,
                           se.breakdown::text          AS breakdown_text,
                           i.verifier_passed           AS old_passed
                    FROM interpretations i
                    JOIN selected_events se ON se.selected_id = i.selected_id
                    WHERE i.trading_date = ?
                    """;

            try (PreparedStatement st = conn.prepareStatement(sql)) {
                st.setObject(1, trading);
                try (ResultSet rs = st.executeQuery()) {
                    while (rs.next()) {
                        byte[] interpHash = rs.getBytes("interpretation_hash");
                        String symbol    = rs.getString("symbol");
                        String prose     = rs.getString("interpretation");
                        String scorerId  = rs.getString("scorer_id");
                        JsonNode pre       = json.readTree(rs.getString("pre_text"));
                        JsonNode post      = json.readTree(rs.getString("post_text"));
                        JsonNode breakdown = json.readTree(rs.getString("breakdown_text"));
                        boolean oldPassed = rs.getBoolean("old_passed");

                        InterpretationVerifier.Result r = verifier.verify(
                                prose, breakdown, pre, post, symbol, scorerId);
                        processed++;
                        if (oldPassed)  beforePass++; else beforeFail++;
                        if (r.passed()) afterPass++;  else afterFail++;
                        if (!oldPassed && r.passed()) {
                            flipped++;
                            System.out.printf("  FLIPPED  scorer=%-22s symbol=%-8s — old=fail, new=pass%n",
                                    scorerId, symbol);
                        } else if (oldPassed && !r.passed()) {
                            System.out.printf("  REGRESS  scorer=%-22s symbol=%-8s — old=pass, new=fail (%s)%n",
                                    scorerId, symbol, r.mismatches());
                        }

                        updateInterpVerifierStatus(conn, interpHash, r);
                    }
                }
            }
        }

        System.out.println();
        System.out.println("== summary ==");
        System.out.printf("processed:          %d%n", processed);
        System.out.printf("before: %d pass, %d fail%n", beforePass, beforeFail);
        System.out.printf("after:  %d pass, %d fail%n", afterPass,  afterFail);
        System.out.printf("flipped fail→pass:  %d%n", flipped);
    }

    private static void updateInterpVerifierStatus(final Connection conn,
                                                    final byte[] interpHash,
                                                    final InterpretationVerifier.Result r) throws Exception {
        ObjectMapper json = new ObjectMapper();
        String notes = json.createObjectNode()
                .put("numbers_checked", r.numbersChecked())
                .set("mismatches", json.valueToTree(r.mismatches()))
                .toString();

        String sql = "UPDATE interpretations SET verifier_passed = ?, verifier_notes = ?::jsonb "
                   + "WHERE interpretation_hash = ?";
        try (PreparedStatement st = conn.prepareStatement(sql)) {
            st.setBoolean(1, r.passed());
            if (notes == null) st.setNull(2, Types.OTHER); else st.setString(2, notes);
            st.setBytes(3, interpHash);
            st.executeUpdate();
        }
    }

    private static void updateVerifierStatus(final Connection conn,
                                             final byte[] eventHash,
                                             final GroundingVerifier.Result r) throws Exception {
        ObjectMapper json = new ObjectMapper();
        String notes = json.createObjectNode()
                .put("numbers_checked", r.numbersChecked())
                .set("mismatches", json.valueToTree(r.mismatches()))
                .toString();

        String sql = "UPDATE narratives SET verifier_passed = ?, verifier_notes = ?::jsonb "
                   + "WHERE event_hash = ?";
        try (PreparedStatement st = conn.prepareStatement(sql)) {
            st.setBoolean(1, r.passed());
            if (notes == null) st.setNull(2, Types.OTHER); else st.setString(2, notes);
            st.setBytes(3, eventHash);
            st.executeUpdate();
        }
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
