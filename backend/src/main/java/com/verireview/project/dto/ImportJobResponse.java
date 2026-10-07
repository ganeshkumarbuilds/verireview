package com.verireview.project.dto;

import com.verireview.project.ImportJobStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * Response DTO for import job status.
 */
public record ImportJobResponse(
    UUID id,
    UUID projectId,
    String name,
    ImportJobStatus status,
    long filesProcessed,
    long filesTotal,
    long bytesProcessed,
    long bytesTotal,
    String currentStep,
    String errorMessage,
    Instant createdAt,
    Instant startedAt,
    Instant finishedAt,
    Long durationMs
) {
  public static ImportJobResponse from(com.verireview.project.ImportJob job) {
    return new ImportJobResponse(
        job.getId(),
        job.getProject() != null ? job.getProject().getId() : null,
        job.getName(),
        job.getStatus(),
        job.getFilesProcessed(),
        job.getFilesTotal(),
        job.getBytesProcessed(),
        job.getBytesTotal(),
        job.getCurrentStep(),
        job.getErrorMessage(),
        job.getCreatedAt(),
        job.getStartedAt(),
        job.getFinishedAt(),
        job.getDurationMs()
    );
  }
}