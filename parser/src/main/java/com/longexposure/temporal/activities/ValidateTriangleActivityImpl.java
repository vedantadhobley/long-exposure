package com.longexposure.temporal.activities;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.longexposure.validation.BboValidationResult;
import com.longexposure.validation.DeepBboCrossValidator;
import com.longexposure.validation.DeepVsDplsValidator;
import com.longexposure.validation.DplsBboCrossValidator;
import com.longexposure.wire.PriorClose;
import io.temporal.activity.Activity;
import io.temporal.activity.ActivityExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Types;
import java.time.LocalDate;
import java.util.Map;

/**
 * Implementation of {@link ValidateTriangleActivity}.
 *
 * <p>Runs three correctness checks sequentially:
 * <ol>
 *   <li>DPLS↔DEEP price-level — the load-bearing 100% leg
 *   <li>DPLS→TOPS BBO — expected ≥ 99%
 *   <li>DEEP→TOPS BBO — expected ≥ 99% (parsers bug-equivalent, should match DPLS)
 * </ol>
 *
 * <p>Round-lots loaded from {@code IEX_PRIOR_CLOSE_CSV} env if set;
 * otherwise validators fall back to per-level Reg-NMS tier.
 *
 * <p>Writes one row to {@code validation_runs} keyed by trading_date,
 * upserted on conflict so reruns overwrite. Returns the
 * {@link Result} so the workflow can short-circuit (skip cleanup,
 * mark unverified) on threshold failure.
 */
public final class ValidateTriangleActivityImpl implements ValidateTriangleActivity {

    private static final Logger LOG = LoggerFactory.getLogger(ValidateTriangleActivityImpl.class);

    /** BBO-leg pass threshold. Empirical floor across recent days is ~99.4%. */
    private static final double BBO_THRESHOLD = 0.99;

    /** Price-level leg pass threshold. Should be ~100% (modulo same-ns multi-txn). */
    private static final double PRICE_LEVEL_THRESHOLD = 0.9999;

    private final ObjectMapper json = new ObjectMapper();

    @Override
    public Result validate(
            final String dplsFilePath,
            final String deepFilePath,
            final String topsFilePath,
            final LocalDate targetDate) {

        ActivityExecutionContext ctx = Activity.getExecutionContext();
        long t0 = System.nanoTime();

        Path dpls = Path.of(dplsFilePath);
        Path deep = Path.of(deepFilePath);
        Path tops = Path.of(topsFilePath);

        Map<String, Long> roundLots = loadRoundLots();
        ObjectNode notes = json.createObjectNode();

        Double dplsDeepRate = null;
        Double dplsTopsRate = null;
        Double deepTopsRate = null;

        try {
            LOG.info("DPLS↔DEEP price-level start  date={}", targetDate);
            DeepVsDplsValidator.Result r1 = new DeepVsDplsValidator().run(dpls, deep);
            dplsDeepRate = r1.matchRate();
            notes.put("dpls_deep_compared", r1.deepTransactionsCompared());
            notes.put("dpls_deep_matched", r1.matched());
            notes.put("dpls_deep_mismatched", r1.mismatched());
            LOG.info("DPLS↔DEEP done  matched={} mismatched={} rate={}",
                    r1.matched(), r1.mismatched(), dplsDeepRate);
            ctx.heartbeat("dpls_deep_done");
        } catch (Exception e) {
            LOG.error("DPLS↔DEEP failed", e);
            notes.put("dpls_deep_error", e.getMessage());
        }

        try {
            LOG.info("DPLS→TOPS BBO start  date={}", targetDate);
            BboValidationResult r2 = new DplsBboCrossValidator(roundLots).run(dpls, tops);
            dplsTopsRate = r2.matchRate();
            notes.put("dpls_tops_compared", r2.totalQuotesCompared());
            notes.put("dpls_tops_matched", r2.matched());
            notes.put("dpls_tops_mismatched", r2.mismatched());
            LOG.info("DPLS→TOPS done  matched={} mismatched={} rate={}",
                    r2.matched(), r2.mismatched(), dplsTopsRate);
            ctx.heartbeat("dpls_tops_done");
        } catch (Exception e) {
            LOG.error("DPLS→TOPS failed", e);
            notes.put("dpls_tops_error", e.getMessage());
        }

        try {
            LOG.info("DEEP→TOPS BBO start  date={}", targetDate);
            BboValidationResult r3 = new DeepBboCrossValidator(roundLots).run(deep, tops);
            deepTopsRate = r3.matchRate();
            notes.put("deep_tops_compared", r3.totalQuotesCompared());
            notes.put("deep_tops_matched", r3.matched());
            notes.put("deep_tops_mismatched", r3.mismatched());
            LOG.info("DEEP→TOPS done  matched={} mismatched={} rate={}",
                    r3.matched(), r3.mismatched(), deepTopsRate);
            ctx.heartbeat("deep_tops_done");
        } catch (Exception e) {
            LOG.error("DEEP→TOPS failed", e);
            notes.put("deep_tops_error", e.getMessage());
        }

        long elapsedSeconds = (System.nanoTime() - t0) / 1_000_000_000L;
        String status = classify(dplsDeepRate, dplsTopsRate, deepTopsRate);
        String notesJson = notes.toString();

        // Persist to validation_runs (upsert on trading_date).
        try (Connection conn = openConnection()) {
            upsertValidationRun(conn, targetDate, dplsTopsRate, deepTopsRate, dplsDeepRate,
                    null, (int) elapsedSeconds, status, notesJson);
        } catch (Exception e) {
            LOG.error("failed to persist validation_runs row", e);
            // Don't fail the activity — workflow still gets the in-memory result
        }

        return new Result(dplsTopsRate, deepTopsRate, dplsDeepRate,
                false, elapsedSeconds, status, notesJson);
    }

    private static String classify(final Double dplsDeep, final Double dplsTops, final Double deepTops) {
        if (dplsDeep == null || dplsTops == null || deepTops == null) return "failed";
        if (dplsDeep < PRICE_LEVEL_THRESHOLD) return "below_threshold";
        if (dplsTops < BBO_THRESHOLD || deepTops < BBO_THRESHOLD) return "below_threshold";
        return "passed";
    }

    /**
     * Load per-symbol round-lots from {@code IEX_PRIOR_CLOSE_CSV} if set,
     * otherwise return an empty map so validators fall back to the
     * Reg-NMS tier defaults.
     */
    private static Map<String, Long> loadRoundLots() {
        String path = System.getenv("IEX_PRIOR_CLOSE_CSV");
        if (path == null || path.isBlank()) return Map.of();
        try {
            if (!Files.exists(Path.of(path))) {
                LOG.warn("IEX_PRIOR_CLOSE_CSV set but file missing  path={}", path);
                return Map.of();
            }
            return PriorClose.roundLotBySymbol(PriorClose.loadCsv(Path.of(path)));
        } catch (Exception e) {
            LOG.warn("failed to load prior-close CSV  path={} err={}", path, e.getMessage());
            return Map.of();
        }
    }

    private static void upsertValidationRun(final Connection conn,
                                            final LocalDate date,
                                            final Double dplsTops,
                                            final Double deepTops,
                                            final Double dplsDeep,
                                            final Boolean tradeVolumeMatch,
                                            final int elapsedSeconds,
                                            final String status,
                                            final String notesJson) throws Exception {
        String sql = "INSERT INTO validation_runs ("
                + "trading_date, dpls_tops_match_pct, deep_tops_match_pct, "
                + "dpls_deep_match_pct, trade_volume_match, elapsed_seconds, status, notes) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb) "
                + "ON CONFLICT (trading_date) DO UPDATE SET "
                + "run_at = NOW(), "
                + "dpls_tops_match_pct = EXCLUDED.dpls_tops_match_pct, "
                + "deep_tops_match_pct = EXCLUDED.deep_tops_match_pct, "
                + "dpls_deep_match_pct = EXCLUDED.dpls_deep_match_pct, "
                + "trade_volume_match = EXCLUDED.trade_volume_match, "
                + "elapsed_seconds = EXCLUDED.elapsed_seconds, "
                + "status = EXCLUDED.status, "
                + "notes = EXCLUDED.notes";
        try (PreparedStatement st = conn.prepareStatement(sql)) {
            st.setObject(1, date);
            if (dplsTops != null) st.setDouble(2, dplsTops); else st.setNull(2, Types.DOUBLE);
            if (deepTops != null) st.setDouble(3, deepTops); else st.setNull(3, Types.DOUBLE);
            if (dplsDeep != null) st.setDouble(4, dplsDeep); else st.setNull(4, Types.DOUBLE);
            if (tradeVolumeMatch != null) st.setBoolean(5, tradeVolumeMatch); else st.setNull(5, Types.BOOLEAN);
            st.setInt(6, elapsedSeconds);
            st.setString(7, status);
            st.setString(8, notesJson);
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
