package com.longexposure.temporal.activities;

import com.longexposure.validation.BboValidationResult;
import com.longexposure.validation.DeepBboCrossValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Map;

public final class DeepTopsValidatorActivityImpl implements DeepTopsValidatorActivity {

    private static final Logger LOG = LoggerFactory.getLogger(DeepTopsValidatorActivityImpl.class);

    @Override
    public ValidationLegResult validate(final String deepFilePath, final String topsFilePath) {
        LOG.info("DEEP→TOPS BBO start  deep={} tops={}", deepFilePath, topsFilePath);
        Map<String, Long> roundLots = DplsTopsValidatorActivityImpl.RoundLots.fromEnv();
        long t0 = System.nanoTime();
        try {
            BboValidationResult r = new DeepBboCrossValidator(roundLots).run(
                    Path.of(deepFilePath), Path.of(topsFilePath));
            long elapsed = (System.nanoTime() - t0) / 1_000_000L;
            LOG.info("DEEP→TOPS done  matched={} mismatched={} rate={} elapsed_ms={}",
                    r.matched(), r.mismatched(), r.matchRate(), elapsed);
            return new ValidationLegResult(
                    r.totalQuotesCompared(), r.matched(), r.mismatched(),
                    r.matchRate(), elapsed);
        } catch (Exception e) {
            throw new RuntimeException("DEEP→TOPS validator failed", e);
        }
    }
}
