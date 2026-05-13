package com.longexposure.temporal.activities;

import com.longexposure.validation.BboValidationResult;
import com.longexposure.validation.DplsBboCrossValidator;
import com.longexposure.wire.PriorClose;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public final class DplsTopsValidatorActivityImpl implements DplsTopsValidatorActivity {

    private static final Logger LOG = LoggerFactory.getLogger(DplsTopsValidatorActivityImpl.class);

    @Override
    public ValidationLegResult validate(final String dplsFilePath, final String topsFilePath) {
        LOG.info("DPLS→TOPS BBO start  dpls={} tops={}", dplsFilePath, topsFilePath);
        Map<String, Long> roundLots = RoundLots.fromEnv();
        long t0 = System.nanoTime();
        try {
            BboValidationResult r = new DplsBboCrossValidator(roundLots).run(
                    Path.of(dplsFilePath), Path.of(topsFilePath));
            long elapsed = (System.nanoTime() - t0) / 1_000_000L;
            LOG.info("DPLS→TOPS done  matched={} mismatched={} rate={} elapsed_ms={}",
                    r.matched(), r.mismatched(), r.matchRate(), elapsed);
            return new ValidationLegResult(
                    r.totalQuotesCompared(), r.matched(), r.mismatched(),
                    r.matchRate(), elapsed);
        } catch (Exception e) {
            throw new RuntimeException("DPLS→TOPS validator failed", e);
        }
    }

    /** Shared helper for the two BBO legs. */
    static final class RoundLots {
        static Map<String, Long> fromEnv() {
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
    }
}
