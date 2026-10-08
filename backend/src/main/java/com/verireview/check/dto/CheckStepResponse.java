package com.verireview.check.dto;

import java.time.Instant;
import java.util.UUID;

/** Check step response. */
public record CheckStepResponse(
    UUID id,
    UUID checkRunId,
    int stepOrder,
    String name,
    String status,
    int progress,
    String currentMessage,
    String errorMessage,
    Instant startedAt,
    Instant finishedAt,
    Long durationMs,
    String logTail) {
}