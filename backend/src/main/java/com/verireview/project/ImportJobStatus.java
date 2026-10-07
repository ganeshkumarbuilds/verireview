package com.verireview.project;

/**
 * Import job lifecycle status.
 * QUEUED → EXTRACTING → INDEXING → DONE/FAILED
 */
public enum ImportJobStatus {
  QUEUED,
  EXTRACTING,
  INDEXING,
  DONE,
  FAILED
}