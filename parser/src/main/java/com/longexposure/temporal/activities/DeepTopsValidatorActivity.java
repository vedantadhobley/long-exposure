package com.longexposure.temporal.activities;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

/** DEEP-derived BBO vs TOPS QuoteUpdate cross-validation leg. */
@ActivityInterface
public interface DeepTopsValidatorActivity {
    @ActivityMethod
    ValidationLegResult validate(String deepFilePath, String topsFilePath);
}
