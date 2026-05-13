package com.longexposure.temporal.activities;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

/** DPLS-derived BBO vs TOPS QuoteUpdate cross-validation leg. */
@ActivityInterface
public interface DplsTopsValidatorActivity {
    @ActivityMethod
    ValidationLegResult validate(String dplsFilePath, String topsFilePath);
}
