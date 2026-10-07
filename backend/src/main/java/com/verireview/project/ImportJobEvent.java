package com.verireview.project;

import java.util.UUID;

/**
 * Event published after transaction commit to trigger async import job processing.
 */
public class ImportJobEvent {

  private final UUID jobId;
  private final UUID ownerId;
  private final ImportJobType type;

  public ImportJobEvent(UUID jobId, UUID ownerId, ImportJobType type) {
    this.jobId = jobId;
    this.ownerId = ownerId;
    this.type = type;
  }

  public UUID getJobId() {
    return jobId;
  }

  public UUID getOwnerId() {
    return ownerId;
  }

  public ImportJobType getType() {
    return type;
  }

  public enum ImportJobType {
    ZIP, GITHUB
  }
}