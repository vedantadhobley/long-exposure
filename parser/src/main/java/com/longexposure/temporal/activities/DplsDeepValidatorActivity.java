package com.longexposure.temporal.activities;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

/**
 * DPLS↔DEEP price-level cross-validation leg. The load-bearing 100%-match
 * leg of the triangle; independent of TOPS.
 */
@ActivityInterface
public interface DplsDeepValidatorActivity {
    @ActivityMethod(name = "DplsDeepValidate")
    ValidationLegResult validate(String dplsFilePath, String deepFilePath);
}
