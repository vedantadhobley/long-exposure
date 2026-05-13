package com.longexposure.temporal.activities;

/**
 * Result of one leg of the validation triangle. All counts are summed
 * over the full trading day; {@code matchRate} is in [0.0, 1.0].
 */
public record ValidationLegResult(
        long compared,
        long matched,
        long mismatched,
        double matchRate,
        long elapsedMs) {}
