package com.verireview.project.dto;

import com.verireview.project.ImportJobStatus;
import java.util.UUID;

/**
 * Response DTO for async import job creation.
 * Returns minimal fields: jobId and status (always QUEUED at creation).
 */
public record ImportJobCreatedResponse(
    UUID jobId,
    ImportJobStatus status
) {
  public static ImportJobCreatedResponse from(com.verireview.project.ImportJob job) {
    return new ImportJobCreatedResponse(job.getId(), job.getStatus());
  }
}