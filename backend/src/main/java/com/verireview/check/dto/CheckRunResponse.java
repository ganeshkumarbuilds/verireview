package com.verireview.check.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Check run response with optional steps and gates. */
public record CheckRunResponse(
    UUID id,
    UUID projectId,
    UUID generationId,
    String feature,
    String status,
    int progress,
    String currentStep,
    String errorMessage,
    Instant startedAt,
    Instant finishedAt,
    Long durationMs,
    int severityCritical,
    int severityHigh,
    int severityMedium,
    int severityLow,
    int severityInfo,
    Instant createdAt,
    Instant updatedAt,
    List<CheckStepResponse> steps,
    List<CheckRunGateResponse> gates) {

  public CheckRunResponse(
      UUID id,
      UUID projectId,
      UUID generationId,
      String feature,
      String status,
      int progress,
      String currentStep,
      String errorMessage,
      Instant startedAt,
      Instant finishedAt,
      Long durationMs,
      int severityCritical,
      int severityHigh,
      int severityMedium,
      int severityLow,
      int severityInfo,
      Instant createdAt,
      Instant updatedAt) {
    this(id, projectId, generationId, feature, status, progress, currentStep, errorMessage,
        startedAt, finishedAt, durationMs, severityCritical, severityHigh, severityMedium,
        severityLow, severityInfo, createdAt, updatedAt, List.of(), List.of());
  }
}