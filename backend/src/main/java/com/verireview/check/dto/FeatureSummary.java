package com.verireview.check.dto;

import java.time.Instant;

/** Summary for a single feature (GENERATE, REVIEW, FIX, VERIFY). */
public record FeatureSummary(
    String feature,
    int totalRuns,
    int running,
    int succeeded,
    int failed,
    int skipped,
    Instant lastRunAt) {
}