package com.longexposure.temporal.activities;

import com.longexposure.validation.DeepVsDplsValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

public final class DplsDeepValidatorActivityImpl implements DplsDeepValidatorActivity {

    private static final Logger LOG = LoggerFactory.getLogger(DplsDeepValidatorActivityImpl.class);

    @Override
    public ValidationLegResult validate(final String dplsFilePath, final String deepFilePath) {
        LOG.info("DPLS↔DEEP price-level start  dpls={} deep={}", dplsFilePath, deepFilePath);
        long t0 = System.nanoTime();
        try {
            DeepVsDplsValidator.Result r = new DeepVsDplsValidator().run(
                    Path.of(dplsFilePath), Path.of(deepFilePath));
            long elapsed = (System.nanoTime() - t0) / 1_000_000L;
            LOG.info("DPLS↔DEEP done  matched={} mismatched={} rate={} elapsed_ms={}",
                    r.matched(), r.mismatched(), r.matchRate(), elapsed);
            return new ValidationLegResult(
                    r.deepTransactionsCompared(), r.matched(), r.mismatched(),
                    r.matchRate(), elapsed);
        } catch (Exception e) {
            throw new RuntimeException("DPLS↔DEEP validator failed", e);
        }
    }
}
