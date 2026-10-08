package com.verireview.check.dto;

import java.time.Instant;
import java.util.UUID;

/** Summary of a single check run. */
public record RunSummary(
    UUID runId,
    String feature,
    String status,
    int progress,
    Instant startedAt,
    Instant finishedAt,
    Long durationMs,
    int totalIssues) {
}